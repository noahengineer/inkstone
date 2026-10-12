package dev.inkstone.core.calc.machine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `▶r∠θ` and `▶a+bi` "override the setup for that one result" (research 03 §3, VERIFIED;
 * [ComplexOverride]). The fake formatter draws a value in r∠θ as the value and `∠0`, so each test
 * reads which complex format the machine handed the formatter.
 */
class ComplexOverrideTest {
    /** EX-CMPLX-09 [G p.49]: `▶r∠θ` ends the line, the result is polar, the next one is a+bi again. */
    @Test
    fun polarCommandOverridesRectangularForOneResult() {
        val s = Session().press("MODE 2 2 SHIFT 2 3 =")
        assertEquals(listOf("2▶r∠θ", "2∠0"), s.lines)
        s.press("5 =")
        assertEquals(listOf("5", "5"), s.lines)
    }

    /** EX-CMPLX-10: `▶a+bi` under SETUP r∠θ. */
    @Test
    fun rectangularCommandOverridesPolarForOneResult() {
        val s = Session().press("MODE 2 SETUP ▼ 3 2 2 SHIFT 2 4 =")
        assertEquals(listOf("2▶a+bi", "2"), s.lines)
        s.press("7 =")
        assertEquals(listOf("7", "7∠0"), s.lines)
    }

    /**
     * The form belongs to the result: ▲ shows it again as `=` did, S⇔D restyles it in that form,
     * and a SETUP change redraws it without losing it.
     */
    @Test
    fun theFormStaysWithItsResult() {
        val s = Session().press("MODE 2 2 ÷ 3 SHIFT 2 3 = 4 =")
        assertEquals("4", s.lines[1])
        s.press("▲")
        assertEquals(listOf("2÷3▶r∠θ", "2/3∠0"), s.lines)
        s.press("S⇔D")
        assertEquals("0,666666666666667∠0", s.lines[1])
        s.press("SETUP 6 2")
        assertEquals("2/3∠0", s.lines[1])
        s.press("▼")
        assertEquals("4", s.lines[1])
    }

    /** E-48 [G p.41]: CALC on `A+Bi▶r∠θ` answers in polar form every time round. */
    @Test
    fun calcAnswersInTheFormItsLineAsksFor() {
        val s = Session().press("MODE 2 A SHIFT 2 3 CALC 2 =")
        assertEquals(listOf("A▶r∠θ", "2∠0"), s.lines)
        s.press("= 3 =")
        assertEquals(listOf("A▶r∠θ", "3∠0"), s.lines)
        s.press("SETUP 6 2")
        assertEquals("3∠0", s.lines[1])
    }

    /** A CALC assignment's command applies to the value it assigns, which is the one shown. */
    @Test
    fun anAssignmentTakesTheFormOfItsRightSide() {
        val s = Session().press("MODE 2 Y EQ A SHIFT 2 3 CALC 6 =")
        assertEquals(listOf("Y=A▶r∠θ", "6∠0"), s.lines)
    }
}
