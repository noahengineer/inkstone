package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.Key
import dev.inkstone.core.calc.eval.Answer
import dev.inkstone.core.calc.eval.EvalOutcome
import dev.inkstone.core.calc.eval.Memory
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.InputState
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.input.TokenItem
import dev.inkstone.core.calc.parse.BinOp
import dev.inkstone.core.calc.parse.Binary
import dev.inkstone.core.calc.parse.SourceMap
import dev.inkstone.core.calc.parse.Sym
import dev.inkstone.core.calc.parse.Symbolic
import dev.inkstone.core.calc.parse.Var
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.Value

/*
 * STO, RCL, M+ and M− on the calculation line (research 02 §9, VERIFIED unless marked).
 */

/**
 * Which key names which variable after STO or RCL. "No ALPHA is needed after STO: `3+5 STO (−)`
 * gives A = 8" (research 06 §5.25), so it is the **physical** key that counts, whatever the S/A
 * latch says — `ALPHA (−)` works too, as the guide's own sequences press it.
 */
val VARIABLE_KEYS: Map<Key, Variable> = mapOf(
    Key.NEGATIVE to Variable.A,
    Key.DMS to Variable.B,
    Key.HYP to Variable.C,
    Key.SIN to Variable.D,
    Key.COS to Variable.E,
    Key.TAN to Variable.F,
    Key.CLOSE_PAREN to Variable.X,
    Key.S_TO_D to Variable.Y,
    Key.M_PLUS to Variable.M,
)

private val VARIABLE_TOKENS: Map<Variable, Tok> = mapOf(
    Variable.A to Tok.VAR_A,
    Variable.B to Tok.VAR_B,
    Variable.C to Tok.VAR_C,
    Variable.D to Tok.VAR_D,
    Variable.E to Tok.VAR_E,
    Variable.F to Tok.VAR_F,
    Variable.M to Tok.VAR_M,
    Variable.X to Tok.VAR_X,
    Variable.Y to Tok.VAR_Y,
)

/**
 * STO/RCL wait for a variable key with their indicator lit. `AC` gives up; any other key is
 * ignored (⚠️ GUESS — the guide only ever presses a variable key next).
 */
internal fun Calculation.awaitVariable(command: Command, press: Press) {
    if (press.command == Command.AC) {
        pending = null
        return
    }
    val variable = VARIABLE_KEYS[press.key] ?: return
    pending = null
    if (command == Command.STO) store(variable, press) else recall(variable)
}

/**
 * STO: "`3+5 STO A` shows 8" — an expression being typed is evaluated first, as `=` would; a
 * result on screen is stored as it is. STO updates Ans (research 02 §9, V\* from the sibling
 * guide). On an empty line Ans itself is stored (⚠️ GUESS). The input line keeps what was typed;
 * whether the firmware adds a `→A` is UNKNOWN.
 */
private fun Calculation.store(variable: Variable, press: Press) {
    val shown = view as? CalcView.Result
    val value = when {
        shown != null -> shown.answer.value.also { reassert(it) }
        input.expr.isEmpty -> showValue(Expr(listOf(TokenItem(Tok.ANS))), host.memory.ans)
        else -> execute(input.expr, 0, decimal = false, press)?.value ?: return
    }
    host.memory.variables[variable] = value
}

/**
 * RCL shows a variable's value as a result and updates Ans (research 02 §9; Ans update V\*). The
 * line shows the variable's name — ⚠️ GUESS at the layout; the guide prints only the value.
 *
 * RCL records its value even when Ans already holds an equal one, so PreAns takes the old Ans: a
 * recall is a new result on screen, and "a new result moves Ans into PreAns" (research 02 §9) says
 * nothing about values. STO and M+ differ on purpose ([reassert]): they act on a result the line
 * has just recorded, and recording it again would move that same result into PreAns. Whether the
 * firmware compares at all is UNKNOWN (research 02 §15 #12) — ⚠️ GUESS.
 */
private fun Calculation.recall(variable: Variable) {
    val value = host.memory.variables[variable] ?: Memory.ZERO
    host.recordAnswer(value)
    showValue(Expr(listOf(TokenItem(VARIABLE_TOKENS.getValue(variable)))), value)
}

/**
 * M+ / M−: "evaluates the current expression and adds the result to M; the displayed value is that
 * result" (`10×5 M+` shows 50). On a result already shown, that result is added. The sum itself is
 * the evaluator's, as `M+Ans` / `M−Ans` with Ans just set to the value, so exact values stay exact.
 * An empty line does nothing (⚠️ GUESS).
 */
internal fun Calculation.accumulate(press: Press, subtract: Boolean) {
    val shown = view as? CalcView.Result
    when {
        shown != null -> reassert(shown.answer.value)
        input.expr.isEmpty -> return
        execute(input.expr, 0, decimal = false, press) == null -> return
    }
    val sum = Binary(if (subtract) BinOp.SUB else BinOp.ADD, Var(Variable.M), Sym(Symbolic.ANS))
    when (val outcome = host.evaluator.evaluate(sum, SourceMap(), host.context(press.interrupt))) {
        is EvalOutcome.Done -> host.memory.variables[Variable.M] = outcome.answer.value
        is EvalOutcome.Failed -> fail(outcome.error, null)
        EvalOutcome.Interrupted -> Unit
    }
}

/** M+, M−, STO and RCL all update Ans; a result already in Ans is not recorded twice (PreAns would lose its value). */
private fun Calculation.reassert(value: Value) {
    if (host.memory.ans != value) host.recordAnswer(value)
}

/** A result screen for a value that no evaluation produced (RCL, STO of Ans). Not added to history. */
private fun Calculation.showValue(expr: Expr, value: Value): Value {
    input = InputState.editing(expr, host.naturalInput)
    scroll = 0
    view = CalcView.Result(expr, Answer.Plain(value), present(Answer.Plain(value), false), false, 0, 1, null)
    return value
}
