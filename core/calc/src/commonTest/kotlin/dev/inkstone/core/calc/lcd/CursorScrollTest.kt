package dev.inkstone.core.calc.lcd

import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.PathStep
import dev.inkstone.core.calc.input.Template
import dev.inkstone.core.calc.input.TemplateKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cursor rectangles (research 02 §7.2) and horizontal scrolling (research 02 §7.3, §8.1: a full
 * line is 16 characters, a clipped one 15 plus a marker).
 */
class CursorScrollTest {
    private val none = emptySet<Indicator>()

    private fun frame(
        expr: Expr,
        cursor: Cursor?,
        natural: Boolean = true,
        scroll: Int = 0,
        shape: CursorShape = CursorShape.INSERT,
    ) = Screens.calculation(none, expr, cursor, natural, inputScroll = scroll, cursorShape = shape)

    /** The dark dots of one cell (cell column [x], rows [top] .. [top] + 8) as a picture. */
    private fun LcdFrame.cell(x: Int, top: Int = 1): List<String> =
        (top until top + 9).map { y -> (x + 1..x + 5).joinToString("") { if (this[it, y]) "#" else "." } }

    @Test
    fun anEmptyLineHasTheBarAtTheLeftEdgeAsTallAsALargeGlyph() {
        assertEquals(LcdCursor(0, 1, 1, 9), frame(Expr(), Cursor()).cursor)
    }

    @Test
    fun theBarStandsInTheLeadingColumnOfTheNextCell() {
        val expected = listOf(0 to 0, 1 to 6, 2 to 12)
        for ((index, x) in expected) {
            assertEquals(LcdCursor(x, 1, 1, 9), frame(ex("12"), Cursor(index = index)).cursor, "index $index")
        }
    }

    @Test
    fun theBarIsAsTallAsTheSlotItStandsIn() {
        val expr = Expr(listOf(frac("12", "3")))
        assertEquals(LcdCursor(13, 1, 1, 6), frame(expr, Cursor(listOf(PathStep(0, 0)), 2)).cursor)
        assertEquals(LcdCursor(4, 10, 1, 6), frame(expr, Cursor(listOf(PathStep(0, 1)), 0)).cursor)
        assertEquals(LcdCursor(14, 1, 1, 15), frame(expr, Cursor(index = 1)).cursor)
    }

    @Test
    fun anEmptySlotPutsTheBarInsideItsPlaceholder() {
        val expr = Expr(listOf(Template.empty(TemplateKind.FRACTION)))
        assertEquals(LcdCursor(2, 1, 1, 6), frame(expr, Cursor(listOf(PathStep(0, 0)), 0)).cursor)
    }

    @Test
    fun theBlockCursorFillsTheNextCharacterCell() {
        val cursor = frame(ex("12"), Cursor(index = 2), shape = CursorShape.BLOCK).cursor
        assertEquals(LcdCursor(13, 1, 5, 9, block = true), cursor)
    }

    @Test
    fun theOverwriteCursorUnderlinesTheCharacterItReplaces() {
        val cursor = frame(ex("12"), Cursor(index = 1), natural = false, shape = CursorShape.OVERWRITE).cursor
        assertEquals(LcdCursor(7, 10, 5, 1), cursor)
    }

    @Test
    fun aPathThatDoesNotExistHasNoCursor() {
        assertNull(frame(ex("12"), Cursor(listOf(PathStep(0, 0)), 0)).cursor)
        assertNull(frame(ex("12"), Cursor(index = 3)).cursor)
    }

    @Test
    fun sixteenCharactersFillTheLineWithoutAMarker() {
        val frame = frame(ex("1234567890123456"), null)
        assertEquals(Ink(2, 1, 95, 9), frame.ink())
        assertEquals(frame(ex("6"), null).cell(0), frame.cell(90))
    }

    @Test
    fun aLongerLineShowsFifteenCharactersAndTheHollowMarker() {
        val frame = frame(ex("12345678901234567890"), null)
        assertEquals(frame(ex("5"), null).cell(0), frame.cell(84))
        val hollow = LcdCanvas().also { it.text(LcdFont.LARGE, "▷", 0, 1) }.freeze(none, null, "")
        assertEquals(hollow.cell(0), frame.cell(90))
    }

    @Test
    fun theCursorAtTheEndScrollsTheLineAndShowsTheLeftMarker() {
        val long = ex("12345678901234567890")
        val end = Cursor(index = 20)
        val scroll = MathLayout.inputScroll(long, end, natural = true)
        assertEquals(30, scroll)
        val frame = frame(long, end, scroll = scroll)
        assertEquals(LcdCursor(90, 1, 1, 9), frame.cursor)
        val filled = LcdCanvas().also { it.text(LcdFont.LARGE, "◀", 0, 1) }.freeze(none, null, "")
        assertEquals(filled.cell(0), frame.cell(0))
        assertEquals(frame(ex("7"), null).cell(0), frame.cell(6))
    }

    @Test
    fun theScrollStaysWhileTheCursorIsInViewAndFollowsItOut() {
        val long = ex("12345678901234567890")
        assertEquals(30, MathLayout.inputScroll(long, Cursor(index = 19), true, previous = 30))
        assertEquals(30, MathLayout.inputScroll(long, Cursor(index = 6), true, previous = 30))
        assertEquals(12, MathLayout.inputScroll(long, Cursor(index = 3), true, previous = 30))
        assertEquals(0, MathLayout.inputScroll(long, Cursor(index = 1), true, previous = 30))
        assertEquals(0, MathLayout.inputScroll(ex("12"), Cursor(index = 2), true, previous = 30))
    }

    @Test
    fun aWideResultShowsTheFilledMarkerAndScrollsToItsEnd() {
        val wide = ex("12345678901234567890")
        val limit = MathLayout.resultScrollLimit(wide, natural = true)
        assertEquals(24, limit)
        assertEquals(0, MathLayout.resultScrollLimit(ex("1234567890123456"), natural = true))
        val start = Screens.calculation(none, Expr(), null, true, result = wide)
        val filled = LcdCanvas().also { it.text(LcdFont.LARGE, "▶", 0, 22) }.freeze(none, null, "")
        assertEquals(filled.cell(0, 22), start.cell(90, 22))
        val end = Screens.calculation(none, Expr(), null, true, result = wide, resultScroll = limit)
        val back = LcdCanvas().also { it.text(LcdFont.LARGE, "◀", 0, 22) }.freeze(none, null, "")
        assertEquals(back.cell(0, 22), end.cell(0, 22))
        assertEquals(frame(ex("0"), null).cell(0), end.cell(90, 22))
    }

    @Test
    fun aTallInputIsPushedUpToKeepTheCursorInView() {
        val inner = Expr(listOf(frac("1", "2")))
        val middle = Expr(listOf(template(TemplateKind.FRACTION, ex("1"), inner)))
        val tower = Expr(listOf(template(TemplateKind.FRACTION, ex("1"), middle)))
        assertEquals(33, MathLayout.measure(tower, natural = true).height)
        val deep = Cursor(listOf(PathStep(0, 1), PathStep(0, 1), PathStep(0, 1)), 1)
        // Unpushed, the innermost denominator would be rows 28–33; the result owns rows 22–30.
        val cursor = Screens.calculation(none, tower, deep, true, result = ex("5")).cursor
        assertEquals(LcdCursor(9, 16, 1, 6), cursor)
    }
}
