package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.parse.Binary
import dev.inkstone.core.calc.parse.Call
import dev.inkstone.core.calc.parse.Convert
import dev.inkstone.core.calc.parse.Derivative
import dev.inkstone.core.calc.parse.Dms
import dev.inkstone.core.calc.parse.Equation
import dev.inkstone.core.calc.parse.Fraction
import dev.inkstone.core.calc.parse.Integral
import dev.inkstone.core.calc.parse.MixedFraction
import dev.inkstone.core.calc.parse.Negate
import dev.inkstone.core.calc.parse.Node
import dev.inkstone.core.calc.parse.Postfix
import dev.inkstone.core.calc.parse.Power
import dev.inkstone.core.calc.parse.Root
import dev.inkstone.core.calc.parse.Statements
import dev.inkstone.core.calc.parse.Summation

/**
 * Walks a parsed line for the machine's own structural checks — the functions SOLVE refuses
 * (research 02 §10.15). Meaning stays the evaluator's; this only finds nodes.
 */
internal object NodeTree {
    /** The first node of [root] (itself included, depth first, left to right) that [matches], or null. */
    fun find(root: Node, matches: (Node) -> Boolean): Node? {
        if (matches(root)) return root
        for (child in children(root)) find(child, matches)?.let { return it }
        return null
    }

    private fun children(node: Node): List<Node> = when (node) {
        is Negate -> listOf(node.operand)
        is Binary -> listOf(node.left, node.right)
        is Postfix -> listOf(node.operand)
        is Convert -> listOf(node.operand)
        is Power -> listOf(node.base, node.exponent)
        is Root -> listOf(node.index, node.radicand)
        is Fraction -> listOf(node.numerator, node.denominator)
        is MixedFraction -> listOf(node.whole, node.numerator, node.denominator)
        is Call -> node.args
        else -> outer(node)
    }

    /** The calculus templates and the statement-level nodes; leaves have none. */
    private fun outer(node: Node): List<Node> = when (node) {
        is Integral -> listOfNotNull(node.f, node.lower, node.upper, node.tol)
        is Derivative -> listOfNotNull(node.f, node.at, node.tol)
        is Summation -> listOf(node.f, node.lower, node.upper)
        is Equation -> listOfNotNull(node.lhs, node.rhs)
        is Statements -> node.list
        is Dms -> listOfNotNull(node.degrees, node.minutes, node.seconds)
        else -> emptyList()
    }
}
