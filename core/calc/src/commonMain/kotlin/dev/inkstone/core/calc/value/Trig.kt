package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.AngleUnit
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.num.DecMath
import dev.inkstone.core.calc.num.HiDec
import dev.inkstone.core.calc.num.HiTrig

/**
 * sin, cos, tan and their inverses on [Real], in an [AngleUnit].
 *
 * - **Exact special angles** first: a multiple of 15° (and the Rad/Gra equivalents) gives the
 *   ROM table's fraction or surd whatever the argument's own type (see [SpecialAngles]). This is
 *   what makes `sin(π÷6)` in Rad `1/2`, since π is a decimal.
 * - **Input limits** (research 02 §13, VERIFIED): `|x| < 9×10⁹` in Deg, `< 157079632,7` in Rad,
 *   `< 1×10¹⁰` in Gra, else Math ERROR; `tan` at an odd multiple of 90° is Math ERROR.
 * - Otherwise a decimal, correctly rounded from 48 working digits (docs/CALCULATOR.md §3, 07 T11).
 *
 * The inverses answer in the unit: `sin⁻¹(1/2) = 30` in Deg (research 04 E-02), `cos⁻¹ 0 = π/2`
 * in Rad. An exact argument that is one of the table's values gives the exact angle; any other
 * argument is computed in radians at working precision and converted once, so `sin⁻¹ 0,5` is
 * exactly `30` too.
 */
object Trig {
    private val DEGREE_LIMIT = Dec.fromDigits(false, "9", 1, 9)
    private val RADIAN_LIMIT = Dec.fromDigits(false, "1570796327", 9, 0)
    private val GRAD_LIMIT = Dec.fromDigits(false, "1", 1, 10)

    fun sin(x: Real, unit: AngleUnit): Res<Real> {
        if (outOfRange(x.dec, unit)) return Res.MATH
        SpecialAngles.indexOf(x.dec, unit)?.let { return Res.Ok(SpecialAngles.sin(it)) }
        return if (unit == AngleUnit.RAD) {
            RealArith.decimalOf(DecMath.sin(x.dec))
        } else {
            Reals.decimal(HiTrig.sin(Angles.toRadians(x.dec, unit), Angles.DIGITS).toDec())
        }
    }

    fun cos(x: Real, unit: AngleUnit): Res<Real> {
        if (outOfRange(x.dec, unit)) return Res.MATH
        SpecialAngles.indexOf(x.dec, unit)?.let { return Res.Ok(SpecialAngles.cos(it)) }
        return if (unit == AngleUnit.RAD) {
            RealArith.decimalOf(DecMath.cos(x.dec))
        } else {
            Reals.decimal(HiTrig.cos(Angles.toRadians(x.dec, unit), Angles.DIGITS).toDec())
        }
    }

    fun tan(x: Real, unit: AngleUnit): Res<Real> {
        if (outOfRange(x.dec, unit)) return Res.MATH
        SpecialAngles.indexOf(x.dec, unit)?.let { k ->
            return SpecialAngles.tan(k)?.let { Res.Ok(it) } ?: Res.MATH
        }
        return if (unit == AngleUnit.RAD) {
            RealArith.decimalOf(DecMath.tan(x.dec))
        } else {
            RealArith.decimalOf(HiTrig.tan(Angles.toRadians(x.dec, unit), Angles.DIGITS)?.toDec())
        }
    }

    /** `sin⁻¹`, in `[−90°, 90°]`; Math ERROR for `|x| > 1`. */
    fun asin(x: Real, unit: AngleUnit): Res<Real> {
        if (x.dec.abs() > Dec.ONE) return Res.MATH
        SpecialAngles.asinIndex(x)?.let { return Res.Ok(SpecialAngles.angle(it, unit)) }
        val hx = HiDec.of(x.dec)
        return inUnit(HiTrig.atan2(hx, complement(hx), Angles.DIGITS), unit)
    }

    /** `cos⁻¹`, in `[0°, 180°]`; Math ERROR for `|x| > 1`. */
    fun acos(x: Real, unit: AngleUnit): Res<Real> {
        if (x.dec.abs() > Dec.ONE) return Res.MATH
        SpecialAngles.acosIndex(x)?.let { return Res.Ok(SpecialAngles.angle(it, unit)) }
        val hx = HiDec.of(x.dec)
        return inUnit(HiTrig.atan2(complement(hx), hx, Angles.DIGITS), unit)
    }

    /** `tan⁻¹`, in `(−90°, 90°)`, for any x. */
    fun atan(x: Real, unit: AngleUnit): Res<Real> {
        SpecialAngles.atanIndex(x)?.let { return Res.Ok(SpecialAngles.angle(it, unit)) }
        return inUnit(HiTrig.atan(HiDec.of(x.dec), Angles.DIGITS), unit)
    }

    /**
     * The angle of the point ([x], [y]) in `(−180°, 180°]` (research 02 §10.10), for `Pol` and the
     * complex `arg`. Exact points on a 15° ray give the exact angle — `Pol(√2;√2)` is `θ=45`
     * (04 E-33), `arg(1+i) = 45` (05 EX-CMPLX-08) — because y/x is then one of the tangent table's
     * values. At the origin the angle is undefined and the real calculator's answer is UNKNOWN;
     * this answers **0** (a **GUESS**, research 07 §6 has no test for it).
     */
    fun atan2(y: Real, x: Real, unit: AngleUnit): Res<Real> {
        exactAtan2Index(y, x)?.let { return Res.Ok(SpecialAngles.angle(it, unit)) }
        val radians = HiTrig.atan2(HiDec.of(y.dec), HiDec.of(x.dec), Angles.DIGITS) ?: return Res.Ok(Reals.ZERO)
        return inUnit(radians, unit)
    }

    private fun exactAtan2Index(y: Real, x: Real): Int? {
        if (!Reals.isExact(x) || !Reals.isExact(y)) return null
        val sx = Reals.signum(x)
        val sy = Reals.signum(y)
        if (sx == 0) return if (sy == 0) null else sy * SpecialAngles.RIGHT
        val ratio = RealArith.divide(y, x).getOrNull() ?: return null
        val k = SpecialAngles.atanIndex(ratio) ?: return null
        return when {
            sx > 0 -> k
            sy >= 0 -> k + SpecialAngles.HALF_TURN
            else -> k - SpecialAngles.HALF_TURN
        }
    }

    private fun outOfRange(x: Dec, unit: AngleUnit): Boolean = x.abs() >= when (unit) {
        AngleUnit.DEG -> DEGREE_LIMIT
        AngleUnit.RAD -> RADIAN_LIMIT
        AngleUnit.GRA -> GRAD_LIMIT
    }

    private fun inUnit(radians: HiDec?, unit: AngleUnit): Res<Real> =
        if (radians == null) Res.MATH else Reals.decimal(Angles.fromRadians(radians, unit))

    /** `√((1 − x)(1 + x))`, the side opposite, with no cancellation near ±1 (as `DecMath.acos`). */
    private fun complement(x: HiDec): HiDec =
        HiDec.ONE.sub(x, HiDec.EXACT).mul(HiDec.ONE.add(x, HiDec.EXACT), HiDec.EXACT).sqrt(Angles.DIGITS)
}
