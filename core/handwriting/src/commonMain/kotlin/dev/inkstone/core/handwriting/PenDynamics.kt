package dev.inkstone.core.handwriting

import dev.inkstone.core.model.PenTrajectory
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * ⭐ Pressure, tilt and timing for ink the network drew — docs/HANDWRITING-SYNTHESIS.md §7.1.
 *
 * The network outputs geometry only: no public or synthetic data has pressure or tilt, and its steps
 * are uniform in *model* time. So they come from the user's own samples:
 *
 * - **pressure** is a small least-squares fit, `p ≈ c0 + c1·speed + c2·curvature + c3·ends`, where
 *   `speed` is the point's speed over its word's median, `curvature` its turning per x-height of path
 *   (capped), and `ends` how close it is to either end of its stroke (1 at an end, 0 from a third of
 *   an x-height in). A pen presses less when it moves fast and lightens as it lands and lifts; four
 *   coefficients say how much, for this hand. Refitted whenever the profile is rebuilt;
 * - **tilt** is the median of the user's, held constant — it barely moves within a word;
 * - **timing**: a step lasts as long as the user's median pen speed takes to cover the median step,
 *   and the pen is up between strokes for the hand's own [HandStats.penUpMs].
 *
 * With no samples to fit from, pressure and tilt stay null — *unknown*, as for any trajectory that
 * never had them — and timing falls back to [DEFAULT_SPEED].
 *
 * @property speed the user's median pen speed, in x-heights per millisecond.
 */
public class PenDynamics internal constructor(
    private val coefficients: FloatArray?,
    private val tilt: Float?,
    public val speed: Float,
    private val penUpMs: Int,
) {
    /**
     * [x], [y] (the model's frame, one point per step, the pen lifting after each [strokeEnds] entry)
     * as a trajectory with this hand's timing, pressure and tilt. Null only if the result breaks a
     * [PenTrajectory] rule, which a test pins as unreachable for finite input.
     */
    public fun apply(x: FloatArray, y: FloatArray, strokeEnds: IntArray): PenTrajectory? {
        val n = x.size
        if (n == 0) return null
        val steps = (1 until n).map { hypot(x[it] - x[it - 1], y[it] - y[it - 1]) }.filter { it > 0f }.sorted()
        val stepMs = ((steps.getOrNull(steps.size / 2) ?: DEFAULT_STEP) / speed).coerceIn(MIN_STEP_MS, MAX_STEP_MS)
        val pressure = coefficients?.let { FloatArray(n) }
        val features = FloatArray(FEATURES)
        val builder = PenTrajectory.Builder(n, pressure != null, tilt != null)
        var clock = 0f
        var start = 0
        var lastMs = -1
        for (end in strokeEnds) {
            val cum = cumulative(x, y, start, end)
            for (i in start until end) {
                var ms = clock.roundToInt()
                if (ms <= lastMs) ms = lastMs + 1
                lastMs = ms
                val p = coefficients?.let { c ->
                    featuresAt(x, y, start, end, i, steps.getOrNull(steps.size / 2) ?: DEFAULT_STEP, cum, features)
                    predict(c, features)
                } ?: 0f
                builder.add(x[i], y[i], ms, p, tilt ?: 0f)
                clock = ms + stepMs
            }
            builder.endStroke()
            clock = lastMs + penUpMs.toFloat()
            start = end
        }
        return builder.build()
    }

    public companion object {
        /** About 25 x-heights a second: an ordinary hand, used until one has been measured. */
        public const val DEFAULT_SPEED: Float = 0.025f
        private const val DEFAULT_STEP = 0.12f
        private const val MIN_STEP_MS = 2f
        private const val MAX_STEP_MS = 40f
        private const val DEFAULT_PEN_UP_MS = 120
        private const val FEATURES = 4
        private const val MIN_POINTS = 50
        private const val MIN_PRESSURE = 0.05f
        private const val ENDS_REACH = 0.33f
        private const val MAX_CURVATURE = 6f
        private const val RIDGE = 1e-3
        private const val SINGULAR = 1e-12
        private const val MIN_SPAN = 1e-3f
        private val PI_F = kotlin.math.PI.toFloat()
        private val TWO_PI_F = (2 * kotlin.math.PI).toFloat()

        /** The fit's features, in order. */
        private const val F_BIAS = 0
        private const val F_SPEED = 1
        private const val F_CURVE = 2
        private const val F_ENDS = 3

        /** No hand to measure: unknown pressure and tilt, default timing. */
        public val Unmeasured: PenDynamics = PenDynamics(null, null, DEFAULT_SPEED, DEFAULT_PEN_UP_MS)

        /**
         * Fitted to [samples] — the user's words in the normalised frame (x-heights, real timing) —
         * with [penUpMs] from the hand's own stats.
         */
        public fun fit(samples: List<PenTrajectory>, penUpMs: Int): PenDynamics {
            val speeds = samples.flatMap(::speedsOf).sorted()
            val speed = speeds.getOrNull(speeds.size / 2)?.takeIf { it > 0f } ?: DEFAULT_SPEED
            val tilts = samples.flatMap { s -> s.tilt?.toList().orEmpty() }.sorted()
            val tilt = tilts.getOrNull(tilts.size / 2)
            return PenDynamics(fitPressure(samples), tilt, speed, if (penUpMs > 0) penUpMs else DEFAULT_PEN_UP_MS)
        }

        /** Every pen-down step's speed in [s], in x-heights per millisecond. */
        private fun speedsOf(s: PenTrajectory): List<Float> = (0 until s.strokeCount).flatMap { stroke ->
            (s.strokeStart(stroke) + 1 until s.strokeEnd(stroke)).mapNotNull { i ->
                val dt = s.tMs[i] - s.tMs[i - 1]
                if (dt > 0) hypot(s.x[i] - s.x[i - 1], s.y[i] - s.y[i - 1]) / dt else null
            }
        }

        /** The normal equations of the pressure fit, summed point by point. */
        private class Normal {
            val ata = Array(FEATURES) { DoubleArray(FEATURES) }
            val atb = DoubleArray(FEATURES)
            val f = FloatArray(FEATURES)
            var count = 0

            fun add(pressure: Float) {
                for (a in 0 until FEATURES) {
                    for (b in 0 until FEATURES) ata[a][b] += (f[a] * f[b]).toDouble()
                    atb[a] += (f[a] * pressure).toDouble()
                }
                count++
            }

            /** Every point of [s] that has a pressure. */
            fun addAll(s: PenTrajectory) {
                val p = s.pressure ?: return
                val steps = (1 until s.pointCount).map { hypot(s.x[it] - s.x[it - 1], s.y[it] - s.y[it - 1]) }
                    .filter { it > 0f }.sorted()
                val median = steps.getOrNull(steps.size / 2) ?: return
                for (stroke in 0 until s.strokeCount) {
                    val start = s.strokeStart(stroke)
                    val end = s.strokeEnd(stroke)
                    val cum = cumulative(s.x, s.y, start, end)
                    for (i in start until end) {
                        featuresAt(s.x, s.y, start, end, i, median, cum, f)
                        add(p[i])
                    }
                }
            }
        }

        /** Least squares over every sample point that has a pressure, or null with too few. */
        private fun fitPressure(samples: List<PenTrajectory>): FloatArray? {
            val normal = Normal()
            samples.forEach(normal::addAll)
            val ata = normal.ata
            val atb = normal.atb
            val count = normal.count
            if (count < MIN_POINTS) return null
            for (a in 0 until FEATURES) ata[a][a] += RIDGE * count
            return solve(ata, atb)?.map { it.toFloat() }?.toFloatArray()
        }

        /** `[1, speed/median, curvature, closeness to an end]` for point [i] of stroke `[start, end)`. */
        @Suppress("LongParameterList")
        private fun featuresAt(
            x: FloatArray,
            y: FloatArray,
            start: Int,
            end: Int,
            i: Int,
            median: Float,
            cum: FloatArray,
            out: FloatArray,
        ) {
            val prev = maxOf(start, i - 1)
            val next = minOf(end - 1, i + 1)
            val span = hypot(x[next] - x[prev], y[next] - y[prev])
            val hops = (next - prev).coerceAtLeast(1)
            out[F_BIAS] = 1f
            out[F_SPEED] = if (median > 0f) span / hops / median else 1f
            out[F_CURVE] = if (i in start + 1 until end - 1) {
                val a1 = atan2(y[i] - y[prev], x[i] - x[prev])
                val a2 = atan2(y[next] - y[i], x[next] - x[i])
                var turn = abs(a2 - a1)
                if (turn > PI_F) turn = TWO_PI_F - turn
                (turn / span.coerceAtLeast(MIN_SPAN)).coerceAtMost(MAX_CURVATURE) / MAX_CURVATURE
            } else {
                0f
            }
            val fromStart = cum[i - start]
            val toEnd = cum[end - 1 - start] - fromStart
            out[F_ENDS] = 1f - (minOf(fromStart, toEnd) / ENDS_REACH).coerceAtMost(1f)
        }

        /** Arc length from the stroke's first point to each of its points. */
        private fun cumulative(x: FloatArray, y: FloatArray, start: Int, end: Int): FloatArray {
            val out = FloatArray(end - start)
            for (k in start + 1 until end) out[k - start] = out[k - start - 1] + hypot(x[k] - x[k - 1], y[k] - y[k - 1])
            return out
        }

        private fun predict(c: FloatArray, f: FloatArray): Float {
            var p = 0f
            for (k in c.indices) p += c[k] * f[k]
            return p.coerceIn(MIN_PRESSURE, 1f)
        }

        /** Gaussian elimination with partial pivoting; null for a singular system. */
        private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            val m = Array(n) { a[it].copyOf() + b[it] }
            for (col in 0 until n) {
                val pivot = (col until n).maxBy { abs(m[it][col]) }
                if (abs(m[pivot][col]) < SINGULAR) return null
                val tmp = m[col]
                m[col] = m[pivot]
                m[pivot] = tmp
                for (r in 0 until n) {
                    if (r == col) continue
                    val k = m[r][col] / m[col][col]
                    for (c in col..n) m[r][c] -= k * m[col][c]
                }
            }
            return DoubleArray(n) { m[it][n] / m[it][it] }
        }
    }
}
