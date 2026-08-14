package dev.inkstone.android.ink

import android.graphics.Path
import androidx.ink.geometry.MutableVec
import androidx.ink.geometry.Parallelogram
import dev.inkstone.core.model.AnchorTarget
import dev.inkstone.core.model.ConnectorPath
import dev.inkstone.core.model.ConnectorRouting
import dev.inkstone.core.model.ConnectorTargets
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.ShapeGeometry
import dev.inkstone.core.model.ShapeHit
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.ShapePath
import dev.inkstone.core.model.ShapeRecord
import dev.inkstone.core.model.ShapeSegment
import dev.inkstone.core.model.asAnchorTarget

/**
 * ⭐ M8b — a [ShapeObject] the dry layer actually **draws**.
 *
 * Replaces [DryPlaceholder] for shapes, which is exactly the move that type's KDoc describes: *"a
 * kind that paints itself replaces this type rather than configuring it"* — a new [DryObject]
 * subclass with its own silhouette, its own coverage test and its own branch in [PagePainter],
 * with the sealed hierarchy making a forgotten painter a compile error.
 *
 * ## ⭐ A connector's geometry is derived, and it is kept in the object's own space anyway
 *
 * SPEC §4.8.3: a connector's path comes from the fold at draw time and is never persisted. That
 * path is naturally **page-space** — it is computed from where the shapes it joins have ended up —
 * while every other [DryObject] holds geometry in its own space and is placed by [transform].
 *
 * Rather than special-case the whole render path, [reroute] maps the routed page-space path back
 * through the inverse of [transform] and stores *that*. The base class's invariant survives
 * untouched: [bounds] is object-space, [pageBounds] is page-space, culling and erasing and the
 * lasso all keep working, and the painter needs no branch for "this one is already in page space".
 * The cost is a handful of point transforms per re-route, and a re-route happens when something
 * moves — not per frame.
 *
 * A degenerate [transform] has no inverse, so a connector carrying one falls back to its plain
 * outline. `SelectionEdit.transform` refuses to write one; a peer running a future build might.
 *
 * ## Why the record is held rather than re-decoded
 *
 * [ShapeRecord.decode] parses a string. Doing it inside a re-route — which runs when any shape on
 * the page moves — would parse every connector's record on every drag frame. It is decoded once
 * per `SetTextContent` and kept.
 *
 * @property obj the payload as folded. ⚠️ **Read the registers off `this`, never off [obj]** — the
 *   payload is first-write-wins (SPEC §4.2) and holds the values it was folded with. It is kept
 *   for what it alone says: the shape's kind and its local extent.
 */
internal class DryShape(val obj: ShapeObject, record: ShapeRecord = ShapeRecord.None) :
    DryObject(obj.transform, obj.style, obj.z) {

    override val id: ObjectId get() = obj.id

    /** The ink band: a highlighter drawn across a diagram must tint it, exactly as it tints text. */
    override val drawsOverInk: Boolean get() = false

    var record: ShapeRecord = record
        private set

    /** How each end resolved, for a UI that wants to say "the shape this pointed at was deleted". */
    var routing: ConnectorPath? = null
        private set

    /** Object-space outline — the plain shape, or a connector's route mapped back in. */
    var outline: ShapePath = ShapePath.Empty
        private set

    /** [outline] as a platform path, rebuilt only when [outline] changes. `onDraw` must not build one. */
    val path: Path = Path()

    val isConnector: Boolean get() = record.isConnector

    val fillable: Boolean get() = ShapeGeometry.isFillable(obj.shape) && record.fillArgb != null

    init {
        rebuild(plainOutline())
    }

    /** The render side of `SetTextContent` — a shape's record is that register (SPEC §4.8.2). */
    fun setRecord(text: String?) {
        record = ShapeRecord.decode(text) ?: ShapeRecord.None
        rebuild(plainOutline())
    }

    /**
     * Recompute a connector's path against the page as it stands now.
     *
     * **Writes no op**, which is the whole of SPEC §4.8.3. Returns true when the geometry actually
     * changed, so the store can skip a redraw for a re-route that settled on the same answer — a
     * drag emits one of these per frame and most of them move nothing.
     */
    fun reroute(targets: ConnectorTargets): Boolean {
        if (!isConnector) return false
        val routed = ConnectorRouting.route(asAnchorTarget(), record, targets)
        routing = routed
        val pagePath = routed?.path ?: return false
        // Page space → this object's space, so everything downstream is unchanged. See the KDoc.
        val inverse = transform.inverse() ?: return rebuildIfChanged(plainOutline())
        return rebuildIfChanged(pagePath.under(inverse))
    }

    private fun asAnchorTarget(): AnchorTarget = obj.asAnchorTarget().copy(transform = transform)

    private fun plainOutline(): ShapePath =
        ShapeGeometry.outline(obj.shape, obj.widthPt, obj.heightPt, record.cornerRadiusPt)

    private fun rebuildIfChanged(next: ShapePath): Boolean {
        if (next == outline) return false
        rebuild(next)
        return true
    }

    private fun rebuild(next: ShapePath) {
        outline = next
        path.rewind()
        for (segment in next.segments) {
            when (segment) {
                is ShapeSegment.MoveTo -> path.moveTo(segment.x, segment.y)

                is ShapeSegment.LineTo -> path.lineTo(segment.x, segment.y)

                is ShapeSegment.CubicTo ->
                    path.cubicTo(segment.c1x, segment.c1y, segment.c2x, segment.c2y, segment.x, segment.y)

                is ShapeSegment.Close -> path.close()
            }
        }
        setBounds(boundsOf(next))
    }

    private fun boundsOf(shape: ShapePath): PageBounds? {
        val points = shape.flattened()
        if (points.isEmpty()) return null
        return PageBounds(
            points.minOf { it.x },
            points.minOf { it.y },
            points.maxOf { it.x },
            points.maxOf { it.y },
        )
    }

    /**
     * The arrowhead's two barbs, in this object's space, or null when there is none to draw.
     *
     * Derived from the **last** segment's direction rather than from the object's rotation, so an
     * elbow route's head points along the leg it arrives on rather than along the connector's
     * nominal axis — which is the difference between an arrow that points at the box and one that
     * points past it.
     */
    fun arrowHead(lengthPt: Float): Path? {
        if (obj.shape != ShapeKind.ARROW) return null
        val points = outline.flattened()
        if (points.size < 2) return null
        val tip = points.last()
        val before = points[points.size - 2]
        val dx = tip.x - before.x
        val dy = tip.y - before.y
        val length = kotlin.math.sqrt(dx * dx + dy * dy)
        if (length < MIN_DIRECTION) return null
        val ux = dx / length
        val uy = dy / length
        val head = Path()
        head.moveTo(tip.x, tip.y)
        head.lineTo(
            tip.x - (ux * COS_BARB - uy * SIN_BARB) * lengthPt,
            tip.y - (uy * COS_BARB + ux * SIN_BARB) * lengthPt,
        )
        head.moveTo(tip.x, tip.y)
        head.lineTo(
            tip.x - (ux * COS_BARB + uy * SIN_BARB) * lengthPt,
            tip.y - (uy * COS_BARB - ux * SIN_BARB) * lengthPt,
        )
        return head
    }

    /**
     * The style this shape is painted with right now — the live register, then the payload's,
     * then the defaults [PagePainter.paintShape] falls back to.
     *
     * ⭐ This is what a first restyle's **baseline** must write (`SelectionController.restyle`):
     * the register starts null for a shape a peer created without one, and the inverse of the
     * first `SetStyle` has to put back what was on screen — which is this, not an invented
     * default. Kept beside the painter's own fallback chain so the two cannot drift.
     */
    fun effectiveStyle(): ObjectStyle = style ?: obj.style ?: ObjectStyle(DEFAULT_COLOR, DEFAULT_WIDTH_PT)

    /**
     * ⭐ Arithmetic rather than a native coverage test — see [ShapeHit].
     *
     * [sweptTip] is ignored: it is the query `androidx.ink` wants for a tessellated mesh, and a
     * shape has an outline instead. The capsule carries the same sweep in the form this can use.
     */
    override fun hitBy(sweptTip: Parallelogram, capsule: ErasedRegion.Capsule): Boolean = ShapeHit.hitsCapsule(
        path = outline,
        filled = fillable,
        fromX = capsule.fromX,
        fromY = capsule.fromY,
        toX = capsule.toX,
        toY = capsule.toY,
        radius = capsule.radius,
        slop = (style?.widthPt ?: obj.style?.widthPt ?: 0f) / 2f,
    )

    /** The outline in page space — what a lasso and the tap test measure against. */
    override fun forEachPagePoint(scratch: MutableVec, action: (Float, Float) -> Unit) {
        for (point in outline.flattened()) {
            val mapped = transform.map(PagePoint(point.x, point.y))
            action(mapped.x, mapped.y)
        }
    }

    private companion object {
        /** A 30° barb, precomputed — `onDraw` is downstream of this and must not do trigonometry. */
        const val COS_BARB = 0.8660254f
        const val SIN_BARB = 0.5f
        const val MIN_DIRECTION = 1e-4f

        /** What a shape with no style register and no payload style is drawn with — the pen's default weight. */
        const val DEFAULT_WIDTH_PT = 2f
        const val DEFAULT_COLOR = 0xFF1A1A1A.toInt()
    }
}
