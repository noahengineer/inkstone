package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.eval.EvalOutcome
import dev.inkstone.core.calc.eval.StatisticsSource
import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.parse.ParseResult
import dev.inkstone.core.calc.parse.Radix
import dev.inkstone.core.calc.parse.Statements
import dev.inkstone.core.calc.parse.parse
import dev.inkstone.core.calc.value.Value

/** What a typed value came to: a value, an error with its place, or an `AC` that broke it off. */
sealed interface LineValue {
    data class Ok(val value: Value) : LineValue

    data class Error(val error: CalcError, val at: Cursor?) : LineValue

    data object Interrupted : LineValue
}

/**
 * Evaluates [expr] as **one value** — what `=` does to a CALC/SOLVE prompt, an editor cell
 * (research 03 §2.4: "any other expression can be typed into a cell and is evaluated when `=` is
 * pressed") or a mode's parameter prompt. A `:` multi-statement is a Syntax ERROR: they "are
 * refused in all editors" (research 06 §5.26), which is read here as an error rather than a
 * refused key (⚠️ GUESS). Ans is **not** touched; call [Host.recordAnswer] if the value should
 * become Ans. The keys an editor refuses outright (M+, STO, Pol, ÷R…) are the caller's to refuse.
 */
fun Host.evaluateLine(
    expr: Expr,
    interrupt: () -> Boolean,
    statistics: StatisticsSource? = null,
    radix: Radix = Radix.DEC,
): LineValue {
    val parsed = when (val result = parse(expr, complex = mode == Mode.CMPLX)) {
        is ParseResult.Failure -> return LineValue.Error(result.error, result.at)
        is ParseResult.Ok -> result
    }
    if (parsed.node is Statements) return LineValue.Error(CalcError.SYNTAX, null)
    return when (val outcome = evaluator.evaluate(parsed.node, parsed.spans, context(interrupt, statistics, radix))) {
        is EvalOutcome.Done -> LineValue.Ok(outcome.answer.value)
        is EvalOutcome.Failed -> LineValue.Error(outcome.error, outcome.at)
        EvalOutcome.Interrupted -> LineValue.Interrupted
    }
}
