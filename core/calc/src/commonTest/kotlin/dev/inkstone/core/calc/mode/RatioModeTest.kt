package dev.inkstone.core.calc.mode

import dev.inkstone.core.calc.Calculator
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.lcd.Indicator
import dev.inkstone.core.calc.machine.FakeEvaluator
import dev.inkstone.core.calc.machine.FakeFormatter
import dev.inkstone.core.calc.machine.Session
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.FractionReal
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RATIO end to end through the keys (research 03 §12; vectors EX-RATIO-01…04 and EX-SA-RATIO-01 of
 * research 05). The fakes' formatter shows a cell as its canonical decimal with the comma and a
 * fraction stacked (`9/2`), which is all these need to tell the screens apart.
 */
class RatioModeTest {
    private fun ratio(form: Int): Session = Session().press("MODE ▼ 3 $form")

    @Test
    fun enteringShowsTheTwoFormsOnePerLine() {
        val s = Session().press("MODE ▼ 3")
        assertEquals(Mode.RATIO, s.machine.mode)
        assertEquals("1:a:b=X:d\n2:a:b=c:X", s.text)
    }

    /** EX-RATIO-01 (screens `RAITO_02`, `RAITO_03`): headers, the template, the current cell, `X=` and `9/2`. */
    @Test
    fun threeToEightIsXToTwelve() {
        val s = ratio(1)
        assertEquals("a | b | d\n0 | 0 | 0\na:b=X:d\n0", s.text)
        s.press("3 = 8 = 12 =")
        assertEquals("a | b | d\n3 | 8 | 12\na:b=X:d\n12", s.text)
        s.press("=")
        assertEquals("X=\n9/2", s.text)
        s.press("=")
        assertEquals("a | b | d\n3 | 8 | 12\na:b=X:d\n12", s.text)
    }

    @Test
    fun theGuidesOtherTwoExamples() {
        assertEquals("X=\n5", ratio(1).press("1 = 2 = 10 = =").text)
        val second = ratio(2)
        assertEquals("a | b | c\n0 | 0 | 0\na:b=c:X\n0", second.text)
        assertEquals("X=\n20", second.press("1 = 2 = 10 = =").text)
    }

    /**
     * EX-SA-RATIO-01 (a VERIFIED capture): decimals typed into the cells, and X shown as the exact
     * `125/3` — then S⇔D gives the decimal. A decimal reading of the coefficients could only ever
     * show the decimal, which is what [dev.inkstone.core.calc.machine.Literals] is for.
     */
    @Test
    fun decimalCoefficientsGiveAnExactAnswer() {
        val s = ratio(2).press("1 , 5 = 12 , 50 = 5 =")
        assertEquals("a | b | c\n1,5 | 12,5 | 5\na:b=c:X\n5", s.text)
        s.press("=")
        assertEquals("X=\n125/3", s.text)
        s.press("S⇔D")
        assertEquals("X=\n41,6666666666667", s.text)
        s.press("S⇔D")
        assertEquals("X=\n125/3", s.text)
    }

    /** ⚠️ GUESS pinned (LabelledResult): ENG leaves X alone, as EQN's solutions "cannot be converted with ENG". */
    @Test
    fun engDoesNothingToTheAnswer() {
        val s = ratio(1).press("3 = 8 = 12 = = ENG ←ENG")
        assertEquals("X=\n9/2", s.text)
    }

    /** A SETUP change redraws X from its standard face, as the line redraws a result (⚠️ GUESS, LabelledResult). */
    @Test
    fun aSetupChangeRedrawsTheAnswer() {
        val s = ratio(2).press("1 , 5 = 12 , 50 = 5 = = S⇔D")
        assertEquals("X=\n41,6666666666667", s.text)
        s.press("SETUP 4")
        assertEquals("X=\n125/3", s.text)
        assertEquals("a | b | c\n1,5 | 12,5 | 5\na:b=c:X\n5", s.press("=").text)
    }

    /** EX-RATIO-04: "A Math ERROR will occur if you perform a calculation while 0 is input for a coefficient." */
    @Test
    fun aZeroCoefficientIsAMathError() {
        val s = ratio(1).press("0 = 2 = 10 = =")
        assertEquals("Math ERROR", s.lines[0])
        s.press("AC")
        assertEquals("a | b | d\n0 | 2 | 10\na:b=X:d\n10", s.text)
        assertEquals("Math ERROR", ratio(2).press("1 = 0 =").press("=").lines[0])
        assertEquals("Math ERROR", ratio(2).press("1 = 2 = =").lines[0])
    }

    /** Research 03 §2.4 (INFERRED): a bare `=` solves from any cell, not only the last. */
    @Test
    fun aBareEqualsSolvesFromAnyCell() {
        val s = ratio(1).press("3 = 8 = 12 = ◀ ◀ =")
        assertEquals("X=\n9/2", s.text)
    }

    /** "To change a coefficient value you already have input, move the cursor to the appropriate cell…" */
    @Test
    fun aCellIsChangedByMovingToItAndTypingAgain() {
        val s = ratio(1).press("3 = 8 = 12 = ◀")
        assertEquals("8", s.lines.last())
        s.press("4 =")
        assertEquals("a | b | d\n3 | 4 | 12\na:b=X:d\n12", s.text)
        assertEquals("X=\n9", s.press("=").text)
    }

    /** "Pressing [AC] will clear all of the coefficients to zero" (VERIFIED). */
    @Test
    fun acZeroesTheCoefficients() {
        val s = ratio(1).press("3 = 8 = 12 = AC")
        assertEquals("a | b | d\n0 | 0 | 0\na:b=X:d\n0", s.text)
    }

    /** "Changing the ratio expression type causes the values of all … coefficients to change to zero." */
    @Test
    fun choosingAFormAgainZeroesTheCoefficients() {
        val s = ratio(1).press("3 = 8 = 12 = MODE ▼ 3")
        assertEquals("1:a:b=X:d\n2:a:b=c:X", s.text)
        s.press("1")
        assertEquals("a | b | d\n0 | 0 | 0\na:b=X:d\n0", s.text)
    }

    /** ⚠️ GUESS pinned (RatioMode): `AC` on an answer goes back to the editor, as `=` does. */
    @Test
    fun acOnTheAnswerReturnsToTheEditor() {
        val s = ratio(1).press("3 = 8 = 12 = = AC")
        assertEquals("a | b | d\n3 | 8 | 12\na:b=X:d\n12", s.text)
    }

    /** ⚠️ GUESS pinned (RatioMode): X does not become Ans, and the variables are left alone. */
    @Test
    fun theAnswerDoesNotBecomeAns() {
        val s = Session().press("7 = MODE ▼ 3 1 3 = 8 = 12 = =")
        assertEquals("X=\n9/2", s.text)
        assertEquals(FractionReal(7, 1), s.machine.memory.ans)
    }

    /** "M+, M−, STO … Pol, Rec, ÷R, and multi-statements also cannot be input with the Coefficient Editor." */
    @Test
    fun theKeysTheEditorRefusesDoNothing() {
        val s = ratio(1).press("Pol M+ M− STO : 5")
        assertEquals("a | b | d\n0 | 0 | 0\na:b=X:d\n5", s.text)
        assertEquals(FractionReal(0, 1), s.machine.memory.variables[Variable.M])
        assertFalse(Indicator.STO in s.icons)
    }

    /** Research 03 §2.2 (VERIFIED): RATIO has no mode icon and shows Math. */
    @Test
    fun theEditorShowsMathAndNoModeIcon() {
        assertEquals(setOf(Indicator.DEG, Indicator.MATH), ratio(1).icons)
        assertEquals(setOf(Indicator.DEG), ratio(1).press("SETUP 2").icons)
    }

    @Test
    fun theFormAndCoefficientsSurvivePowerOffAndOn() {
        val s = ratio(2).press("1 = 2 = 10 = OFF ON")
        assertEquals("a | b | c\n1 | 2 | 10\na:b=c:X\n1", s.text)
        val back = Calculator.restore(s.calculator.snapshot()) { fresh() }
        assertEquals(Mode.RATIO, back.machine.mode)
        assertEquals("a | b | c\n1 | 2 | 10\na:b=c:X\n1", back.frame.text)
        assertEquals(s.calculator.snapshot(), back.snapshot())
    }

    /** ⚠️ GUESS pinned (RatioMode.clear): CLR Memory keeps the coefficients, CLR All forgets the form. */
    @Test
    fun clrMemoryKeepsTheCoefficientsAndClrAllForgetsThem() {
        val s = ratio(1).press("3 = 8 = 12 = CLR 2 = AC")
        assertEquals("a | b | d\n3 | 8 | 12\na:b=X:d\n3", s.text)
        assertTrue("ctl.RATIO=" in s.calculator.snapshot())
        s.press("CLR 3 = AC")
        assertFalse("ctl.RATIO=" in s.calculator.snapshot())
    }

    /** A form that was never chosen saves nothing, and a later `MODE ▼ 3` still asks for one. */
    @Test
    fun aCalculatorThatNeverChoseAFormSavesNoRatioState() {
        val s = Session().press("MODE ▼ 3")
        assertFalse("ctl.RATIO=" in s.calculator.snapshot())
        val back = Calculator.restore(s.calculator.snapshot()) { fresh() }
        assertEquals("1:a:b=X:d\n2:a:b=c:X", back.frame.text)
    }

    @Test
    fun unreadableRatioTextGivesAFreshCalculator() {
        val good = ratio(1).press("3 =").calculator.snapshot()
        val line = good.lines().single { it.startsWith("ctl.RATIO=") }
        assertEquals("ctl.RATIO=A_B_X_D F3/1 F0/1 F0/1", line)
        assertEquals(Mode.RATIO, Calculator.restore(good) { fresh() }.machine.mode)
        for (bad in listOf("ctl.RATIO=A_B_X_Q F3/1 F0/1 F0/1", "ctl.RATIO=A_B_X_D F3/1 F0/1", "ctl.RATIO=A_B_X_D")) {
            val back = Calculator.restore(good.replace(line, bad)) { fresh() }
            assertEquals(Mode.COMP, back.machine.mode, bad)
        }
    }

    private fun fresh() = Calculator(FakeEvaluator(), FakeFormatter(), Random(1), StandardModes.registry())
}
