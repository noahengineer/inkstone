package dev.inkstone.core.calc.num

import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * `⌊√n⌋`, `⌊∛n⌋` and `⌊ᵏ√n⌋` of a [BigNat], exactly.
 *
 * Exact floors are what make [DecMath.sqrt] and [DecMath.cbrt] *correctly* rounded rather than
 * "correct in practice": a floor's digits are the true root's digits, so the 16th one decides a
 * half-away-from-zero rounding with no tie ever misjudged (CALCULATOR.md §3, research 07 T11).
 * [iroot] does the same for `xʸ` with a small-denominator `y` ([DecPowers]), which *can* land
 * exactly on a tie.
 *
 * All three are integer Newton iterations started **above** the root, from which they descend
 * monotonically and stop at the floor. A Double gives the seed — the only thing a Double does
 * here — and is nudged up by a part in 10⁹ so it cannot start below.
 */
internal object NatRoots {
    private const val SEED_MARGIN = 1.000000001
    private const val SEED_SLACK = 2L
    private const val SQUARE = 2
    private const val CUBE = 3
    private const val DECIMAL = 10.0

    /** Leading digits of an [iroot] seed held in the Double; the rest is a power of ten. */
    private const val SEED_DIGITS = 14

    fun isqrt(n: BigNat): BigNat {
        if (n.isZero) return BigNat.ZERO
        var x = seed(n, SQUARE)
        var y = (x + n.divRem(x).first).divRemSmall(SQUARE).first
        while (y < x) {
            x = y
            y = (x + n.divRem(x).first).divRemSmall(SQUARE).first
        }
        return x
    }

    fun icbrt(n: BigNat): BigNat {
        if (n.isZero) return BigNat.ZERO
        var x = seed(n, CUBE)
        var y = cubeStep(n, x)
        while (y < x) {
            x = y
            y = cubeStep(n, x)
        }
        return x
    }

    /**
     * `⌊ᵏ√n⌋` for `k ≥ 2`. The step `⌊((k−1)x + ⌊n/xᵏ⁻¹⌋)/k⌋` never drops below the floor (it is
     * the floor of an arithmetic mean whose geometric mean is the root) and strictly falls while
     * above it, so the first step that does not fall has found it.
     */
    fun iroot(n: BigNat, k: Int): BigNat {
        require(k >= SQUARE) { "root index must be at least 2" }
        if (n.isZero) return BigNat.ZERO
        var x = rootSeed(n, k)
        var y = rootStep(n, x, k)
        while (y < x) {
            x = y
            y = rootStep(n, x, k)
        }
        return x
    }

    private fun cubeStep(n: BigNat, x: BigNat): BigNat =
        (x.timesSmall(SQUARE) + n.divRem(x * x).first).divRemSmall(CUBE).first

    private fun rootStep(n: BigNat, x: BigNat, k: Int): BigNat =
        (x.timesSmall(k - 1) + n.divRem(x.pow(k - 1)).first).divRemSmall(k).first

    /** A value at or above the k-th root of [n], within a few parts in 10⁹ of it. */
    private fun seed(n: BigNat, k: Int): BigNat {
        val (mantissa, shift) = n.approx()
        val rest = shift % k
        var scaled = mantissa
        repeat(rest) { scaled *= DECIMAL }
        val root = if (k == SQUARE) sqrt(scaled) else cbrt(scaled)
        val head = BigNat.of((root * SEED_MARGIN).toLong() + SEED_SLACK)
        return head.timesPow10((shift - rest) / k)
    }

    /**
     * [seed] for any index, through the logarithm: `10^(log₁₀n / k)`, its leading
     * [SEED_DIGITS] digits in the Double and the rest as a power of ten. The Double's error in
     * `log₁₀n` (a few parts in 10¹³ for a thousand-digit n) is far inside [SEED_MARGIN].
     */
    private fun rootSeed(n: BigNat, k: Int): BigNat {
        val (mantissa, shift) = n.approx()
        val rootLog = (log10(mantissa) + shift) / k
        val tail = maxOf(0, floor(rootLog).toInt() - SEED_DIGITS)
        val head = BigNat.of((DECIMAL.pow(rootLog - tail) * SEED_MARGIN).toLong() + SEED_SLACK)
        return head.timesPow10(tail)
    }
}
