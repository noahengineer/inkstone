// A byte layout's masks, shifts and field offsets ARE the specification — 0xFF is not a
// magic number here, it is a byte, and `shl 8` is not a tunable, it is the next byte along.
// Naming each one would add a constant per bit position and make the layout harder to check
// against the format it implements, which is the opposite of what the rule is for.
@file:Suppress("MagicNumber")

package dev.inkstone.core.storage

import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * SPEC §5.1 — `journal.ops`. **This is what autosave touches.**
 *
 * > Autosave cannot rewrite a 40 MB PDF every two seconds, so the PDF is not the
 * > working format. Autosave = append to `journal.ops`. Cheap, bounded, crash-safe.
 *
 * ## The framing, and why it is not JSON lines
 *
 * ```
 * header:  "INKJ" | version u32
 * record:  metaLen u32 | blobLen u32 | crc32 u32 | meta (JSON) | blob (geometry)
 * ```
 *
 * A stroke's geometry is binary (SPEC §5.4). Base64 in a JSON line would add a third
 * to every stroke on the hot path — the one write that happens while the user is
 * writing. So each record carries its ops' metadata as JSON and their geometry
 * verbatim after it, with the JSON referencing offsets into that blob.
 *
 * ## Crash safety
 *
 * ROADMAP M2: *"force-stop mid-stroke → reopen → at most the in-progress stroke lost"*.
 *
 * An append is the only non-atomic write in this app, deliberately (see
 * [FileSystem.appendBytes]). A process killed mid-append leaves a **torn tail**: a
 * partial header, or lengths that run past the end, or bytes that fail the checksum.
 * [read] stops at the first record that is not whole and reports where, so everything
 * written before the kill survives and the incomplete record — which is the stroke
 * that was still being drawn — is the only loss.
 *
 * The checksum matters beyond truncation. An append interrupted by a power loss can
 * leave a record that is the right *length* and the wrong *content*, and a plain
 * length check would accept it and hand a corrupt op to the fold.
 */
internal object Journal {

    private const val MAGIC = "INKJ"
    private const val VERSION = 1L
    const val HEADER_SIZE = 8
    private const val RECORD_HEADER_SIZE = 12

    private const val F_REV = "rev"
    private const val F_OPS = "ops"
    private const val F_DOC = "doc"

    fun header(): ByteArray = ByteBuffer(HEADER_SIZE).apply {
        text(MAGIC)
        u32(VERSION)
    }.toByteArray()

    /**
     * One record's bytes, ready to append.
     *
     * A whole flush is a single record and therefore a single append: 40 strokes and
     * their geometry cost one syscall, not 40. That is what keeps a two-second autosave
     * off the ink path's budget.
     *
     * @param document the document's metadata when it changed in this flush — page
     *   order, title, tombstones. Absolute, and small: a crash then loses no page
     *   management either, and the newest record wins on read.
     */
    fun record(revision: Long, ops: List<PageOp>, pages: Map<PageOp, PageId>, document: JsonObject?): ByteArray {
        val geometry = GeometryWriter()
        val meta = buildJsonObject {
            put(F_REV, JsonPrimitive(revision))
            if (document != null) put(F_DOC, document)
            put(
                F_OPS,
                buildJsonArray {
                    for (op in ops) add(OpCodec.encode(op, geometry, pages[op]?.value))
                },
            )
        }
        val metaBytes = OpCodec.json.encodeToString(JsonObject.serializer(), meta).encodeToByteArray()
        val blobBytes = geometry.toByteArray()

        return ByteBuffer(RECORD_HEADER_SIZE + metaBytes.size + blobBytes.size).apply {
            u32(metaBytes.size.toLong())
            u32(blobBytes.size.toLong())
            u32(Crc32.of(metaBytes, blobBytes))
            bytes(metaBytes)
            bytes(blobBytes)
        }.toByteArray()
    }

    /**
     * Read every whole record.
     *
     * @return the ops in file order — which is *append* order, not clock order. The
     *   fold sorts (SPEC §4.3); this must not, because the file's own order is the
     *   evidence of what happened when a record turns out to be torn.
     */
    @Suppress(
        // A framed-record parser. Every branch is a distinct way a record can be malformed, and
        // each one has to stop the scan at exactly the right byte for the torn-tail guarantee to
        // hold. Splitting it into helpers would move the cursor bookkeeping between functions,
        // which is the part that must be obviously correct.
        "CyclomaticComplexMethod",
        "LoopWithTooManyJumpStatements",
    )
    suspend fun read(fs: FileSystem, path: String): JournalContents {
        if (!fs.exists(path)) return JournalContents.Empty
        val bytes = fs.readBytes(path)
        if (bytes.size < HEADER_SIZE) return JournalContents.Empty.copy(truncatedAtByte = 0)
        if (bytes.decodeToString(0, MAGIC.length) != MAGIC) {
            // Not our file. Refusing is right: writing more records into it would be
            // appending to something else's data.
            error("$path is not an Inkstone journal")
        }
        val version = bytes.readU32(MAGIC.length)
        require(version <= VERSION) { "journal at $path is version $version, this build reads $VERSION" }

        val ops = ArrayList<JournaledOp>()
        var revision = 0L
        var document: JsonObject? = null
        var cursor = HEADER_SIZE
        var truncatedAt: Int? = null

        while (cursor < bytes.size) {
            if (cursor + RECORD_HEADER_SIZE > bytes.size) {
                truncatedAt = cursor
                break
            }
            val metaLength = bytes.readU32(cursor).toInt()
            val blobLength = bytes.readU32(cursor + 4).toInt()
            val expectedCrc = bytes.readU32(cursor + 8)
            val metaStart = cursor + RECORD_HEADER_SIZE
            val blobStart = metaStart + metaLength
            val recordEnd = blobStart + blobLength

            if (metaLength < 0 || blobLength < 0 || recordEnd > bytes.size) {
                truncatedAt = cursor
                break
            }
            val metaBytes = bytes.copyOfRange(metaStart, blobStart)
            val blobBytes = bytes.copyOfRange(blobStart, recordEnd)
            if (Crc32.of(metaBytes, blobBytes) != expectedCrc) {
                // The right length and the wrong content — an interrupted append, not a
                // truncated one. Everything after it is suspect too, so stop here.
                truncatedAt = cursor
                break
            }

            val meta = OpCodec.json.parseToJsonElement(metaBytes.decodeToString()).jsonObject
            val geometry = GeometryReader(blobBytes)
            revision = maxOf(revision, meta[F_REV]?.jsonPrimitive?.long ?: revision)
            meta[F_DOC]?.let { document = it.jsonObject }
            for (element in meta.getValue(F_OPS).jsonArray) {
                val decoded = OpCodec.decode(element.jsonObject, geometry)
                ops += JournaledOp(decoded.op, decoded.pageId?.let(::PageId))
            }
            cursor = recordEnd
        }

        return JournalContents(
            ops = ops,
            revision = revision,
            document = document,
            truncatedAtByte = truncatedAt,
            wholeBytes = truncatedAt ?: bytes.size,
        )
    }
}

internal data class JournaledOp(val op: PageOp, val pageId: PageId?)

/**
 * @property truncatedAtByte where an incomplete record started, or null when the
 *   journal was whole. Non-null after a crash mid-stroke, which is the expected
 *   outcome of the acceptance test rather than a defect.
 * @property document the newest document metadata any record carried.
 */
internal data class JournalContents(
    val ops: List<JournaledOp>,
    val revision: Long,
    val document: JsonObject?,
    val truncatedAtByte: Int?,
    val wholeBytes: Int,
) {
    companion object {
        val Empty: JournalContents = JournalContents(emptyList(), 0L, null, null, Journal.HEADER_SIZE)
    }
}

private fun JsonObject.getValue(key: String) = requireNotNull(this[key]) { "journal record has no '$key'" }
