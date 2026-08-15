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
 * ## ⚠️ …but only against a box the shape is square to, and M8b used the axis-aligned one
 *
 * That is the bug this file was rewritten to fix, and it was not a tuning problem. Measured against
 * the **axis-aligned** box the ratio is not a property of the shape at all — a square's is
 * `1 / (1 + k·sin 2θ)`, which sweeps the whole feature range as the user tilts their hand, so a
 * square drawn 7° off-axis scored *ellipse* and one drawn at 45° scored *triangle at confidence
 * 1.000*. A **circle's** axis-aligned fill is `π/4` at every angle, because a circle's box does not
 * change when it turns — which made the ellipse the one kind the old feature could actually
 * measure, and every tilted rectangle fell through the ellipse window on its way down.
 *
 * The fill is now taken against [OrientedBounds.minimumArea], the smallest enclosing rectangle at
 * *any* angle. All four kinds are rotation-invariant against it by construction, so the three
 * targets are finally the facts this KDoc always claimed they were. See [OrientedBox] for the
 * table of what the old feature did to a tilted square.
 *
 * ⚠️ **What is still tuned is [DEFAULT_CONFIDENCE] and [FILL_WINDOW]**, and M7's lesson applies with
 * full force: *"a synthetic fixture is the worst possible witness for a geometric heuristic"* — its
 * rotated-text detector passed its unit test and produced 105 false positives on real pages. A test
 * in this repo can prove that a mathematically perfect ellipse classifies as an ellipse; it cannot
 * prove that a person's ellipse does. The numbers below are a starting point to be **checked by
 * drawing on the tablet**, and the acceptance criterion they exist to serve — *"no fighting it to
 * draw a freehand curve"* — is a device question, not a JVM one.
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
     * ⚠️ **This allocates**, and the class KDoc's *"no allocation at all"* was narrowed when the
     * oriented box arrived: [OrientedBounds.minimumArea] builds a convex hull and therefore an index
     * array. It runs **once**, when the hold fires and the user is already waiting for a shape to
     * appear — never per input sample, which is the half of rule 7 that matters. `sample` on the
     * detector is still two float writes and a compare.
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
        val closed = closedCandidate(xs, ys, count, metrics)
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
        // ⚠️ The ramp reaches full confidence at LINE_CERTAIN, not at 1.0. Ramping to a perfectly
        // straight line made the *effective* bar 0.9685 rather than the 0.93 the constant names —
        // a line whose path is 3.2% longer than its chord was refused, and a hand-drawn one over a
        // hand's width always is.
        val confidence =
            ((straightness - LINE_MIN_STRAIGHTNESS) / (LINE_CERTAIN - LINE_MIN_STRAIGHTNESS)).coerceIn(0f, 1f)
        return SnapCandidate(
            kind = ShapeKind.LINE,
            box = metrics.box,
            from = PagePoint(xs[0], ys[0]),
            to = PagePoint(xs[count - 1], ys[count - 1]),
            confidence = confidence,
        )
    }

    /**
     * A rectangle, an ellipse or a triangle, by how much of its **minimum-area** bounding box the
     * gesture encloses.
     *
     * The gesture must close first: an open arc encloses an area too, and calling it an ellipse
     * would snap every curve the user drew on purpose.
     *
     * The box is [OrientedBounds.minimumArea] rather than the axis-aligned one, which is what makes
     * the three targets rotation-invariant — see the class KDoc for the table of what the
     * axis-aligned version did to a tilted square. It is also what the shape is *placed* by, so a
     * rectangle drawn at 20° comes out at 20° instead of being flattened onto the axes.
     */
    private fun closedCandidate(xs: FloatArray, ys: FloatArray, count: Int, metrics: Metrics): SnapCandidate? {
        if (metrics.pathLength <= 0f || metrics.endGap / metrics.pathLength > CLOSED_MAX_GAP) return null
        val oriented = OrientedBounds.minimumArea(xs, ys, count)?.takeIf { it.area > 0f } ?: return null

        val (kind, confidence) = nearestTarget(metrics.area / oriented.area) ?: return null
        return SnapCandidate(
            kind = kind,
            box = metrics.box,
            from = PagePoint(metrics.box.left, metrics.box.top),
            to = PagePoint(metrics.box.right, metrics.box.bottom),
            confidence = confidence.coerceIn(0f, 1f),
            oriented = straightened(oriented),
        )
    }

    /** Which of [FILL_TARGETS] [fill] is nearest, and how confidently. See [FILL_WINDOW]. */
    private fun nearestTarget(fill: Float): Pair<ShapeKind, Float>? {
        var best: ShapeKind? = null
        var bestConfidence = 0f
        for ((kind, target) in FILL_TARGETS) {
            val confidence = 1f - abs(fill - target) / FILL_WINDOW
            if (confidence > bestConfidence) {
                bestConfidence = confidence
                best = kind
            }
        }
        return best?.let { it to bestConfidence }
    }

    /**
     * [box] with a near-axis-aligned angle rounded to exactly zero.
     *
     * Nobody draws a rectangle to within two degrees on purpose, and a shape placed at 1.4° looks
     * like a mistake next to the text around it — the tilt is visible in a way the user did not ask
     * for and cannot easily correct. Below [STRAIGHTEN_RADIANS] the intent is *"straight"*, so it is
     * made straight; above it the tilt is deliberate and is kept exactly.
     */
    private fun straightened(box: OrientedBox): OrientedBox =
        if (abs(box.angleRadians) < STRAIGHTEN_RADIANS) box.copy(angleRadians = 0f) else box

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
     * How much of its **minimum-area** box each closed kind fills. Facts about the shapes, and now
     * true at every angle: a rectangle all of it, an ellipse `π/4`, a triangle half.
     *
     * ⚠️ Rectangle is **1.0**, not M8b's 0.95. Against the axis-aligned box no real rectangle ever
     * reached 0.95 so the target had been nudged down to meet them — which handicapped the one kind
     * it was meant to help: a geometrically perfect rectangle scored 0.643 where a perfect ellipse
     * and a perfect triangle both scored 1.000, and every degradation moved it *toward* the ellipse
     * target. Against the oriented box a rectangle really does fill all of it, so the honest number
     * is the one that works.
     *
     * The minimal enclosing rectangle of a triangle is exactly twice its area — a theorem, not a
     * measurement — so 0.5 needs no window of its own either.
     */
    private val FILL_TARGETS = listOf(
        ShapeKind.RECTANGLE to 1f,
        ShapeKind.ELLIPSE to 0.785f,
        ShapeKind.TRIANGLE to 0.5f,
    )

    /**
     * Width parameter of the window around each target, in fill-ratio units.
     *
     * ⚠️ **The accepted half-width is `FILL_WINDOW × (1 − DEFAULT_CONFIDENCE)`, not `FILL_WINDOW`** —
     * the confidence bar narrows it, and reading this constant as the tolerance is how M8b ended up
     * accepting only 36.5 % of the fill range. At 0.24 and a bar of 0.55 the half-width is 0.108,
     * giving rectangle `[0.892, 1.0]`, ellipse `[0.677, 0.893]` and triangle `[0.392, 0.608]`.
     *
     * Rectangle and ellipse now meet rather than leaving a gap, because the boundary between them is
     * real and continuous: a rectangle with corners rounded far enough *is* becoming an oval, and at
     * fill ≈ 0.89 the nearer target should simply win. The genuine no-man's-land is between ellipse
     * and triangle, where nothing a person draws lives. **Tune on the tablet, not here.**
     */
    private const val FILL_WINDOW = 0.24f

    /**
     * A gesture whose ends are further apart than this fraction of its length is not closed.
     *
     * ⚠️ Tightened from M8b's 0.22, which was loose enough to let an **open 300° arc** through at
     * ellipse-confidence 0.84 — so a `C`, a bracket or any large open curve held still was eaten and
     * turned into an oval. That is the second half of *"i can only do ovels"*, and it is a
     * completely separate mechanism from the fill ratio. At 0.12 an arc must reach about 322° before
     * it counts as closed, while a 200 × 120 rectangle may still leave a 77 pt gap at its corner.
     */
    private const val CLOSED_MAX_GAP = 0.12f

    private const val LINE_MIN_STRAIGHTNESS = 0.93f

    /** Where a line's confidence ramp reaches 1.0. See [openCandidate] for why it is not 1.0. */
    private const val LINE_CERTAIN = 0.96f

    /** Two degrees. Below this a tilt is a wobble, not a decision. See [straightened]. */
    private const val STRAIGHTEN_RADIANS = 0.035f

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
 *
 * @param box the axis-aligned bounds. Kept for callers that want a quick page-space extent; it is
 *   **not** what a closed shape is placed by any more.
 * @param oriented the minimum-area box, present for a closed candidate and null for a line. This is
 *   what [placement] uses, and it is why a rectangle drawn at an angle now stays at that angle.
 */
public data class SnapCandidate(
    val kind: ShapeKind,
    val box: PageRect,
    val from: PagePoint,
    val to: PagePoint,
    val confidence: Float,
    val oriented: OrientedBox? = null,
) {

    /** Where this candidate's shape sits, ready for [ShapeEdit.create]. */
    public fun placement(bandPt: Float = 1f, asKind: ShapeKind = kind): ShapePlacement =
        if (asKind == ShapeKind.LINE || asKind == ShapeKind.ARROW) {
            ShapeEdit.placeSegment(from.x, from.y, to.x, to.y, bandPt, asKind)
        } else {
            oriented?.let { ShapeEdit.placeOriented(asKind, it) } ?: ShapeEdit.place(asKind, box)
        }
}
