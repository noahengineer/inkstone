package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.lcd.Indicator
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.FractionReal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CALC and SOLVE beyond [EquationRunTest]: the research 04 vectors E-47, E-49 and E-50 as key
 * sequences, the functions SOLVE refuses, the keys on a CALC result, and the typing menus at a
 * prompt (research 02 §10.14–10.15).
 */
class CalcSolveTest {
    /** E-47 [G p.40–41]: 3A+B with (5;10) is 25, then `CALC` re-prompts showing the values kept, (7;20) is 41. */
    @Test
    fun calcRepeatsWithTheValuesKept() {
        val s = Session().press("3 A + B CALC")
        assertEquals(listOf("A?", "0"), s.lines)
        s.press("5 = 10 =")
        assertEquals(listOf("3A+B", "25"), s.lines)
        s.press("CALC")
        assertEquals(listOf("A?", "5"), s.lines)
        s.press("7 = 20 =")
        assertEquals(listOf("3A+B", "41"), s.lines)
        s.press("AC")
        assertEquals("3A+B", s.text)
    }

    /**
     * "An assignment with a single variable on the left" (research 02 §10.14): the result is stored
     * there. Which variables it prompts for is the evaluator's `variablesOf`, so every prompt the
     * fake asks is answered with 2.
     */
    @Test
    fun calcOfAnAssignmentStoresItsResult() {
        val s = Session().press("Y EQ X x² + X + 3 CALC")
        repeat(PROMPT_LIMIT) { if (s.lines[0].endsWith("?")) s.press("2 =") }
        assertEquals(listOf("Y=X²+X+3", "9"), s.lines)
        assertEquals(FractionReal(9, 1), s.machine.memory.variables[Variable.Y])
    }

    /**
     * E-49 [G p.42–43]: Y=AX²+B prompts Y?, A?, B? in order of appearance, then `Solve for X`; the
     * fake solves only linear equations, so this checks the prompts and the start value.
     */
    @Test
    fun solvePromptsForTheOtherVariablesInOrderOfAppearance() {
        val s = Session().press("Y EQ A X x² + B SOLVE")
        assertEquals(listOf("Y?", "0"), s.lines)
        s.press("0 =")
        assertEquals("A?", s.lines[0])
        s.press("1 =")
        assertEquals("B?", s.lines[0])
        s.press("(−) 2 =")
        assertEquals(listOf("Solve for X", "0"), s.lines)
        s.press("1 =")
        assertEquals(FractionReal(1, 1), s.evaluator.starts.last())
    }

    /** E-50 [G p.44]: on a solution `=` returns to `Y?`, and `=` at `Solve for X` keeps the last solution. */
    @Test
    fun solveRepeatsFromTheSolutionScreen() {
        val s = Session().press("Y EQ X + 1 SOLVE 3 = 1 =")
        assertEquals(listOf("Y=X+1", "X=2", "L-R=0"), s.lines)
        s.press("= 7 =")
        assertEquals(listOf("Solve for X", "2"), s.lines)
        s.press("=")
        assertEquals("X=6", s.lines[1])
        assertEquals(FractionReal(2, 1), s.evaluator.starts.last())
    }

    /**
     * `{equation};{solution variable}` (research 02 §10.15; the `;` separator INFERRED for the ZA):
     * the other variables are asked for first, then `Solve for Y` from Y's current value. The
     * solution screen's top line is the input as typed, `;Y` included — the line is never rewritten
     * (research 02 §7.1: "the input line is not rewritten").
     */
    @Test
    fun solveForAnotherVariableNamedAfterASemicolon() {
        val s = Session().press("Y EQ X + 5 ; Y SOLVE")
        assertEquals(listOf("X?", "0"), s.lines)
        s.press("3 =")
        assertEquals(listOf("Solve for Y", "0"), s.lines)
        s.press("=")
        assertEquals(listOf("Y=X+5;Y", "Y=8", "L-R=0"), s.lines)
        assertEquals(FractionReal(8, 1), s.machine.memory.variables[Variable.Y])
    }

    /** The solution is in its variable afterwards even from an evaluator that did not store it. */
    @Test
    fun theMachineStoresTheSolutionItself() {
        val s = Session()
        s.evaluator.forgetsSolutions = true
        s.press("2 X EQ 8 SOLVE =")
        assertEquals("X=4", s.lines[1])
        assertEquals(FractionReal(4, 1), s.machine.memory.variables[Variable.X])
    }

    /** "The following functions are not allowed inside of an equation: ∫, d/dx, Σ, Π, Pol, Rec, ÷R" (VERIFIED). */
    @Test
    fun solveRefusesPolAndRemainderDivision() {
        val pol = Session().press("X + Pol 1 ; 1 ) SOLVE")
        assertEquals("Syntax ERROR", pol.lines[0])
        pol.press("▶")
        assertEquals("X+Pol(1;1)", pol.text)
        assertNotNull(pol.frame.cursor)
        assertEquals("Syntax ERROR", Session().press("X ÷R 2 SOLVE").lines[0])
        assertEquals(emptyList(), Session().press("X ÷R 2 SOLVE").evaluator.starts)
    }

    /** ⚠️ GUESS pinned (EquationRun.finished): `CALC` on an intermediate result starts the prompts again. */
    @Test
    fun calcOnAMultiStatementResultStartsThePromptsAgain() {
        val s = Session().press("X + Y : X × Y CALC 2 = 3 =")
        assertTrue(Indicator.DISP in s.icons)
        s.press("CALC")
        assertEquals(listOf("X?", "2"), s.lines)
    }

    /** ⚠️ GUESS pinned: `SHIFT CALC` restarts a SOLVE run and `CALC` does nothing to its solution. */
    @Test
    fun theKeyThatStartedARunRestartsIt() {
        val s = Session().press("3 X + 4 EQ 2 X + 6 SOLVE =")
        s.press("CALC")
        assertEquals("X=2", s.lines[1])
        s.press("SOLVE")
        assertEquals(listOf("Solve for X", "2"), s.lines)
    }

    /** ⚠️ GUESS pinned: the result keys restyle a CALC result as they would the line's. */
    @Test
    fun sToDTogglesACalcResult() {
        val s = Session().press("A ÷ 3 CALC 1 =")
        assertEquals(listOf("A÷3", "1/3"), s.lines)
        s.press("S⇔D")
        assertEquals("0,333333333333333", s.lines[1])
        s.press("S⇔D")
        assertEquals("1/3", s.lines[1])
        s.press("°'\"")
        assertTrue(s.lines[1] != "1/3")
        s.press("°'\"")
        assertEquals("1/3", s.lines[1])
    }

    /** ⚠️ GUESS pinned: hyp and CONST type into the value at a prompt, as into the line. */
    @Test
    fun typingMenusTypeIntoAPrompt() {
        assertEquals(listOf("A?", "sinh("), Session().press("A CALC hyp 1").lines)
        assertEquals(listOf("A?", "C0"), Session().press("A CALC CONST 28").lines)
        val s = Session().press("A CALC CONST 00")
        assertEquals("Syntax ERROR", s.lines[0])
        s.press("AC")
        assertEquals(listOf("A?", "0"), s.lines)
    }

    private companion object {
        /** More prompts than any line here has. */
        const val PROMPT_LIMIT = 4
    }
}
