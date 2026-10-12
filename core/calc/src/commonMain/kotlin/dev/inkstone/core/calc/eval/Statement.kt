package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.AngleUnit
import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.parse.BinOp
import dev.inkstone.core.calc.parse.Binary
import dev.inkstone.core.calc.parse.Call
import dev.inkstone.core.calc.parse.Equation
import dev.inkstone.core.calc.parse.Fn
import dev.inkstone.core.calc.parse.Node
import dev.inkstone.core.calc.parse.Statements
import dev.inkstone.core.calc.parse.Sym
import dev.inkstone.core.calc.parse.Symbolic
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.Coordinates
import dev.inkstone.core.calc.value.Division
import dev.inkstone.core.calc.value.MatrixValue
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.Value
import dev.inkstone.core.calc.value.VectorValue

/** The functions whose result the π form is tried on when evaluated in Rad (docs/CALCULATOR.md §3). */
private val RADIAN_PI_SOURCES = setOf(Fn.ASIN, Fn.ACOS, Fn.ATAN, Fn.POL, Fn.ARG)

/**
 * A whole statement, or each of a `:` line's statements in turn answering the last. The machine
 * normally splits a `:` line itself (EvalApi.kt) so it can move Ans and show `Disp` between them;
 * given the whole line, this does not move Ans — that is the machine's alone.
 */
internal fun Session.statement(node: Node): Ev<Answer> {
    if (node !is Statements) return single(node)
    var last: Ev<Answer> = failAt(CalcError.SYNTAX, node)
    for (part in node.list) {
        last = single(part)
        if (last !is Ev.Ok) return last
    }
    return last
}

/**
 * One statement, by the shape of its top:
 * - `V = expr` assigns, as CALC's assignment form does (research 02 §10.14: `A=B+C`,
 *   `Y=X²+X+3` — "an assignment with a single variable on the left"): the right side is
 *   evaluated, stored in V and answered. Any other equation (`3X+4=2X+6`, or one naming a solve
 *   variable) is SOLVE's, and evaluating it is Syntax ERROR (a **GUESS**);
 * - `Pol(`/`Rec(` answer both values and store them in X and Y (02 §10.10);
 * - a top-level `÷R` answers its remainder too (02 §10.12);
 * - anything else is a plain value.
 */
private fun Session.single(statement: Node): Ev<Answer> {
    Restrictions.violation(statement, mode)?.let { return it }
    return when {
        statement is Equation -> assignment(statement)
        mode == Mode.BASE_N -> BaseN(this).value(statement).map { Answer.Plain(it) }
        statement is Call && (statement.fn == Fn.POL || statement.fn == Fn.REC) -> coordinates(statement)
        statement is Binary && statement.op == BinOp.DIV_R -> division(statement)
        else -> plain(statement)
    }
}

private fun Session.plain(statement: Node): Ev<Answer> =
    eval(statement).map { Answer.Plain(settle(it), piEligible(statement)) }

private fun Session.valueOf(node: Node): Ev<Value> =
    if (mode == Mode.BASE_N) BaseN(this).value(node) else eval(node).map(::settle)

private fun Session.assignment(equation: Equation): Ev<Answer> {
    val (target, rhs) = Prompts.assignmentOf(equation) ?: return failAt(CalcError.SYNTAX, equation)
    val value = valueOf(rhs).orElse { return it }
    if (value is MatrixValue || value is VectorValue) return failAt(CalcError.SYNTAX, equation)
    memory.variables[target] = value
    return Ev.Ok(Answer.Plain(value, piEligible(rhs)))
}

/**
 * `Pol(x;y)` → r and θ, `Rec(r;θ)` → x and y, both stored in X and Y (research 02 §9, §10.10);
 * Ans takes the first ([Answer.Coordinates]). θ is in the current unit, `−180° < θ ≤ 180°`.
 */
private fun Session.coordinates(call: Call): Ev<Answer> {
    val a = real(call.args[0]).orElse { return it }
    val b = real(call.args[1]).orElse { return it }
    val polar = call.fn == Fn.POL
    val pair: Pair<Real, Real> = if (polar) {
        Coordinates.pol(a, b, unit).at(call).orElse { return it }.let { it.r to it.theta }
    } else {
        Coordinates.rec(a, b, unit).at(call).orElse { return it }.let { it.x to it.y }
    }
    val first = lineReal(pair.first)
    val second = lineReal(pair.second)
    memory.variables[Variable.X] = first
    memory.variables[Variable.Y] = second
    return Ev.Ok(Answer.Coordinates(first, second, polar, piEligible(call)))
}

private fun Session.division(node: Binary): Ev<Answer> = remainderDivision(node).map { division ->
    when (division) {
        is Division.WithRemainder -> Answer.Division(division.quotient, division.remainder)
        is Division.Ordinary -> Answer.Plain(division.quotient)
    }
}

/**
 * docs/CALCULATOR.md §3: the π form is tried only when the expression contains `π`, or an inverse
 * trig function, `Pol` or `arg` evaluated in Rad — the 2005 guide's rule, which both ZA examples
 * satisfy (`π÷6`, ∫ to π; research 02 §8.7, cross-check C14). The formatter does the rest
 * (|x| < 10⁶, the fraction search).
 */
internal fun Session.piEligible(node: Node): Boolean = Trees.any(node) { n ->
    (n is Sym && n.symbol == Symbolic.PI) || (unit == AngleUnit.RAD && n is Call && n.fn in RADIAN_PI_SOURCES)
}
