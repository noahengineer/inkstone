package dev.inkstone.core.calc.lcd

import dev.inkstone.core.calc.input.TemplateKind

/**
 * A laid-out piece of a line, TeX-style: [width] columns, and a vertical extent measured from the
 * **math axis** — the row where `−` is drawn and fraction bars sit, so that `1+2/3` puts the bar
 * level with the plus. [above] counts rows strictly above the axis, [below] the axis row and the
 * rows under it.
 *
 * Every box starts with one blank column (the leading column of a glyph cell, see [LcdGeometry]),
 * which is also where the insert cursor stands when it is in front of the box.
 */
internal abstract class Box {
    abstract val width: Int
    abstract val above: Int
    abstract val below: Int
    val height: Int get() = above + below

    /** Draws the box with its left column at [x] and its axis on row [axis]. */
    abstract fun draw(canvas: LcdCanvas, x: Int, axis: Int)

    /** Adds every [ExprRow] directly inside this box, offset from this box's (left, axis) by ([dx], [dy]). */
    open fun collectRows(out: MutableList<PlacedRow>, dx: Int, dy: Int) = Unit

    /** True when the box ends in a raised exponent that has not yet taken its trailing column ([ScriptBox]). */
    open val endsInScript: Boolean get() = false

    /** This box with its final exponent's trailing column added; itself when [endsInScript] is false. */
    open fun withScriptGap(): Box = this
}

/** An [ExprRow] and where it sits relative to the template that holds it. */
internal class PlacedRow(val row: ExprRow, val dx: Int, val dy: Int)

/** The left column of each of [parts] laid side by side, plus the total width as the last entry. */
internal fun leftEdges(parts: List<Box>): IntArray {
    val edges = IntArray(parts.size + 1)
    parts.forEachIndexed { i, part -> edges[i + 1] = edges[i] + part.width }
    return edges
}

/** A run of glyphs in one font. */
internal class TextBox(private val text: String, private val font: LcdFont) : Box() {
    override val width: Int = font.width(text)
    override val above: Int = font.axis
    override val below: Int = font.below

    override fun draw(canvas: LcdCanvas, x: Int, axis: Int) {
        canvas.text(font, text, x, axis - font.axis)
    }
}

/** Boxes side by side, axes aligned. */
internal class HBox(private val parts: List<Box>) : Box() {
    private val offsets = leftEdges(parts)
    override val width: Int = offsets[parts.size]
    override val above: Int = parts.maxOfOrNull { it.above } ?: 0
    override val below: Int = parts.maxOfOrNull { it.below } ?: 0

    override fun draw(canvas: LcdCanvas, x: Int, axis: Int) {
        parts.forEachIndexed { i, part -> part.draw(canvas, x + offsets[i], axis) }
    }

    override fun collectRows(out: MutableList<PlacedRow>, dx: Int, dy: Int) {
        parts.forEachIndexed { i, part -> part.collectRows(out, dx + offsets[i], dy) }
    }

    override val endsInScript: Boolean get() = parts.lastOrNull()?.endsInScript == true

    override fun withScriptGap(): Box =
        if (endsInScript) HBox(parts.dropLast(1) + parts.last().withScriptGap()) else this
}

/** [inner] moved [dy] rows down from this box's axis (negative = up): a log base, a constant's subscript. */
internal class ShiftBox(private val inner: Box, private val dy: Int) : Box() {
    override val width: Int = inner.width
    override val above: Int = maxOf(0, inner.above - dy)
    override val below: Int = maxOf(0, inner.below + dy)

    override fun draw(canvas: LcdCanvas, x: Int, axis: Int) = inner.draw(canvas, x, axis + dy)

    override fun collectRows(out: MutableList<PlacedRow>, dx: Int, dy: Int) = inner.collectRows(out, dx, dy + this.dy)
}

/**
 * A raised exponent, [dy] rows down from the axis (so negative). Its first cell sits straight after
 * the base, and one blank column follows it **when something else does** ([gap] = 1): on the SA
 * captures `3A²+4B` puts the A on columns 7–11, the 2 on 13–17 and the + on 20–24 (slide 12), and
 * `X²−6X=0` the X on 1–5, the 2 on 7–11 and the − on 14–18 (slide 14). Research 01 §3.6's "in its
 * own 6-col cell" holds; the seventh column is what it did not say.
 *
 * ⚠️ Guess: at the very end of a line the column is left out. Nothing measured shows that case, and
 * leaving it in would push the widest Norm result, `-1,234567891×10⁻¹²`, one dot past the line.
 */
internal class ScriptBox(private val inner: Box, private val dy: Int, private val gap: Int = 0) : Box() {
    override val width: Int = inner.width + gap
    override val above: Int = maxOf(0, inner.above - dy)
    override val below: Int = maxOf(0, inner.below + dy)
    override val endsInScript: Boolean get() = gap == 0

    override fun withScriptGap(): Box = if (endsInScript) ScriptBox(inner, dy, SCRIPT_GAP) else this

    override fun draw(canvas: LcdCanvas, x: Int, axis: Int) = inner.draw(canvas, x, axis + dy)

    override fun collectRows(out: MutableList<PlacedRow>, dx: Int, dy: Int) = inner.collectRows(out, dx, dy + this.dy)

    private companion object {
        const val SCRIPT_GAP = 1
    }
}

/**
 * A stacked fraction, exactly as research 01 §3.6 measures it: numerator, one blank row, a 1-dot
 * bar, one blank row, denominator (15 rows for two small-font operands). The bar is the wider
 * operand's ink plus one dot each side, and both operands are centred on it. When the difference
 * is odd the extra column goes to the right — a guess; the captures only show even differences.
 */
internal class FractionBox(private val numerator: Box, private val denominator: Box) : Box() {
    private val ink = maxOf(numerator.width - 1, denominator.width - 1, 1)
    override val width: Int = ink + BAR_EXTRA + 1
    override val above: Int = numerator.height + 1
    override val below: Int = denominator.height + 2

    override fun draw(canvas: LcdCanvas, x: Int, axis: Int) {
        canvas.horizontal(x + 1, axis, ink + BAR_EXTRA)
        numerator.draw(canvas, x + left(numerator), numeratorAxis(axis))
        denominator.draw(canvas, x + left(denominator), denominatorAxis(axis))
    }

    override fun collectRows(out: MutableList<PlacedRow>, dx: Int, dy: Int) {
        numerator.collectRows(out, dx + left(numerator), numeratorAxis(dy))
        denominator.collectRows(out, dx + left(denominator), denominatorAxis(dy))
    }

    private fun left(operand: Box) = 1 + (ink - (operand.width - 1)) / 2

    private fun numeratorAxis(axis: Int) = axis - 1 - numerator.below

    private fun denominatorAxis(axis: Int) = axis + 2 + denominator.above

    private companion object {
        /** One overhanging dot each side of the operand's ink. */
        const val BAR_EXTRA = 2
    }
}

/**
 * `√`, `∛` and `ⁿ√`: a tick, a rising stroke and an overbar one blank row above the radicand,
 * running one column past it. The index (`3`, or the ⁿ slot) sits up and left, its bottom row one
 * above the tick. The stroke shape is ours; research 01 §3.6 has no measurement of it.
 */
internal class RadicalBox(private val index: Box?, private val radicand: Box) : Box() {
    private val signLeft = index?.width ?: 1
    private val top = -(radicand.above + 2)
    private val bottom = radicand.below - 1
    private val tick = maxOf(top + 2, bottom - TICK_RISE)
    private val indexAxis = tick - (index?.below ?: 0)
    override val width: Int = signLeft + SIGN + radicand.width + 1
    override val above: Int = maxOf(radicand.above + 2, index?.let { it.above - indexAxis } ?: 0)
    override val below: Int = radicand.below

    override fun draw(canvas: LcdCanvas, x: Int, axis: Int) {
        val sign = x + signLeft
        canvas.horizontal(sign + STROKE, axis + top, width - signLeft - STROKE)
        canvas.vertical(sign + STROKE, axis + top, bottom - 1 - top)
        canvas.vertical(sign + 2, axis + bottom - 1, 2)
        canvas.vertical(sign + 1, axis + tick + 1, bottom - 1 - tick)
        canvas.plot(sign, axis + tick)
        radicand.draw(canvas, sign + SIGN, axis)
        index?.draw(canvas, x, axis + indexAxis)
    }

    override fun collectRows(out: MutableList<PlacedRow>, dx: Int, dy: Int) {
        index?.collectRows(out, dx, dy + indexAxis)
        radicand.collectRows(out, dx + signLeft + SIGN, dy)
    }

    private companion object {
        const val SIGN = 4
        const val STROKE = 3
        const val TICK_RISE = 4
    }
}

/** `|x|`: a bar each side, one row taller than the content above and below. A guess at the shape. */
internal class BarsBox(private val content: Box) : Box() {
    override val width: Int = content.width + EXTRA
    override val above: Int = content.above + 1
    override val below: Int = content.below + 1

    override fun draw(canvas: LcdCanvas, x: Int, axis: Int) {
        canvas.vertical(x + 1, axis - above, height)
        canvas.vertical(x + width - 1, axis - above, height)
        content.draw(canvas, x + 2, axis)
    }

    override fun collectRows(out: MutableList<PlacedRow>, dx: Int, dy: Int) = content.collectRows(out, dx + 2, dy)

    private companion object {
        /** Leading column, left bar, the space before the right bar, right bar. */
        const val EXTRA = 4
    }
}

/** A box above and a box below a middle one, all centred: Σ with its limits. */
internal class ColumnBox(private val top: Box, private val middle: Box, private val bottom: Box) : Box() {
    override val width: Int = maxOf(top.width, middle.width, bottom.width)
    override val above: Int = middle.above + top.height
    override val below: Int = middle.below + bottom.height

    override fun draw(canvas: LcdCanvas, x: Int, axis: Int) {
        middle.draw(canvas, x + centre(middle), axis)
        top.draw(canvas, x + centre(top), topAxis(axis))
        bottom.draw(canvas, x + centre(bottom), bottomAxis(axis))
    }

    override fun collectRows(out: MutableList<PlacedRow>, dx: Int, dy: Int) {
        top.collectRows(out, dx + centre(top), topAxis(dy))
        middle.collectRows(out, dx + centre(middle), dy)
        bottom.collectRows(out, dx + centre(bottom), bottomAxis(dy))
    }

    private fun centre(box: Box) = (width - box.width) / 2

    private fun topAxis(axis: Int) = axis - middle.above - top.below

    private fun bottomAxis(axis: Int) = axis + middle.below + bottom.above
}

/** A dotted square standing in for an empty template slot, as the Natural display shows one. */
internal class PlaceholderBox(private val font: LcdFont) : Box() {
    override val width: Int = font.inkWidth + 1
    override val above: Int = font.axis
    override val below: Int = font.below

    override fun draw(canvas: LcdCanvas, x: Int, axis: Int) {
        val top = axis - above
        for (row in 0 until height) {
            for (col in 0 until font.inkWidth) {
                val edge = row == 0 || row == height - 1 || col == 0 || col == font.inkWidth - 1
                if (edge && (row + col) % 2 == 0) canvas.plot(x + 1 + col, top + row)
            }
        }
    }
}

/** A Natural-display template: its drawing, and the rows of its slots for the cursor to find. */
internal class TemplateBox(val kind: TemplateKind, private val inner: Box) : Box() {
    override val width: Int get() = inner.width
    override val above: Int get() = inner.above
    override val below: Int get() = inner.below

    override fun draw(canvas: LcdCanvas, x: Int, axis: Int) = inner.draw(canvas, x, axis)

    override val endsInScript: Boolean get() = inner.endsInScript

    /** Stays a [TemplateBox], so a cursor path through it still finds its slots. */
    override fun withScriptGap(): Box = if (endsInScript) TemplateBox(kind, inner.withScriptGap()) else this

    /** The slot rows in slot order (the order ▶ visits them), offsets relative to this box. */
    fun slotRows(): List<PlacedRow> =
        ArrayList<PlacedRow>().also { inner.collectRows(it, 0, 0) }.sortedBy { it.row.slotIndex }
}
