package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.AngleUnit
import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.parse.BaseLit
import dev.inkstone.core.calc.parse.Binary
import dev.inkstone.core.calc.parse.Call
import dev.inkstone.core.calc.parse.Const
import dev.inkstone.core.calc.parse.Convert
import dev.inkstone.core.calc.parse.Derivative
import dev.inkstone.core.calc.parse.Dms
import dev.inkstone.core.calc.parse.Equation
import dev.inkstone.core.calc.parse.Fraction
import dev.inkstone.core.calc.parse.Integral
import dev.inkstone.core.calc.parse.MatrixRef
import dev.inkstone.core.calc.parse.MixedFraction
import dev.inkstone.core.calc.parse.Negate
import dev.inkstone.core.calc.parse.Node
import dev.inkstone.core.calc.parse.NumberLit
import dev.inkstone.core.calc.parse.Postfix
import dev.inkstone.core.calc.parse.Power
import dev.inkstone.core.calc.parse.Root
import dev.inkstone.core.calc.parse.StatVar
import dev.inkstone.core.calc.parse.Statements
import dev.inkstone.core.calc.parse.Summation
import dev.inkstone.core.calc.parse.Sym
import dev.inkstone.core.calc.parse.Var
import dev.inkstone.core.calc.parse.VectorRef
import dev.inkstone.core.calc.value.ComplexValue
import dev.inkstone.core.calc.value.MatrixValue
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.Value
import dev.inkstone.core.calc.value.VectorValue

/**
 * One evaluation: the context it reads, and the little state a walk over the tree needs — how
 * many matrices and vectors are waiting on their stacks. The node kinds are evaluated by
 * extension functions grouped by subject (Scalars, Arithmetic, Postfixes, Structures, Functions,
 * Calculus); this class only dispatches and enforces what is common to all of them.
 *
 * Mode legality (an `i` in COMP, `MatA` outside MATRIX, ∫ nested in ∫ …) is decided before any of
 * this runs, by [Restrictions], so the walk can assume a tree that is legal in [mode].
 */
internal class Session(val context: EvalContext) {
    val setup: Setup get() = context.setup
    val mode: Mode get() = context.mode
    val memory: Memory get() = context.memory
    val unit: AngleUnit get() = context.setup.angle

    /**
     * MthIO-MathO: the only setting in which a result is shown in an exact form. Everywhere else
     * the output is linear, which has no √ form and shows a fraction only when one was typed with
     * `⌟` (research 02 §8.5: LineIO `1÷5` = `0,2` but `1−4⌟5` = `1⌟5`; §8.8, [S570]: linear
     * results are otherwise decimal). See [line].
     *
     * The modes in [LINEAR_ONLY] are linear **whatever SETUP says**: entering STAT, BASE-N, MATRIX
     * or VECTOR "switches the display to Linear automatically" (research 03 §2.3, VERIFIED [G]
     * p.12), and the machine leaves SETUP itself alone. The guide's own example shows it: EX-VEC-07
     * is set up from the default MthIO-MathO with Fix 3 and prints `0,984`, where MathO would keep
     * the surd `11√5/25`, which Fix does not touch (research 02 §8.3). Reading only `setup.io`
     * gave `MatA÷2` as halves and `1÷3` in STAT as `1/3` (a review finding).
     */
    val natural: Boolean = context.setup.io.naturalOutput && context.mode !in LINEAR_ONLY

    private var matricesHeld = 0
    private var vectorsHeld = 0

    /**
     * [node]'s value. A matrix or vector produced while [MATRIX_STACK] / [VECTOR_STACK] others are
     * already waiting is Stack ERROR (research 02 §11, sizes from [S570] pp.E-66/67 as recorded
     * in research 00 §5a). What counts as "waiting" is a **GUESS**: the left operand of every
     * binary operation still to be completed, which is what a stack machine holds.
     */
    fun eval(node: Node): Ev<Value> {
        val value = dispatch(node).orElse { return it }
        return when {
            value is MatrixValue && matricesHeld >= MATRIX_STACK -> failAt(CalcError.STACK, node)
            value is VectorValue && vectorsHeld >= VECTOR_STACK -> failAt(CalcError.STACK, node)
            else -> Ev.Ok(value)
        }
    }

    /** [node] as a real number; see [realOf]. */
    fun real(node: Node): Ev<Real> = eval(node).then { realOf(it, node) }

    /** [node] as a number, real or complex; a matrix or vector here is Syntax ERROR (a GUESS). */
    fun scalar(node: Node): Ev<Value> = eval(node).then {
        if (it is MatrixValue || it is VectorValue) failAt(CalcError.SYNTAX, node) else Ev.Ok(it)
    }

    /** [value] waits on its stack while the next operand is computed. */
    fun hold(value: Value) = shift(value, 1)

    fun release(value: Value) = shift(value, -1)

    private fun shift(value: Value, by: Int) {
        when (value) {
            is MatrixValue -> matricesHeld += by
            is VectorValue -> vectorsHeld += by
            else -> Unit
        }
    }

    /**
     * [value] as linear output can hold it: unchanged in MathO; elsewhere a surd, or a fraction
     * that is not a whole number, becomes a decimal **at this step** (docs/CALCULATOR.md §3's
     * "becomes a decimal at that step and stays one"). Applied to the results of every function,
     * root and angle conversion — the producers whose exact forms only Natural display shows — and,
     * through [lineQuotient], of divisions; never to `+ − ×`, `x²`, `x³`, `(−)` or a typed
     * fraction, which is how `2⌟3+1⌟2` still gives `7⌟6` in LineIO (research 04 C-07). That split
     * is a **GUESS** beyond the guide's printed cases (`1÷5` = `0,2`, `sin 30` = `0,5`,
     * `1−4⌟5` = `1⌟5`).
     */
    fun line(value: Value): Value = if (natural) value else LineForms.of(value)

    fun lineReal(x: Real): Real = if (natural) x else LineForms.real(x)

    /**
     * A division's result (`÷`, `x⁻¹`, `%`, a negative power) in linear output: exact when one of
     * its [operands] was itself a fraction — fraction arithmetic, as `2⌟3+1⌟2` = `7⌟6` is
     * (research 04 C-07), so `1⌟2÷3` is `1⌟6` — else [line], so `1÷5` is `0,2` (C-03). A **GUESS**
     * at the boundary between the guide's two printed cases.
     */
    fun lineQuotient(value: Value, vararg operands: Value): Value =
        if (operands.any(LineForms::isFraction)) value else line(value)

    fun interrupted(): Boolean = context.interrupt()

    private fun dispatch(node: Node): Ev<Value> = when (node) {
        is NumberLit -> number(node)

        is Var, is Sym, is Const, is StatVar, is MatrixRef, is VectorRef -> named(node)

        is Dms -> sexagesimal(node)

        is Negate -> negate(node)

        is Binary -> binary(node)

        is Postfix -> postfix(node)

        is Convert -> convert(node)

        is Power, is Root, is Fraction, is MixedFraction -> structured(node)

        is Call -> call(node)

        is Integral, is Derivative, is Summation -> calculus(node)

        // BASE-N literals are read by BaseN; equations and statements only stand at the top.
        is BaseLit, is Equation, is Statements -> failAt(CalcError.SYNTAX, node)
    }

    companion object {
        /** Matrices that can be on the stack at once ([S570] pp.E-66/67, research 00 §5a). */
        const val MATRIX_STACK = 3

        /** Vectors that can be on the stack at once (same source). */
        const val VECTOR_STACK = 5

        /**
         * The modes whose display is always Linear (see [natural]). DIST is not in the guide's
         * list but no DIST screen shows `Math` (research 03 §2.2, INFERRED); it is here because
         * the machine's `ModeController.LINEAR_ONLY` makes the same call for its input.
         */
        val LINEAR_ONLY: Set<Mode> = setOf(Mode.STAT, Mode.BASE_N, Mode.MATRIX, Mode.VECTOR, Mode.DIST)
    }
}

/**
 * [value] where a real number is needed: a complex number with a zero imaginary part is its real
 * part; any other complex number is Math ERROR — `sin(i)` and friends, which the guide never
 * defines (research 06 §6.3 "likely absent", a **GUESS**); a matrix or vector is Syntax ERROR
 * (a **GUESS**: it is a malformed request rather than an out-of-range one).
 */
internal fun realOf(value: Value, node: Node): Ev<Real> = when (value) {
    is Real -> Ev.Ok(value)
    is ComplexValue -> if (value.im.dec.isZero) Ev.Ok(value.re) else failAt(CalcError.MATH, node)
    is MatrixValue, is VectorValue -> failAt(CalcError.SYNTAX, node)
}

/**
 * A complex number whose imaginary part is zero, as the real number it is. CMPLX arithmetic keeps
 * reals real this way, so `(1+i)²+(1−i)²` answers a plain `0` (research 05 EX-CMPLX-05) and a
 * real-only function can take it.
 */
internal fun settle(value: Value): Value = if (value is ComplexValue && value.im.dec.isZero) value.re else value
