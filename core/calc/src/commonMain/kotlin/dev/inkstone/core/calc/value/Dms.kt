package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.num.HiDec

/**
 * A sexagesimal value split for display: `±degrees°minutes'seconds"`. [seconds] is exact (it may
 * carry decimals); [roundedSeconds] is the display step.
 */
data class DmsParts(val negative: Boolean, val degrees: Long, val minutes: Int, val seconds: Dec) {
    /**
     * The same angle with its seconds rounded to [places] decimals, half away from zero, carrying
     * a rounded-up 60" into the minutes and 60' into the degrees — so `0°59'59,999"` at two places
     * reads `1°0'0"`, never `0°59'60"`. The guide warns the seconds are only good to ±1 in the
     * second decimal (research 02 §8.9), which is the place the formatter usually rounds to.
     */
    fun roundedSeconds(places: Int): DmsParts {
        var s = seconds.roundPlaces(places)
        var m = minutes
        var d = degrees
        if (s >= SIXTY) {
            s -= SIXTY
            m++
        }
        if (m >= MINUTES_PER_DEGREE) {
            m -= MINUTES_PER_DEGREE
            d++
        }
        return DmsParts(negative, d, m, s)
    }

    private companion object {
        const val MINUTES_PER_DEGREE = 60
        val SIXTY = Dec.of(60)
    }
}

/**
 * Degrees, minutes and seconds (research 02 §8.9, 06 §5.4). A DMS value is an ordinary decimal
 * flagged for sexagesimal display — the type-4 record (07 §4.1) — so arithmetic on it is decimal
 * arithmetic and [RealArith] carries the flag (`2°20'30" + 0°39'30" = 3°0'0"`, 04 C-14).
 */
object Dms {
    private const val SECONDS_PER_DEGREE = 3600
    private const val SECONDS_PER_MINUTE = 60

    /** `9999999°59'59"`, the largest angle the `°'"` conversion shows (research 02 §8.9). */
    private const val MAX_SECONDS = 35_999_999_999L

    /**
     * `a°b'c"` as a number: `a + b/60 + c/3600`, with a's sign applying to the whole — a guess for
     * a negative a typed inside the literal (the usual `(−)2°30'` negates the finished value
     * anyway). [minutes] and [seconds] must not be negative (research 02 §13); either may be
     * absent. One rounding, from the exact sum of seconds: `2°15'18" = 2,255` (04 C-15).
     */
    fun of(degrees: Real, minutes: Real?, seconds: Real?): Res<Real> {
        val m = minutes?.dec ?: Dec.ZERO
        val s = seconds?.dec ?: Dec.ZERO
        if (m.isNegative || s.isNegative) return Res.MATH
        val total = HiDec.of(degrees.dec.abs()).mulSmall(SECONDS_PER_DEGREE, HiDec.EXACT)
            .add(HiDec.of(m).mulSmall(SECONDS_PER_MINUTE, HiDec.EXACT), HiDec.EXACT)
            .add(HiDec.of(s), HiDec.EXACT)
        val value = total.divSmall(SECONDS_PER_DEGREE, Angles.DIGITS).toDec()
        return Reals.decimal(if (degrees.dec.isNegative) -value else value, dms = true)
    }

    /** [x] flagged for sexagesimal display (pressing `°'"` on a value), as a decimal. */
    fun marked(x: Real): Res<Real> = Reals.decimal(x.dec, dms = true)

    /**
     * [x] split into degrees, minutes and exact seconds, or null outside
     * `0°0'0" ≤ |x| ≤ 9999999°59'59"`, the guide's conversion range (research 02 §8.9) — the
     * formatter then shows the decimal.
     */
    fun parts(x: Real): DmsParts? {
        val (whole, fraction) = (if (x is FractionReal) exactSeconds(x) else decimalSeconds(x.dec)) ?: return null
        if (whole > MAX_SECONDS || (whole == MAX_SECONDS && !fraction.isZero)) return null
        val degrees = whole / SECONDS_PER_DEGREE
        val minutes = ((whole % SECONDS_PER_DEGREE) / SECONDS_PER_MINUTE).toInt()
        val seconds = Dec.of(whole % SECONDS_PER_MINUTE) + fraction
        return DmsParts(x.dec.isNegative, degrees, minutes, seconds)
    }

    /**
     * `|x| × 3600` as whole seconds and the fraction of one, exactly for a fraction — so one third
     * of a degree is `0°20'0"`, not `0°19'59,9999999999988"` from its 15-digit decimal.
     */
    private fun exactSeconds(x: FractionReal): Pair<Long, Dec> {
        // |num| has at most 13 digits, so ×3600 stays well inside a Long.
        val n = (if (x.num < 0L) -x.num else x.num) * SECONDS_PER_DEGREE
        return (n / x.den) to Dec.of(n % x.den) / Dec.of(x.den)
    }

    private fun decimalSeconds(x: Dec): Pair<Long, Dec>? {
        val total = HiDec.of(x.abs()).mulSmall(SECONDS_PER_DEGREE, HiDec.EXACT)
        val whole = total.floorAbs().toLongOrNull() ?: return null
        return whole to total.sub(HiDec.of(whole), HiDec.EXACT).toDec()
    }
}
