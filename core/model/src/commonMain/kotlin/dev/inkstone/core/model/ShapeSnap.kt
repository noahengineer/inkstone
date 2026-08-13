// Every constant here is a measured ratio with its meaning in the name beside it — `RECTANGLE_FILL`
// says more than a symbol would, and the table of three targets is the algorithm.
@file:Suppress("MagicNumber")

package dev.inkstone.core.model

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * ROADMAP M8b — **draw-and-hold shape snapping**: which shape a freehand gesture was, decided by
 * arithmetic over the points it left.
 *
 * ## This runs on the ink path, and the split is the whole design
 *
 * SPEC §2.4 and CLAUDE.md rule 7: *"no allocation in a loop, no I/O, no serialization, no
 * recognition"* between `MotionEvent` and the front buffer. Draw-and-hold is on that path by
 * definition — the user is still touching the screen — so the work is split in two:
 *
 * - **Per sample**, the caller only records two floats into a buffer it already owns and compares
 *   a distance against a threshold. No allocation, no branch into this file. That is what runs
 *   240 times a second.
 * - **Once, when the hold fires**, [recognise] makes two passes over that buffer. It is O(points)
 *   float arithmetic with **no allocation at all** — which is why the signature is three
 *   primitives and not a `List<PagePoint>`: a list would allocate one object per sample at the
 *   exact moment the user is waiting to see the shape appear.
 *
 * *"Recognition"* in rule 7's sense means a recogniser — ML Kit, a model, a dictionary lookup
 * (SPEC §8). This is a shoelace area and a bounding box.
 *
 * ## Why a ratio and not a corner detector
 *
 * A corner detector needs smoothing, and a smoothing window is a parameter that behaves
 * differently at every stylus sampling rate and every zoom level. The **area a gesture encloses,
 * as a fraction of its bounding box**, separates the three closed kinds with no parameters at all:
 * a rectangle fills its box, an ellipse fills π/4 of it, a triangle half. Those are facts about
 * the shapes, not tuning.
 *
 * ⚠️ **What is tuned is [DEFAULT_CONFIDENCE] and the width of the window around each target**, and
 * M7's lesson applies with full force here: *"a synthetic fixture is the worst possible witness
 * for a geometric heuristic"* — its rotated-text detector passed its unit test and produced 105
 * false positives on real pages. A test in this repo can prove that a mathematically perfect
 * ellipse classifies as an ellipse; it cannot prove that a person's ellipse does. The numbers
 * below are a starting point to be **checked by drawing on the tablet**, and the acceptance
 * criterion they exist to serve — *"no fighting it to draw a freehand curve"* — is a device
 * question, not a JVM one.
 *
 * ## What is not recognised
 *
 * [ShapeKind.ARROW] never comes out of here. An arrowhead is a few samples at one end of a
 * gesture, indistinguishable from a hooked pen-lift, and guessing wrong turns a line into an arrow
 * that the user then has to undo. An arrow is a **tool the user picks**, and picking it makes the
 * same gesture that yields a [ShapeKind.LINE] yield an arrow instead.
 */
public object ShapeSnap {

    /**
     * Classify the gesture held in [xs] / [ys] — the first [count] entries, in page space.
     *
     * @return null when nothing matched well enough, which **keeps the freehand ink**. That is the
     *   default and it is the behaviour the acceptance criterion asks for: snapping the user did
     *   not want is far worse than snapping they have to ask for twice.
     */
    public fun recognise(
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        minimumConfidence: Float = DEFAULT_CONFIDENCE,
    ): SnapCandidate? {
        if (count < MIN_POINTS) return null
        val metrics = measure(xs, ys, count)
        if (metrics.pathLength < MIN_PATH_LENGTH_PT) return null

        val open = openCandidate(xs, ys, count, metrics)
        val closed = closedCandidate(metrics)
        val best = listOfNotNull(open, closed).maxByOrNull { it.confidence } ?: return null
        return best.takeIf { it.confidence >= minimumConfidence }
    }

    /** Readability overload for tests and for callers not on the ink path. Allocates. */
    public fun recognise(points: List<PagePoint>, minimumConfidence: Float = DEFAULT_CONFIDENCE): SnapCandidate? {
        val xs = FloatArray(points.size) { points[it].x }
        val ys = FloatArray(points.size) { points[it].y }
        return recognise(xs, ys, points.size, minimumConfidence)
    }

    /**
     * A straight line — the one open kind.
     *
     * Straightness is `endpoint distance / path length`, which is 1 for a perfect line and falls
     * away for anything that wanders. It is scale-free and rotation-free, so it behaves the same
     * for a 2 cm line drawn quickly and a full-page one drawn slowly.
     */
    private fun openCandidate(xs: FloatArray, ys: FloatArray, count: Int, metrics: Metrics): SnapCandidate? {
        if (metrics.pathLength <= 0f) return null
        val straightness = metrics.endGap / metrics.pathLength
        if (straightness < LINE_MIN_STRAIGHTNESS) return null
        val confidence = ((straightness - LINE_MIN_STRAIGHTNESS) / (1f - LINE_MIN_STRAIGHTNESS)).coerceIn(0f, 1f)
        return SnapCandidate(
            kind = ShapeKind.LINE,
            box = metrics.box,
            from = PagePoint(xs[0], ys[0]),
            to = PagePoint(xs[count - 1], ys[count - 1]),
            confidence = confidence,
        )
    }

    /**
     * A rectangle, an ellipse or a triangle, by how much of its bounding box the gesture encloses.
     *
     * The gesture must close first: an open arc encloses an area too, and calling it an ellipse
     * would snap every curve the user drew on purpose.
     */
    private fun closedCandidate(metrics: Metrics): SnapCandidate? {
        if (metrics.pathLength <= 0f) return null
        if (metrics.endGap / metrics.pathLength > CLOSED_MAX_GAP) return null
        val boxArea = metrics.box.width * metrics.box.height
        if (boxArea <= 0f) return null
        val fill = metrics.area / boxArea

        var best: ShapeKind? = null
        var bestConfidence = 0f
        for ((kind, target) in FILL_TARGETS) {
            val confidence = 1f - abs(fill - target) / FILL_WINDOW
            if (confidence > bestConfidence) {
                bestConfidence = confidence
                best = kind
            }
        }
        val kind = best ?: return null
        return SnapCandidate(
            kind = kind,
            box = metrics.box,
            from = PagePoint(metrics.box.left, metrics.box.top),
            to = PagePoint(metrics.box.right, metrics.box.bottom),
            confidence = bestConfidence.coerceIn(0f, 1f),
        )
    }

    /**
     * Bounding box, path length, endpoint gap and enclosed area — **one pass, no allocation**
     * except the result.
     *
     * The area is the shoelace formula over the gesture treated as a closed polygon. For an open
     * gesture that is the area of the polygon you get by joining the last point back to the first,
     * which is exactly the quantity [closedCandidate] wants and is meaningless for anything it has
     * already rejected.
     */
    private fun measure(xs: FloatArray, ys: FloatArray, count: Int): Metrics {
        var left = xs[0]
        var right = xs[0]
        var top = ys[0]
        var bottom = ys[0]
        var length = 0f
        var twiceArea = 0f
        for (index in 0 until count) {
            val x = xs[index]
            val y = ys[index]
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
            if (index > 0) length += hypot(x - xs[index - 1], y - ys[index - 1])
            val next = if (index == count - 1) 0 else index + 1
            twiceArea += x * ys[next] - xs[next] * y
        }
        return Metrics(
            box = PageRect(left, top, right, bottom),
            pathLength = length,
            endGap = hypot(xs[count - 1] - xs[0], ys[count - 1] - ys[0]),
            area = abs(twiceArea) / 2f,
        )
    }

    private class Metrics(val box: PageRect, val pathLength: Float, val endGap: Float, val area: Float)

    /**
     * How much of its bounding box each closed kind fills. Facts about the shapes: a rectangle all
     * of it, an ellipse `π/4`, a triangle half.
     */
    private val FILL_TARGETS = listOf(
        ShapeKind.RECTANGLE to 0.95f,
        ShapeKind.ELLIPSE to 0.785f,
        ShapeKind.TRIANGLE to 0.5f,
    )

    /**
     * Half-width of the window around each target, in fill-ratio units.
     *
     * Chosen so the windows very nearly meet without overlapping — the gaps between the three
     * targets are 0.165 and 0.285 — which means a gesture between two of them scores low against
     * both and is refused, rather than being assigned to whichever is marginally nearer. **Tune on
     * the tablet, not here.**
     */
    private const val FILL_WINDOW = 0.14f

    /** A gesture whose ends are further apart than this fraction of its length is not closed. */
    private const val CLOSED_MAX_GAP = 0.22f

    private const val LINE_MIN_STRAIGHTNESS = 0.93f

    /** Below this, a gesture is a tap or a dot and there is nothing to recognise. */
    private const val MIN_PATH_LENGTH_PT = 12f

    private const val MIN_POINTS = 6

    /**
     * The bar a candidate must clear to replace the user's ink.
     *
     * Deliberately high. Snapping something that was meant to stay freehand costs an undo and the
     * user's trust in the feature; refusing to snap something that was meant to be a rectangle
     * costs them holding still for another moment.
     */
    public const val DEFAULT_CONFIDENCE: Float = 0.55f
}

/**
 * What a held gesture would become.
 *
 * [from] and [to] carry the endpoints because a [ShapeKind.LINE] is placed by them and not by its
 * box — see [ShapeEdit.placeSegment] for why a line that is exactly horizontal is the case that
 * forces the distinction.
 */
public data class SnapCandidate(
    val kind: ShapeKind,
    val box: PageRect,
    val from: PagePoint,
    val to: PagePoint,
    val confidence: Float,
) {

    /** Where this candidate's shape sits, ready for [ShapeEdit.create]. */
    public fun placement(bandPt: Float = 1f, asKind: ShapeKind = kind): ShapePlacement =
        if (asKind == ShapeKind.LINE || asKind == ShapeKind.ARROW) {
            ShapeEdit.placeSegment(from.x, from.y, to.x, to.y, bandPt, asKind)
        } else {
            ShapeEdit.place(asKind, box)
        }
}
