package dev.inkstone.core.model

import kotlin.math.abs

/**
 * ROADMAP M8a — *"alignment guides on move (dashed = centre, solid = edge)"*.
 *
 * Pure arithmetic over rectangles, in `:core:model` for the same reason [SelectionEdit] is:
 * the decision is plain data, and the alternative is a rule you can only check by dragging
 * something on a tablet. What `:android:ink` does with the answer is draw two kinds of line
 * and add [Alignment.dx]/[Alignment.dy] to the drag — nothing it does is a decision.
 *
 * ## The snap and the guide are the same fact
 *
 * A guide the drag does not snap to is a line that lies, and a snap with no line is the
 * object refusing to go where it was put. So [forMove] returns both or neither: the guides
 * are exactly the coincidences that the returned offset makes true.
 */
public object AlignmentGuides {

    /**
     * Guides and the offset that satisfies them, for a selection being dragged to [moving].
     *
     * One candidate per axis wins — the nearest within [tolerancePt] — and then *every*
     * guide that the winning offset makes exact is reported, so dragging into line with
     * three objects at once shows three lines rather than one.
     *
     * @param moving the selection's page-space bounds at the drag's current position.
     * @param others the page-space bounds of everything that is not being dragged. The
     *   caller filters: an object aligning with itself is not a guide.
     * @param page the paper, so the centre of the page is a guide. Its edges deliberately
     *   are not — ink runs to the margin all the time and a guide that fires whenever you
     *   write near the edge is noise.
     * @param tolerancePt the snap radius in **page points**, so the caller must divide by
     *   the camera scale if it wants a constant distance on the glass. It does: a snap that
     *   is half a centimetre at 100% and half a millimetre at 1000% is the same code with
     *   the conversion forgotten.
     */
    public fun forMove(
        moving: PageRect,
        others: List<PageRect>,
        page: PageSize,
        tolerancePt: Float = DEFAULT_TOLERANCE_PT,
    ): Alignment = forMove(moving, AlignmentTargets(others, page), tolerancePt)

    /**
     * The overload a *drag* should use, with the targets built once per gesture.
     *
     * [forMove] runs on every preview frame of a move, and the target list — three entries per
     * non-selected object — depends only on what is **not** being dragged, which cannot change
     * while the finger is down. Rebuilding it per call made every drag frame O(page) in
     * allocations; building [AlignmentTargets] once in the gesture's `begin` makes the per-frame
     * cost the float compares alone.
     */
    public fun forMove(
        moving: PageRect,
        targets: AlignmentTargets,
        tolerancePt: Float = DEFAULT_TOLERANCE_PT,
    ): Alignment {
        val vertical = candidates(
            movingEdges = listOf(moving.left, moving.centreX, moving.right),
            targets = targets.vertical,
            axis = GuideAxis.VERTICAL,
            tolerancePt = tolerancePt,
        )
        val horizontal = candidates(
            movingEdges = listOf(moving.top, moving.centreY, moving.bottom),
            targets = targets.horizontal,
            axis = GuideAxis.HORIZONTAL,
            tolerancePt = tolerancePt,
        )
        return Alignment(
            dx = vertical?.offset ?: 0f,
            dy = horizontal?.offset ?: 0f,
            guides = (vertical?.guides ?: emptyList()) + (horizontal?.guides ?: emptyList()),
        )
    }

    /**
     * Every position worth snapping to on one axis: each other object's near edge, centre
     * and far edge, plus the centre of the page.
     *
     * [edges] extracts the three from a rectangle, so the two axes share this and cannot
     * drift into having different rules — which is what "it snaps horizontally but not
     * vertically" would be, reported as a mystery.
     */
    private fun targetsOn(
        others: List<PageRect>,
        pageCentre: Float,
        edges: (PageRect) -> Triple<Float, Float, Float>,
    ): List<Pair<Float, GuideKind>> {
        val targets = ArrayList<Pair<Float, GuideKind>>(others.size * EDGES_PER_RECT + 1)
        for (rect in others) {
            val (near, centre, far) = edges(rect)
            targets += near to GuideKind.EDGE
            targets += centre to GuideKind.CENTRE
            targets += far to GuideKind.EDGE
        }
        targets += pageCentre to GuideKind.CENTRE
        return targets
    }

    /**
     * The nearest snap on one axis, and every guide it makes exact.
     *
     * The kind of a guide is the **target's** kind, not the moving edge's: a selection's
     * left edge landing on another object's centre line is a centre alignment, and drawing
     * it solid would say the two edges met when they did not.
     */
    private fun candidates(
        movingEdges: List<Float>,
        targets: List<Pair<Float, GuideKind>>,
        axis: GuideAxis,
        tolerancePt: Float,
    ): AxisSnap? {
        var best: Float? = null
        for (edge in movingEdges) {
            for ((target, _) in targets) {
                val delta = target - edge
                if (abs(delta) > tolerancePt) continue
                if (best == null || abs(delta) < abs(best)) best = delta
            }
        }
        val offset = best ?: return null
        // Everything the winning offset lines up exactly, deduplicated by position: two
        // objects with the same left edge are one line on screen, not two.
        val matched = LinkedHashMap<Float, GuideKind>()
        for (edge in movingEdges) {
            for ((target, kind) in targets) {
                if (abs(target - (edge + offset)) > EXACT_PT) continue
                // A centre guide wins a tie: it is the more informative of the two, and a
                // centre line coinciding with an edge line is the one case where the
                // dashed/solid distinction has something to say.
                if (kind == GuideKind.CENTRE || target !in matched) matched[target] = kind
            }
        }
        return AxisSnap(offset, matched.map { (position, kind) -> AlignmentGuide(axis, position, kind) })
    }

    private class AxisSnap(val offset: Float, val guides: List<AlignmentGuide>)

    /**
     * Every snap position on both axes, precomputed from the objects a drag cannot change.
     *
     * Built once per move gesture — see the drag overload of [forMove].
     */
    public class AlignmentTargets(others: List<PageRect>, page: PageSize) {
        internal val vertical: List<Pair<Float, GuideKind>> =
            targetsOn(others, page.widthPt / HALF) { rect -> Triple(rect.left, rect.centreX, rect.right) }
        internal val horizontal: List<Pair<Float, GuideKind>> =
            targetsOn(others, page.heightPt / HALF) { rect -> Triple(rect.top, rect.centreY, rect.bottom) }
    }

    /** About 2 mm on the page. Tuned on the tablet is the only tuning that counts. */
    private const val DEFAULT_TOLERANCE_PT = 6f

    /** Float slop for "the offset made this exact". Well below a pixel at any usable zoom. */
    private const val EXACT_PT = 0.01f

    /** The page's centre is halfway across it. Named because detekt counts `2f` as magic. */
    private const val HALF = 2f

    /** Near edge, centre, far edge — what [targetsOn] takes from each rectangle. */
    private const val EDGES_PER_RECT = 3
}

/** A guide line at constant x ([VERTICAL]) or constant y ([HORIZONTAL]), in page space. */
public enum class GuideAxis { VERTICAL, HORIZONTAL }

/** ROADMAP M8a: **dashed = centre, solid = edge**. The renderer reads this and nothing else. */
public enum class GuideKind { CENTRE, EDGE }

public data class AlignmentGuide(val axis: GuideAxis, val positionPt: Float, val kind: GuideKind)

/**
 * What a drag should do, and why.
 *
 * [dx] and [dy] are added to the drag's own translation — they are a *correction*, not the
 * move itself, and they are zero when nothing is in range.
 */
public data class Alignment(val dx: Float, val dy: Float, val guides: List<AlignmentGuide>) {
    public val isEmpty: Boolean get() = guides.isEmpty()

    public companion object {
        public val None: Alignment = Alignment(0f, 0f, emptyList())
    }
}
