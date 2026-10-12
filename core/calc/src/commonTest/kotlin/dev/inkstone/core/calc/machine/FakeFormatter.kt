package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.ComplexFormat
import dev.inkstone.core.calc.FractionFormat
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.eval.Answer
import dev.inkstone.core.calc.format.Form
import dev.inkstone.core.calc.format.Formatter
import dev.inkstone.core.calc.format.Presentation
import dev.inkstone.core.calc.format.Shown
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.Item
import dev.inkstone.core.calc.input.Template
import dev.inkstone.core.calc.input.TemplateKind
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.input.TokenItem
import dev.inkstone.core.calc.lcd.LabelledValue
import dev.inkstone.core.calc.parse.Radix
import dev.inkstone.core.calc.value.FractionReal
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.Value

/**
 * A [Formatter] for machine tests. It makes each presentation **recognisable in
 * [dev.inkstone.core.calc.lcd.LcdFrame.text]** rather than right — the real formatter's digits are
 * not what these tests are about — and it answers null ("the key does nothing") wherever the
 * contract allows and the tests do not need more:
 *
 * - STANDARD: a whole number as its digits; any other fraction stacked (`3/4`) in MathO output,
 *   `3⌟4` otherwise; in BASE-N the number in the current base, with its `Dec`/`Hex` label.
 * - DECIMAL (`SHIFT =`, S⇔D): the canonical decimal with the comma (`0,75`). S⇔D toggles only a
 *   non-integer fraction; on anything else it returns null.
 * - ENGINEERING: the digits and an `×10^n` exponent; ENG lowers n by 3 and `SHIFT ENG` raises it.
 * - DMS: the digits followed by `°`. FACTORS: the digits followed by `!` — a marker, not a
 *   factorisation. Both toggle back to STANDARD.
 * - `SHIFT S⇔D` flips [Presentation.mixed] on a non-integer fraction and shows nothing different.
 * - ÷R: `q;R=r` on one line in MathO output, or the stacked quotient and `R=` in LineIO, as the
 *   guide draws them (research 02 §8.1).
 * - CMPLX with the `setup` it is handed in r∠θ: the value followed by `∠0` — a real's polar form,
 *   which is all the fake evaluator ever computes — so a test can see which complex format the
 *   machine asked for (SETUP's, or a result's own `▶r∠θ`/`▶a+bi`).
 */
class FakeFormatter : Formatter {
    override fun present(answer: Answer, setup: Setup, mode: Mode, decimal: Boolean): Presentation {
        val form = if (decimal) Form.DECIMAL else Form.STANDARD
        return Presentation(answer, form, mixed = setup.fraction == FractionFormat.MIXED)
    }

    override fun toggleExact(presentation: Presentation, setup: Setup, mode: Mode): Presentation? {
        val fraction = presentation.answer.value as? FractionReal ?: return null
        if (fraction.den == 1L) return null
        return presentation.copy(form = if (presentation.form == Form.DECIMAL) Form.STANDARD else Form.DECIMAL)
    }

    override fun toggleMixed(presentation: Presentation, setup: Setup, mode: Mode): Presentation? {
        val fraction = presentation.answer.value as? FractionReal ?: return null
        return if (fraction.den == 1L) null else presentation.copy(mixed = !presentation.mixed)
    }

    override fun engineering(presentation: Presentation, setup: Setup, mode: Mode, left: Boolean): Presentation? {
        if (presentation.answer.value !is Real) return null
        val step = if (left) ENG_STEP else -ENG_STEP
        return presentation.copy(form = Form.ENGINEERING, engExponent = presentation.engExponent + step)
    }

    override fun toggleDms(presentation: Presentation, setup: Setup, mode: Mode): Presentation? =
        toggle(presentation, Form.DMS)

    override fun factorize(presentation: Presentation, setup: Setup, mode: Mode): Presentation? =
        toggle(presentation, Form.FACTORS)

    private fun toggle(presentation: Presentation, form: Form): Presentation? {
        if (presentation.answer.value !is Real) return null
        return presentation.copy(form = if (presentation.form == form) Form.STANDARD else form)
    }

    override fun show(presentation: Presentation, setup: Setup, mode: Mode, radix: Radix): Shown {
        val natural = setup.io.naturalOutput
        val answer = presentation.answer
        val label = if (mode == Mode.BASE_N) BASE_LABELS.getValue(radix) else null
        if (answer is Answer.Division) return division(answer, natural)
        val value = answer.value
        val expr = when (presentation.form) {
            Form.DECIMAL -> decimal(value)
            Form.ENGINEERING -> Expr(exact(value, natural, radix).items + exponent(presentation.engExponent))
            Form.DMS -> Expr(exact(value, natural, radix).items + TokenItem(Tok.DMS))
            Form.FACTORS -> Expr(exact(value, natural, radix).items + TokenItem(Tok.FACTORIAL))
            else -> exact(value, natural, if (mode == Mode.BASE_N) radix else Radix.DEC)
        }
        val polar = mode == Mode.CMPLX && setup.complex == ComplexFormat.POLAR
        return Shown(if (polar) Expr(expr.items + POLAR_ZERO) else expr, naturalResult = natural, baseLabel = label)
    }

    override fun valueExpr(value: Value, setup: Setup, mode: Mode, radix: Radix): Expr = exact(value, false, radix)

    override fun cellText(real: Real, setup: Setup): String = real.dec.toCanonicalString().replace('.', ',')

    private fun division(answer: Answer.Division, natural: Boolean): Shown {
        val quotient = exact(answer.quotient, natural, Radix.DEC)
        val remainder = exact(answer.remainder, natural, Radix.DEC)
        if (!natural) return Shown(null, false, listOf(LabelledValue("", quotient), LabelledValue("R=", remainder)))
        val joined = quotient.items + TokenItem(Tok.SEMICOLON) + TokenItem(Tok.REMAINDER) + TokenItem(Tok.EQUALS)
        return Shown(Expr(joined + remainder.items), true)
    }

    private fun exact(value: Value, natural: Boolean, radix: Radix): Expr {
        val fraction = value as? FractionReal ?: return decimal(value)
        if (fraction.den == 1L) return digits(fraction.num.toString(RADIXES.getValue(radix)))
        val num = digits(fraction.num.toString())
        val den = digits(fraction.den.toString())
        if (!natural) return Expr(num.items + TokenItem(Tok.FRAC_SEP) + den.items)
        return Expr(listOf(Template(TemplateKind.FRACTION, listOf(num, den))))
    }

    private fun decimal(value: Value): Expr {
        val text = (value as? Real)?.dec?.toCanonicalString() ?: return Expr.EMPTY
        return digits(text)
    }

    private fun exponent(n: Int): Item = Template(TemplateKind.SCI_EXPONENT, listOf(digits(n.toString())))

    private fun digits(text: String): Expr = Expr(text.uppercase().mapNotNull { CHARS[it] }.map(::TokenItem))

    private companion object {
        const val ENG_STEP = 3

        val POLAR_ZERO: List<Item> = listOf(TokenItem(Tok.ANGLE), TokenItem(Tok.D0))

        val RADIXES = mapOf(Radix.DEC to 10, Radix.HEX to 16, Radix.BIN to 2, Radix.OCT to 8)

        val BASE_LABELS = mapOf(Radix.DEC to "Dec", Radix.HEX to "Hex", Radix.BIN to "Bin", Radix.OCT to "Oct")

        val CHARS: Map<Char, Tok> = mapOf(
            '0' to Tok.D0, '1' to Tok.D1, '2' to Tok.D2, '3' to Tok.D3, '4' to Tok.D4,
            '5' to Tok.D5, '6' to Tok.D6, '7' to Tok.D7, '8' to Tok.D8, '9' to Tok.D9,
            'A' to Tok.HEX_A, 'B' to Tok.HEX_B, 'C' to Tok.HEX_C, 'D' to Tok.HEX_D, 'E' to Tok.HEX_E,
            'F' to Tok.HEX_F, '.' to Tok.DECIMAL, '-' to Tok.NEGATIVE,
        )
    }
}
