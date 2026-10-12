package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.parse.Equation
import dev.inkstone.core.calc.parse.Node
import dev.inkstone.core.calc.parse.SourceMap
import dev.inkstone.core.calc.parse.Statements
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.Real

/**
 * The evaluator: a parsed statement and a context in, an [Answer] or the calculator's error screen
 * out (EvalApi.kt). Every rule it applies is the guide's where the guide has one, and a documented
 * **GUESS** (docs/CALCULATOR.md §3) where it does not; the KDoc beside each rule says which.
 *
 * In outline:
 * - [Restrictions] refuses, before anything runs, what the mode or the position forbids;
 * - [Session] walks the tree with the value package's arithmetic, keeping exactness as far as a
 *   record can hold it, and — outside MthIO-MathO — giving up exact forms only linear output
 *   cannot show ([Session.line]);
 * - [BaseN] evaluates BASE-N's 32-bit integers on its own;
 * - [Quadrature], [Differentiation], `Σ` and [Solver] are the long operations, which poll
 *   [EvalContext.interrupt] and keep X as they found it.
 *
 * An error's place is the end of the node it happened in, which is where `[◀][▶]:Goto` puts the
 * cursor (research 02 §11: `14÷0×2` → after the `0`).
 *
 * For the machine:
 * - memory is written only where the calculator writes it: `Pol`/`Rec` into X and Y, a CALC
 *   assignment `V=expr` into V, SOLVE into its variable; ∫, d/dx and Σ borrow X and put it back.
 *   Ans and PreAns are never touched (EvalApi.kt);
 * - a `▶r∠θ` / `▶a+bi` at the end of a statement does not change its value: the machine reads it
 *   off the statement's node (`Postfix(TO_POLAR, …)`) when choosing how to show the answer;
 * - a CMPLX result whose imaginary part is exactly zero comes back as a [Real];
 * - SOLVE's setup steps are [equationOf], [solveTarget] and [solvePrompts].
 */
object Evaluation : Evaluator {
    override fun evaluate(statement: Node, spans: SourceMap, context: EvalContext): EvalOutcome =
        when (val result = Session(context).statement(statement)) {
            is Ev.Ok -> EvalOutcome.Done(result.value)
            is Ev.Fail -> EvalOutcome.Failed(result.error, result.node?.let(spans::endOf))
            Ev.Stop -> EvalOutcome.Interrupted
        }

    /**
     * SOLVE (research 02 §10.15): COMP only; ∫, d/dx, Σ, Pol, Rec and ÷R are not allowed in the
     * equation (Syntax ERROR, a **GUESS** as in [Restrictions]); a [variable] that does not occur in
     * it is **Variable ERROR** (02 §11). See [Solver] for the iteration.
     */
    override fun solve(
        equation: Equation,
        spans: SourceMap,
        context: EvalContext,
        variable: Variable,
        start: Real,
    ): SolveOutcome = when (val result = solving(equation, context, variable, start)) {
        is Ev.Ok -> result.value
        is Ev.Fail -> SolveOutcome.Failed(result.error, result.node?.let(spans::endOf))
        Ev.Stop -> SolveOutcome.Interrupted
    }

    private fun solving(equation: Equation, context: EvalContext, variable: Variable, start: Real): Ev<SolveOutcome> {
        if (context.mode != Mode.COMP) return failAt(CalcError.SYNTAX, equation)
        val banned = Restrictions.violation(equation, context.mode) ?: Restrictions.solveViolation(equation)
        if (banned != null) return banned
        if (variable !in Trees.variables(equation)) return failAt(CalcError.VARIABLE, equation)
        return Solver(Session(context), equation, variable).run(start.dec)
    }

    /** CALC's prompts, in order ([Prompts.calc]). */
    override fun variablesOf(node: Node): List<Variable> = Prompts.calc(node)

    /**
     * What SOLVE solves for a typed line: an equation as it stands, an expression as `expr = 0`
     * (research 02 §10.15), and nothing for a `:` line — SOLVE takes one equation, so the machine
     * shows Syntax ERROR for null (a **GUESS**; the guide has no multi-statement SOLVE).
     */
    fun equationOf(statement: Node): Equation? = when (statement) {
        is Statements -> null
        is Equation -> statement
        else -> Equation(statement, null)
    }

    /**
     * The variable SOLVE solves for: the one named after `;`, else X if the equation has one;
     * null means **Variable ERROR** (research 02 §11: "no solution variable given and no X").
     */
    fun solveTarget(equation: Equation): Variable? =
        equation.solveFor ?: Variable.X.takeIf { it in Trees.variables(equation) }

    /** The variables SOLVE asks for before `Solve for …`, in order ([Prompts.solve]). */
    fun solvePrompts(equation: Equation, variable: Variable): List<Variable> = Prompts.solve(equation, variable)
}
