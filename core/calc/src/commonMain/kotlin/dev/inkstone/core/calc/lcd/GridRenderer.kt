package dev.inkstone.core.calc.lcd

/**
 * Draws a [GridSpec]. The geometry is counted on the SA handout's EQN (`aX²+bX+c=0`) and RATIO
 * captures, re-measured for this file:
 *
 * - column `j` spans columns `10 + 28j` … `37 + 28j`; its header (3 × 5 font, rows 1–5) is centred
 *   on `22 + 28j` — `a`, `b`, `c` sit on columns 21–23, 49–51, 77–79;
 * - row `r` spans rows `6 + 6r` … `11 + 6r`; the current cell is inverted over columns
 *   `12 + 28j` … `35 + 28j` of it, and a cell's value ends on column `35 + 28j` (`0` on 33–35,
 *   61–63, 89–91);
 * - row numbers are centred on column 7; brackets are columns 10 and 94 with one-dot hooks, except
 *   on a side with columns scrolled out of view (the INEQ cubic editor showing `b c d`, slide 10);
 * - the template under a one-row editor is on rows 13–18, right-aligned; the full value of the
 *   current cell is bottom right in the small font (rows 25–30).
 *
 * What the STAT, TABLE, matrix and vector editors add (FREQ, row numbers past 9, a matrix letter)
 * follows the same grid; their own screens were not in the captures.
 */
internal object GridRenderer {
    private const val COLUMN_LEFT = 10
    private const val COLUMN_PITCH = 28
    private const val HEADER_CENTRE = 12
    private const val CELL_INSET = 2
    private const val CELL_WIDTH = 24
    private const val ROW_TOP = 6
    private const val ROW_PITCH = 6
    private const val NUMBER_CENTRE = 7
    private const val HEADER_TOP = 1
    private const val RIGHT_BRACKET = 94
    private const val FOOTER_GAP = 2

    private val tiny get() = LcdFont.TINY
    private val small get() = LcdFont.SMALL

    fun draw(canvas: LcdCanvas, spec: GridSpec): LcdCursor? {
        val widest = maxOf(spec.headers.size, spec.rows.maxOfOrNull { it.cells.size } ?: 0)
        val columns = minOf(GridSpec.MAX_COLUMNS, widest)
        val rows = spec.rows.take(GridSpec.MAX_ROWS)
        spec.headers.take(columns).forEachIndexed { j, header ->
            centred(canvas, tiny, header, headerCentre(j), HEADER_TOP)
        }
        spec.title?.let { centred(canvas, tiny, it, NUMBER_CENTRE, HEADER_TOP) }
        spec.tag?.let { canvas.text(tiny, it, LcdGeometry.WIDTH - tiny.width(it), HEADER_TOP) }
        rows.forEachIndexed { r, row -> drawRow(canvas, row, r, columns, spec.current) }
        if (spec.brackets && rows.isNotEmpty()) brackets(canvas, rows.size, !spec.hiddenLeft, !spec.hiddenRight)
        spec.footer?.let { footer ->
            canvas.text(small, footer, LcdGeometry.WIDTH - small.width(footer), rowTop(rows.size) + FOOTER_GAP - 1)
        }
        return bottomLine(canvas, spec)
    }

    fun text(spec: GridSpec): String = buildList {
        add(listOfNotNull(spec.title).plus(spec.headers).plus(listOfNotNull(spec.tag)).joinToString(" | "))
        spec.rows.take(GridSpec.MAX_ROWS).forEach { row ->
            add((row.number?.let { "$it: " } ?: "") + row.cells.joinToString(" | "))
        }
        spec.footer?.let { add(it) }
        add(spec.editing?.let { MathLayout.plainText(it) } ?: spec.value)
    }.joinToString("\n")

    private fun drawRow(canvas: LcdCanvas, row: GridRow, r: Int, columns: Int, current: GridCell?) {
        val top = rowTop(r)
        row.number?.let { centred(canvas, tiny, it.toString(), NUMBER_CENTRE, top + 1) }
        for (j in 0 until columns) {
            val text = fitted(row.cells.getOrElse(j) { "" })
            val cellLeft = columnLeft(j) + CELL_INSET
            val isCurrent = current?.row == r && current.column == j
            if (isCurrent) canvas.fill(cellLeft, top, CELL_WIDTH, ROW_PITCH)
            canvas.text(tiny, text, cellLeft + CELL_WIDTH - tiny.width(text), top + 1, inverse = isCurrent)
        }
    }

    /** A side whose columns run on past the screen is a bare line, without hooks (SA slide 10). */
    private fun brackets(canvas: LcdCanvas, rowCount: Int, leftHooks: Boolean, rightHooks: Boolean) {
        val height = rowCount * ROW_PITCH
        canvas.vertical(COLUMN_LEFT, ROW_TOP, height)
        canvas.vertical(RIGHT_BRACKET, ROW_TOP, height)
        for (y in listOf(ROW_TOP, ROW_TOP + height - 1)) {
            if (leftHooks) canvas.plot(COLUMN_LEFT + 1, y)
            if (rightHooks) canvas.plot(RIGHT_BRACKET - 1, y)
        }
    }

    private fun bottomLine(canvas: LcdCanvas, spec: GridSpec): LcdCursor? {
        val editing = spec.editing
        if (editing == null) {
            canvas.text(small, spec.value, LcdGeometry.WIDTH - small.width(spec.value), TextRow.SMALL_4.top)
            return null
        }
        val line = LaidOutLine(editing, natural = false, font = small)
        val scroll = Scroll.input(line.box.width, spec.editCursor?.let { line.cursorCell(it) }?.x, 0)
        return LineRenderer.input(
            canvas,
            line,
            spec.editCursor,
            scroll,
            TextRow.SMALL_4.top,
            LcdGeometry.LAST_ROW,
            spec.cursorShape,
        )
    }

    /** Cuts characters off the end until the text fits the cell, leaving one dot of margin. */
    private fun fitted(text: String): String {
        var keys = tiny.keys(text)
        while (keys.sumOf { tiny.advance(it) } > CELL_WIDTH) keys = keys.dropLast(1)
        return keys.joinToString("")
    }

    private fun centred(canvas: LcdCanvas, font: LcdFont, text: String, centre: Int, y: Int) {
        val width = font.width(text)
        canvas.text(font, text, centre - 1 - (width - 2) / 2, y)
    }

    private fun columnLeft(j: Int) = COLUMN_LEFT + COLUMN_PITCH * j

    private fun headerCentre(j: Int) = columnLeft(j) + HEADER_CENTRE

    private fun rowTop(r: Int) = ROW_TOP + ROW_PITCH * r
}
