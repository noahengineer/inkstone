package dev.inkstone.core.calc.format

import dev.inkstone.core.calc.eval.Answer
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.Item
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.lcd.LabelledValue
import dev.inkstone.core.calc.value.Real

/**
 * The results that come in two parts (research 02 §8.1, VERIFIED): `Pol`, `Rec` and `÷R`.
 * - MathO joins them on one line with `;` and no spaces: `r=2;θ=45`, `X=1;Y=1`, `2;R=1` (the
 *   capture on the guide's p.7 has none, though the printed text does).
 * - Linear output stacks them: `r=` above `θ=`, and for `÷R` the quotient alone above `R=`
 *   (04 C-20) — LineIO in the guide; MthIO-LineO is taken to stack too, being linear output
 *   (a **GUESS**).
 *
 * Each part is drawn as `=` would draw it alone, exact forms included — but never in π form.
 * docs/CALCULATOR.md §3 lists `Pol` in Rad among the π-eligible expressions, and research 04 F-01
 * is a capture of exactly that case in MathO showing `r=2;θ=0,7853981▶` — a decimal where `1/4π`
 * would have fitted. The capture wins: [Answer.Coordinates.piEligible] is not used here.
 */
internal object TwoParts {
    private val SEMICOLON: Item = Items.tok(Tok.SEMICOLON)
    private val EQUALS: Item = Items.tok(Tok.EQUALS)

    fun coordinates(answer: Answer.Coordinates, decimal: Boolean, style: Style): Shown {
        val first = part(answer.first, decimal, style)
        val second = part(answer.second, decimal, style)
        val (firstLabel, secondLabel) = if (answer.polar) POLAR_LABELS else RECTANGULAR_LABELS
        if (!style.natural) {
            return stacked(LabelledValue(firstLabel, Expr(first)), LabelledValue(secondLabel, Expr(second)))
        }
        val (firstName, secondName) = if (answer.polar) Tok.RESULT_R to Tok.THETA else Tok.VAR_X to Tok.VAR_Y
        val line = listOf(Items.tok(firstName), EQUALS) + first + SEMICOLON + Items.tok(secondName) + EQUALS + second
        return Shown(Expr(line), true)
    }

    /** `÷R`: `2;R=1` in MathO; the quotient over `R=1` in linear output (research 02 §10.12). */
    fun division(answer: Answer.Division, decimal: Boolean, style: Style): Shown {
        val quotient = part(answer.quotient, decimal, style)
        val remainder = part(answer.remainder, decimal, style)
        if (!style.natural) {
            return stacked(LabelledValue("", Expr(quotient)), LabelledValue("R=", Expr(remainder)))
        }
        val line = quotient + SEMICOLON + Items.tok(Tok.REMAINDER) + EQUALS + remainder
        return Shown(Expr(line), true)
    }

    private fun part(x: Real, decimal: Boolean, style: Style): List<Item> = RealForms.part(x, decimal, style)

    private fun stacked(upper: LabelledValue, lower: LabelledValue): Shown = Shown(null, false, listOf(upper, lower))

    private val POLAR_LABELS = "r=" to "θ="
    private val RECTANGULAR_LABELS = "X=" to "Y="
}
