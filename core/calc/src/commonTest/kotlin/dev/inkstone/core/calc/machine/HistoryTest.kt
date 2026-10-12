package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.eval.Answer
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.input.TokenItem
import dev.inkstone.core.calc.lcd.Indicator
import dev.inkstone.core.calc.value.FractionReal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Calculation history and replay (research 02 §10.2, VERIFIED examples C-27 and C-28). */
class HistoryTest {
    @Test
    fun upAndDownStepThroughTheHistory() {
        val s = Session().press("1 + 1 = 2 + 2 = 3 + 3 =")
        assertTrue(Indicator.UP in s.icons)
        assertFalse(Indicator.DOWN in s.icons)
        s.press("▲")
        assertEquals(listOf("2+2", "4"), s.lines)
        assertEquals(setOf(Indicator.UP, Indicator.DOWN), s.icons.filter { it in ARROWS }.toSet())
        s.press("▲")
        assertEquals(listOf("1+1", "2"), s.lines)
        assertEquals(setOf(Indicator.DOWN), s.icons.filter { it in ARROWS }.toSet())
        s.press("▲")
        assertEquals("2", s.lines[1])
        s.press("▼ ▼")
        assertEquals(listOf("3+3", "6"), s.lines)
    }

    @Test
    fun theFirstCalculationHasNoOlderEntryToShow() {
        val s = Session().press("1 + 1 =")
        assertFalse(Indicator.UP in s.icons)
        s.press("▲")
        assertEquals(listOf("1+1", "2"), s.lines)
    }

    @Test
    fun anEmptyLineReplaysTheLastCalculation() {
        val s = Session().press("1 + 1 = 2 × 5 = AC")
        assertTrue(Indicator.UP in s.icons)
        s.press("◀")
        assertEquals("2×5", s.text)
        s.press("AC ▲")
        assertEquals(listOf("2×5", "10"), s.lines)
        s.press("▲")
        assertEquals(listOf("1+1", "2"), s.lines)
    }

    @Test
    fun aReplayedEntryCanBeEditedAndRunAgain() {
        val s = Session().press("1 + 1 = 2 + 2 = ▲ ◀ DEL 5 =")
        assertEquals(listOf("1+5", "6"), s.lines)
    }

    @Test
    fun equalsOnAnOlderEntryRunsItAgain() {
        val s = Session().press("1 + 1 = 2 + 2 = ▲ =")
        assertEquals(listOf("1+1", "2"), s.lines)
        assertEquals(FractionReal(2, 1), s.machine.memory.ans)
    }

    @Test
    fun aModeChangeClearsHistory() {
        val s = Session().press("1 + 1 = MODE 1")
        assertFalse(Indicator.UP in s.icons)
        s.press("▲ ◀")
        assertEquals("", s.text)
    }

    @Test
    fun aDisplayFormatChangeClearsHistoryButAnAngleChangeDoesNot() {
        val s = Session().press("1 + 1 = SETUP 4 AC")
        assertTrue(Indicator.UP in s.icons)
        s.press("SETUP 2")
        assertFalse(Indicator.UP in s.icons)
    }

    @Test
    fun theHistoryKeepsAboutTwoHundredBytesDroppingTheOldest() {
        val history = History()
        val entry = HistoryEntry(Expr(List(30) { TokenItem(Tok.D1) }), Answer.Plain(FractionReal(1, 1)))
        repeat(10) { history.add(entry) }
        assertEquals(5, history.size)
        assertTrue(history.bytes() <= History.CAPACITY)
        history.add(HistoryEntry(Expr(List(99) { TokenItem(Tok.D2) }), Answer.Plain(FractionReal(2, 1))))
        assertTrue(history.bytes() <= History.CAPACITY)
        assertEquals(99, history.getOrNull(history.lastIndex)?.expr?.items?.size)
    }

    private companion object {
        val ARROWS = setOf(Indicator.UP, Indicator.DOWN)
    }
}
