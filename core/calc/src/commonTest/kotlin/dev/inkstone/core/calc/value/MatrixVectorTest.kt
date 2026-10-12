package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.AngleUnit.DEG
import dev.inkstone.core.calc.NumberFormat
import kotlin.test.Test

/**
 * MATRIX and VECTOR at value level: research 05's EX-MAT-01…11 and EX-VEC-01…07 with the guide's
 * own shared state, then the errors of research 03 §7.4. A matrix is written row by row,
 * `a b | c d`; a vector as its cells.
 */
class MatrixVectorTest {
    private fun m(rows: Int, cols: Int, vararg cells: Long) = MatrixValue(rows, cols, cells.map { int(it) })

    private fun v(vararg cells: Long) = VectorValue(cells.map { int(it) })

    private fun mat(r: Res<MatrixValue>): String = when (r) {
        is Res.Err -> r.error.name
        is Res.Ok -> showMatrix(r.value)
    }

    private fun vec(r: Res<VectorValue>): String = when (r) {
        is Res.Err -> r.error.name
        is Res.Ok -> showVector(r.value)
    }

    private val matA = m(2, 2, 2, 1, 1, 1)
    private val matB = m(2, 2, 2, -1, -1, 2)
    private val matC = m(2, 3, 1, 0, -1, 0, -1, 1)
    private val nine = m(3, 3, 1, 2, 3, 4, 5, 6, 7, 8, 9)
    private val unimodular = m(3, 3, 1, 2, 3, 0, 1, 4, 5, 6, 0)

    private val vctA = v(1, 2)
    private val vctB = v(3, 4)
    private val vctC = v(2, -1, 2)

    /** (A•B)/(|A||B|), as EX-VEC-07 types it. */
    private fun cosineAB(): Real =
        VectorMath.dot(vctA, vctB).ok() / (VectorMath.magnitude(vctA).ok() * VectorMath.magnitude(vctB).ok())

    private val matrices = listOf(
        Row("EX-MAT-01 MatA times MatB", { mat(MatrixMath.multiply(matA, matB)) }, "3 0 | 1 1"),
        Row("EX-MAT-02 MatA plus MatB", { mat(MatrixMath.add(matA, matB)) }, "4 0 | 0 3"),
        Row("EX-MAT-04 3 times MatA", { mat(MatrixMath.scale(int(3), matA)) }, "6 3 | 3 3"),
        Row("EX-MAT-05 det MatA", { show(MatrixMath.determinant(matA)) }, "1"),
        Row("EX-MAT-06 Trn MatC", { showMatrix(MatrixMath.transpose(matC)) }, "1 0 | 0 -1 | -1 1"),
        Row("EX-MAT-07 MatA inverse", { mat(MatrixMath.inverse(matA)) }, "1 -1 | -1 2"),
        Row("EX-MAT-08 Abs MatB", { showMatrix(MatrixMath.abs(matB)) }, "2 1 | 1 2"),
        Row("EX-MAT-09 MatA squared", { mat(MatrixMath.square(matA)) }, "5 3 | 3 2"),
        Row("EX-MAT-09 MatA cubed", { mat(MatrixMath.cube(matA)) }, "13 8 | 8 5"),
        Row("EX-MAT-10 Ref", { mat(RowReduction.ref(nine)) }, "1 8/7 9/7 | 0 1 2 | 0 0 0"),
        Row("EX-MAT-11 Rref", { mat(RowReduction.rref(nine)) }, "1 0 -1 | 0 1 2 | 0 0 0"),
        Row("Rref of an invertible matrix is the identity", { mat(RowReduction.rref(matA)) }, "1 0 | 0 1"),
        Row("Ref skips an empty column", { mat(RowReduction.ref(m(2, 3, 0, 2, 4, 0, 1, 1))) }, "0 1 2 | 0 0 1"),
        Row("det of nine", { show(MatrixMath.determinant(nine)) }, "0"),
        Row("det of a 3 by 3", { show(MatrixMath.determinant(unimodular)) }, "1"),
        Row("inverse of a 3 by 3", { mat(MatrixMath.inverse(unimodular)) }, "-24 18 5 | 20 -15 -4 | -5 4 1"),
        Row("inverse of a fraction matrix", { mat(MatrixMath.inverse(m(2, 2, 1, 2, 3, 4))) }, "-2 1 | 3/2 -1/2"),
        Row("1 by 1 inverse", { mat(MatrixMath.inverse(m(1, 1, 4))) }, "1/4"),
        Row("MatC times Trn MatC", { mat(MatrixMath.multiply(matC, MatrixMath.transpose(matC))) }, "2 -1 | -1 2"),
        Row("MatA over 2", { mat(MatrixMath.divide(matA, int(2))) }, "1 1/2 | 1/2 1/2"),
    )

    private val matrixErrors = listOf(
        Row("adding different sizes", { mat(MatrixMath.add(matA, matC)) }, "DIMENSION"),
        Row("MatC times MatC", { mat(MatrixMath.multiply(matC, matC)) }, "DIMENSION"),
        Row("det of MatC", { show(MatrixMath.determinant(matC)) }, "DIMENSION"),
        Row("inverse of MatC", { mat(MatrixMath.inverse(matC)) }, "DIMENSION"),
        Row("MatC squared", { mat(MatrixMath.square(matC)) }, "DIMENSION"),
        Row("singular inverse", { mat(MatrixMath.inverse(nine)) }, "MATH"),
        Row("divide by zero", { mat(MatrixMath.divide(matA, int(0))) }, "MATH"),
        Row("a cell overflows", { mat(MatrixMath.scale(dec("5E99"), matA)) }, "MATH"),
    )

    private val vectors = listOf(
        Row("EX-VEC-01 VctA plus VctB", { vec(VectorMath.add(vctA, vctB)) }, "4 6"),
        Row("EX-VEC-03 3 times VctA", { vec(VectorMath.scale(int(3), vctA)) }, "3 6"),
        Row(
            "EX-VEC-03 then minus VctB",
            { vec(VectorMath.subtract(VectorMath.scale(int(3), vctA).ok(), vctB)) },
            "0 2",
        ),
        Row("EX-VEC-04 dot", { show(VectorMath.dot(vctA, vctB)) }, "11"),
        Row("EX-VEC-05 cross of two 2D vectors", { vec(VectorMath.cross(vctA, vctB)) }, "0 0 -2"),
        Row("EX-VEC-06 Abs VctC", { show(VectorMath.magnitude(vctC)) }, "3"),
        Row("EX-VEC-07 cosine of the angle", { show(cosineAB()) }, "11√5/25"),
        Row(
            "EX-VEC-07 the cosine shown in Fix 3",
            { Coordinates.rnd(cosineAB(), NumberFormat.Fix(3)).ok().dec.toString() },
            "0.984",
        ),
        Row("EX-VEC-07 the angle", { show(VectorMath.angle(vctA, vctB, DEG)) }, "~10.30484647"),
        Row("cross of 3D vectors", { vec(VectorMath.cross(v(1, 0, 0), v(0, 1, 0))) }, "0 0 1"),
        Row("cross of VctC with itself", { vec(VectorMath.cross(vctC, vctC)) }, "0 0 0"),
        Row("magnitude of VctA", { show(VectorMath.magnitude(vctA)) }, "√5"),
        Row("right angle", { show(VectorMath.angle(v(1, 0), v(0, 1), DEG)) }, "90"),
        Row("VctB over 2", { vec(VectorMath.divide(vctB, int(2))) }, "3/2 2"),
        Row("negate", { showVector(VectorMath.negate(vctC)) }, "-2 1 -2"),
    )

    private val vectorErrors = listOf(
        Row("adding 2D and 3D", { vec(VectorMath.add(vctA, vctC)) }, "DIMENSION"),
        Row("dot of 2D and 3D", { show(VectorMath.dot(vctA, vctC)) }, "DIMENSION"),
        Row("cross of 2D and 3D", { vec(VectorMath.cross(vctA, vctC)) }, "DIMENSION"),
        Row("angle with a zero vector", { show(VectorMath.angle(vctA, v(0, 0), DEG)) }, "MATH"),
        Row("divide by zero", { vec(VectorMath.divide(vctA, int(0))) }, "MATH"),
    )

    @Test
    fun `matrix examples from the guide hold at value level`() = checkRows(matrices)

    @Test
    fun `matrix sizes that do not fit are dimension errors and singular is math`() = checkRows(matrixErrors)

    @Test
    fun `vector examples from the guide hold at value level`() = checkRows(vectors)

    @Test
    fun `vector sizes that do not fit are dimension errors`() = checkRows(vectorErrors)
}
