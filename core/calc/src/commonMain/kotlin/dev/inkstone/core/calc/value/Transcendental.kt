package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.num.DecMath

/**
 * Logarithms, exponentials and the hyperbolic functions on [Real]. Their results are decimals —
 * none of them is among the guide's exact producers (research 02 §8.5–8.6) — computed by
 * [DecMath], which already enforces every limit here that is a property of the function alone
 * (research 02 §13: `sinh`/`cosh` to `230,2585092`, `tanh⁻¹` below 1, `sinh⁻¹`/`cosh⁻¹` below
 * 5×10⁹⁹, logs of positive numbers). Null from [DecMath] is Math ERROR.
 *
 * One exception to "decimal": `10^n` with an exact integer n is a power with an integer exponent,
 * which docs/CALCULATOR.md §3 keeps exact while it fits — `10^3 = 1000`, `10^−2 = 1/100`.
 */
object Transcendental {
    private val TEN = Reals.of(10)

    fun sinh(x: Real): Res<Real> = RealArith.decimalOf(DecMath.sinh(x.dec))

    fun cosh(x: Real): Res<Real> = RealArith.decimalOf(DecMath.cosh(x.dec))

    fun tanh(x: Real): Res<Real> = RealArith.decimalOf(DecMath.tanh(x.dec))

    fun asinh(x: Real): Res<Real> = RealArith.decimalOf(DecMath.asinh(x.dec))

    /** `cosh⁻¹ 1 = 0` (research 04 E-04); Math ERROR below 1. */
    fun acosh(x: Real): Res<Real> = RealArith.decimalOf(DecMath.acosh(x.dec))

    fun atanh(x: Real): Res<Real> = RealArith.decimalOf(DecMath.atanh(x.dec))

    /** `e^x` (the `e^▪` template). Math ERROR past `x ≈ 230,2585093`; zero far below. */
    fun exp(x: Real): Res<Real> = RealArith.decimalOf(DecMath.exp(x.dec))

    /** `10^x` (the `10^▪` template): exact for an exact integer x, else a decimal. */
    fun tenPow(x: Real): Res<Real> = when {
        x is FractionReal && x.den == 1L -> RealArith.powInt(TEN, x.num)
        else -> RealArith.decimalOf(DecMath.tenPow(x.dec))
    }

    fun ln(x: Real): Res<Real> = RealArith.decimalOf(DecMath.ln(x.dec))

    /** `log x`, base 10; exact for a power of ten (`log 1000 = 3`, research 04 E-09). */
    fun log10(x: Real): Res<Real> = RealArith.decimalOf(DecMath.log10(x.dec))

    /** `log(a;b)` and the `log▪▯` template: `log₂16 = 4` (04 E-10). Math ERROR for base 1. */
    fun log(base: Real, x: Real): Res<Real> = RealArith.decimalOf(DecMath.log(base.dec, x.dec))
}
