package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.num.Dec
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/*
 * Building values and reading them back for the value-package tests.
 *
 * `show` is the one oracle format: exact values are written the way the guide prints them, in the
 * package's canonical term order (`45√3+10√2`, `√6/4-√2/4`, `2-√3`, `1/2`), and a decimal is `~`
 * plus its ten-significant-digit rounding — the digits Norm 2 would show — in canonical text
 * (`~3129.089165`, `~-8.898979486`). So a row can say "this is exact and equals √3+3√2" or "this
 * fell back to a decimal that displays as 5,595754113" in one string, and the two can never be
 * confused.
 */

internal fun int(n: Long): Real = Reals.of(n)

internal fun frac(num: Long, den: Long): Real = Reals.fraction(num, den).ok()

internal fun dec(text: String): Real = DecimalReal(assertNotNull(Dec.parse(text), "bad literal $text"))

internal fun rt(n: Long): Real = Roots.sqrt(int(n)).ok()

internal operator fun Real.plus(other: Real): Real = RealArith.add(this, other).ok()

internal operator fun Real.minus(other: Real): Real = RealArith.subtract(this, other).ok()

internal operator fun Real.times(other: Real): Real = RealArith.multiply(this, other).ok()

internal operator fun Real.div(other: Real): Real = RealArith.divide(this, other).ok()

internal operator fun Real.unaryMinus(): Real = RealArith.negate(this)

internal fun <T> Res<T>.ok(): T = when (this) {
    is Res.Ok -> value
    is Res.Err -> fail("expected a value, got $error")
}

internal fun Res<*>.assertError(expected: CalcError, what: String) {
    assertEquals(expected, errorOrNull, "$what: expected $expected, got $this")
}

internal fun show(x: Real): String = when (x) {
    is FractionReal -> if (x.den == 1L) "${x.num}" else "${x.num}/${x.den}"
    is SurdReal -> x.terms.mapIndexed { i, t -> term(t, first = i == 0) }.joinToString("")
    is DecimalReal -> "~" + x.dec.roundSignificant(NORM).toCanonicalString() + if (x.dms) "dms" else ""
}

internal fun show(r: Res<Real>): String = when (r) {
    is Res.Ok -> show(r.value)
    is Res.Err -> r.error.name
}

internal fun showComplex(z: ComplexValue): String = "${show(z.re)} ${show(z.im)}i"

internal fun showMatrix(m: MatrixValue): String =
    (0 until m.rows).joinToString(" | ") { i -> (0 until m.cols).joinToString(" ") { j -> show(m[i, j]) } }

internal fun showVector(v: VectorValue): String = v.cells.joinToString(" ") { show(it) }

private fun term(t: SurdTerm, first: Boolean): String {
    val sign = if (t.num < 0L) {
        "-"
    } else if (first) {
        ""
    } else {
        "+"
    }
    val magnitude = abs(t.num)
    val coefficient = if (magnitude == 1L && t.radicand != 1L) "" else "$magnitude"
    val root = if (t.radicand == 1L) "" else "√${t.radicand}"
    val denominator = if (t.den == 1L) "" else "/${t.den}"
    return sign + coefficient + root + denominator
}

private const val NORM = 10

/** One row of a table: a label (for the failure message), what was computed, what is expected. */
internal class Row(val id: String, val actual: () -> String, val expected: String)

internal fun checkRows(rows: List<Row>) {
    val failures = rows.mapNotNull { row ->
        val got = row.actual()
        if (got == row.expected) null else "${row.id}: expected ${row.expected}, got $got"
    }
    if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
}
