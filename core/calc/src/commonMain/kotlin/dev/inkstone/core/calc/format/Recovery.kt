package dev.inkstone.core.calc.format

import dev.inkstone.core.calc.num.BigNat
import dev.inkstone.core.calc.num.Dec

/** A fraction found for a decimal: [num]/[den] in lowest terms, `den > 0`, the sign on [num]. */
internal data class Ratio(val num: Long, val den: Long)

/**
 * Finding the fraction a decimal stands for: `S⇔D` on a decimal (research 02 §8.8) and the π form
 * (§8.7), which has no record type and so can only be recognised at display time (07 §4.3).
 *
 * The rules are docs/CALCULATOR.md §3's (research 07 T7, T9 — **GUESSES** until run on Casio's
 * emulator):
 * - the **simplest** fraction, found as the first continued-fraction convergent of the decimal's
 *   exact value that agrees with it to within the tolerance — 5×10⁻¹⁵ relative for a fraction,
 *   which covers any 15-digit rounding of a fraction the display can hold, and 1×10⁻¹³ for a π
 *   multiple, because a numeric integral reaching π (`∫₀^π (sin x + cos x)² dx`, 04 E-27) carries
 *   more error than one rounding. Semiconvergents are not tried;
 * - it must fit the display: integer + numerator + denominator + separators ≤ 10 digits ([fits]).
 *
 * Everything is exact integer arithmetic on the Dec's own digits; nothing goes near a Double.
 */
internal object Recovery {
    private const val DISPLAY_DIGITS = 10

    /** No displayable fraction has a denominator of nine digits: one digit is left for the numerator. */
    private const val MAX_DENOMINATOR_DIGITS = 8

    /** Outside `10⁻⁹ … 10¹⁰` no fraction of ten digits can be close (`1/99999999` is the smallest). */
    private const val LOWEST_EXPONENT = -9
    private const val HIGHEST_EXPONENT = 9

    /** `units × 10^-exponent`, relative. */
    private class Tolerance(val units: Int, val exponent: Int)

    private val FRACTION = Tolerance(5, 15)
    private val PI_MULTIPLE = Tolerance(1, 13)

    /** π form is tried only below 10⁶ (research 02 §8.7, VERIFIED range). */
    private val PI_LIMIT: Dec = Dec.of(1_000_000)

    /** [x] as a fraction that fits the display in the [mixed] or improper form, or null. */
    fun fraction(x: Dec, mixed: Boolean): Ratio? = simplest(x, FRACTION)?.takeIf { fits(it, mixed) }

    /**
     * `x/π` as a fraction — the coefficient of a π form — or null. Divided by Casio's own π
     * ([Dec.PI], 3,14159265358980), the one every π the user typed was.
     */
    fun piMultiple(x: Dec, mixed: Boolean): Ratio? {
        if (x.isZero || x.abs() >= PI_LIMIT) return null
        return simplest(x / Dec.PI, PI_MULTIPLE)?.takeIf { it.num != 0L && fits(it, mixed) }
    }

    /**
     * The guide's limit for `a b/c`, "integer + numerator + denominator ≤ 10 digits including the
     * separators" (research 02 §8.5, §13), applied to the improper form too (docs/CALCULATOR.md §3):
     * `n/d` needs `digits(n) + digits(d) + 1 ≤ 10`, `w n/d` needs `… + 2 ≤ 10`. The sign is not
     * counted (a GUESS). A whole number is not a fraction and always passes.
     */
    fun fits(r: Ratio, mixed: Boolean): Boolean {
        val n = if (r.num < 0L) -r.num else r.num
        return when {
            r.den == 1L -> true
            mixed && n > r.den -> digits(n / r.den) + digits(n % r.den) + digits(r.den) + 2 <= DISPLAY_DIGITS
            else -> digits(n) + digits(r.den) + 1 <= DISPLAY_DIGITS
        }
    }

    /**
     * The first convergent of [x] within [tolerance], or null when none of displayable size is.
     * Convergents only grow, so once the denominator passes eight digits nothing later can fit.
     */
    private fun simplest(x: Dec, tolerance: Tolerance): Ratio? {
        if (x.isZero) return Ratio(0L, 1L)
        if (x.exponent !in LOWEST_EXPONENT..HIGHEST_EXPONENT) return null
        val magnitude = BigNat.of(x.coefficient)
        val num = if (x.scale >= 0) magnitude.timesPow10(x.scale) else magnitude
        val den = if (x.scale >= 0) BigNat.ONE else BigNat.pow10(-x.scale)
        var a = num
        var b = den
        var h = BigNat.ONE
        var hBefore = BigNat.ZERO
        var k = BigNat.ZERO
        var kBefore = BigNat.ONE
        var found: Ratio? = null
        while (found == null && !b.isZero && k.digitCount <= MAX_DENOMINATOR_DIGITS) {
            val (q, r) = a.divRem(b)
            val hNext = q * h + hBefore
            val kNext = q * k + kBefore
            hBefore = h
            kBefore = k
            h = hNext
            k = kNext
            if (k.digitCount <= MAX_DENOMINATOR_DIGITS && tolerance.accepts(h, k, num, den)) {
                found = ratio(h, k, x.isNegative)
            }
            a = b
            b = r
        }
        return found
    }

    /** `|h/k − num/den| ≤ tolerance × num/den`, cross-multiplied so it stays in whole numbers. */
    private fun Tolerance.accepts(h: BigNat, k: BigNat, num: BigNat, den: BigNat): Boolean {
        val left = h * den
        val right = k * num
        val gap = if (left >= right) left - right else right - left
        return gap.timesPow10(exponent) <= (num * k).timesSmall(units)
    }

    /** Null only if the numerator leaves a Long, which no displayable fraction does. */
    private fun ratio(h: BigNat, k: BigNat, negative: Boolean): Ratio? {
        val n = h.toLongOrNull() ?: return null
        val d = k.toLongOrNull() ?: return null
        return Ratio(if (negative) -n else n, d)
    }

    private fun digits(n: Long): Int = n.toString().length
}
