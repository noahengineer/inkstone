package dev.inkstone.core.calc.format

import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.eval.Answer
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.lcd.LabelledValue
import dev.inkstone.core.calc.parse.Radix
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.Value

/** Which face of a result is on show (research 02 §8). */
enum class Form {
    /** What `=` shows: exact (fraction, surd, π) in MathO where possible, else decimal. */
    STANDARD,

    /** Decimal: after `SHIFT =`, or `S⇔D` from an exact form. */
    DECIMAL,

    /** `S⇔D` from a decimal: its fraction or π form (never a surd — research 02 §8.8). */
    RECOVERED,

    /** `°'"` on a result: sexagesimal. */
    DMS,

    /** `ENG` / `SHIFT ENG`: exponent a multiple of 3, see [Presentation.engExponent]. */
    ENGINEERING,

    /** `FACT`: prime factorisation of an integer result (research 02 §10.13). */
    FACTORS,
}

/**
 * A result plus how it is being presented. The machine keeps the current one and asks the
 * [Formatter] for the next on each display key; the formatter owns what each key means.
 */
data class Presentation(
    val answer: Answer,
    val form: Form = Form.STANDARD,
    /** Mixed (`a b/c`) rather than improper — starts from SETUP, flipped by `SHIFT S⇔D`. */
    val mixed: Boolean = false,
    /** For [Form.ENGINEERING]: the exponent shown (a multiple of 3). */
    val engExponent: Int = 0,
    /**
     * The result was asked for with `SHIFT =`. Its decimal then toggles with `S⇔D` to a fraction or
     * a π form but **never** back to a √ form (research 02 §8.8, VERIFIED) — which a decimal
     * reached by `S⇔D` from that same √ form must do ("and back again", 04 C-02). The two decimals
     * look identical, so the presentation has to remember which one it is. Set by
     * [Formatter.present]; added in stage 2, defaulted so every existing call still compiles.
     */
    val shiftEquals: Boolean = false,
)

/** A presentation laid out for [dev.inkstone.core.calc.lcd.Screens]. */
data class Shown(
    /** The one-line result for `Screens.calculation`, or null when [parts] is used instead. */
    val result: Expr?,
    /** Draw [result] with templates (MathO) or linearly. */
    val naturalResult: Boolean,
    /** Two-part results stacked on their own lines (LineIO `r=`/`θ=`, `÷R`'s quotient and `R=`). */
    val parts: List<LabelledValue> = emptyList(),
    /** BASE-N's `Dec`/`Hex`/`Bin`/`Oct` label. */
    val baseLabel: String? = null,
)

/**
 * Formatting, as the key machine sees it. Implemented by `format.Formatting`; an interface only so
 * the machine could be built in parallel against a fake (one production implementation).
 * A function that answers null means the key does nothing to this result.
 */
interface Formatter {
    /** The first presentation of a fresh result; [decimal] for `SHIFT =`. */
    fun present(answer: Answer, setup: Setup, mode: Mode, decimal: Boolean): Presentation

    /** `S⇔D`. */
    fun toggleExact(presentation: Presentation, setup: Setup, mode: Mode): Presentation?

    /** `SHIFT S⇔D` (a b/c ⇔ d/c). */
    fun toggleMixed(presentation: Presentation, setup: Setup, mode: Mode): Presentation?

    /** `ENG` (exponent down by 3) or, with [left], `SHIFT ENG` (up by 3). */
    fun engineering(presentation: Presentation, setup: Setup, mode: Mode, left: Boolean): Presentation?

    /** `°'"` on a displayed result. */
    fun toggleDms(presentation: Presentation, setup: Setup, mode: Mode): Presentation?

    /** `FACT`. */
    fun factorize(presentation: Presentation, setup: Setup, mode: Mode): Presentation?

    fun show(presentation: Presentation, setup: Setup, mode: Mode, radix: Radix): Shown

    /** A value drawn like a result where a screen shows one (CALC/SOLVE prompts, EQN solutions, cells being edited). */
    fun valueExpr(value: Value, setup: Setup, mode: Mode, radix: Radix): Expr

    /** The short text of an editor cell (research 03 §2.4: the tiny font, about six characters). */
    fun cellText(real: Real, setup: Setup): String
}
