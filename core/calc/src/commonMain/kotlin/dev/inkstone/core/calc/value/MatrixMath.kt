package dev.inkstone.core.calc.value

/**
 * MATRIX mode's operations on matrices of up to 3×3 (research 03 §7, 06 §6.7). Cells are [Real]s
 * and every cell operation is [RealArith]'s, so a matrix of integers stays exact through `det`,
 * the inverse and `Ref`: `MatA⁻¹` of `[[2,1],[1,1]]` is `[[1,−1],[−1,2]]` (05 EX-MAT-07).
 *
 * Errors (research 03 §7.4, 02 §11): sizes that do not fit the operation are Dimension ERROR;
 * inverting a singular matrix is Math ERROR (INFERRED — the guide does not say, and no other
 * error fits); a cell outside ±1×10¹⁰⁰ is Math ERROR. A product of matrices of at most 3×3 is at
 * most 3×3, so no result can outgrow the mode.
 */
object MatrixMath {
    /** `MatA + MatB`: the same size or Dimension ERROR (05 EX-MAT-02). */
    fun add(a: MatrixValue, b: MatrixValue): Res<MatrixValue> = cellwise(a, b) { x, y -> RealArith.add(x, y) }

    fun subtract(a: MatrixValue, b: MatrixValue): Res<MatrixValue> = cellwise(a, b) { x, y -> RealArith.subtract(x, y) }

    /** `MatA × MatB`: a's columns must match b's rows (05 EX-MAT-01). */
    fun multiply(a: MatrixValue, b: MatrixValue): Res<MatrixValue> {
        if (a.cols != b.rows) return Res.DIMENSION
        val cells = ArrayList<Real>(a.rows * b.cols)
        for (i in 0 until a.rows) {
            for (j in 0 until b.cols) cells += rowTimesColumn(a, i, b, j).valueOr { return it }
        }
        return Res.Ok(MatrixValue(a.rows, b.cols, cells))
    }

    /** `Σₖ a[i,k]·b[k,j]`, in order from k = 0. */
    private fun rowTimesColumn(a: MatrixValue, i: Int, b: MatrixValue, j: Int): Res<Real> {
        var sum = Reals.ZERO
        for (k in 0 until a.cols) {
            val term = RealArith.multiply(a[i, k], b[k, j]).valueOr { return it }
            sum = RealArith.add(sum, term).valueOr { return it }
        }
        return Res.Ok(sum)
    }

    /** `k × MatA` (and `MatA × k`): every cell scaled (05 EX-MAT-04). */
    fun scale(k: Real, m: MatrixValue): Res<MatrixValue> = map(m) { RealArith.multiply(k, it) }

    /** `MatA ÷ k`; Math ERROR for `k = 0`. */
    fun divide(m: MatrixValue, k: Real): Res<MatrixValue> =
        if (k.dec.isZero) Res.MATH else map(m) { RealArith.divide(it, k) }

    fun negate(m: MatrixValue): MatrixValue = MatrixValue(m.rows, m.cols, m.cells.map(RealArith::negate))

    /** `Trn(MatC)`: rows become columns (05 EX-MAT-06). */
    fun transpose(m: MatrixValue): MatrixValue =
        MatrixValue(m.cols, m.rows, List(m.rows * m.cols) { index -> m[index % m.rows, index / m.rows] })

    /** `Abs(MatB)`: element by element (05 EX-MAT-08). */
    fun abs(m: MatrixValue): MatrixValue = MatrixValue(m.rows, m.cols, m.cells.map(RealArith::abs))

    /** `det(MatA)` of a square matrix, by cofactors; a non-square one is Dimension ERROR (05 EX-MAT-05). */
    fun determinant(m: MatrixValue): Res<Real> {
        if (m.rows != m.cols) return Res.DIMENSION
        if (m.rows == 1) return Res.Ok(m[0, 0])
        var sum: Res<Real> = Res.Ok(Reals.ZERO)
        for (j in 0 until m.cols) {
            val term = cofactor(m, 0, j).then { RealArith.multiply(m[0, j], it) }
            sum = sum.zip(term, RealArith::add)
        }
        return sum
    }

    /**
     * `MatA⁻¹` — only through `x⁻¹`, the guide's one way to invert (research 03 §7.1). The
     * adjugate over the determinant, so integer matrices with determinant ±1 invert to integers.
     */
    fun inverse(m: MatrixValue): Res<MatrixValue> = determinant(m).then { det ->
        when {
            det.dec.isZero -> Res.MATH
            m.rows == 1 -> RealArith.reciprocal(m[0, 0]).map { MatrixValue(1, 1, listOf(it)) }
            else -> adjugateOver(m, det)
        }
    }

    /** The adjugate (transposed cofactors) divided by [det]. */
    private fun adjugateOver(m: MatrixValue, det: Real): Res<MatrixValue> {
        val cells = ArrayList<Real>(m.rows * m.cols)
        for (i in 0 until m.rows) {
            for (j in 0 until m.cols) cells += cofactorOver(m, j, i, det).valueOr { return it }
        }
        return Res.Ok(MatrixValue(m.rows, m.cols, cells))
    }

    /** `MatA²`: square matrices only (05 EX-MAT-09). */
    fun square(m: MatrixValue): Res<MatrixValue> = if (m.rows != m.cols) Res.DIMENSION else multiply(m, m)

    /** `MatA³` (05 EX-MAT-09). */
    fun cube(m: MatrixValue): Res<MatrixValue> = square(m).then { multiply(it, m) }

    /** One cell of the inverse: a cofactor over the determinant. */
    private fun cofactorOver(m: MatrixValue, row: Int, col: Int, det: Real): Res<Real> =
        cofactor(m, row, col).then { RealArith.divide(it, det) }

    /** `(−1)^(i+j) · det(m without row i and column j)`. */
    private fun cofactor(m: MatrixValue, row: Int, col: Int): Res<Real> {
        val minorCells = ArrayList<Real>((m.rows - 1) * (m.cols - 1))
        for (i in 0 until m.rows) {
            if (i == row) continue
            for (j in 0 until m.cols) if (j != col) minorCells += m[i, j]
        }
        val minor = determinant(MatrixValue(m.rows - 1, m.cols - 1, minorCells))
        return if ((row + col) % 2 == 0) minor else minor.map(RealArith::negate)
    }

    private inline fun map(m: MatrixValue, f: (Real) -> Res<Real>): Res<MatrixValue> {
        val cells = m.cells.map { f(it).valueOr { e -> return e } }
        return Res.Ok(MatrixValue(m.rows, m.cols, cells))
    }

    private inline fun cellwise(a: MatrixValue, b: MatrixValue, f: (Real, Real) -> Res<Real>): Res<MatrixValue> {
        if (a.rows != b.rows || a.cols != b.cols) return Res.DIMENSION
        val cells = a.cells.indices.map { f(a.cells[it], b.cells[it]).valueOr { e -> return e } }
        return Res.Ok(MatrixValue(a.rows, a.cols, cells))
    }
}
