package dev.inkstone.core.model

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * ROADMAP M8b — what a shape or connector gesture writes down, as a pure function of plain data.
 *
 * The sibling of [SelectionEdit], for the same reason and with the same rule: the *decision* is
 * arithmetic and belongs where a JVM test, the iOS target and SPEC §13.2's merge matrix can all
 * reach it (CLAUDE.md definition-of-done #6 — *"hoist the decision into a pure function over plain
 * data"*).
 *
 * ⭐ **Unlike a stroke, a shape's whole `AddObject` can be built here**, and the contrast is worth
 * stating because [SelectionEdit.keysAbove] exists precisely to work around the opposite case. A
 * stroke's payload is an opaque geometry blob produced by `androidx.ink`'s encoder, which lives in
 * `:android:ink` and must not run on the ink path (SPEC §5.4, CLAUDE.md rule 7) — so duplication
 * had to hoist only the z key and leave the mechanics behind. A [ShapeObject]'s payload is a kind
 * and two extents. There is nothing to encode, so [create] returns the complete gesture and the
 * ink module does no op-building of its own.
 *
 * ## No new op types, no new object kinds
 *
 * Everything here emits ops M2 already defined, on objects M2 already defined. A connector is a
 * [ShapeObject] of kind [ShapeKind.LINE] or [ShapeKind.ARROW] whose `SetTextContent` register
 * holds a [ShapeRecord]; see that type for why that is the only home available, and what it costs.
 */
public object ShapeEdit {

    /**
     * A drawn shape, as the two ops that make it.
     *
     * ⚠️ **Two ops, in this order, in one gesture** — and the order is not cosmetic. The record is
     * a register write, and a register op that sorts *before* its `AddObject` is legal but
     * pointless work for the fold (see `PageFolder`'s KDoc); more importantly, one gesture means
     * one undo entry, so *"draw a shape, press undo"* removes the shape and its record together
     * rather than leaving a recolour on a tombstone.
     *
     * @return `AddObject` alone when [record] says nothing — a plain unfilled shape needs no
     *   register, and writing an empty one would put a string in every note that draws a line.
     */
    public fun create(
        id: ObjectId,
        placement: ShapePlacement,
        z: FractionalKey,
        style: ObjectStyle? = null,
        record: ShapeRecord = ShapeRecord.None,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> {
        val shape = ShapeObject(
            id = id,
            shape = placement.kind,
            widthPt = max(placement.widthPt, ShapeGeometry.MIN_EXTENT_PT),
            heightPt = max(placement.heightPt, ShapeGeometry.MIN_EXTENT_PT),
            transform = placement.transform,
            style = style,
            z = z,
        )
        val add = PageOp.AddObject(nextOpId(), nextClock(), shape)
        if (record.isDefault) return listOf(add)
        return listOf(add, writeRecord(id, record, nextOpId, nextClock))
    }

    /**
     * A copy of a shape already on the page, at [id] — the selection tool's duplicate.
     *
     * The stroke half of that gesture is built beside the encoder
     * (`PageOpRecorder.strokeDuplicated`), because a stroke's payload is a geometry blob only
     * `:android:ink` can produce; a shape's payload is a kind and two extents, so its whole
     * gesture is built here, where a JVM test can reach it — the same contrast [create]'s KDoc
     * draws.
     *
     * [transform], [style], [z] and [erased] are the **live** register values, collapsed onto the
     * copy's payload, so the copy looks exactly like the original at the moment it was duplicated —
     * including its erased bites, which are in the object's own space and therefore travel with it
     * unchanged.
     *
     * @param record the source's record, with any end the caller wants re-targeted already passed
     *   through [ShapeRecord.remapTargets] — a duplicated diagram keeps its own wiring, while an
     *   end attached to something outside the copy keeps its target and the copy attaches to the
     *   same shape the original does.
     */
    @Suppress("LongParameterList") // The live registers, spelled out; a holder would hide which are collapsed.
    public fun duplicate(
        source: ShapeObject,
        id: ObjectId,
        transform: Matrix3,
        style: ObjectStyle?,
        erased: ErasedArea,
        z: FractionalKey,
        record: ShapeRecord,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> {
        val add = PageOp.AddObject(
            nextOpId(),
            nextClock(),
            source.copy(id = id, transform = transform, style = style, z = z, erased = erased),
        )
        if (record.isDefault) return listOf(add)
        return listOf(add, writeRecord(id, record, nextOpId, nextClock))
    }

    /**
     * The one op that changes a shape's fill, corner radius, route or attachment.
     *
     * ⭐ **This is the only op M8b ever writes about a connector**, and re-attaching an endpoint is
     * the only thing that writes it. Moving, resizing or rotating a shape a connector touches
     * writes that *shape's* absolute `SetTransform` and nothing else, however many connectors
     * touch it — the route is derived by [ConnectorRouting] at draw time (ROADMAP M8b).
     */
    public fun writeRecord(
        target: ObjectId,
        record: ShapeRecord,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): PageOp = PageOp.SetTextContent(nextOpId(), nextClock(), target, record.encode())

    /**
     * Place [kind] so that it fills [box] in page space.
     *
     * @return the extents and the matrix, ready for [create]. Extents are clamped so a tap that
     *   produced a zero-height box still yields an invertible matrix — `SelectionEdit.transform`
     *   refuses to *write* a singular one, and a shape that arrives already singular could never
     *   be moved, erased or attached to.
     */
    public fun place(kind: ShapeKind, box: PageRect): ShapePlacement {
        if (kind == ShapeKind.LINE || kind == ShapeKind.ARROW) {
            return placeSegment(box.left, box.top, box.right, box.bottom, kind = kind)
        }
        return ShapePlacement(
            kind = kind,
            widthPt = max(box.width, ShapeGeometry.MIN_EXTENT_PT),
            heightPt = max(box.height, ShapeGeometry.MIN_EXTENT_PT),
            transform = Matrix3.translate(box.left, box.top),
        )
    }

    /**
     * Place a [ShapeKind.LINE] or [ShapeKind.ARROW] from one page point to another.
     *
     * ⚠️ **A rotation, never a scale**, and this is the reason [ShapeGeometry]'s local segment runs
     * horizontally rather than along the box diagonal. Mapping a diagonal onto an arbitrary drag
     * needs a matrix whose x and y scales are the drag's components — which is **singular for a
     * horizontal or vertical line**, i.e. for the two lines people draw most. A unit-length
     * horizontal segment placed by `translate · rotate` has determinant 1 whatever the angle.
     *
     * [bandPt] is the local box's height. Nothing is drawn across it — the segment is at its
     * middle — but it must be positive, and giving it the stroke's own width makes the local box
     * agree with what is on the screen, which is what a lasso and a tap test measure against.
     */
    @Suppress("LongParameterList") // Four coordinates and two options; a point type would read worse.
    public fun placeSegment(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        bandPt: Float = 1f,
        kind: ShapeKind = ShapeKind.LINE,
    ): ShapePlacement {
        val length = max(hypot(x1 - x0, y1 - y0), ShapeGeometry.MIN_EXTENT_PT)
        val band = max(bandPt, ShapeGeometry.MIN_EXTENT_PT)
        val angle = atan2(y1 - y0, x1 - x0)
        return ShapePlacement(
            kind = kind,
            widthPt = length,
            heightPt = band,
            transform = Matrix3.translate(x0, y0) * Matrix3.rotate(angle) * Matrix3.translate(0f, -band / 2f),
        )
    }
}

/**
 * Where a shape's local box sits on the page — **and which kind it is**.
 *
 * The kind travels with the placement rather than beside it because the two must agree: a
 * [ShapeKind.LINE]'s box is a rotated band and a rectangle's is axis-aligned (see
 * [ShapeEdit.placeSegment]), so a placement computed for one kind and used for the other puts the
 * shape somewhere nobody asked for. Carrying it makes that unrepresentable instead of a rule.
 */
public data class ShapePlacement(val kind: ShapeKind, val widthPt: Float, val heightPt: Float, val transform: Matrix3)

/**
 * The [ShapeRecord] on [id], or [ShapeRecord.None] when there is none.
 *
 * ⚠️ Answers [ShapeRecord.None] rather than null for an object whose register holds something this
 * build cannot parse. A register that is not a record is a plain text register, a stranger's data,
 * or corruption; drawing the object as an ordinary shape is the only reading of it that cannot
 * invent geometry. Use [FoldedPage.textOf] directly when the difference matters.
 */
public fun FoldedPage.recordOf(id: ObjectId): ShapeRecord = ShapeRecord.decode(textOf(id)) ?: ShapeRecord.None

/**
 * Every connector on this page whose route depends on [moved].
 *
 * Not used to *write* anything — nothing is written when a shape moves (ROADMAP M8b). It exists so
 * a renderer can invalidate exactly the connectors a `SetTransform` affected instead of re-routing
 * the whole page, and so a test can assert that moving a shape reaches its connectors at all.
 */
public fun FoldedPage.connectorsTouching(moved: Set<ObjectId>): List<ShapeObject> = objects
    .filterIsInstance<ShapeObject>()
    .filter { shape -> recordOf(shape.id).targets.any { it in moved } }
