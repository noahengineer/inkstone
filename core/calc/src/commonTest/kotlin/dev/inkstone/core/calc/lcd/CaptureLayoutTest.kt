package dev.inkstone.core.calc.lcd

import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.PathStep
import dev.inkstone.core.calc.input.TemplateKind
import dev.inkstone.core.calc.input.Tok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Positions counted dot by dot on the SA handout's emulator captures (the PNGs research 00 [SA-img]
 * describes, exact 2× and 4× copies of the LCD), in matrix coordinates. Each test names its slide.
 * These are independent of the implementation: the numbers were read off the captures, not off a
 * frame this code drew. Glyph *shapes* are ours (decision D1), so only positions and extents are
 * compared, never whole bitmaps.
 */
class CaptureLayoutTest {
    private val none = emptySet<Indicator>()

    /** Dark columns of row [y] inside [range]. */
    private fun LcdFrame.inked(y: Int, range: IntRange): List<Int> = darkColumns(y).filter { it in range }

    /**
     * Slide 12, `3A²+4B` in COMP: the 2 is inked on columns 13–17 — the cell straight after the A —
     * and the + on 20–24, so the extra column an exponent takes comes **after** it, not before.
     */
    @Test
    fun anExponentIsInkedInTheNextCellAndTheGapComesAfterIt() {
        val frame = Screens.calculation(none, ex("3", Tok.VAR_A, Tok.SQUARE, "+4", Tok.VAR_B), null, true)
        assertEquals(Ink(13, 1, 17, 6), frame.ink(columns = 12..18, rows = 0..7))
        assertEquals((20..24).toList(), frame.inked(7, 18..25))
        assertEquals(Ink(7, 3, 11, 11), frame.ink(columns = 6..11, rows = 0..12))
    }

    /**
     * An `x^□` exponent is the same superscript as `²`, so it takes the same trailing column — and
     * the template keeps its slot for the cursor: the end of the exponent and the gap before the `+`
     * are two different columns.
     */
    @Test
    fun aPowerTemplateTakesTheGapAndKeepsItsSlot() {
        val expr = Expr(items("2") + template(TemplateKind.POWER, ex("10")) + items("+1"))
        val inExponent = Screens.calculation(none, expr, Cursor(listOf(PathStep(1, 0)), 2), true).cursor
        val afterIt = Screens.calculation(none, expr, Cursor(index = 2), true).cursor
        assertEquals(18, inExponent?.x)
        assertEquals(19, afterIt?.x)
        val frame = Screens.calculation(none, expr, null, true)
        // The small 1 is inked on its cell's middle three columns, so the exponent starts on 8.
        assertEquals(Ink(8, 1, 17, 6), frame.ink(columns = 6..18, rows = 0..7))
        assertEquals((20..24).toList(), frame.inked(7, 18..25))
    }

    /**
     * Slide 14, SOLVE's `X²−6X=0` over `X=` and `L−R=`: the input rises to rows 0–10 so that row 11
     * stays blank above the middle line. The exponent is on rows 0–5, columns 7–11; the X under it
     * on rows 2–10; the `−` on row 6, columns 14–18; the `=` bars on rows 5 and 7, columns 32–36.
     */
    @Test
    fun theSolveScreenLiftsASuperscriptedInputAboveTheMiddleLine() {
        val input = ex(Tok.VAR_X, Tok.SQUARE, "−6", Tok.VAR_X, "=0")
        val lines = listOf(LabelledValue("X=", ex("0")), LabelledValue("L-R=", ex("0")))
        val frame = Screens.stacked(none, input, lines, natural = true)
        assertEquals(Ink(7, 0, 11, 5), frame.ink(columns = 6..12, rows = 0..11))
        assertEquals(Ink(1, 2, 5, 10), frame.ink(columns = 0..5, rows = 0..11))
        assertEquals((14..18).toList(), frame.inked(6, 12..19))
        assertEquals((32..36).toList(), frame.inked(5, 31..37))
        assertEquals((32..36).toList(), frame.inked(7, 31..37))
        assertEquals(Ink(38, 2, 42, 10), frame.ink(columns = 37..43, rows = 0..11))
        assertTrue(frame.darkColumns(11).isEmpty(), "row 11 is the gap above the middle line")
        assertEquals(Ink(91, 12, 95, 20), frame.ink(columns = 84..95, rows = 11..21))
        assertEquals(Ink(91, 22, 95, 30), frame.ink(columns = 84..95, rows = 21..30))
    }

    /**
     * The hyphen the firmware writes inside its own strings is the five-dot bar, not the three-dot
     * `(−)` sign: `L-R=` (slide 13, row 26), `X-Value Minimum=` (slide 16, row 5), `1:1-VAR`
     * (slide 17, row 4) and the MODE menu's `BASE-N` (slide 2, row 11).
     */
    @Test
    fun firmwareHyphensAreFiveDotsWide() {
        val solve = Screens.stacked(none, null, listOf(LabelledValue("X=", ex("0")), LabelledValue("L-R=", ex("0"))))
        assertEquals((7..11).toList(), solve.inked(26, 6..12))
        val vertex = Screens.labelValue(none, "X-Value Minimum=", ex("-4"))
        assertEquals((7..11).toList(), vertex.inked(5, 6..12))
        val stat = Screens.menu(none, listOf(MenuItem(1, "1-VAR"), MenuItem(2, "A+BX")))
        assertEquals((19..23).toList(), stat.inked(4, 18..24))
        val modes = listOf("COMP", "CMPLX", "STAT", "BASE-N").mapIndexed { i, label -> MenuItem(i + 1, label) }
        val mode = Screens.menu(none, modes, MenuStyle.MODE)
        assertEquals((81..85).toList(), mode.inked(11, 80..86))
    }

    /** The value a firmware label introduces keeps the short `(−)` sign: `-4` after `X-Value Minimum=`. */
    @Test
    fun aNegativeValueStillHasTheShortSign() {
        val vertex = Screens.labelValue(none, "X-Value Minimum=", ex("-4"))
        assertEquals(listOf(86, 87, 88), vertex.inked(26, 84..89))
    }

    /**
     * Slide 19, `CONVERSION` / `Number 01~40?` / `[__]`: the field is on rows 21–29 — one row above a
     * result line — with `[` on 74–76, the underscores on 79–83 and 85–89 and `]` on 92–94.
     */
    @Test
    fun theConstAndConvFieldSitsOnRows21To29() {
        val lines = listOf(
            TextLine("CONVERSION", TextRow.SMALL_1),
            TextLine("Number 01~40?", TextRow.SMALL_2),
            TextLine("[__]", TextRow.LARGE_FIELD, TextAlign.RIGHT),
        )
        val frame = Screens.text(none, lines)
        assertEquals(listOf(74, 75, 76, 92, 93, 94), frame.inked(21, 70..95))
        assertEquals(listOf(74, 94), frame.inked(22, 70..95))
        assertEquals((74..76) + (79..83) + (85..89) + (92..94), frame.inked(29, 70..95))
        assertTrue(frame.inked(30, 0..95).isEmpty(), "row 30 is clear")
        assertEquals(Ink(1, 1, 59, 6), frame.ink(rows = 0..7))
        assertEquals(1, frame.ink(rows = 8..15)?.left)
    }

    /**
     * Research 02 §8.3 describes `5×10⁻³` as the mantissa, a **compact** `×` and small `10`, then the
     * raised exponent — the one-cell `×₁₀` glyph of the `×10ˣ` key (04's notation note says the
     * same). Drawn as three large cells instead, the guide's Sci 0 result `1,428571429×10⁻¹` (02 §8.3)
     * would not fit the line and would show `▶`.
     */
    @Test
    fun anExponentialResultUsesTheCompactTimesTenGlyph() {
        val sci = Expr(items("1,428571429") + template(TemplateKind.SCI_EXPONENT, ex("-1")))
        val glyph = LcdCanvas().also { it.text(LcdFont.LARGE, Tok.EXP10.text, CELL_78, 22) }.freeze(none, null, "")
        for (natural in listOf(true, false)) {
            assertEquals(0, MathLayout.resultScrollLimit(sci, natural), "natural = $natural")
            val frame = Screens.calculation(none, Expr(), null, natural, result = sci)
            // 11 cells of mantissa, then ×₁₀ in cell 78, then the two-cell exponent ending on 95.
            val cell = CELL_78..CELL_78 + 5
            assertEquals(glyph.ink(columns = cell), frame.ink(columns = cell, rows = 22..30), "natural = $natural")
        }
    }

    /**
     * The widest Norm result, a negative ten-digit mantissa with a negative two-digit exponent, fits
     * on one line in both displays: the exponent's trailing column is only taken when something
     * follows it (a guess at the line's end; the captures only show it before `+` and `−`).
     */
    @Test
    fun theWidestExponentialResultFitsTheLine() {
        val widest = Expr(items("-1,234567891") + template(TemplateKind.SCI_EXPONENT, ex("-12")))
        for (natural in listOf(true, false)) {
            assertEquals(0, MathLayout.resultScrollLimit(widest, natural), "natural = $natural")
            val frame = Screens.calculation(none, Expr(), null, natural, result = widest)
            assertEquals(95, frame.ink()?.right, "natural = $natural")
            assertEquals("\n-1,234567891×10^-12", frame.text)
        }
    }

    /**
     * Sixteen characters fill the line, so the cursor after the sixteenth has no column of its own
     * (its bar stands in the next cell's leading column, 96): the line scrolls one cell and shows `◀`.
     */
    @Test
    fun aCursorAfterTheSixteenthCharacterScrollsOneCell() {
        val full = ex("1234567890123456")
        val end = Cursor(index = 16)
        assertEquals(6, MathLayout.inputScroll(full, end, natural = true))
        assertEquals(0, MathLayout.inputScroll(full, Cursor(index = 15), natural = true))
        val frame = Screens.calculation(none, full, end, true, inputScroll = 6)
        assertEquals(LcdCursor(90, 1, 1, 9), frame.cursor)
        assertNull(frame.ink(columns = 90..95))
    }

    /**
     * Slide 10, the INEQ cubic editor scrolled to `b c d` (`a` is off to the left): the left bracket
     * is a bare line on column 10, rows 6–11, while the right one keeps its hooks on column 93.
     * Slide 16's quadratic editor, with nothing hidden, has hooks on both (column 11 and 93).
     */
    @Test
    fun aBracketOnASideWithHiddenColumnsHasNoHooks() {
        val row = listOf(GridRow(null, listOf("-5", "6", "0")))
        val scrolled = GridSpec(listOf("b", "c", "d"), row, GridCell(0, 2), "0", brackets = true, hiddenLeft = true)
        val frame = Screens.grid(none, scrolled)
        assertEquals((6..11).toList(), (0..30).filter { frame[10, it] })
        assertTrue(!frame[11, 6] && !frame[11, 11], "no hooks on the left")
        assertTrue(frame[93, 6] && frame[93, 11] && (6..11).all { frame[94, it] }, "hooks on the right")
        val whole = Screens.grid(none, scrolled.copy(hiddenLeft = false, current = null))
        assertTrue(whole[11, 6] && whole[11, 11], "hooks when nothing is hidden")
    }

    /**
     * With `◀` showing, the first cell belongs to the marker: a cursor that would stand in it (the
     * gap before the fifth character, with the line scrolled five cells) makes the line scroll back.
     */
    @Test
    fun theCursorNeverStandsUnderTheLeftMarker() {
        val long = ex("12345678901234567890")
        assertEquals(24, MathLayout.inputScroll(long, Cursor(index = 5), true, previous = 30))
        val frame = Screens.calculation(none, long, Cursor(index = 5), true, inputScroll = 24)
        assertEquals(LcdCursor(6, 1, 1, 9), frame.cursor)
    }

    private companion object {
        /** The cell the `×₁₀` of `1,428571429×10⁻¹` lands in: 96 − (66 + 6 + 12). */
        const val CELL_78 = 78
    }
}
