package dev.inkstone.core.calc.num

/**
 * `xʸ`, `ˣ√y`, `10ˣ` and `x!` for [DecMath]. Null means Math ERROR: outside the domain, or a
 * result of `10¹⁰⁰` or more.
 *
 * An **integer** exponent is computed exactly (a `BigNat` power, then one rounding), so `1,5¹⁰`,
 * `2⁻³` and `123,45675²` — whose square is a 16-digit number ending in 5, an exact tie at the
 * 15th digit — round correctly. So is a **small-denominator** one, `y = p/q` with `q ≤ 49`: the
 * q-th root of the exact power `xᵖ` as an exact integer floor, because `10001000025^1,5` is
 * `100005³ = 1000150007500125`, another exact tie, and `exp(y·ln x)` puts that on the wrong side
 * half the time. Any other exponent goes through `exp(y·ln x)` at working precision, which no
 * such tie can reach (see [MAX_EXACT_DENOMINATOR]).
 *
 * The guide says the calculator itself chains internal steps here and "accumulates error"
 * (research 02 §13), so an exact power may beat it in the 15th digit: a guess either way until
 * test T11 is run (research 07 §6), and the 10 displayed digits agree.
 */
internal object DecPowers {
    /** `|n| × digits(x)` up to this is computed exactly; past it `exp(y·ln x)` is as good. */
    private const val EXACT_POWER_DIGITS = 1200

    /**
     * The largest reduced denominator `q` for which `x^(p/q)` is an exact root. A result can only
     * be a 16-digit tie — the one place the log route can round the wrong way — if `q` divides
     * how many times some prime divides x's significand (comparing prime exponents on both sides
     * of `Tᵠ = xᵖ`), and a 15-digit integer holds no prime more than 49 times (2⁴⁹ < 10¹⁵ < 2⁵⁰).
     */
    private const val MAX_EXACT_DENOMINATOR = 49

    /** Digits an exact root keeps before the one rounding: its 16th is then a true digit. */
    private const val ROOT_DIGITS = 17

    /** `x!` is in range for `0 ≤ x ≤ 69` (research 02 §13); `70! ≈ 1,2×10¹⁰⁰`. */
    private const val MAX_FACTORIAL = 69

    private const val QUOTIENT_DIGITS = 17
    private const val CUBE = 3
    private const val WORK = HiConstants.WORK

    /** `e²³¹ > 10¹⁰⁰` and `e⁻²³³ < 10⁻¹⁰⁰`: past these the answer is known without computing. */
    private val LOG_OVERFLOW = HiDec.of(231L)
    private val LOG_UNDERFLOW = HiDec.of(-233L)

    /**
     * `y·ln x` at or below this is `y·log x ≤ −10¹⁰⁰`, where the guide's range for xʸ ends
     * (research 02 §13: `−1×10¹⁰⁰ < y·log x < 100`): Math ERROR, not an underflow to zero.
     */
    private val LOG_FLOOR: HiDec by lazy {
        HiDec.ONE.scale10(Dec.OVERFLOW_EXPONENT).mul(HiConstants.ln10, HiDec.EXACT).negate()
    }

    /** `10¹⁰⁰` overflows; `10⁻¹⁰¹` and below underflow. */
    private val TEN_POW_OVERFLOW = Dec.of(100)
    private val TEN_POW_UNDERFLOW = Dec.of(-101)

    fun pow(x: Dec, y: Dec): Dec? {
        if (x.isZero) return if (y.signum > 0) Dec.ZERO else null
        if (y.isZero) return Dec.ONE
        val result = if (y.isInteger) integerExponent(x, y) else fractionalExponent(x, y)
        return result?.takeUnless { it.isOverflow }
    }

    fun nthRoot(x: Dec, n: Int): Dec? = when {
        n == 0 -> null

        x.isZero -> if (n > 0) Dec.ZERO else null

        x.isNegative && n % 2 == 0 -> null

        n == 1 -> x

        n == 2 -> DecMath.sqrt(x)

        n == CUBE -> DecMath.cbrt(x)

        n == -1 -> (Dec.ONE / x).takeUnless { it.isOverflow }

        else -> {
            val g = WORK + HiConstants.GUARD
            val t = HiExpLog.ln(HiDec.of(x).abs(), g).div(HiDec.of(n.toLong()), g)
            fromExp(t, x.isNegative)
        }
    }

    fun tenPow(x: Dec): Dec? {
        if (x >= TEN_POW_OVERFLOW) return null
        if (x <= TEN_POW_UNDERFLOW) return Dec.ZERO
        val whole = x.floor()
        val n = whole.toIntOrNull() ?: return null
        val fraction = x - whole
        if (fraction.isZero) return Dec.ONE.scaleByPowerOfTen(n)
        val g = WORK + HiConstants.GUARD
        val e = HiExpLog.exp(HiDec.of(fraction).mul(HiConstants.ln10, g), WORK)
        return e.scale10(n).toDec().takeUnless { it.isOverflow }
    }

    fun factorial(n: Int): Dec? {
        if (n !in 0..MAX_FACTORIAL) return null
        var product = BigNat.ONE
        for (k in 2..n) product = product.timesSmall(k)
        return Dec.fromBig(false, product, 0L)
    }

    /** `xⁿ`: exact when short enough, else through the logarithm with the sign from n's parity. */
    private fun integerExponent(x: Dec, y: Dec): Dec? =
        y.toLongOrNull()?.let { integerPower(x, it) } ?: viaLogs(x, y, x.isNegative && isOdd(y))

    /**
     * `x^(p/q)` for a non-integer y. A negative x needs an odd q — the guide's `m/(2n+1)`
     * (research 02 §13) — and then takes its sign from p: `(−32)^0,2 = −2`, `(−32)^1,2 = 64`.
     * How the firmware tells that a stored `0,2` is `1/5` is undocumented; treating every decimal
     * whose reduced denominator is odd that way is a guess, and the one the documented domain
     * describes. A fraction the evaluator holds exactly (`1/3`) never reaches here as one.
     */
    private fun fractionalExponent(x: Dec, y: Dec): Dec? {
        val ratio = ExponentRatio.of(y)
        if (x.isNegative && ratio.twos > 0) return null
        val p = ratio.numerator
        val negative = x.isNegative && p % 2 != 0L
        val q = ratio.denominatorUpTo(MAX_EXACT_DENOMINATOR)
        val magnitude = if (p < 0) -p else p
        return if (q != null && magnitude * decimalDigits(x.coefficient) <= EXACT_POWER_DIGITS) {
            rationalPower(x, p, q, negative)
        } else {
            viaLogs(x, y, negative)
        }
    }

    /**
     * `xⁿ` exactly, then one rounding — possibly to an overflowed value, which [pow] rejects.
     * Null only when it was not attempted because the exact power would be needlessly long.
     */
    private fun integerPower(x: Dec, n: Long): Dec? {
        if (n == Long.MIN_VALUE) return null
        val magnitude = if (n < 0) -n else n
        if (magnitude > EXACT_POWER_DIGITS || magnitude * decimalDigits(x.coefficient) > EXACT_POWER_DIGITS) {
            return null
        }
        val power = BigNat.of(x.coefficient).pow(magnitude.toInt())
        val negative = x.isNegative && magnitude % 2 == 1L
        val q = x.scale.toLong() * magnitude
        return if (n > 0) Dec.fromBig(negative, power, q) else reciprocal(negative, power, -q)
    }

    /** `10^q / den`, correctly rounded: a floor quotient's 16th digit is the true one. */
    private fun reciprocal(negative: Boolean, den: BigNat, q: Long): Dec {
        val shift = QUOTIENT_DIGITS + den.digitCount
        val floor = BigNat.pow10(shift).divRem(den).first
        return Dec.fromBig(negative, floor, q - shift)
    }

    /**
     * `±|x|^(p/q)` as the exact floor of the q-th root of the exact power `|x|^|p|`, kept to
     * [ROOT_DIGITS] digits, so the one rounding reads a true 16th digit.
     */
    private fun rationalPower(x: Dec, p: Long, q: Int, negative: Boolean): Dec {
        val magnitude = if (p < 0) -p else p
        val power = BigNat.of(x.coefficient).pow(magnitude.toInt())
        val exponent = x.scale.toLong() * magnitude
        return if (p > 0) rootOf(power, exponent, q, negative) else rootOfReciprocal(power, exponent, q, negative)
    }

    /**
     * `(n·10ᵉ)^(1/q)`. Whole q-th powers of ten move in and out of the radicand without losing
     * the floor, because `⌊ᵠ√⌊m / 10^(qj)⌋⌋ = ⌊ᵠ√m / 10ʲ⌋`.
     */
    private fun rootOf(n: BigNat, e: Long, q: Int, negative: Boolean): Dec {
        val whole = e.floorDiv(q.toLong())
        val radicand = n.timesPow10((e - whole * q).toInt())
        val excess = radicand.digitCount - q * ROOT_DIGITS
        val j = if (excess >= 0) excess / q else -((-excess + q - 1) / q)
        val sized = if (j >= 0) radicand.divPow10(j * q) else radicand.timesPow10(-j * q)
        return Dec.fromBig(negative, NatRoots.iroot(sized, q), whole + j)
    }

    /** `(10⁻ᵉ / n)^(1/q)`, as the q-th root of `⌊10ᵏ / n⌋` with k long enough and `k ≡ −e (mod q)`. */
    private fun rootOfReciprocal(n: BigNat, e: Long, q: Int, negative: Boolean): Dec {
        val least = q * ROOT_DIGITS + n.digitCount
        val k = least + (-e - least).mod(q.toLong()).toInt()
        val quotient = BigNat.pow10(k).divRem(n).first
        return Dec.fromBig(negative, NatRoots.iroot(quotient, q), (-e - k) / q)
    }

    /** `±e^(y·ln|x|)`; null when it overflows, or when `y·log|x|` is below the guide's floor. */
    private fun viaLogs(x: Dec, y: Dec, negative: Boolean): Dec? {
        val g = WORK + HiConstants.GUARD
        val t = HiDec.of(y).mul(HiExpLog.ln(HiDec.of(x).abs(), g), g)
        return if (t <= LOG_FLOOR) null else fromExp(t, negative)
    }

    /** `±eᵗ`, deciding over- and underflow from `t` before anything huge is computed. */
    private fun fromExp(t: HiDec, negative: Boolean): Dec? = when {
        t > LOG_OVERFLOW -> null

        t < LOG_UNDERFLOW -> Dec.ZERO

        else -> {
            val e = HiExpLog.exp(t, WORK)
            (if (negative) e.negate() else e).toDec().takeUnless { it.isOverflow }
        }
    }

    /** True for an odd integer; `coefficient × 10^scale` with a positive scale is even. */
    private fun isOdd(y: Dec): Boolean = y.isInteger && y.scale == 0 && y.coefficient % 2 == 1L
}

private const val PRIME_TWO = 2L
private const val PRIME_FIVE = 5L

/**
 * A non-integer `y` as `numerator / (2^twos · 5^fives)` in lowest terms. A decimal's denominator
 * divides a power of ten, so only 2 and 5 can cancel — and a normalised significand never has
 * both, having no trailing zero. The denominator is odd exactly when [twos] is 0.
 */
private class ExponentRatio(val numerator: Long, val twos: Int, val fives: Int) {

    /** The denominator, or null if it is larger than [limit] (`1×10⁻⁹⁹` has 2⁹⁹·5⁹⁹: no Long holds it). */
    fun denominatorUpTo(limit: Int): Int? {
        var q = 1L
        for (i in 0 until twos + fives) {
            q *= if (i < twos) PRIME_TWO else PRIME_FIVE
            if (q > limit) return null
        }
        return q.toInt()
    }

    companion object {
        fun of(y: Dec): ExponentRatio {
            var c = y.coefficient
            var twos = -y.scale
            var fives = -y.scale
            while (twos > 0 && c % PRIME_TWO == 0L) {
                c /= PRIME_TWO
                twos--
            }
            while (fives > 0 && c % PRIME_FIVE == 0L) {
                c /= PRIME_FIVE
                fives--
            }
            return ExponentRatio(if (y.isNegative) -c else c, twos, fives)
        }
    }
}
