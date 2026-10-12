package dev.inkstone.core.calc.parse

import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.Keys
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * A node as an S-expression, so a priority test reads `(+ 1 (* 2 3))` rather than a page of
 * constructors. Numbers print as typed (`1,2e-7`); operators by short names.
 */
fun show(node: Node): String = when (node) {
    is Negate -> "(neg ${show(node.operand)})"
    is Binary -> "(${OPS.getValue(node.op)} ${show(node.left)} ${show(node.right)})"
    is Postfix -> "(${node.op.name.lowercase()} ${show(node.operand)})"
    is Convert -> "(conv${node.number} ${show(node.operand)})"
    is Power, is Root, is Fraction, is MixedFraction, is Call -> showStructured(node)
    is Integral, is Derivative, is Summation, is Equation, is Statements -> showStructured(node)
    else -> showLeaf(node)
}

private fun showLeaf(node: Node): String = when (node) {
    is NumberLit -> node.intDigits + optional(",", node.fracDigits) + optional("e", node.exponent)
    is BaseLit -> "${node.radix?.name?.lowercase() ?: "base"}:${node.digits}"
    is Dms -> "(dms ${show(node.degrees)}${optional(" ", node.minutes)}${optional(" ", node.seconds)})"
    is Var -> node.variable.name
    is Sym -> node.symbol.name.lowercase()
    is Const -> "c${node.number}"
    is MatrixRef -> "Mat${node.name}"
    is VectorRef -> "Vct${node.name}"
    is StatVar -> node.stat.name.lowercase()
    else -> fail("no printer for $node")
}

private fun showStructured(node: Node): String = when (node) {
    is Power -> "(^ ${show(node.base)} ${show(node.exponent)})"
    is Root -> "(root ${show(node.index)} ${show(node.radicand)})"
    is Fraction -> "(frac ${show(node.numerator)} ${show(node.denominator)})"
    is MixedFraction -> "(mixed ${show(node.whole)} ${show(node.numerator)} ${show(node.denominator)})"
    is Call -> "(${node.fn.name.lowercase()} ${all(node.args)})"
    is Integral -> "(int ${all(listOfNotNull(node.f, node.lower, node.upper, node.tol))})"
    is Derivative -> "(ddx ${all(listOfNotNull(node.f, node.at, node.tol))})"
    is Summation -> "(sum ${all(listOf(node.f, node.lower, node.upper))})"
    is Equation -> "(= ${show(node.lhs)} ${node.rhs?.let(::show) ?: "_"}${optional(" ;", node.solveFor)})"
    is Statements -> "(: ${all(node.list)})"
    else -> fail("no printer for $node")
}

private fun all(nodes: List<Node>): String = nodes.joinToString(" ") { show(it) }

private fun optional(prefix: String, value: Any?): String = when (value) {
    null -> ""
    is Node -> prefix + show(value)
    else -> prefix + value
}

private val OPS = mapOf(
    BinOp.IMPLICIT_MUL to "imp", BinOp.NPR to "P", BinOp.NCR to "C", BinOp.ANGLE to "∠", BinOp.DOT to "dot",
    BinOp.MUL to "*", BinOp.DIV to "/", BinOp.DIV_R to "/R", BinOp.ADD to "+", BinOp.SUB to "-",
    BinOp.AND to "and", BinOp.OR to "or", BinOp.XOR to "xor", BinOp.XNOR to "xnor",
)

/** Parse [expr] and require success. */
fun parsed(expr: Expr, label: String = ""): ParseResult.Ok {
    val result = parse(expr)
    if (result is ParseResult.Failure) fail("$label: expected a parse, got $result")
    return assertIs<ParseResult.Ok>(result)
}

/** Type [keys] and show the parse. */
fun tree(keys: String, natural: Boolean = true): String = show(parsed(Keys.type(keys, natural).expr, keys).node)

/** Type [keys] and return the failure. */
fun failure(keys: String, natural: Boolean = true): ParseResult.Failure =
    assertIs<ParseResult.Failure>(parse(Keys.type(keys, natural).expr), keys)
