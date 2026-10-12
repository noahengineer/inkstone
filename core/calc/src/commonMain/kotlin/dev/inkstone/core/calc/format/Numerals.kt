package dev.inkstone.core.calc.format

import dev.inkstone.core.calc.NumberFormat
import dev.inkstone.core.calc.num.Dec

/**
 * A number as the LCD spells it: a sign, the mantissa's digits with the decimal **comma**
 * (`"1,234"`, `"0,005"`, `"10,000"`), and the power of ten drawn after the compact `×10` when the
 * number is in exponential form. [Items.numeral] turns it into tokens.
 */
internal data class Numeral(val negative: Boolean, val mantissa: String, val exponent: Int? = null)

/**
 * Display rounding: a stored 15-digit [Dec] to the digits the screen shows (research 02 §8.2–8.4,
 * 07 §4.2). Digits are read off the Dec's own significand — never through a Double, whose
 * shortest decimal is a different number often enough to show (`0,1+0,2`).
 *
 * Every rounding is half away from zero, and Norm's ranges are tested on the value **after** it is
 * rounded to ten digits (docs/CALCULATOR.md §3, both GUESSES pinned by tests: research 07 T5, T6).
 * So `9999999999,6` shows as `1×10¹⁰` — tested before rounding it would need eleven digits, which
 * the display does not have.
 */
internal object Numerals {
    /** Norm shows ten significant digits; also the display's digit budget (research 02 §8.2). */
    const val DISPLAY_DIGITS = 10

    /** `|x| ≥ 10¹⁰` is exponential in Norm 1 and Norm 2 alike (research 02 §8.3). */
    private const val FIRST_EXPONENTIAL = 10

    /** Norm 1 is plain down to `10⁻²`, Norm 2 down to `10⁻⁹` (research 02 §8.3, VERIFIED). */
    private const val NORM1_LOWEST_PLAIN = -2
    private const val NORM2_LOWEST_PLAIN = -9

    private val ZERO = Numeral(false, "0")

    fun of(d: Dec, format: NumberFormat): Numeral = when (format) {
        is NumberFormat.Norm -> norm(d, format.range)
        is NumberFormat.Fix -> fix(d, format.places)
        is NumberFormat.Sci -> scientific(d, sciDigits(format.digits))
    }

    /**
     * Norm 1/2: ten significant digits, trailing zeros dropped (`0,5`, `1200`), exponential
     * outside the range (`1÷200` is `5×10⁻³` in Norm 1, `0,005` in Norm 2 — research 04 A-02/03).
     */
    fun norm(d: Dec, range: Int): Numeral {
        val r = d.roundSignificant(DISPLAY_DIGITS)
        if (r.isZero) return ZERO
        val lowest = if (range == 1) NORM1_LOWEST_PLAIN else NORM2_LOWEST_PLAIN
        return if (r.exponent in lowest until FIRST_EXPONENTIAL) {
            Numeral(r.isNegative, positional(r.significandDigits, r.exponent + 1))
        } else {
            Numeral(r.isNegative, positional(r.significandDigits, 1), r.exponent)
        }
    }

    /**
     * Fix n: n decimal places, trailing zeros **kept** (`10,000`, research 02 §8.3, VERIFIED), a
     * value too small for them is `0,000`.
     *
     * Two **GUESSES**, both about the display's ten digits rather than anything the guide prints:
     * - the places shrink so integer digits plus places never pass ten. Fix 9 gives exactly ten
     *   positions below 10 (`0,523598776`), which reads as the range being *designed* to fill
     *   them; `176,2857143` rather than an eleventh and twelfth digit is the same budget;
     * - from `10¹⁰` up, where not even the integer fits, the number is exponential with n
     *   decimals in its mantissa (`1,235×10¹⁰` in Fix 3).
     */
    fun fix(d: Dec, places: Int): Numeral {
        val (r, decimals) = fixRounding(d, places)
        if (r.exponent >= FIRST_EXPONENTIAL) return scientific(d, minOf(places + 1, DISPLAY_DIGITS))
        if (r.isZero) return Numeral(false, positional("0", 1, decimals))
        return Numeral(r.isNegative, positional(r.significandDigits, r.exponent + 1, decimals))
    }

    /**
     * Fix's rounding of [d] and the decimals it keeps: [places], fewer where integer digits plus
     * places would pass ten (the budget in [fix]'s KDoc). An ENG mantissa under Fix is rounded the
     * same way ([engineering]), so `999999999,6` in Fix 9 is `999,9999996×10⁶` after ENG, not
     * `999,999999600×10⁶` — twelve digits on a display [fix] says has ten.
     */
    fun fixRounding(d: Dec, places: Int): Pair<Dec, Int> {
        var decimals = places
        var r = d.roundPlaces(decimals)
        while (decimals > 0 && integerDigits(r) + decimals > DISPLAY_DIGITS) {
            decimals--
            r = d.roundPlaces(decimals)
        }
        return r to decimals
    }

    /**
     * Exponential with exactly [count] significant digits, trailing zeros **kept**: Sci 3 shows
     * `ln 90` as `4,50×10⁰` (research 04 E-14), exponent 0 included. A rounding that carries
     * moves the exponent (`9,996` → `1,00×10¹`). Zero is `0,00×10⁰` — a GUESS.
     */
    fun scientific(d: Dec, count: Int): Numeral {
        val r = d.roundSignificant(count)
        val digits = (if (r.isZero) "0" else r.significandDigits).padEnd(count, '0')
        return Numeral(r.isNegative, positional(digits, 1), r.exponent)
    }

    /** Sci 0 means ten digits (research 02 §8.3: `Sci 0` gives `1,428571429×10⁻¹`). */
    fun sciDigits(setting: Int): Int = if (setting == 0) DISPLAY_DIGITS else setting

    /**
     * The significant digits the current format would show, which ENG keeps (see [engineering]):
     * Sci's own count, ten otherwise.
     */
    fun significance(format: NumberFormat): Int =
        if (format is NumberFormat.Sci) sciDigits(format.digits) else DISPLAY_DIGITS

    /**
     * `ENG`: [d] with the comma moved so the exponent is [exponent] (a multiple of 3): `1,234×10³`,
     * `1234×10⁰`, `0,000123×10⁶` (research 04 C-17/18). The digits are the ones the format shows
     * anyway — ten in Norm, Sci's count with its zeros — and under Fix the mantissa gets Fix's
     * places, within Fix's ten-digit budget ([fixRounding]). Carrying the format into the mantissa
     * is a **GUESS**; the guide's examples are all Norm.
     */
    fun engineering(d: Dec, format: NumberFormat, exponent: Int): Numeral {
        if (format is NumberFormat.Fix) {
            val (m, decimals) = fixRounding(d.scaleByPowerOfTen(-exponent), format.places)
            val digits = if (m.isZero) "0" else m.significandDigits
            val point = if (m.isZero) 1 else m.exponent + 1
            return Numeral(m.isNegative, positional(digits, point, decimals), exponent)
        }
        val count = significance(format)
        val r = d.roundSignificant(count)
        if (r.isZero) return Numeral(false, positional("0", 1), exponent)
        val digits = if (format is NumberFormat.Sci) r.significandDigits.padEnd(count, '0') else r.significandDigits
        return Numeral(r.isNegative, positional(digits, r.exponent - exponent + 1), exponent)
    }

    /**
     * [digits] with the comma after the first [point] of them — zeros go in front when [point] is
     * zero or less (`0,005`), and behind when it passes the end (`1200`) — and the fraction padded
     * with zeros to [minDecimals] places (Fix's `10,000`). No comma when there is no fraction.
     */
    fun positional(digits: String, point: Int, minDecimals: Int = 0): String {
        val whole: String
        val fraction: String
        when {
            point <= 0 -> {
                whole = "0"
                fraction = "0".repeat(-point) + digits
            }

            point >= digits.length -> {
                whole = digits + "0".repeat(point - digits.length)
                fraction = ""
            }

            else -> {
                whole = digits.substring(0, point)
                fraction = digits.substring(point)
            }
        }
        val padded = fraction.padEnd(minDecimals, '0')
        return if (padded.isEmpty()) whole else "$whole,$padded"
    }

    /** Digits before the comma, the `0` of `0,5` counting as one. */
    private fun integerDigits(r: Dec): Int = if (r.isZero || r.exponent < 0) 1 else r.exponent + 1
}
