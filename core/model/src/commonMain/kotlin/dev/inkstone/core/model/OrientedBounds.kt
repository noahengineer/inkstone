package dev.inkstone.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The smallest-area rectangle enclosing a gesture — **at any angle**, not just the axis-aligned one.
 *
 * ## Why this type exists, and what it replaced
 *
 * [ShapeSnap] classifies a closed gesture by the fraction of its bounding box that it encloses: a
 * rectangle fills all of it, an ellipse `π/4`, a triangle half. Those are facts about the shapes and
 * they need no tuning — **but only against a box the shape is square to.** Measured against an
 * *axis-aligned* box the ratio is not a property of the shape at all, it is a property of how the
 * user happened to be holding the tablet:
 *
 * | tilt of a hand-drawn square | fill vs the axis-aligned box | what M8b called it |
 * |---|---|---|
 * | 0–3° | 1.00 → 0.91 | rectangle |
 * | 4–5° | 0.88 → 0.85 | *nothing* |
 * | 6–8° | 0.83 → 0.78 | **ellipse** |
 * | 15–20° | 0.67 → 0.61 | *nothing* |
 * | 30–45° | 0.54 → 0.50 | **triangle** — a 45° diamond scored 1.000 |
 *
 * A square's axis-aligned fill is `1 / (1 + k·sin 2θ)`, which sweeps the entire feature range as it
 * turns. A **circle's is `π/4` at every angle**, because a circle's axis-aligned box does not
 * change when it rotates — so the ellipse was the one kind the old feature could actually measure,
 * and every tilted rectangle fell through the ellipse window on its way down. That is the whole of
 * *"i can like only do ovels/circles"*, and no amount of re-tuning the windows could have fixed it:
 * the feature and the thing it was supposed to measure were independent.
 *
 * Against the **minimum-area** box, all four kinds are rotation-invariant by construction — a
 * rectangle is flush with its own minimal box at every angle, a triangle's minimal box is exactly
 * twice its area (a known property: the minimal rectangle is always flush with an edge), and an
 * ellipse's is `4ab` against its `πab`. The ratios become the facts the KDoc always claimed.
 *
 * ## Cost, and where it is allowed to run
 *
 * A convex hull (monotone chain, `O(n log n)`) then one pass per hull edge (`O(h²)`, and `h` is
 * tens for a hand-drawn shape). ⚠️ **This allocates**, unlike everything else the snap path
 * touches — see [ShapeSnap.recognise]'s own note. It is legal *only* because it runs once, when the
 * hold fires, and never per input sample (CLAUDE.md rule 7). Do not call it from `sample`.
 */
public data class OrientedBox(
    val centreX: Float,
    val centreY: Float,
    val widthPt: Float,
    val heightPt: Float,
    /** Rotation of the box's local x-axis, in radians, normalised to `(-π/4, π/4]`. */
    val angleRadians: Float,
) {

    public val area: Float get() = widthPt * heightPt

    /**
     * Where this box's top-left corner lands on the page.
     *
     * The local box runs `[0, widthPt] × [0, heightPt]` like every other shape's (see
     * [ShapeGeometry]), so the placement matrix is *"move to the centre, turn, then step back by
     * half the extents"* — which puts the box's centre at [centreX], [centreY] whatever the angle.
     */
    public fun transform(): Matrix3 = Matrix3.translate(centreX, centreY) *
        Matrix3.rotate(angleRadians) *
        Matrix3.translate(-widthPt / 2f, -heightPt / 2f)
}

/** @see OrientedBox */
public object OrientedBounds {

    /**
     * The minimum-area enclosing rectangle of the first [count] points of [xs] / [ys].
     *
     * @return null when the points are fewer than three or are collinear — a gesture with no area
     *   has no orientation to find, and inventing one would place a shape at a random angle.
     */
    public fun minimumArea(xs: FloatArray, ys: FloatArray, count: Int): OrientedBox? {
        val hull = convexHull(xs, ys, count) ?: return null
        return minimumAreaOfHull(xs, ys, hull)
    }

    /**
     * Indices of the convex hull in counter-clockwise order, or null if there is no hull with area.
     *
     * Andrew's monotone chain. The `<= 0` in both passes drops collinear points, which is what the
     * caliper loop below wants — a run of collinear hull points would test the same edge direction
     * repeatedly and change no answer.
     */
    private fun convexHull(xs: FloatArray, ys: FloatArray, count: Int): IntArray? {
        if (count < MIN_POINTS) return null
        val order = IntArray(count) { it }
        sortByXThenY(order, xs, ys)

        val chain = IntArray(2 * count)
        var size = 0
        for (index in 0 until count) {
            val point = order[index]
            while (size >= 2 && cross(xs, ys, chain[size - 2], chain[size - 1], point) <= 0f) size--
            chain[size++] = point
        }
        val lowerEnd = size + 1
        for (index in count - 2 downTo 0) {
            val point = order[index]
            while (size >= lowerEnd && cross(xs, ys, chain[size - 2], chain[size - 1], point) <= 0f) size--
            chain[size++] = point
        }
        // The chain closes on its own first point; drop the repeat.
        val hullSize = size - 1
        if (hullSize < MIN_POINTS) return null
        return chain.copyOf(hullSize)
    }

    /**
     * Rotating calipers, in its `O(h²)` form.
     *
     * The minimum-area enclosing rectangle of a convex polygon is always **flush with one of its
     * edges** — that is the theorem this rests on — so trying every edge as the box's x-axis and
     * keeping the smallest is exact, not an approximation. The advancing-caliper form is `O(h)` and
     * would be worth writing if `h` were large; for a hand-drawn shape it is tens, and the
     * quadratic form has no bookkeeping to get wrong.
     */
    private fun minimumAreaOfHull(xs: FloatArray, ys: FloatArray, hull: IntArray): OrientedBox? {
        var bestArea = Float.MAX_VALUE
        var best: OrientedBox? = null

        for (edge in hull.indices) {
            val candidate = boxAlong(xs, ys, hull, edge)
            if (candidate != null && candidate.area < bestArea) {
                bestArea = candidate.area
                best = candidate
            }
        }
        return best?.takeIf { it.area > EPSILON }
    }

    /** The enclosing box whose x-axis runs along hull edge [edge], or null for a degenerate edge. */
    private fun boxAlong(xs: FloatArray, ys: FloatArray, hull: IntArray, edge: Int): OrientedBox? {
        val from = hull[edge]
        val to = hull[(edge + 1) % hull.size]
        val edgeLength = hypot(xs[to] - xs[from], ys[to] - ys[from])
        if (edgeLength < EPSILON) return null
        val axisX = (xs[to] - xs[from]) / edgeLength
        val axisY = (ys[to] - ys[from]) / edgeLength

        var minAlong = Float.MAX_VALUE
        var maxAlong = -Float.MAX_VALUE
        var minAcross = Float.MAX_VALUE
        var maxAcross = -Float.MAX_VALUE
        for (index in hull) {
            val along = xs[index] * axisX + ys[index] * axisY
            val across = -xs[index] * axisY + ys[index] * axisX
            if (along < minAlong) minAlong = along
            if (along > maxAlong) maxAlong = along
            if (across < minAcross) minAcross = across
            if (across > maxAcross) maxAcross = across
        }

        // Back out of the (along, across) frame: page = along·axis + across·perpendicular.
        val centreAlong = (minAlong + maxAlong) / 2f
        val centreAcross = (minAcross + maxAcross) / 2f
        return normalised(
            centreX = centreAlong * axisX - centreAcross * axisY,
            centreY = centreAlong * axisY + centreAcross * axisX,
            width = maxAlong - minAlong,
            height = maxAcross - minAcross,
            angle = atan2(axisY, axisX),
        )
    }

    /**
     * The same rectangle, expressed with its angle in `(-π/4, π/4]`.
     *
     * A rectangle is unchanged by a quarter turn with its extents swapped, so the caliper loop can
     * return any of four equivalent descriptions of one box — and which one it happens to return
     * depends on which edge it walked first. ⚠️ Without this a rectangle drawn 2° off-square could
     * come back as *"88° with the extents swapped"*, which is the same rectangle and a wholly
     * different-looking `SetTransform`: the shape would be placed correctly and then be impossible
     * to reason about, and [ShapeSnap.straightened] could not tell it was nearly axis-aligned.
     */
    private fun normalised(centreX: Float, centreY: Float, width: Float, height: Float, angle: Float): OrientedBox {
        var turned = angle
        var w = width
        var h = height
        while (turned > QUARTER_TURN) {
            turned -= HALF_PI
            val swap = w
            w = h
            h = swap
        }
        while (turned <= -QUARTER_TURN) {
            turned += HALF_PI
            val swap = w
            w = h
            h = swap
        }
        return OrientedBox(centreX, centreY, w, h, turned)
    }

    /** `(b - a) × (c - a)` — positive when `a → b → c` turns counter-clockwise in a y-down page. */
    private fun cross(xs: FloatArray, ys: FloatArray, a: Int, b: Int, c: Int): Float =
        (xs[b] - xs[a]) * (ys[c] - ys[a]) - (ys[b] - ys[a]) * (xs[c] - xs[a])

    /**
     * Heapsort over an index array, by x then y.
     *
     * Hand-written because `IntArray` has no `sortedWith`, and the boxing overload would allocate an
     * `Integer` per sample on a path that already allocates more than the rest of the snap does.
     * Heapsort rather than quicksort for the worst case: a gesture drawn as a straight vertical line
     * arrives already sorted on x, which is quicksort's quadratic input and is a shape a user draws
     * constantly.
     */
    private fun sortByXThenY(order: IntArray, xs: FloatArray, ys: FloatArray) {
        val size = order.size
        for (start in size / 2 - 1 downTo 0) siftDown(order, start, size, xs, ys)
        for (end in size - 1 downTo 1) {
            val swap = order[0]
            order[0] = order[end]
            order[end] = swap
            siftDown(order, 0, end, xs, ys)
        }
    }

    private fun siftDown(order: IntArray, from: Int, size: Int, xs: FloatArray, ys: FloatArray) {
        var root = from
        while (true) {
            val left = 2 * root + 1
            if (left >= size) return
            var largest = left
            val right = left + 1
            if (right < size && precedes(order[left], order[right], xs, ys)) largest = right
            if (!precedes(order[root], order[largest], xs, ys)) return
            val swap = order[root]
            order[root] = order[largest]
            order[largest] = swap
            root = largest
        }
    }

    private fun precedes(a: Int, b: Int, xs: FloatArray, ys: FloatArray): Boolean =
        if (xs[a] != xs[b]) xs[a] < xs[b] else ys[a] < ys[b]

    /** Three points is the fewest that can enclose an area. */
    private const val MIN_POINTS = 3

    private const val EPSILON = 1e-6f

    private const val HALF_PI = (PI / 2.0).toFloat()

    private val QUARTER_TURN = (PI / 4.0).toFloat()
}

/**
 * [degrees] of tilt, as a signed angle in radians — the amount [OrientedBox.angleRadians] would have
 * to change to be axis-aligned.
 */
internal fun OrientedBox.tiltFromAxis(): Float = abs(angleRadians)
