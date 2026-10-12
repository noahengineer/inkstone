package dev.inkstone.core.calc.num

/**
 * Every real function the calculator needs, on [Dec].
 *
 * **Null means Math ERROR**: the argument is outside the function's domain, or the result would
 * be `10¹⁰⁰` or more (research 02 §8.2). An overflowed *argument* ([Dec.isOverflow]) is also
 * null. A result below `10⁻⁹⁹` underflows to zero, like every [Dec] (CALCULATOR.md §3).
 *
 * **Accuracy.** Each function is computed at [HiConstants.WORK] (42) digits on [HiDec] and
 * rounded once, half away from zero, to 15 (CALCULATOR.md §3, research 07 T11: "at least 25
 * digits and rounded to 15"). [sqrt], [cbrt], [factorial] and [pow] with an integer or
 * small-denominator exponent are *correctly* rounded, from exact integer arithmetic; the
 * transcendental ones are correctly rounded in practice — an error would need a true value within
 * 10⁻⁴⁰ of a rounding tie, and a transcendental value is never exactly on one. Nothing passes
 * through a Double, which gets the 15th digit wrong often and differently on the JVM and Native.
 *
 * **Input limits.** The guide's input ranges (research 02 §13) are Math ERROR on the calculator.
 * Those that are a property of the function alone are enforced here, where they are narrower
 * than the mathematics: `sinh`/`cosh` stop where eˣ does, `sinh⁻¹`/`cosh⁻¹` below 5×10⁹⁹, and
 * xʸ needs `y·log x > −10¹⁰⁰`. Most of the table's other limits are simply "the result stays
 * below 10¹⁰⁰", which the overflow rule already is.
 *
 * **What is not here, on purpose.** Angle units (the evaluator converts to radians), exact
 * special angles (`sin 30° = ½`, keyed on the angle, CALCULATOR.md §3 / 07 T8), and the limits
 * that depend on the angle unit or the typed form — `sin` in Deg only below `9×10⁹`, `tan 90°`,
 * and an exponent the user typed as a fraction (`(−8)^(1/3)`), which only the evaluator knows.
 *
 * One function per calculator function, by construction, which is why the count is large: the
 * same argument the repo's detekt config makes for OpCodec.
 */
@Suppress("TooManyFunctions")
object DecMath {
    private const val WORK = HiConstants.WORK

    /** `√x` and `∛x` are exact floors at this many digits, so their rounding is exact too. */
    private const val ROOT_DIGITS = 17

    /** `e²³¹ > 10¹⁰⁰`; `e⁻²³³ < 10⁻¹⁰⁰`. */
    private val EXP_OVERFLOW = Dec.of(231)
    private val EXP_UNDERFLOW = Dec.of(-233)

    /**
     * `sinh` and `cosh` are Math ERROR wherever `eˣ` is, not only where they pass 10¹⁰⁰ (about
     * 230,95): the guide limits both to `|x| ≤ 230,2585092` (research 02 §13 and 06 §5.11,
     * VERIFIED), which is eˣ's own limit printed to 10 digits — the calculator evidently forms eˣ
     * on the way. The last digits are a **guess about the firmware**: the largest 15-digit x whose
     * eˣ rounds below 10¹⁰⁰, since `ln(9,999999999999995×10⁹⁹) = 230,25850929940456…`.
     */
    private val EXP_LIMIT = Dec.fromDigits(false, "230258509299404", 3, 0)

    /**
     * `sinh⁻¹` and `cosh⁻¹` are limited to `|x| ≤ 4,999999999×10⁹⁹` (research 02 §13, VERIFIED)
     * though their values are only about 230: the calculator evidently forms `2x` on the way, as
     * `ln(2x)` for a large x would. Read, like every limit in that table, as the printed edge of
     * an internal overflow — `2x ≥ 10¹⁰⁰` — which is a **guess about the firmware**.
     */
    private val INVERSE_HYPERBOLIC_LIMIT = Dec.fromDigits(false, "5", 1, 99)

    // ---- Roots, powers, exponentials and logarithms ------------------------------------------

    /** `√x`; null for `x < 0`. Correctly rounded. */
    fun sqrt(x: Dec): Dec? = if (x.isNegative || x.isOverflow) null else HiDec.of(x).sqrt(ROOT_DIGITS).toDec()

    /** `∛x`, negative arguments included (`∛−27 = −3`). Correctly rounded. */
    fun cbrt(x: Dec): Dec? = if (x.isOverflow) null else HiDec.of(x).cbrt(ROOT_DIGITS).toDec()

    /**
     * `ⁿ√x` for an integer index. An odd [n] takes a negative [x]; an even one does not. `n = 0`
     * and `ⁿ√0` with `n < 0` are null. A *fractional* index with an odd denominator (the guide's
     * `x = (2n+1)/m` case, research 02 §13) needs the exact fraction, so it is the evaluator's.
     */
    fun nthRoot(x: Dec, n: Int): Dec? = if (x.isOverflow) null else DecPowers.nthRoot(x, n)

    /** `eˣ`. Null from `x ≳ 230,2585093`; zero below `x ≈ −227,96`. */
    fun exp(x: Dec): Dec? = when {
        x.isOverflow || x > EXP_OVERFLOW -> null
        x < EXP_UNDERFLOW -> Dec.ZERO
        else -> finish(HiExpLog.exp(HiDec.of(x), WORK))
    }

    /** `ln x`; null for `x ≤ 0`. */
    fun ln(x: Dec): Dec? = if (!isPositive(x)) null else finish(HiExpLog.ln(HiDec.of(x), WORK))

    /** `log₁₀ x`; null for `x ≤ 0`. Exact for a power of ten (`log 1000 = 3`). */
    fun log10(x: Dec): Dec? = when {
        !isPositive(x) -> null
        x.coefficient == 1L -> Dec.of(x.scale)
        else -> finish(logRatio(HiDec.of(x), HiConstants.ln10))
    }

    /** `log_base x` (the `log▪▯` template); null for `x ≤ 0`, `base ≤ 0` or `base = 1`. */
    fun log(base: Dec, x: Dec): Dec? {
        if (!isPositive(base) || !isPositive(x) || base == Dec.ONE) return null
        val g = WORK + HiConstants.GUARD
        return finish(logRatio(HiDec.of(x), HiExpLog.ln(HiDec.of(base), g)))
    }

    /**
     * `xʸ`. `0⁰` and `0` to a negative power are null. A negative [x] needs an integer [y] or a
     * decimal one whose reduced denominator is odd — the guide's `m/(2n+1)` (research 02 §13):
     * `(−32)^0,2 = −2`, `(−8)^0,5` is null. A *fraction* with an odd denominator that a decimal
     * cannot hold exactly (`1/3`) is the evaluator's, through [nthRoot]. Null also when
     * `y·log|x| ≤ −10¹⁰⁰`, the guide's floor. Integer exponents and those with a denominator up
     * to 49 are exact before the one rounding, so even an exact 16-digit tie rounds correctly.
     */
    fun pow(x: Dec, y: Dec): Dec? = if (x.isOverflow || y.isOverflow) null else DecPowers.pow(x, y)

    /** `10ˣ`. Exact for an integer [x]; null from `x ≥ 100`. */
    fun tenPow(x: Dec): Dec? = if (x.isOverflow) null else DecPowers.tenPow(x)

    // ---- Circular functions, in radians -----------------------------------------------------

    /** `sin x`, x in radians, reduced by the true π (good across the whole `|x| < 10¹⁰⁰`). */
    fun sin(x: Dec): Dec? = if (x.isOverflow) null else finish(HiTrig.sin(HiDec.of(x), WORK))

    /** `cos x`, x in radians. */
    fun cos(x: Dec): Dec? = if (x.isOverflow) null else finish(HiTrig.cos(HiDec.of(x), WORK))

    /** `tan x`, x in radians. A decimal is never exactly at a pole; the evaluator rejects `tan 90°`. */
    fun tan(x: Dec): Dec? = if (x.isOverflow) null else HiTrig.tan(HiDec.of(x), WORK)?.let(::finish)

    /** `sin⁻¹ x` in `[−π/2, π/2]`; null for `|x| > 1`. */
    fun asin(x: Dec): Dec? {
        if (x.isOverflow || x.abs() > Dec.ONE) return null
        val hx = HiDec.of(x)
        return HiTrig.atan2(hx, complement(hx), WORK)?.let(::finish)
    }

    /** `cos⁻¹ x` in `[0, π]`; null for `|x| > 1`. No cancellation near `x = 1`. */
    fun acos(x: Dec): Dec? {
        if (x.isOverflow || x.abs() > Dec.ONE) return null
        val hx = HiDec.of(x)
        return HiTrig.atan2(complement(hx), hx, WORK)?.let(::finish)
    }

    /** `tan⁻¹ x` in `(−π/2, π/2)`. */
    fun atan(x: Dec): Dec? = if (x.isOverflow) null else finish(HiTrig.atan(HiDec.of(x), WORK))

    /**
     * The angle of `(x, y)` in `(−π, π]`, for Pol and the complex `arg`. Null at the origin, where
     * it is undefined; what `Pol(0;0)` and `arg 0` show on the real calculator is UNKNOWN, so the
     * evaluator decides (research 07 §6 has no test for it yet).
     */
    fun atan2(y: Dec, x: Dec): Dec? =
        if (x.isOverflow || y.isOverflow) null else HiTrig.atan2(HiDec.of(y), HiDec.of(x), WORK)?.let(::finish)

    // ---- Hyperbolic functions ------------------------------------------------------------------

    /** `sinh x`; null for `|x| > 230,258509299404`, where eˣ is (see [EXP_LIMIT]). */
    fun sinh(x: Dec): Dec? = if (x.abs() > EXP_LIMIT) null else finish(HiHyperbolic.sinh(HiDec.of(x), WORK))

    /** `cosh x`; null for `|x| > 230,258509299404`, where eˣ is (see [EXP_LIMIT]). */
    fun cosh(x: Dec): Dec? = if (x.abs() > EXP_LIMIT) null else finish(HiHyperbolic.cosh(HiDec.of(x), WORK))

    /** `tanh x`, for any x. */
    fun tanh(x: Dec): Dec? = if (x.isOverflow) null else finish(HiHyperbolic.tanh(HiDec.of(x), WORK))

    /** `sinh⁻¹ x`; null for `|x| ≥ 5×10⁹⁹` (see [INVERSE_HYPERBOLIC_LIMIT]). */
    fun asinh(x: Dec): Dec? =
        if (x.abs() >= INVERSE_HYPERBOLIC_LIMIT) null else finish(HiHyperbolic.asinh(HiDec.of(x), WORK))

    /** `cosh⁻¹ x`; null for `x < 1` and for `x ≥ 5×10⁹⁹` (see [INVERSE_HYPERBOLIC_LIMIT]). */
    fun acosh(x: Dec): Dec? =
        if (x < Dec.ONE || x >= INVERSE_HYPERBOLIC_LIMIT) null else finish(HiHyperbolic.acosh(HiDec.of(x), WORK))

    /**
     * `tanh⁻¹ x`; null for `|x| ≥ 1`. The guide prints the limit as `9,999999999×10⁻¹`, which is
     * `|x| < 1` written in ten digits — the same reading as every other limit in research 02 §13,
     * where `9,999999999×10⁹⁹` plainly means "any value the calculator holds".
     */
    fun atanh(x: Dec): Dec? = if (x.abs() >= Dec.ONE) null else finish(HiHyperbolic.atanh(HiDec.of(x), WORK))

    // ---- Integers --------------------------------------------------------------------------

    /** `n!` for `0 ≤ n ≤ 69`, exact before one rounding (`69! ≈ 1,711224524×10⁹⁸`); else null. */
    fun factorial(n: Int): Dec? = DecPowers.factorial(n)

    /** Greatest common divisor of `|a|` and `|b|`; `gcd(0, 0) = 0`. Neither may be `Long.MIN_VALUE`. */
    fun gcd(a: Long, b: Long): Long = LongMath.gcd(a, b)

    /**
     * Least common multiple of `|a|` and `|b|`, `0` if either is `0`; null if it leaves a Long.
     * Neither may be `Long.MIN_VALUE` (as for [gcd]).
     */
    fun lcm(a: Long, b: Long): Long? = LongMath.lcm(a, b)

    /** `n = k²·r` with `r` squarefree, returned as `k to r` (`72 → 6 to 2`). Requires `n > 0`. */
    fun squareFreePart(n: Long): Pair<Long, Long> = LongMath.squareFreePart(n)

    /** True when `n ≥ 0` is the square of an integer. */
    fun isPerfectSquare(n: Long): Boolean = LongMath.isPerfectSquare(n)

    /** `⌊√n⌋` for `n ≥ 0`, exactly. */
    fun floorSqrt(n: Long): Long = LongMath.floorSqrt(n)

    /** `a × b`, or null if it leaves a Long. */
    fun multiplyExact(a: Long, b: Long): Long? = LongMath.multiplyExact(a, b)

    /** `a + b`, or null if it leaves a Long. */
    fun addExact(a: Long, b: Long): Long? = LongMath.addExact(a, b)

    /** `baseᵉˣᵖᵒⁿᵉⁿᵗ` for `exponent ≥ 0` (`0⁰ = 1`), or null if it leaves a Long. */
    fun powExact(base: Long, exponent: Int): Long? = LongMath.powExact(base, exponent)

    // ---- Plumbing ----------------------------------------------------------------------------

    private fun isPositive(x: Dec): Boolean = x.signum > 0 && !x.isOverflow

    /** `√((1 − x)(1 + x))`, the side opposite: exact products, so nothing cancels near ±1. */
    private fun complement(x: HiDec): HiDec {
        val g = WORK + HiConstants.GUARD
        val product = HiDec.ONE.sub(x, HiDec.EXACT).mul(HiDec.ONE.add(x, HiDec.EXACT), HiDec.EXACT)
        return product.sqrt(g)
    }

    private fun logRatio(x: HiDec, lnBase: HiDec): HiDec {
        val g = WORK + HiConstants.GUARD
        return HiExpLog.ln(x, g).div(lnBase, g).truncate(WORK)
    }

    /** One rounding to 15 digits; null if that lands at or above `10¹⁰⁰`. */
    private fun finish(h: HiDec): Dec? = h.toDec().takeUnless { it.isOverflow }
}
