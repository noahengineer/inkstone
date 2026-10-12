package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.lcd.Indicator
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.FractionReal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * `AC` during a long calculation (research 02 §10.7 "AC interrupts it", §10.15 for SOLVE): the
 * calculation returns to its input, and the `AC` that broke it — still queued when the broken
 * press returns, as the owner feeds keys one at a time — has done its work and does nothing more
 * (Machine's KDoc). Each test sets the fake evaluator [FakeEvaluator.slow], marks the presses as
 * having an `AC` queued behind them, and then delivers that `AC`.
 */
class AcBreakTest {
    private fun breakNext(s: Session, keys: String) {
        s.evaluator.slow = true
        s.interrupted = true
        s.press(keys)
        s.interrupted = false
        s.press("AC")
        s.evaluator.slow = false
    }

    @Test
    fun theLineKeepsWhatWasTypedAndOnlyALaterAcClearsIt() {
        val s = Session().press("2 + 3")
        breakNext(s, "=")
        assertEquals("2+3", s.text)
        assertNotNull(s.frame.cursor)
        s.press("AC")
        assertEquals("", s.text)
    }

    /** SOLVE: the equation stays on the line, the variable is as it was, and SOLVE runs again from it. */
    @Test
    fun solveReturnsToItsEquation() {
        val s = Session().press("3 X + 4 EQ 2 X + 6 SOLVE")
        breakNext(s, "=")
        assertEquals("3X+4=2X+6", s.text)
        assertNotNull(s.frame.cursor)
        assertEquals(FractionReal(0, 1), s.machine.memory.variables[Variable.X])
        s.press("SOLVE =")
        assertEquals(listOf("3X+4=2X+6", "X=2", "L-R=0"), s.lines)
    }

    /** `Continue:[=]`'s `=` iterates again, and an `AC` that breaks that run also leaves the equation. */
    @Test
    fun aBrokenContinueReturnsToItsEquation() {
        val s = Session()
        s.evaluator.unfinishedSolves = 1
        s.press("2 X EQ 8 SOLVE =")
        assertEquals("Continue:[=]", s.lines[0])
        breakNext(s, "=")
        assertEquals("2X=8", s.text)
    }

    /** CALC: a broken calculation returns to the expression, with the values given so far kept. */
    @Test
    fun calcReturnsToItsExpression() {
        val s = Session().press("A + 1 CALC 4 = CALC")
        assertEquals(listOf("A?", "4"), s.lines)
        breakNext(s, "=")
        assertEquals("A+1", s.text)
        assertEquals(FractionReal(4, 1), s.machine.memory.variables[Variable.A])
    }

    /** A value typed at a prompt whose evaluation is broken stays typed at that prompt. */
    @Test
    fun aPromptKeepsTheValueTypedAtIt() {
        val s = Session().press("A + 1 CALC 4")
        breakNext(s, "=")
        assertEquals(listOf("A?", "4"), s.lines)
        assertNotNull(s.frame.cursor)
        s.press("=")
        assertEquals(listOf("A+1", "5"), s.lines)
    }

    /** An editor cell: the value typed stays, and the `AC` does not go on to zero the coefficients. */
    @Test
    fun aCellKeepsItsValueAndTheCoefficients() {
        val s = Session().press("MODE ▼ 3 1 3 = 8 = 1 2")
        breakNext(s, "=")
        assertEquals("a | b | d\n3 | 8 | 0\na:b=X:d\n12", s.text)
        s.press("=")
        assertEquals("a | b | d\n3 | 8 | 12\na:b=X:d\n12", s.text)
    }

    /** A press that was not broken leaves the next `AC` alone, even with an `AC` queued behind it. */
    @Test
    fun aPressThatWasNotBrokenSpendsNothing() {
        val s = Session()
        s.interrupted = true
        s.press("2 + 3 =")
        s.interrupted = false
        s.press("AC")
        assertEquals("", s.text)
    }

    /**
     * `SHIFT AC` (OFF) pressed during the calculation: the `AC` breaks it, and the SHIFT that came
     * with it — which the real one, busy calculating, never read — leaves no S latch behind.
     */
    @Test
    fun aShiftQueuedWithTheBreakingAcLeavesNoLatch() {
        val s = Session().press("2 + 3")
        s.evaluator.slow = true
        s.interrupted = true
        s.press("=")
        s.interrupted = false
        s.evaluator.slow = false
        s.press("SHIFT AC")
        assertFalse(s.frame.off)
        assertFalse(Indicator.SHIFT in s.icons)
        s.press("7")
        assertEquals("2+37", s.text)
    }

    /** `ON` starts afresh: an `AC` after it acts, whatever was broken before it. */
    @Test
    fun onForgetsABreak() {
        val s = Session().press("2 + 3")
        s.evaluator.slow = true
        s.interrupted = true
        s.press("=")
        s.interrupted = false
        s.evaluator.slow = false
        s.press("ON 7 AC")
        assertEquals("", s.text)
    }
}
