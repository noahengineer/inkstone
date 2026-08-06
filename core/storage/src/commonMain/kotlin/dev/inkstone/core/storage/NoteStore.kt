package dev.inkstone.core.storage

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.Document
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageOrder
import dev.inkstone.core.model.PageRef
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.UndoEntry
import dev.inkstone.core.model.foldPage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * SPEC §5.1 — reads and writes a note's working files. **The journal model lives here.**
 *
 * - **Autosave = [appendOps].** One append to `journal.ops`. The bundle is not
 *   rewritten, the PDF is not rewritten, nothing is re-serialised.
 * - **[materialize] rewrites the bundle** and resets the journal. Runs on close,
 *   before upload, and on idle-while-charging — never on the ink path.
 * - **[open] = bundle, then journal replayed on top.**
 *
 * Every write except the journal append is write-temp-then-atomic-rename, because that
 * is [FileSystem]'s only offer (CLAUDE.md rule 10). The append is the deliberate
 * exception: an append cut short by a process kill costs the in-progress stroke and
 * nothing else, and [Journal] detects the torn tail on the way back in.
 *
 * ## The invariant
 *
 * SPEC §5.1: *"`document.pdf` is never more than one editing session stale. State this
 * in the code as an assertion."* [assertNotStale] is that assertion; the session calls
 * it when it closes a note.
 */
public class NoteStore(private val fs: FileSystem, private val clock: Clock, private val ids: IdFactory) {

    // -----------------------------------------------------------------------
    // Create
    // -----------------------------------------------------------------------

    /** A new note with [pageCount] blank pages, written to disk before it returns. */
    public suspend fun create(
        title: String,
        deviceId: String,
        pageCount: Int = 1,
        size: PageSize = PageSize.Letter,
        background: Background = Background.Blank,
    ): OpenNote {
        require(pageCount >= 1) { "a note needs at least one page" }
        val now = clock.nowEpochMillis()
        val keys = FractionalKey.sequence(pageCount)
        val pages = List(pageCount) { PageRef(ids.newPageId(), keys[it]) }
        val document = Document(
            id = ids.newDocumentId(),
            title = title,
            pages = pages,
            localRev = 1L,
            deviceId = deviceId,
            createdEpochMillis = now,
            modifiedEpochMillis = now,
        )
        val contents = BundleContents(
            document = document,
            pages = pages.associate { it.id to PageContents(size, background, emptyList()) },
            highestClockCounter = 0L,
        )

        fs.createDirectories(NoteLayout.noteDir(document.id))
        fs.writeBytesAtomically(NoteLayout.bundle(document.id), BundleWriter.write(contents))
        fs.writeBytesAtomically(NoteLayout.journal(document.id), Journal.header())

        return OpenNote(
            document = document,
            journalOps = emptyMap(),
            highestClockCounter = 0L,
            journalBytes = Journal.HEADER_SIZE.toLong(),
            journalWasTruncated = false,
            reader = null,
        )
    }

    // -----------------------------------------------------------------------
    // Open
    // -----------------------------------------------------------------------

    /**
     * SPEC §5.1 — *"load the embedded bundle, then replay `journal.ops` on top"*.
     *
     * Neither step reads a page's contents. The manifest gives the document and its
     * page order; the journal gives whatever has happened since the last
     * materialization. A page's ops are read when the page is drawn ([loadPage]), which
     * is what makes a 200-page notebook open in under two seconds.
     *
     * @return null when there is no such note.
     */
    public suspend fun open(id: DocumentId): OpenNote? {
        val bundlePath = NoteLayout.bundle(id)
        if (!fs.exists(bundlePath)) return null
        val reader = BundleReader.open(FileByteSource(fs, bundlePath)) ?: return null

        val journal = Journal.read(fs, NoteLayout.journal(id))
        val opsByPage = LinkedHashMap<PageId, MutableList<PageOp>>()
        var highestCounter = reader.highestClockCounter
        for (entry in journal.ops) {
            val pageId = entry.pageId ?: continue
            opsByPage.getOrPut(pageId) { ArrayList() } += entry.op
            highestCounter = maxOf(highestCounter, entry.op.clock.counter)
        }

        // The journal's document metadata is newer than the manifest's by construction —
        // the manifest was written at the last materialization, the journal after it.
        val document = journal.document
            ?.let { applyDocumentRecord(reader.document, it) }
            ?: reader.document
        val repaired = document.copy(
            pages = PageOrder.repair(document.pages),
            localRev = maxOf(document.localRev, journal.revision),
        )

        return OpenNote(
            document = repaired,
            journalOps = opsByPage,
            highestClockCounter = highestCounter,
            journalBytes = journal.wholeBytes.toLong(),
            journalWasTruncated = journal.truncatedAtByte != null,
            reader = reader,
        )
    }

    /**
     * Re-read the journal into [note].
     *
     * Called when a page is folded again after having been evicted: the ops written since
     * the note was opened are in the journal file, not in the [OpenNote] captured at open
     * time. Re-reading from disk rather than holding every op of the session in memory is
     * the point — geometry blobs are the bulk of a session's writes, and keeping them all
     * would give back exactly the memory the page cache is bounded to save.
     */
    public suspend fun reloadJournal(note: OpenNote): OpenNote {
        val journal = Journal.read(fs, NoteLayout.journal(note.document.id))
        val opsByPage = LinkedHashMap<PageId, MutableList<PageOp>>()
        for (entry in journal.ops) {
            val pageId = entry.pageId ?: continue
            opsByPage.getOrPut(pageId) { ArrayList() } += entry.op
        }
        return note.copy(
            journalOps = opsByPage,
            journalBytes = journal.wholeBytes.toLong(),
            journalWasTruncated = note.journalWasTruncated || journal.truncatedAtByte != null,
        )
    }

    /**
     * One page's whole log: what the bundle holds, plus what the journal added.
     *
     * Two reads, both scoped to this page. Called when a page comes on screen and
     * discarded when it goes away — nothing here keeps a reference, so a scrolled-past
     * page is collectable (ROADMAP M2: *"memory doesn't grow linearly with page
     * count"*).
     */
    public suspend fun loadPage(note: OpenNote, pageId: PageId): LoadedPage {
        val stored = note.reader?.page(pageId)
        val journalOps = note.journalOps[pageId].orEmpty()
        return LoadedPage(
            pageId = pageId,
            size = stored?.size ?: PageSize.Letter,
            background = stored?.background ?: Background.Blank,
            ops = stored?.ops.orEmpty() + journalOps,
        )
    }

    // -----------------------------------------------------------------------
    // Autosave
    // -----------------------------------------------------------------------

    /**
     * Append a flush to the journal. **This is autosave** (SPEC §5.1).
     *
     * One append, one record, whatever the flush contains. Nothing else on disk is
     * touched: not the bundle, not the PDF.
     *
     * @param pages which page each op belongs to. A map rather than a field on the op
     *   because a page is a property of where an op is *stored*, not of the op — the
     *   same op vocabulary is used inside a page file, where the page is implied.
     * @param document the document's metadata when page order, title or tombstones
     *   changed in this flush. Absolute and tiny, so a crash loses no page management.
     * @return the note's new `local_rev` (SPEC §7.6 — bumped on every committed change).
     */
    public suspend fun appendOps(
        note: OpenNote,
        ops: List<PageOp>,
        pages: Map<PageOp, PageId>,
        document: Document? = null,
    ): Long {
        if (ops.isEmpty() && document == null) return note.document.localRev
        val revision = note.document.localRev + 1
        val record = Journal.record(revision, ops, pages, document?.let(::documentRecord))
        fs.appendBytes(NoteLayout.journal(note.document.id), record)
        return revision
    }

    // -----------------------------------------------------------------------
    // Materialize
    // -----------------------------------------------------------------------

    /**
     * Fold the journal into the bundle and reset the journal.
     *
     * Order matters and is not negotiable: the bundle is written and renamed into place
     * *first*, and only then is the journal reset. A crash between the two replays a
     * journal whose ops are already in the bundle, which is harmless — the fold is
     * idempotent over absolute registers. A crash the other way round would lose them.
     *
     * @param pages every page's whole log, as it should appear in the new bundle.
     */
    public suspend fun materialize(note: OpenNote, pages: Map<PageId, PageContents>): OpenNote {
        val id = note.document.id
        val highest = pages.values.flatMap { it.ops }.maxOfOrNull { it.clock.counter }
            ?: note.highestClockCounter
        val document = note.document.copy(modifiedEpochMillis = clock.nowEpochMillis())
        val contents = BundleContents(
            document = document,
            pages = pages,
            highestClockCounter = maxOf(highest, note.highestClockCounter),
            carried = note.reader?.carried() ?: emptyMap(),
        )

        fs.writeBytesAtomically(NoteLayout.bundle(id), BundleWriter.write(contents))
        fs.writeBytesAtomically(NoteLayout.journal(id), Journal.header())

        val reader = BundleReader.open(FileByteSource(fs, NoteLayout.bundle(id)))
        return note.copy(
            document = document,
            journalOps = emptyMap(),
            highestClockCounter = contents.highestClockCounter,
            journalBytes = Journal.HEADER_SIZE.toLong(),
            journalWasTruncated = false,
            reader = reader,
        )
    }

    /**
     * SPEC §5.1's invariant, as an assertion: the published file is never more than one
     * editing session stale.
     *
     * A journal that still has content when a note closes means materialization did not
     * run, which is the state that would make `document.pdf` two sessions old.
     */
    public suspend fun assertNotStale(id: DocumentId) {
        val journalSize = fs.size(NoteLayout.journal(id))
        check(journalSize <= Journal.HEADER_SIZE) {
            "note ${id.value} was closed with $journalSize bytes of unmaterialized journal — " +
                "document.pdf would be more than one session stale (SPEC §5.1)"
        }
    }

    // -----------------------------------------------------------------------
    // Compaction snapshot (SPEC §4.7)
    // -----------------------------------------------------------------------

    /**
     * Write a page's folded state as a cache.
     *
     * The snapshot is stored as a bundle whose single page holds one `AddObject` per
     * live object — i.e. the fold re-expressed as the shortest log that produces it.
     * Reusing the bundle format means one serialiser, one set of format tests, and a
     * snapshot that is readable by anything that can read a note.
     */
    public suspend fun writeSnapshot(id: DocumentId, page: FoldedPage, deviceId: String) {
        val clockValue = page.throughClock ?: return
        val ops = page.objects.map { obj ->
            PageOp.AddObject(opId = ids.newOpId(), clock = clockValue, obj = obj)
        }
        val document = Document(
            id = id,
            title = "",
            pages = listOf(PageRef(page.pageId, FractionalKey.Initial)),
            deviceId = deviceId,
        )
        val contents = BundleContents(
            document = document,
            pages = mapOf(page.pageId to PageContents(page.size, page.background, ops)),
            highestClockCounter = clockValue.counter,
        )
        fs.writeBytesAtomically(NoteLayout.snapshot(id), BundleWriter.write(contents))
    }

    public suspend fun readSnapshot(id: DocumentId, pageId: PageId): FoldedPage? {
        val path = NoteLayout.snapshot(id)
        if (!fs.exists(path)) return null
        val reader = BundleReader.open(FileByteSource(fs, path)) ?: return null
        val page = reader.page(pageId) ?: return null
        return foldPage(pageId, page.ops, defaultSize = page.size)
    }

    /** SPEC §4.7 — *"if an op older than the snapshot ever arrives, discard the snapshot"*. */
    public suspend fun discardSnapshot(id: DocumentId) {
        val path = NoteLayout.snapshot(id)
        if (fs.exists(path)) fs.delete(path)
    }

    // -----------------------------------------------------------------------
    // Undo stacks — local, per device (SPEC §4.6)
    // -----------------------------------------------------------------------

    public suspend fun saveUndo(id: DocumentId, undo: List<UndoEntry>, redo: List<UndoEntry>) {
        val geometry = GeometryWriter()
        val json = buildJsonObject {
            put(U_UNDO, encodeEntries(undo, geometry))
            put(U_REDO, encodeEntries(redo, geometry))
        }
        // The stacks reference geometry too — an undone stroke's AddObject carries its
        // blob — so the same offset scheme applies, with the blob appended after the
        // JSON in one file.
        val jsonBytes = OpCodec.json.encodeToString(JsonObject.serializer(), json).encodeToByteArray()
        val blob = geometry.toByteArray()
        val out = ByteBuffer(HEADER_BYTES + jsonBytes.size + blob.size).apply {
            u32(jsonBytes.size.toLong())
            bytes(jsonBytes)
            bytes(blob)
        }
        fs.writeBytesAtomically(NoteLayout.undo(id), out.toByteArray())
    }

    public suspend fun loadUndo(id: DocumentId): Pair<List<UndoEntry>, List<UndoEntry>> {
        val path = NoteLayout.undo(id)
        if (!fs.exists(path)) return emptyList<UndoEntry>() to emptyList()
        val bytes = fs.readBytes(path)
        if (bytes.size < HEADER_BYTES) return emptyList<UndoEntry>() to emptyList()
        val jsonLength = bytes.readU32(0).toInt()
        if (HEADER_BYTES + jsonLength > bytes.size) return emptyList<UndoEntry>() to emptyList()
        val json = OpCodec.json
            .parseToJsonElement(bytes.decodeToString(HEADER_BYTES, HEADER_BYTES + jsonLength))
            .jsonObject
        val geometry = GeometryReader(bytes.copyOfRange(HEADER_BYTES + jsonLength, bytes.size))
        return decodeEntries(json[U_UNDO], geometry) to decodeEntries(json[U_REDO], geometry)
    }

    private fun encodeEntries(entries: List<UndoEntry>, geometry: GeometryWriter) = buildJsonArray {
        for (entry in entries) {
            add(
                buildJsonObject {
                    put(U_FORWARD, buildJsonArray { for (op in entry.forward) add(OpCodec.encode(op, geometry)) })
                    put(U_INVERSE, buildJsonArray { for (op in entry.inverse) add(OpCodec.encode(op, geometry)) })
                },
            )
        }
    }

    private fun decodeEntries(element: kotlinx.serialization.json.JsonElement?, geometry: GeometryReader) =
        element?.jsonArray.orEmpty().map { entry ->
            val obj = entry.jsonObject
            UndoEntry(
                forward = obj[U_FORWARD]?.jsonArray.orEmpty().map { OpCodec.decode(it.jsonObject, geometry).op },
                inverse = obj[U_INVERSE]?.jsonArray.orEmpty().map { OpCodec.decode(it.jsonObject, geometry).op },
            )
        }

    // -----------------------------------------------------------------------
    // Document metadata in the journal
    // -----------------------------------------------------------------------

    private fun documentRecord(document: Document): JsonObject = buildJsonObject {
        put(Bundle.F_TITLE, JsonPrimitive(document.title))
        put(Bundle.F_LOCAL_REV, JsonPrimitive(document.localRev))
        put(
            Bundle.F_PAGES,
            buildJsonArray {
                for (ref in document.pages) {
                    add(
                        buildJsonObject {
                            put(Bundle.F_PAGE_ID, JsonPrimitive(ref.id.value))
                            put(Bundle.F_SORT_KEY, JsonPrimitive(ref.sortKey.value))
                            if (ref.deleted) {
                                put(Bundle.F_DELETED, JsonPrimitive(true))
                                ref.deletedEpochMillis?.let { put(Bundle.F_DELETED_AT, JsonPrimitive(it)) }
                            }
                        },
                    )
                }
            },
        )
    }

    private fun applyDocumentRecord(base: Document, record: JsonObject): Document {
        val pages = record[Bundle.F_PAGES]?.jsonArray.orEmpty().map { element ->
            val page = element.jsonObject
            PageRef(
                id = PageId(page.getValue(Bundle.F_PAGE_ID).jsonPrimitive.content),
                sortKey = FractionalKey(page.getValue(Bundle.F_SORT_KEY).jsonPrimitive.content),
                deleted = page[Bundle.F_DELETED]?.jsonPrimitive?.content == "true",
                deletedEpochMillis = page[Bundle.F_DELETED_AT]?.jsonPrimitive?.long,
            )
        }
        return base.copy(
            title = record[Bundle.F_TITLE]?.jsonPrimitive?.content ?: base.title,
            // Union, never replacement: a page the journal does not mention is one the
            // bundle knows about and this record simply did not change (SPEC §4.3).
            pages = if (pages.isEmpty()) base.pages else PageOrder.mergePageOrder(pages, base.pages),
            localRev = maxOf(base.localRev, record[Bundle.F_LOCAL_REV]?.jsonPrimitive?.long ?: 0L),
        )
    }

    private companion object {
        const val HEADER_BYTES = 4
        const val U_UNDO = "undo"
        const val U_REDO = "redo"
        const val U_FORWARD = "f"
        const val U_INVERSE = "i"
    }
}

/**
 * An open note: its metadata, and the handles needed to read pages on demand.
 *
 * Deliberately not a page cache. Whoever draws decides what to keep in memory; this
 * only knows how to fetch.
 *
 * @property journalWasTruncated true when the journal ended in a torn record — a crash
 *   mid-stroke. The session heals it on the next materialization and can tell the user
 *   what happened, which is more honest than pretending nothing did.
 */
public data class OpenNote(
    val document: Document,
    val journalOps: Map<PageId, List<PageOp>>,
    val highestClockCounter: Long,
    val journalBytes: Long,
    val journalWasTruncated: Boolean,
    val reader: BundleReader?,
)

/** One page's log, ready to fold. */
public data class LoadedPage(val pageId: PageId, val size: PageSize, val background: Background, val ops: List<PageOp>)

private fun JsonObject.getValue(key: String) = requireNotNull(this[key]) { "record has no '$key'" }
