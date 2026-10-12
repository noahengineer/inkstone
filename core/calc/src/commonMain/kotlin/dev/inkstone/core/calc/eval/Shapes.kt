package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.parse.BinOp
import dev.inkstone.core.calc.value.MatrixMath
import dev.inkstone.core.calc.value.MatrixValue
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.Res
import dev.inkstone.core.calc.value.Value
import dev.inkstone.core.calc.value.VectorMath
import dev.inkstone.core.calc.value.VectorValue

/**
 * `+ − × ÷` where a matrix or a vector takes part (research 03 §7.1, §9.2, 06 §6.7, §6.9). Null
 * means the pair has no meaning — the caller's Syntax ERROR.
 *
 * - matrices: `A±B`, `A×B`, `k×A`, `A×k`, `A÷k` (÷ is one of the MatAns keys, so scalar division
 *   is INFERRED, 06 §6.7);
 * - vectors: `A±B`, `A×B` is the cross product (`(1,2)×(3,4) = (0,0,−2)`, 05 EX-VEC-05),
 *   `k×A`, `A×k`, `A÷k`.
 */
internal object Shapes {
    fun matrix(op: BinOp, a: Value, b: Value): Res<Value>? = when {
        a is MatrixValue && b is MatrixValue -> matrixPair(op, a, b)

        a is MatrixValue && b is Real -> when {
            isProduct(op) -> MatrixMath.scale(b, a)
            op == BinOp.DIV -> MatrixMath.divide(a, b)
            else -> null
        }

        a is Real && b is MatrixValue && isProduct(op) -> MatrixMath.scale(a, b)

        else -> null
    }

    private fun matrixPair(op: BinOp, a: MatrixValue, b: MatrixValue): Res<Value>? = when {
        op == BinOp.ADD -> MatrixMath.add(a, b)
        op == BinOp.SUB -> MatrixMath.subtract(a, b)
        isProduct(op) -> MatrixMath.multiply(a, b)
        else -> null
    }

    fun vector(op: BinOp, a: Value, b: Value): Res<Value>? = when {
        a is VectorValue && b is VectorValue -> vectorPair(op, a, b)

        a is VectorValue && b is Real -> when {
            isProduct(op) -> VectorMath.scale(b, a)
            op == BinOp.DIV -> VectorMath.divide(a, b)
            else -> null
        }

        a is Real && b is VectorValue && isProduct(op) -> VectorMath.scale(a, b)

        else -> null
    }

    private fun vectorPair(op: BinOp, a: VectorValue, b: VectorValue): Res<Value>? = when {
        op == BinOp.ADD -> VectorMath.add(a, b)
        op == BinOp.SUB -> VectorMath.subtract(a, b)
        isProduct(op) -> VectorMath.cross(a, b)
        else -> null
    }

    private fun isProduct(op: BinOp): Boolean = op == BinOp.MUL || op == BinOp.IMPLICIT_MUL
}
