package dev.inkstone.core.storage

import dev.inkstone.core.model.ElementId
import dev.inkstone.core.model.ElementInstance
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.InkObject
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.SelectionEdit
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.TextBoxObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ⭐ ROADMAP M8c / SPEC §4.9.4 — **a saved Element, and it is a template rather than a recording.**
 *
 * ## The trap this type exists to make unrepresentable
 *
 * The obvious implementation of *"save this selection, stamp it later"* is to store the
 * [PageOp.AddObject]s the selection folded from and replay them at insert time. It is wrong, and it
 * fails **silently and only on the second use**:
 *
 * `PageFolder.absorb` is **first-write-wins on payload** (SPEC §4.2, and it must be — it is what
 * makes a re-added object keep its geometry). So stamping one Element twice replays two
 * `AddObject`s carrying **one** [ObjectId]. The second's payload is discarded, its `SetTransform`
 * is not, and the page ends up with a **single** object holding the first stamp's payload at the
 * second stamp's position. The first stamp visibly vanishes; the log is well-formed, the fold is
 * deterministic, both devices agree, and nothing anywhere reports a thing. That is SPEC §5.6's
 * duplicate-`ObjectId` corruption arriving from the other direction.
 *
 * So an Element holds **objects and their two registers, and no ops at all** — not in memory, not
 * on disk — and [ElementLibrary.stamp] mints a fresh [ObjectId] per object and a fresh
 * `assets/img-<newId>.webp` per picture on **every** insertion.
 *
 * @property objects the saved payloads with their registers already collapsed
 *   (`InkObject.withRegisters`), and their transforms **relative to the Element's own origin** —
 *   see [ElementLibrary.save] for what that origin is and the one case where it is not what a
 *   reader would assume.
 * @property texts the `SetTextContent` register per object, keyed by the object's id **inside this
 *   Element**. A text box's words, and a shape's `ShapeRecord` — including a connector's
 *   attachment, which [ElementLibrary.stamp] re-targets onto the fresh ids.
 * @property formats the `SetFormat` register per object (SPEC §4.9.2), same keying. Kept separate
 *   from [texts] for the reason that register is separate at all: merging them would let a restyle
 *   discard a paragraph.
 * @property assets image bytes by **bundle entry name** (`Bundle.imageAsset`), the Element's own
 *   copy. An Element outlives the note it was saved from, and a note can be trashed and purged, so
 *   pointing at the source's `assets/` would leave the stamp as a blank rectangle thirty days
 *   later — defined, non-crashing and reported by nothing.
 * @property widthPt the Element's extent, for a picker preview. **Zero when the selection was
 *   nothing but ink** — see [ElementLibrary.save].
 * @property pivot ⭐ the point a placed copy snaps to the grid by, in the Element's own space
 *   (SPEC §4.9.7). Null for an Element saved before pivots existed; a stamp then carries none and a
 *   copy snaps the way any other selection does.
 */
public data class Element(
    val id: ElementId,
    val name: String,
    val collection: String,
    val objects: List<InkObject>,
    val texts: Map<ObjectId, String>,
    val formats: Map<ObjectId, String>,
    val assets: Map<String, ByteArray>,
    val widthPt: Float,
    val heightPt: Float,
    val pivot: PagePoint? = null,
)

/**
 * One insertion of an [Element]: the ops to apply, and the image bytes that must land beside them.
 *
 * ⚠️ **Two halves, and both must be written.** The ops name `assets/img-<newId>.webp` entries that
 * exist nowhere until [assets] is handed to the bundle writer. Applying the ops alone gives a note
 * whose pictures are blank rectangles — the failure SPEC §5.3 names, silent by construction — and
 * writing the assets alone gives orphaned bytes, which is merely wasteful. A single return type
 * makes forgetting the first half something a caller has to do on purpose.
 */
public data class ElementStamp(val ops: List<PageOp>, val assets: Map<String, ByteArray>)

/**
 * SPEC §4.9.4 — the Element library: save a selection as a reusable stamp, list them, stamp them
 * back onto a page.
 *
 * Stored under [NoteLayout.ELEMENTS_DIR], a sibling of `notes/`, one file per Element so that a
 * save is a single atomic write (CLAUDE.md rule 10). See that constant for why it is not inside
 * any note.
 *
 * ## Why there is no [dev.inkstone.core.model.Clock] here
 *
 * [delete] moves an Element to `elements/trash/` and nothing ever sweeps it (CLAUDE.md rule 1's
 * *"no hard deletes"*, with the thirty-day half deliberately not implemented — see
 * [NoteLayout.ELEMENTS_TRASH_DIR]). So this class needs no wall clock, and not taking one is worth
 * a sentence: a clock in a type that does not need it is how *"order by wall clock"* gets
 * reintroduced, and every ordering here is by name.
 */
public class ElementLibrary(private val fs: FileSystem, private val ids: IdFactory) {

    /**
     * Save part of a page as a reusable Element.
     *
     * ## What is copied
     *
     * The selected objects with their registers collapsed onto the payload, **plus** the two
     * registers a fold does *not* collapse — `SetTextContent` and `SetFormat` — and the bytes of
     * every picture among them. Those are the three places a note's content lives (SPEC §4.9.4),
     * and missing any one of them produces something that looks right in a test and wrong on
     * paper: an unfilled box, a straight line where an elbow connector was, or a blank rectangle.
     *
     * @param assets where the image bytes come from — `BundleReader.assets()` of the note being
     *   saved from. Only the entries the selected images actually name are kept, and a name with
     *   no bytes behind it is **dropped rather than recorded empty**, so that a picture missing
     *   from the source does not become a picture missing from every stamp of this Element.
     *
     * ## ⚠️ The origin, and the one case where it is not the selection's top-left
     *
     * Transforms are stored relative to the selection's bounding box, so an Element can be stamped
     * anywhere. That box is computed from the objects whose extent `:core:*` **can** know — images,
     * text boxes and shapes, which carry `widthPt`/`heightPt` — and **a stroke contributes
     * nothing**, because a stroke's geometry is an opaque blob produced by `androidx.ink` and this
     * module may not look inside it (SPEC §5.4, and that is a decision rather than an oversight).
     *
     * Every object is translated by the *same* vector, so the Element's internal geometry is exact
     * whatever the origin is. What the origin decides is only where the stamp lands relative to
     * [ElementLibrary.stamp]'s `at`. So for a selection containing at least one sized object the
     * answer is what a reader expects; for a selection of **nothing but ink** the box is empty, the
     * origin is the page origin, and a stamp therefore lands at `at` composed with the ink's
     * *captured* position rather than at `at`. That case is visible in the data rather than
     * inferred — [Element.widthPt] and [Element.heightPt] are then zero — so a caller that cares
     * can measure the ink itself (`:android:ink` can; nothing here can) and fold the correction
     * into `at`.
     *
     * @param measured ⭐ the selection's page-space box as the **platform** measured it, strokes
     *   included — `InkSurfaceView.boundsOf` answers it, and nothing in `:core:*` can. When given it
     *   is the origin, so an Element of handwriting lands at `at` like any other and has a real
     *   [Element.widthPt] for the panel to draw. Null keeps the payload-only box described above,
     *   which is what a caller with no ink renderer — a test, or a future iOS build that has not
     *   written its measurer yet — gets.
     * @return the saved Element, already written. An empty [selection] yields an empty Element
     *   rather than an error: this is a save path, and refusing to write is not obviously kinder
     *   than writing nothing. A caller should not offer "save as Element" with nothing selected.
     */
    public suspend fun save(
        name: String,
        collection: String,
        page: FoldedPage,
        selection: Set<ObjectId>,
        assets: Map<String, ByteArray>,
        measured: PageRect? = null,
        pivot: PagePoint? = null,
        replacing: ElementId? = null,
    ): Element {
        // Fold order, so the Element keeps the stacking the user saw; `stamp` re-keys from it.
        val picked = page.objects.filter { it.id in selection }
        val box = measured ?: boundsOf(picked)
        val toOrigin = Matrix3.translate(-box.left, -box.top)
        val relative = picked.map { it.withRegisters(transform = toOrigin * it.transform) }
        val element = Element(
            id = replacing ?: ids.newElementId(),
            name = name,
            collection = collection,
            objects = relative,
            // ⚠️ Both registers, and each on its own pass over the selection. A single loop with
            // two `continue`s is the shape of bug `copiedObjectOps` was hoisted to stop: an object
            // with a format and no text would silently lose the format.
            texts = picked.mapNotNull { obj -> page.textOf(obj.id)?.let { obj.id to it } }.toMap(),
            // ⚠️ Without any instance tag. An Element saved from a placed copy — or from the editor,
            // which works on one — would otherwise carry the copy's identity into every stamp of
            // itself, and "update every copy" would then find copies of the wrong Element.
            formats = picked.mapNotNull { obj -> untagged(page.formatOf(obj.id))?.let { obj.id to it } }.toMap(),
            assets = relative.filterIsInstance<ImageObject>()
                .mapNotNull { image -> assets[image.asset]?.let { image.asset to it } }
                .toMap(),
            widthPt = box.width,
            heightPt = box.height,
            pivot = pivot?.let { PagePoint(it.x - box.left, it.y - box.top) },
        )
        fs.createDirectories(NoteLayout.ELEMENTS_DIR)
        // ⭐ Revising an Element replaces its file, and CLAUDE.md rule 1 says the previous version
        // goes to trash first — so an edit that went wrong has the original to go back to.
        if (replacing != null) keepPreviousVersion(replacing)
        fs.writeBytesAtomically(NoteLayout.element(element.id), ElementCodec.write(element))
        return element
    }

    /**
     * The file [id] is about to replace, moved to `elements/trash/` under a name no earlier version
     * holds. A move, never a copy-then-delete, for [delete]'s reason.
     */
    private suspend fun keepPreviousVersion(id: ElementId) {
        val current = NoteLayout.element(id)
        if (!fs.exists(current)) return
        fs.createDirectories(NoteLayout.ELEMENTS_TRASH_DIR)
        var version = 1
        while (fs.exists(previousVersion(id, version))) version++
        fs.move(current, previousVersion(id, version))
    }

    private fun previousVersion(id: ElementId, version: Int): String =
        "${NoteLayout.ELEMENTS_TRASH_DIR}/${id.value}.v$version${NoteLayout.ELEMENT_EXTENSION}"

    /** A format register with its instance tag removed, or null when nothing else was in it. */
    private fun untagged(record: String?): String? {
        if (record == null) return null
        val format = ObjectFormat.decode(record)
        if (format.instance == null) return record
        return format.copy(instance = null).encode().ifEmpty { null }
    }

    /**
     * Every saved Element, or only those in one [collection].
     *
     * Ordered by collection then name then id, so the picker is stable across runs and across
     * devices — a directory listing's own order is a filesystem detail and differs between the
     * emulator and the tablet.
     *
     * ⚠️ **An unreadable file is skipped, not thrown on.** These files are read to draw a picker,
     * and one corrupt or newer-than-this-build Element must not be able to empty the whole library
     * or crash the screen. Same answer `BundleReader.open` gives a file that is not a bundle.
     */
    public suspend fun list(collection: String? = null): List<Element> = fs.list(NoteLayout.ELEMENTS_DIR)
        .filter { it.endsWith(NoteLayout.ELEMENT_EXTENSION) }
        .mapNotNull { name -> ElementCodec.read(FileByteSource(fs, "${NoteLayout.ELEMENTS_DIR}/$name")) }
        .filter { collection == null || it.collection == collection }
        .sortedWith(compareBy({ it.collection }, { it.name }, { it.id.value }))

    /**
     * Delete an Element — which **moves** it, because CLAUDE.md rule 1 has no exception for things
     * that look small.
     *
     * A move rather than a copy-then-unlink for [NoteTrash]'s reason: a crash between the two
     * leaves the file in both places or in neither. Deleting an Element that is not there is a
     * no-op rather than an error; two taps on a delete button are not a programming error.
     */
    public suspend fun delete(id: ElementId) {
        val from = NoteLayout.element(id)
        if (!fs.exists(from)) return
        fs.createDirectories(NoteLayout.ELEMENTS_TRASH_DIR)
        fs.move(from, NoteLayout.trashedElement(id))
    }

    /**
     * ⭐ **One insertion of [element] onto a page** — fresh ids, fresh assets, every time.
     *
     * Pure: it writes nothing and reads nothing, so the caller applies the ops through its own
     * session and writes [ElementStamp.assets] with the next materialize. That also makes the whole
     * decision testable without a filesystem, which is the point of hoisting it (CLAUDE.md
     * definition-of-done #6).
     *
     * ## What re-identification has to cover, and why it is not written here
     *
     * The pass is [copiedObjectOps] — the same one a note duplicate, a page duplicate and SPEC
     * §7.7's conflicted copy use. It mints the new ids, copies **both** registers, and re-targets
     * the `ShapeRecord`s so a stamped diagram's connectors point at the copies rather than at the
     * originals. Writing a fourth copier here is exactly how the *next* register gets forgotten,
     * which is the failure that function's KDoc records from M8b.
     *
     * Two things it deliberately does not do, and this method finishes both:
     *
     * 1. **Z keys.** [z] is where the *stamp* goes; the objects inside keep their relative
     *    stacking, taking consecutive keys above it via [SelectionEdit.keysAbove]. Fractional keys
     *    rather than a renumbering pass, for SPEC §4.3's reason.
     * 2. ⭐ **Asset names.** `copiedObjectOps` copies `ImageObject.asset` **verbatim**, correctly —
     *    within one note a copy shares the original's bytes and asset lifetime is refcount-free
     *    (SPEC §4.9.4). An Element is not within one note: its bytes are its own and the target may
     *    never have seen them. So each stamped image is renamed to `Bundle.imageAsset(<its new
     *    id>)` and its bytes are handed back under that name, which is what keeps *"an asset name
     *    is a UUIDv7, so a union by name needs no comparison"* true of the note it lands in.
     *
     * @param at where the Element goes, composed onto each object's stored relative transform. An
     *   absolute matrix like every other transform in this codebase (CLAUDE.md rule 8) — it may
     *   rotate and scale the whole stamp, not only place it.
     * @param newObjectId the caller's id source, so the ops carry ids from the same generator as
     *   everything else in the session. **Called once per object, and its results must never be
     *   reused between two calls to this method** — that is the corruption in [Element]'s KDoc.
     */
    @Suppress("LongParameterList") // Where, at which key, as which copy, and the three generators.
    public fun stamp(
        element: Element,
        at: Matrix3,
        z: FractionalKey?,
        newObjectId: () -> ObjectId,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
        instanceId: String? = null,
    ): ElementStamp = stampAt(
        element = element,
        at = at,
        keys = SelectionEdit.keysAbove(z, element.objects.size),
        newObjectId = newObjectId,
        nextOpId = nextOpId,
        nextClock = nextClock,
        instanceId = instanceId,
    )

    /**
     * [stamp] with its keys chosen by the caller — what [replaceCopies] needs to put a revised copy
     * back at the stacking position the old one had, rather than on top of the page.
     *
     * @param instanceId ⭐ when non-null, every piece is tagged with an [ElementInstance] (SPEC
     *   §4.9.7) so this copy can be found, and updated, again.
     */
    @Suppress("LongParameterList")
    private fun stampAt(
        element: Element,
        at: Matrix3,
        keys: List<FractionalKey>,
        newObjectId: () -> ObjectId,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
        instanceId: String?,
    ): ElementStamp {
        // `(z, id)` is how a page resolves stacking, so the stamp is ordered the same way — two
        // objects saved at one key would otherwise take their new keys in map order and could
        // swap between two stamps of one Element.
        val ordered = element.objects.sortedWith(compareBy({ it.z }, { it.id.value }))
        val placed = ordered.mapIndexed { index, obj ->
            obj.withRegisters(transform = at * obj.transform, z = keys[index])
        }
        val formats = if (instanceId == null) element.formats else tagged(element, ordered, instanceId)
        val ops = copiedObjectOps(placed, element.texts, formats, newObjectId, nextOpId, nextClock)
        return withFreshAssets(element, ops)
    }

    /** Every piece's format register with this copy's [ElementInstance] added to it. */
    private fun tagged(element: Element, ordered: List<InkObject>, instanceId: String): Map<ObjectId, String> =
        ordered.associate { obj ->
            val tag = ElementInstance(
                elementId = element.id.value,
                instanceId = instanceId,
                member = obj.transform,
                pivot = element.pivot,
                size = PagePoint(element.widthPt, element.heightPt),
            )
            obj.id to ObjectFormat.decode(element.formats[obj.id]).copy(instance = tag).encode()
        }

    /**
     * ⭐ SPEC §4.9.7 — every copy of [element] on [page], replaced by the current [element], each at
     * the placement and the stacking position the old one had.
     *
     * Pure, like [stamp]: the caller applies the ops through its own session (as one gesture, so
     * one undo puts the old copies back) and stages [ElementStamp.assets].
     *
     * The old pieces are **deleted, not rewritten** — tombstones, so the log keeps them (CLAUDE.md
     * rule 1) — because an edit can add or remove pieces, and there is no op that turns one object
     * into another kind. Each new copy keeps its old copy's instance id, so a second update finds it.
     *
     * @param originMoved where the revised Element's origin now sits, measured in the **old**
     *   Element's space. An edit that adds ink above or to the left of everything moves the origin
     *   (it is the top-left of what was saved), and replacing at the old placement would then slide
     *   every unchanged piece of every copy by that much. Zero for an edit that did not move it.
     * @return null when there is no copy of [element] on [page].
     */
    @Suppress("LongParameterList")
    public fun replaceCopies(
        page: FoldedPage,
        element: Element,
        newObjectId: () -> ObjectId,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
        originMoved: PagePoint = PagePoint(0f, 0f),
    ): ElementStamp? {
        val copies = ElementCopies.on(page, element.id)
        if (copies.isEmpty()) return null
        val ops = ArrayList<PageOp>()
        val assets = LinkedHashMap<String, ByteArray>()
        for (copy in copies) {
            copy.pieces.forEach { ops += PageOp.DeleteObject(nextOpId(), nextClock(), it) }
            val keys = ElementCopies.keysFor(page, copy, element.objects.size)
            val at = copy.placement * Matrix3.translate(originMoved.x, originMoved.y)
            val stamped = stampAt(element, at, keys, newObjectId, nextOpId, nextClock, copy.instanceId)
            ops += stamped.ops
            assets += stamped.assets
        }
        return ElementStamp(ops, assets)
    }

    /** See [stamp]'s point 2: each stamped picture gets its own entry name and its own bytes. */
    private fun withFreshAssets(element: Element, ops: List<PageOp>): ElementStamp {
        val assets = LinkedHashMap<String, ByteArray>()
        val rewritten = ops.map { op ->
            val image = (op as? PageOp.AddObject)?.obj as? ImageObject ?: return@map op
            // `image.id` is already the fresh one — `copiedObjectOps` re-identified the payload and
            // left the asset string alone, which is what leaves the old name here to look up.
            val fresh = Bundle.imageAsset(image.id)
            element.assets[image.asset]?.let { assets[fresh] = it }
            op.copy(obj = image.copy(asset = fresh))
        }
        return ElementStamp(rewritten, assets)
    }

    /**
     * The selection's box in page space — see [save] for why a stroke is not in it.
     *
     * @return an empty box at the page origin when nothing in [objects] has a knowable extent.
     */
    private fun boundsOf(objects: List<InkObject>): PageRect = objects
        .mapNotNull { obj -> obj.localCorners()?.map { obj.transform.map(it) } }
        .mapNotNull { PageRect.around(it) }
        .reduceOrNull { box, next -> box.union(next) }
        ?: PageRect(0f, 0f, 0f, 0f)

    /**
     * The four corners of an object's own box, or null for a [StrokeObject].
     *
     * The `when` is exhaustive over [InkObject] on purpose: a fifth kind added later has to answer
     * this question rather than inherit a silent `else`.
     */
    private fun InkObject.localCorners(): List<PagePoint>? {
        val extent = when (this) {
            is ImageObject -> widthPt to heightPt
            is TextBoxObject -> widthPt to heightPt
            is ShapeObject -> widthPt to heightPt
            is StrokeObject -> null
        } ?: return null
        val (width, height) = extent
        return listOf(
            PagePoint(0f, 0f),
            PagePoint(width, 0f),
            PagePoint(width, height),
            PagePoint(0f, height),
        )
    }
}

/**
 * An Element's file: a ZIP holding one JSON document, one geometry blob, and the pictures.
 *
 * ```
 * element.json           name, collection, extents, the objects, and both registers
 * element.ink            OPAQUE stroke geometry, referenced by offset (SPEC §5.4)
 * assets/img-<id>.webp   this Element's own copy of each picture
 * ```
 *
 * ## Why this is a ZIP and not a bundle, and why the objects are not ops
 *
 * A bundle (`note.inkstone`) would have brought the whole thing for free — it already encodes every
 * object kind, the geometry blob and the `assets/` family. It is not used because a bundle **is a
 * note**: it carries a `Document`, a page list, a schema version that `BundleReader.open` refuses
 * from the future by throwing, and — the part that matters — its pages are **op logs**. Storing an
 * Element as ops is the one thing SPEC §4.9.4 forbids, and a format that can express the forbidden
 * thing is a format someone eventually writes it into.
 *
 * What *is* reused is the piece that would otherwise be duplicated badly: [OpCodec.encodeObject]
 * and [OpCodec.decodeObject], with [GeometryWriter]/[GeometryReader] for the blob. A second object
 * codec would agree with none of this one's decisions and would rot separately — M12's second
 * WebDAV client is the entry in CLAUDE.md about exactly that.
 *
 * Deterministic: entries in a fixed order, assets sorted by name, so saving the same selection
 * twice produces the same bytes.
 */
internal object ElementCodec {

    fun write(element: Element): ByteArray {
        val geometry = GeometryWriter()
        val objects = buildJsonArray {
            for (obj in element.objects) add(OpCodec.encodeObject(obj, geometry))
        }
        val json = buildJsonObject {
            put(F_VERSION, JsonPrimitive(VERSION))
            put(F_ID, JsonPrimitive(element.id.value))
            put(F_NAME, JsonPrimitive(element.name))
            put(F_COLLECTION, JsonPrimitive(element.collection))
            put(F_WIDTH, JsonPrimitive(element.widthPt))
            put(F_HEIGHT, JsonPrimitive(element.heightPt))
            put(F_OBJECTS, objects)
            put(F_TEXTS, registers(element.texts))
            put(F_FORMATS, registers(element.formats))
            // Absent rather than null when there is none, so an Element without a pivot is
            // byte-identical to one written before pivots existed.
            element.pivot?.let {
                put(F_PIVOT_X, JsonPrimitive(it.x))
                put(F_PIVOT_Y, JsonPrimitive(it.y))
            }
        }
        val entries = ArrayList<Zip.Entry>()
        entries += Zip.Entry(ENTRY_JSON, OpCodec.json.encodeToString(JsonObject.serializer(), json).encodeToByteArray())
        // Always written, even when empty, for the reason `BundleWriter` always writes a page's
        // `.ink`: an absent entry and an empty one are two states to tell apart while debugging,
        // and an empty stored entry costs sixty bytes.
        entries += Zip.Entry(ENTRY_INK, geometry.toByteArray())
        // `sortedBy`, not `toSortedMap`: the latter is JVM-only and fails the iOS compile.
        for (asset in element.assets.entries.sortedBy { it.key }) {
            entries += Zip.Entry(asset.key, asset.value)
        }
        return Zip.write(entries)
    }

    /**
     * @return null when [source] is not a readable Element — corrupt, truncated, damaged in a way
     *   the ZIP checksum catches, or written by a build whose format this one does not know.
     *
     * ⚠️ **Never throws**, and the `runCatching` is the contract rather than defensiveness. Three
     * things under here raise on damage by design — `Zip.Directory.read` on a checksum mismatch,
     * the JSON parser, and [OpCodec.decodeObject] on an object kind this build has never heard of
     * — and every one of them is a *file*, not a programming error. The caller is drawing a picker
     * over a directory it did not write in this session, so one bad file must cost one row and not
     * the screen.
     */
    suspend fun read(source: ByteSource): Element? = runCatching { decode(source) }.getOrNull()

    @Suppress("ReturnCount") // One guard per way a file can fail to be an Element; nesting reads worse.
    private suspend fun decode(source: ByteSource): Element? {
        val directory = Zip.readDirectory(source) ?: return null
        val bytes = directory.read(ENTRY_JSON) ?: return null
        val json = OpCodec.json.parseToJsonElement(bytes.decodeToString()).jsonObject
        // A version this build does not write is either from the future — which SPEC §5.8 says is
        // never opened and above all never re-saved — or corruption. There is exactly one version,
        // so the two are indistinguishable and the answer is the same either way.
        if (json[F_VERSION]?.jsonPrimitive?.content?.toIntOrNull() != VERSION) return null
        val geometry = GeometryReader(directory.read(ENTRY_INK) ?: ByteArray(0))
        return Element(
            id = ElementId(json[F_ID]?.jsonPrimitive?.content ?: return null),
            name = json[F_NAME]?.jsonPrimitive?.content.orEmpty(),
            collection = json[F_COLLECTION]?.jsonPrimitive?.content.orEmpty(),
            objects = json[F_OBJECTS]?.jsonArray.orEmpty().map { OpCodec.decodeObject(it.jsonObject, geometry) },
            texts = registersOf(json[F_TEXTS]),
            formats = registersOf(json[F_FORMATS]),
            assets = directory.names.filter { Bundle.isAsset(it) }
                .mapNotNull { name -> directory.read(name)?.let { name to it } }
                .toMap(),
            widthPt = json[F_WIDTH]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
            heightPt = json[F_HEIGHT]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
            pivot = pivotOf(json),
        )
    }

    private fun pivotOf(json: JsonObject): PagePoint? {
        val x = json[F_PIVOT_X]?.jsonPrimitive?.content?.toFloatOrNull() ?: return null
        val y = json[F_PIVOT_Y]?.jsonPrimitive?.content?.toFloatOrNull() ?: return null
        return PagePoint(x, y)
    }

    /** Sorted by object id, so the file's bytes do not depend on a map's iteration order. */
    private fun registers(values: Map<ObjectId, String>): JsonObject = buildJsonObject {
        for (entry in values.entries.sortedBy { it.key.value }) {
            put(entry.key.value, JsonPrimitive(entry.value))
        }
    }

    private fun registersOf(element: JsonElement?): Map<ObjectId, String> {
        val values = element as? JsonObject ?: return emptyMap()
        return values.entries.associate { ObjectId(it.key) to it.value.jsonPrimitive.content }
    }

    private const val ENTRY_JSON = "element.json"
    private const val ENTRY_INK = "element.ink"

    private const val F_VERSION = "v"
    private const val F_ID = "id"
    private const val F_NAME = "name"
    private const val F_COLLECTION = "collection"
    private const val F_WIDTH = "w"
    private const val F_HEIGHT = "h"
    private const val F_OBJECTS = "objects"
    private const val F_TEXTS = "texts"
    private const val F_FORMATS = "formats"
    private const val F_PIVOT_X = "px"
    private const val F_PIVOT_Y = "py"

    /** One format, one version. A mismatch is unreadable rather than guessed — see [read]. */
    private const val VERSION = 1
}
