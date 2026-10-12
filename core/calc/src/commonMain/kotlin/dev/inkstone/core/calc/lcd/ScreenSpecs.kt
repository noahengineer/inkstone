package dev.inkstone.core.calc.lcd

import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.input.Expr

/** One numbered menu item: [number] is the key that selects it, [label] what follows it. */
data class MenuItem(val number: Int, val label: String)

/** The two menu looks on this model. */
enum class MenuStyle {
    /**
     * The MODE menu: the number light in a dark 7 × 7 cell, the label in 5 × 7 capitals beside it,
     * two items per line (SA slides 2 and 15).
     */
    MODE,

    /** Everything else: `1:Type  2:Data` in the small font (research 02 §5, 03 §4.2). */
    COLON,
}

/**
 * Whether more menu pages exist below or above. On the captures these are the **status icons** ▼
 * and ▲ (bits F80A.3 / F80B.7, research 01 §3.2) — the matrix's top-right stays empty — so a menu
 * screen adds [Indicator.DOWN] / [Indicator.UP] rather than drawing a dot-matrix arrow.
 */
enum class PageArrows(internal val indicators: Set<Indicator>) {
    NONE(emptySet()),
    DOWN(setOf(Indicator.DOWN)),
    UP(setOf(Indicator.UP)),
    BOTH(setOf(Indicator.DOWN, Indicator.UP)),
}

/**
 * The fixed text rows of the matrix: four small-font lines on the measured 8-row pitch (glyph rows
 * 1–6, 9–14, 17–22, 25–30), or a large-font line at the top (rows 1–9), the middle of a three-line
 * screen (12–20, measured on the SOLVE capture), the bottom (22–30), or [LARGE_FIELD].
 */
enum class TextRow(internal val top: Int, internal val large: Boolean) {
    SMALL_1(SMALL_1_TOP, false),
    SMALL_2(SMALL_2_TOP, false),
    SMALL_3(SMALL_3_TOP, false),
    SMALL_4(SMALL_4_TOP, false),
    LARGE_TOP(LARGE_TOP_ROW, true),
    LARGE_MIDDLE(LARGE_MIDDLE_ROW, true),
    LARGE_BOTTOM(LARGE_BOTTOM_ROW, true),

    /**
     * The `[__]` entry field under `CONSTANT` / `CONVERSION` and `Number 01~40?` (research 03 §14–15),
     * pass it right-aligned: rows 21–29, **one row above** a result line. Measured on SA slide 19
     * (`[` on columns 74–76, the underscores on row 29, `]` on 92–94); why the firmware lifts it is
     * not known.
     */
    LARGE_FIELD(LARGE_FIELD_ROW, true),
    ;

    internal val font: LcdFont get() = if (large) LcdFont.LARGE else LcdFont.SMALL

    companion object {
        /** The small row [band] (0–3), for callers that count lines. */
        fun small(band: Int): TextRow = listOf(SMALL_1, SMALL_2, SMALL_3, SMALL_4)[band.coerceIn(0, SMALL_ROWS - 1)]

        const val SMALL_ROWS = 4
    }
}

private const val SMALL_1_TOP = 1
private const val SMALL_2_TOP = 9
private const val SMALL_3_TOP = 17
private const val SMALL_4_TOP = 25
private const val LARGE_TOP_ROW = 1
private const val LARGE_MIDDLE_ROW = 12
private const val LARGE_BOTTOM_ROW = 22
private const val LARGE_FIELD_ROW = 21

/**
 * Horizontal placement of a [TextLine]. [CENTER] centres by whole cells, which is how the
 * firmware's strings do it — with leading spaces (`"   Reset All"`, research 02 §5.3).
 */
enum class TextAlign { LEFT, CENTER, RIGHT }

/** One line of a prompt or message screen. */
data class TextLine(val text: String, val row: TextRow, val align: TextAlign = TextAlign.LEFT)

/** One line of a stacked result: [label] at the left, [value] right-aligned (`r=` … `2`). */
data class LabelledValue(val label: String, val value: Expr)

/** A cell of an editor grid, counted within the visible rows and columns. */
data class GridCell(val row: Int, val column: Int)

/** One visible editor row: its number at the left (null for none) and its cells' text. */
data class GridRow(val number: Int?, val cells: List<String>)

/**
 * An editor or table screen (research 03 §2.4): STAT, the EQN/INEQ/RATIO coefficient editors, the
 * matrix and vector editors, TABLE and the DIST list. Up to [MAX_COLUMNS] columns and [MAX_ROWS]
 * rows are visible; the caller scrolls by passing the visible slice.
 *
 * Measured on the SA EQN and RATIO captures: headers centred above 28-dot columns starting at
 * column 10, rows 6 dots apart from row 6, the current cell inverted, values right-aligned in the
 * 3 × 5 font, row numbers centred on column 7, brackets on columns 10 and 94, the current cell's
 * full [value] bottom-right in the small font. Cell text that does not fit is cut from the right —
 * research 03 §2.4: truncated, not rounded; the caller formats, this only refuses to overflow.
 */
data class GridSpec(
    val headers: List<String>,
    val rows: List<GridRow>,
    val current: GridCell? = null,
    /** The current cell's full value, bottom right. */
    val value: String = "",
    /** Top-left label: the matrix or vector letter (`A`). */
    val title: String? = null,
    /** Top-right tag: the DIST list's type (`Bpd`). */
    val tag: String? = null,
    /** Brackets round the rows: matrix, vector and coefficient editors. */
    val brackets: Boolean = false,
    /** The template under the rows (`aX²+bX+c<0`, `a:b=X:d`), small font, right-aligned. */
    val footer: String? = null,
    /** What is being typed into the current cell; replaces [value] on the bottom line. */
    val editing: Expr? = null,
    val editCursor: Cursor? = null,
    val cursorShape: CursorShape = CursorShape.INSERT,
    /**
     * Columns exist beyond the left edge of the visible slice. That side's bracket is then a bare
     * line without its hooks, as the INEQ cubic editor's is while it shows `b c d` (SA slide 10).
     */
    val hiddenLeft: Boolean = false,
    /** The same for the right edge — by symmetry with [hiddenLeft]; no capture shows it. */
    val hiddenRight: Boolean = false,
) {
    companion object {
        const val MAX_COLUMNS = 3
        const val MAX_ROWS = 3
    }
}
