package dev.inkstone.core.calc.lcd

import dev.inkstone.core.calc.input.Cursor

/**
 * Draws a [LaidOutLine] as the input line (top-aligned, scrolled to the cursor, ◀ and ▷ markers)
 * or as a result (bottom-aligned, right-aligned when it fits, ◀ and ▶ when it does not).
 */
internal object LineRenderer {
    private const val CELL = LcdGeometry.MARKER_CELL
    private const val WIDTH = LcdGeometry.WIDTH

    /**
     * Draws [line] with its first ink row on [top], clipped to rows `0..bottomLimit`, scrolled left
     * by [scroll] dots. Returns where [cursor] is, or null when there is none.
     *
     * A line that would run past [bottomLimit] first rises, at most to row 0: SOLVE's `X²−6X=0`
     * puts its exponent on row 0 and its X on rows 2–10 rather than 1–11, so that the row above the
     * middle line stays blank (SA slide 14, measured). Natural input can be taller still (up to two
     * screens, research 02 §7.2); then the line is pushed up just far enough that the cursor's slot
     * is in view, and whatever falls outside the area is clipped — a result line below always wins
     * the rows it occupies. That last part is a guess: research does not describe it.
     */
    fun input(
        canvas: LcdCanvas,
        line: LaidOutLine,
        cursor: Cursor?,
        scroll: Int,
        top: Int,
        bottomLimit: Int,
        shape: CursorShape = CursorShape.INSERT,
    ): LcdCursor? {
        val cell = cursor?.let { line.cursorCell(it) }
        val overflow = top + line.box.height - 1 - bottomLimit
        val risen = top + line.box.above - overflow.coerceIn(0, maxOf(0, top))
        val axis = if (cell == null) risen else inView(risen, cell, bottomLimit)
        val left = scroll > 0
        val right = Scroll.rightMarker(line.box.width, scroll)
        canvas.clipped(if (left) CELL else 0, 0, if (right) WIDTH - CELL else WIDTH, bottomLimit + 1) {
            line.box.draw(canvas, -scroll, axis)
        }
        markers(canvas, line.font, axis, if (left) 0 else null, if (right) WIDTH - CELL else null, "▷")
        return cell?.let { cursorRect(it, axis, scroll, shape) }
    }

    /**
     * Draws [line] as a result whose last ink row is [bottom], in the columns from [left] to the
     * right edge: right-aligned when it fits, otherwise from [scroll] with ◀/▶ marking what is cut.
     */
    fun result(canvas: LcdCanvas, line: LaidOutLine, scroll: Int, bottom: Int, left: Int = 0) {
        val box = line.box
        val axis = bottom - box.below + 1
        val room = WIDTH - left
        if (box.width <= room) {
            box.draw(canvas, WIDTH - box.width, axis)
            return
        }
        val leftMark = scroll > 0
        val rightMark = Scroll.rightMarker(box.width, scroll, room)
        canvas.clipped(left + (if (leftMark) CELL else 0), 0, if (rightMark) WIDTH - CELL else WIDTH, bottom + 1) {
            box.draw(canvas, left - scroll, axis)
        }
        markers(canvas, line.font, axis, if (leftMark) left else null, if (rightMark) WIDTH - CELL else null, "▶")
    }

    private fun inView(axis: Int, cell: CursorCell, bottomLimit: Int): Int {
        val cellTop = axis + cell.top
        val cellBottom = cellTop + cell.height - 1
        return when {
            cellBottom > bottomLimit -> axis - (cellBottom - bottomLimit)
            cellTop < 0 -> axis - cellTop
            else -> axis
        }
    }

    private fun markers(canvas: LcdCanvas, font: LcdFont, axis: Int, leftX: Int?, rightX: Int?, rightMark: String) {
        val y = (axis - font.axis).coerceIn(0, LcdGeometry.HEIGHT - font.height)
        if (leftX != null) canvas.text(font, "◀", leftX, y)
        if (rightX != null) canvas.text(font, rightMark, rightX, y)
    }

    private fun cursorRect(cell: CursorCell, axis: Int, scroll: Int, shape: CursorShape): LcdCursor {
        val x = cell.x - scroll
        val y = axis + cell.top
        val glyphX = (x + 1).coerceIn(0, WIDTH - LcdFont.FULL_INK)
        return when (shape) {
            CursorShape.INSERT -> LcdCursor(x.coerceIn(0, LcdGeometry.LAST_COLUMN), y, 1, cell.height)

            CursorShape.BLOCK -> LcdCursor(glyphX, y, LcdFont.FULL_INK, cell.height, block = true)

            CursorShape.OVERWRITE ->
                LcdCursor(glyphX, (y + cell.height).coerceAtMost(LcdGeometry.LAST_ROW), LcdFont.FULL_INK, 1)
        }
    }
}
