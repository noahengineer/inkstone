package dev.inkstone.core.handwriting.quality

import dev.inkstone.core.model.PenTrajectory
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ⭐ docs/HANDWRITING-SYNTHESIS.md §8.3 — how much a generated word looks like the user's own.
 *
 * ## The distance
 *
 * Both words are in the normalised frame (x-heights, y up, baseline 0, deslanted). Each is moved so its
 * ink starts at x = 0, then:
 *
 * 1. **resampled by arc length**, [POINTS_PER_X_HEIGHT] points per x-height of path — the pen's travel
 *    in the air between strokes included, as pen-up points, so a word written in a different number of
 *    strokes, or in a different order, is visibly a different path;
 * 2. **DTW** over `(x, y, pen state)`: a step costs the distance between the two points, plus
 *    [PEN_PENALTY] when one pen is down and the other up; the total is divided by the warping path's
 *    length, so a long word is not further from itself than a short one;
 * 3. **plus a symmetric chamfer term** over the two sets of pen-down points — the mean distance from
 *    each point to the nearest point of the other word, both ways. It ignores order, so an i-dot added
 *    after the word costs nothing there, where DTW charges for it.
 *
 * `d = DTW / length + λ·chamfer`, λ = [CHAMFER_WEIGHT].
 *
 * ## The score
 *
 * `F = clamp((d_generic − d_B) / (d_generic − d_self), 0, 1)` ([score]). A raw distance means nothing to
 * a person or a threshold; this runs from *no better than the unpersonalised model* (0) to *as close as
 * you are to yourself* (1).
 */
public object Fidelity {
    /** §8.3: 32 points per x-height of path. */
    public const val POINTS_PER_X_HEIGHT: Int = 32

    /** What a DTW step costs, in x-heights, when one pen is down and the other is up. */
    public const val PEN_PENALTY: Float = 0.5f

    /** λ: the chamfer term's weight against DTW's. Both are mean distances in x-heights. */
    public const val CHAMFER_WEIGHT: Float = 1f

    /**
     * d(a, b) for two words in the normalised frame, or null when either has no ink to measure.
     * Symmetric, and zero for a word against itself.
     */
    public fun distance(a: PenTrajectory, b: PenTrajectory): Float? {
        val pa = Path.of(a) ?: return null
        val pb = Path.of(b) ?: return null
        return dtw(pa, pb) + CHAMFER_WEIGHT * chamfer(pa, pb)
    }

    /**
     * F for one word, or null when it cannot be said: the unpersonalised model is already as close as
     * the user is to themselves ([dGeneric] ≤ [dSelf]), so there is no room to improve into.
     */
    public fun score(dGeneric: Float, dBackend: Float, dSelf: Float): Float? {
        val room = dGeneric - dSelf
        if (room <= MIN_ROOM) return null
        return ((dGeneric - dBackend) / room).coerceIn(0f, 1f)
    }

    /** A word as DTW sees it: points along the pen's whole path, each down or up. */
    internal class Path(val x: FloatArray, val y: FloatArray, val down: BooleanArray) {
        val size: Int get() = x.size

        companion object {
            private const val STEP = 1f / POINTS_PER_X_HEIGHT

            fun of(t: PenTrajectory): Path? {
                if (t.pointCount == 0) return null
                var left = Float.POSITIVE_INFINITY
                for (i in 0 until t.pointCount) left = min(left, t.x[i])
                val xs = ArrayList<Float>()
                val ys = ArrayList<Float>()
                val downs = ArrayList<Boolean>()
                for (s in 0 until t.strokeCount) {
                    val from = t.strokeStart(s)
                    val to = t.strokeEnd(s)
                    if (s > 0) {
                        // The pen in the air, from where the last stroke ended to where this one starts.
                        val px = t.x[t.strokeEnd(s - 1) - 1] - left
                        val py = t.y[t.strokeEnd(s - 1) - 1]
                        walk(px, py, t.x[from] - left, t.y[from], false, xs, ys, downs, includeStart = false)
                    }
                    xs += t.x[from] - left
                    ys += t.y[from]
                    downs += true
                    for (i in from + 1 until to) {
                        walk(t.x[i - 1] - left, t.y[i - 1], t.x[i] - left, t.y[i], true, xs, ys, downs, false)
                    }
                }
                return Path(xs.toFloatArray(), ys.toFloatArray(), downs.toBooleanArray())
            }

            /** Points every [STEP] from (x0, y0) to (x1, y1), ending exactly at (x1, y1). */
            @Suppress("LongParameterList")
            private fun walk(
                x0: Float,
                y0: Float,
                x1: Float,
                y1: Float,
                down: Boolean,
                xs: MutableList<Float>,
                ys: MutableList<Float>,
                downs: MutableList<Boolean>,
                includeStart: Boolean,
            ) {
                val length = hypot(x1 - x0, y1 - y0)
                val n = max(1, ceil(length / STEP).toInt())
                for (k in (if (includeStart) 0 else 1)..n) {
                    val f = k.toFloat() / n
                    xs += x0 + (x1 - x0) * f
                    ys += y0 + (y1 - y0) * f
                    downs += down
                }
            }
        }
    }

    /** DTW's total cost over its warping path's length — two rows of cost and two of length. */
    internal fun dtw(a: Path, b: Path): Float {
        val m = b.size
        var cost = FloatArray(m)
        var length = IntArray(m)
        var prevCost = FloatArray(m)
        var prevLength = IntArray(m)
        for (i in 0 until a.size) {
            for (j in 0 until m) {
                val c = step(a, i, b, j)
                if (i == 0 && j == 0) {
                    cost[j] = c
                    length[j] = 1
                    continue
                }
                var best = Float.POSITIVE_INFINITY
                var bestLength = 0
                if (i > 0 && prevCost[j] < best) {
                    best = prevCost[j]
                    bestLength = prevLength[j]
                }
                if (j > 0 && cost[j - 1] < best) {
                    best = cost[j - 1]
                    bestLength = length[j - 1]
                }
                if (i > 0 && j > 0 && prevCost[j - 1] <= best) {
                    best = prevCost[j - 1]
                    bestLength = prevLength[j - 1]
                }
                cost[j] = best + c
                length[j] = bestLength + 1
            }
            val swapCost = prevCost
            prevCost = cost
            cost = swapCost
            val swapLength = prevLength
            prevLength = length
            length = swapLength
        }
        return prevCost[m - 1] / prevLength[m - 1]
    }

    private fun step(a: Path, i: Int, b: Path, j: Int): Float {
        val d = hypot(a.x[i] - b.x[j], a.y[i] - b.y[j])
        return if (a.down[i] == b.down[j]) d else d + PEN_PENALTY
    }

    /** The mean nearest-neighbour distance between the two words' pen-down points, both ways, averaged. */
    internal fun chamfer(a: Path, b: Path): Float {
        val ab = oneWay(a, b) ?: return 0f
        val ba = oneWay(b, a) ?: return 0f
        return (ab + ba) / 2f
    }

    private fun oneWay(from: Path, to: Path): Float? {
        var total = 0.0
        var count = 0
        for (i in 0 until from.size) {
            if (!from.down[i]) continue
            val best = nearest(from.x[i], from.y[i], to)
            if (best.isFinite()) {
                total += best
                count++
            }
        }
        return if (count == 0) null else (total / count).toFloat()
    }

    /** The distance from (x, y) to the nearest pen-down point of [to]; infinite when it has none. */
    private fun nearest(x: Float, y: Float, to: Path): Float {
        var best = Float.POSITIVE_INFINITY
        for (j in 0 until to.size) {
            val dx = x - to.x[j]
            // A point further away along x alone cannot be nearer: skip the square root.
            if (to.down[j] && abs(dx) < best) {
                val dy = y - to.y[j]
                best = minOf(best, sqrt(dx * dx + dy * dy))
            }
        }
        return best
    }

    private const val MIN_ROOM = 1e-4f
}
