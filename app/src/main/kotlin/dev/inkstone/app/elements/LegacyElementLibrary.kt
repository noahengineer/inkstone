package dev.inkstone.app.elements

import dev.inkstone.app.note.pageBounds
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.ImageEdit
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.InkObject
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageId
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
import dev.inkstone.core.model.foldPage
import dev.inkstone.core.storage.Bundle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * ⛔ **Superseded — read only so that what it saved can be migrated.** M8c's stand-in Element
 * library, kept for its on-disk format and nothing else.
 *
 * It was written in `:app` because `:core:storage`'s `ElementLibrary` was being built in parallel and
 * was not on the classpath; the plan was that *"moving this is a change of import"*. The move never
 * happened, and the stand-in shipped — with the one limitation that made Elements feel broken: it
 * could not hold **pen strokes**. `:app` cannot measure a stroke (SPEC §5.4) and `InkSelection` gave
 * out a count rather than ids, so a hand-drawn icon saved as *"Nothing in that box could be saved"*.
 * `:core:storage`'s version holds every kind, and the surface now measures and enumerates ink for it
 * (`InkSurfaceView.objectsWithin` / `boundsOf`). See `docs/M8-ELEMENTS-KNOCKOUT.md` §2.
 *
 * [ElementShelf] reads this index once, turns each row into a real `:core:storage` Element through
 * [asPage], saves that, and then removes the row — leaving the row's image files on disk, for
 * CLAUDE.md rule 1's reason. [save] and [stamp] survive because the migration's own tests build their
 * fixtures with them and the migration re-uses [LegacyElementMember.opsFor] to rebuild each object
 * exactly as this class would have stamped it; nothing in production calls either.
 *
 * ## On disk
 *
 * ```
 * elements/index.json                  every element's metadata and its members
 * elements/<elementId>/<assetName>     one file per image the element carries
 * ```
 */
class LegacyElementLibrary(private val fs: FileSystem, private val clock: Clock) {

    /**
     * Every saved element, newest first.
     *
     * An unreadable index answers empty rather than throwing. The library is a convenience built
     * out of copies — the originals are still in the notes they were cut from — so a corrupt index
     * must never be able to stop a note from opening, which is what an exception escaping a
     * composable's `LaunchedEffect` would do.
     */
    suspend fun list(): List<LegacyElement> = readIndex().elements.sortedByDescending { it.createdEpochMillis }

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
    suspend fun save(name: String, collection: String, objects: List<CapturedObject>): LegacyElement? {
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

        val element = LegacyElement(
            id = id,
            name = name.ifBlank { DEFAULT_NAME },
            collection = collection.ifBlank { DEFAULT_COLLECTION },
            createdEpochMillis = clock.nowEpochMillis(),
            widthPt = bounds.width,
            heightPt = bounds.height,
            members = members,
        )
        writeIndex(LegacyElementIndex(readIndex().elements + element))
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
        writeIndex(LegacyElementIndex(readIndex().elements.filterNot { it.id == id }))
    }

    /**
     * ⭐ [element] rebuilt as the page it was cut from — every member as a real object at its
     * stored relative position, with both registers and the image bytes — which is exactly what
     * `:core:storage`'s `ElementLibrary.save` takes.
     *
     * Each member keeps its **original** id (`sourceId`), so a connector's attachment, which names
     * those ids, still points at the boxes it was drawn between; the core library re-identifies on
     * every stamp anyway. Built through [LegacyElementMember.opsFor] — the same path a stamp took —
     * so the migrated Element is the one this library would have stamped, not a re-interpretation.
     *
     * @return null when nothing in the row could be rebuilt, which [ElementShelf] reports by
     *   leaving the row where it is.
     */
    suspend fun asPage(element: LegacyElement): MigratedElement? {
        val ids = element.members.associate { it.sourceId to ObjectId(it.sourceId) }
        val keys = SelectionEdit.keysAbove(null, element.members.size)
        var counter = 0
        val ops = ArrayList<PageOp>()
        val assets = LinkedHashMap<String, ByteArray>()
        element.members.forEachIndexed { index, member ->
            val id = ids.getValue(member.sourceId)
            val asset = member.asset?.let { source ->
                val name = Bundle.imageAsset(id)
                readAsset(element.id, source)?.let { assets[name] = it } ?: return@forEachIndexed
                name
            }
            ops += member.opsFor(
                id = id,
                transform = member.transform(),
                z = keys[index],
                asset = asset,
                remap = ids,
                nextOpId = { "migrate-${counter++}" },
                nextClock = { LamportClock((++counter).toLong(), MIGRATION_DEVICE) },
            )
        }
        val page = foldPage(PageId(MIGRATION_PAGE), ops)
        if (page.objects.isEmpty()) return null
        return MigratedElement(
            page = page,
            ids = page.objects.map { it.id }.toSet(),
            assets = assets,
            bounds = PageRect(0f, 0f, element.widthPt, element.heightPt),
        )
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
        element: LegacyElement,
        at: PagePoint,
        topZ: FractionalKey?,
        newObjectId: () -> ObjectId,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): LegacyElementStamp {
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
        return LegacyElementStamp(ops, assets)
    }

    private suspend fun readAsset(elementId: String, name: String): ByteArray? {
        val path = "$ELEMENTS_DIR/$elementId/$name"
        // `exists` first, never a bare `readBytes` — M12: `readBytes` throws rather than answering
        // empty, so an "is it missing" guard written as `isEmpty()` is unreachable in production
        // and the throw takes the whole operation with it.
        return if (fs.exists(path)) fs.readBytes(path) else null
    }

    private suspend fun readIndex(): LegacyElementIndex = runCatching {
        if (!fs.exists(INDEX_PATH)) return@runCatching LegacyElementIndex(emptyList())
        json.decodeFromString(LegacyElementIndex.serializer(), fs.readBytes(INDEX_PATH).decodeToString())
    }.getOrElse { LegacyElementIndex(emptyList()) }

    private suspend fun writeIndex(index: LegacyElementIndex) {
        fs.writeBytesAtomically(
            INDEX_PATH,
            json.encodeToString(LegacyElementIndex.serializer(), index).encodeToByteArray(),
        )
    }

    private fun memberOf(captured: CapturedObject, origin: Matrix3, assetName: String?): LegacyElementMember? {
        val obj = captured.obj
        val style = obj.style ?: DEFAULT_STYLE
        val relative = origin * obj.transform
        val kind = when (obj) {
            is TextBoxObject -> LegacyElementKind.TEXT_BOX
            is ImageObject -> LegacyElementKind.IMAGE
            is ShapeObject -> LegacyElementKind.SHAPE
            else -> return null
        }
        val extents = when (obj) {
            is TextBoxObject -> obj.widthPt to obj.heightPt
            is ImageObject -> obj.widthPt to obj.heightPt
            is ShapeObject -> obj.widthPt to obj.heightPt
            else -> return null
        }
        return LegacyElementMember(
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
        const val MIGRATION_PAGE = "legacy-element"
        const val MIGRATION_DEVICE = "legacy-element"

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
class LegacyElementStamp(val ops: List<PageOp>, val assets: Map<String, ByteArray>)

/** One legacy row, as the input `:core:storage`'s `ElementLibrary.save` takes. See [LegacyElementLibrary.asPage]. */
class MigratedElement(
    val page: FoldedPage,
    val ids: Set<ObjectId>,
    val assets: Map<String, ByteArray>,
    /** The row's own extent, which is where its members' relative transforms were measured from. */
    val bounds: PageRect,
)

@Serializable
data class LegacyElementIndex(val elements: List<LegacyElement> = emptyList())

@Serializable
data class LegacyElement(
    val id: String,
    val name: String,
    val collection: String,
    val createdEpochMillis: Long,
    val widthPt: Float,
    val heightPt: Float,
    val members: List<LegacyElementMember> = emptyList(),
)

enum class LegacyElementKind { TEXT_BOX, IMAGE, SHAPE }

/**
 * One object inside an element.
 *
 * ⚠️ [matrix] is **relative to the element's own top-left**, never page space. Stamping composes
 * it under a translation, so the same element lands correctly wherever it is dropped and on
 * whatever page size — which is the property a stored page-space matrix would not have.
 */
@Serializable
data class LegacyElementMember(
    /** The `ObjectId` this member was cut from — the key the connector remap is built on. */
    val sourceId: String,
    val kind: LegacyElementKind,
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
            LegacyElementKind.TEXT_BOX -> TextBoxEdit.create(
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

            LegacyElementKind.IMAGE -> asset?.let {
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

            LegacyElementKind.SHAPE -> ShapeKind.entries.firstOrNull { it.name == shape }?.let { kind ->
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
