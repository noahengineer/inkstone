package dev.inkstone.core.calc.lcd

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.TemplateKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The composition API: where each screen puts its parts, and what [LcdFrame.text] says. */
class ScreensTest {
    private val none = emptySet<Indicator>()

    @Test
    fun theCalculationScreenReportsInputAndResultAsText() {
        val frame = Screens.calculation(none, ex("1÷200"), null, true, result = Expr(listOf(frac("1", "200"))))
        assertEquals("1÷200\n1/200", frame.text)
        assertNull(frame.cursor)
    }

    @Test
    fun aPrefixIsDrawnBeforeTheInputAndTheCursorSkipsIt() {
        val frame = Screens.calculation(none, ex("2"), Cursor(index = 0), true, prefix = "f(X)=")
        assertEquals(LcdCursor(30, 1, 1, 9), frame.cursor)
        assertEquals("f(X)=2", frame.text)
    }

    @Test
    fun aResultLineWinsTheRowsItOccupies() {
        val nested = Expr(listOf(template(TemplateKind.FRACTION, Expr(listOf(frac("1", "2"))), ex("3"))))
        val frame = Screens.calculation(none, nested, null, true, result = ex("5"))
        assertEquals(Ink(91, 22, 95, 30), frame.ink(rows = 22..30))
    }

    @Test
    fun aBaseNLabelSitsRightAlignedAboveTheResult() {
        val frame = Screens.calculation(none, ex("12"), null, false, result = ex("12"), baseLabel = "Dec")
        val label = frame.ink(rows = 10..20)
        assertTrue(label != null && label.right == 95 && label.top >= 14 && label.bottom <= 19, "label at $label")
        assertEquals("12\nDec\n12", frame.text)
    }

    @Test
    fun stackedLinesPutLabelsLeftAndValuesRight() {
        val lines = listOf(LabelledValue("r=", ex("2")), LabelledValue("θ=", ex("45")))
        val frame = Screens.stacked(none, ex("8"), lines)
        assertEquals(Ink(91, 12, 95, 20), frame.ink(columns = 84..95, rows = 11..21))
        assertEquals(20, frame.ink(columns = 0..5, rows = 11..21)?.bottom)
        assertEquals(30, frame.ink(columns = 0..11, rows = 21..30)?.bottom)
        assertEquals("8\nr=2\nθ=45", frame.text)
    }

    @Test
    fun aSingleStackedLineGoesOnTheBottomRow() {
        val frame = Screens.stacked(none, null, listOf(LabelledValue("R=", ex("1"))), title = "X≤A;B≤X")
        assertTrue(frame.darkRows().none { it in 10..21 })
        assertEquals("X≤A;B≤X\nR=1", frame.text)
    }

    @Test
    fun aValueBeingTypedIsLeftAlignedOnTheBottomLineWithTheCursor() {
        val frame = Screens.labelValue(none, "Start?", ex("12"), cursor = Cursor(index = 2))
        assertEquals(LcdCursor(12, 22, 1, 9), frame.cursor)
        assertEquals(22, frame.ink(columns = 0..11, rows = 11..30)?.top)
    }

    @Test
    fun menusLightThePageArrowIconsAndKeepTheCallersIcons() {
        val frame = Screens.menu(setOf(Indicator.DEG), listOf(MenuItem(1, "DIST")), arrows = PageArrows.UP)
        assertEquals(setOf(Indicator.DEG, Indicator.UP), frame.indicators)
        val both = Screens.text(none, listOf(TextLine("x", TextRow.SMALL_1)), PageArrows.BOTH)
        assertEquals(setOf(Indicator.UP, Indicator.DOWN), both.indicators)
    }

    @Test
    fun aMenuTitleTakesTheFirstLine() {
        val items = listOf(MenuItem(1, "Setup"), MenuItem(2, "Memory"), MenuItem(3, "All"))
        val frame = Screens.menu(none, items, title = listOf("Clear?"))
        assertEquals("Clear?\n1:Setup  2:Memory\n3:All", frame.text)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 9, 10, 11, 12, 13, 14, 17, 18, 19, 20, 21, 22), frame.darkRows())
    }

    @Test
    fun aOneColumnMenuPutsOneItemPerLine() {
        val items = (1..4).map { MenuItem(it, "Item$it") }
        val frame = Screens.menu(none, items, columns = 1)
        assertEquals("1:Item1\n2:Item2\n3:Item3\n4:Item4", frame.text)
        assertTrue(frame.ink(columns = 48..95) == null)
    }

    @Test
    fun textLinesAlignByCell() {
        val lines = listOf(
            TextLine("Reset All", TextRow.SMALL_2, TextAlign.CENTER),
            TextLine("8", TextRow.LARGE_BOTTOM, TextAlign.RIGHT),
        )
        val frame = Screens.text(none, lines)
        assertEquals(19, frame.ink(rows = 9..14)?.left)
        assertEquals(Ink(91, 22, 95, 30), frame.ink(rows = 22..30))
    }

    @Test
    fun anErrorWithoutGotoHasNoFourthLine() {
        val frame = Screens.error(none, CalcError.SYNTAX, goto = false)
        assertEquals("Syntax ERROR\n[AC]  :Cancel", frame.text)
        assertTrue(frame.darkRows().none { it >= 24 })
    }

    @Test
    fun gridCellsAreCutToFitTheirColumn() {
        val spec = GridSpec(listOf("X"), listOf(GridRow(1, listOf("123456789012"))), GridCell(0, 0), "123456789012")
        val frame = Screens.grid(none, spec)
        val cell = frame.ink(columns = 10..37, rows = 6..11)
        assertTrue(cell != null && cell.left >= 12 && cell.right <= 35, "cell at $cell")
        assertTrue((frame.ink(columns = 0..11, rows = 6..11)?.right ?: 0) <= 9, "nothing spills left")
    }

    @Test
    fun gridTextListsHeadersRowsFooterAndValue() {
        val spec = GridSpec(
            headers = listOf("a", "b", "d"),
            rows = listOf(GridRow(null, listOf("3", "8", "12"))),
            current = GridCell(0, 1),
            value = "8",
            brackets = true,
            footer = "a:b=X:d",
        )
        assertEquals("a | b | d\n3 | 8 | 12\na:b=X:d\n8", Screens.grid(none, spec).text)
    }

    @Test
    fun typingIntoAGridCellShowsTheInputWithACursor() {
        val row = listOf(GridRow(1, listOf("0")))
        val spec = GridSpec(listOf("X"), row, GridCell(0, 0), editing = ex("17"), editCursor = Cursor(index = 2))
        val frame = Screens.grid(none, spec)
        assertEquals(LcdCursor(12, 25, 1, 6), frame.cursor)
        assertTrue(frame.text.endsWith("\n17"))
    }

    @Test
    fun aBlankScreenShowsOnlyTheIcons() {
        val frame = Screens.blank(setOf(Indicator.DEG))
        assertNull(frame.ink())
        assertEquals(setOf(Indicator.DEG), frame.indicators)
    }
}
