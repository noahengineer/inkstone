package dev.inkstone.core.calc.num

import kotlin.math.abs

/**
 * The calculator's number: `±significand × 10^exp` with at most **15 significant decimal
 * digits**, which is what the fx-991ZA PLUS II holds in its 10-byte BCD records (research 07
 * §4.1; 02 §8.2: "internal calculation uses 15 digits").
 *
 * Decimal, not binary, on purpose: `1 − 0,9 − 0,1` must be exactly **0** here, as it is on the
 * calculator, and a Double makes it `−2,8×10⁻¹⁷`, which Norm 2 would happily display.
 *
 * The rules, each a **guess about Casio's firmware** chosen in docs/CALCULATOR.md §3 (research
 * 07 T3, T4) and pinned by `DecArithmeticTest`:
 * - every result is rounded to 15 significant digits, **half away from zero**, and no guard digit
 *   survives from one operation to the next;
 * - a result smaller than 1×10⁻⁹⁹ in magnitude **is zero** (underflow) — judged after the
 *   rounding, so `9,999999999999995×10⁻¹⁰⁰` rounds up to `1×10⁻⁹⁹` and survives;
 * - a result of 1×10¹⁰⁰ or more is **kept** and flagged by [isOverflow] — never clamped, never
 *   thrown — so the evaluator can raise `Math ERROR` at the step that overflowed. Past
 *   10^1000000 the magnitude saturates; such a value means only "overflowed".
 *
 * Values are normalised (no trailing zeros in the significand, no negative zero), so `==`,
 * [hashCode] and [compareTo] agree, and `0,50 == 0,5`.
 *
 * Display rounding (10 digits, Fix, Sci) is the formatter's job and never changes a stored value
 * (research 07 §4.2). [roundSignificant] and [roundPlaces] exist for it and for `Rnd`.
 */
class Dec private constructor(
    /** True for values below zero; [ZERO] is never negative. */
    val isNegative: Boolean,
    /** 0, or 1…999 999 999 999 999 with no trailing zero. */
    internal val coefficient: Long,
    /** The value is `coefficient × 10^scale`. */
    internal val scale: Int,
) : Comparable<Dec> {

    val isZero: Boolean get() = coefficient == 0L

    /** −1, 0 or 1. */
    val signum: Int get() = if (isZero) {
        0
    } else if (isNegative) {
        -1
    } else {
        1
    }

    val isInteger: Boolean get() = isZero || scale >= 0

    /** `|x| ≥ 1×10¹⁰⁰`: the calculator's range is exceeded and this result is a Math ERROR. */
    val isOverflow: Boolean get() = !isZero && exponent >= OVERFLOW_EXPONENT

    /** `⌊log₁₀|x|⌋` for a non-zero value — the exponent Sci display shows; 0 for zero. */
    val exponent: Int get() = if (isZero) 0 else scale + decimalDigits(coefficient) - 1

    /** The significant digits, 1 to 15 of them, without trailing zeros: `"31415926535898"`. */
    val significandDigits: String get() = coefficient.toString()

    operator fun plus(other: Dec): Dec = when {
        isZero -> other

        other.isZero -> this

        // An addend t whose leading digit is 17 or more places below a's is smaller than half a
        // unit in a's 16th place, so |a| ± t rounds (half away, 15 digits) straight back to |a|
        // — including when a is a power of ten and the grid below it is ten times finer. The
        // boundary is sharp: at 16 places 1 − 9,99999999999999×10⁻¹⁶ is 0,999999999999999.
        exponent - other.exponent >= NEGLIGIBLE_GAP -> this

        other.exponent - exponent >= NEGLIGIBLE_GAP -> other

        else -> addAligned(this, other)
    }

    operator fun minus(other: Dec): Dec = plus(-other)

    operator fun times(other: Dec): Dec {
        if (isZero || other.isZero) return ZERO
        val negative = isNegative != other.isNegative
        val q = scale.toLong() + other.scale
        return if (coefficient < LONG_PRODUCT_LIMIT && other.coefficient < LONG_PRODUCT_LIMIT) {
            make(negative, coefficient * other.coefficient, q)
        } else {
            fromBig(negative, BigNat.of(coefficient) * BigNat.of(other.coefficient), q)
        }
    }

    /**
     * The quotient, rounded half away from zero. A zero divisor is a caller's bug — the evaluator
     * turns `÷0` into Math ERROR before it gets here — so it is a `require`, not a result.
     */
    operator fun div(other: Dec): Dec {
        require(!other.isZero) { "division by zero" }
        if (isZero) return ZERO
        var d = other.coefficient
        var r = coefficient
        var shift = 0
        // Bring the leading digits level, both ways, so d ≤ r < 10d and each step below yields
        // exactly one digit. Scaling only r up was not enough: 3,00000000000001 ÷ 2 starts with
        // r/d ≈ 1,5×10¹⁴, and sixteen steps of that leave a Long.
        while (r < d) {
            r *= DECIMAL
            shift++
        }
        while (r >= d * DECIMAL) {
            d *= DECIMAL
            shift--
        }
        // Sixteen truncated digits: their last is exactly the digit a half-away rounding reads.
        var quotient = 0L
        repeat(MAX_DIGITS + 1) {
            quotient = quotient * DECIMAL + r / d
            r = (r % d) * DECIMAL
        }
        val q = scale.toLong() - other.scale - shift - MAX_DIGITS
        return make(isNegative != other.isNegative, quotient, q)
    }

    operator fun unaryMinus(): Dec = if (isZero) this else Dec(!isNegative, coefficient, scale)

    fun abs(): Dec = if (isNegative) Dec(false, coefficient, scale) else this

    override fun compareTo(other: Dec): Int {
        if (signum != other.signum) return signum.compareTo(other.signum)
        if (isZero) return 0
        val m = compareMagnitude(other)
        return if (isNegative) -m else m
    }

    /** Round to [n] significant digits (1…15), half away from zero — Sci n and Norm's ten. */
    fun roundSignificant(n: Int): Dec {
        require(n in 1..MAX_DIGITS) { "significant digits must be 1..15" }
        val drop = decimalDigits(coefficient) - n
        return if (drop <= 0) this else dropDigits(drop)
    }

    /**
     * Round to [n] decimal places, half away from zero — Fix n, and `Rnd` under Fix (research
     * 07 §4.2). A negative [n] rounds to tens, hundreds, …
     */
    fun roundPlaces(n: Int): Dec {
        val drop = -n.toLong() - scale
        return when {
            isZero || drop <= 0 -> this
            drop > decimalDigits(coefficient) -> ZERO
            else -> dropDigits(drop.toInt())
        }
    }

    /** Toward zero. Always exact: an integer part never has more digits than its number. */
    fun truncate(): Dec {
        if (scale >= 0) return this
        val drop = -scale
        return if (drop >= decimalDigits(coefficient)) ZERO else make(isNegative, coefficient / POW10[drop], 0)
    }

    fun floor(): Dec {
        val t = truncate()
        return if (isNegative && t != this) t - ONE else t
    }

    fun ceil(): Dec {
        val t = truncate()
        return if (!isNegative && t != this) t + ONE else t
    }

    /** `x − truncate(x)`, so it carries x's sign: −2,5 gives −0,5. Exact. */
    fun fractionalPart(): Dec = this - truncate()

    /**
     * `x × 10ⁿ`, exactly — the significand is untouched. For `×10ˣ` and ENG steps. Only the range
     * rules apply: it can underflow to [ZERO] or overflow ([isOverflow]).
     */
    fun scaleByPowerOfTen(n: Int): Dec = if (isZero) this else make(isNegative, coefficient, scale.toLong() + n)

    /** The exact value as a Long, or null if it is not an integer or does not fit. */
    fun toLongOrNull(): Long? {
        if (!isInteger) return null
        if (scale >= POW10.size || coefficient > Long.MAX_VALUE / POW10[scale]) return null
        val v = coefficient * POW10[scale]
        return if (isNegative) -v else v
    }

    fun toIntOrNull(): Int? = toLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    /**
     * The nearest Double, for heuristics only (seeds, plot scaling) — never for display, never for
     * maths. Read from the canonical text, so it is correctly rounded on every platform rather
     * than `coefficient × 10^scale` with two roundings; an overflowed value gives ±Infinity.
     */
    fun toDouble(): Double = toCanonicalString().toDouble()

    /** Lossless text for persistence; [parse] reads it back to an equal value. */
    fun toCanonicalString(): String = canonicalText(isNegative, significandDigits, exponent)

    override fun equals(other: Any?): Boolean {
        if (other !is Dec) return false
        return isNegative == other.isNegative && coefficient == other.coefficient && scale == other.scale
    }

    override fun hashCode(): Int = (coefficient.hashCode() * HASH_PRIME + scale) * HASH_PRIME + isNegative.hashCode()

    override fun toString(): String = toCanonicalString()

    private fun compareMagnitude(other: Dec): Int {
        if (exponent != other.exponent) return exponent.compareTo(other.exponent)
        val a = coefficient * POW10[MAX_DIGITS - decimalDigits(coefficient)]
        val b = other.coefficient * POW10[MAX_DIGITS - decimalDigits(other.coefficient)]
        return a.compareTo(b)
    }

    /** Drop the last [drop] (≥ 1) significant digits, half away from zero. */
    private fun dropDigits(drop: Int): Dec {
        val kept = coefficient / POW10[drop]
        val first = (coefficient / POW10[drop - 1]) % DECIMAL
        return make(isNegative, if (first >= HALF_DIGIT) kept + 1 else kept, scale.toLong() + drop)
    }

    companion object {
        /** Significant digits held, as on the calculator (research 02 §8.2). */
        const val MAX_DIGITS = 15

        /** `|x| < 1×10⁻⁹⁹` underflows to zero (CALCULATOR.md §3, a guess: research 07 T4). */
        const val MIN_EXPONENT = -99

        /** `|x| ≥ 1×10¹⁰⁰` is out of range (research 02 §8.2). */
        const val OVERFLOW_EXPONENT = 100

        private const val SATURATED_EXPONENT = 1_000_000
        private const val NEGLIGIBLE_GAP = 17
        private const val LONG_PRODUCT_LIMIT = 3_037_000_499L
        private const val DECIMAL = 10L
        private const val HALF_DIGIT = 5L
        private const val HASH_PRIME = 31
        private const val LONG_DIGITS = 18
        private const val ROUNDING_DIGITS = 16

        /** 10⁰…10¹⁸. */
        internal val POW10: LongArray = LongArray(LONG_DIGITS + 1).also { p ->
            p[0] = 1L
            for (i in 1..LONG_DIGITS) p[i] = p[i - 1] * DECIMAL
        }

        val ZERO: Dec = Dec(false, 0L, 0)
        val ONE: Dec = make(false, 1L, 0)
        val TWO: Dec = make(false, 2L, 0)
        val TEN: Dec = make(false, 1L, 1)
        val HALF: Dec = make(false, 5L, -1)

        /**
         * Casio's internal π, **3,14159265358980** — π rounded to 14 digits and padded, not the
         * 15-digit 3,14159265358979 (research 07 §4.2, VERIFIED from the guide; quirk Q1).
         * This is the π the user types; [DecMath]'s trig reduces arguments with the true one.
         */
        val PI: Dec = make(false, 314_159_265_358_980L, -14)

        /** Casio's internal e, **2,71828182845904** — truncated, not rounded (research 07 §4.2). */
        val E: Dec = make(false, 271_828_182_845_904L, -14)

        fun of(value: Long): Dec = when {
            // −2⁶³ has no positive twin; its last digit cannot reach the 16th, so drop it.
            value == Long.MIN_VALUE -> make(true, -(value / DECIMAL), 1)

            else -> make(value < 0, abs(value), 0)
        }

        fun of(value: Int): Dec = of(value.toLong())

        /**
         * Read `-12.5`, `1.5E-3`, `0.52917721067E-10`: an optional sign, digits with an optional
         * `.` point, an optional `E` exponent. More than 15 significant digits are rounded half
         * away from zero. Null if the text is not a number. Persistence and the constant tables
         * use this; it is not the keypad's parser (that is [fromDigits]).
         */
        fun parse(text: String): Dec? = parseDecText(text)

        /**
         * A typed literal: [digits] exactly as keyed (any length, leading zeros allowed), with the
         * decimal comma after [pointIndex] of them (`digits.length` if there was none), times
         * `10^exponent` from `×10ˣ`. Rounded once to 15 digits, half away from zero.
         */
        fun fromDigits(negative: Boolean, digits: String, pointIndex: Int, exponent: Int): Dec {
            require(digits.all { it in '0'..'9' }) { "digits only" }
            require(pointIndex in 0..digits.length) { "pointIndex out of range" }
            val first = digits.indexOfFirst { it != '0' }
            if (first < 0) return ZERO
            val significant = digits.substring(first)
            val q = pointIndex.toLong() - digits.length + exponent
            if (significant.length <= LONG_DIGITS) return make(negative, significant.toLong(), q)
            val head = significant.substring(0, ROUNDING_DIGITS).toLong()
            return make(negative, head, q + significant.length - ROUNDING_DIGITS)
        }

        /**
         * The one place a value is built: rounds [coefficient] (any non-negative Long) to 15
         * digits half away from zero, strips trailing zeros, flushes underflow to [ZERO] and
         * saturates absurd exponents. Only the 16th digit decides the rounding, so a caller may
         * pass a coefficient already truncated to 16 or more digits.
         */
        internal fun make(negative: Boolean, coefficient: Long, q: Long): Dec {
            require(coefficient >= 0)
            if (coefficient == 0L) return ZERO
            var c = coefficient
            var e = q
            val n = decimalDigits(c)
            if (n > MAX_DIGITS) {
                val drop = n - MAX_DIGITS
                val first = (c / POW10[drop - 1]) % DECIMAL
                c /= POW10[drop]
                if (first >= HALF_DIGIT) c++
                e += drop
            }
            while (c % DECIMAL == 0L) {
                c /= DECIMAL
                e++
            }
            val exponent = e + decimalDigits(c) - 1
            return when {
                exponent < MIN_EXPONENT -> ZERO
                exponent > SATURATED_EXPONENT -> Dec(negative, 1L, SATURATED_EXPONENT)
                else -> Dec(negative, c, e.toInt())
            }
        }

        /** [make] for a magnitude that may not fit a Long. */
        internal fun fromBig(negative: Boolean, magnitude: BigNat, q: Long): Dec {
            val n = magnitude.digitCount
            if (n <= LONG_DIGITS) return make(negative, magnitude.toLongOrNull() ?: 0L, q)
            val cut = n - ROUNDING_DIGITS
            return make(negative, magnitude.divPow10(cut).toLongOrNull() ?: 0L, q + cut)
        }

        private fun addAligned(a: Dec, b: Dec): Dec {
            val qMin = minOf(a.scale, b.scale)
            val sa = a.scale - qMin
            val sb = b.scale - qMin
            if (sa <= LONG_SHIFT && sb <= LONG_SHIFT) {
                val x = a.coefficient * POW10[sa]
                val y = b.coefficient * POW10[sb]
                val s = (if (a.isNegative) -x else x) + (if (b.isNegative) -y else y)
                return make(s < 0, abs(s), qMin.toLong())
            }
            val x = BigNat.of(a.coefficient).timesPow10(sa)
            val y = BigNat.of(b.coefficient).timesPow10(sb)
            if (a.isNegative == b.isNegative) return fromBig(a.isNegative, x + y, qMin.toLong())
            val c = x.compareTo(y)
            return when {
                c == 0 -> ZERO
                c > 0 -> fromBig(a.isNegative, x - y, qMin.toLong())
                else -> fromBig(b.isNegative, y - x, qMin.toLong())
            }
        }

        /** Two 15-digit coefficients shifted this far still sum inside a Long (< 2×10¹⁸). */
        private const val LONG_SHIFT = 3
    }
}

/** Decimal digits of a positive Long. */
internal fun decimalDigits(v: Long): Int {
    var n = 1
    while (n < Dec.POW10.size && v >= Dec.POW10[n]) n++
    return n
}
