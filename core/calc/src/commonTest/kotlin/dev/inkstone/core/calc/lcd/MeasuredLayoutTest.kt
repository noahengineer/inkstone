package dev.inkstone.core.calc.lcd

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.Tok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pixel layout research 01 §3.6 measured on the SA handout's emulator captures, reproduced.
 *
 * Columns are the re-measured ones of [LcdGeometry]: one to the right of §3.6's figures, because
 * the captures' inked extent is exactly 96 dots from the MODE menu's number cell to a result's
 * last digit. Rows and every relative measurement are §3.6's own.
 */
class MeasuredLayoutTest {
    private val none = emptySet<Indicator>()

    private fun input(expr: Expr, cursor: Cursor? = null) = Screens.calculation(none, expr, cursor, natural = true)

    private fun result(value: Expr) = Screens.calculation(none, Expr(), null, natural = true, result = value)

    @Test
    fun aFractionIsNumeratorGapBarGapDenominator() {
        val frame = input(Expr(listOf(frac("2", "3"))))
        assertEquals(TWO_THIRDS, frame.art())
        assertEquals(Ink(2, 1, 6, 6), frame.ink(rows = 0..7))
        assertEquals((1..7).toList(), frame.darkColumns(8))
        assertEquals(Ink(2, 10, 6, 15), frame.ink(rows = 9..30))
        assertTrue(frame.darkColumns(7).isEmpty() && frame.darkColumns(9).isEmpty())
        assertEquals("2/3", frame.text)
    }

    @Test
    fun theBarIsTheWiderOperandPlusOneDotEachSideAndOperandsAreCentred() {
        val frame = input(Expr(listOf(frac("1", "800"))))
        assertEquals((1..19).toList(), frame.darkColumns(8))
        assertEquals(Ink(2, 10, 18, 15), frame.ink(rows = 9..30))
        val numerator = frame.ink(rows = 0..7)
        assertTrue(numerator != null && numerator.left >= 8 && numerator.right <= 12, "numerator at $numerator")
    }

    @Test
    fun aSuperscriptDropsTheLineToRows3To11() {
        val frame = input(ex("3", Tok.VAR_A, Tok.SQUARE, "+4", Tok.VAR_B))
        assertEquals(3..11, frame.darkRows(1..5).let { it.first()..it.last() })
        assertEquals(3..11, frame.darkRows(7..11).let { it.first()..it.last() })
        // SA slide 12: the 2 on columns 13–17, straight after the A's cell (see CaptureLayoutTest).
        val exponent = frame.ink(columns = 12..18)
        assertEquals(Ink(13, 1, 17, 6), exponent)
        assertEquals(3..11, frame.darkRows(25..35).let { it.first()..it.last() })
    }

    @Test
    fun resultGlyphsSitOnRows22To30AndEndOnTheLastColumn() {
        val frame = result(ex("33,33333333"))
        assertEquals(Ink(31, 22, 95, 30), frame.ink())
        val first = input(ex("80"))
        assertEquals(1, first.ink()?.left)
    }

    @Test
    fun theNegativeSignIsAThreeDotBarOnTheFifthRow() {
        val frame = result(ex("-6"))
        val cell = (84..89).flatMap { x -> (22..30).filter { y -> frame[x, y] }.map { y -> x to y } }
        assertEquals(listOf(86 to 26, 87 to 26, 88 to 26), cell)
        val minus = result(ex("6−6"))
        assertEquals((85..89).toList(), minus.darkColumns(26).filter { it in 84..89 })
    }

    @Test
    fun theDecimalCommaHasTheMeasuredShape() {
        val frame = result(ex("0,5"))
        val comma = (84..89).flatMap { x -> (22..30).filter { y -> frame[x, y] }.map { y -> x to y } }
        assertEquals(setOf(86 to 27, 87 to 27, 86 to 28, 87 to 28, 87 to 29, 86 to 30), comma.toSet())
    }

    @Test
    fun aFractionResultIsBottomAlignedWithItsBarEndingOnTheLastColumn() {
        val frame = result(Expr(listOf(frac("1", "200"))))
        assertEquals((77..95).toList(), frame.darkColumns(23))
        assertEquals(Ink(78, 25, 94, 30), frame.ink(rows = 24..30))
        assertEquals(16..21, frame.darkRows(76..95).filter { it < 23 }.let { it.first()..it.last() })
        assertEquals("\n1/200", frame.text)
    }

    @Test
    fun aSubscriptSitsOnTheLargeGlyphsBaseline() {
        val frame = Screens.labelValue(none, "X₁=", ex("-2"))
        assertEquals(Ink(1, 1, 5, 9), frame.ink(columns = 0..5, rows = 0..10))
        assertEquals(Ink(8, 4, 10, 9), frame.ink(columns = 6..11, rows = 0..10))
        assertEquals(listOf(13, 14, 15, 16, 17), frame.darkColumns(4).filter { it in 12..17 })
    }

    @Test
    fun modeMenuPageOne() {
        val items = listOf("COMP", "CMPLX", "STAT", "BASE-N", "EQN", "MATRIX", "TABLE", "VECTOR")
            .mapIndexed { i, label -> MenuItem(i + 1, label) }
        val frame = Screens.menu(none, items, MenuStyle.MODE, arrows = PageArrows.DOWN)
        assertEquals(MODE_PAGE_1, frame.art())
        assertEquals(setOf(Indicator.DOWN), frame.indicators)
        assertEquals(Ink(9, 0, 13, 6), frame.ink(columns = 8..13, rows = 0..7))
        assertEquals(Ink(48, 0, 54, 6), frame.ink(columns = 48..54, rows = 0..7))
    }

    @Test
    fun setupPageOne() {
        val items = listOf("MthIO", "LineIO", "Deg", "Rad", "Gra", "Fix", "Sci", "Norm")
            .mapIndexed { i, label -> MenuItem(i + 1, label) }
        val frame = Screens.menu(none, items, arrows = PageArrows.DOWN)
        assertEquals(SETUP_PAGE_1, frame.art())
        for (band in 0..3) {
            val inBand = frame.darkRows(0..47).filter { it in band * 8..band * 8 + 7 }
            assertEquals(band * 8 + 1..band * 8 + 6, inBand.first()..inBand.last(), "band $band")
        }
        assertEquals(49, frame.ink(columns = 48..95, rows = 0..7)?.left)
    }

    @Test
    fun theMathErrorScreen() {
        val frame = Screens.error(setOf(Indicator.DEG, Indicator.MATH), CalcError.MATH)
        assertEquals(MATH_ERROR, frame.art())
        assertEquals("Math ERROR\n[AC]  :Cancel\n[◀][▶]:Goto", frame.text)
        assertEquals(setOf(Indicator.DEG, Indicator.MATH), frame.indicators)
    }

    @Test
    fun theCoefficientEditorMatchesTheEqnCapture() {
        val zeros = listOf("0", "0", "0")
        val rows = listOf(GridRow(1, zeros), GridRow(2, zeros))
        val spec = GridSpec(listOf("a", "b", "c"), rows, GridCell(0, 0), "0", brackets = true)
        val frame = Screens.grid(none, spec)
        assertEquals(EQN_EDITOR, frame.art())
        assertEquals(Ink(21, 3, 23, 5), frame.ink(columns = 19..25, rows = 0..5))
        assertEquals(Ink(49, 1, 51, 5), frame.ink(columns = 47..53, rows = 0..5))
        assertEquals(Ink(61, 7, 63, 11), frame.ink(columns = 40..64, rows = 7..11))
        assertEquals((10..35).toList() + listOf(93, 94), frame.darkColumns(6))
        assertTrue(!frame[33, 7] && !frame[35, 9] && frame[34, 9], "the light 0 in the inverted cell")
        assertEquals(Ink(91, 25, 95, 30), frame.ink(rows = 24..30))
    }
}
