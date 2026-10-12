package dev.inkstone.core.calc.format

import dev.inkstone.core.calc.ComplexFormat
import dev.inkstone.core.calc.FractionFormat
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.eval.Answer
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.parse.Radix
import dev.inkstone.core.calc.value.ComplexValue
import dev.inkstone.core.calc.value.DecimalReal
import dev.inkstone.core.calc.value.MatrixValue
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.Reals
import dev.inkstone.core.calc.value.Value
import dev.inkstone.core.calc.value.VectorValue

/**
 * How a result looks — the one production [Formatter] (research 02 §8, 03 §3, §5).
 *
 * A result is turned into an [Expr] the LCD lays out like any input: a MathO fraction is a
 * `FRACTION` template, a √ a `SQRT`, exponential form a `SCI_EXPONENT`; linear output uses the
 * LineIO spellings (`7⌟6`). Digits come from the stored [dev.inkstone.core.calc.num.Dec] itself
 * ([Numerals]) — never from a Double.
 *
 * The keys that restyle a result each move the [Presentation] on, and answer null when the key does
 * nothing: `S⇔D` and `SHIFT S⇔D` also answer null when the next face would draw exactly what is on
 * screen already (an integer has no other face), which is what pressing them there looks like.
 * In BASE-N those keys type hex digits or switch bases, so every one of them answers null there.
 * Matrices and vectors have no one-line form: their own screens draw them, cell by cell, with
 * [cellText], and [show] gives the calculation line no result for them.
 *
 * Natural output is SETUP's MthIO-MathO **in a mode that allows it**: STAT, BASE-N, MATRIX and
 * VECTOR force Linear Display (research 03 §2.2–2.3, VERIFIED), DIST is taken to as well (no DIST
 * screen shows `Math`, INFERRED), and the machine deliberately leaves SETUP itself alone there. So
 * the mode decides too ([naturalOutput]) — otherwise an exact value from those modes' own maths
 * comes out in a natural form: EX-VEC-07's cosine is the exact surd `11√5/25`, which Fix 3 in MathO
 * keeps as a surd (research 04 E-20), where the guide's VECTOR screen shows `0,984`.
 *
 * Every rule that is a guess about Casio's firmware rather than something the guide prints is
 * marked GUESS where it is made, so a run against Casio's emulator (research 07 §6) knows what to
 * check; docs/CALCULATOR.md §3 is followed throughout, with one exception — `Pol`'s θ is never
 * shown in π form, which a screen capture settles (see [TwoParts]).
 */
object Formatting : Formatter {
    /**
     * `SHIFT =` asks for the decimal instead of a √, π or fraction form (research 02 §8.8) — a DMS
     * result is none of those, so it is drawn as `=` draws it (`2°15'18"`, not `2,255`; the
     * type-4 record is a decimal already, 07 §4.1). [Presentation.shiftEquals] is kept either way.
     */
    override fun present(answer: Answer, setup: Setup, mode: Mode, decimal: Boolean): Presentation = Presentation(
        answer = answer,
        form = if (decimal && !isDms(answer)) Form.DECIMAL else Form.STANDARD,
        mixed = setup.fraction == FractionFormat.MIXED,
        shiftEquals = decimal,
    )

    /**
     * `S⇔D`. On a `Pol`/`Rec` result it toggles the parts' exact forms (`r=√2;θ=45`), a **GUESS**:
     * the guide disables `S⇔D` for `÷R` (02 §8.8, §10.12) but for `Pol`/`Rec` refuses only `FACT`
     * (§10.10), which reads as `S⇔D` working there.
     */
    override fun toggleExact(presentation: Presentation, setup: Setup, mode: Mode): Presentation? {
        if (mode == Mode.BASE_N) return null
        val next = when (val answer = presentation.answer) {
            is Answer.Plain -> plainExact(answer, presentation, setup, mode)
            is Answer.Coordinates -> Faces.partsExact(listOf(answer.first, answer.second), presentation)
            is Answer.Division -> null
        }
        return next?.takeIf { changes(presentation, it, setup, mode) }
    }

    private fun plainExact(answer: Answer.Plain, presentation: Presentation, setup: Setup, mode: Mode): Presentation? =
        when (val value = answer.value) {
            is Real -> Faces.exact(value, presentation, style(setup, mode, presentation), answer.piEligible)
            is ComplexValue -> Faces.partsExact(ComplexForms.drawnParts(value, look(answer, setup)), presentation)
            is MatrixValue, is VectorValue -> null
        }

    override fun toggleMixed(presentation: Presentation, setup: Setup, mode: Mode): Presentation? {
        val answer = presentation.answer as? Answer.Plain ?: return null
        val real = answer.value as? Real ?: return null
        if (mode == Mode.BASE_N) return null
        return Faces.mixed(real, presentation, style(setup, mode, presentation), answer.piEligible)
    }

    override fun engineering(presentation: Presentation, setup: Setup, mode: Mode, left: Boolean): Presentation? {
        val real = plainReal(presentation, mode) ?: return null
        return Faces.engineering(real, presentation, setup.format, left)
    }

    override fun toggleDms(presentation: Presentation, setup: Setup, mode: Mode): Presentation? {
        val real = plainReal(presentation, mode) ?: return null
        val answer = presentation.answer as? Answer.Plain ?: return null
        return Faces.dms(real, presentation, style(setup, mode, presentation), answer.piEligible)
    }

    /** `FACT` is a COMP feature (research 02 §10.13); elsewhere, and on Pol/Rec/÷R, nothing. */
    override fun factorize(presentation: Presentation, setup: Setup, mode: Mode): Presentation? {
        if (mode != Mode.COMP) return null
        val real = plainReal(presentation, mode) ?: return null
        return Faces.factorize(real, presentation, naturalOutput(setup, mode))
    }

    override fun show(presentation: Presentation, setup: Setup, mode: Mode, radix: Radix): Shown {
        if (mode == Mode.BASE_N) {
            return Shown(Expr(BaseN.items(presentation.answer.value, radix)), false, baseLabel = BaseN.label(radix))
        }
        val style = style(setup, mode, presentation)
        val decimal = presentation.form == Form.DECIMAL
        return when (val answer = presentation.answer) {
            is Answer.Coordinates -> TwoParts.coordinates(answer, decimal, style)
            is Answer.Division -> TwoParts.division(answer, decimal, style)
            is Answer.Plain -> plain(answer, presentation, style, setup)
        }
    }

    /**
     * A value drawn as `=` would draw it, where a screen shows one without a result line of its own:
     * CALC and SOLVE prompts, EQN solutions (`(3+√57)/4`, `-57/8`, 05 EX-EQN-03), RATIO's `9/2`.
     * Never in π form — there is no expression to make it eligible. A matrix or vector has no
     * one-line form; their screens draw cells with [cellText].
     */
    override fun valueExpr(value: Value, setup: Setup, mode: Mode, radix: Radix): Expr {
        if (mode == Mode.BASE_N) return Expr(BaseN.items(value, radix))
        val style = Style(setup.format, naturalOutput(setup, mode), setup.fraction == FractionFormat.MIXED)
        return when (value) {
            is Real -> Expr(RealForms.standard(value, style, piEligible = false).items)

            is ComplexValue -> {
                val look = ComplexLook(setup.complex, setup.angle, explicit = false)
                Expr(ComplexForms.line(value, decimal = false, style = style, look = look))
            }

            is MatrixValue, is VectorValue -> Expr.EMPTY
        }
    }

    override fun cellText(real: Real, setup: Setup): String = Cells.text(real.dec)

    private fun plain(answer: Answer.Plain, presentation: Presentation, style: Style, setup: Setup): Shown =
        when (val value = answer.value) {
            // `2▶r∠θ` asks for polar even when the evaluator hands back a plain real.
            is Real -> if (answer.complexFormat == ComplexFormat.POLAR) {
                complex(ComplexValue(value, Reals.ZERO), answer, presentation, style, setup)
            } else {
                Shown(Expr(Faces.real(value, presentation, style, answer.piEligible).items), style.natural)
            }

            is ComplexValue -> complex(value, answer, presentation, style, setup)

            is MatrixValue, is VectorValue -> Shown(null, style.natural)
        }

    private fun complex(
        z: ComplexValue,
        answer: Answer.Plain,
        presentation: Presentation,
        style: Style,
        setup: Setup,
    ): Shown = ComplexForms.shown(z, presentation.form == Form.DECIMAL, style, look(answer, setup))

    /** SETUP's complex format, unless `▶r∠θ` / `▶a+bi` ended the statement (research 03 §3). */
    private fun look(answer: Answer.Plain, setup: Setup): ComplexLook =
        ComplexLook(answer.complexFormat ?: setup.complex, setup.angle, answer.complexFormat != null)

    /**
     * The real value of an ordinary result, outside BASE-N; null for anything a key cannot restyle —
     * including a real that `▶r∠θ` draws as `2∠0`, where ENG, `°'"` and FACT have no number of
     * their own to restyle and a form they set would only be ignored by the polar drawing.
     */
    private fun plainReal(presentation: Presentation, mode: Mode): Real? {
        if (mode == Mode.BASE_N) return null
        val answer = presentation.answer as? Answer.Plain ?: return null
        return (answer.value as? Real)?.takeIf { answer.complexFormat != ComplexFormat.POLAR }
    }

    private fun isDms(answer: Answer): Boolean = ((answer as? Answer.Plain)?.value as? DecimalReal)?.dms == true

    private fun style(setup: Setup, mode: Mode, presentation: Presentation): Style =
        Style(setup.format, naturalOutput(setup, mode), presentation.mixed)

    /** MthIO-MathO, and a mode that does not force Linear Display (see the class KDoc). */
    private fun naturalOutput(setup: Setup, mode: Mode): Boolean = setup.io.naturalOutput && mode !in FORCED_LINEAR

    /** Research 03 §2.2: STAT, BASE-N, MATRIX, VECTOR VERIFIED; DIST INFERRED (no `Math` on its screens). */
    private val FORCED_LINEAR: Set<Mode> = setOf(Mode.STAT, Mode.BASE_N, Mode.MATRIX, Mode.VECTOR, Mode.DIST)

    private fun changes(from: Presentation, to: Presentation, setup: Setup, mode: Mode): Boolean =
        show(from, setup, mode, Radix.DEC) != show(to, setup, mode, Radix.DEC)
}
