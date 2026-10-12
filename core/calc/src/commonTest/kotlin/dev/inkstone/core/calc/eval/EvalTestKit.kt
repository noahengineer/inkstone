package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.InputOutput
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.NumberFormat
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.input.Keys
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.parse.BASIC_VECTORS
import dev.inkstone.core.calc.parse.CALCULUS_VECTORS
import dev.inkstone.core.calc.parse.COMP_VECTORS
import dev.inkstone.core.calc.parse.MODE_VECTORS
import dev.inkstone.core.calc.parse.ParseResult
import dev.inkstone.core.calc.parse.Radix
import dev.inkstone.core.calc.parse.parse
import dev.inkstone.core.calc.value.ComplexValue
import dev.inkstone.core.calc.value.DecimalReal
import dev.inkstone.core.calc.value.FractionReal
import dev.inkstone.core.calc.value.MatrixValue
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.SurdReal
import dev.inkstone.core.calc.value.SurdTerm
import dev.inkstone.core.calc.value.Value
import dev.inkstone.core.calc.value.VectorValue
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertIs
import kotlin.test.fail

/*
 * Driving the evaluator the way the machine will: type the guide's keys into an InputState
 * (input/Keys.kt), parse the line, evaluate it, and write the answer in one string that cannot be
 * mistaken — so a table row says "exact 7/6" or "a decimal that shows as 0,2" plainly:
 *
 * - an exact fraction or integer as `7/6`, `-5/2`, `12`;
 * - a surd as the guide prints it, terms in the value package's order: `45√3+10√2`, `√2/2`;
 * - a decimal as `~` plus the digits the **current display setting** shows (Norm: 10 significant;
 *   Fix n: n places; Sci n: n significant, Sci 0 = 10), in canonical text, so the guide's printed
 *   `14,286` (Fix 3) is `~14.286`; a DMS-flagged decimal ends in `dms`;
 * - complex `re|im`; a matrix row by row, `3 0 | 1 1`; a vector `4 6`;
 * - Pol/Rec `r=2;θ=45` / `X=1;Y=1`; ÷R `2;R=1`;
 * - an error by its CalcError name; AC as `INTERRUPTED`.
 */

/** Every input the parser's guide-vector tables hold, by research id. */
internal val GUIDE = (BASIC_VECTORS + COMP_VECTORS + CALCULUS_VECTORS + MODE_VECTORS).associateBy { it.id }

internal fun context(
    mode: Mode = Mode.COMP,
    setup: Setup = Setup(),
    memory: Memory = Memory(),
    statistics: StatisticsSource? = null,
    radix: Radix = Radix.DEC,
    random: Random = Random(SEED),
    interrupt: () -> Boolean = { false },
): EvalContext = EvalContext(mode, setup, memory, statistics, radix, random, interrupt)

internal const val SEED = 991

/** [keys] typed (Natural input unless [natural] is false) and parsed for [mode]. */
internal fun parsed(keys: String, natural: Boolean, mode: Mode = Mode.COMP, solve: Boolean = false): ParseResult.Ok {
    val result = parse(Keys.type(keys, natural).expr, complex = mode == Mode.CMPLX, solve = solve)
    return assertIs<ParseResult.Ok>(result, "'$keys' should parse, got $result")
}

internal fun evaluate(keys: String, natural: Boolean, context: EvalContext): EvalOutcome {
    val ok = parsed(keys, natural, context.mode)
    return Evaluation.evaluate(ok.node, ok.spans, context)
}

internal fun text(outcome: EvalOutcome, format: NumberFormat): String = when (outcome) {
    is EvalOutcome.Done -> text(outcome.answer, format)
    is EvalOutcome.Failed -> outcome.error.name
    EvalOutcome.Interrupted -> "INTERRUPTED"
}

internal fun text(answer: Answer, format: NumberFormat): String = when (answer) {
    is Answer.Plain -> text(answer.value, format)

    is Answer.Coordinates ->
        if (answer.polar) {
            "r=${text(answer.first, format)};θ=${text(answer.second, format)}"
        } else {
            "X=${text(answer.first, format)};Y=${text(answer.second, format)}"
        }

    is Answer.Division -> "${text(answer.quotient, format)};R=${text(answer.remainder, format)}"
}

internal fun text(value: Value, format: NumberFormat = NumberFormat.Norm(2)): String = when (value) {
    is Real -> real(value, format)

    is ComplexValue -> "${real(value.re, format)}|${real(value.im, format)}"

    is MatrixValue -> (0 until value.rows).joinToString(" | ") { i ->
        (0 until value.cols).joinToString(" ") { j -> real(value[i, j], format) }
    }

    is VectorValue -> value.cells.joinToString(" ") { real(it, format) }
}

private fun real(x: Real, format: NumberFormat): String = when (x) {
    is FractionReal -> if (x.den == 1L) "${x.num}" else "${x.num}/${x.den}"
    is SurdReal -> x.terms.mapIndexed { i, t -> term(t, first = i == 0) }.joinToString("")
    is DecimalReal -> "~" + displayed(x.dec, format).toCanonicalString() + if (x.dms) "dms" else ""
}

private fun displayed(d: Dec, format: NumberFormat): Dec = when (format) {
    is NumberFormat.Norm -> d.roundSignificant(NORM_DIGITS)
    is NumberFormat.Fix -> d.roundPlaces(format.places)
    is NumberFormat.Sci -> d.roundSignificant(if (format.digits == 0) NORM_DIGITS else format.digits)
}

private const val NORM_DIGITS = 10

private fun term(t: SurdTerm, first: Boolean): String {
    val sign = if (t.num < 0L) {
        "-"
    } else if (first) {
        ""
    } else {
        "+"
    }
    val magnitude = abs(t.num)
    val coefficient = if (magnitude == 1L && t.radicand != 1L) "" else "$magnitude"
    val root = if (t.radicand == 1L) "" else "√${t.radicand}"
    val denominator = if (t.den == 1L) "" else "/${t.den}"
    return sign + coefficient + root + denominator
}

/**
 * One evaluation to check: the keys of guide vector [id] (or [keys]), the setup — Natural or
 * linear input from the vector, LineIO when the guide typed it linearly — and what must come out.
 */
internal class Case(
    val id: String,
    val expected: String,
    val keys: String? = null,
    val natural: Boolean? = null,
    val setup: Setup? = null,
    val mode: Mode = Mode.COMP,
    val radix: Radix = Radix.DEC,
    val pi: Boolean? = null,
    val prepare: (Memory) -> Unit = {},
) {
    fun run(statistics: StatisticsSource? = null): String {
        val vector = GUIDE[id]
        val typed = keys ?: vector?.keys ?: fail("$id: no keys and no guide vector")
        val naturalInput = natural ?: vector?.natural ?: true
        val io = if (naturalInput) InputOutput.MATH_MATH else InputOutput.LINE_LINE
        val chosen = setup ?: Setup(io = io)
        val memory = Memory().also(prepare)
        val outcome = evaluate(typed, naturalInput, context(mode, chosen, memory, statistics, radix))
        val piMismatch = pi != null && (outcome as? EvalOutcome.Done)?.let { piOf(it.answer) } != pi
        return text(outcome, chosen.format) + if (piMismatch) " (π-eligibility should be $pi)" else ""
    }

    private fun piOf(answer: Answer): Boolean = when (answer) {
        is Answer.Plain -> answer.piEligible
        is Answer.Coordinates -> answer.piEligible
        is Answer.Division -> false
    }
}

/** Runs every case and fails once, listing every mismatch. */
internal fun verify(cases: List<Case>, statistics: StatisticsSource? = null) {
    val failures = cases.mapNotNull { case ->
        val got = case.run(statistics)
        if (got == case.expected) null else "${case.id}: expected ${case.expected}, got $got"
    }
    if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
}

/** Setups the guide's examples name, built on the vector's own input mode. */
internal fun line(format: NumberFormat = NumberFormat.Norm(2)) = Setup(io = InputOutput.LINE_LINE, format = format)

internal fun math(format: NumberFormat = NumberFormat.Norm(2)) = Setup(io = InputOutput.MATH_MATH, format = format)
