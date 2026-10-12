package dev.inkstone.core.calc.lcd

/**
 * Where things go on the 96 × 31 matrix. Every number here is either measured on the SA
 * handout's emulator captures (research 01 §3.6, re-counted for this file) or marked as a guess.
 *
 * ⚠️ **Columns are one to the right of research 01 §3.6's figures, deliberately.** §3.6 says input
 * text starts on column 0 and the last result glyph sits on columns 90–94. Re-measured on the same
 * captures, the matrix's inked extent across all of them is exactly 96 dots wide, from the MODE
 * menu's number cell (which touches the left edge) to the last digit of a result (which touches
 * the right): 0.25 → 96.25 dots in every 388-px capture, 3.5 → 99.5 in every 208-px one. Under
 * §3.6's numbers the MODE cell would start on column −1. So every glyph cell is a **leading** blank
 * column then five columns of ink: text starts on column 1, a result ends on column 95, and a
 * fraction at the start of a line puts its bar on column 1. Everything *relative* in §3.6 — the
 * 6-dot advance, the 15-row fraction, the bar one dot wider than its operand on each side, the
 * superscript's two-row drop — is unchanged.
 */
internal object LcdGeometry {
    const val WIDTH = LcdFrame.WIDTH
    const val HEIGHT = LcdFrame.HEIGHT
    const val LAST_COLUMN = WIDTH - 1
    const val LAST_ROW = HEIGHT - 1

    /** First ink row of the input line: row 0 is the line's leading blank row. */
    const val INPUT_TOP = 1

    /** Last ink row of a result: the matrix's bottom row is the result's baseline. */
    const val RESULT_BOTTOM = LAST_ROW

    /** Three-line screens (SOLVE, LineIO two-part results): the middle line's glyphs end here. */
    const val MIDDLE_BOTTOM = 20

    /** A ◀, ▷ or ▶ marker takes one whole cell at the edge of its line. */
    const val MARKER_CELL = LcdFont.CELL

    /** Small-font lines are 8 rows apart with their glyphs on rows 1–6 of each band. */
    const val BAND = 8
    const val BAND_GLYPH_TOP = 1

    /** The second item of a two-column menu starts at cell 8. */
    const val SECOND_COLUMN = 48

    /** The MODE menu's number cell is 7 × 7; its label's first cell starts one column after it. */
    const val MODE_CELL = 7
    const val MODE_LABEL = 8
}
