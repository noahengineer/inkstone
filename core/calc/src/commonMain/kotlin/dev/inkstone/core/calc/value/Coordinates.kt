package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.AngleUnit
import dev.inkstone.core.calc.NumberFormat
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.num.HiDec

/** `Pol`'s answer: the distance and the angle (in the current unit). */
data class Polar(val r: Real, val theta: Real)

/** `Rec`'s answer, and the parts of a complex number. */
data class Rectangular(val x: Real, val y: Real)

/**
 * `Pol`, `Rec` and `Rnd` — the functions that turn one value into another shape (research 02
 * §10.10–10.11).
 */
object Coordinates {
    /** Ten significant digits: what Norm shows, and what `Rnd` keeps under Norm (02 §10.11). */
    private const val NORM_DIGITS = 10

    /**
     * `Pol(x;y)`: `r = √(x²+y²)` and θ in `(−180°, 180°]` (research 02 §10.10). Exact where it can
     * be: `Pol(√2;√2)` is `r=2;θ=45` (04 E-33). A decimal pair is computed at working precision,
     * so `r` is in range whenever the guide says it is (`|x|, |y|, r ≤ 9,999999999×10⁹⁹`) even
     * when `x²` alone would not be. `Pol(0;0)` is `r=0;θ=0`, a **GUESS** (see [Trig.atan2]).
     */
    fun pol(x: Real, y: Real, unit: AngleUnit): Res<Polar> {
        val r = hypotenuse(x, y).valueOr { return it }
        val theta = Trig.atan2(y, x, unit).valueOr { return it }
        return Res.Ok(Polar(r, theta))
    }

    /** `√(x²+y²)`: exact for exact parts (`|1+i| = √2`), else at working precision. */
    internal fun hypotenuse(x: Real, y: Real): Res<Real> {
        if (!Reals.isExact(x) || !Reals.isExact(y)) return decimalHypotenuse(x.dec, y.dec)
        val x2 = RealArith.square(x).valueOr { return it }
        val y2 = RealArith.square(y).valueOr { return it }
        return RealArith.add(x2, y2).then { Roots.sqrt(it) }
    }

    /**
     * `Rec(r;θ)`: `x = r·cos θ`, `y = r·sin θ`, exact at special angles — `Rec(√2;45)` is
     * `X=1;Y=1` (04 E-35). θ has sin's range; `r < 0` is Math ERROR because the guide's range
     * is `0 ≤ r` (research 02 §13) — a **GUESS** that the range is enforced rather than merely
     * the documented accuracy domain. CMPLX's `r∠θ` uses [ComplexMath.polar], which takes any r.
     */
    fun rec(r: Real, theta: Real, unit: AngleUnit): Res<Rectangular> {
        if (r.dec.isNegative) return Res.MATH
        return rectangular(r, theta, unit)
    }

    /**
     * `Rnd(x)`: x rounded the way the display would round it, **into the stored value** — ten
     * significant digits in Norm, the set places in Fix, the set digits in Sci (`Sci 0` = ten),
     * half away from zero (research 02 §10.11, docs/CALCULATOR.md §3). The result is a decimal:
     * "Rnd makes the argument a decimal", so `Rnd(10÷3)×3 = 9,999` in Fix 3 (04 E-44).
     */
    fun rnd(x: Real, format: NumberFormat): Res<Real> {
        val d = x.dec
        val rounded = when (format) {
            is NumberFormat.Norm -> d.roundSignificant(NORM_DIGITS)
            is NumberFormat.Fix -> d.roundPlaces(format.places)
            is NumberFormat.Sci -> d.roundSignificant(if (format.digits == 0) NORM_DIGITS else format.digits)
        }
        return Reals.decimal(rounded, dms = x is DecimalReal && x.dms)
    }

    /** `r·cos θ + r·sin θ`, for any r. */
    internal fun rectangular(r: Real, theta: Real, unit: AngleUnit): Res<Rectangular> {
        val cos = Trig.cos(theta, unit).valueOr { return it }
        val sin = Trig.sin(theta, unit).valueOr { return it }
        val x = RealArith.multiply(r, cos).valueOr { return it }
        val y = RealArith.multiply(r, sin).valueOr { return it }
        return Res.Ok(Rectangular(x, y))
    }

    private fun decimalHypotenuse(x: Dec, y: Dec): Res<Real> {
        val hx = HiDec.of(x)
        val hy = HiDec.of(y)
        val sum = hx.mul(hx, HiDec.EXACT).add(hy.mul(hy, HiDec.EXACT), HiDec.EXACT)
        return Reals.decimal(sum.sqrt(Angles.DIGITS).toDec())
    }
}
