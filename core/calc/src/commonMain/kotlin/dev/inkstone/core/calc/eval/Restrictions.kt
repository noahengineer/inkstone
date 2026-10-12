package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.parse.BaseLit
import dev.inkstone.core.calc.parse.BinOp
import dev.inkstone.core.calc.parse.Binary
import dev.inkstone.core.calc.parse.Call
import dev.inkstone.core.calc.parse.Const
import dev.inkstone.core.calc.parse.Convert
import dev.inkstone.core.calc.parse.Derivative
import dev.inkstone.core.calc.parse.Equation
import dev.inkstone.core.calc.parse.Fn
import dev.inkstone.core.calc.parse.Integral
import dev.inkstone.core.calc.parse.MatrixRef
import dev.inkstone.core.calc.parse.Node
import dev.inkstone.core.calc.parse.Postfix
import dev.inkstone.core.calc.parse.PostfixOp
import dev.inkstone.core.calc.parse.StatVar
import dev.inkstone.core.calc.parse.Statements
import dev.inkstone.core.calc.parse.Summation
import dev.inkstone.core.calc.parse.Sym
import dev.inkstone.core.calc.parse.Symbolic
import dev.inkstone.core.calc.parse.VectorRef

/**
 * What may be written where, checked over the whole statement **before** anything is evaluated,
 * so an illegal item is reported even when evaluation would never reach it, at the first such
 * item in typing order, and nothing (X, Y, the variables) has been touched.
 *
 * Every refusal here is **Syntax ERROR** — a **GUESS** throughout: the guide says only that these
 * items "cannot be used" (research 02 §10.7–10.9, 03 §5.3, §8, 06 §5.14), and on the real
 * calculator most of them cannot even be typed in the wrong mode (their keys mean something else
 * there), so the error is reached only by a line recalled from history or a machine bug.
 *
 * - **Mode**: `i`, `∠`, `arg`, `Conjg`, `▶r∠θ`/`▶a+bi` are CMPLX's (research 03 §3); `PreAns` is
 *   COMP's (02 §9); logic and base prefixes BASE-N's (03 §5); Mat… and `det`/`Trn`/`Ref`/`Rref`
 *   MATRIX's (03 §7.1); Vct… and `•` VECTOR's (03 §9.1); STAT variables, estimates, `▶t` and
 *   `P(`/`Q(`/`R(` STAT's (03 §4); ∫, d/dx, Σ and `÷R` COMP's (02 §10.7–10.9, 06 §5.6); CONST
 *   everywhere but BASE-N, CONV everywhere but BASE-N and TABLE (02 §10.16); `Pol`/`Rec` on the
 *   calculation screens of COMP, STAT, MATRIX and VECTOR (banned in TABLE, DIST and every editor,
 *   06 §5.14; CMPLX and BASE-N are a **GUESS**); a `=` assignment (CALC's form) in COMP and CMPLX
 *   (02 §10.14).
 * - **Nesting**: ∫, d/dx and Σ may not appear inside one another (in f, the limits or tol), and
 *   Pol, Rec and ÷R may not appear in their f (02 §10.7–10.9).
 * - **Whole statements only**: `Pol`/`Rec` answer two values into X and Y, so they cannot be part
 *   of a larger expression (a **GUESS**, as on the fx-82MS family); an equation or a `:` only ever
 *   stands at the top (the parser already guarantees it).
 */
internal object Restrictions {
    private enum class Scope { TOP, NESTED, CALCULUS_LIMIT, INTEGRAND }

    fun violation(statement: Node, mode: Mode): Ev.Fail? = find(statement, mode, Scope.TOP)

    /**
     * SOLVE's own bans (research 02 §10.15): no ∫, d/dx, Σ, Pol, Rec or ÷R anywhere in the
     * equation.
     */
    fun solveViolation(equation: Node): Ev.Fail? {
        val banned = Trees.first(equation) { isCalculus(it) || isCoordinates(it) || isRemainder(it) }
        return banned?.let { Ev.Fail(CalcError.SYNTAX, it) }
    }

    private fun find(node: Node, mode: Mode, scope: Scope): Ev.Fail? {
        val allowed = allowedModes(node)
        if ((allowed != null && mode !in allowed) || misplaced(node, scope)) return Ev.Fail(CalcError.SYNTAX, node)
        val f = Trees.integrand(node)
        for (child in Trees.children(node)) {
            find(child, mode, childScope(scope, f, child))?.let { return it }
        }
        return null
    }

    private fun childScope(scope: Scope, integrand: Node?, child: Node): Scope = when {
        integrand != null && child === integrand -> Scope.INTEGRAND
        integrand != null -> if (scope == Scope.INTEGRAND) Scope.INTEGRAND else Scope.CALCULUS_LIMIT
        scope == Scope.TOP -> Scope.NESTED
        else -> scope
    }

    private fun misplaced(node: Node, scope: Scope): Boolean = when {
        isCalculus(node) -> scope == Scope.CALCULUS_LIMIT || scope == Scope.INTEGRAND
        isCoordinates(node) -> scope != Scope.TOP
        isRemainder(node) -> scope == Scope.INTEGRAND
        node is Equation || node is Statements -> scope != Scope.TOP
        else -> false
    }

    private fun isCalculus(node: Node): Boolean = node is Integral || node is Derivative || node is Summation

    private fun isCoordinates(node: Node): Boolean = node is Call && (node.fn == Fn.POL || node.fn == Fn.REC)

    private fun isRemainder(node: Node): Boolean = node is Binary && node.op == BinOp.DIV_R

    private fun allowedModes(node: Node): Set<Mode>? = when (node) {
        is Sym -> SYMBOL_MODES[node.symbol]
        is Call -> FUNCTION_MODES[node.fn]
        is Binary -> OPERATOR_MODES[node.op]
        is Postfix -> POSTFIX_MODES[node.op]
        is StatVar -> STAT
        is MatrixRef -> MATRIX
        is VectorRef -> VECTOR
        is Integral, is Derivative, is Summation -> COMP
        is BaseLit -> BASE_N
        is Convert -> CONVERSION_MODES
        is Const -> NOT_BASE_N
        is Equation -> CALC_MODES
        else -> null
    }

    private val COMP = setOf(Mode.COMP)
    private val CMPLX = setOf(Mode.CMPLX)
    private val STAT = setOf(Mode.STAT)
    private val BASE_N = setOf(Mode.BASE_N)
    private val MATRIX = setOf(Mode.MATRIX)
    private val VECTOR = setOf(Mode.VECTOR)
    private val NOT_BASE_N = Mode.entries.toSet() - Mode.BASE_N
    private val CONVERSION_MODES = NOT_BASE_N - Mode.TABLE
    private val CALC_MODES = setOf(Mode.COMP, Mode.CMPLX)
    private val COORDINATE_MODES = setOf(Mode.COMP, Mode.STAT, Mode.MATRIX, Mode.VECTOR)

    private val SYMBOL_MODES: Map<Symbolic, Set<Mode>> = mapOf(Symbolic.I to CMPLX, Symbolic.PRE_ANS to COMP)

    private val FUNCTION_MODES: Map<Fn, Set<Mode>> = mapOf(
        Fn.ARG to CMPLX, Fn.CONJG to CMPLX,
        Fn.NOT to BASE_N, Fn.NEG to BASE_N,
        Fn.DET to MATRIX, Fn.TRN to MATRIX, Fn.REF to MATRIX, Fn.RREF to MATRIX,
        Fn.DIST_P to STAT, Fn.DIST_Q to STAT, Fn.DIST_R to STAT,
        Fn.POL to COORDINATE_MODES, Fn.REC to COORDINATE_MODES,
    )

    private val OPERATOR_MODES: Map<BinOp, Set<Mode>> = mapOf(
        BinOp.ANGLE to CMPLX,
        BinOp.DOT to VECTOR,
        BinOp.DIV_R to COMP,
        BinOp.AND to BASE_N,
        BinOp.OR to BASE_N,
        BinOp.XOR to BASE_N,
        BinOp.XNOR to BASE_N,
    )

    private val POSTFIX_MODES: Map<PostfixOp, Set<Mode>> = mapOf(
        PostfixOp.TO_POLAR to CMPLX,
        PostfixOp.TO_RECT to CMPLX,
        PostfixOp.TO_T to STAT,
        PostfixOp.EST_X to STAT,
        PostfixOp.EST_X1 to STAT,
        PostfixOp.EST_X2 to STAT,
        PostfixOp.EST_Y to STAT,
    )
}
