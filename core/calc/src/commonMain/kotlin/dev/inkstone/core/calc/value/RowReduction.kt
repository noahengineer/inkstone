package dev.inkstone.core.calc.value

/**
 * `Ref` and `Rref` (research 03 §7.3, 05 EX-MAT-10/11): Gaussian elimination with **partial
 * pivoting** — in each column, the row with the largest |value| at or below the current one
 * becomes the pivot row, and is divided through so the pivot is 1. That is INFERRED from the one
 * capture: `Ref([[1,2,3],[4,5,6],[7,8,9]])` begins with `(7,8,9)/7`, which only pivoting
 * produces, and partial pivoting reproduces all three rows. Ties keep the higher row (the usual
 * strict comparison, a **GUESS**). A column with no non-zero entry left is skipped.
 *
 * `Rref` is `Ref` followed by clearing every pivot's column above it, bottom pivot first. In exact
 * arithmetic that is the same matrix any order of elimination gives; with decimals it decides the
 * last digit, and the firmware's order is UNKNOWN.
 *
 * Zero is tested exactly — a decimal residue of 10⁻¹⁵ is a pivot, as it would be on the real
 * calculator unless its firmware has a tolerance nobody has documented.
 */
object RowReduction {
    fun ref(m: MatrixValue): Res<MatrixValue> = echelon(m).map { it.toMatrix(m) }

    fun rref(m: MatrixValue): Res<MatrixValue> {
        val rows = echelon(m).valueOr { return it }
        for (r in rows.indices.reversed()) {
            val pivotColumn = rows[r].indexOfFirst { !it.dec.isZero }
            if (pivotColumn < 0) continue
            for (i in 0 until r) {
                rows[i] = eliminate(rows[i], rows[r], pivotColumn).valueOr { return it }
            }
        }
        return Res.Ok(rows.toMatrix(m))
    }

    private fun echelon(m: MatrixValue): Res<MutableList<List<Real>>> {
        val rows = MutableList(m.rows) { r -> List(m.cols) { c -> m[r, c] } }
        var lead = 0
        for (c in 0 until m.cols) {
            val pivot = (if (lead < rows.size) pivotRow(rows, lead, c) else null) ?: continue
            val swapped = rows[pivot]
            rows[pivot] = rows[lead]
            rows[lead] = normalise(swapped, c).valueOr { return it }
            for (i in lead + 1 until rows.size) {
                rows[i] = eliminate(rows[i], rows[lead], c).valueOr { return it }
            }
            lead++
        }
        return Res.Ok(rows)
    }

    /** The row at or below [from] with the largest |value| in column [c], or null if all are 0. */
    private fun pivotRow(rows: List<List<Real>>, from: Int, c: Int): Int? {
        var best: Int? = null
        for (i in from until rows.size) {
            val v = rows[i][c].dec.abs()
            if (!v.isZero && (best == null || v > rows[best][c].dec.abs())) best = i
        }
        return best
    }

    /** [row] divided by its entry in column [c], with that entry set to exactly 1. */
    private fun normalise(row: List<Real>, c: Int): Res<List<Real>> {
        val pivot = row[c]
        val out = row.mapIndexed { j, x ->
            when {
                j < c -> x
                j == c -> Reals.ONE
                else -> RealArith.divide(x, pivot).valueOr { return it }
            }
        }
        return Res.Ok(out)
    }

    /** [target] − f·[source], f being [target]'s entry in column [c], which becomes exactly 0. */
    private fun eliminate(target: List<Real>, source: List<Real>, c: Int): Res<List<Real>> {
        val f = target[c]
        if (f.dec.isZero) return Res.Ok(target)
        val out = target.mapIndexed { j, x ->
            when {
                j < c -> x

                j == c -> Reals.ZERO

                else -> {
                    val scaled = RealArith.multiply(f, source[j]).valueOr { return it }
                    RealArith.subtract(x, scaled).valueOr { return it }
                }
            }
        }
        return Res.Ok(out)
    }

    private fun List<List<Real>>.toMatrix(shape: MatrixValue): MatrixValue =
        MatrixValue(shape.rows, shape.cols, flatten())
}
