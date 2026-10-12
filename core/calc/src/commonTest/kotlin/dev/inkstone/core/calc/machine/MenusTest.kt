package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.lcd.Indicator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** MODE, MODE 0, hyp, DRG▶, CONST, CONV and the CMPLX/BASE menus (research 02 §4, §10.4–10.5, 03 §3, §5). */
class MenusTest {
    @Test
    fun theModeMenuHasTwoPagesWithTheirArrows() {
        val s = Session().press("MODE")
        assertEquals(PAGE_1, s.text)
        assertTrue(Indicator.DOWN in s.icons)
        assertFalse(Indicator.UP in s.icons)
        s.press("▼")
        assertEquals("1:DIST  2:INEQ\n3:RATIO", s.text)
        assertTrue(Indicator.UP in s.icons)
        assertFalse(Indicator.DOWN in s.icons)
        s.press("▲")
        assertEquals(PAGE_1, s.text)
    }

    @Test
    fun acClosesTheModeMenuLeavingTheLineAsItWas() {
        val s = Session().press("1 + 2 MODE AC")
        assertEquals("1+2", s.text)
        assertNotNull(s.frame.cursor)
        s.press("=")
        assertEquals("3", s.lines[1])
    }

    @Test
    fun choosingAModeEntersItWithItsIcon() {
        val s = Session().press("MODE 2")
        assertEquals(Mode.CMPLX, s.machine.mode)
        assertTrue(Indicator.CMPLX in s.icons)
        assertTrue(Indicator.MATH in s.icons)
        s.press("1 + 2 ENG")
        assertEquals("1+2i", s.text)
    }

    @Test
    fun pageTwoModesAreChosenAfterDown() {
        val s = Session().press("MODE ▼ 3")
        assertEquals(Mode.RATIO, s.machine.mode)
    }

    @Test
    fun unbuiltModesShowAPlaceholderAndKeepTheirIcons() {
        // Only the built-in modes, so STAT, MATRIX and VECTOR are placeholders whichever controllers exist.
        val s = Session(modes = ModeRegistry.standard()).press("MODE 3")
        assertEquals(Mode.STAT, s.machine.mode)
        assertTrue(s.text.startsWith("STAT"))
        assertTrue(Indicator.STAT in s.icons)
        assertFalse(Indicator.MATH in s.icons)
        s.press("1 + = AC")
        assertTrue(s.text.startsWith("STAT"))
        s.press("MODE 6")
        assertTrue(Indicator.MAT in s.icons)
        s.press("MODE 8")
        assertTrue(Indicator.VCT in s.icons)
        s.press("MODE 1")
        assertEquals("", s.text)
    }

    @Test
    fun modeZeroShowsTheAuthenticityScreenAndAcReturnsToTheMenu() {
        val s = Session().press("MODE 0")
        assertTrue(s.text.startsWith("Calculator ID:"))
        assertEquals(24, s.lines.drop(1).joinToString("").length)
        s.press("AC")
        assertEquals(PAGE_1, s.text)
    }

    @Test
    fun hypMenuTypesTheChosenFunction() {
        val s = Session().press("hyp")
        assertEquals("1:sinh  2:cosh\n3:tanh  4:sinh⁻¹\n5:cosh⁻¹  6:tanh⁻¹", s.text)
        s.press("5")
        assertEquals("cosh⁻¹(", s.text)
    }

    @Test
    fun drgMenuAppendsAnAngleMarkAfterAns() {
        val s = Session().press("90 = DRG")
        assertEquals("1:°  2:ʳ\n3:ᵍ", s.text)
        s.press("2")
        assertEquals("Ansʳ", s.text)
    }

    @Test
    fun aMenuIgnoresDigitsThatAreNotItems() {
        val s = Session().press("DRG 7")
        assertEquals("1:°  2:ʳ\n3:ᵍ", s.text)
        s.press("AC")
        assertEquals("", s.text)
    }

    @Test
    fun constTakesTwoDigitsAndInsertsTheSymbol() {
        val s = Session().press("2 CONST")
        assertEquals("CONSTANT\nNumber 01~40?\n[__]", s.text)
        s.press("2")
        assertEquals("CONSTANT\nNumber 01~40?\n[2_]", s.text)
        s.press("8")
        assertEquals("2C0", s.text)
    }

    @Test
    fun convAfterAResultStartsWithAns() {
        val s = Session().press("5 = CONV 02")
        assertEquals("Anscm▶in", s.text)
    }

    @Test
    fun acCancelsConstLeavingTheLine() {
        val s = Session().press("7 CONST 1 AC")
        assertEquals("7", s.text)
    }

    @Test
    fun cmplxMenuTypesItsItems() {
        val s = Session().press("MODE 2 SHIFT 2")
        assertEquals("1:arg  2:Conjg\n3:▶r∠θ  4:▶a+bi", s.text)
        s.press("3")
        assertEquals("▶r∠θ", s.text)
        s.press("SHIFT (−)")
        assertEquals("▶r∠θ∠", s.text)
    }

    @Test
    fun theModeMenusOfOtherModesDoNothingInComp() {
        val s = Session().press("SHIFT 2 SHIFT 3 SHIFT 4 SHIFT 5 SHIFT 1")
        assertEquals("", s.text)
        assertEquals(setOf(Indicator.DEG, Indicator.MATH), s.icons)
    }

    @Test
    fun baseNShowsItsLabelAndSwitchesBase() {
        val s = Session().press("MODE 4")
        assertEquals(listOf("", "Dec", "0"), s.lines)
        assertFalse(Indicator.MATH in s.icons)
        s.press("x^")
        assertEquals(listOf("", "Hex", "0"), s.lines)
        s.press("1 (−) sin")
        assertEquals(listOf("1AD", "Hex"), s.lines)
        s.press("AC 255 = x²")
        assertEquals(listOf("255", "Dec", "255"), s.lines)
        s.press("x^")
        assertEquals(listOf("255", "Hex", "FF"), s.lines)
    }

    @Test
    fun baseNMenuHasTwoPages() {
        val s = Session().press("MODE 4 SHIFT 3")
        assertEquals("1:and  2:or\n3:xor  4:xnor\n5:Not  6:Neg", s.text)
        s.press("▼")
        assertEquals("1:d  2:h\n3:b  4:o", s.text)
        s.press("2")
        assertEquals("h", s.lines[0])
    }

    @Test
    fun baseNStartsInDecimalWheneverItIsEntered() {
        val s = Session().press("MODE 4 x^ MODE 4")
        assertEquals("Dec", s.lines[1])
    }

    private companion object {
        const val PAGE_1 = "1:COMP  2:CMPLX\n3:STAT  4:BASE-N\n5:EQN  6:MATRIX\n7:TABLE  8:VECTOR"
    }
}
