package dev.inkstone.core.storage

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Document
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageRef
import dev.inkstone.core.model.PageSize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * SPEC §5.3 — the bundle, `note.inkstone`.
 *
 * ```
 * manifest.json           schemaVersion, documentId, title, pages[{id,sortKey}], timestamps, deviceId
 * pages/<pageId>.json     op-log metadata: op ids, clocks, types, and offsets into the geometry blob
 * pages/<pageId>.ink      OPAQUE stroke geometry blob (SPEC §5.4)
 * assets/img-<id>.webp    inserted images
 * text/<pageId>.json      recognised text + per-word page-space bounding boxes
 * ```
 *
 * No `source.pdf` — the original is the front of the PDF verbatim (SPEC §5.2). No
 * thumbnail — those live in a local cache (SPEC §5.7).
 *
 * **One file per page is what makes lazy loading possible** (SPEC §5.3): opening a
 * 200-page notebook reads `manifest.json` and the page being drawn, and nothing else.
 * [BundleReader] is that; it never materialises a page it was not asked for.
 *
 * Assets and recognised text are M7/M8 material. Their paths are fixed here, and
 * [BundleWriter] carries any entry it was given through unchanged, so a note that
 * gains them later is not a format change.
 */
public object Bundle {

    public const val MANIFEST: String = "manifest.json"
    public const val PAGES_DIR: String = "pages/"
    public const val ASSETS_DIR: String = "assets/"
    public const val TEXT_DIR: String = "text/"

    public fun pageJson(id: PageId): String = "$PAGES_DIR${id.value}.json"

    public fun pageInk(id: PageId): String = "$PAGES_DIR${id.value}.ink"

    public fun pageText(id: PageId): String = "$TEXT_DIR${id.value}.json"

    internal const val F_SCHEMA = "schemaVersion"
    internal const val F_DOCUMENT_ID = "documentId"
    internal const val F_TITLE = "title"
    internal const val F_PAGES = "pages"
    internal const val F_DEVICE = "deviceId"
    internal const val F_CREATED = "createdEpochMillis"
    internal const val F_MODIFIED = "modifiedEpochMillis"
    internal const val F_LOCAL_REV = "localRev"
    internal const val F_CLOCK = "highestClockCounter"

    internal const val F_PAGE_ID = "id"
    internal const val F_SORT_KEY = "sortKey"
    internal const val F_DELETED = "deleted"
    internal const val F_DELETED_AT = "deletedEpochMillis"

    internal const val F_SIZE = "size"
    internal const val F_BACKGROUND = "background"
    internal const val F_OPS = "ops"
}

/**
 * Everything needed to write a bundle: the document, and each page's whole op log.
 *
 * @property highestClockCounter the highest Lamport counter anywhere in the note, so
 *   an open can seed its generator without folding every page (SPEC §4.1's `max + 1`).
 * @property carried entries this build did not produce — recognised text, assets, and
 *   anything a newer version added. Copied through byte-for-byte (SPEC §5.8).
 */
public class BundleContents(
    public val document: Document,
    public val pages: Map<PageId, PageContents>,
    public val highestClockCounter: Long,
    public val carried: Map<String, ByteArray> = emptyMap(),
)

/** One page's log, as it goes into the bundle. */
public class PageContents(public val size: PageSize, public val background: Background, public val ops: List<PageOp>)

/**
 * Serialises a [BundleContents] to bytes.
 *
 * Deterministic: entry order is manifest, then pages in document order, then carried
 * entries by name; the ZIP is stored and unstamped (see [Zip]). Two bundles with the
 * same content are the same bytes, which is ROADMAP M2's *"save → load → save →
 * byte-identical"*.
 */
public object BundleWriter {

    public fun write(contents: BundleContents): ByteArray {
        val entries = ArrayList<Zip.Entry>()
        entries += Zip.Entry(Bundle.MANIFEST, manifest(contents).toBytes())

        for (ref in contents.document.pages) {
            val page = contents.pages[ref.id] ?: continue
            val geometry = GeometryWriter()
            val ops = buildJsonArray {
                for (op in page.ops) add(OpCodec.encode(op, geometry))
            }
            val json = buildJsonObject {
                put(Bundle.F_PAGE_ID, JsonPrimitive(ref.id.value))
                put(Bundle.F_SIZE, OpCodec.encodeSize(page.size))
                put(Bundle.F_BACKGROUND, OpCodec.encodeBackground(page.background))
                put(Bundle.F_OPS, ops)
            }
            entries += Zip.Entry(Bundle.pageJson(ref.id), json.toBytes())
            // Always written, even when empty: an absent `.ink` and an empty one are
            // different states to debug, and an empty stored entry costs 60 bytes.
            entries += Zip.Entry(Bundle.pageInk(ref.id), geometry.toByteArray())
        }

        // Sorted by name, not by map order: `toSortedMap` is JVM-only, and the point is
        // that two bundles with the same content are the same bytes.
        for (entry in contents.carried.entries.sortedBy { it.key }) {
            entries += Zip.Entry(entry.key, entry.value)
        }
        return Zip.write(entries)
    }

    private fun manifest(contents: BundleContents): JsonObject = buildJsonObject {
        val document = contents.document
        put(Bundle.F_SCHEMA, JsonPrimitive(document.schemaVersion))
        put(Bundle.F_DOCUMENT_ID, JsonPrimitive(document.id.value))
        put(Bundle.F_TITLE, JsonPrimitive(document.title))
        put(Bundle.F_DEVICE, JsonPrimitive(document.deviceId))
        put(Bundle.F_CREATED, JsonPrimitive(document.createdEpochMillis))
        put(Bundle.F_MODIFIED, JsonPrimitive(document.modifiedEpochMillis))
        put(Bundle.F_LOCAL_REV, JsonPrimitive(document.localRev))
        put(Bundle.F_CLOCK, JsonPrimitive(contents.highestClockCounter))
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
}

/**
 * Reads a bundle lazily.
 *
 * [open] reads the central directory and `manifest.json` — two small reads, whatever
 * the note's size. [page] reads one page's JSON and one page's geometry. Nothing else
 * is touched, so memory does not grow with page count while scrolling (ROADMAP M2).
 */
public class BundleReader private constructor(
    private val directory: Zip.Directory,
    public val document: Document,
    public val highestClockCounter: Long,
) {

    /** Names of entries this build does not produce, to carry through on the next write. */
    public val carriedNames: List<String>
        get() = directory.names.filterNot { name ->
            name == Bundle.MANIFEST || name.startsWith(Bundle.PAGES_DIR)
        }

    public suspend fun carried(): Map<String, ByteArray> =
        carriedNames.mapNotNull { name -> directory.read(name)?.let { name to it } }.toMap()

    /** @return null when the bundle holds no such page — a manifest and pages can disagree. */
    public suspend fun page(id: PageId): PageContents? {
        val jsonBytes = directory.read(Bundle.pageJson(id)) ?: return null
        val blob = directory.read(Bundle.pageInk(id)) ?: ByteArray(0)
        val json = OpCodec.json.parseToJsonElement(jsonBytes.decodeToString()).jsonObject
        val geometry = GeometryReader(blob)
        val ops = json[Bundle.F_OPS]?.jsonArray.orEmptyArray().map { OpCodec.decode(it.jsonObject, geometry).op }
        return PageContents(
            size = json[Bundle.F_SIZE]?.let { OpCodec.decodeSize(it.jsonArray) } ?: PageSize.Letter,
            background = json[Bundle.F_BACKGROUND]?.let { OpCodec.decodeBackground(it.jsonObject) }
                ?: Background.Blank,
            ops = ops,
        )
    }

    public companion object {
        /**
         * @return null when [source] is not a readable Inkstone bundle. That is a real
         *   state, not an error: SPEC §5.6's three-way detection begins with exactly this
         *   question, and a stranger's annotated PDF answers it with a null.
         */
        public suspend fun open(source: ByteSource): BundleReader? {
            val directory = Zip.readDirectory(source) ?: return null
            val manifestBytes = directory.read(Bundle.MANIFEST) ?: return null
            val manifest = OpCodec.json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject

            val schema = manifest[Bundle.F_SCHEMA]?.jsonPrimitive?.int() ?: return null
            // SPEC §5.8 — checked on every open. A file from the future is not opened and
            // is certainly not re-saved: re-saving it with this build's writer is how a
            // newer version's data gets destroyed.
            require(schema <= Document.SCHEMA_VERSION) {
                "note is schema version $schema, this build understands ${Document.SCHEMA_VERSION}"
            }

            val pages = manifest[Bundle.F_PAGES]?.jsonArray.orEmptyArray().map { element ->
                val page = element.jsonObject
                PageRef(
                    id = PageId(page.getValue(Bundle.F_PAGE_ID).jsonPrimitive.content),
                    sortKey = FractionalKey(page.getValue(Bundle.F_SORT_KEY).jsonPrimitive.content),
                    deleted = page[Bundle.F_DELETED]?.jsonPrimitive?.content == "true",
                    deletedEpochMillis = page[Bundle.F_DELETED_AT]?.jsonPrimitive?.long,
                )
            }

            val document = Document(
                id = DocumentId(manifest.getValue(Bundle.F_DOCUMENT_ID).jsonPrimitive.content),
                title = manifest[Bundle.F_TITLE]?.jsonPrimitive?.content.orEmpty(),
                pages = pages,
                schemaVersion = schema,
                localRev = manifest[Bundle.F_LOCAL_REV]?.jsonPrimitive?.long ?: 0L,
                deviceId = manifest[Bundle.F_DEVICE]?.jsonPrimitive?.content.orEmpty(),
                createdEpochMillis = manifest[Bundle.F_CREATED]?.jsonPrimitive?.long ?: 0L,
                modifiedEpochMillis = manifest[Bundle.F_MODIFIED]?.jsonPrimitive?.long ?: 0L,
            )
            return BundleReader(
                directory = directory,
                document = document,
                highestClockCounter = manifest[Bundle.F_CLOCK]?.jsonPrimitive?.long ?: 0L,
            )
        }
    }
}

private fun JsonObject.toBytes(): ByteArray =
    OpCodec.json.encodeToString(JsonObject.serializer(), this).encodeToByteArray()

private fun JsonObject.getValue(key: String) = requireNotNull(this[key]) { "manifest has no '$key'" }

private fun JsonArray?.orEmptyArray(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

private fun JsonPrimitive.int(): Int = content.toInt()
