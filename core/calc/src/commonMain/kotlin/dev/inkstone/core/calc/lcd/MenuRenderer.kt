package dev.inkstone.core.calc.lcd

/**
 * Draws menus. Both styles use the four 8-row bands of the small-font grid; measured on the SA
 * captures (SETUP page 1, the STAT menu, MODE pages 1 and 2):
 *
 * - [MenuStyle.COLON]: `n:label` in the small font on glyph rows 1–6 of the band, the first item
 *   at cell 0 and the second at cell 8 (column 48).
 * - [MenuStyle.MODE]: a dark 7 × 7 cell on rows 0–6 of the band at column 0 or 48 with the number
 *   light inside it (rows 1–5, columns 1–5), and the label in 5 × 7 capitals on rows 0–6 starting
 *   with the cell at column 8 (or 56) — `COMP`'s C has its ink on columns 9–13.
 */
internal object MenuRenderer {
    fun draw(canvas: LcdCanvas, items: List<MenuItem>, style: MenuStyle, title: List<String>, columns: Int): String =
        when (style) {
            MenuStyle.MODE -> drawMode(canvas, items)
            MenuStyle.COLON -> drawColon(canvas, items, title, columns)
        }

    private fun drawColon(canvas: LcdCanvas, items: List<MenuItem>, title: List<String>, columns: Int): String {
        val lines = ArrayList<String>()
        title.forEach { line ->
            canvas.text(LcdFont.SMALL, firmwareText(line), 0, bandTop(lines.size) + LcdGeometry.BAND_GLYPH_TOP)
            lines += line
        }
        items.chunked(columns).forEach { row ->
            val y = bandTop(lines.size) + LcdGeometry.BAND_GLYPH_TOP
            row.forEachIndexed { column, item ->
                val text = firmwareText("${item.number}:${item.label}")
                canvas.text(LcdFont.SMALL, text, column * LcdGeometry.SECOND_COLUMN, y)
            }
            lines += row.joinToString("  ") { "${it.number}:${it.label}" }
        }
        return lines.joinToString("\n")
    }

    private fun drawMode(canvas: LcdCanvas, items: List<MenuItem>): String = items.chunked(2).mapIndexed { band, row ->
        row.forEachIndexed { column, item ->
            val x = column * LcdGeometry.SECOND_COLUMN
            val y = bandTop(band)
            canvas.fill(x, y, LcdGeometry.MODE_CELL, LcdGeometry.MODE_CELL)
            canvas.glyph(LcdFont.INVERTED, (item.number % DIGITS).toString(), x + 1, y + 1, inverse = true)
            canvas.text(LcdFont.MENU, item.label, x + LcdGeometry.MODE_LABEL, y)
        }
        row.joinToString("  ") { "${it.number}:${it.label}" }
    }.joinToString("\n")

    private fun bandTop(band: Int) = band * LcdGeometry.BAND

    private const val DIGITS = 10
}

/**
 * A string the firmware writes itself — a menu item, a title, a label such as `L-R=` or
 * `X-Value Minimum=` — with its hyphens drawn as the five-dot bar those strings have on the SA
 * captures (`L-R=` slide 13, `X-Value Minimum=` slide 16, `1:1-VAR` slide 17). ASCII `-` in the
 * fonts is the shorter `(−)` sign of Tok.NEGATIVE (the measured `-6`), and none of these strings
 * ever carries a negative number, so the swap cannot change one. The MODE menu's own 5 × 7 face
 * draws its `-` five dots wide already (`BASE-N`, slide 2).
 */
internal fun firmwareText(text: String): String = text.replace('-', '−')
