package dev.inkstone.app.elements

import dev.inkstone.app.note.pageBounds
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.ImageEdit
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.InkObject
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.SelectionEdit
import dev.inkstone.core.model.ShapeEdit
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.ShapePlacement
import dev.inkstone.core.model.ShapeRecord
import dev.inkstone.core.model.TextBoxEdit
import dev.inkstone.core.model.TextBoxObject
import dev.inkstone.core.storage.Bundle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ⭐ ROADMAP M8c — *"save a selection as a reusable Element, with a name and a collection"*.
 *
 * ## An Element is a template, never a recording of ops
 *
 * SPEC §4.9.4 is explicit and the reason is worth restating, because the obvious implementation is
 * the broken one: storing the `AddObject` ops and replaying them would stamp the **same
 * `ObjectId`** twice, and `PageFolder.absorb` is first-write-wins on the payload — so the second
 * insertion would produce *one* object carrying the first stamp's payload at the second stamp's
 * transform, and the first copy would visibly vanish. That is SPEC §5.6's duplicate-id corruption
 * arriving from the other direction.
 *
 * So an Element holds payloads, geometry **relative to its own top-left**, style, the two record
 * registers, and its own copy of any image bytes. [stamp] mints a fresh UUIDv7 per object and a
 * fresh `assets/img-<newId>.webp` per asset, every time.
 *
 * ## ⚠️ What an Element can hold, and the one thing it cannot
 *
 * Text boxes, images and shapes. **Not pen strokes** — and that is a seam, not a decision. A
 * stroke's geometry is an opaque blob that only `:android:ink` can decode or re-encode (SPEC
 * §5.4), so `:app` can neither measure a stroke's bounds to decide it is inside the capture area
 * nor copy its payload into a template. `InkSelection` exposes a *count* and not its ids
 * (`SelectionController`), so the lasso cannot supply the answer either. `ElementCapture` takes an
 * **area** for that reason, and this limitation is stated in the UI rather than discovered.
 *
 * ## Why this lives in `:app`
 *
 * It should not, and this is a stand-in: `:core:storage` is where an `ElementLibrary` belongs, so
 * that the iOS target inherits it. It is here because that module was being written in parallel
 * with this one and the type was not on the classpath. The seam is [ElementStamp], which is the
 * shape `:core:storage`'s is specified to return, so moving this is a change of import.
 *
 * ## On disk
 *
 * ```
 * elements/index.json                  every element's metadata and its members
 * elements/<elementId>/<assetName>     one file per image the element carries
 * ```
 *
 * A **sibling of `notes/`**, for `NoteLayout.RECORDINGS_DIR`'s reason: a note directory is
 * something this app moves and copies as a unit — trash, duplicate, orphan-stub — and an Element
 * belongs to the user, not to whichever note it was cut from. Trashing that note must not take the
 * library with it.
 */
@Singleton
class ElementLibrary @Inject constructor(private val fs: FileSystem, private val clock: Clock) {

    /**
     * Every saved element, newest first.
     *
     * An unreadable index answers empty rather than throwing. The library is a convenience built
     * out of copies — the originals are still in the notes they were cut from — so a corrupt index
     * must never be able to stop a note from opening, which is what an exception escaping a
     * composable's `LaunchedEffect` would do.
     */
    suspend fun list(): List<Element> = readIndex().elements.sortedByDescending { it.createdEpochMillis }

    /** The collections that exist, in first-use order, so the panel's grouping is stable. */
    suspend fun collections(): List<String> = list().map { it.collection }.distinct()

    /**
     * Save [objects] as a named element.
     *
     * Geometry is stored **relative to the capture's top-left**, so stamping is a translation and
     * an element cut from the bottom of page 40 does not arrive off the bottom of page 1.
     *
     * @param objects what to capture, with each one's live register values already collapsed onto
     *   it — the same discipline `ShapeEdit.duplicate` documents, and for the same reason: a copy
     *   has to look like the original did at the moment it was copied, not like its `AddObject`
     *   payload did when it was created.
     * @return null when there is nothing capturable in the area. The caller says so; an element
     *   with no members would sit in the panel stamping nothing.
     */
    suspend fun save(name: String, collection: String, objects: List<CapturedObject>): Element? {
        if (objects.isEmpty()) return null
        val bounds = objects.mapNotNull { it.obj.pageBounds() }
            .reduceOrNull { a, b -> a.union(b) } ?: return null
        val id = ELEMENT_PREFIX + clock.nowEpochMillis().toString(RADIX) + "-" + objects.size
        val origin = Matrix3.translate(-bounds.left, -bounds.top)
        val members = objects.mapIndexedNotNull { index, captured ->
            memberOf(captured, origin, assetName = captured.assetBytes?.let { "$index$ASSET_EXTENSION" })
        }
        if (members.isEmpty()) return null

        // Bytes before the index, always. An index row naming a file that is not there yet is an
        // element that stamps a blank rectangle; a file with no index row is invisible and costs
        // only disk. Same ordering rule as materialize-then-publish (SPEC §5.2).
        objects.forEachIndexed { index, captured ->
            captured.assetBytes?.let { fs.writeBytesAtomically("$ELEMENTS_DIR/$id/$index$ASSET_EXTENSION", it) }
        }

        val element = Element(
            id = id,
            name = name.ifBlank { DEFAULT_NAME },
            collection = collection.ifBlank { DEFAULT_COLLECTION },
            createdEpochMillis = clock.nowEpochMillis(),
            widthPt = bounds.width,
            heightPt = bounds.height,
            members = members,
        )
        writeIndex(ElementIndex(readIndex().elements + element))
        return element
    }

    /**
     * Remove an element from the library.
     *
     * ⚠️ **The index row goes and the bytes stay**, which looks like a leak and is CLAUDE.md rule
     * 1 applied at the right scale: the files are small, and a hard delete of the one copy of an
     * image the user assembled by hand is exactly the operation this project does not perform. The
     * directory is orphaned, not overwritten, so a future undelete has something to find.
     */
    suspend fun delete(id: String) {
        writeIndex(ElementIndex(readIndex().elements.filterNot { it.id == id }))
    }

    /**
     * ⭐ One insertion: fresh ids, fresh asset names, and the bytes to write beside the ops.
     *
     * ⚠️ **The assets must reach the bundle with the ops, not after them.** An `ImageObject` whose
     * `assets/` entry is missing draws a blank rectangle and reports nothing, because the asset
     * family has no refcount and nothing anywhere is entitled to conclude that a missing entry is
     * an error (SPEC §4.9.4). [ElementStamp] carries both so a caller cannot take one without the
     * other.
     *
     * @param at where the element's top-left lands, in page space.
     * @param topZ the highest z key on the page, or null for an empty one — the stamp goes above
     *   everything, which is what a user who just placed something expects to be able to see.
     */
    @Suppress("LongParameterList") // The placement and the note's four generators; see the KDoc.
    suspend fun stamp(
        element: Element,
        at: PagePoint,
        topZ: FractionalKey?,
        newObjectId: () -> ObjectId,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): ElementStamp {
        val ops = ArrayList<PageOp>(element.members.size * OPS_PER_MEMBER)
        val assets = LinkedHashMap<String, ByteArray>()
        val keys = SelectionEdit.keysAbove(topZ, element.members.size)
        val place = Matrix3.translate(at.x, at.y)
        // ⚠️ **Every id first, then the ops.** A connector inside an element names its endpoints
        // by `ObjectId` in its `ShapeRecord` (SPEC §4.8.3), so the record can only be remapped
        // once the whole copy's ids exist. Stamping without this leaves a diagram whose arrows
        // name the *original's* boxes — which resolves as detached, is defined and non-crashing,
        // and is visibly wrong. `copiedObjectOps` learned the same lesson in M8b.
        val remap = element.members.associate { it.sourceId to newObjectId() }
        element.members.forEachIndexed { index, member ->
            val id = remap[member.sourceId] ?: return@forEachIndexed
            val asset = member.asset?.let { source ->
                val name = Bundle.imageAsset(id)
                readAsset(element.id, source)?.let { assets[name] = it }
                name
            }
            ops += member.opsFor(id, place * member.transform(), keys[index], asset, remap, nextOpId, nextClock)
        }
        return ElementStamp(ops, assets)
    }

    private suspend fun readAsset(elementId: String, name: String): ByteArray? {
        val path = "$ELEMENTS_DIR/$elementId/$name"
        // `exists` first, never a bare `readBytes` — M12: `readBytes` throws rather than answering
        // empty, so an "is it missing" guard written as `isEmpty()` is unreachable in production
        // and the throw takes the whole operation with it.
        return if (fs.exists(path)) fs.readBytes(path) else null
    }

    private suspend fun readIndex(): ElementIndex = runCatching {
        if (!fs.exists(INDEX_PATH)) return@runCatching ElementIndex(emptyList())
        json.decodeFromString(ElementIndex.serializer(), fs.readBytes(INDEX_PATH).decodeToString())
    }.getOrElse { ElementIndex(emptyList()) }

    private suspend fun writeIndex(index: ElementIndex) {
        fs.writeBytesAtomically(INDEX_PATH, json.encodeToString(ElementIndex.serializer(), index).encodeToByteArray())
    }

    private fun memberOf(captured: CapturedObject, origin: Matrix3, assetName: String?): ElementMember? {
        val obj = captured.obj
        val style = obj.style ?: DEFAULT_STYLE
        val relative = origin * obj.transform
        val kind = when (obj) {
            is TextBoxObject -> ElementKind.TEXT_BOX
            is ImageObject -> ElementKind.IMAGE
            is ShapeObject -> ElementKind.SHAPE
            else -> return null
        }
        val extents = when (obj) {
            is TextBoxObject -> obj.widthPt to obj.heightPt
            is ImageObject -> obj.widthPt to obj.heightPt
            is ShapeObject -> obj.widthPt to obj.heightPt
            else -> return null
        }
        return ElementMember(
            sourceId = obj.id.value,
            kind = kind,
            widthPt = extents.first,
            heightPt = extents.second,
            matrix = listOf(relative.m00, relative.m01, relative.m02, relative.m10, relative.m11, relative.m12),
            colorArgb = style.colorArgb,
            strokeWidthPt = style.widthPt,
            opacity = style.opacity,
            text = captured.text,
            format = captured.format,
            asset = assetName,
            shape = (obj as? ShapeObject)?.shape?.name,
        )
    }

    private companion object {
        const val ELEMENTS_DIR = "elements"
        const val INDEX_PATH = "$ELEMENTS_DIR/index.json"
        const val ASSET_EXTENSION = ".webp"
        const val ELEMENT_PREFIX = "el-"
        const val RADIX = 36
        const val DEFAULT_NAME = "Element"
        const val DEFAULT_COLLECTION = "My elements"

        /** `AddObject`, plus at most a text register and a format register. */
        const val OPS_PER_MEMBER = 3

        /**
         * ⚠️ A member with no style still stamps one, because SPEC §4.9.2 requires a text box and
         * an image to be created **with** a style: `SetStyle` cannot express "unset", so a copy
         * that arrived with a null style would have no inverse for its first recolour and *"change
         * the colour, press undo"* would silently eat the press. Black at one point is the same
         * default the pen starts at.
         */
        val DEFAULT_STYLE = ObjectStyle(colorArgb = 0xFF1A1A1A.toInt(), widthPt = 1f)

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/** One object being captured, with the register values that are not on its payload. */
class CapturedObject(
    val obj: InkObject,
    /** The `SetTextContent` register — a text box's words, or a shape's `ShapeRecord`. */
    val text: String?,
    /** The `SetFormat` register, verbatim. Copied rather than parsed, so a later build's keys survive. */
    val format: String?,
    /** The image's bytes, read out of the source note's bundle. Null for anything that is not an image. */
    val assetBytes: ByteArray?,
)

/**
 * One insertion's ops **and** the asset bytes they name.
 *
 * The two travel together because taking one without the other is the failure: ops with no bytes
 * is a blank rectangle, bytes with no ops is dead weight nothing will ever delete (SPEC §4.9.4 —
 * asset lifetime is refcount-free).
 */
class ElementStamp(val ops: List<PageOp>, val assets: Map<String, ByteArray>)

@Serializable
data class ElementIndex(val elements: List<Element> = emptyList())

@Serializable
data class Element(
    val id: String,
    val name: String,
    val collection: String,
    val createdEpochMillis: Long,
    val widthPt: Float,
    val heightPt: Float,
    val members: List<ElementMember> = emptyList(),
)

enum class ElementKind { TEXT_BOX, IMAGE, SHAPE }

/**
 * One object inside an element.
 *
 * ⚠️ [matrix] is **relative to the element's own top-left**, never page space. Stamping composes
 * it under a translation, so the same element lands correctly wherever it is dropped and on
 * whatever page size — which is the property a stored page-space matrix would not have.
 */
@Serializable
data class ElementMember(
    /** The `ObjectId` this member was cut from — the key the connector remap is built on. */
    val sourceId: String,
    val kind: ElementKind,
    val widthPt: Float,
    val heightPt: Float,
    val matrix: List<Float>,
    val colorArgb: Int,
    val strokeWidthPt: Float,
    val opacity: Float,
    val text: String? = null,
    val format: String? = null,
    val asset: String? = null,
    val shape: String? = null,
) {
    fun transform(): Matrix3 = if (matrix.size == MATRIX_SIZE) {
        Matrix3(matrix[M00], matrix[M01], matrix[M02], matrix[M10], matrix[M11], matrix[M12])
    } else {
        Matrix3.Identity
    }

    /**
     * One stamped copy, as the ops that make it.
     *
     * ⚠️ **Through `:core:model`'s builders, never by hand.** `TextBoxEdit.create`,
     * `ImageEdit.create` and `ShapeEdit.create` each know things a copy here would have to
     * re-derive and would eventually get wrong: the extent clamp that keeps a matrix invertible,
     * the op *order* that makes one insertion one undo entry, and the rule that a default format
     * is written as no op at all rather than as an empty register.
     *
     * @param remap old id → new id for every member of this element, so a connector's endpoints
     *   point at *this* copy. An endpoint outside the element keeps its original target, which is
     *   `ShapeEdit.duplicate`'s rule: a copy attaches to the same outside shape the original does.
     * @return empty for a shape whose kind this build does not know. **Not an error and not a
     *   guess**: `ShapeKind.valueOf` throwing is SPEC §4.9.1's *"a note that will not open"*, and
     *   an element written by a later build must degrade to the members this one understands
     *   rather than take the whole stamp down with it.
     */
    @Suppress("LongParameterList") // Identity, placement, the asset, the remap, and two generators.
    fun opsFor(
        id: ObjectId,
        transform: Matrix3,
        z: FractionalKey,
        asset: String?,
        remap: Map<String, ObjectId>,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> {
        val style = ObjectStyle(colorArgb, strokeWidthPt, opacity)
        return when (kind) {
            ElementKind.TEXT_BOX -> TextBoxEdit.create(
                id = id,
                text = text.orEmpty(),
                widthPt = widthPt,
                heightPt = heightPt,
                transform = transform,
                z = z,
                style = style,
                format = ObjectFormat.decode(format),
                nextOpId = nextOpId,
                nextClock = nextClock,
            )

            ElementKind.IMAGE -> asset?.let {
                ImageEdit.create(
                    id = id,
                    asset = it,
                    widthPt = widthPt,
                    heightPt = heightPt,
                    transform = transform,
                    z = z,
                    style = style,
                    format = ObjectFormat.decode(format),
                    nextOpId = nextOpId,
                    nextClock = nextClock,
                )
            }.orEmpty()

            ElementKind.SHAPE -> ShapeKind.entries.firstOrNull { it.name == shape }?.let { kind ->
                ShapeEdit.create(
                    id = id,
                    placement = ShapePlacement(kind, widthPt, heightPt, transform),
                    z = z,
                    style = style,
                    record = (ShapeRecord.decode(text) ?: ShapeRecord.None)
                        .remapTargets { old -> remap[old.value] },
                    nextOpId = nextOpId,
                    nextClock = nextClock,
                )
            }.orEmpty()
        }
    }

    private companion object {
        const val MATRIX_SIZE = 6

        // Named rather than literal, so a member written by one version and read by another cannot
        // be transposed silently — `Matrix3`'s own field order is the wire order here.
        const val M00 = 0
        const val M01 = 1
        const val M02 = 2
        const val M10 = 3
        const val M11 = 4
        const val M12 = 5
    }
}

/**
 * The objects inside [area], from a folded page — what *"save this as an Element"* captures.
 *
 * A pure function so the rule is testable without a page, a surface or a database. Containment is
 * by **whole bounding box**, not by overlap: a diagram half inside the box would be saved as a
 * broken diagram, and the user's own drag is the affordance for saying "include this too".
 *
 * ⚠️ Objects with no measurable bounds — every pen stroke — are skipped. See [ElementLibrary].
 */
fun objectsWithin(objects: List<InkObject>, area: PageRect): List<InkObject> = objects.filter { obj ->
    val box = obj.pageBounds() ?: return@filter false
    box.left >= area.left && box.right <= area.right && box.top >= area.top && box.bottom <= area.bottom
}
