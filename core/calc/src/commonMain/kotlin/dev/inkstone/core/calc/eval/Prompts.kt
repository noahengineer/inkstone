package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.parse.Equation
import dev.inkstone.core.calc.parse.Node
import dev.inkstone.core.calc.parse.Statements
import dev.inkstone.core.calc.parse.Var
import dev.inkstone.core.calc.parse.Variable

/**
 * Which variables CALC and SOLVE ask for (research 02 §10.14–10.15).
 *
 * CALC asks for every variable the expression reads, once, **in the order they were typed**:
 * `3A+B` → `A?`, `B?` (04 E-47); `3A²+4B` → `A?`, `B?` (H-04). An assignment `V = expr` (CALC's
 * third form, 02 §10.14) computes V rather than reading it, so V is not asked for — unless the
 * right side reads it (`A=A+1`). In a multi-statement a variable assigned by an earlier statement
 * is not asked for again by a later one (`A=B+C:A×2` asks `B?`, `C?`). Both are **GUESSES** at
 * what the guide's word "prompts for each variable" means where it gives no example.
 */
internal object Prompts {
    fun calc(node: Node): List<Variable> {
        val prompts = LinkedHashSet<Variable>()
        val assigned = HashSet<Variable>()
        for (statement in (node as? Statements)?.list ?: listOf(node)) {
            val assignment = assignmentOf(statement)
            Trees.variables(assignment?.second ?: statement).filterTo(prompts) { it !in assigned }
            assignment?.let { assigned += it.first }
        }
        return prompts.toList()
    }

    /**
     * SOLVE asks for every variable but the one it solves for, in typing order, the left side's
     * included: `Y=AX²+B` asks `Y?`, `A?`, `B?`, then `Solve for X` (04 E-49).
     */
    fun solve(equation: Equation, variable: Variable): List<Variable> = Trees.variables(equation) - variable

    /** `V = expr` as CALC reads it: the variable assigned and the expression; null for anything else. */
    fun assignmentOf(statement: Node): Pair<Variable, Node>? {
        if (statement !is Equation || statement.solveFor != null) return null
        val target = (statement.lhs as? Var)?.variable ?: return null
        val rhs = statement.rhs ?: return null
        return target to rhs
    }
}
