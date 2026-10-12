package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.lcd.Indicator
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.FractionReal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** STO, RCL, M+ and M− (research 02 §9; vectors D-04, D-05 and C-21 of research 04). */
class MemoryKeysTest {
    private fun Session.variable(v: Variable) = machine.memory.variables[v]

    @Test
    fun stoEvaluatesTheLineAndStoresTheResult() {
        val s = Session().press("3 + 5 STO")
        assertTrue(Indicator.STO in s.icons)
        assertEquals("3+5", s.text)
        s.press("(−)")
        assertFalse(Indicator.STO in s.icons)
        assertEquals(listOf("3+5", "8"), s.lines)
        assertEquals(FractionReal(8, 1), s.variable(Variable.A))
        assertEquals(FractionReal(8, 1), s.machine.memory.ans)
    }

    @Test
    fun variablesAreUsedRecalledAndCleared() {
        val s = Session().press("3 + 5 STO (−)")
        s.press("A × 10 =")
        assertEquals(listOf("A×10", "80"), s.lines)
        s.press("RCL")
        assertTrue(Indicator.RCL in s.icons)
        s.press("(−)")
        assertEquals(listOf("A", "8"), s.lines)
        assertEquals(FractionReal(8, 1), s.machine.memory.ans)
        s.press("0 STO (−)")
        assertEquals(FractionReal(0, 1), s.variable(Variable.A))
    }

    @Test
    fun stoTakesTheVariableWithAlphaLatchedToo() {
        val s = Session().press("6 STO ALPHA S_TO_D")
        assertEquals(FractionReal(6, 1), s.variable(Variable.Y))
    }

    @Test
    fun stoOfAResultOnScreenStoresIt() {
        val s = Session().press("7 × 3 = STO )")
        assertEquals(FractionReal(21, 1), s.variable(Variable.X))
        assertEquals(listOf("7×3", "21"), s.lines)
    }

    @Test
    fun stoOfADivRResultStoresOnlyTheQuotient() {
        val s = Session().press("5 ÷R 2 STO )")
        assertEquals(FractionReal(2, 1), s.variable(Variable.X))
        s.press("RCL )")
        assertEquals(listOf("X", "2"), s.lines)
    }

    @Test
    fun stoWaitsThroughOtherKeysAndAcCancelsIt() {
        val s = Session().press("4 STO 7 +")
        assertTrue(Indicator.STO in s.icons)
        assertEquals("4", s.text)
        s.press("AC")
        assertFalse(Indicator.STO in s.icons)
        assertEquals("4", s.text)
        assertEquals(FractionReal(0, 1), s.variable(Variable.A))
    }

    @Test
    fun independentMemoryAddsAndSubtracts() {
        val s = Session().press("0 STO M+")
        assertFalse(Indicator.MEMORY in s.icons)
        s.press("10 × 5 M+")
        assertEquals(listOf("10×5", "50"), s.lines)
        assertTrue(Indicator.MEMORY in s.icons)
        assertEquals(FractionReal(50, 1), s.variable(Variable.M))
        s.press("10 + 5 M−")
        assertEquals("15", s.lines[1])
        assertEquals(FractionReal(35, 1), s.variable(Variable.M))
        s.press("RCL M+")
        assertEquals(listOf("M", "35"), s.lines)
    }

    @Test
    fun mPlusOnAResultAddsItWithoutMovingPreAns() {
        val s = Session().press("2 = 9 = M+")
        assertEquals(FractionReal(9, 1), s.variable(Variable.M))
        assertEquals(FractionReal(2, 1), s.machine.memory.preAns)
        s.press("M+")
        assertEquals(FractionReal(18, 1), s.variable(Variable.M))
    }

    @Test
    fun mPlusOnAnEmptyLineDoesNothing() {
        val s = Session().press("M+")
        assertEquals(FractionReal(0, 1), s.variable(Variable.M))
        assertEquals("", s.text)
    }

    @Test
    fun theMemoryIconFollowsM() {
        val s = Session().press("3 STO M+")
        assertTrue(Indicator.MEMORY in s.icons)
        s.press("0 STO M+")
        assertFalse(Indicator.MEMORY in s.icons)
    }
}
