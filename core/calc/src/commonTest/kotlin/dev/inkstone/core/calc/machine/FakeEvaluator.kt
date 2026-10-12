package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.eval.Answer
import dev.inkstone.core.calc.eval.EvalContext
import dev.inkstone.core.calc.eval.EvalOutcome
import dev.inkstone.core.calc.eval.Evaluator
import dev.inkstone.core.calc.eval.SolveOutcome
import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.parse.BinOp
import dev.inkstone.core.calc.parse.Binary
import dev.inkstone.core.calc.parse.Equation
import dev.inkstone.core.calc.parse.Fraction
import dev.inkstone.core.calc.parse.Negate
import dev.inkstone.core.calc.parse.Node
import dev.inkstone.core.calc.parse.NumberLit
import dev.inkstone.core.calc.parse.Postfix
import dev.inkstone.core.calc.parse.PostfixOp
import dev.inkstone.core.calc.parse.SourceMap
import dev.inkstone.core.calc.parse.Statements
import dev.inkstone.core.calc.parse.Sym
import dev.inkstone.core.calc.parse.Symbolic
import dev.inkstone.core.calc.parse.Var
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.DecimalReal
import dev.inkstone.core.calc.value.FractionReal
import dev.inkstone.core.calc.value.Real

/**
 * An [Evaluator] for machine tests: exact rationals ([FractionReal]) over a small slice of the
 * language, so a test can say what a line *should* give without the real evaluator.
 *
 * What it does, and nothing more:
 * - whole-number literals; `+ − ×` (and the omitted ×), `÷`, the fraction template, `(−)`, x², x³;
 * - `÷R` on two positive integers gives [Answer.Division]; anything else there is a plain division,
 *   which is what the guide says the real one falls back to (research 02 §10.12);
 * - the nine variables, Ans and PreAns, when they hold fractions;
 * - an [Equation] `V = expr` (CALC's assignment form) stores the value in V and returns it;
 * - division by zero is Math ERROR at the end of the division node ([SourceMap.endOf]); any node
 *   outside the slice is Math ERROR with no place;
 * - [solve] finds the root of an equation that is **linear** in the variable exactly (two
 *   evaluations), stores it, and reports L−R; otherwise Can't Solve. It never reports Unfinished
 *   unless [unfinishedSolves] says so;
 * - a statement that is nothing but a decimal literal (`1,5`, what is typed into an editor cell)
 *   is a [DecimalReal], as docs/CALCULATOR.md §3 says a typed decimal is; a decimal literal inside
 *   any arithmetic is still outside the slice (Math ERROR);
 * - `▶r∠θ` and `▶a+bi` leave the value as it is, as the real evaluator does — which form the
 *   result is drawn in is the machine's to pass on ([ComplexOverride]).
 *
 * Test controls: [slow] makes every evaluation — and every solve — poll the interrupt first and
 * stop with `Interrupted` when it is set, as the contract allows a long one to. [evaluated]
 * records every statement asked for, and [starts] every value [solve] was asked to start from —
 * which the root found here does not depend on, so it is the only way a test can see it.
 * [forgetsSolutions] puts the variable back as it was after a solve, breaking the contract's
 * "already stored", so a test can see that the machine stores the solution itself.
 */
class FakeEvaluator : Evaluator {
    var slow = false
    var unfinishedSolves = 0
    var forgetsSolutions = false
    val evaluated = mutableListOf<Node>()
    val starts = mutableListOf<Real>()

    override fun evaluate(statement: Node, spans: SourceMap, context: EvalContext): EvalOutcome {
        evaluated += statement
        if (slow && context.interrupt()) return EvalOutcome.Interrupted
        decimal(statement)?.let { return EvalOutcome.Done(Answer.Plain(it)) }
        val run = Run(context, spans)
        if (statement is Binary && statement.op == BinOp.DIV_R) run.remainder(statement)?.let { return it }
        val value = run.of(statement) ?: return EvalOutcome.Failed(run.error, run.at)
        return EvalOutcome.Done(Answer.Plain(value))
    }

    override fun solve(
        equation: Equation,
        spans: SourceMap,
        context: EvalContext,
        variable: Variable,
        start: Real,
    ): SolveOutcome {
        starts += start
        if (slow && context.interrupt()) return SolveOutcome.Interrupted
        if (unfinishedSolves > 0) {
            unfinishedSolves--
            return SolveOutcome.Unfinished(variable, start)
        }
        val before = context.memory.variables.getValue(variable)
        val outcome = Run(context, spans).linearRoot(equation, variable) ?: SolveOutcome.Failed(CalcError.CANT_SOLVE)
        if (forgetsSolutions) context.memory.variables[variable] = before
        return outcome
    }

    override fun variablesOf(node: Node): List<Variable> = buildList { collect(node, this) }.distinct()

    private fun decimal(statement: Node): DecimalReal? {
        val literal = statement as? NumberLit ?: return null
        val fraction = literal.fracDigits ?: return null
        if (literal.exponent != null) return null
        return Dec.parse(literal.intDigits.ifEmpty { "0" } + "." + fraction.ifEmpty { "0" })?.let { DecimalReal(it) }
    }

    /**
     * In typing order, as the real `variablesOf` answers; an assignment `V=expr` computes V rather
     * than reading it, so only its right side is asked about (`eval.Prompts`).
     */
    private fun collect(node: Node, into: MutableList<Variable>) {
        when (node) {
            is Var -> into += node.variable
            is Negate -> collect(node.operand, into)
            is Binary -> listOf(node.left, node.right).forEach { collect(it, into) }
            is Fraction -> listOf(node.numerator, node.denominator).forEach { collect(it, into) }
            is Postfix -> collect(node.operand, into)
            is Equation -> assigned(node)?.let { collect(it, into) } ?: equationSides(node, into)
            is Statements -> node.list.forEach { collect(it, into) }
            else -> Unit
        }
    }

    private fun assigned(node: Equation): Node? = node.rhs?.takeIf { node.lhs is Var && node.solveFor == null }

    private fun equationSides(node: Equation, into: MutableList<Variable>) =
        listOfNotNull(node.lhs, node.rhs).forEach { collect(it, into) }

    private class Run(private val context: EvalContext, private val spans: SourceMap) {
        var error = CalcError.MATH
        var at: Cursor? = null

        fun of(node: Node): FractionReal? = when (node) {
            is NumberLit -> if (node.fracDigits == null && node.exponent == null) literal(node.intDigits) else null
            is Negate -> of(node.operand)?.let { FractionReal(-it.num, it.den) }
            is Binary -> binary(node)
            is Fraction -> divide(node, of(node.numerator), of(node.denominator))
            is Var -> context.memory.variables[node.variable] as? FractionReal
            is Sym -> symbol(node.symbol)
            is Postfix -> power(node)
            is Equation -> assign(node)
            else -> null
        }

        private fun literal(digits: String): FractionReal? = digits.toLongOrNull()?.let { FractionReal(it, 1) }

        private fun symbol(symbol: Symbolic): FractionReal? = when (symbol) {
            Symbolic.ANS -> context.memory.ans as? FractionReal
            Symbolic.PRE_ANS -> context.memory.preAns as? FractionReal
            else -> null
        }

        private fun binary(node: Binary): FractionReal? {
            val left = of(node.left) ?: return null
            val right = of(node.right) ?: return null
            return when (node.op) {
                BinOp.ADD -> add(left, right)
                BinOp.SUB -> sub(left, right)
                BinOp.MUL, BinOp.IMPLICIT_MUL -> mul(left, right)
                BinOp.DIV, BinOp.DIV_R -> divide(node, left, right)
                else -> null
            }
        }

        private fun power(node: Postfix): FractionReal? {
            val base = of(node.operand) ?: return null
            return when (node.op) {
                PostfixOp.SQUARE -> mul(base, base)
                PostfixOp.CUBE -> mul(base, base)?.let { mul(it, base) }
                PostfixOp.TO_POLAR, PostfixOp.TO_RECT -> base
                else -> null
            }
        }

        private fun assign(node: Equation): FractionReal? {
            val target = (node.lhs as? Var)?.variable ?: return null
            val value = of(node.rhs ?: return null) ?: return null
            context.memory.variables[target] = value
            return value
        }

        /** `a ÷R b` for positive integers; null to fall back to division. */
        fun remainder(node: Binary): EvalOutcome? {
            val a = of(node.left)?.takeIf { it.den == 1L && it.num > 0 } ?: return null
            val b = of(node.right)?.takeIf { it.den == 1L && it.num > 0 } ?: return null
            return EvalOutcome.Done(Answer.Division(FractionReal(a.num / b.num, 1), FractionReal(a.num % b.num, 1)))
        }

        /** The root of `lhs − rhs` when it is linear in [variable], stored there, with L−R. */
        fun linearRoot(equation: Equation, variable: Variable): SolveOutcome? {
            val f = Binary(BinOp.SUB, equation.lhs, equation.rhs ?: NumberLit("0"))
            val f0 = valueAt(f, variable, FractionReal(0, 1)) ?: return null
            val f1 = valueAt(f, variable, FractionReal(1, 1))
            val slope = f1?.let { sub(it, f0) }?.takeIf { it.num != 0L } ?: return null
            val root = div(FractionReal(-f0.num, f0.den), slope) ?: return null
            return valueAt(f, variable, root)?.let { SolveOutcome.Solved(variable, root, it) }
        }

        private fun valueAt(f: Node, variable: Variable, value: FractionReal): FractionReal? {
            context.memory.variables[variable] = value
            return of(f)
        }

        private fun divide(node: Node, a: FractionReal?, b: FractionReal?): FractionReal? {
            if (a == null || b == null) return null
            if (b.num == 0L) {
                at = spans.endOf(node)
                return null
            }
            return div(a, b)
        }

        fun add(a: FractionReal, b: FractionReal) = reduced(a.num * b.den + b.num * a.den, a.den * b.den)

        fun sub(a: FractionReal, b: FractionReal) = reduced(a.num * b.den - b.num * a.den, a.den * b.den)

        fun mul(a: FractionReal, b: FractionReal) = reduced(a.num * b.num, a.den * b.den)

        fun div(a: FractionReal, b: FractionReal) = reduced(a.num * b.den, a.den * b.num)

        private fun reduced(num: Long, den: Long): FractionReal? {
            if (den == 0L) return null
            val g = gcd(if (num < 0) -num else num, if (den < 0) -den else den).coerceAtLeast(1)
            val sign = if (den < 0) -1 else 1
            return FractionReal(sign * num / g, sign * den / g)
        }

        private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
    }
}
