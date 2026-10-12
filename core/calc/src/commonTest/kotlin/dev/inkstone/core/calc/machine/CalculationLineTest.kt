package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.lcd.Indicator
import dev.inkstone.core.calc.value.FractionReal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The calculation line through `Calculator.press`: typing, `=`, the result keys, Ans and PreAns,
 * multi-statements (research 02 §7–§10). Results come from the test fakes, so the digits prove
 * the machine's plumbing, not the arithmetic.
 */
class CalculationLineTest {
    @Test
    fun typingShowsTheLineWithACursorAndEqualsShowsTheResult() {
        val s = Session().press("1 + 2")
        assertEquals("1+2", s.text)
        assertNotNull(s.frame.cursor)
        s.press("=")
        assertEquals(listOf("1+2", "3"), s.lines)
        assertNull(s.frame.cursor)
    }

    @Test
    fun theDefaultScreenShowsDegAndMath() {
        val icons = Session().icons
        assertEquals(setOf(Indicator.DEG, Indicator.MATH), icons)
    }

    @Test
    fun anEmptyLineIgnoresEquals() {
        val s = Session().press("=")
        assertEquals("", s.text)
        assertTrue(s.evaluator.evaluated.isEmpty())
    }

    @Test
    fun replayLeftOpensTheLineAtItsEndForEditing() {
        val s = Session().press("4 × 3 + 2 =")
        assertEquals("14", s.lines[1])
        s.press("◀ DEL DEL − 7 =")
        assertEquals(listOf("4×3−7", "5"), s.lines)
    }

    @Test
    fun replayRightOpensTheLineAtItsStart() {
        val s = Session().press("23 = ▶")
        assertEquals("23", s.text)
        assertEquals(0, s.frame.cursor?.x)
        s.press("1")
        assertEquals("123", s.text)
    }

    @Test
    fun anOperatorFirstAfterAResultStartsWithAns() {
        val s = Session().press("3 × 4 = ÷ 30 =")
        assertEquals(listOf("Ans÷30", "2/5"), s.lines)
    }

    @Test
    fun aDigitFirstAfterAResultStartsAFreshLine() {
        val s = Session().press("3 × 4 = 5")
        assertEquals("5", s.text)
    }

    @Test
    fun theAnsKeyTypesAns() {
        val s = Session().press("123 + 456 = 789 − Ans =")
        assertEquals(listOf("789−Ans", "210"), s.lines)
    }

    @Test
    fun preAnsGivesTheFibonacciNumbers() {
        val s = Session().press("1 = 1 = Ans + PreAns =")
        assertEquals(listOf("Ans+PreAns", "2"), s.lines)
        s.press("=")
        assertEquals("3", s.lines[1])
        s.press("=")
        assertEquals("5", s.lines[1])
    }

    @Test
    fun preAnsIsClearedOnLeavingComp() {
        val s = Session().press("7 = 8 =")
        assertEquals(FractionReal(7, 1), s.machine.memory.preAns)
        s.press("MODE 2 MODE 1")
        assertEquals(FractionReal(0, 1), s.machine.memory.preAns)
        assertEquals(FractionReal(8, 1), s.machine.memory.ans)
    }

    @Test
    fun shiftEqualsAsksForADecimalAndSToDTogglesBack() {
        val s = Session().press("3 ÷ 4 SHIFT=")
        assertEquals("0,75", s.lines[1])
        s.press("S⇔D")
        assertEquals("3/4", s.lines[1])
        s.press("S⇔D")
        assertEquals("0,75", s.lines[1])
    }

    @Test
    fun aKeyWithNoLegendLeavesTheResultOnScreen() {
        val s = Session().press("3 × 4 = SHIFT 6")
        assertEquals(listOf("3×4", "12"), s.lines)
    }

    @Test
    fun resultKeysDoNothingWhileALineIsBeingTyped() {
        val s = Session().press("12 ENG S⇔D FACT ←ENG ab/c")
        assertEquals(listOf("12"), s.lines)
        assertNotNull(s.frame.cursor)
    }

    @Test
    fun sToDOnAResultTheFormatterCannotChangeDoesNothing() {
        val s = Session().press("12 = S⇔D")
        assertEquals(listOf("12", "12"), s.lines)
    }

    @Test
    fun engAndShiftEngStepTheExponent() {
        val s = Session().press("1234 = ENG")
        assertEquals("1234×10^-3", s.lines[1])
        s.press("←ENG ←ENG")
        assertEquals("1234×10^3", s.lines[1])
    }

    @Test
    fun factOnAWholeNumberAndOnAFraction() {
        val s = Session().press("1014 = FACT")
        assertEquals("1014!", s.lines[1])
        s.press("FACT")
        assertEquals("1014", s.lines[1])
        s.press("1 ÷ 3 = FACT")
        assertEquals("Math ERROR", s.lines[0])
    }

    @Test
    fun dmsKeyOnAResultTogglesSexagesimal() {
        val s = Session().press("8 = °'\"")
        assertEquals("8°", s.lines[1])
        s.press("°'\"")
        assertEquals("8", s.lines[1])
    }

    @Test
    fun divRShowsQuotientAndRemainderAndAnsTakesTheQuotient() {
        val s = Session().press("5 ÷R 2 =")
        assertEquals(listOf("5÷R2", "2;R=1"), s.lines)
        assertEquals(FractionReal(2, 1), s.machine.memory.ans)
        s.press("S⇔D °'\"")
        assertEquals("2;R=1", s.lines[1])
    }

    @Test
    fun divRInLineIoStacksTheRemainder() {
        val s = Session().press("SETUP 2 75 ÷R 4 =")
        assertEquals(listOf("75÷R4", "18", "R=3"), s.lines)
    }

    @Test
    fun multiStatementsRunOnePerEqualsWithDisp() {
        val s = Session().press("3 + 3 : 3 × 3 =")
        assertEquals(listOf("3+3:3×3", "6"), s.lines)
        assertTrue(Indicator.DISP in s.icons)
        s.press("=")
        assertEquals("9", s.lines[1])
        assertFalse(Indicator.DISP in s.icons)
        s.press("=")
        assertEquals("6", s.lines[1])
    }

    @Test
    fun anInterruptedEvaluationReturnsToTheLineShowingNothing() {
        val s = Session()
        s.evaluator.slow = true
        s.interrupted = true
        s.press("2 + 3 =")
        assertEquals("2+3", s.text)
        assertNotNull(s.frame.cursor)
        s.interrupted = false
        s.press("=")
        assertEquals("5", s.lines[1])
    }

    @Test
    fun acClearsTheLineAndTheResult() {
        val s = Session().press("2 + 3 AC")
        assertEquals("", s.text)
        s.press("4 = AC")
        assertEquals("", s.text)
    }

    @Test
    fun theCursorBecomesABlockWithTenBytesLeft() {
        val s = Session().press("1".repeat(88))
        assertEquals(false, s.frame.cursor?.block)
        s.press("1")
        assertEquals(true, s.frame.cursor?.block)
    }

    @Test
    fun aFullLineRefusesTheNextKey() {
        val s = Session().press("1".repeat(99))
        val before = s.text
        s.press("2 FRAC")
        assertEquals(before, s.text)
    }

    @Test
    fun lineIoInsTogglesOverwrite() {
        val s = Session().press("SETUP 2 1 2 3 ◀ ◀ INS 9")
        assertEquals("193", s.text)
        assertFalse(Indicator.MATH in s.icons)
        s.press("INS 7")
        assertEquals("1973", s.text)
    }

    @Test
    fun naturalInsWrapsTheValueRightOfTheCursor() {
        val s = Session().press("1 + 7 ◀ INS √")
        assertEquals("1+√(7)", s.text)
    }

    @Test
    fun aResultWiderThanTheLineScrollsInsteadOfReopening() {
        val s = Session().press("1234567890123456789 =")
        val unscrolled = s.frame.pixels.copyOf()
        s.press("▶")
        assertEquals(2, s.lines.size)
        assertFalse(unscrolled.contentEquals(s.frame.pixels))
        s.press("▶ ▶ ▶ ▶ ◀ ◀ ◀ ◀ ◀ ◀")
        assertTrue(unscrolled.contentEquals(s.frame.pixels))
        s.press("AC ◀")
        assertEquals("1234567890123456789", s.text)
        assertNotNull(s.frame.cursor)
    }

    @Test
    fun templateKeysAfterAResultContinueOnlyForPowers() {
        val s = Session().press("5 = x^ 2")
        assertEquals("Ans^2", s.text)
        s.press("AC 5 = √ 4")
        assertEquals("√(4)", s.text)
    }
}
