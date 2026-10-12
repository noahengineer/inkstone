package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.ComplexFormat
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.parse.Equation
import dev.inkstone.core.calc.parse.Node
import dev.inkstone.core.calc.parse.PostfixOp
import dev.inkstone.core.calc.parse.Radix
import dev.inkstone.core.calc.parse.Slot
import dev.inkstone.core.calc.parse.SourceMap
import dev.inkstone.core.calc.parse.StatVariable
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.FractionReal
import dev.inkstone.core.calc.value.MatrixValue
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.Value
import dev.inkstone.core.calc.value.VectorValue
import kotlin.random.Random

/**
 * What the calculator remembers between calculations (research 02 §9, 03 §7.2, §9.3): the nine
 * variables, Ans, PreAns and the matrix/vector slots. Owned and persisted by the key machine;
 * evaluation reads it, and writes it only where the calculator itself does (Pol/Rec into X and Y,
 * SOLVE into its variable). Ans/PreAns are moved by the machine, never by the evaluator.
 */
class Memory {
    val variables: MutableMap<Variable, Value> = Variable.entries.associateWith<Variable, Value> { ZERO }.toMutableMap()
    var ans: Value = ZERO
    var preAns: Value = ZERO
    val matrices: MutableMap<Slot, MatrixValue?> = Slot.entries.associateWith<Slot, MatrixValue?> {
        null
    }.toMutableMap()
    val vectors: MutableMap<Slot, VectorValue?> = Slot.entries.associateWith<Slot, VectorValue?> { null }.toMutableMap()

    companion object {
        val ZERO: Real = FractionReal(0, 1)
    }
}

/**
 * Statistics for the STAT-mode variables and estimates, supplied by STAT mode from its editor's
 * data. Every function answers null when the calculator would show Math ERROR (no data, a
 * regression that is undefined for the data, …).
 */
interface StatisticsSource {
    fun value(variable: StatVariable): Real?

    /** `x̂`, `x̂₁`, `x̂₂`, `ŷ` — postfix estimates from the current regression. */
    fun estimate(op: PostfixOp, operand: Real): Real?

    /** `▶t`: the normalised variate (x − x̄)/σx. */
    fun normalise(operand: Real): Real?
}

/** Everything one evaluation may consult. */
class EvalContext(
    val mode: Mode,
    val setup: Setup,
    val memory: Memory,
    val statistics: StatisticsSource? = null,
    /** BASE-N's current base: how a plain digit run is read and what the result is shown in. */
    val radix: Radix = Radix.DEC,
    val random: Random = Random.Default,
    /** Polled by long operations (∫, d/dx, Σ, SOLVE); true means AC was pressed: stop, quietly. */
    val interrupt: () -> Boolean = { false },
)

/** What a successful evaluation produced. [value] is what Ans becomes. */
sealed interface Answer {
    val value: Value

    /**
     * An ordinary result. [piEligible]: the expression may be shown in π form (it contains π, or
     * inverse trig / Pol / arg evaluated in Rad — docs/CALCULATOR.md §3).
     *
     * [complexFormat]: `▶r∠θ` or `▶a+bi` ended the statement and overrides SETUP's complex format
     * for this one result (research 03 §3, VERIFIED: `√2+√2i▶r∠θ` shows `2∠45` in a+bi, 05
     * EX-CMPLX-09/10). The value itself cannot carry it — a [dev.inkstone.core.calc.value.ComplexValue]
     * is always rectangular — so the evaluator says it here; null means "as SETUP says". Added in
     * stage 2 by the formatter, defaulted so every existing call still compiles.
     */
    data class Plain(
        override val value: Value,
        val piEligible: Boolean = false,
        val complexFormat: ComplexFormat? = null,
    ) : Answer

    /** `Pol` (r, θ) or `Rec` (x, y); both are also stored in X and Y. Ans takes [first]. */
    data class Coordinates(val first: Real, val second: Real, val polar: Boolean, val piEligible: Boolean = false) :
        Answer {
        override val value: Value get() = first
    }

    /** `÷R`: only the quotient goes to Ans (research 02 §10.12). */
    data class Division(val quotient: Real, val remainder: Real) : Answer {
        override val value: Value get() = quotient
    }
}

sealed interface EvalOutcome {
    data class Done(val answer: Answer) : EvalOutcome

    /** [at] is where `[◀][▶]:Goto` puts the cursor, if the error has a place (from the SourceMap). */
    data class Failed(val error: CalcError, val at: Cursor? = null) : EvalOutcome

    /** AC broke a long calculation. The machine returns to the input, showing nothing. */
    data object Interrupted : EvalOutcome
}

sealed interface SolveOutcome {
    /** The solution, already stored in [variable], with `L−R` (research 02 §10.15). */
    data class Solved(val variable: Variable, val value: Real, val leftMinusRight: Real) : SolveOutcome

    /** The iteration budget ran out: the machine shows `Continue:[=]`; `=` calls solve again from [current]. */
    data class Unfinished(val variable: Variable, val current: Real) : SolveOutcome

    data class Failed(val error: CalcError, val at: Cursor? = null) : SolveOutcome

    data object Interrupted : SolveOutcome
}

/**
 * Evaluation, as the key machine sees it. Implemented by `eval.Evaluation`; machine tests may use
 * a fake, which is why this is an interface (a seam for parallel construction, not for runtime
 * substitution — there is exactly one production implementation).
 */
interface Evaluator {
    /** One statement (the machine splits `:` multi-statements and evaluates them in turn). */
    fun evaluate(statement: Node, spans: SourceMap, context: EvalContext): EvalOutcome

    /** SOLVE for [variable], Newton's method from [start] (research 02 §10.15, docs/CALCULATOR.md §3). */
    fun solve(equation: Equation, spans: SourceMap, context: EvalContext, variable: Variable, start: Real): SolveOutcome

    /** The variables CALC prompts for, in prompt order (research 02 §10.14). */
    fun variablesOf(node: Node): List<Variable>
}
