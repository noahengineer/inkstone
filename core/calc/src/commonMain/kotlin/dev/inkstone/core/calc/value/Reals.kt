package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.num.DecMath
import dev.inkstone.core.calc.num.HiDec
import kotlin.math.abs

/**
 * Making [Real]s, and the questions every other part of this package asks about them.
 *
 * The one rule this object exists to hold in one place: **a value is exact only while it fits
 * the record that holds it** (research 07 §4.1, docs/CALCULATOR.md §3):
 * - a fraction while `digits(num) + digits(den) ≤ 14` — the type-2 record's 15 nibbles less the
 *   separator. So an integer is exact up to 13 digits; a 14-digit integer is a [DecimalReal]
 *   holding exactly the same value;
 * - a surd while it has at most two terms, each `(c/q)·√r` with `|c| < 100`, `q < 100`, `r < 1000`
 *   — the type-8 record's two-digit coefficients and three-digit radicands, which are exactly the
 *   guide's `1 ≤ a < 100, 1 < b < 1000, 1 ≤ c < 100` (research 02 §8.6, VERIFIED).
 *
 * A [SurdReal]'s terms are kept in one canonical order — the rational term first, then by
 * descending radicand — because that is the order of every surd the guide prints: `45√3+10√2`,
 * `√3+3√2`, `√6+2√3`, `6−4√5`, `2−√3`, `(√6−√2)/4` (research 02 §8.6, 04 C-02, 07 §4.4). The
 * formatter can draw them in the order they come.
 */
object Reals {
    /** Digits a fraction record holds across numerator and denominator (docs/CALCULATOR.md §3). */
    const val FRACTION_DIGITS = 14

    /** Exclusive limit on a surd term's coefficient and denominator (research 02 §8.6). */
    const val SURD_COEFFICIENT_LIMIT = 100L

    /** Exclusive limit on a surd radicand (research 02 §8.6). */
    const val SURD_RADICAND_LIMIT = 1000L

    /** Digits a surd's decimal is computed to before its one rounding to 15. */
    private const val SURD_DIGITS = 40
    private const val DECIMAL = 10L

    val ZERO: Real = FractionReal(0, 1)
    val ONE: Real = FractionReal(1, 1)

    /**
     * An integer: exact while it has at most 13 digits, else a decimal — the same value up to 15
     * digits, rounded half away from zero past that (a Long can have 19).
     */
    fun of(n: Long): Real = Q.of(n)?.let(::fractionOrNull) ?: DecimalReal(Dec.of(n))

    /** `num/den` reduced; Math ERROR for a zero denominator; a decimal past the fraction limit. */
    fun fraction(num: Long, den: Long): Res<Real> {
        if (den == 0L) return Res.MATH
        val q = Q.of(num, den) ?: return decimal(Dec.of(num) / Dec.of(den))
        return fractionOrNull(q)?.let { Res.Ok(it) } ?: decimal(Dec.of(num) / Dec.of(den))
    }

    /**
     * A typed integer literal's value — exact when it is an integer of at most 13 digits — or a
     * decimal. The evaluator uses this for digit runs with no comma: typed decimals stay decimals
     * (docs/CALCULATOR.md §3), so a literal with a comma goes to [decimal] instead.
     */
    fun integerOrDecimal(d: Dec): Res<Real> {
        val n = d.toLongOrNull()
        return if (n != null) Res.Ok(of(n)) else decimal(d)
    }

    /** A decimal, or Math ERROR if it is outside ±1×10¹⁰⁰ (research 02 §8.2). */
    fun decimal(d: Dec, dms: Boolean = false): Res<Real> = if (d.isOverflow) Res.MATH else Res.Ok(DecimalReal(d, dms))

    /** True for a fraction or a surd: a value that has not yet lost exactness. */
    fun isExact(x: Real): Boolean = x !is DecimalReal

    fun isZero(x: Real): Boolean = x.dec.isZero

    fun signum(x: Real): Int = x.dec.signum

    /**
     * The value as a Long when it is a whole number, whatever record holds it — `5`, `10/2` and
     * `5,0` alike — else null. Integer-only functions (x!, nPr, GCD, FACT) read their arguments
     * through this: the guide's ranges are about the *value* ("x an integer", research 02 §13).
     */
    fun toLongExact(x: Real): Long? = when (x) {
        is FractionReal -> if (x.den == 1L) x.num else null
        is SurdReal -> null
        is DecimalReal -> x.dec.toLongOrNull()
    }

    /** Exact equality for exact values (terms, not decimals); never true for a decimal. */
    fun exactEquals(a: Real, b: Real): Boolean = when {
        a is FractionReal && b is FractionReal -> a == b
        a is SurdReal && b is SurdReal -> a.terms == b.terms
        else -> false
    }

    /**
     * The [Real] an exact working sum stands for, or null when no record can hold it — more than
     * two terms, a coefficient or radicand past its limit, or a fraction past 14 digits. Null is
     * the caller's cue to redo the step in decimal.
     */
    internal fun exactOrNull(sum: SurdSum): Real? {
        val rational = sum.rationalOrNull()
        if (rational != null) return fractionOrNull(rational)
        if (sum.terms.size > 2 || !sum.terms.all { (r, q) -> fitsSurd(r, q) }) return null
        val ordered = sum.terms.entries
            .sortedWith(compareBy({ it.key != 1L }, { -it.key }))
            .map { SurdTerm(it.value.num, it.value.den, it.key) }
        return SurdReal(ordered, surdDecimal(ordered))
    }

    /**
     * The exact rational a decimal *is* — `1,5` is `3/2`, `3,0` is `3` — or null when either part
     * would leave a Long. Not a way back to exactness (a decimal stays a decimal, docs/CALCULATOR.md
     * §3): it is for ranges the guide states by value, such as `ˣ√y`'s "x = (2n+1)/m" (02 §13),
     * the reading [dev.inkstone.core.calc.num.DecMath.pow] already gives a decimal exponent.
     */
    internal fun rationalValue(d: Dec): Q? {
        val numerator = if (d.isNegative) -d.coefficient else d.coefficient
        val power = Dec.POW10.getOrNull(if (d.scale >= 0) d.scale else -d.scale) ?: return null
        if (d.scale < 0) return Q.of(numerator, power)
        return DecMath.multiplyExact(numerator, power)?.let { Q.of(it) }
    }

    internal fun fractionOrNull(q: Q): Real? =
        if (digitCount(q.num) + digitCount(q.den) <= FRACTION_DIGITS) FractionReal(q.num, q.den) else null

    private fun fitsSurd(radicand: Long, q: Q): Boolean =
        q.abs().num < SURD_COEFFICIENT_LIMIT && q.den < SURD_COEFFICIENT_LIMIT && radicand < SURD_RADICAND_LIMIT

    /**
     * `Σ (c/q)·√r`, correctly rounded: each root and quotient at 40 digits, then one rounding to
     * 15. Rounding each term first and adding would put two roundings into `2−√3`, where the
     * subtraction also cancels the leading digit.
     */
    private fun surdDecimal(terms: List<SurdTerm>): Dec {
        var sum = HiDec.ZERO
        for (t in terms) {
            val root = HiDec.of(t.radicand).sqrt(SURD_DIGITS)
            val scaled = root.mulSmall(abs(t.num).toInt(), SURD_DIGITS)
            val magnitude = scaled.divSmall(t.den.toInt(), SURD_DIGITS)
            sum = sum.add(if (t.num < 0L) magnitude.negate() else magnitude, SURD_DIGITS)
        }
        return sum.toDec()
    }

    private fun digitCount(n: Long): Int {
        var m = if (n < 0L) -n else n
        var count = 1
        while (m >= DECIMAL) {
            m /= DECIMAL
            count++
        }
        return count
    }
}
