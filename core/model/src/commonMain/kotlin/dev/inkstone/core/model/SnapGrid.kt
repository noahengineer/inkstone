package dev.inkstone.core.model

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * ⭐ **Snap to grid** — the lattice a shape's points are rounded onto, and the arithmetic that
 * rounds them.
 *
 * Pure data in `:core:model` for [AlignmentGuides]' reason, which is CLAUDE.md definition-of-done
 * #6: *"hoist the decision into a pure function over plain data so a test can at least assert the
 * decision was made."* What `:android:ink` does with the answer is place a shape and draw some
 * faint lines; nothing it does is a decision.
 *
 * ## ⭐ The grid is the paper's own, wherever the paper has one
 *
 * [AlignmentGuides] states the rule this file inherits: *"a guide the drag does not snap to is a
 * line that lies, and a snap with no line is the object refusing to go where it was put."* So
 * [forBackground] derives the lattice from the **template being drawn** rather than from a constant
 * of its own — a square-grid page snaps to the squares you can see, and a ruled page snaps its
 * y to the rules you can see and leaves x alone, because a ruled page has no vertical line and
 * snapping to an invisible column is exactly the second half of that sentence.
 *
 * Blank paper and an imported PDF page have no lattice to inherit, so they get
 * [DEFAULT_SPACING_PT] and [drawnByThePaper] is **false** — which is the flag `:android:ink` reads
 * to draw the grid as chrome while the setting is on. Chrome, not paper: it must never reach a
 * published PDF or a thumbnail, because it is a statement about a *setting*, not about the note.
 *
 * ## Per axis, and zero means free
 *
 * A spacing below [MIN_SPACING_PT] is *"this axis does not snap"* rather than a nullable, so ruled
 * paper needs no special case anywhere downstream: [snapX] returns its argument and every caller is
 * already written. A nullable would have to be unwrapped in six places, and the fifth one would
 * eventually round to a spacing of zero and divide by it.
 */
public data class SnapGrid(
    val spacingXPt: Float,
    val spacingYPt: Float,
    val originXPt: Float = 0f,
    val originYPt: Float = 0f,
    /**
     * Whether the paper already draws this lattice.
     *
     * False means the snap is invisible unless something draws it — see the class KDoc. It is a
     * property of *where the grid came from*, so it belongs beside the spacing rather than being
     * re-derived from the [Background] by every renderer that wants to ask.
     */
    val drawnByThePaper: Boolean = false,
) {

    public val snapsX: Boolean get() = spacingXPt >= MIN_SPACING_PT

    public val snapsY: Boolean get() = spacingYPt >= MIN_SPACING_PT

    public val isEmpty: Boolean get() = !snapsX && !snapsY

    /** [x] rounded onto the nearest grid line, or unchanged when this grid has no x lattice. */
    public fun snapX(x: Float): Float =
        if (!snapsX) x else originXPt + ((x - originXPt) / spacingXPt).roundToInt() * spacingXPt

    /** [y] rounded onto the nearest grid line, or unchanged when this grid has no y lattice. */
    public fun snapY(y: Float): Float =
        if (!snapsY) y else originYPt + ((y - originYPt) / spacingYPt).roundToInt() * spacingYPt

    /** [point] rounded onto the nearest intersection. On ruled paper that is a horizontal line. */
    public fun snap(point: PagePoint): PagePoint = PagePoint(snapX(point.x), snapY(point.y))

    /**
     * The correction that puts ([x], [y]) on the grid — a *delta*, for a caller composing one.
     *
     * A move gesture adds this to its own translation, exactly as it adds [Alignment.dx]. It is
     * `snap(p) - p` and nothing else; it exists so no call site does that subtraction twice and
     * gets one of them the wrong way round.
     */
    public fun offsetTo(x: Float, y: Float): PagePoint = PagePoint(snapX(x) - x, snapY(y) - y)

    /**
     * ⭐ The translation a move should apply: the finger's own delta, the alignment correction,
     * and the lattice — in that order of authority.
     *
     * ⚠️ **[alignment] wins, per axis.** Both are corrections to the same delta and applying both
     * would satisfy neither: the selection would land beside the guide line the user can see, which
     * is the one thing [AlignmentGuides] exists to prevent. Lining up with another object is also
     * the more specific statement — the lattice is always there, another object's edge is there
     * because somebody put it there. The grid only speaks on an axis the guides had nothing to say
     * about.
     *
     * The box's **top-left** is what lands on an intersection. Snapping whichever edge happened to
     * be nearest would make the same drag finish differently depending on the direction it came
     * from, which is a snap you cannot aim.
     *
     * @param box the selection's bounds at pen-down, [dx]/[dy] the raw drag.
     * @return the delta to move by, alignment included.
     */
    public fun moveBy(box: PageRect, dx: Float, dy: Float, alignment: Alignment): PagePoint =
        moveBy(PagePoint(box.left, box.top), dx, dy, alignment)

    /**
     * ⭐ [moveBy] with the point that lands on an intersection given explicitly — an Element copy's
     * **pivot** (SPEC §4.9.7) rather than its box's top-left. *"A pivot point for snap, so it snaps to
     * a grid when you move it"*: the corner of a hand-drawn icon's bounding box is a point nobody
     * chose, and the pivot is the one somebody did.
     */
    public fun moveBy(anchor: PagePoint, dx: Float, dy: Float, alignment: Alignment): PagePoint {
        val withGuides = PagePoint(dx + alignment.dx, dy + alignment.dy)
        if (isEmpty) return withGuides
        val offset = offsetTo(anchor.x + withGuides.x, anchor.y + withGuides.y)
        return PagePoint(
            x = if (alignment.dx == 0f) withGuides.x + offset.x else withGuides.x,
            y = if (alignment.dy == 0f) withGuides.y + offset.y else withGuides.y,
        )
    }

    /** An extent rounded to a whole number of cells, never smaller than one. */
    public fun snapWidth(widthPt: Float): Float = snapExtent(widthPt, spacingXPt, snapsX)

    /** An extent rounded to a whole number of cells, never smaller than one. */
    public fun snapHeight(heightPt: Float): Float = snapExtent(heightPt, spacingYPt, snapsY)

    private fun snapExtent(extent: Float, spacing: Float, snaps: Boolean): Float {
        if (!snaps) return extent
        return max((extent / spacing).roundToInt(), 1) * spacing
    }

    /**
     * ⭐ A shape's placement, rounded onto the grid — the one entry point for **every** kind.
     *
     * The two kinds are genuinely different edits and both live here so that no caller has to know
     * which it is holding:
     *
     * - **A segment is its two endpoints.** They are recovered from the placement, snapped
     *   independently and re-placed, which is what makes grid snap give a horizontal line for free:
     *   two intersections at the same y *are* a horizontal line. Its angle changes, and that is the
     *   whole feature — a line's angle is its endpoints.
     * - **A closed shape keeps its angle** and has its origin corner put on an intersection with
     *   its extents rounded to whole cells. Straightening a shape the user deliberately drew at
     *   20° would be a rotation nobody asked for, and rounding the extents is what makes two boxes
     *   drawn roughly the same size come out exactly the same size — which is most of what a person
     *   turning this on wants.
     *
     * @return [placement] unchanged when this grid is empty, and when snapping the two ends of a
     *   segment would collapse it onto one intersection. A zero-length line is not a tidier version
     *   of what was drawn; it is the shape disappearing.
     */
    public fun snapped(placement: ShapePlacement): ShapePlacement {
        if (isEmpty) return placement
        return if (ShapeGeometry.bends(placement.kind)) snappedSegment(placement) else snappedBox(placement)
    }

    private fun snappedSegment(placement: ShapePlacement): ShapePlacement {
        // The local segment runs along the middle of the band — see [ShapeEdit.placeSegment], which
        // is also the only thing that builds this transform.
        val middle = placement.heightPt / 2f
        val a = snap(placement.transform.map(PagePoint(0f, middle)))
        val b = snap(placement.transform.map(PagePoint(placement.widthPt, middle)))
        if (abs(a.x - b.x) < ShapeGeometry.MIN_EXTENT_PT && abs(a.y - b.y) < ShapeGeometry.MIN_EXTENT_PT) {
            return placement
        }
        return ShapeEdit.placeSegment(a.x, a.y, b.x, b.y, bandPt = placement.heightPt, kind = placement.kind)
    }

    private fun snappedBox(placement: ShapePlacement): ShapePlacement {
        val origin = placement.transform.map(PagePoint(0f, 0f))
        val moved = snap(origin)
        return placement.copy(
            widthPt = snapWidth(placement.widthPt),
            heightPt = snapHeight(placement.heightPt),
            // Pre-multiplied, so the correction is applied in **page** space and the shape's own
            // rotation is untouched. Composing on the other side would rotate the correction with
            // the shape and put the corner somewhere off the lattice.
            transform = Matrix3.translate(moved.x - origin.x, moved.y - origin.y) * placement.transform,
        )
    }

    /**
     * ⭐ A resize, with the edges the drag actually moved rounded onto the grid.
     *
     * @param from the box at pen-down, [to] the box the handle currently describes. Only the edges
     *   that differ are snapped: a LEFT grip must not quietly move the right edge, and a corner
     *   grip's two edges are both its own.
     *
     * ⚠️ **A corner drag stops preserving the aspect ratio here, and that is the intent.**
     * `SelectionHandle.resize` keeps it so that a corner feels like a scale rather than a squash;
     * a grid is a statement that edges should land on lines, and the two cannot both be honoured
     * by one rectangle. The user asked for the lattice by switching it on.
     *
     * @param minimumPt the same floor the handle enforces, applied again because rounding can cross
     *   it — a box 4 pt tall on an 18 pt grid rounds to zero height otherwise, and a box with no
     *   extent cannot be grabbed again.
     */
    public fun snappedResize(from: PageRect, to: PageRect, minimumPt: Float): PageRect {
        if (isEmpty) return to
        var left = if (moved(from.left, to.left)) snapX(to.left) else to.left
        var right = if (moved(from.right, to.right)) snapX(to.right) else to.right
        var top = if (moved(from.top, to.top)) snapY(to.top) else to.top
        var bottom = if (moved(from.bottom, to.bottom)) snapY(to.bottom) else to.bottom
        // The fixed edge is the one that did not move, so the floor pushes the moved one away
        // from it — which is what keeps a shrink-to-nothing from jumping across its own anchor.
        if (right - left < minimumPt) {
            if (moved(from.left, to.left)) left = right - minimumPt else right = left + minimumPt
        }
        if (bottom - top < minimumPt) {
            if (moved(from.top, to.top)) top = bottom - minimumPt else bottom = top + minimumPt
        }
        return PageRect(left, top, right, bottom)
    }

    private fun moved(before: Float, after: Float): Boolean = abs(after - before) > UNMOVED_PT

    /**
     * ⭐ A box someone dragged out, with every edge on the lattice — what the Element editor's area
     * tool places (SPEC §4.9.7). Each edge rounds to its nearest line, so the box lands on the lines
     * the user was aiming between; one that would collapse keeps a whole cell on that axis, because a
     * box with no extent is not something anybody dragged out.
     */
    public fun snappedBox(box: PageRect): PageRect {
        if (isEmpty) return box
        var left = snapX(box.left)
        var right = snapX(box.right)
        var top = snapY(box.top)
        var bottom = snapY(box.bottom)
        if (snapsX && right - left < spacingXPt) right = left + spacingXPt
        if (snapsY && bottom - top < spacingYPt) bottom = top + spacingYPt
        if (!snapsX) {
            left = box.left
            right = box.right
        }
        if (!snapsY) {
            top = box.top
            bottom = box.bottom
        }
        return PageRect(left, top, right, bottom)
    }

    /**
     * The smallest box on the lattice that holds all of [box] — the Element editor's *Fit*, which
     * makes the bounds the content's own box widened outward to whole cells, so a copy dropped by its
     * pivot lands every edge on a line. Never rounds inward: Fit must not put content outside.
     */
    public fun enclosing(box: PageRect): PageRect {
        if (isEmpty) return box
        return PageRect(
            if (snapsX) lineAtOrBefore(box.left, originXPt, spacingXPt) else box.left,
            if (snapsY) lineAtOrBefore(box.top, originYPt, spacingYPt) else box.top,
            if (snapsX) lineAtOrAfter(box.right, originXPt, spacingXPt) else box.right,
            if (snapsY) lineAtOrAfter(box.bottom, originYPt, spacingYPt) else box.bottom,
        )
    }

    // A value within [UNMOVED_PT] of a line counts as on it, so float noise in a measured box does
    // not push an edge that is already on the lattice out by a whole cell.
    private fun lineAtOrBefore(value: Float, origin: Float, spacing: Float): Float =
        origin + floor((value - origin + UNMOVED_PT) / spacing) * spacing

    private fun lineAtOrAfter(value: Float, origin: Float, spacing: Float): Float =
        origin + ceil((value - origin - UNMOVED_PT) / spacing) * spacing

    public companion object {

        /** No lattice at all: every `snap*` is the identity and [snapped] returns its argument. */
        public val None: SnapGrid = SnapGrid(0f, 0f)

        /**
         * The lattice for paper with none of its own — the square grid's spacing, so that turning
         * a blank page into a grid page does not change what a shape snaps to.
         */
        public const val DEFAULT_SPACING_PT: Float = 18f

        /**
         * Below this a "grid" is a rounding error rather than a lattice, and dividing by it gives
         * a snap position of infinity. Well under any spacing a template uses.
         */
        public const val MIN_SPACING_PT: Float = 1f

        /**
         * ⭐ The grid the paper is already drawing, or the default one when it draws none.
         *
         * The spacings and origins are read off [PageTemplate] and `TemplateBackground`'s own
         * loops rather than restated: a grid page rules at multiples of `spacingPt` from zero, and
         * a ruled page rules from [PageTemplate.RULED_MARGIN_PT] down. A lattice that disagreed
         * with the ink on the paper by half a cell would be worse than no lattice at all, because
         * it would look like the shape had missed.
         */
        // ⚠️ [paperLeft] / [paperTop]: `PagePainter.paintBackground` draws the template from the
        // **paper's** top-left, which is negative on an infinite canvas that has grown up or to the
        // left (SPEC §4.5). A lattice from zero was then offset from every line on the paper by
        // however far the canvas had grown — *"the grid of the note and the editor seem to be
        // offset"*. Blank paper's lattice is chrome drawn from zero, so it stays there.
        public fun forBackground(background: Background, paperLeft: Float = 0f, paperTop: Float = 0f): SnapGrid {
            val template = (background as? Background.Template)?.template ?: return blank()
            return when (template) {
                PageTemplate.BLANK -> blank()

                PageTemplate.GRID, PageTemplate.DOT_GRID -> SnapGrid(
                    spacingXPt = template.spacingPt,
                    spacingYPt = template.spacingPt,
                    originXPt = paperLeft,
                    originYPt = paperTop,
                    drawnByThePaper = true,
                )

                // ⚠️ x is deliberately free. A ruled page has no vertical line, so snapping x would
                // be the "snap with no line" this file's KDoc opens with — the shape would jump
                // sideways to a column the user cannot see and has no way to aim at.
                PageTemplate.RULED, PageTemplate.CORNELL -> SnapGrid(
                    spacingXPt = 0f,
                    spacingYPt = template.spacingPt,
                    originYPt = paperTop + PageTemplate.RULED_MARGIN_PT,
                    drawnByThePaper = true,
                )
            }
        }

        private fun blank(): SnapGrid = SnapGrid(DEFAULT_SPACING_PT, DEFAULT_SPACING_PT, drawnByThePaper = false)

        /** Float slop for *"this edge did not move"* — well under a pixel at any usable zoom. */
        private const val UNMOVED_PT = 0.01f
    }
}
