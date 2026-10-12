package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.Calculator
import dev.inkstone.core.calc.input.InputState
import dev.inkstone.core.calc.input.TemplateKind
import dev.inkstone.core.calc.lcd.Indicator
import dev.inkstone.core.calc.lcd.MathLayout
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.FractionReal
import dev.inkstone.core.calc.value.Real
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Defects found by the second pass of the key machine's review, each pinned by the key sequence or
 * text that showed it ([ReviewRegressionTest] holds the first pass's).
 */
class ReviewSecondPassTest {
    /**
     * Research 02 §11 (VERIFIED): `AC` on an error screen "clears the message and the expression".
     * An error raised by a CALC run is no exception — the line's own errors, and SOLVE's Variable
     * ERROR from the same key, already clear it.
     */
    @Test
    fun acOnACalcRunErrorClearsTheExpression() {
        val s = Session().press("1 ÷ A CALC 0 =")
        assertEquals("Math ERROR", s.lines[0])
        s.press("AC")
        assertEquals("", s.text)
        assertNotNull(s.frame.cursor)
        s.press("=")
        assertEquals("", s.text)
    }

    @Test
    fun acOnCantSolveClearsTheExpressionAsVariableErrorDoes() {
        val s = Session().press("X − X EQ 1 SOLVE =")
        assertEquals("Can't Solve", s.lines[0])
        s.press("AC")
        assertEquals("", s.text)
        s.press("A EQ 1 SOLVE")
        assertEquals("Variable ERROR", s.lines[0])
        s.press("AC")
        assertEquals("", s.text)
    }

    /** Goto from a run error still edits the expression, at the error's place (research 02 §11). */
    @Test
    fun gotoFromACalcRunErrorEditsTheExpression() {
        val s = Session().press("1 ÷ A CALC 0 = ▶")
        assertEquals("1÷A", s.text)
        assertNotNull(s.frame.cursor)
    }

    /**
     * A SETUP change redraws the result in a new presentation, whose width is not the old one's: an
     * ENG display scrolled to its end, redrawn in its standard form, must not keep an offset past
     * the new form's end (blank cells where the digits should be). It starts from its beginning, as
     * every restyled result does.
     */
    @Test
    fun aResultRedrawnBySetupDropsTheOldScrollOffset() {
        val scrolled = Session().press("1234567890123456789 = ENG")
        repeat(SCROLLS) { scrolled.press("▶") }
        scrolled.press("SETUP 4")
        val fresh = Session().press("SETUP 4 1234567890123456789 =")
        assertEquals(fresh.text, scrolled.text)
        assertTrue(fresh.frame.pixels.contentEquals(scrolled.frame.pixels))
    }

    /**
     * Values past the record that holds them (docs/CALCULATOR.md §3, research 07 §4.1): a fraction
     * of more than 14 digits, a surd term outside `|c| < 100, q < 100, r < 1000`, or one that is not
     * in the canonical form arithmetic produces (reduced, squarefree, distinct radicands, at least
     * one irrational term). The value layer relies on those limits — `Roots` assumes `n·d < 10¹⁴`,
     * the surd decimal narrows a term to `Int` — so a snapshot must not smuggle one in.
     */
    @Test
    fun valuesPastTheirRecordsAreRefused() {
        val bad = listOf(
            "F12345678901234/1",
            "F1/123456789012345",
            "F-9876543210987/10",
            "S100/1/2@1",
            "S-100/1/2@1",
            "S1/100/2@1",
            "S1/1/1000@1",
            "S1/1/1@1",
            "S0/1/2@0",
            "S2/4/3@1",
            "S1/1/8@1",
            "S1/1/2,1/1/2@1",
        )
        for (text in bad) assertNull(ValueCodec.decode(text), "<$text>")
        val edges = listOf("F1234567890123/1", "F-1234567/2345678", "S99/1/2@1", "S1/99/997@1", "S-1/1/1,1/2/3@1")
        for (text in edges) assertEquals(text, ValueCodec.decode(text)?.let(ValueCodec::encode), text)
    }

    /** The nine variables hold numbers (research 02 §9); a matrix there is unreadable text. */
    @Test
    fun aSnapshotWithAMatrixInAVariableGivesAFreshCalculator() {
        fun fresh() = Calculator(FakeEvaluator(), FakeFormatter(), Random(1))
        val good = fresh().snapshot()
        for (word in listOf("M 1 1 F1/1", "V 2 F1/1 F2/1")) {
            val bad = good.replace("var.A=F0/1", "var.A=$word")
            assertTrue(bad != good)
            assertEquals(good, Calculator.restore(bad) { fresh() }.snapshot(), word)
        }
    }

    /*
     * Coverage: each test below pins behaviour that a mutation of the machine left green before it
     * was written (the mutation is named in the KDoc).
     */

    /** Research 02 §6: ALPHA is a one-key latch, and pressing it again cancels it (as SHIFT does). */
    @Test
    fun alphaTwiceCancels() {
        val s = Session().press("ALPHA ALPHA")
        assertFalse(Indicator.ALPHA in s.icons)
        s.press("(−)")
        assertEquals("-", s.text)
    }

    /**
     * Research 04 H-06 (SA s.14, VERIFIED): `Solve for X` offers the current X, and that — or the
     * value typed over it — is where Newton's method starts. (Mutation: always start from 0.)
     */
    @Test
    fun solveStartsFromTheValueItOffers() {
        val s = Session().press("3 X + 4 EQ 2 X + 6 SOLVE = AC AC X + 1 EQ 5 SOLVE = AC AC X + 1 EQ 5 SOLVE 7 =")
        assertEquals(listOf<Real>(FractionReal(0, 1), FractionReal(2, 1), FractionReal(7, 1)), s.evaluator.starts)
    }

    /** ⚠️ GUESS pinned (CalculationMemory.store): STO on an empty line stores Ans, shown as `Ans`. */
    @Test
    fun stoOnAnEmptyLineStoresAns() {
        val s = Session().press("7 = AC STO (−)")
        assertEquals(FractionReal(7, 1), s.machine.memory.variables[Variable.A])
        assertEquals(listOf("Ans", "7"), s.lines)
    }

    /**
     * Research 02 §10.14 (VERIFIED): CALC takes a multi-statement; each `=` shows the next one,
     * with Disp, and `=` after the last starts the prompts again. (Mutation: always restart.)
     */
    @Test
    fun calcRunsAMultiStatementOnePerEquals() {
        val s = Session().press("X + Y : X × Y CALC 2 = 3 =")
        assertEquals(listOf("X+Y:X×Y", "5"), s.lines)
        assertTrue(Indicator.DISP in s.icons)
        s.press("=")
        assertEquals("6", s.lines[1])
        assertFalse(Indicator.DISP in s.icons)
        s.press("=")
        assertEquals(listOf("X?", "2"), s.lines)
    }

    /** CONST and CONV number from 01: `00` is a Syntax ERROR, as 41 is (LineMenus.number). */
    @Test
    fun constOrConvZeroZeroIsASyntaxError() {
        assertEquals("Syntax ERROR", Session().press("CONST 00").lines[0])
        assertEquals("Syntax ERROR", Session().press("5 CONV 00").lines[0])
    }

    /** ⚠️ GUESS pinned (EquationRun.solvable): SOLVE refuses a multi-statement as a Syntax ERROR. */
    @Test
    fun solveRefusesAMultiStatement() {
        assertEquals("Syntax ERROR", Session().press("X : X SOLVE").lines[0])
    }

    /**
     * CMPLX lines are parsed as CMPLX, where "each input value uses two levels of the numeric
     * stack" (research 02 [S570] p.E-67, INFERRED): a nesting COMP takes is a Stack ERROR there, on
     * the line and at a CALC prompt alike. (Mutations: parse every line, or every prompt, as COMP.)
     */
    @Test
    fun cmplxLinesAndPromptsAreParsedWithItsStackDepth() {
        val nested = "1 + ( 1 + ( 1 + ( 1 + ( 1 + ( 1"
        assertTrue(Session().press("$nested =").lines[0] != STACK)
        assertEquals(STACK, Session().press("MODE 2 $nested =").lines[0])
        assertEquals(STACK, Session().press("MODE 2 A CALC $nested =").lines[0])
    }

    /**
     * Research 02 §7.2 (VERIFIED): "up to two screens tall (31 dots × 2). Beyond that, input is
     * refused" — so exactly 62 dots (six nested fractions and a √) is still taken, and the key that
     * would pass it is not. (Mutation: refuse at 62.)
     */
    @Test
    fun naturalInputExactlyTwoScreensTallIsTaken() {
        var state = InputState.empty(natural = true)
        repeat(NESTED_FRACTIONS) { state = LineEditor.apply(state, Action.Open(TemplateKind.FRACTION)) }
        val atLimit = LineEditor.apply(state, Action.Open(TemplateKind.SQRT))
        assertNotSame(state, atLimit)
        assertEquals(LineEditor.MAX_NATURAL_HEIGHT, MathLayout.measure(atLimit.expr, natural = true).height)
        assertSame(atLimit, LineEditor.apply(atLimit, Action.Open(TemplateKind.SQRT)))
    }

    private companion object {
        const val NESTED_FRACTIONS = 6

        /** More `▶` than any of these results can scroll; the extra ones stop at the end. */
        const val SCROLLS = 8
        const val STACK = "Stack ERROR"
    }
}
