package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.AngleUnit
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.num.HiConstants
import dev.inkstone.core.calc.num.HiDec

/**
 * Angle units: the DRG▶ marks (`°`, `ʳ`, `ᵍ`, research 02 §10.4), and the radian conversions
 * every trig function in this package goes through.
 *
 * **Two π's, on purpose.**
 * - A conversion the *user* asks for — `(π÷2)ʳ` in Deg — uses Casio's π, [Dec.PI]
 *   (3,14159265358980), because the π in the user's expression is that one: `(π÷2)ʳ` is then
 *   exactly `90` (research 04 E-05), where the true π would leave `90,0000000000002` behind for
 *   the next `sin` to trip over. Deg ↔ Gra needs no π and stays exact: `50ᵍ = 45` (04 E-06).
 * - A trig function's *internal* conversion of a non-special angle uses the true π, after an
 *   exact reduction to one turn, so `sin 1°` is the correctly rounded sine of one degree. The
 *   calculator's own routine is UNKNOWN (07 T11); this can differ from it only in the 15th digit.
 */
object Angles {
    private const val DEGREES_HALF_TURN = 180
    private const val GRADS_HALF_TURN = 200
    private val DEGREES_TURN = Dec.of(360)
    private val GRADS_TURN = Dec.of(400)

    /** Working digits for the conversions, as [dev.inkstone.core.calc.num.DecMath] uses. */
    internal const val DIGITS = HiConstants.WORK + HiConstants.GUARD

    /**
     * `[value]` measured in [from], expressed in [to] — the DRG▶ marks convert into the current
     * unit. The result is never DMS-flagged, the same-unit case included (`30°0'0"°` in Deg is a
     * plain 30; that case used to keep the flag, a review finding).
     */
    fun convert(value: Real, from: AngleUnit, to: AngleUnit): Res<Real> = when {
        from == to -> Res.Ok(plain(value))
        from == AngleUnit.DEG && to == AngleUnit.GRA -> exactScale(value, TEN_NINTHS)
        from == AngleUnit.GRA && to == AngleUnit.DEG -> exactScale(value, NINE_TENTHS)
        else -> Reals.decimal(viaCasioPi(value.dec, from, to))
    }

    /** One turn in [unit]'s own measure, or null for radians (reduced by the true π instead). */
    internal fun turn(unit: AngleUnit): Dec? = when (unit) {
        AngleUnit.DEG -> DEGREES_TURN
        AngleUnit.GRA -> GRADS_TURN
        AngleUnit.RAD -> null
    }

    private fun halfTurn(unit: AngleUnit): Int = if (unit == AngleUnit.GRA) GRADS_HALF_TURN else DEGREES_HALF_TURN

    /**
     * [x] in [unit] as radians at working precision. Degrees and grads are first reduced by whole
     * turns **exactly** (see [reduce]) and only then multiplied by the true π, so a large angle
     * loses nothing.
     */
    internal fun toRadians(x: Dec, unit: AngleUnit): HiDec {
        val period = turn(unit) ?: return HiDec.of(x)
        val reduced = reduce(x, period)
        return HiDec.of(reduced).mul(HiConstants.pi, DIGITS).divSmall(halfTurn(unit), DIGITS)
    }

    /** A radian angle at working precision, in [unit], rounded once to 15 digits. */
    internal fun fromRadians(radians: HiDec, unit: AngleUnit): Dec {
        if (unit == AngleUnit.RAD) return radians.toDec()
        return radians.mulSmall(halfTurn(unit), DIGITS).div(HiConstants.pi, DIGITS).toDec()
    }

    /**
     * [x] less a whole number of [period]s, **exactly**, with `|result| < period`; [x] itself when
     * it is already that small. Exact because the subtraction only ever *shortens* the number:
     * once `|x| ≥ period` it has at least as many integer digits as the result, and the same last
     * decimal place, so a 15-digit x leaves at most 15 digits.
     *
     * The result keeps x's side of zero rather than being moved into `[0, period)`: adding a turn
     * to a small negative angle lengthens it — `−1×10⁻¹⁵ + 360` needs 18 digits — and the rounding
     * that followed made `sin(−1×10⁻¹⁵°)` exactly 0 (a review finding). sin, cos and tan take a
     * signed angle as readily as a positive one.
     */
    internal fun reduce(x: Dec, period: Dec): Dec {
        if (x.abs() < period) return x
        var r = x - (x / period).truncate() * period
        // x / period was rounded, so its integer part can be one out either way; r is exact
        // regardless, and these steps only shorten it.
        while (r >= period) r -= period
        while (r <= -period) r += period
        return r
    }

    private fun exactScale(value: Real, factor: Real): Res<Real> = RealArith.multiply(plain(value), factor)

    /** Converting an angle leaves the DMS display behind: the result is a plain number. */
    private fun plain(value: Real): Real = if (value is DecimalReal && value.dms) value.copy(dms = false) else value

    private fun viaCasioPi(x: Dec, from: AngleUnit, to: AngleUnit): Dec {
        val pi = HiDec.of(Dec.PI)
        val hx = HiDec.of(x)
        return if (from == AngleUnit.RAD) {
            hx.mulSmall(halfTurn(to), DIGITS).div(pi, DIGITS).toDec()
        } else {
            hx.mul(pi, DIGITS).divSmall(halfTurn(from), DIGITS).toDec()
        }
    }

    private val TEN_NINTHS: Real = FractionReal(10, 9)
    private val NINE_TENTHS: Real = FractionReal(9, 10)
}
