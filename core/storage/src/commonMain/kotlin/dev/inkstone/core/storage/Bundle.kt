package dev.inkstone.core.storage

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Document
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageRef
import dev.inkstone.core.model.PageSize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

    /**
     * SPEC §7.7 layer 2's LWW title clock (M6) — see [dev.inkstone.core.model.Document.titleClock].
     *
     * Two flat primitives rather than a nested object, to match the rest of this manifest
     * and because a `LamportClock` is exactly two scalars. Absent in anything written
     * before M6, which reads back as null and loses to any recorded rename.
     */
    internal const val F_TITLE_CLOCK_COUNTER = "titleClockCounter"

    internal const val F_TITLE_CLOCK_DEVICE = "titleClockDevice"

    /**
     * Every manifest member this build understands.
     *
     * Anything **not** in this set is carried through a round trip verbatim — see
     * [BundleReader.carriedManifest]. SPEC §5.8 requires *"unknown ops and unknown JSON
     * fields survive untouched"*, and until M6 only the op half was honoured: `OpExtras`
     * kept unrecognised members of an *op*, while the manifest was rebuilt field by field
     * from a fixed list and everything else was dropped on the floor.
     *
     * That is what lets [dev.inkstone.core.model.Document.titleClock] be an **additive**
     * field with `schemaVersion` still 1. The alternative — bumping to 2 — would make an
     * older build refuse to open the note at all (see [BundleReader.open]'s `require`), and
     * M6's acceptance gate is a week of two-device use where the two devices are very
     * likely on different builds. An older build that *preserves* an unknown `titleClock`
     * degrades gracefully: it cannot update the clock on its own renames, so it may lose a
     * later rename, which is a wrong title. Dropping the field corrupts the register for
     * every device, which is a wrong title that never heals.
     */
    internal val KNOWN_MANIFEST_FIELDS: Set<String> = setOf(
        F_SCHEMA,
        F_DOCUMENT_ID,
        F_TITLE,
        F_PAGES,
        F_DEVICE,
        F_CREATED,
        F_MODIFIED,
        F_LOCAL_REV,
        F_CLOCK,
        F_TITLE_CLOCK_COUNTER,
        F_TITLE_CLOCK_DEVICE,
    )

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
 * @param highestClockCounter the highest Lamport counter across the note's **page ops**, so an
 *   open can seed its generator without folding every page (SPEC §4.1's `max + 1`). Raised to
 *   cover the title clock — see the property of the same name.
 * @property carried entries this build did not produce — recognised text, assets, and
 *   anything a newer version added. Copied through byte-for-byte (SPEC §5.8).
 * @property carriedManifest manifest members this build does not understand, kept so a
 *   round trip does not delete a newer version's document metadata (SPEC §5.8). See
 *   [Bundle.KNOWN_MANIFEST_FIELDS].
 */
public class BundleContents(
    public val document: Document,
    public val pages: Map<PageId, PageContents>,
    highestClockCounter: Long,
    public val carried: Map<String, ByteArray> = emptyMap(),
    public val carriedManifest: Map<String, JsonElement> = emptyMap(),
) {

    /**
     * ⭐ **The highest counter anywhere in the note — page ops *and*
     * [Document.titleClock] (SPEC §4.1's `max + 1`, SPEC §7.7 layer 2).**
     *
     * Normalised here rather than by each writer, because a caller that forgot the title clock
     * produced a note whose *next* session re-issued a counter it had already used: two devices
     * then held two titles at one identical clock, `Register`-style resolution kept whichever each
     * had folded first, and the two disagreed forever with every op present and nothing reported.
     * M6 shipped that bug in `NoteStore.materialize` — which computed the maximum over page ops
     * only, while `NoteSession.rename` stamped the title from the same generator.
     *
     * There are four writers of a bundle (materialize, a compaction snapshot, a recovered import
     * and a conflicted copy) and the invariant belongs to none of them, so it lives on the type
     * they all go through and cannot be forgotten again.
     */
    public val highestClockCounter: Long = maxOf(highestClockCounter, document.titleClock?.counter ?: 0L)
}

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
        // Unknown members first, so a field this build *does* understand always wins over a
        // stale copy of itself carried from the read. Sorted, because two bundles with the
        // same content must be the same bytes (see the object KDoc) and a map's iteration
        // order is not part of that contract.
        for ((name, value) in contents.carriedManifest.entries.sortedBy { it.key }) {
            if (name !in Bundle.KNOWN_MANIFEST_FIELDS) put(name, value)
        }
        put(Bundle.F_SCHEMA, JsonPrimitive(document.schemaVersion))
        put(Bundle.F_DOCUMENT_ID, JsonPrimitive(document.id.value))
        put(Bundle.F_TITLE, JsonPrimitive(document.title))
        put(Bundle.F_DEVICE, JsonPrimitive(document.deviceId))
        put(Bundle.F_CREATED, JsonPrimitive(document.createdEpochMillis))
        put(Bundle.F_MODIFIED, JsonPrimitive(document.modifiedEpochMillis))
        put(Bundle.F_LOCAL_REV, JsonPrimitive(document.localRev))
        put(Bundle.F_CLOCK, JsonPrimitive(contents.highestClockCounter))
        document.titleClock?.let { clock ->
            put(Bundle.F_TITLE_CLOCK_COUNTER, JsonPrimitive(clock.counter))
            put(Bundle.F_TITLE_CLOCK_DEVICE, JsonPrimitive(clock.deviceId))
        }
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
    /**
     * Manifest members this build does not understand, verbatim (SPEC §5.8).
     *
     * Pass to [BundleContents.carriedManifest] on the next write, or a newer version's
     * document metadata is deleted by the act of opening and saving the note here.
     */
    public val carriedManifest: Map<String, JsonElement>,
) {

    /** Every page the manifest lists, tombstones included. */
    public val pageIds: List<PageId> get() = document.pages.map { it.id }

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
                // Both halves or neither. A counter without a deviceId cannot participate in
                // SPEC §4.1's tiebreak, and inventing one here would fabricate an ordering
                // between two devices out of a truncated file.
                titleClock = manifest[Bundle.F_TITLE_CLOCK_COUNTER]?.jsonPrimitive?.long?.let { counter ->
                    manifest[Bundle.F_TITLE_CLOCK_DEVICE]?.jsonPrimitive?.content
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { device -> LamportClock(counter, device) }
                },
            )
            return BundleReader(
                directory = directory,
                document = document,
                highestClockCounter = manifest[Bundle.F_CLOCK]?.jsonPrimitive?.long ?: 0L,
                carriedManifest = manifest.filterKeys { it !in Bundle.KNOWN_MANIFEST_FIELDS },
            )
        }

        /**
         * The manifest's `schemaVersion`, without building a reader and **without throwing**.
         *
         * [open] refuses a bundle from the future by *throwing* (SPEC §5.8's `require`), which is
         * right for every caller that means to read the note and useless for the one caller that
         * has to tell the user why. SPEC §7.7 layer 3 needs the two apart: a schema this build
         * does not understand is *"update Inkstone and this note opens"*, and a file that is not a
         * bundle at all is an unmergeable conflict. Both used to arrive as one
         * `runCatching { open(…) }.getOrNull()`, which is why `MergeReport.TooNew` was a branch
         * nothing could reach — see [NoteMerge.mergeBundle], the only caller.
         *
         * @return null when [source] is not a readable bundle, or its manifest carries no
         *   `schemaVersion`. Never throws: the input is by definition a file this build may not
         *   understand, and an exception out of a WorkManager job is read as a transport failure
         *   and retried forever.
         */
        public suspend fun schemaVersionOf(source: ByteSource): Int? {
            val directory = Zip.readDirectory(source) ?: return null
            val manifestBytes = directory.read(Bundle.MANIFEST) ?: return null
            val manifest = runCatching {
                OpCodec.json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject
            }.getOrNull() ?: return null
            // `toIntOrNull`, not the `int()` helper [open] uses: that one throws on a manifest
            // whose schemaVersion is not a number, and this function's whole contract is that it
            // answers rather than raising.
            return manifest[Bundle.F_SCHEMA]?.jsonPrimitive?.content?.toIntOrNull()
        }
    }
}

private fun JsonObject.toBytes(): ByteArray =
    OpCodec.json.encodeToString(JsonObject.serializer(), this).encodeToByteArray()

private fun JsonObject.getValue(key: String) = requireNotNull(this[key]) { "manifest has no '$key'" }

private fun JsonArray?.orEmptyArray(): List<JsonElement> = this ?: emptyList()

private fun JsonPrimitive.int(): Int = content.toInt()
