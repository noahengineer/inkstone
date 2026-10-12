package dev.inkstone.core.calc.eval

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
import dev.inkstone.core.calc.parse.Var
import dev.inkstone.core.calc.parse.Variable

/**
 * Walking a parse tree in **source order** — the order its items were typed — which is the order
 * CALC prompts in (research 02 §10.14: `3A+B` asks `A?` then `B?`, 04 E-47) and the order the
 * first offending item is met in.
 */
internal object Trees {
    fun children(node: Node): List<Node> = when (node) {
        is Negate -> listOf(node.operand)
        is Binary -> listOf(node.left, node.right)
        is Postfix -> listOf(node.operand)
        is Convert -> listOf(node.operand)
        is Power -> listOf(node.base, node.exponent)
        is Root -> listOf(node.index, node.radicand)
        is Fraction -> listOf(node.numerator, node.denominator)
        else -> structureChildren(node)
    }

    private fun structureChildren(node: Node): List<Node> = when (node) {
        is MixedFraction -> listOf(node.whole, node.numerator, node.denominator)
        is Call -> node.args
        is Integral -> listOfNotNull(node.f, node.lower, node.upper, node.tol)
        is Derivative -> listOfNotNull(node.f, node.at, node.tol)
        is Summation -> listOf(node.f, node.lower, node.upper)
        is Equation -> listOfNotNull(node.lhs, node.rhs)
        is Statements -> node.list
        is Dms -> listOfNotNull(node.degrees, node.minutes, node.seconds)
        else -> emptyList()
    }

    fun any(node: Node, test: (Node) -> Boolean): Boolean = first(node, test) != null

    /** The first node, in source order, that passes [test]. */
    fun first(node: Node, test: (Node) -> Boolean): Node? {
        if (test(node)) return node
        for (child in children(node)) first(child, test)?.let { return it }
        return null
    }

    /** The function a calculus node samples, with X as its bound variable; null for anything else. */
    fun integrand(node: Node): Node? = when (node) {
        is Integral -> node.f
        is Derivative -> node.f
        is Summation -> node.f
        else -> null
    }

    /**
     * Every variable [node] reads, once each, in source order. X inside a ∫, d/dx or Σ function
     * is that construct's own variable — set for each sample and restored after (research 07
     * §4.6) — so it is not a value the expression reads (a **GUESS** for CALC's prompts).
     */
    fun variables(node: Node): List<Variable> {
        val found = LinkedHashSet<Variable>()
        collect(node, found, bound = false)
        return found.toList()
    }

    private fun collect(node: Node, into: MutableSet<Variable>, bound: Boolean) {
        if (node is Var && !(bound && node.variable == Variable.X)) into += node.variable
        val f = integrand(node)
        for (child in children(node)) collect(child, into, bound || child === f)
    }
}
