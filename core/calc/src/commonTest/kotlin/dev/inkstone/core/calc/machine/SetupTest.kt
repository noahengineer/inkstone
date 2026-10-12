package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.AngleUnit
import dev.inkstone.core.calc.ComplexFormat
import dev.inkstone.core.calc.FractionFormat
import dev.inkstone.core.calc.InputOutput
import dev.inkstone.core.calc.NumberFormat
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.TableFunctions
import dev.inkstone.core.calc.lcd.Indicator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** SETUP (research 02 §5): both pages, every item and sub-prompt, and the contrast screen. */
class SetupTest {
    private fun setupAfter(keys: String): Setup = Session().press(keys).machine.setup

    @Test
    fun bothPagesListEveryItem() {
        val s = Session().press("SETUP")
        assertEquals("1:MthIO  2:LineIO\n3:Deg  4:Rad\n5:Gra  6:Fix\n7:Sci  8:Norm", s.text)
        assertTrue(Indicator.DOWN in s.icons)
        s.press("▼")
        assertEquals("1:ab/c  2:d/c\n3:CMPLX  4:STAT\n5:TABLE  6:APO\n7:◀CONT▶", s.text)
        assertTrue(Indicator.UP in s.icons)
    }

    @Test
    fun angleUnitsChangeTheBoxedIcon() {
        val s = Session().press("SETUP 4")
        assertEquals(AngleUnit.RAD, s.machine.setup.angle)
        assertTrue(Indicator.RAD in s.icons)
        assertFalse(Indicator.DEG in s.icons)
        s.press("SETUP 5")
        assertTrue(Indicator.GRA in s.icons)
        s.press("SETUP 3")
        assertTrue(Indicator.DEG in s.icons)
    }

    @Test
    fun fixSciAndNormAskForADigit() {
        val s = Session().press("SETUP 6")
        assertEquals("Fix 0~9?", s.text)
        s.press("3")
        assertEquals(NumberFormat.Fix(3), s.machine.setup.format)
        assertTrue(Indicator.FIX in s.icons)
        s.press("SETUP 7")
        assertEquals("Sci 0~9?", s.text)
        s.press("0")
        assertEquals(NumberFormat.Sci(0), s.machine.setup.format)
        assertTrue(Indicator.SCI in s.icons)
        assertFalse(Indicator.FIX in s.icons)
        s.press("SETUP 8")
        assertEquals("Norm 1~2?", s.text)
        s.press("3")
        assertEquals("Norm 1~2?", s.text)
        s.press("1")
        assertEquals(NumberFormat.Norm(1), s.machine.setup.format)
        assertFalse(Indicator.SCI in s.icons)
    }

    @Test
    fun mthIoAsksForTheResultFormat() {
        val s = Session().press("SETUP 1")
        assertEquals("Result Format?\n1:MathO  2:LineO", s.text)
        s.press("2")
        assertEquals(InputOutput.MATH_LINE, s.machine.setup.io)
        assertTrue(Indicator.MATH in s.icons)
    }

    @Test
    fun lineIoTurnsMathOffAndStartsAFreshLine() {
        val s = Session().press("1 + 2 SETUP 2")
        assertEquals(InputOutput.LINE_LINE, s.machine.setup.io)
        assertFalse(Indicator.MATH in s.icons)
        assertEquals("", s.text)
    }

    @Test
    fun pageTwoItems() {
        assertEquals(FractionFormat.MIXED, setupAfter("SETUP ▼ 1").fraction)
        assertEquals(FractionFormat.IMPROPER, setupAfter("SETUP ▼ 1 SETUP ▼ 2").fraction)
        assertEquals(ComplexFormat.POLAR, setupAfter("SETUP ▼ 3 2").complex)
        assertEquals(true, setupAfter("SETUP ▼ 4 1").statFrequency)
        assertEquals(TableFunctions.F_ONLY, setupAfter("SETUP ▼ 5 1").table)
        assertEquals(60, setupAfter("SETUP ▼ 6 2").autoPowerOffMinutes)
    }

    @Test
    fun subPromptsShowTheirTitles() {
        assertEquals("Complex Result?\n1:a+bi  2:r∠θ", Session().press("SETUP ▼ 3").text)
        assertEquals("Frequency?\n1:ON  2:OFF", Session().press("SETUP ▼ 4").text)
    }

    @Test
    fun contrastScreenAdjustsTheFrameLive() {
        val s = Session().press("SETUP ▼ 7")
        assertEquals("CONTRAST\nLIGHT       DARK\n [◀]        [▶]", s.text)
        assertEquals(Setup.DEFAULT_CONTRAST, s.frame.contrast)
        s.press("◀")
        assertEquals(Setup.DEFAULT_CONTRAST - 1, s.frame.contrast)
        s.press("▶ ▶ ▶")
        assertEquals(Setup.DEFAULT_CONTRAST + 2, s.frame.contrast)
        s.press("AC")
        assertEquals("", s.text)
        assertEquals(Setup.DEFAULT_CONTRAST + 2, s.frame.contrast)
    }

    @Test
    fun contrastStopsAtItsEnds() {
        val s = Session().press("SETUP ▼ 7")
        repeat(Setup.MAX_CONTRAST + 2) { s.press("▶") }
        assertEquals(Setup.MAX_CONTRAST, s.frame.contrast)
    }

    @Test
    fun acLeavesSetupUnchangedAndTheLineIntact() {
        val s = Session().press("1 + 2 SETUP AC")
        assertEquals("1+2", s.text)
        assertNotNull(s.frame.cursor)
        s.press("SETUP 6 AC")
        assertEquals(Setup(), s.machine.setup)
        assertEquals("1+2", s.text)
    }

    @Test
    fun aFormatChangeRedrawsTheResultOnScreen() {
        val s = Session().press("1014 = FACT")
        assertEquals("1014!", s.lines[1])
        s.press("SETUP 6 2")
        assertEquals(listOf("1014", "1014"), s.lines)
        assertTrue(Indicator.FIX in s.icons)
    }
}
