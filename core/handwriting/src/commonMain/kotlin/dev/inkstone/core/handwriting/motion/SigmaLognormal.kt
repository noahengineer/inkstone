package dev.inkstone.core.handwriting.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ⭐ The sigma-lognormal model of rapid handwriting movements — docs/HANDWRITING-SYNTHESIS.md §6.1
 * (motor-plausible variation) and §7.2 (the synthetic corpus). The Kotlin twin of
 * `tools/handwriting/sigmalognormal.py`; the two agree on `tools/handwriting/reference.py`'s
 * trajectories ([SigmaLognormalReferenceTest]), which are computed by integrating the velocity
 * numerically rather than with either side's closed form.
 *
 * A pen-down stroke is the vector sum of lognormal velocity pulses. Pulse `j` starts at `t0`,
 * carries the pen a curvilinear distance `D` along a circular arc turning from `thetaS` to
 * `thetaE`, and its speed is a lognormal in time. The angle moves linearly with the lognormal's
 * CDF — which is also the fraction of `D` travelled — so a pulse's displacement has a closed form
 * and positions are exact.
 *
 * Double precision throughout: this runs at harvest time and in tests, never on the ink path, and
 * parity with Python is the point.
 */
public object SigmaLognormal {

    /** One lognormal velocity pulse. */
    public data class Component(
        val t0: Double,
        val distance: Double,
        val mu: Double,
        val sigma: Double,
        val thetaS: Double,
        val thetaE: Double,
    ) {
        /** When the pulse has carried 99.87% of its distance (3σ). */
        val end: Double get() = t0 + exp(mu + 3.0 * sigma)
    }

    /**
     * How a writer moves — `sigmalognormal.Timing`'s twin. `exp(mu)` is a pulse's median duration
     * after onset; [gap] is the onset-to-onset spacing as a fraction of it (small rounds corners);
     * [sizeMu] is how much longer a longer pulse takes, with [dRef] taking exactly [mu].
     */
    public data class Timing(
        val mu: Double = DEFAULT_MU,
        val sigma: Double = 0.28,
        val gap: Double = 0.45,
        val sizeMu: Double = 0.35,
        val dRef: Double = 0.6,
    )

    /** The fraction of [c]'s distance travelled by time [t]. */
    public fun fraction(c: Component, t: Double): Double =
        if (t <= c.t0) 0.0 else HALF * (1.0 + erf((ln(t - c.t0) - c.mu) / (c.sigma * SQRT2)))

    /** Where [c] has carried the pen by [t], from where it started, into [out] as `(dx, dy)`. */
    public fun displacement(c: Component, t: Double, out: DoubleArray) {
        val lam = fraction(c, t)
        if (lam == 0.0) {
            out[0] = 0.0
            out[1] = 0.0
            return
        }
        val dTheta = c.thetaE - c.thetaS
        if (abs(dTheta) < STRAIGHT) {
            out[0] = c.distance * lam * cos(c.thetaS)
            out[1] = c.distance * lam * sin(c.thetaS)
            return
        }
        val r = c.distance / dTheta
        val a = c.thetaS + dTheta * lam
        out[0] = r * (sin(a) - sin(c.thetaS))
        out[1] = r * (cos(c.thetaS) - cos(a))
    }

    /** The pen at [t]: [startX], [startY] plus every component's displacement so far. */
    public fun position(
        components: List<Component>,
        t: Double,
        startX: Double = 0.0,
        startY: Double = 0.0,
    ): DoubleArray {
        val d = DoubleArray(2)
        var x = startX
        var y = startY
        for (c in components) {
            displacement(c, t, d)
            x += d[0]
            y += d[1]
        }
        return doubleArrayOf(x, y)
    }

    /**
     * Ramer–Douglas–Peucker over [xs], [ys]: the indices of the virtual targets, both ends included.
     * Iterative with the Python twin's stack order and tie-break (the first farthest point), so the
     * two pick the same targets.
     */
    public fun simplify(xs: DoubleArray, ys: DoubleArray, tolerance: Double): IntArray {
        val n = xs.size
        if (n <= 2) return IntArray(n) { it }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to n - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            val far = farthest(xs, ys, a, b)
            if (far.second > tolerance) {
                keep[far.first] = true
                stack.addLast(far.first to b)
                stack.addLast(a to far.first)
            }
        }
        return (0 until n).filter { keep[it] }.toIntArray()
    }

    private fun farthest(xs: DoubleArray, ys: DoubleArray, a: Int, b: Int): Pair<Int, Double> {
        val dx = xs[b] - xs[a]
        val dy = ys[b] - ys[a]
        val length = hypot(dx, dy)
        var best = -1.0
        var bestI = -1
        for (i in a + 1 until b) {
            val d = if (length < DEGENERATE) {
                hypot(xs[i] - xs[a], ys[i] - ys[a])
            } else {
                abs(dx * (ys[i] - ys[a]) - dy * (xs[i] - xs[a])) / length
            }
            if (d > best) {
                best = d
                bestI = i
            }
        }
        return bestI to best
    }

    /**
     * Components that carry the pen through the skeleton's virtual targets — `sigmalognormal.plan`.
     *
     * Each piece between two targets is one arc whose chord is exactly the targets' difference and
     * whose turn is the skeleton's own over that piece (heading leaving the first target against
     * heading arriving at the second), clamped to [MAX_TURN]. With every pulse complete, the pen
     * ends precisely on the last target.
     */
    public fun plan(
        xs: DoubleArray,
        ys: DoubleArray,
        timing: Timing,
        tolerance: Double = 0.04,
        t0: Double = 0.0,
    ): List<Component> {
        val targets = simplify(xs, ys, tolerance)
        val pieces = (0 until targets.size - 1)
            .map { targets[it] to targets[it + 1] }
            .filter { (a, b) -> hypot(xs[b] - xs[a], ys[b] - ys[a]) >= TINY_CHORD }
        val out = ArrayList<Component>()
        var onset = t0
        for ((n, piece) in pieces.withIndex()) {
            val (a, b) = piece
            val cx = xs[b] - xs[a]
            val cy = ys[b] - ys[a]
            val chord = hypot(cx, cy)
            val alpha = atan2(cy, cx)
            val leave = heading(xs, ys, a, +1)
            val arrive = heading(xs, ys, b, -1)
            val turn = if (leave == null || arrive == null) 0.0 else wrap(arrive - leave)
            val dTheta = min(MAX_TURN, max(-MAX_TURN, turn))
            val distance = if (abs(dTheta) < STRAIGHT) chord else chord * (dTheta / 2.0) / sin(dTheta / 2.0)
            val mu = timing.mu + timing.sizeMu * ln(max(distance, MIN_DISTANCE) / timing.dRef)
            out += Component(onset, distance, mu, timing.sigma, alpha - dTheta / 2.0, alpha + dTheta / 2.0)
            onset += spacing(xs, ys, pieces, n, mu, timing)
        }
        return out
    }

    /**
     * Onset to onset: the writer's gap, or, at a cusp, long enough for this pulse to nearly finish.
     * A reversal is where a hand's speed reaches zero; two overlapping pulses in opposite directions
     * cancel instead, and an `h` loses its ascender and reads as `n`.
     */
    private fun spacing(
        xs: DoubleArray,
        ys: DoubleArray,
        pieces: List<Pair<Int, Int>>,
        n: Int,
        mu: Double,
        timing: Timing,
    ): Double {
        if (n + 1 < pieces.size) {
            val arrive = heading(xs, ys, pieces[n].second, -1)
            val leave = heading(xs, ys, pieces[n + 1].first, +1)
            if (arrive != null && leave != null && abs(wrap(leave - arrive)) > CUSP) {
                return exp(mu + CUSP_SIGMAS * timing.sigma)
            }
        }
        return timing.gap * exp(mu)
    }

    /** The heading leaving point [i] (`step = +1`) or arriving at it (`-1`), skipping repeated points. */
    private fun heading(xs: DoubleArray, ys: DoubleArray, i: Int, step: Int): Double? {
        var j = i + step
        while (j in xs.indices) {
            val dx = xs[j] - xs[i]
            val dy = ys[j] - ys[i]
            if (abs(dx) > DEGENERATE || abs(dy) > DEGENERATE) {
                return if (step > 0) atan2(dy, dx) else atan2(-dy, -dx)
            }
            j += step
        }
        return null
    }

    private fun wrap(angle: Double): Double {
        var a = angle
        while (a <= -PI) a += 2.0 * PI
        while (a > PI) a -= 2.0 * PI
        return a
    }

    /**
     * The error function, from the series `erf(x) = 2/√π · e^(−x²) · Σ (2x²)ⁿ x / (1·3·…·(2n+1))`.
     *
     * Common Kotlin has no `erf`, and this series has only positive terms, so nothing cancels and it
     * is accurate to a few ulp wherever it is summed; beyond |x| = [ERF_SATURATES] the answer is ±1
     * in double precision anyway.
     */
    public fun erf(x: Double): Double {
        if (x.isNaN()) return x
        val ax = abs(x)
        if (ax >= ERF_SATURATES) return if (x > 0) 1.0 else -1.0
        val x2 = ax * ax
        var term = ax
        var sum = ax
        var n = 0
        while (term > sum * ERF_EPSILON) {
            n++
            term *= 2.0 * x2 / (2 * n + 1)
            sum += term
        }
        val value = TWO_OVER_SQRT_PI * exp(-x2) * sum
        return if (x < 0) -value else value
    }

    /** A pulse turns by at most this much; past it one radius stops fitting the skeleton. */
    public val MAX_TURN: Double = 160.0 * PI / 180.0

    /** A corner sharper than this is a cusp, and the next pulse waits ([spacing]). */
    public val CUSP: Double = 110.0 * PI / 180.0

    /** How far past its median a pulse is (in sigmas) before the next one starts after a cusp. */
    public const val CUSP_SIGMAS: Double = 1.5

    /** A pulse's median duration is `exp(mu)` ≈ 0.2 s: a comfortable letter stroke. */
    public const val DEFAULT_MU: Double = -1.6

    private const val HALF = 0.5
    private val SQRT2 = sqrt(2.0)
    private val TWO_OVER_SQRT_PI = 2.0 / sqrt(PI)
    private const val STRAIGHT = 1e-9
    private const val DEGENERATE = 1e-12
    private const val TINY_CHORD = 1e-9
    private const val MIN_DISTANCE = 1e-6
    private const val ERF_SATURATES = 6.0
    private const val ERF_EPSILON = 1e-17
}
