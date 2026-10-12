package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.parse.BinOp
import dev.inkstone.core.calc.parse.Binary
import dev.inkstone.core.calc.parse.Negate
import dev.inkstone.core.calc.value.ComplexMath
import dev.inkstone.core.calc.value.ComplexValue
import dev.inkstone.core.calc.value.Division
import dev.inkstone.core.calc.value.IntegerFunctions
import dev.inkstone.core.calc.value.MatrixMath
import dev.inkstone.core.calc.value.MatrixValue
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.RealArith
import dev.inkstone.core.calc.value.Res
import dev.inkstone.core.calc.value.Value
import dev.inkstone.core.calc.value.VectorMath
import dev.inkstone.core.calc.value.VectorValue

/** `(−)x` on anything that has a negative. */
internal fun Session.negate(node: Negate): Ev<Value> = eval(node.operand).map { value ->
    when (value) {
        is Real -> RealArith.negate(value)
        is ComplexValue -> ComplexMath.negate(value)
        is MatrixValue -> MatrixMath.negate(value)
        is VectorValue -> VectorMath.negate(value)
    }
}

/**
 * An infix operation. The left operand waits on its stack while the right one is computed
 * ([Session.hold]). `÷R` inside a longer expression passes on only its quotient: `10+17÷R6` is
 * `10+2` (research 02 §10.12, 04 C-22); a `÷R` that is the whole statement is answered by
 * [Session.statement] with its remainder too.
 */
internal fun Session.binary(node: Binary): Ev<Value> {
    if (node.op == BinOp.DIV_R) return remainderDivision(node).map { it.quotient }
    val left = eval(node.left).orElse { return it }
    hold(left)
    val right = eval(node.right)
    release(left)
    return combine(node, left, right.orElse { return it })
}

private fun Session.combine(node: Binary, a: Value, b: Value): Ev<Value> = when (node.op) {
    BinOp.ADD, BinOp.SUB, BinOp.MUL, BinOp.IMPLICIT_MUL, BinOp.DIV -> arithmetic(node, a, b)

    BinOp.NPR, BinOp.NCR -> choose(node, a, b)

    BinOp.ANGLE -> polarEntry(node, a, b)

    BinOp.DOT -> dot(node, a, b)

    // Logic is BASE-N's (BaseN), and a ÷R never reaches here.
    BinOp.DIV_R, BinOp.AND, BinOp.OR, BinOp.XOR, BinOp.XNOR -> failAt(CalcError.SYNTAX, node)
}

/**
 * `+ − × ÷` and the omitted `×`, on whatever pair of shapes the modes allow: numbers (real or
 * complex), and in MATRIX / VECTOR the combinations of research 03 §7.1 and §9.2. A pair of shapes
 * that has no meaning (`MatA+3`, `VctA÷MatA`) is Syntax ERROR — a **GUESS**, the guide names no
 * error for it; sizes that do not fit are Dimension ERROR (the value package's).
 *
 * Only `÷` goes through [Session.lineQuotient]: in linear output a division of whole numbers is a
 * decimal (research 02 §8.5, `1÷5` = `0,2`), while `+ − ×` keep a typed fraction exact
 * (`2⌟3+1⌟2` = `7⌟6`).
 */
private fun Session.arithmetic(node: Binary, a: Value, b: Value): Ev<Value> {
    val result = when {
        a is MatrixValue || b is MatrixValue -> Shapes.matrix(node.op, a, b)
        a is VectorValue || b is VectorValue -> Shapes.vector(node.op, a, b)
        a is ComplexValue || b is ComplexValue -> complexArithmetic(node.op, complexOf(a), complexOf(b))
        a is Real && b is Real -> realArithmetic(node.op, a, b)
        else -> null
    } ?: return failAt(CalcError.SYNTAX, node)
    val value = settle(result.at(node).orElse { return it })
    return Ev.Ok(if (node.op == BinOp.DIV) lineQuotient(value, a, b) else value)
}

private fun realArithmetic(op: BinOp, a: Real, b: Real): Res<Real> = when (op) {
    BinOp.ADD -> RealArith.add(a, b)
    BinOp.SUB -> RealArith.subtract(a, b)
    BinOp.DIV -> RealArith.divide(a, b)
    else -> RealArith.multiply(a, b)
}

private fun complexArithmetic(op: BinOp, a: ComplexValue, b: ComplexValue): Res<ComplexValue> = when (op) {
    BinOp.ADD -> ComplexMath.add(a, b)
    BinOp.SUB -> ComplexMath.subtract(a, b)
    BinOp.DIV -> ComplexMath.divide(a, b)
    else -> ComplexMath.multiply(a, b)
}

/** A number as a complex one; only ever called on a real or a complex value. */
internal fun complexOf(value: Value): ComplexValue = when (value) {
    is ComplexValue -> value
    is Real -> ComplexMath.of(value)
    is MatrixValue, is VectorValue -> error("complexOf is only called on numbers")
}

/** `nPr` / `nCr` (research 02 §10.11): integers by value, range checks in [IntegerFunctions]. */
private fun Session.choose(node: Binary, a: Value, b: Value): Ev<Value> {
    val n = realOf(a, node.left).orElse { return it }
    val r = realOf(b, node.right).orElse { return it }
    val result = if (node.op == BinOp.NPR) IntegerFunctions.permutations(n, r) else IntegerFunctions.combinations(n, r)
    return result.at(node)
}

/**
 * CMPLX's `r∠θ` (research 03 §3): θ in the current unit, `2∠45` = `√2+√2i` (05 EX-CMPLX-02).
 * Both parts must be real; a complex r or θ is Math ERROR (a **GUESS**).
 */
private fun Session.polarEntry(node: Binary, a: Value, b: Value): Ev<Value> {
    val r = realOf(a, node.left).orElse { return it }
    val theta = realOf(b, node.right).orElse { return it }
    return ComplexMath.polar(r, theta, unit).at(node).map { settle(line(it)) }
}

/** `VctA•VctB` (research 03 §9.2). Anything but two vectors is Syntax ERROR (a **GUESS**). */
private fun dot(node: Binary, a: Value, b: Value): Ev<Value> {
    if (a !is VectorValue || b !is VectorValue) return failAt(CalcError.SYNTAX, node)
    return VectorMath.dot(a, b).at(node)
}

/**
 * `a ÷R b` (research 02 §10.12): quotient and remainder, or, outside ÷R's range, an ordinary
 * division — which in linear output is then a decimal like any other `÷` ([Session.line]).
 * The calculator's fallback rules are [IntegerFunctions.divideWithRemainder]'s.
 */
internal fun Session.remainderDivision(node: Binary): Ev<Division> {
    val a = real(node.left).orElse { return it }
    val b = real(node.right).orElse { return it }
    return IntegerFunctions.divideWithRemainder(a, b).at(node).map { division ->
        when (division) {
            is Division.WithRemainder -> division
            is Division.Ordinary -> Division.Ordinary(lineReal(division.quotient))
        }
    }
}
