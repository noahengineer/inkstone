package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.AngleUnit.DEG
import dev.inkstone.core.calc.AngleUnit.GRA
import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.num.Dec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The constructors and predicates every other part of the engine leans on, the record invariants
 * (lowest terms, positive denominator, canonical surd order), and the odd corners of the other
 * files that their own tables do not reach.
 */
class RealsTest {
    @Test
    fun `fractions are reduced with the sign on the numerator`() {
        assertEquals(FractionReal(3, 2), frac(6, 4))
        assertEquals(FractionReal(-1, 2), frac(3, -6))
        assertEquals(FractionReal(0, 1), frac(0, -5))
        assertEquals(FractionReal(7, 1), int(7))
    }

    @Test
    fun `integer literals are exact up to thirteen digits`() {
        assertEquals(FractionReal(1234567890123, 1), Reals.integerOrDecimal(Dec.of(1234567890123)).ok())
        assertTrue(Reals.integerOrDecimal(Dec.of(12345678901234)).ok() is DecimalReal)
        assertTrue(Reals.integerOrDecimal(assertDec("2.5")).ok() is DecimalReal)
        Reals.decimal(assertDec("1E100")).assertError(CalcError.MATH, "1E100 as a decimal")
    }

    @Test
    fun `whole numbers are read by value whatever holds them`() {
        assertEquals(5L, Reals.toLongExact(int(5)))
        assertEquals(5L, Reals.toLongExact(int(10) / int(2)))
        assertEquals(5L, Reals.toLongExact(dec("5")))
        assertNull(Reals.toLongExact(frac(1, 2)))
        assertNull(Reals.toLongExact(rt(2)))
        assertNull(Reals.toLongExact(dec("5.5")))
    }

    @Test
    fun `exactness and exact equality`() {
        assertTrue(Reals.isExact(rt(2)))
        assertTrue(Reals.isExact(frac(1, 3)))
        assertFalse(Reals.isExact(dec("0.5")))
        assertTrue(Reals.exactEquals(int(1) / rt(2), rt(2) / int(2)))
        assertFalse(Reals.exactEquals(dec("0.5"), frac(1, 2)))
        assertEquals(-1, Reals.signum(int(1) - rt(2)))
        assertTrue(Reals.isZero(rt(2) - rt(2)))
    }

    @Test
    fun `a surd keeps the rational term first then descending radicands`() {
        val s = rt(2) + int(3) + rt(5)
        assertTrue(s is DecimalReal, "three terms are a decimal")
        val t = rt(2) + rt(7) / int(3)
        assertEquals(listOf(SurdTerm(1, 3, 7), SurdTerm(1, 1, 2)), (t as SurdReal).terms)
        val u = rt(3) + int(5)
        assertEquals(listOf(SurdTerm(5, 1, 1), SurdTerm(1, 1, 3)), (u as SurdReal).terms)
    }

    @Test
    fun `corners the tables do not reach`() {
        checkRows(
            listOf(
                Row("2 to the root 2", { show(RealArith.pow(int(2), rt(2))) }, "~2.665144143"),
                Row("tan 1 grad", { show(Trig.tan(int(1), GRA)) }, "~0.01570925532"),
                Row("sin minus 1 degree", { show(Trig.sin(int(-1), DEG)) }, "~-0.01745240644"),
                Row("percent of a decimal", { show(RealArith.percent(dec("12.5"))) }, "~0.125"),
                Row("DMS over DMS", { show(Dms.marked(int(4)).ok() / Dms.marked(int(2)).ok()) }, "~2dms"),
                Row("cross of 3D vectors", { showVector(VectorMath.cross(v3(1, 2, 3), v3(4, 5, 6)).ok()) }, "-3 6 -3"),
                Row("matrix subtract", { showMatrix(MatrixMath.subtract(m22(5, 6, 7, 8), ones).ok()) }, "4 5 | 6 7"),
                Row("matrix negate", { showMatrix(MatrixMath.negate(m22(1, -2, 3, 0))) }, "-1 2 | -3 0"),
                Row("complex of a real", { showComplex(ComplexMath.of(int(3))) }, "3 0i"),
                Row("a real complex", { "${ComplexMath.isReal(ComplexMath.of(int(3)))}" }, "true"),
            ),
        )
    }

    private val ones = MatrixValue(2, 2, List(4) { int(1) })

    private fun assertDec(text: String): Dec = dec(text).dec

    private fun v3(a: Long, b: Long, c: Long) = VectorValue(listOf(int(a), int(b), int(c)))

    private fun m22(a: Long, b: Long, c: Long, d: Long) = MatrixValue(2, 2, listOf(int(a), int(b), int(c), int(d)))
}
