package dev.inkstone.core.calc.num

/**
 * A working-precision decimal for [DecMath]: `±mag × 10^exp`, with every operation **truncating**
 * its result to the number of significant digits the caller asks for.
 *
 * Why it exists: the calculator holds 15 digits, and a transcendental function computed in a
 * Double (≈15,95 digits) gets the 15th wrong often enough to show in a chain, and differently
 * on the JVM and on Native. CALCULATOR.md §3 (research 07 T11) asks for ≥ 25 digits rounded to
 * 15; this computes at ~45 and rounds once, in [toDec].
 *
 * Why truncation: a truncated quotient or root has the *true* digits, so when a result is exact
 * (`2⁻²²`, `√2,25`) the digit [toDec]'s half-away rounding reads is exact as well. Rounding at
 * every step would be no more accurate and would lose that.
 *
 * Internal and deliberately small: it is a calculator's scratch register, not a library.
 */
internal class HiDec private constructor(val negative: Boolean, val mag: BigNat, val exp: Int) : Comparable<HiDec> {

    val isZero: Boolean get() = mag.isZero

    val signum: Int get() = if (isZero) {
        0
    } else if (negative) {
        -1
    } else {
        1
    }

    /** `⌊log₁₀|x|⌋`; meaningless for zero. */
    val lead: Int get() = exp + mag.digitCount - 1

    fun negate(): HiDec = if (isZero) this else HiDec(!negative, mag, exp)

    fun abs(): HiDec = if (negative) HiDec(false, mag, exp) else this

    /** `x × 10ᵏ`, exactly. */
    fun scale10(k: Int): HiDec = if (isZero) this else HiDec(negative, mag, exp + k)

    /** Toward zero, to [p] significant digits. */
    fun truncate(p: Int): HiDec {
        val cut = mag.digitCount - p
        return if (cut <= 0) this else make(negative, mag.divPow10(cut), exp + cut)
    }

    fun add(other: HiDec, p: Int): HiDec {
        if (isZero) return other.truncate(p)
        if (other.isZero) return truncate(p)
        // An addend wholly below the last digit kept changes the result by less than that digit.
        val gap = lead - other.lead
        if (gap > p + 2) return truncate(p)
        if (-gap > p + 2) return other.truncate(p)
        val e = minOf(exp, other.exp)
        val a = mag.timesPow10(exp - e)
        val b = other.mag.timesPow10(other.exp - e)
        val sum = when {
            negative == other.negative -> make(negative, a + b, e)
            a >= b -> make(negative, a - b, e)
            else -> make(other.negative, b - a, e)
        }
        return sum.truncate(p)
    }

    fun sub(other: HiDec, p: Int): HiDec = add(other.negate(), p)

    fun mul(other: HiDec, p: Int): HiDec =
        make(negative != other.negative, mag * other.mag, exp + other.exp).truncate(p)

    /** Truncated quotient; its digits are the true quotient's. */
    fun div(other: HiDec, p: Int): HiDec {
        require(!other.isZero) { "division by zero" }
        if (isZero) return ZERO
        val s = maxOf(0, p + other.mag.digitCount - mag.digitCount + 1)
        val q = mag.timesPow10(s).divRem(other.mag).first
        return make(negative != other.negative, q, exp - other.exp - s).truncate(p)
    }

    /** `x × k` for `0 < k ≤ 10⁹`. */
    fun mulSmall(k: Int, p: Int): HiDec = make(negative, mag.timesSmall(k), exp).truncate(p)

    /** `x ÷ k` for `0 < k ≤ 10⁹`, truncated. */
    fun divSmall(k: Int, p: Int): HiDec {
        if (isZero) return ZERO
        val s = maxOf(0, p + DIVISOR_DIGITS - mag.digitCount + 1)
        val q = mag.timesPow10(s).divRemSmall(k).first
        return make(negative, q, exp - s).truncate(p)
    }

    /** `⌊√x⌋` to [p] digits; the digits are the true root's. */
    fun sqrt(p: Int): HiDec {
        require(!negative) { "sqrt of a negative" }
        if (isZero) return ZERO
        var s = maxOf(0, 2 * p + 2 - mag.digitCount)
        if ((exp - s) % 2 != 0) s++
        return make(false, NatRoots.isqrt(mag.timesPow10(s)), (exp - s) / 2).truncate(p)
    }

    /** Real cube root to [p] digits, sign kept. */
    fun cbrt(p: Int): HiDec {
        if (isZero) return ZERO
        var s = maxOf(0, CUBE * p + CUBE - mag.digitCount)
        while ((exp - s) % CUBE != 0) s++
        return make(negative, NatRoots.icbrt(mag.timesPow10(s)), (exp - s) / CUBE).truncate(p)
    }

    /**
     * True once this series term can no longer change a [p]-digit [sum]: every series in
     * [HiExpLog], [HiTrig] and [HiConstants] stops on it, so none has a fixed term count to get
     * wrong at the edges of its argument range.
     */
    fun negligibleBeside(sum: HiDec, p: Int): Boolean = isZero || (!sum.isZero && lead < sum.lead - p - 1)

    /** The integer part of `|x|`. */
    fun floorAbs(): BigNat = if (exp >= 0) mag.timesPow10(exp) else mag.divPow10(-exp)

    /** `|x|` rounded to the nearest integer, halves up. */
    fun roundAbs(): BigNat {
        val whole = floorAbs()
        if (exp >= 0) return whole
        val firstFraction = mag.divPow10(-exp - 1).divRemSmall(DECIMAL).second
        return if (firstFraction >= HALF_DIGIT) whole + BigNat.ONE else whole
    }

    /** Round once, half away from zero, to the calculator's 15 digits. */
    fun toDec(): Dec = Dec.fromBig(negative, mag, exp.toLong())

    override fun compareTo(other: HiDec): Int {
        if (signum != other.signum) return signum.compareTo(other.signum)
        if (isZero) return 0
        val m = if (lead != other.lead) lead.compareTo(other.lead) else alignedCompare(other)
        return if (negative) -m else m
    }

    override fun equals(other: Any?): Boolean = other is HiDec && compareTo(other) == 0

    override fun hashCode(): Int = toDec().hashCode()

    override fun toString(): String = (if (negative) "-" else "") + mag + "E" + exp

    private fun alignedCompare(other: HiDec): Int {
        val e = minOf(exp, other.exp)
        return mag.timesPow10(exp - e).compareTo(other.mag.timesPow10(other.exp - e))
    }

    companion object {
        /** A precision no exact operation here reaches: "do not truncate". */
        const val EXACT = 1_000_000
        private const val CUBE = 3
        private const val DECIMAL = 10
        private const val HALF_DIGIT = 5
        private const val DIVISOR_DIGITS = 10

        val ZERO = HiDec(false, BigNat.ZERO, 0)
        val ONE = HiDec(false, BigNat.ONE, 0)

        fun make(negative: Boolean, mag: BigNat, exp: Int): HiDec = if (mag.isZero) ZERO else HiDec(negative, mag, exp)

        fun of(d: Dec): HiDec = make(d.isNegative, BigNat.of(d.coefficient), d.scale)

        fun of(v: Long): HiDec = make(v < 0, BigNat.of(if (v < 0) -v else v), 0)

        /** Plain decimal text with an optional point, for the stored constants. */
        fun parse(text: String): HiDec {
            val point = text.indexOf('.')
            val digits = if (point < 0) text else text.removeRange(point, point + 1)
            val exp = if (point < 0) 0 else -(text.length - point - 1)
            return make(false, BigNat.parse(digits), exp)
        }
    }
}
