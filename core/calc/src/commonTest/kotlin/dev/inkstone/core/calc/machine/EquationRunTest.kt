package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.lcd.Indicator
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.FractionReal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CALC and SOLVE (research 02 §10.14–10.15; vectors H-04 to H-06 of research 04, from the SA
 * handout's screens). The fake evaluator solves only equations linear in the variable.
 */
class EquationRunTest {
    @Test
    fun calcPromptsForEachVariableAndShowsTheResult() {
        val s = Session().press("3 A x² + 4 B CALC")
        assertEquals(listOf("A?", "0"), s.lines)
        s.press("6 =")
        assertEquals(listOf("B?", "0"), s.lines)
        s.press("2 =")
        assertEquals(listOf("3A²+4B", "116"), s.lines)
        assertEquals(FractionReal(116, 1), s.machine.memory.ans)
    }

    @Test
    fun equalsOnACalcResultStartsThePromptsAgainShowingTheValuesKept() {
        val s = Session().press("3 A x² + 4 B CALC 6 = 2 = =")
        assertEquals(listOf("A?", "6"), s.lines)
        s.press("= =")
        assertEquals("116", s.lines[1])
    }

    @Test
    fun aValueTypedAtAPromptIsEvaluatedAndShownWhileTyped() {
        val s = Session().press("A + 1 CALC 2 × 3")
        assertEquals("2×3", s.lines[1])
        assertTrue(s.frame.cursor != null)
        s.press("=")
        assertEquals(listOf("A+1", "7"), s.lines)
        assertEquals(FractionReal(6, 1), s.machine.memory.variables[Variable.A])
    }

    @Test
    fun acLeavesCalcBackToTheExpression() {
        val s = Session().press("A + 1 CALC AC")
        assertEquals("A+1", s.text)
        s.press("=")
        assertEquals("1", s.lines[1])
    }

    @Test
    fun anErrorAtAPromptReturnsToThatPrompt() {
        val s = Session().press("A + 1 CALC 1 ÷ 0 =")
        assertEquals("Math ERROR", s.lines[0])
        s.press("AC")
        assertEquals("A?", s.lines[0])
    }

    @Test
    fun solveAsksForTheStartAndShowsTheSolution() {
        val s = Session().press("3 X + 4 EQ 2 X + 6 SOLVE")
        assertEquals(listOf("Solve for X", "0"), s.lines)
        s.press("=")
        assertEquals(listOf("3X+4=2X+6", "X=2", "L-R=0"), s.lines)
        assertEquals(FractionReal(2, 1), s.machine.memory.variables[Variable.X])
    }

    @Test
    fun solveStartsFromTheCurrentValueOfTheVariable() {
        val s = Session().press("3 X + 4 EQ 2 X + 6 SOLVE = AC AC X + 1 EQ 5 SOLVE")
        assertEquals(listOf("Solve for X", "2"), s.lines)
        s.press("=")
        assertEquals("X=4", s.lines[1])
    }

    @Test
    fun solvePromptsForTheOtherVariablesFirst() {
        val s = Session().press("X + A EQ 10 SOLVE")
        assertEquals("A?", s.lines[0])
        s.press("3 = =")
        assertEquals("X=7", s.lines[1])
    }

    @Test
    fun continueAppearsWhenTheIterationsRunOut() {
        val s = Session()
        s.evaluator.unfinishedSolves = 1
        s.press("2 X EQ 8 SOLVE =")
        assertEquals("Continue:[=]", s.lines[0])
        s.press("=")
        assertEquals("X=4", s.lines[1])
    }

    @Test
    fun solveWithoutItsVariableIsAVariableError() {
        val s = Session().press("A + 1 EQ 3 SOLVE")
        assertEquals("Variable ERROR", s.lines[0])
    }

    @Test
    fun solveIsCompOnlyAndCalcWorksInCmplx() {
        val s = Session().press("MODE 2 X + 1 SOLVE")
        assertEquals("X+1", s.text)
        s.press("CALC")
        assertEquals("X?", s.lines[0])
        assertTrue(Indicator.CMPLX in s.icons)
    }
}
