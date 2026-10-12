package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.num.Dec

/**
 * Everything an evaluation can produce. The real calculator's records (research 07 §4.1) are a
 * float, an exact fraction, a two-term surd, a complex pair, and — in their own modes — matrices
 * and vectors; this mirrors them.
 */
sealed interface Value

/**
 * A real number as the calculator holds it. [dec] is always available: it is the value the
 * calculator falls back to the moment exactness is lost (docs/CALCULATOR.md §3).
 */
sealed interface Real : Value {
    val dec: Dec
}

/**
 * Inexact: a 15-digit decimal (record type 0). [dms] marks a value the calculator shows in
 * degrees-minutes-seconds — the type-4 record, produced by `°'"` input and by DMS arithmetic
 * (research 02 §8.9).
 */
data class DecimalReal(override val dec: Dec, val dms: Boolean = false) : Real

/**
 * Exact rational [num]/[den] in lowest terms with `den > 0` (record type 2). Integers are
 * `den == 1`. Held only while `digits(num) + digits(den) <= 14` — the record's 15 nibbles less
 * the separator (docs/CALCULATOR.md §3); a result past that is a [DecimalReal].
 */
data class FractionReal(val num: Long, val den: Long) : Real {
    init {
        require(den > 0) { "a fraction's denominator is positive" }
    }

    override val dec: Dec = Dec.of(num).div(Dec.of(den))
}

/**
 * One term of a surd: ([num]/[den])·√[radicand]. [radicand] is squarefree; 1 means a rational
 * term. Limits per term are the record's (research 07 §4.1): |num| < 100, den < 100,
 * radicand < 1000.
 */
data class SurdTerm(val num: Long, val den: Long, val radicand: Long)

/**
 * Exact sum of one or two [SurdTerm]s, at least one with radicand > 1 (record type 8). Anything
 * that would need a third term or break a term's limits is a [DecimalReal] instead — at the step
 * where it happened, so it never comes back (research 02 §8.6, docs/CALCULATOR.md §3).
 */
data class SurdReal(val terms: List<SurdTerm>, override val dec: Dec) : Real {
    init {
        require(terms.size in 1..2) { "a surd has one or two terms" }
    }
}

/** CMPLX mode's numbers. Each part keeps its own exactness (`1/2+1/2i`, research 05 EX-CMPLX-04). */
data class ComplexValue(val re: Real, val im: Real) : Value

/** MATRIX mode: up to 3×3, row-major [cells] (research 03 §7). */
data class MatrixValue(val rows: Int, val cols: Int, val cells: List<Real>) : Value {
    init {
        require(cells.size == rows * cols) { "a $rows×$cols matrix has ${rows * cols} cells" }
    }

    operator fun get(row: Int, col: Int): Real = cells[row * cols + col]
}

/** VECTOR mode: two or three dimensions (research 03 §9). */
data class VectorValue(val cells: List<Real>) : Value
