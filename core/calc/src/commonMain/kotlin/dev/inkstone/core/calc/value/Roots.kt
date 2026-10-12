package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.num.BigNat
import dev.inkstone.core.calc.num.DecMath
import dev.inkstone.core.calc.num.NatRoots

/**
 * `√`, `∛` and `ⁿ√` on [Real].
 *
 * Only `√` makes a surd: it is the one root the guide lists among the √-form producers (research
 * 02 §8.6), and the type-8 record holds square roots and nothing else (07 §4.1). So `√8 = 2√2`,
 * `√(1/3) = √3/3` and `√(4/9) = 2/3`, while `∛` and `ⁿ√` are exact only for perfect powers
 * (`∛(27/8) = 3/2`, `⁵√32 = 2`) and otherwise decimal. A root of a surd or of a decimal is a
 * decimal.
 */
object Roots {
    /** No Long above 1 is a 63rd or higher power: 2⁶³ is already past `Long.MAX_VALUE`. */
    private const val MAX_LONG_ROOT = 63

    /** `√x`; Math ERROR for `x < 0` (research 02 §13). CMPLX's `√(−4) = 2i` is [ComplexMath.sqrt]. */
    fun sqrt(x: Real): Res<Real> {
        if (x.dec.isNegative) return Res.MATH
        if (x is FractionReal) exactSqrt(x)?.let { return Res.Ok(it) }
        return RealArith.decimalOf(DecMath.sqrt(x.dec))
    }

    /** `∛x`, negative x included (`∛−27 = −3`, research 02 §10.6). */
    fun cbrt(x: Real): Res<Real> {
        if (x is FractionReal) exactRoot(x, CUBE)?.let { return Res.Ok(it) }
        return RealArith.decimalOf(DecMath.cbrt(x.dec))
    }

    /**
     * `ˣ√y`: the [index]-th root of [x] (research 02 §13). An exact integer index is an n-th root —
     * Math ERROR for index 0, or for a negative x under an even index. Any other index is
     * `x^(1/index)`, so a fractional index takes [RealArith.pow]'s rule: a negative x needs the
     * reciprocal to have an odd denominator, the guide's `x = (2n+1)/m`.
     *
     * A **decimal** index is read by the exact value it holds — `3,0` is 3 and `1,5` is 3/2 — because
     * the guide states that range by value; and the answer is a decimal, because the index was
     * (02 §8.5). Rounding its reciprocal first made `3,0 ˣ√ −8` Math ERROR, `0,333333333333333`
     * having an even reduced denominator (a review finding).
     */
    fun root(index: Real, x: Real): Res<Real> {
        if (index is DecimalReal) return decimalIndexRoot(index, x)
        if (index is FractionReal && index.den == 1L && index.num in Int.MIN_VALUE..Int.MAX_VALUE) {
            return integerRoot(index.num.toInt(), x)
        }
        if (index.dec.isZero) return Res.MATH
        return RealArith.reciprocal(index).then { RealArith.pow(x, it) }
    }

    private fun decimalIndexRoot(index: DecimalReal, x: Real): Res<Real> {
        val exact = Reals.rationalValue(index.dec) ?: return RealArith.reciprocal(index).then { RealArith.pow(x, it) }
        if (exact.isZero) return Res.MATH
        // 1 ÷ (n/d) = d/n with the sign carried to the numerator, still in lowest terms.
        val sign = if (exact.num < 0L) -1L else 1L
        return RealArith.fractionPower(x, sign * exact.den, sign * exact.num).then { Reals.decimal(it.dec) }
    }

    private fun integerRoot(k: Int, x: Real): Res<Real> {
        if (k == 0) return Res.MATH
        if (x.dec.isZero) return if (k > 0) Res.Ok(x) else Res.MATH
        if (x.dec.isNegative && k % 2 == 0) return Res.MATH
        if (x is FractionReal && k != Int.MIN_VALUE) {
            val root = exactRoot(x, if (k < 0) -k else k)
            if (root != null) return if (k < 0) RealArith.reciprocal(root) else Res.Ok(root)
        }
        return RealArith.decimalOf(DecMath.nthRoot(x.dec, k))
    }

    /** `√(n/d) = √(n·d)/d`, simplified: `n·d < 10¹⁴` because the fraction has at most 14 digits. */
    private fun exactSqrt(x: FractionReal): Real? {
        if (x.num == 0L) return Reals.ZERO
        val (k, s) = DecMath.squareFreePart(x.num * x.den)
        val coefficient = Q.of(k, x.den) ?: return null
        return Reals.exactOrNull(SurdSum.of(mapOf(s to coefficient)))
    }

    /** The exact k-th root (k ≥ 1) of a fraction when both parts are perfect k-th powers, else null. */
    internal fun exactRoot(x: FractionReal, k: Int): FractionReal? {
        if (k == 1) return x
        val negative = x.num < 0L
        if (negative && k % 2 == 0) return null
        val n = integerRootOf(if (negative) -x.num else x.num, k) ?: return null
        val d = integerRootOf(x.den, k) ?: return null
        return FractionReal(if (negative) -n else n, d)
    }

    private fun integerRootOf(n: Long, k: Int): Long? {
        if (n < 2L) return n
        if (k >= MAX_LONG_ROOT) return null
        val r = NatRoots.iroot(BigNat.of(n), k).toLongOrNull() ?: return null
        return if (DecMath.powExact(r, k) == n) r else null
    }

    private const val CUBE = 3
}
