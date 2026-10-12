package dev.inkstone.core.calc.format

import dev.inkstone.core.calc.NumberFormat
import dev.inkstone.core.calc.num.Dec

/**
 * `ENG` and `SHIFT ENG` (←): which exponent comes next. Research 02 §8.4 gives four data points
 * and an INFERRED model, followed here:
 * - the first `ENG` shows standard engineering form, `1 ≤ |mantissa| < 1000`: `1234` →
 *   `1,234×10³` (04 C-17);
 * - each further `ENG` lowers the exponent by 3: `1234×10⁰`;
 * - the first `SHIFT ENG` is one step *above* standard form: `123` → `0,123×10³` (04 C-18), and
 *   each further one raises it by 3: `0,000123×10⁶`.
 *
 * How far it can go is UNKNOWN (02 §15 #4). The **GUESS**: while the mantissa stays inside the
 * window a ten-digit display can show without an exponent — `10⁻⁹ ≤ |mantissa| < 10¹⁰`, Norm 2's
 * own window — so `123` stops at `0,000000123×10⁹` and `1234` at `1234000000×10⁻⁶`; and while
 * the exponent itself stays inside the calculator's two-digit range, `10⁻⁹⁹ … 10⁹⁹` (07 §4.1), so
 * `9,5×10⁹⁹` has no `0,0095×10¹⁰²`. A press past either does nothing. Zero has only `0×10⁰`.
 */
internal object Engineering {
    private const val STEP = 3

    /** The mantissa's leading digit stays between 10⁻⁹ and 10⁹ (see the class KDoc). */
    private const val LOWEST_LEAD = -9
    private const val HIGHEST_LEAD = 9

    /** The exponent shown stays two digits, as every other exponent the calculator draws. */
    private val EXPONENTS = Dec.MIN_EXPONENT until Dec.OVERFLOW_EXPONENT

    /**
     * The exponent after one press, or null when the key does nothing. [current] is the exponent
     * on screen, or null when the result is not yet in ENG form.
     */
    fun next(d: Dec, format: NumberFormat, current: Int?, left: Boolean): Int? {
        val shown = d.roundSignificant(Numerals.significance(format))
        if (shown.isZero) return if (current == null) 0 else null
        val lead = shown.exponent
        val exponent = when {
            current != null -> current + if (left) STEP else -STEP
            else -> standard(d, format, lead) + if (left) STEP else 0
        }
        return exponent.takeIf { lead - it in LOWEST_LEAD..HIGHEST_LEAD && it in EXPONENTS }
    }

    /**
     * The exponent of standard engineering form, `1 ≤ |mantissa| < 1000`. floorDiv, not `/`:
     * `0,05` (exponent −2) is `50×10⁻³`, and `-2 / 3` would make it `0,05×10⁰`. Under Fix the
     * mantissa is rounded as [Numerals.engineering] rounds it — Fix's places within the ten-digit
     * budget — which can carry it to `1000` (`999,9996` in Fix 3) — then standard form is one step
     * up, `1,000×10³`.
     */
    private fun standard(d: Dec, format: NumberFormat, lead: Int): Int {
        val exponent = lead.floorDiv(STEP) * STEP
        if (format !is NumberFormat.Fix) return exponent
        val (mantissa, _) = Numerals.fixRounding(d.scaleByPowerOfTen(-exponent), format.places)
        return if (mantissa.exponent >= STEP) exponent + STEP else exponent
    }
}
