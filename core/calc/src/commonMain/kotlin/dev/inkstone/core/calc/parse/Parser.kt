package dev.inkstone.core.calc.parse

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.Item
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.input.tokOrNull

/**
 * Parse an input line into a [Node], or say where it is malformed.
 *
 * The grammar, top down:
 * - a line is statements separated by `:` (research 02 §10.1), each one non-empty;
 * - a statement is an expression, optionally `= expression` (CALC, SOLVE: research 02 §10.14),
 *   optionally `; V` naming SOLVE's variable (research 02 §10.15 — the separator is `;` on the ZA,
 *   INFERRED, because the comma is the decimal point), optionally ended by `▶r∠θ` / `▶a+bi`, which
 *   format everything before them (research 03 §3);
 * - an expression follows the guide's thirteen priority levels (Operators.kt, Operands.kt,
 *   Calls.kt).
 *
 * An empty line is a Syntax ERROR at its start; the machine layer is expected not to evaluate one.
 *
 * @param complex the line is evaluated in CMPLX, where each value takes two numeric stack levels
 *   (see [ParseContext]); only the Stack ERROR depth changes.
 * @param solve the line is being parsed for SOLVE (`SHIFT CALC`). A `; V` that **ends the line**
 *   is then SOLVE's variable even when a bracket before it was left open: the ES family's guide
 *   gives `Y = X × log(2,Y` as log₁₀2 solved for Y, beside `Y = X × log(2,Y)` as log₂Y solved
 *   for X (research 02 [S570] p.E-29; INFERRED for the ZA, whose guide says only "do not omit
 *   closing parentheses"). Outside SOLVE that `;Y` stays log's second argument — ⚠️ GUESS: the
 *   guide documents the trailing variable only under SOLVE, so CALC and `=` are assumed to read
 *   the line as written.
 */
fun parse(expr: Expr, complex: Boolean = false, solve: Boolean = false): ParseResult {
    val context = ParseContext(complex)
    val target = if (solve) trailingVariable(expr.items) else null
    val items = if (target == null) expr.items else expr.items.dropLast(SOLVE_SUFFIX)
    val node = ParseState(items, emptyList(), context).parseLine(target)
    val error = context.error
    return if (node != null && error == null) {
        ParseResult.Ok(node, context.spans)
    } else {
        ParseResult.Failure(error ?: CalcError.SYNTAX, context.errorAt)
    }
}

/** `;` and a variable: SOLVE's suffix. */
private const val SOLVE_SUFFIX = 2

/** The `V` of a `; V` that ends [items], or null. */
private fun trailingVariable(items: List<Item>): Variable? {
    if (items.size < SOLVE_SUFFIX || items[items.size - SOLVE_SUFFIX].tokOrNull() != Tok.SEMICOLON) return null
    return items.last().tokOrNull()?.let { Tables.variables[it] }
}

/** The whole line; [solveFor] is a SOLVE suffix already cut off it, owed to the last statement. */
private fun ParseState.parseLine(solveFor: Variable?): Node? {
    if (items.isEmpty()) return fail()
    val statements = mutableListOf<Node>()
    do {
        if (statements.isNotEmpty()) pos++
        statements += parseStatement() ?: return null
    } while (!atEnd)
    // The line ends after the cut-off `; V`, not where this run stopped.
    val end = Cursor(path, if (solveFor == null) pos else pos + SOLVE_SUFFIX)
    if (solveFor != null) statements[statements.lastIndex] = solving(statements.last(), solveFor, end) ?: return null
    return statements.singleOrNull() ?: Statements(statements).also { ctx.spans.add(it, end) }
}

/**
 * [statement] solved for [variable], ending at [end], where the cut-off `; V` ended. A statement
 * that names its variable already (`Y=X;A;B`) is a Syntax ERROR at the second `;`.
 */
private fun ParseState.solving(statement: Node, variable: Variable, end: Cursor): Node? {
    val equation = when {
        statement !is Equation -> Equation(statement, null, variable)
        statement.solveFor == null -> statement.copy(solveFor = variable)
        else -> return fail()
    }
    ctx.spans.add(equation, end)
    return equation
}

/** One statement, ending at `:` or the end of the line. */
private fun ParseState.parseStatement(): Node? {
    val lhs = parseFormatted() ?: return null
    val rhs = if (peekTok() == Tok.EQUALS) rightHandSide() ?: return null else null
    val solveFor = if (peekTok() == Tok.SEMICOLON) solveTarget() ?: return null else null
    if (!atEnd && peekTok() != Tok.COLON) return fail()
    return if (rhs == null && solveFor == null) lhs else record(Equation(lhs, rhs, solveFor))
}

private fun ParseState.rightHandSide(): Node? {
    pos++
    return parseFormatted()
}

/** `; V` after an equation: the variable SOLVE solves for. */
private fun ParseState.solveTarget(): Variable? {
    pos++
    val variable = peekTok()?.let { Tables.variables[it] }
    if (variable == null) {
        fail()
    } else {
        pos++
    }
    return variable
}

/** An expression, optionally followed by `▶r∠θ` or `▶a+bi`. */
private fun ParseState.parseFormatted(): Node? {
    val node = parseExpression() ?: return null
    val format = peekTok()?.let { Tables.formats[it] } ?: return node
    pos++
    return record(Postfix(format, node))
}
