package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.Calculator
import dev.inkstone.core.calc.lcd.Indicator
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.FractionReal
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Defects found by the adversarial review of the key machine, each pinned by the key sequence that
 * showed it, plus the research's worked examples that touch the machine and had no test yet.
 */
class ReviewRegressionTest {
    /**
     * Research 02 §11: Goto returns "to the calculation" — for a FACT on an older history entry
     * that is the entry's own line, not whatever was typed last.
     */
    @Test
    fun aFactErrorOnAnOlderEntryGoesBackToThatEntry() {
        val s = Session().press("1 ÷ 3 = 5 = ▲ FACT")
        assertEquals("Math ERROR", s.lines[0])
        s.press("◀")
        assertEquals("1÷3", s.text)
    }

    @Test
    fun aFactErrorOnAReplayedEntryGoesBackToThatEntry() {
        val s = Session().press("1 ÷ 3 = AC ▲ FACT")
        assertEquals("Math ERROR", s.lines[0])
        s.press("▶")
        assertEquals("1÷3", s.text)
    }

    /**
     * An input/output change clears the line and history (research 02 §7.2, §10.2); a CALC or SOLVE
     * run over the line must go with it, or its AC reopens a Natural-typed expression in a LineIO
     * line, where its templates cannot be parsed.
     */
    @Test
    fun anInputFormatChangeEndsACalcRun() {
        val s = Session().press("FRAC 3 ▼ 4 ▶ + A CALC SETUP 2")
        assertEquals("", s.text)
        assertFalse(Indicator.MATH in s.icons)
        s.press("AC 1 + 2 =")
        assertEquals(listOf("1+2", "3"), s.lines)
    }

    @Test
    fun anInputFormatChangeEndsASolveRun() {
        val s = Session().press("3 X + 4 EQ 2 X + 6 SOLVE = SETUP 2")
        assertEquals("", s.text)
        s.press("=")
        assertEquals("", s.text)
    }

    @Test
    fun otherSetupChangesLeaveACalcRunWhereItWas() {
        val s = Session().press("A + 1 CALC SETUP 4")
        assertEquals(listOf("A?", "0"), s.lines)
        assertTrue(Indicator.RAD in s.icons)
        s.press("2 =")
        assertEquals(listOf("A+1", "3"), s.lines)
    }

    /** Research 02 §11 at a CALC prompt: ◀/▶ put the cursor at the error inside the typed value. */
    @Test
    fun gotoFromAPromptErrorPutsTheCursorAtTheError() {
        val s = Session().press("A + 1 CALC 1 ÷ 0 + 5 =")
        assertEquals("Math ERROR", s.lines[0])
        s.press("◀")
        assertEquals(listOf("A?", "1÷0+5"), s.lines)
        assertEquals(CELL * 3, s.frame.cursor?.x)
        s.press("DEL 2 =")
        assertEquals(listOf("A+1", "13/2"), s.lines)
        assertEquals(FractionReal(11, 2), s.variable(Variable.A))
    }

    /** Research 02 §11: AC "clears the message and the expression" — at a prompt, the value typed. */
    @Test
    fun acOnAPromptErrorClearsTheTypedValue() {
        val s = Session().press("A + 1 CALC 1 ÷ 0 = AC")
        assertEquals(listOf("A?", "0"), s.lines)
        s.press("=")
        assertEquals(listOf("A+1", "1"), s.lines)
    }

    /** Text that parses but breaks a [dev.inkstone.core.calc.value.Value] invariant is unreadable too. */
    @Test
    fun valuesThatBreakTheirInvariantsAreRefused() {
        val bad = listOf(
            "M 65536 65536",
            "M 4 1 F1/1 F1/1 F1/1 F1/1",
            "M 1 4 F1/1 F1/1 F1/1 F1/1",
            "V 1 F1/1",
            "V 4 F1/1 F1/1 F1/1 F1/1",
            "F2/4",
            "F0/2",
            "F-9223372036854775808/1",
            "D1E200",
            "G-1E100",
            "C D1E200 F0/1",
            "S1/0/2@1",
            "S1/2/0@1",
            "S1/2/3@1E100",
        )
        for (text in bad) assertNull(ValueCodec.decode(text), "<$text>")
    }

    @Test
    fun aSnapshotHoldingAnUnreducedFractionGivesAFreshCalculator() {
        fun fresh() = Calculator(FakeEvaluator(), FakeFormatter(), Random(1))
        val good = fresh().snapshot()
        val bad = good.replace("ans=F0/1", "ans=F2/4")
        assertTrue(bad != good)
        assertEquals(good, Calculator.restore(bad) { fresh() }.snapshot())
    }

    /** Research 04 H-07 (SA s.19): CONV on an empty line, then ◀ to type the value before it. */
    @Test
    fun aConversionCanBeTypedFirstAndItsValueInsertedBeforeIt() {
        val s = Session().press("CONV 37")
        assertEquals("°F▶°C", s.text)
        s.press("◀ 356")
        assertEquals("356°F▶°C", s.text)
    }

    /** Research 04 D-01: LineIO, `÷` after a result starts `Ans÷`; D and ▲ lit, Math not. */
    @Test
    fun lineIoAnsContinuationShowsTheHistoryArrowAndNoMath() {
        val s = Session().press("SETUP 2 3 × 4 = ÷ 30 =")
        assertEquals(listOf("Ans÷30", "2⌟5"), s.lines)
        assertEquals(setOf(Indicator.DEG, Indicator.UP), s.icons)
    }

    /** Research 04 G-02 and G-03: variables survive AC; CLR Memory clears them. */
    @Test
    fun variablesSurviveAcAndClrMemoryClearsThem() {
        val s = Session().press("5 STO (−) AC RCL (−)")
        assertEquals(listOf("A", "5"), s.lines)
        s.press("CLR 2 = AC RCL (−)")
        assertEquals(listOf("A", "0"), s.lines)
    }

    /** PreAns is COMP-only (research 02 §9, VERIFIED): a CMPLX result moves Ans, never PreAns. */
    @Test
    fun aResultOutsideCompDoesNotMovePreAns() {
        val s = Session().press("MODE 2 3 = 4 =")
        assertEquals(FractionReal(4, 1), s.machine.memory.ans)
        assertEquals(FractionReal(0, 1), s.machine.memory.preAns)
    }

    /** FACT takes a positive integer "of up to 10 digits" (research 02 §10.13). */
    @Test
    fun factTakesTenDigitsAndRefusesEleven() {
        val s = Session().press("9999999999 = FACT")
        assertEquals("9999999999!", s.lines[1])
        s.press("10000000000 = FACT")
        assertEquals("Math ERROR", s.lines[0])
        s.press("AC 0 = FACT")
        assertEquals("Math ERROR", s.lines[0])
    }

    /** FACT is COMP-only (research 02 §10.13): in CMPLX the key leaves the result alone. */
    @Test
    fun factDoesNothingOutsideComp() {
        val s = Session().press("MODE 2 1 ÷ 3 = FACT")
        assertEquals(listOf("1÷3", "1/3"), s.lines)
    }

    /** A menu takes an unshifted digit only; SHIFT 1 there spends the latch and selects nothing. */
    @Test
    fun aShiftedDigitSelectsNothingInAMenu() {
        val s = Session().press("hyp SHIFT 1")
        assertEquals("1:sinh  2:cosh\n3:tanh  4:sinh⁻¹\n5:cosh⁻¹  6:tanh⁻¹", s.text)
        assertFalse(Indicator.SHIFT in s.icons)
        s.press("1")
        assertEquals("sinh(", s.text)
    }

    @Test
    fun delTakesBackTheFirstDigitOfAConstantNumber() {
        val s = Session().press("CONST 3 DEL 2 8")
        assertEquals("C0", s.text)
    }

    private fun Session.variable(v: Variable) = machine.memory.variables[v]

    @Test
    fun stoOnAPlainLineStillStores() {
        val s = Session().press("6 × 7 STO (−)")
        assertEquals(FractionReal(42, 1), s.variable(Variable.A))
    }

    /**
     * "The solution is always decimal" (research 02 §10.15, VERIFIED) — a display rule, so the
     * screen keeps it even when the evaluator hands back an exact root. The variable keeps the
     * exact value.
     */
    @Test
    fun aSolveSolutionIsShownAsADecimal() {
        val s = Session().press("2 X EQ 1 SOLVE =")
        assertEquals(listOf("2X=1", "X=0,5", "L-R=0"), s.lines)
        assertEquals(FractionReal(1, 2), s.variable(Variable.X))
    }

    /**
     * MthIO-MathO to MthIO-LineO keeps Natural input but is still a display-format change, which
     * clears history (research 02 §10.2): the result it pointed into goes with it.
     */
    @Test
    fun aResultFormatChangeStartsAFreshLine() {
        val s = Session().press("1 + 1 = 2 + 2 = SETUP 1 2")
        assertEquals("", s.text)
        assertTrue(Indicator.MATH in s.icons)
        assertFalse(Indicator.UP in s.icons)
    }

    /** Research 04 E-47: CALC's result, then the CALC key itself starts the prompts again. */
    @Test
    fun theCalcKeyRunsCalcAgainFromItsResult() {
        val s = Session().press("3 A + B CALC 5 = 10 =")
        assertEquals(listOf("3A+B", "25"), s.lines)
        s.press("CALC")
        assertEquals(listOf("A?", "5"), s.lines)
        s.press("7 = 20 =")
        assertEquals(listOf("3A+B", "41"), s.lines)
        s.press("AC")
        assertEquals("3A+B", s.text)
    }

    /** Research 04 E-49: SOLVE asks for Y, A and B in order of appearance, never for X. */
    @Test
    fun solveAsksForTheOtherVariablesInOrderOfAppearance() {
        val s = Session().press("Y EQ A X x² + B SOLVE")
        assertEquals(listOf("Y?", "0"), s.lines)
        s.press("0 =")
        assertEquals("A?", s.lines[0])
        s.press("1 =")
        assertEquals("B?", s.lines[0])
        s.press("(−) 2 =")
        assertEquals(listOf("Solve for X", "0"), s.lines)
        assertEquals(FractionReal(-2, 1), s.variable(Variable.B))
    }

    /**
     * Research 04 E-50's key flow, on an equation the test evaluator can solve: `=` on a solution
     * asks for Y again, and a bare `=` at `Solve for X` starts from the X found last time.
     */
    @Test
    fun solveRunsAgainForANewYFromTheLastX() {
        val s = Session().press("Y EQ 2 X + 1 SOLVE 3 = =")
        assertEquals("X=1", s.lines[1])
        s.press("= 7 =")
        assertEquals(listOf("Solve for X", "1"), s.lines)
        s.press("=")
        assertEquals("X=3", s.lines[1])
    }

    /** Research 04 E-52: CONST inserts at the cursor, inside the √ in the denominator. */
    @Test
    fun constantsGoInAtTheCursorInsideATemplate() {
        val s = Session().press("FRAC 1 ▼ √ CONST 32 CONST 33")
        assertEquals("1/√(ε0μ0)", s.text)
    }

    private companion object {
        const val CELL = 6
    }
}
