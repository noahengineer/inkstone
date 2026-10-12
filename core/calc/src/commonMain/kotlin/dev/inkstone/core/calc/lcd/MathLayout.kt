package dev.inkstone.core.calc.lcd

import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.input.Expr

/** A laid-out expression's size in dots. */
data class LcdSize(val width: Int, val height: Int)

/** How the cursor is drawn (research 02 §7.2). */
enum class CursorShape {
    /** The blinking bar, as tall as the slot it stands in. Natural display is always insert. */
    INSERT,

    /** LineIO's overwrite mode (`SHIFT DEL`): an underline under the character it will replace. */
    OVERWRITE,

    /** Ten bytes or fewer of input left: a filled character-sized block. */
    BLOCK,
}

/**
 * What the key machine asks of the layout without drawing anything: how wide an expression is,
 * where the input line must scroll to keep the cursor in view, and how far a result can scroll.
 *
 * Scrolling is in whole 6-dot cells, so a clipped line of text shows exactly 15 characters beside
 * its marker, as research 02 §8.1 counts (`4×sin(30)×(30+1▷`). The machine keeps the returned
 * offset and passes it back on the next key, because the real display only scrolls when the
 * cursor would leave the window — it does not recentre on every key.
 */
object MathLayout {
    /** Size of [expr] as the input line would draw it. Natural input is refused beyond 2 screens tall (62 dots). */
    fun measure(expr: Expr, natural: Boolean): LcdSize {
        val line = LaidOutLine(expr, natural)
        return LcdSize(line.box.width, line.box.height)
    }

    /**
     * The horizontal offset, in dots and always a whole number of 6-dot cells, that keeps [cursor]
     * visible on the input line, starting from [previous]. 0 when the line **and the cursor** fit:
     * after a sixteenth character the bar would stand on column 96, which does not exist, so the
     * line scrolls one cell and shows `◀`. [prefix] is fixed text before the expression (`f(X)=`).
     */
    fun inputScroll(input: Expr, cursor: Cursor?, natural: Boolean, previous: Int = 0, prefix: String? = null): Int {
        val line = LaidOutLine(input, natural, prefix)
        val cell = cursor?.let { line.cursorCell(it) }
        return Scroll.input(line.box.width, cell?.x, previous)
    }

    /** The largest useful result offset for `▶`/`◀` on the result line; 0 when it fits (no ▶ shown). */
    fun resultScrollLimit(result: Expr, natural: Boolean): Int =
        Scroll.resultLimit(LaidOutLine(result, natural).box.width, LcdGeometry.WIDTH)

    /** The plain linear text of [expr], as [LcdFrame.text] reports it (`1/200`, `1,25×10^-3`). */
    fun plainText(expr: Expr): String = ExprText.of(expr, ExprText.Style.PLAIN)
}

/** Where a cursor stands, relative to its line's left column and axis. */
internal data class CursorCell(val x: Int, val top: Int, val height: Int)

/** One expression laid out once, with an optional fixed prefix (`f(X)=`), in [font]. */
internal class LaidOutLine(expr: Expr, natural: Boolean, prefix: String? = null, val font: LcdFont = LcdFont.LARGE) {
    private val row: ExprRow = MathBuilder(natural).root(expr, font)
    private val prefixWidth = prefix?.let { font.width(it) } ?: 0
    val box: Box = if (prefix == null) row else HBox(listOf(TextBox(prefix, font), row))

    /** Follows [cursor]'s path down through the templates; null if the path does not exist here. */
    fun cursorCell(cursor: Cursor): CursorCell? {
        var current = row
        var dx = prefixWidth
        var dy = 0
        for (step in cursor.path) {
            val template = current.item(step.item) as? TemplateBox ?: return null
            val placed = template.slotRows().getOrNull(step.slot) ?: return null
            dx += current.itemOffset(step.item) + placed.dx
            dy += placed.dy
            current = placed.row
        }
        if (cursor.index !in 0..current.itemCount) return null
        return CursorCell(dx + current.gapX(cursor.index), dy - current.above, current.height)
    }
}

/** Horizontal scrolling of a line wider than the matrix, in whole cells. */
internal object Scroll {
    private const val CELL = LcdFont.CELL
    private const val WIDTH = LcdGeometry.WIDTH

    fun rightMarker(width: Int, offset: Int, room: Int = WIDTH): Boolean = width - offset > room

    fun input(width: Int, cursorX: Int?, previous: Int): Int {
        val extent = maxOf(width, (cursorX ?: -1) + 1)
        if (extent <= WIDTH) return 0
        // The limit allows for a cursor after the last item, so stepping back from the end keeps the offset.
        val start = floorCell(previous.coerceIn(0, ceilCell(width + 1 - WIDTH)))
        return when {
            cursorX == null -> start
            cursorX - start < leftBound(start) -> leftmost(cursorX)
            cursorX - start > rightBound(width, start) -> rightmost(width, cursorX, start)
            else -> start
        }
    }

    fun resultLimit(width: Int, room: Int): Int = if (width <= room) 0 else ceilCell(width - room)

    /** With ◀ shown, the first visible column is the second cell's leading column. */
    private fun leftBound(offset: Int) = if (offset > 0) CELL else 0

    /** With ▷ shown, the cursor can still stand in the marker cell's blank leading column. */
    private fun rightBound(width: Int, offset: Int) =
        if (rightMarker(width, offset)) WIDTH - CELL else LcdGeometry.LAST_COLUMN

    private fun leftmost(cursorX: Int): Int = floorCell(maxOf(0, cursorX - CELL))

    private fun rightmost(width: Int, cursorX: Int, from: Int): Int {
        var offset = maxOf(from, ceilCell(cursorX - LcdGeometry.LAST_COLUMN))
        while (cursorX - offset > rightBound(width, offset)) offset += CELL
        return offset
    }

    private fun floorCell(x: Int) = x / CELL * CELL

    private fun ceilCell(x: Int) = (x + CELL - 1) / CELL * CELL
}
