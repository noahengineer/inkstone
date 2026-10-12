package dev.inkstone.core.calc.lcd

/**
 * One [dev.inkstone.core.calc.input.Expr] laid out: its items' boxes side by side. It is what a
 * cursor path walks through — [slotIndex] says which slot of its template it fills (−1 for the
 * root), and [gapX] says where the insert cursor stands in front of item `n`.
 *
 * An empty slot shows a dotted square ([placeholder]) and the cursor stands inside it. An empty
 * root shows nothing, with the cursor at column 0 ([G p.20]).
 */
internal class ExprRow(private val items: List<Box>, val slotIndex: Int, base: LcdFont, placeholder: Box?) : Box() {
    private val parts: List<Box> = if (items.isEmpty() && placeholder != null) listOf(placeholder) else items
    private val offsets = leftEdges(parts)
    private val placeholderShown = items.isEmpty() && placeholder != null
    override val width: Int = offsets[parts.size]

    /** At least a glyph of the row's own font, so an empty or all-small row still has a cursor height. */
    override val above: Int = maxOf(base.axis, parts.maxOfOrNull { it.above } ?: 0)
    override val below: Int = maxOf(base.below, parts.maxOfOrNull { it.below } ?: 0)

    val itemCount: Int get() = items.size

    fun item(index: Int): Box? = items.getOrNull(index)

    fun itemOffset(index: Int): Int = offsets[index]

    /** Column, relative to the row, of the insert cursor in front of item [index] (or after the last). */
    fun gapX(index: Int): Int = if (placeholderShown) 1 else offsets[index]

    override fun draw(canvas: LcdCanvas, x: Int, axis: Int) {
        parts.forEachIndexed { i, part -> part.draw(canvas, x + offsets[i], axis) }
    }

    override fun collectRows(out: MutableList<PlacedRow>, dx: Int, dy: Int) {
        out += PlacedRow(this, dx, dy)
    }

    companion object {
        const val ROOT = -1
    }
}

/**
 * `∫` with its limits to the right of the sign, upper at the top and lower at the bottom, then the
 * integrand and `dx`. The sign spans the taller of the integrand and the two stacked limits. The
 * shape is ours: research 01 §3.6 lists ∫ among the templates still unmeasured.
 */
internal class IntegralBox(private val upper: Box, private val lower: Box, private val body: Box) : Box() {
    private val signTop = minOf(-body.above, -upper.height)
    private val signBottom = maxOf(body.below - 1, lower.height)
    private val limitsWidth = maxOf(upper.width, lower.width)
    override val width: Int = 1 + SIGN + limitsWidth + body.width
    override val above: Int = -signTop
    override val below: Int = signBottom + 1

    override fun draw(canvas: LcdCanvas, x: Int, axis: Int) {
        val sign = x + 1
        canvas.horizontal(sign + 1, axis + signTop, 2)
        canvas.vertical(sign + 1, axis + signTop + 1, signBottom - signTop - 1)
        canvas.horizontal(sign, axis + signBottom, 2)
        upper.draw(canvas, x + LIMITS, axis + upperAxis())
        lower.draw(canvas, x + LIMITS, axis + lowerAxis())
        body.draw(canvas, x + LIMITS + limitsWidth, axis)
    }

    override fun collectRows(out: MutableList<PlacedRow>, dx: Int, dy: Int) {
        body.collectRows(out, dx + LIMITS + limitsWidth, dy)
        lower.collectRows(out, dx + LIMITS, dy + lowerAxis())
        upper.collectRows(out, dx + LIMITS, dy + upperAxis())
    }

    private fun upperAxis() = signTop + upper.above

    private fun lowerAxis() = signBottom - lower.below + 1

    private companion object {
        const val SIGN = 3
        const val LIMITS = 1 + SIGN
    }
}

/**
 * `d/dx(f)|x=a`: the small stacked `d/dx`, the bracketed function, a bar, and `x=a` as a subscript
 * hanging from the axis. Our shape; unmeasured.
 */
internal class DerivativeBox(private val body: Box, private val at: Box) : Box() {
    private val barBottom = maxOf(body.below - 1, at.height)
    override val width: Int = body.width + 2 + at.width
    override val above: Int = body.above
    override val below: Int = barBottom + 1

    override fun draw(canvas: LcdCanvas, x: Int, axis: Int) {
        body.draw(canvas, x, axis)
        canvas.vertical(x + body.width + 1, axis - body.above, body.above + barBottom + 1)
        at.draw(canvas, x + body.width + 2, axis + atAxis())
    }

    override fun collectRows(out: MutableList<PlacedRow>, dx: Int, dy: Int) {
        body.collectRows(out, dx, dy)
        at.collectRows(out, dx + body.width + 2, dy + atAxis())
    }

    private fun atAxis() = 1 + at.above
}
