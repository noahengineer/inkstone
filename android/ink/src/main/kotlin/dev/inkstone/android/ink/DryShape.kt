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
 * Replaced `DryPlaceholder` for shapes, which is exactly the move that type's KDoc described: *"a
 * kind that paints itself replaces this type rather than configuring it"* — a new [DryObject]
 * subclass with its own silhouette, its own coverage test and its own branch in [PagePainter],
 * with the sealed hierarchy making a forgotten painter a compile error. M8c finished the job for
 * text boxes and images, and the placeholder type is gone.
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

    /**
     * ⭐ The bend a handle drag is currently proposing, or null when none is.
     *
     * ⚠️ **A preview, and therefore not the truth** — the sibling of [StrokeStore.previewTransform]
     * and there for the same reason: a curve has to follow the finger at 240 Hz and must produce
     * exactly **one** `SetTextContent` when the finger lifts, not one per frame. Held here rather
     * than pushed through [setRecord] so that a preview cannot be mistaken for a register write:
     * [record] is what the log says and what a peer will see, and it is unchanged until the commit
     * comes back through [StrokeStore.applyOps] like every other op.
     *
     * `null` is *"no preview"*, not *"straight"* — a drag back onto the chord previews a bend that
     * [ShapeGeometry.outline] itself renders straight, so the two never have to be told apart here.
     */
    private var bendPreview: PagePoint? = null

    /** True while [bendPreview] is standing in for the record's own bend. */
    private var previewingBend = false

    /**
     * ⭐ The vertices a handle drag is currently proposing — [bendPreview]'s sibling for a closed
     * shape, and there for the same reason: a drag must be visible at 240 Hz and must write exactly
     * one `SetTextContent` when the finger lifts.
     *
     * Null is *"no preview"*, never *"no points"*: a shape whose vertices have been cleared is not a
     * state this app can reach, and conflating the two would make a cancelled drag erase an edit.
     */
    private var pointsPreview: List<PagePoint>? = null

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
        // ⚠️ **A shape was the one kind that dropped its erased region on load**, and the symptom was
        // as bad as it sounds: *"if you erase or delete a shape then leave the note and enter it
        // again, the shape is back"*.
        //
        // `SetErasedRegion` was written to the journal correctly and applied to the screen
        // correctly, so nothing looked wrong until the note was re-opened. The fold then collapses
        // the register onto `ShapeObject.erased` (SPEC §4.2) and hands the payload here — and this
        // constructor read the transform, the style and the z key off it and **ignored the erased
        // region**, so the shape came back whole. `dryStrokeFor` and `DryBox`'s own init both did
        // it; a shape simply never had the line. It also cost the *published PDF* the erasure, since
        // `InkStrokeOutliner` subtracts whatever this holds.
        //
        // In the init rather than at the call site, like [DryBox]'s, because there are two
        // construction paths — `StrokeStore.dryObjectFor` on load and `addShape` on creation — and a
        // rule that has to be remembered at each of them is the rule that was missed here.
        setErased(obj.erased.toRegion())
    }

    /** The render side of `SetTextContent` — a shape's record is that register (SPEC §4.8.2). */
    fun setRecord(text: String?) {
        record = ShapeRecord.decode(text) ?: ShapeRecord.None
        // The register is the truth again, so a preview standing in front of it is over. Without
        // this, committing a bend would leave the preview shadowing the value it just wrote — and
        // the *next* op to arrive on this shape, from an undo or a peer, would be drawn with the
        // last drag's curve.
        clearBendPreview()
        rebuild(plainOutline())
    }

    /**
     * Show [local] as this shape's bend without writing anything.
     *
     * ⚠️ **Non-null, and [endPreview] is how a drag finishes.** It took a nullable and read null as
     * *"stop previewing"*, which collided with the *other* thing a null bend means — **straight**.
     * Flattening a curve therefore fell back to [record]'s bend and sprang back to the original arc
     * mid-drag. See [BendDrag], which is where the two meanings were separated.
     *
     * @return true when the outline actually changed, so a caller can skip a repaint for a frame
     *   the finger did not move enough to matter.
     */
    fun previewBend(local: PagePoint): Boolean {
        previewingBend = true
        bendPreview = local
        return rebuildIfChanged(plainOutline())
    }

    /** End any preview in flight — the drag committed, or the pointer was cancelled. */
    fun endPreview(): Boolean {
        if (!previewingBend && pointsPreview == null) return false
        clearBendPreview()
        return rebuildIfChanged(plainOutline())
    }

    /** Show [local] as this shape's vertices without writing anything. See [endPreview]. */
    fun previewPoints(local: List<PagePoint>): Boolean {
        pointsPreview = local
        return rebuildIfChanged(plainOutline())
    }

    private fun clearBendPreview() {
        previewingBend = false
        bendPreview = null
        pointsPreview = null
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
        ShapeGeometry.outline(obj.shape, obj.widthPt, obj.heightPt, record.cornerRadiusPt, bendNow(), pointsNow())

    /** The vertices to draw with: the drag in flight, else the register's. See [bendNow]. */
    fun pointsNow(): List<PagePoint>? = pointsPreview ?: record.points

    /**
     * The bend to draw with: the drag in flight, else the register's.
     *
     * ⭐ **Public, because the handle has to sit on the curve the user can see.** `ShapePoints.of`
     * takes the live bend so the middle handle tracks the ink during the drag rather than snapping
     * to it on release — and reading it off the record instead would put the handle back where the
     * curve *was* every frame.
     */
    fun bendNow(): PagePoint? = if (previewingBend) bendPreview else record.bend

    private fun rebuildIfChanged(next: ShapePath): Boolean {
        if (next == outline) return false
        rebuild(next)
        return true
    }

    private fun rebuild(next: ShapePath) {
        outline = next
        invalidateSilhouette()
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

    /**
     * ⛔ **Inflated by half the pen, and it was the bare outline for four milestones** — which made
     * an *axis-aligned* straight line impossible to move.
     *
     * A shape's outline is its centre line; [PagePainter] then strokes it with a paint of
     * [effectiveStyle]'s width, so the ink really occupies half that width either side. Both hit
     * tests beside this one already know it — [tappedAt] and [hitBy] each add
     * `widthPt / 2` to their reach — and this was the sibling that did not, which is the shape of
     * bug CLAUDE.md keeps finding by *"grepping for the sibling implementations of the invariant,
     * not for the symptom"*.
     *
     * It stayed invisible while a bounding box was only ever used to *cull*: a box a hairline too
     * small still overlaps the viewport, so nothing was ever wrongly culled by much. What made it
     * a real bug is that a **straight segment's outline is two points**, so for a horizontal or
     * vertical line the box had one extent of exactly **zero** — and `SelectionController.begin`
     * decides MOVE by asking whether the press is inside the selection box. A zero-width box
     * cannot contain anything, so the press fell through to `Mode.LASSO`: the line did not move,
     * and the lasso then cleared the selection. Reproduced on the emulator against a diagonal
     * line, whose box is a proper rectangle and which moved correctly with the identical gesture.
     *
     * ⚠️ This makes the box depend on the **style**, so [restyle] has to rebuild it. The base
     * class only records the register (see `DryObject.restyle`); `DryStroke` already overrides for
     * the same reason, because for a stroke the width is geometry too.
     */
    private fun boundsOf(shape: ShapePath): PageBounds? {
        val points = shape.flattened()
        if (points.isEmpty()) return null
        val reach = effectiveStyle().widthPt / 2f
        return PageBounds(
            points.minOf { it.x } - reach,
            points.minOf { it.y } - reach,
            points.maxOf { it.x } + reach,
            points.maxOf { it.y } + reach,
        )
    }

    /**
     * Record the register, then rebuild the box — because [boundsOf] measures the pen.
     *
     * Widening a shape's stroke really does make it cover more paper, so a box left at the old
     * width would under-report the ink on screen: the shape would cull, lasso and grab as though
     * it were still thin.
     */
    override fun restyle(style: ObjectStyle?, brushes: InkBrushFactory) {
        super.restyle(style, brushes)
        setBounds(boundsOf(outline))
    }

    /**
     * ⭐ Whether this shape draws an arrowhead right now: the record's override, then the kind.
     *
     * ⚠️ **The one place the question is answered**, and it has two readers already — the screen
     * ([PagePainter]) and the published PDF ([InkStrokeOutliner]) — both of which reach it through
     * [arrowHead]. A second copy of *"is this an arrow"* is how a note comes out of this app with a
     * head on screen and none in the file, which is the class of bug `docs/M4-PDF.md` is about.
     */
    val hasArrowHead: Boolean
        get() = record.arrow ?: (obj.shape == ShapeKind.ARROW)

    /**
     * The arrowhead's two barbs, in this object's space, or null when there is none to draw.
     *
     * Derived from the **last** segment's direction rather than from the object's rotation, so an
     * elbow route's head points along the leg it arrives on rather than along the connector's
     * nominal axis — which is the difference between an arrow that points at the box and one that
     * points past it.
     *
     * ⭐ Gated on [hasArrowHead], which is the register **or** the kind — not the kind alone, which
     * it was until a user asked to be able to say *"if its an arrow or not"* after drawing one.
     */
    fun arrowHead(lengthPt: Float): Path? {
        if (!hasArrowHead) return null
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

    /**
     * A tap anywhere **inside** a closed shape is on it, filled or not — see [DryObject.tappedAt].
     *
     * This is the one kind that overrides, because it is the one kind whose eraser rule says its
     * middle is empty. A line and an arrow are not closed, so they keep the outline test and are
     * still grabbed by reach alone.
     */
    // The Element editor's bounds are grabbed by their edge only: a tap inside them is a tap on the
    // Element, which is the thing being edited (SPEC §4.9.7).
    override fun tappedAt(localX: Float, localY: Float, reach: Float): Boolean =
        if (elementFrame) edgeTappedAt(localX, localY, reach) else hits(localX, localY, reach, outline.closed)

    override fun edgeTappedAt(localX: Float, localY: Float, reach: Float): Boolean =
        hits(localX, localY, reach, filled = false)

    private fun hits(localX: Float, localY: Float, reach: Float, filled: Boolean): Boolean = ShapeHit.hitsPoint(
        path = outline,
        filled = filled,
        x = localX,
        y = localY,
        reach = reach + (style?.widthPt ?: obj.style?.widthPt ?: 0f) / 2f,
    )

    /**
     * ⚠️ **False — so a fully erased shape is never pruned, and that is a known cost, not an
     * oversight.** On a real note (A: 101 erased objects) 17 were shapes, each still paying a
     * `saveLayer` and a `DST_OUT` composite per re-record to draw nothing.
     *
     * It is false because no coverage test for a shape is both cheap and clearly right, and a wrong
     * *true* hides a line the user can see. A stroke's test samples the tessellated mesh, which **is**
     * what the painter fills. A shape has no mesh: what `PagePainter.paintShape` paints is this
     * outline stroked at the style's width with round caps and joins, plus the fill when [fillable],
     * plus two arrowhead barbs whose length the painter derives from the width. Deciding that area
     * is covered would take a second definition of it here — the centre line densified to the
     * eraser's radius (a rectangle's edge is two points, and a hole between them is invisible to
     * a vertex test), offset either side by half the width, the interior sampled on a grid, the
     * barbs rebuilt — and a second definition of what a shape paints is the kind of thing this
     * codebase keeps finding drifted from the first. Until the painter and a test can share one
     * definition, a fully erased shape costs one layer.
     */
    override fun isErasedAway(region: ErasedRegion): Boolean = false

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
