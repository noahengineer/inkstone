package dev.inkstone.core.handwriting.neural

import dev.inkstone.core.model.PenTrajectory
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Ink as the network reads it: one `(dx, dy, lift)` per point, normalised, after resampling to the
 * density the model was trained at.
 *
 * @property values `(o_k, e_k)` per resampled point: the offset from the previous point (the first
 *   from the frame's origin), normalised by the training corpus's mean and spread, and whether the
 *   pen lifts after it.
 * @property sourceIndex for each point of the original trajectory, the resampled point it became —
 *   how an alignment over resampled points is spread back over the user's own.
 */
internal class ModelInputs(
    val values: FloatArray,
    val points: Int,
    val lastX: Float,
    val lastY: Float,
    private val sourceIndex: IntArray,
) {
    /** Step [t]'s input, Graves's teacher forcing: a start token, then the point before. */
    fun inputAt(t: Int, x: FloatArray) {
        if (t == 0) {
            x[0] = 0f
            x[1] = 0f
            x[2] = 1f
        } else {
            values.copyInto(x, 0, (t - 1) * PEN, t * PEN)
        }
    }

    fun spread(rows: List<FloatArray>, original: PenTrajectory): List<FloatArray> =
        List(original.pointCount) { rows[sourceIndex[it]] }

    private companion object {
        const val PEN = 3
    }
}

/**
 * ⭐ How real ink meets a network trained on synthetic ink (docs/HANDWRITING-SYNTHESIS.md §7.2).
 *
 * The corpus was sampled uniformly **in time** at [SynthesisNetwork.pointsPerChar] points per
 * character, so a slow, curvy part of a letter has more points than a fast stroke — which is how a
 * digitiser samples a real pen too, at 240 Hz rather than the corpus's rate. Resampling each stroke
 * uniformly in its own time to the same total keeps that shape and changes only the density. A
 * trajectory without timing (all zero) is resampled by arc length instead.
 */
internal object ModelInk {

    private const val PEN = 3

    /**
     * [ink] in the model's frame (x-heights, y up), as inputs for [visibleChars] characters' worth of
     * points. Null for ink with nothing to read.
     */
    fun inputs(
        ink: PenTrajectory,
        visibleChars: Int,
        pointsPerChar: Float,
        mean: FloatArray,
        std: FloatArray,
    ): ModelInputs? {
        if (ink.pointCount == 0) return null
        val target = maxOf(ink.strokeCount, (pointsPerChar * maxOf(1, visibleChars)).roundToInt())
        val measures = Array(ink.strokeCount) { measure(ink, it) }
        val total = measures.sumOf { it.last().toDouble() }
        val counts = IntArray(ink.strokeCount) { s ->
            val m = measures[s].last()
            if (total <= 0.0) 1 else maxOf(1, (target * m / total).roundToInt())
        }
        val n = counts.sum()
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val lifts = BooleanArray(n)
        val source = IntArray(ink.pointCount)
        var at = 0
        for (s in 0 until ink.strokeCount) {
            val from = ink.strokeStart(s)
            val m = measures[s]
            val count = counts[s]
            for (k in 0 until count) {
                val want = if (count == 1) 0f else m.last() * k / (count - 1)
                place(ink, from, m, want, xs, ys, at + k)
            }
            for (i in from until ink.strokeEnd(s)) {
                val f = if (m.last() > 0f) m[i - from] / m.last() else 0f
                source[i] = at + (f * (count - 1)).roundToInt().coerceIn(0, count - 1)
            }
            at += count
            lifts[at - 1] = true
        }
        val values = FloatArray(n * PEN)
        var px = 0f
        var py = 0f
        for (k in 0 until n) {
            values[k * PEN] = (xs[k] - px - mean[0]) / std[0]
            values[k * PEN + 1] = (ys[k] - py - mean[1]) / std[1]
            values[k * PEN + 2] = if (lifts[k]) 1f else 0f
            px = xs[k]
            py = ys[k]
        }
        return ModelInputs(values, n, xs[n - 1], ys[n - 1], source)
    }

    /** Each point's position along its stroke: milliseconds when the stroke has timing, else length. */
    private fun measure(ink: PenTrajectory, s: Int): FloatArray {
        val from = ink.strokeStart(s)
        val to = ink.strokeEnd(s)
        val timed = ink.tMs[to - 1] > ink.tMs[from]
        val out = FloatArray(to - from)
        for (i in from + 1 until to) {
            out[i - from] = if (timed) {
                (ink.tMs[i] - ink.tMs[from]).toFloat()
            } else {
                out[i - from - 1] + hypot(ink.x[i] - ink.x[i - 1], ink.y[i] - ink.y[i - 1])
            }
        }
        return out
    }

    /** The point at measure [want] along the stroke starting at [from], linearly interpolated. */
    @Suppress("LongParameterList")
    private fun place(
        ink: PenTrajectory,
        from: Int,
        m: FloatArray,
        want: Float,
        xs: FloatArray,
        ys: FloatArray,
        at: Int,
    ) {
        var j = 0
        while (j < m.size - 1 && m[j + 1] < want) j++
        if (j >= m.size - 1) {
            xs[at] = ink.x[from + m.size - 1]
            ys[at] = ink.y[from + m.size - 1]
            return
        }
        val span = m[j + 1] - m[j]
        val f = if (span > 0f) (want - m[j]) / span else 0f
        xs[at] = ink.x[from + j] + (ink.x[from + j + 1] - ink.x[from + j]) * f
        ys[at] = ink.y[from + j] + (ink.y[from + j + 1] - ink.y[from + j]) * f
    }
}

/**
 * The one product the network spends its time in (§7.4, measured in §19.0): `y[b] += Wᵀ·x[b]` for
 * every sequence `b`, with `W` stored **by input row** (`rows × cols`), so the inner loop is a
 * column-wise multiply-add over the outputs with no reduction. Four input rows at a time, each read
 * once per call and reused by every sequence of the batch while it is in cache — the spike measured
 * that at 31 % faster than one row at a time.
 */
internal object KernelMath {
    private const val UNROLL = 4

    // `+ 1`, `+ 2`, `+ 3` are the unroll itself.
    @Suppress("LongParameterList", "NestedBlockDepth", "MagicNumber")
    fun matVecAdd(w: FloatArray, rows: Int, cols: Int, x: FloatArray, y: FloatArray, batch: Int) {
        var j = 0
        while (j + UNROLL <= rows) {
            val r0 = j * cols
            val r1 = r0 + cols
            val r2 = r1 + cols
            val r3 = r2 + cols
            for (b in 0 until batch) {
                val xb = b * rows + j
                val x0 = x[xb]
                val x1 = x[xb + 1]
                val x2 = x[xb + 2]
                val x3 = x[xb + 3]
                val yb = b * cols
                for (i in 0 until cols) {
                    y[yb + i] += w[r0 + i] * x0 + w[r1 + i] * x1 + w[r2 + i] * x2 + w[r3 + i] * x3
                }
            }
            j += UNROLL
        }
        while (j < rows) {
            val r = j * cols
            for (b in 0 until batch) {
                val xv = x[b * rows + j]
                val yb = b * cols
                for (i in 0 until cols) y[yb + i] += w[r + i] * xv
            }
            j++
        }
    }
}
