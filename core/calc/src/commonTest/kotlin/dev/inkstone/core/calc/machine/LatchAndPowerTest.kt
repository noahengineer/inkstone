package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.Key
import dev.inkstone.core.calc.lcd.Indicator
import dev.inkstone.core.calc.lcd.LcdFrame
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.FractionReal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The S/A latch (research 02 §6) and the power keys (research 02 §3, §14). */
class LatchAndPowerTest {
    @Test
    fun shiftLightsSAndIsSpentByTheNextKey() {
        val s = Session().press("SHIFT")
        assertTrue(Indicator.SHIFT in s.icons)
        s.press("x^")
        assertFalse(Indicator.SHIFT in s.icons)
        assertEquals("ˣ√()", s.text)
    }

    @Test
    fun shiftTwiceCancels() {
        val s = Session().press("SHIFT SHIFT")
        assertFalse(Indicator.SHIFT in s.icons)
        s.press("7")
        assertEquals("7", s.text)
    }

    @Test
    fun alphaAfterShiftSwapsTheLatch() {
        val s = Session().press("SHIFT ALPHA")
        assertEquals(setOf(Indicator.ALPHA), s.icons.filter { it == Indicator.SHIFT || it == Indicator.ALPHA }.toSet())
        s.press("NEGATIVE")
        assertEquals("A", s.text)
        assertFalse(Indicator.ALPHA in s.icons)
    }

    @Test
    fun shiftAfterAlphaSwapsBack() {
        val s = Session().press("ALPHA SHIFT")
        assertTrue(Indicator.SHIFT in s.icons)
        assertFalse(Indicator.ALPHA in s.icons)
        s.press("EXP")
        assertEquals("π", s.text)
    }

    @Test
    fun aCombinationWithNoLegendStillSpendsTheLatch() {
        val s = Session().press("SHIFT 6")
        assertEquals("", s.text)
        assertFalse(Indicator.SHIFT in s.icons)
        s.press("6")
        assertEquals("6", s.text)
    }

    @Test
    fun shiftAcSwitchesOffAndOnlyOnBringsItBack() {
        val s = Session().press("5 STO (−) 1 + 2")
        s.press("OFF")
        assertTrue(s.frame.off)
        assertSame(LcdFrame.OFF, s.frame)
        s.press("7 = MODE SHIFT")
        assertSame(LcdFrame.OFF, s.frame)
        s.press("ON")
        assertFalse(s.frame.off)
        assertEquals("", s.text)
        assertEquals(FractionReal(5, 1), s.machine.memory.variables[Variable.A])
    }

    @Test
    fun autoPowerOffIsShiftAcAndUsesTheApoSetting() {
        val s = Session()
        assertEquals(10, s.calculator.autoPowerOffMinutes)
        s.press("SETUP ▼ 6 2")
        assertEquals(60, s.calculator.autoPowerOffMinutes)
        assertTrue(s.calculator.autoPowerOff().off)
        assertTrue(s.frame.off)
        s.press("ON")
        assertFalse(s.frame.off)
    }

    @Test
    fun onWhileOnClearsTheLineAndTheHistory() {
        val s = Session().press("1 + 1 = 2")
        s.press("ON")
        assertEquals("", s.text)
        s.press("▲")
        assertEquals("", s.text)
        assertFalse(Indicator.UP in s.icons)
    }

    @Test
    fun onClosesAnOpenMenu() {
        val s = Session().press("MODE")
        assertTrue(s.text.startsWith("1:COMP"))
        s.press("ON")
        assertEquals("", s.text)
    }

    @Test
    fun theLatchIsReadOnTheKeyItIsSpentOn() {
        val s = Session()
        s.calculator.press(Key.SHIFT)
        s.calculator.press(Key.SHIFT)
        s.calculator.press(Key.ALPHA)
        s.calculator.press(Key.CLOSE_PAREN)
        assertEquals("X", s.text)
    }
}
