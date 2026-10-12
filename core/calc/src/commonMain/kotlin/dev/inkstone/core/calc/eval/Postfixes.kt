package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.AngleUnit
import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.parse.Postfix
import dev.inkstone.core.calc.parse.PostfixOp
import dev.inkstone.core.calc.value.Angles
import dev.inkstone.core.calc.value.ComplexMath
import dev.inkstone.core.calc.value.ComplexValue
import dev.inkstone.core.calc.value.IntegerFunctions
import dev.inkstone.core.calc.value.MatrixMath
import dev.inkstone.core.calc.value.MatrixValue
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.RealArith
import dev.inkstone.core.calc.value.Res
import dev.inkstone.core.calc.value.Value
import dev.inkstone.core.calc.value.VectorValue

/** The unit each DRG▶ mark says its operand is in (research 02 §10.4). */
private val MARKED_UNITS: Map<PostfixOp, AngleUnit> =
    mapOf(PostfixOp.DEG to AngleUnit.DEG, PostfixOp.RAD to AngleUnit.RAD, PostfixOp.GRAD to AngleUnit.GRA)

/**
 * The postfix operators (research 02 §12 levels 3 and 6, 03 §3–4):
 * - `x² x³ x⁻¹` on numbers, complex numbers and matrices (`x⁻¹` is MATRIX's inverse, research 03
 *   §7.1); a vector has none of them (VctAns takes only `+ − × ÷`, 03 §9.3) — Syntax ERROR;
 * - `x!` and `%` on reals; `%` divides by 100 (02 §10.3);
 * - `°` `ʳ` `ᵍ` convert into the current unit (`(π÷2)ʳ` = 90 in Deg, 04 E-05);
 * - `▶t` and the regression estimates ask STAT ([StatisticsSource]); no answer is Math ERROR;
 * - `▶r∠θ` / `▶a+bi` only choose how a CMPLX result is shown, so the value passes through and the
 *   machine reads the format off the statement's node.
 */
internal fun Session.postfix(node: Postfix): Ev<Value> {
    val operand = eval(node.operand).orElse { return it }
    return when (node.op) {
        PostfixOp.SQUARE, PostfixOp.CUBE, PostfixOp.RECIPROCAL -> power(node, operand)

        PostfixOp.FACTORIAL -> onReal(node, operand) { IntegerFunctions.factorial(it) }

        PostfixOp.PERCENT -> onReal(node, operand) { RealArith.percent(it) }.map { lineQuotient(it, operand) }

        PostfixOp.DEG, PostfixOp.RAD, PostfixOp.GRAD -> angleMark(node, operand)

        PostfixOp.TO_T -> statistic(node, operand) { context.statistics?.normalise(it) }

        PostfixOp.EST_X, PostfixOp.EST_X1, PostfixOp.EST_X2, PostfixOp.EST_Y ->
            statistic(node, operand) { context.statistics?.estimate(node.op, it) }

        PostfixOp.TO_POLAR, PostfixOp.TO_RECT -> Ev.Ok(operand)
    }
}

private inline fun Session.onReal(node: Postfix, operand: Value, f: (Real) -> Res<Real>): Ev<Value> =
    realOf(operand, node.operand).then { f(it).at(node) }

/** An estimate or `▶t`: computed, so through [Session.lineReal] like a STAT variable (see `named`). */
private inline fun Session.statistic(node: Postfix, operand: Value, f: (Real) -> Real?): Ev<Value> {
    val x = realOf(operand, node.operand).orElse { return it }
    return f(x)?.let { Ev.Ok(lineReal(it)) } ?: failAt(CalcError.MATH, node)
}

private fun Session.angleMark(node: Postfix, operand: Value): Ev<Value> {
    val from = MARKED_UNITS[node.op] ?: return failAt(CalcError.SYNTAX, node)
    return onReal(node, operand) { Angles.convert(it, from, unit) }.map { line(it) }
}

/**
 * `x²`, `x³` are repeated multiplication and keep exactness in every display; `x⁻¹` is a
 * division ([Session.lineQuotient]): LineIO `(3⁻¹−4⁻¹)⁻¹` = `12` (research 04 E-23) either way.
 */
private fun Session.power(node: Postfix, operand: Value): Ev<Value> {
    val result = when (operand) {
        is Real -> realPower(node.op, operand)
        is ComplexValue -> complexPower(node.op, operand)
        is MatrixValue -> matrixPower(node.op, operand)
        is VectorValue -> return failAt(CalcError.SYNTAX, node)
    }
    val value = settle(result.at(node).orElse { return it })
    return Ev.Ok(if (node.op == PostfixOp.RECIPROCAL) lineQuotient(value, operand) else value)
}

private fun realPower(op: PostfixOp, x: Real): Res<Real> = when (op) {
    PostfixOp.SQUARE -> RealArith.square(x)
    PostfixOp.CUBE -> RealArith.cube(x)
    else -> RealArith.reciprocal(x)
}

private fun complexPower(op: PostfixOp, z: ComplexValue): Res<ComplexValue> = when (op) {
    PostfixOp.SQUARE -> ComplexMath.square(z)
    PostfixOp.CUBE -> ComplexMath.cube(z)
    else -> ComplexMath.reciprocal(z)
}

private fun matrixPower(op: PostfixOp, m: MatrixValue): Res<MatrixValue> = when (op) {
    PostfixOp.SQUARE -> MatrixMath.square(m)
    PostfixOp.CUBE -> MatrixMath.cube(m)
    else -> MatrixMath.inverse(m)
}
