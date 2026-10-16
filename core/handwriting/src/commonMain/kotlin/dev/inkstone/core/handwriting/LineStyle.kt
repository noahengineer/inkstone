package dev.inkstone.core.handwriting

import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PenTrajectory
import dev.inkstone.core.model.SampledStroke
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * ⭐ How one line of someone's writing sits on the page — docs/HANDWRITING-SYNTHESIS.md §5.2.
 *
 * Two callers, one estimator: the harvester normalises every word it learns in the frame of its
 * line, and at drop time (D15) the same measurement of the lines *around* the drop point is what
 * makes generated text take the size, slant and tilt of the writing beside it. Hence a value
 * measured from a line rather than a step private to the harvester.
 *
 * Everything is in page space, where **y grows downward** (SPEC §4.4): the baseline is the *lower*
 * edge of the writing, and "above the baseline" is smaller y.
 *
 * @property baselineAtZero the baseline's y where it would cross x = 0. With [slope], the line.
 * @property slope dy/dx of the baseline. Positive runs downhill to the right.
 * @property xHeightPt the height of a lowercase `x` above the baseline, in points.
 * @property slantRad how far the writing leans: positive means the tops of letters are to the
 *   right of their bottoms, the ordinary forward slant.
 * @property bounds the box around the line's samples.
 */
public data class LineStyle(
    val baselineAtZero: Float,
    val slope: Float,
    val xHeightPt: Float,
    val slantRad: Float,
    val bounds: PageRect,
) {
    public val angleRad: Float get() = atan(slope)

    public fun baselineY(x: Float): Float = baselineAtZero + slope * x

    /** The frame a word beginning at [left] on this line is learned in. */
    public fun frameAt(left: Float): NormalFrame = NormalFrame(left, baselineY(left), angleRad, slantRad, xHeightPt)
}

/**
 * A word's own coordinate system: the origin on its baseline at its left edge, x along the
 * baseline, y **up** from it, the slant removed and one x-height as the unit.
 *
 * In this frame two words written at different sizes, angles and slants can be compared — which is
 * what fidelity (§8.3), assembly (§6.1) and the model (§7) all need — and undoing it with another
 * frame's numbers is how a learned word is written into a different line.
 */
public data class NormalFrame(
    val originX: Float,
    val originY: Float,
    val angleRad: Float,
    val slantRad: Float,
    val xHeightPt: Float,
) {
    private val cosA = cos(angleRad)
    private val sinA = sin(angleRad)
    private val tanS = tan(slantRad)

    /** Writes the normalised `(u, v)` of page point `(x, y)` into [out]. */
    public fun toNormal(x: Float, y: Float, out: FloatArray) {
        val dx = x - originX
        val dy = y - originY
        val along = dx * cosA + dy * sinA
        val up = dx * sinA - dy * cosA
        out[0] = (along - up * tanS) / xHeightPt
        out[1] = up / xHeightPt
    }

    /** Writes the page point of normalised `(u, v)` into [out] — [toNormal]'s inverse. */
    public fun toPage(u: Float, v: Float, out: FloatArray) {
        val up = v * xHeightPt
        val along = u * xHeightPt + up * tanS
        out[0] = originX + along * cosA + up * sinA
        out[1] = originY + along * sinA - up * cosA
    }

    /** [trajectory] in this frame. Null only when the result would not be a valid trajectory. */
    public fun normalise(trajectory: PenTrajectory): PenTrajectory? = mapped(trajectory, ::toNormal)

    /** A normalised trajectory put back on the page in this frame. */
    public fun denormalise(trajectory: PenTrajectory): PenTrajectory? = mapped(trajectory, ::toPage)

    private inline fun mapped(trajectory: PenTrajectory, map: (Float, Float, FloatArray) -> Unit): PenTrajectory? {
        val out = FloatArray(2)
        val x = FloatArray(trajectory.pointCount)
        val y = FloatArray(trajectory.pointCount)
        for (i in 0 until trajectory.pointCount) {
            map(trajectory.x[i], trajectory.y[i], out)
            x[i] = out[0]
            y[i] = out[1]
        }
        return PenTrajectory.of(x, y, trajectory.tMs, trajectory.pressure, trajectory.tilt, trajectory.strokeEnds)
    }
}

/**
 * The page as a whole, for D15: what the lines on it share.
 *
 * @property xHeightPt the median x-height of its lines, or null with no lines.
 * @property linePitchPt the median distance from one baseline to the next, or null with fewer than
 *   two lines. What a phrase dropped below a paragraph lands on.
 * @property leftMarginPt the median left edge of its lines.
 */
public data class PageStyle(
    val lines: List<LineStyle>,
    val xHeightPt: Float?,
    val linePitchPt: Float?,
    val leftMarginPt: Float?,
) {
    public companion object {
        /**
         * The pitch is measured **within paragraphs** ([paragraphGroups]). It was the median gap
         * between neighbouring baselines across the whole page, which on the physics note's mind map
         * (labels scattered everywhere) came out at 2.6 pt for writing 4.3 pt tall: every label had
         * another a hair above or below it somewhere across the page.
         */
        public fun of(lines: List<LineStyle>): PageStyle {
            if (lines.isEmpty()) return PageStyle(lines, null, null, null)
            val sorted = lines.sortedBy { it.baselineY(it.bounds.centreX) }
            val pitches = paragraphGroups(sorted).flatMap { group ->
                group.zipWithNext { a, b -> baselineAtCentre(sorted[b]) - baselineAtCentre(sorted[a]) }
            }
            return PageStyle(
                lines = sorted,
                xHeightPt = median(lines.map { it.xHeightPt }),
                linePitchPt = if (pitches.isEmpty()) null else median(pitches),
                leftMarginPt = median(lines.map { it.bounds.left }),
            )
        }
    }
}

private fun baselineAtCentre(line: LineStyle): Float = line.baselineY(line.bounds.centreX)

/**
 * Lines grouped into paragraphs, as indices into [lines], each group top to bottom: a line joins the
 * paragraph whose last line sits a plausible line pitch above it and overlaps it sideways. A page in two
 * columns makes two paragraphs side by side, and a mind map many small ones, which is right: a label
 * continued is continued where it is (D15).
 */
internal fun paragraphGroups(lines: List<LineStyle>): List<List<Int>> {
    val order = lines.indices.sortedBy { baselineAtCentre(lines[it]) }
    val groups = ArrayList<MutableList<Int>>()
    for (index in order) {
        val line = lines[index]
        val base = baselineAtCentre(line)
        val host = groups
            .filter { group ->
                val last = lines[group.last()]
                val xh = maxOf(last.xHeightPt, line.xHeightPt)
                val gap = base - baselineAtCentre(last)
                val overlap = minOf(last.bounds.right, line.bounds.right) - maxOf(last.bounds.left, line.bounds.left)
                gap in xh * MIN_PITCH_X_HEIGHTS..xh * MAX_PITCH_X_HEIGHTS && overlap > -xh * SIDEWAYS_SLACK_X_HEIGHTS
            }
            .minByOrNull { group -> base - baselineAtCentre(lines[group.last()]) }
        if (host == null) groups += mutableListOf(index) else host += index
    }
    return groups
}

private const val MIN_PITCH_X_HEIGHTS = 1.2f
private const val MAX_PITCH_X_HEIGHTS = 6f
private const val SIDEWAYS_SLACK_X_HEIGHTS = 2f

/**
 * Measures a [LineStyle] from a line's strokes.
 *
 * ## The baseline is fitted through where the pen *turned*, not through stroke bottoms
 *
 * A joined-up word is one stroke, and its lowest point is its descender — so a baseline fitted
 * through each stroke's bottom sits on the `g` of every cursive word. The turning points a pen makes
 * at the bottom of each letter are the stable feature (`turningPoints`), with descenders removed as
 * outliers below the fitted line. M7 found box bottoms to be the stable feature for *lines*
 * (SPEC §8.2); within a line it is these.
 *
 * ## x-height is the lower of two heights, when there are two
 *
 * The tops of letters fall into two groups — x-height letters and ascenders or capitals — whose
 * proportions depend on what was written. A median of all tops is therefore the x-height in
 * *"minimum"* and the ascender height in *"little bill"*. The tops are split in two where that best
 * explains them, and the lower group is the x-height when the split is real.
 *
 * ## ⚠️ Synthetic lines are the arithmetic's test, not the estimator's
 *
 * M7's lesson stands: *"a synthetic fixture is the worst possible witness for a geometric
 * heuristic."* `LineStyleTest` proves the fit recovers what a fixture was built with; whether it is
 * right about a real hand is phase 2's real-ink check (docs §12, H2).
 */
public object LineStyles {

    /** @return null for a line with nothing measurable on it. */
    @Suppress("ReturnCount")
    public fun measure(strokes: List<SampledStroke>): LineStyle? {
        val usable = strokes.filter { it.samples.isNotEmpty() }
        if (usable.isEmpty()) return null
        val bounds = usable.map { it.bounds }.reduce(PageRect::union)
        val scale = median(usable.map { it.bounds.height }).coerceAtLeast(MIN_SCALE_PT)
        val letters = usable.filter { it.bounds.height >= scale * MARK_FRACTION }.ifEmpty { usable }

        val bottoms = ArrayList<Point>()
        val tops = ArrayList<Point>()
        for (stroke in letters) {
            turningPoints(stroke, scale * PROMINENCE_FRACTION, bottoms, tops)
        }
        if (bottoms.isEmpty()) return null

        val first = fit(bottoms, scale)
        val residuals = bottoms.map { it.y - first.at(it.x) }
        val centre = median(residuals)
        val spread = median(residuals.map { abs(it - centre) }) * MAD_TO_SIGMA
        val limit = centre + maxOf(spread * OUTLIER_SIGMAS, scale * MIN_OUTLIER_FRACTION)
        val kept = bottoms.filterIndexed { index, _ -> residuals[index] <= limit }
        val baseline = if (kept.size >= 2 && kept.size < bottoms.size) fit(kept, scale) else first

        val heights = tops.map { baseline.at(it.x) - it.y }.filter { it > scale * MIN_TOP_FRACTION }
        val xHeight = xHeightOf(heights) ?: (scale * FALLBACK_X_HEIGHT_FRACTION)

        return LineStyle(
            baselineAtZero = baseline.intercept,
            slope = baseline.slope,
            xHeightPt = xHeight.coerceAtLeast(MIN_SCALE_PT),
            slantRad = slantOf(letters, scale),
            bounds = bounds,
        )
    }

    internal class Point(val x: Float, val y: Float)

    private class Line(val intercept: Float, val slope: Float) {
        fun at(x: Float): Float = intercept + slope * x
    }

    /**
     * The local extremes of y along a stroke whose swing is at least [prominence] — a zig-zag
     * filter, so the jitter of a hand holding still does not become a hundred tiny letters.
     * Each stroke's single lowest and highest samples are always included: a short stroke — an `l`,
     * an `o` — may not swing far enough inside itself to produce a turning point at all.
     */
    // A zig-zag filter is three states and two thresholds; split across functions it reads worse, not
    // better, because each branch is only meaningful beside the other two.
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private fun turningPoints(
        stroke: SampledStroke,
        prominence: Float,
        bottoms: MutableList<Point>,
        tops: MutableList<Point>,
    ) {
        val samples = stroke.samples
        var lowest = samples[0]
        var highest = samples[0]
        for (sample in samples) {
            if (sample.y > lowest.y) lowest = sample
            if (sample.y < highest.y) highest = sample
        }
        bottoms += Point(lowest.x, lowest.y)
        tops += Point(highest.x, highest.y)

        // +1 while the pen is going down the page (y increasing), -1 going up, 0 not yet known.
        var direction = 0
        var extreme = samples[0]
        for (sample in samples) {
            when (direction) {
                0 -> if (sample.y - extreme.y >= prominence) {
                    direction = 1
                    extreme = sample
                } else if (extreme.y - sample.y >= prominence) {
                    direction = -1
                    extreme = sample
                }

                1 -> if (sample.y > extreme.y) {
                    extreme = sample
                } else if (extreme.y - sample.y >= prominence) {
                    if (extreme !== lowest) bottoms += Point(extreme.x, extreme.y)
                    direction = -1
                    extreme = sample
                }

                else -> if (sample.y < extreme.y) {
                    extreme = sample
                } else if (sample.y - extreme.y >= prominence) {
                    if (extreme !== highest) tops += Point(extreme.x, extreme.y)
                    direction = 1
                    extreme = sample
                }
            }
        }
    }

    /**
     * Theil–Sen: the median of pairwise slopes, then the median intercept. Robust to the
     * descenders the caller has not removed yet, which is the point of fitting twice.
     */
    private fun fit(points: List<Point>, scale: Float): Line {
        if (points.size < 2) return Line(points.firstOrNull()?.y ?: 0f, 0f)
        val slopes = ArrayList<Float>()
        val step = maxOf(1, points.size / MAX_PAIR_POINTS)
        val sampled = points.filterIndexed { index, _ -> index % step == 0 }
        for (i in sampled.indices) {
            for (j in i + 1 until sampled.size) {
                val dx = sampled[j].x - sampled[i].x
                if (abs(dx) >= scale * MIN_PAIR_SPAN) slopes += (sampled[j].y - sampled[i].y) / dx
            }
        }
        val slope = if (slopes.isEmpty()) 0f else median(slopes).coerceIn(-MAX_SLOPE, MAX_SLOPE)
        return Line(median(points.map { it.y - slope * it.x }), slope)
    }

    /** The lower of two clusters of heights when there are two; the median when there is one. */
    private fun xHeightOf(heights: List<Float>): Float? {
        if (heights.isEmpty()) return null
        val sorted = heights.sorted()
        if (sorted.size < MIN_SPLIT_POINTS) return median(sorted)
        var bestCost = Float.MAX_VALUE
        var bestSplit = -1
        for (split in 1 until sorted.size) {
            val cost = spreadOf(sorted, 0, split) + spreadOf(sorted, split, sorted.size)
            if (cost < bestCost) {
                bestCost = cost
                bestSplit = split
            }
        }
        val lower = sorted.subList(0, bestSplit)
        val upper = sorted.subList(bestSplit, sorted.size)
        val twoGroups =
            lower.size >= sorted.size * MIN_LOWER_SHARE && upper.average() >= lower.average() * ASCENDER_RATIO
        return if (twoGroups) median(lower) else median(sorted)
    }

    private fun spreadOf(values: List<Float>, from: Int, to: Int): Float {
        if (to - from <= 1) return 0f
        var mean = 0f
        for (i in from until to) mean += values[i]
        mean /= (to - from)
        var sum = 0f
        for (i in from until to) sum += (values[i] - mean) * (values[i] - mean)
        return sum
    }

    /**
     * The length-weighted mean lean of the pen's near-vertical travel.
     *
     * Each segment is taken pointing *up* the page whichever way the pen went — a downstroke and an
     * upstroke along the same line are the same lean — and only segments within 45° of vertical
     * count: a joining stroke between letters is a slope, not a slant.
     */
    private fun slantOf(strokes: List<SampledStroke>, scale: Float): Float {
        val step = scale * SLANT_STEP_FRACTION
        var weighted = 0.0
        var total = 0.0
        for (stroke in strokes) {
            val samples = stroke.samples
            var from = 0
            for (i in 1 until samples.size) {
                var dx = samples[i].x - samples[from].x
                var dy = samples[i].y - samples[from].y
                val length = hypot(dx, dy)
                if (length < step) continue
                from = i
                if (dy > 0f) {
                    dx = -dx
                    dy = -dy
                }
                val lean = atan2(dx, -dy)
                if (abs(lean) < MAX_LEAN) {
                    weighted += lean * length
                    total += length
                }
            }
        }
        return if (total <= 0.0) 0f else (weighted / total).toFloat()
    }

    private const val MIN_SCALE_PT = 0.5f
    private const val MARK_FRACTION = 0.25f
    private const val PROMINENCE_FRACTION = 0.15f
    private const val MAD_TO_SIGMA = 1.4826f
    private const val OUTLIER_SIGMAS = 2.5f
    private const val MIN_OUTLIER_FRACTION = 0.12f
    private const val MIN_TOP_FRACTION = 0.15f
    private const val FALLBACK_X_HEIGHT_FRACTION = 0.6f
    private const val MAX_PAIR_POINTS = 60
    private const val MIN_PAIR_SPAN = 0.5f

    /** LineSegmenter's own limit: steeper than this was never accepted as a line. */
    private const val MAX_SLOPE = 0.2f
    private const val MIN_SPLIT_POINTS = 4
    private const val MIN_LOWER_SHARE = 0.2f
    private const val ASCENDER_RATIO = 1.35f
    private const val SLANT_STEP_FRACTION = 0.08f
    private const val MAX_LEAN = (kotlin.math.PI / 4).toFloat()
}

/** The middle value; the lower middle for an even count. Never called with an empty list. */
internal fun median(values: List<Float>): Float {
    val sorted = values.sorted()
    return sorted[(sorted.size - 1) / 2]
}
