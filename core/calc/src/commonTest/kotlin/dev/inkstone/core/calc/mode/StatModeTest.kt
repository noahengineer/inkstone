package dev.inkstone.core.calc.mode

import dev.inkstone.core.calc.Calculator
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.lcd.Indicator
import dev.inkstone.core.calc.machine.Session
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.FractionReal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Types [keys] (research 05's notation, as [Session.keys] reads it) on this calculator. */
private fun Calculator.type(keys: String): Calculator = apply { Session.keys(keys).forEach { press(it) } }

private val Calculator.text: String get() = frame.text

/**
 * STAT through the keys (research 03 §4), on the public `Calculator()` with the real evaluator and
 * formatter. The guide's own examples are `e2e.GuideStatExamplesTest`; these pin the rest of the
 * mode — the editor's rules, the menus, what deletes the data, persistence — and every ⚠️ GUESS
 * [StatMode] and [StatEditor] make is named in the test that pins it.
 */
class StatModeTest {
    private fun stat(type: Int, setup: String = ""): Calculator = Calculator().type("$setup MODE 3 $type")

    @Test
    fun theModeIsLinearWithItsIconAndAcOnTheEntryMenuDoesNothing() {
        val c = Calculator().type("MODE 3")
        assertEquals(Mode.STAT, c.machine.mode)
        assertEquals(setOf(Indicator.STAT, Indicator.DEG), c.frame.indicators)
        c.type("AC")
        assertTrue(c.text.startsWith("1:1-VAR"), "⚠️ GUESS: nothing under the entry menu to return to")
    }

    @Test
    fun theEditorShowsTheColumnsTheTypeAndSetupAskFor() {
        assertEquals("X\n1: \n2: \n3: \n", stat(1).text)
        assertEquals("X | FREQ\n1:  | \n2:  | \n3:  | \n", stat(1, "SETUP ▼ 4 1").text)
        assertEquals("X | Y | FREQ\n1:  |  | \n2:  |  | \n3:  |  | \n", stat(8, "SETUP ▼ 4 1").text)
    }

    /** "Typing a value and pressing = stores it and moves down one row" — the empty row moves with it. */
    @Test
    fun equalsStoresAndMovesDownAndTheViewFollows() {
        val c = stat(1).type("5 =")
        assertEquals("X\n1: 5\n2: \n3: \n", c.text)
        c.type("6 = 7 = 8 =")
        assertEquals("X\n3: 7\n4: 8\n5: \n", c.text)
        c.type("▲")
        assertEquals("X\n3: 7\n4: 8\n5: \n8", c.text)
    }

    /** Research 03 §4.1 (INFERRED) `▼` from the last row wraps to row 1; ⚠️ GUESS `▲` from row 1 wraps back. */
    @Test
    fun downFromTheLastRowWrapsToRowOneAndUpWrapsBack() {
        val c = stat(1).type("5 = 6 = 7 = 8 = ▼")
        assertEquals("X\n1: 5\n2: 6\n3: 7\n5", c.text)
        c.type("▲")
        assertEquals("X\n3: 7\n4: 8\n5: \n", c.text)
    }

    /** "Once X values exist, the untouched cells of the Y column show 0" — and ⚠️ GUESS a Y alone gives X 0. */
    @Test
    fun aValueTypedIntoAnyCellOfTheEmptyRowMakesItARow() {
        val c = stat(2).type("4 = ▶ 9 =")
        assertEquals("X | Y\n1: 4 | 0\n2: 0 | 9\n3:  | \n", c.text)
    }

    @Test
    fun delDeletesTheRowUnderTheCursorAndNothingOnTheEmptyRow() {
        val c = stat(1).type("5 = 6 = 7 = ▲ ▲ DEL")
        assertEquals("X\n2: 7\n3: \n4: \n7", c.text, "the view stays where it was")
        c.type("▲")
        assertEquals("X\n1: 5\n2: 7\n3: \n5", c.text)
        c.type("▼ ▼ DEL")
        assertEquals("X\n1: 5\n2: 7\n3: \n", c.text, "⚠️ GUESS: DEL on the empty row does nothing")
        c.type("8 DEL")
        assertEquals("X\n1: 5\n2: 7\n3: \n", c.text, "DEL while typing edits what is typed")
    }

    /** `SHIFT 1 3 1` Ins and `SHIFT 1 3 2` Del-A (research 03 §4.1); the editor's menu shape is ⚠️ GUESS. */
    @Test
    fun theEditorsMenuHasEditWithInsAndDelA() {
        val c = stat(1).type("5 = 6 = ▲ SHIFT 1")
        assertEquals("1:Type  2:Data\n3:Edit  4:Sum\n5:Var  6:Distr\n7:MinMax", c.text)
        c.type("3")
        assertEquals("1:Ins  2:Del-A", c.text)
        c.type("1")
        assertEquals("X\n1: 5\n2: 0\n3: 6\n0", c.text)
        c.type("SHIFT 1 3 2")
        assertEquals("X\n1: \n2: \n3: \n", c.text)
        assertEquals("1:Type  2:Data\n3:Edit  4:Sum\n5:Var  6:Reg\n7:MinMax", stat(2).type("SHIFT 1").text)
    }

    @Test
    fun acGoesToTheCalculationScreenAndDataComesBack() {
        val c = stat(1).type("5 = 6 = AC")
        assertEquals("", c.text)
        assertEquals(setOf(Indicator.STAT, Indicator.DEG), c.frame.indicators)
        c.type("SHIFT 1 2")
        assertEquals("X\n1: 5\n2: 6\n3: \n5", c.text)
    }

    /** Research 03 §4.2 and 06 §6.4: the paired menus and every sub-menu's items. */
    @Test
    fun theCalculationScreensMenusForEachKindOfType() {
        val paired = stat(2).type("AC SHIFT 1")
        assertEquals("1:Type  2:Data\n3:Sum  4:Var\n5:Reg  6:MinMax", paired.text)
        val sums = "1:Σx²  2:Σx\n3:Σy²  4:Σy\n5:Σxy  6:Σx³\n7:Σx²y  8:Σx⁴"
        assertEquals(sums, stat(2).type("AC SHIFT 1 3").text)
        assertEquals("1:n  2:x̄\n3:σx  4:sx\n5:ȳ  6:σy\n7:sy", stat(2).type("AC SHIFT 1 4").text)
        assertEquals("1:A  2:B\n3:r  4:x̂\n5:ŷ", stat(2).type("AC SHIFT 1 5").text)
        assertEquals("1:A  2:B\n3:C  4:x̂₁\n5:x̂₂  6:ŷ", stat(3).type("AC SHIFT 1 5").text)
        assertEquals("1:minX  2:maxX\n3:minY  4:maxY", stat(2).type("AC SHIFT 1 6").text)
        assertEquals("1:Σx²  2:Σx", stat(1).type("AC SHIFT 1 3").text)
        assertEquals("1:n  2:x̄\n3:σx  4:sx", stat(1).type("AC SHIFT 1 4").text)
        assertEquals("1:P(  2:Q(\n3:R(  4:▶t", stat(1).type("AC SHIFT 1 5").text)
    }

    @Test
    fun aSubMenuTypesItsItemAndAcClosesAMenu() {
        val c = stat(2).type("AC 2 + SHIFT 1 4 5")
        assertEquals("2+ȳ", c.text)
        c.type("SHIFT 1 AC")
        assertEquals("2+ȳ", c.text)
    }

    /** "Changing between paired types keeps the data" (EX-STAT-03); 1-VAR ⇔ paired deletes it (VERIFIED). */
    @Test
    fun changingTypeKeepsPairedDataAndDeletesItAcrossKinds() {
        val c = stat(2).type("1 = 2 = ▼ ▶ 3 = 5 = AC SHIFT 1 1 7")
        assertEquals("X | Y\n1: 1 | 3\n2: 2 | 5\n3:  | \n1", c.text)
        c.type("AC SHIFT 1 1 1")
        assertEquals("X\n1: \n2: \n3: \n", c.text)
        c.type("AC SHIFT 1 1 AC")
        assertEquals("", c.text, "the type menu from SHIFT 1 closes back to the line")
    }

    /** "Changing the Stat Format setting deletes all Statistics Editor data" (research 03 §2.3, VERIFIED). */
    @Test
    fun changingTheFreqSettingDeletesTheData() {
        val c = stat(1).type("5 = 6 = SETUP ▼ 4 1")
        assertEquals("X | FREQ\n1:  | \n2:  | \n3:  | \n", c.text)
        c.type("7 = SETUP ▼ 4 1")
        assertEquals("X | FREQ\n1: 7 | 1\n2:  | \n3:  | \n", c.text, "the same setting again changes nothing")
        c.type("SETUP ▼ 4 2")
        assertEquals("X\n1: \n2: \n3: \n", c.text)
    }

    @Test
    fun leavingStatDeletesTheData() {
        val c = stat(1).type("5 = 6 = MODE 1 MODE 3 1")
        assertEquals("X\n1: \n2: \n3: \n", c.text)
    }

    @Test
    fun theTypeMenuFromTheEditorClosesBackToItAndOnBeforeAnyTypeShowsTheMenu() {
        val c = stat(1).type("5 = SHIFT 1 1")
        assertEquals(TYPE_MENU, c.text)
        c.type("AC")
        assertEquals("X\n1: 5\n2: \n3: \n", c.text)
        assertEquals(TYPE_MENU, Calculator().type("MODE 3 ON").text)
    }

    /** The guide's row capacities as printed (research 03 §4.1, 00 C11): 40, 40, 20 and 26. */
    @Test
    fun eachLayoutHoldsTheGuidesNumberOfRows() {
        assertEquals(40, filled(stat(1)))
        assertEquals(40, filled(stat(2)))
        assertEquals(20, filled(stat(1, "SETUP ▼ 4 1")))
        assertEquals(26, filled(stat(2, "SETUP ▼ 4 1")))
    }

    /** Types `1 =` until the cursor stops moving down, then counts the rows with n. */
    private fun filled(c: Calculator): Int {
        repeat(MORE_THAN_ANY_CAPACITY) { c.type("1 =") }
        val bottom = c.text.lines()[3]
        assertFalse(bottom.endsWith(": "), "a full table has no empty row: $bottom")
        c.type("AC SHIFT 1 4 1 =")
        return c.text.lines()[1].toInt()
    }

    @Test
    fun statisticsOfTheDataAreTheLinesAnswers() {
        val c = stat(1).type("2 = 4 = 9 = AC SHIFT 1 4 2 =")
        assertEquals("x̄\n5", c.text)
        assertEquals("Math ERROR", stat(1).type("AC SHIFT 1 4 2 =").text.lines()[0], "no data: x̄ divides by 0")
        assertEquals("n\n0", stat(1).type("AC SHIFT 1 4 1 =").text, "⚠️ GUESS: n of no data is 0")
    }

    /** ⚠️ GUESS pinned (StatEditor): a statistic typed into a cell is worked out over the data as it stands. */
    @Test
    fun aStatisticTypedIntoACellUsesTheData() {
        val c = stat(1).type("1 = 3 = SHIFT 1 5 2")
        assertEquals("x̄", c.text.lines().last())
        c.type("=")
        assertEquals("X\n2: 3\n3: 2\n4: \n", c.text)
    }

    /** "These cannot be used inside an editor cell": M+, M−, STO, Pol, Rec, ÷R, `:` (research 03 §2.4). */
    @Test
    fun theKeysTheEditorRefusesDoNothing() {
        val c = stat(1).type("Pol M+ M− STO : 5")
        assertEquals("X\n1: \n2: \n3: \n5", c.text)
        assertEquals(FractionReal(0, 1), c.machine.memory.variables[Variable.M])
        assertFalse(Indicator.STO in c.frame.indicators)
    }

    @Test
    fun anErrorInACellShowsOverTheEditorAndAcDropsWhatWasTyped() {
        val c = stat(1).type("1 ÷ 0 =")
        assertEquals("Math ERROR", c.text.lines()[0])
        c.type("AC")
        assertEquals("X\n1: \n2: \n3: \n", c.text)
        c.type("1 ÷ 0 = ◀")
        assertEquals("1÷0", c.text.lines().last())
    }

    /** ⚠️ GUESS pinned (StatEditor): a bare `=` does nothing; `AC` while typing leaves for the line. */
    @Test
    fun aBareEqualsDoesNothingAndAcWhileTypingLeaves() {
        val c = stat(1).type("5 = =")
        assertEquals("X\n1: 5\n2: \n3: \n", c.text)
        c.type("7 AC")
        assertEquals("", c.text)
        assertEquals("X\n1: 5\n2: \n3: \n5", c.type("SHIFT 1 2").text)
    }

    @Test
    fun onReturnsToTheEditorWithTheDataAndTheTypeSurvivesPowerOff() {
        val c = stat(2).type("1 = 2 = ▼ ▶ 3 = 5 = AC OFF ON")
        assertEquals("X | Y\n1: 1 | 3\n2: 2 | 5\n3:  | \n1", c.text)
        val back = Calculator.restore(c.snapshot())
        assertEquals(Mode.STAT, back.machine.mode)
        assertEquals(c.text, back.text)
        assertEquals(c.snapshot(), back.snapshot())
        assertEquals("r\n1", back.type("AC SHIFT 1 5 3 =").text)
    }

    /** ⚠️ GUESS pinned (StatMode.clear): CLR Memory keeps the data, CLR All forgets it. */
    @Test
    fun clrMemoryKeepsTheDataAndClrAllForgetsIt() {
        val c = stat(1).type("5 = 6 = CLR 2 = AC")
        assertEquals("X\n1: 5\n2: 6\n3: \n5", c.text)
        assertTrue("ctl.STAT=" in c.snapshot())
        c.type("CLR 3 = AC")
        assertFalse("ctl.STAT=" in c.snapshot())
        assertEquals(Mode.COMP, c.machine.mode)
    }

    @Test
    fun unreadableStatTextGivesAFreshCalculator() {
        val good = stat(1).type("5 =").snapshot()
        val line = good.lines().single { it.startsWith("ctl.STAT=") }
        assertTrue(line.startsWith("ctl.STAT=ONE_VAR 0 1 F5/1 F0/1 F1/1 "), line)
        assertEquals(Mode.STAT, Calculator.restore(good).machine.mode)
        val tooMany = "ctl.STAT=ONE_VAR 0 41 " + line.substringAfter("ONE_VAR 0 1 ")
        val bad = listOf("ctl.STAT=TWO_VAR 0 1", "ctl.STAT=ONE_VAR 2 1", "ctl.STAT=ONE_VAR 0 1 F5/1", tooMany)
        for (text in bad) {
            assertEquals(Mode.COMP, Calculator.restore(good.replace(line, text)).machine.mode, text)
        }
    }

    /** Rows saved under the other FREQ setting cannot be shown in this one, so [StatMode.home] drops them. */
    @Test
    fun rowsSavedUnderTheOtherFreqSettingAreDropped() {
        val saved = stat(1, "SETUP ▼ 4 1").type("5 = 6 =").snapshot()
        val back = Calculator.restore(saved.replace("frequency=true", "frequency=false"))
        assertEquals("X\n1: \n2: \n3: \n", back.text)
    }

    /** The quadratic's estimates through the keys: `20 x̂₁` and `20 x̂₂` (StatStatisticsTest has the values). */
    @Test
    fun theQuadraticsEstimatesAreTypedAfterTheirValue() {
        val data = "29 = 50 = 74 = 103 = 118 = ▼ ▶ 1 , 6 = 23 , 5 = 38 , 0 = 46 , 4 = 48 , 9 ="
        val c = stat(3).type("$data AC 20 SHIFT 1 5 4 =")
        assertEquals("20x̂₁\n47,27156322", c.text)
        assertEquals("20x̂₂\n179,4521592", c.type("AC 20 SHIFT 1 5 5 =").text)
    }

    private companion object {
        const val MORE_THAN_ANY_CAPACITY = 45
        const val TYPE_MENU = "1:1-VAR  2:A+BX\n3:_+CX²  4:ln X\n5:e^X  6:A•B^X\n7:A•X^B  8:1/X"
    }
}
