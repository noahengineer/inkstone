package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.lcd.Indicator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The error screen and its keys (research 02 §11; vectors F-02 and F-03 of research 04). */
class ErrorFlowTest {
    @Test
    fun mathErrorShowsTheMessageAndItsKeys() {
        val s = Session().press("14 ÷ 0 × 2 =")
        assertEquals(listOf("Math ERROR", "[AC]  :Cancel", "[◀][▶]:Goto"), s.lines)
        assertNull(s.frame.cursor)
        assertTrue(Indicator.DEG in s.icons && Indicator.MATH in s.icons)
    }

    @Test
    fun gotoPutsTheCursorJustAfterTheZero() {
        val s = Session().press("14 ÷ 0 × 2 = ▶")
        assertEquals("14÷0×2", s.text)
        assertEquals(CELL * 4, s.frame.cursor?.x)
        s.press("◀ 1 =")
        assertEquals(listOf("14÷10×2", "14/5"), s.lines)
    }

    @Test
    fun leftAlsoGoesToTheError() {
        val s = Session().press("14 ÷ 0 × 2 = ◀")
        assertEquals(CELL * 4, s.frame.cursor?.x)
    }

    @Test
    fun acClearsTheMessageAndTheExpression() {
        val s = Session().press("14 ÷ 0 × 2 = AC")
        assertEquals("", s.text)
        assertNotNull(s.frame.cursor)
    }

    @Test
    fun otherKeysAreIgnoredOnTheErrorScreen() {
        val s = Session().press("1 ÷ 0 = 5 + DEL =")
        assertEquals("Math ERROR", s.lines[0])
    }

    @Test
    fun aSyntaxErrorGoesToWhereTheParserStopped() {
        val s = Session().press("1 + × 2 =")
        assertEquals("Syntax ERROR", s.lines[0])
        s.press("▶")
        assertEquals("1+×2", s.text)
        assertEquals(CELL * 2, s.frame.cursor?.x)
    }

    @Test
    fun anOutOfRangeConversionNumberIsASyntaxError() {
        val s = Session().press("5 CONV 41")
        assertEquals("Syntax ERROR", s.lines[0])
        s.press("◀")
        assertEquals("5", s.text)
    }

    @Test
    fun theErrorScreenStillOpensTheGlobalMenus() {
        val s = Session().press("1 ÷ 0 = MODE")
        assertTrue(s.text.startsWith("1:COMP"))
        s.press("AC")
        assertEquals("Math ERROR", s.lines[0])
    }

    private companion object {
        const val CELL = 6
    }
}
