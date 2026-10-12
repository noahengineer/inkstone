package dev.inkstone.core.calc.lcd

/**
 * A mutable 96 × 31 dot matrix that the layout code draws into and then [freeze]s into an
 * immutable [LcdFrame]. One per frame: the engine builds a new frame per key press.
 *
 * Every write is clipped — to the matrix, and to the current clip rectangle — and anything
 * outside is dropped without complaint. Layout code relies on that: a scrolled input line simply
 * draws at a negative x and lets the clip keep the ◀ marker's cell clean.
 */
internal class LcdCanvas {
    private val pixels = BooleanArray(LcdFrame.WIDTH * LcdFrame.HEIGHT)
    private var clip = Clip(0, 0, LcdFrame.WIDTH, LcdFrame.HEIGHT)

    operator fun get(x: Int, y: Int): Boolean =
        x in 0 until LcdFrame.WIDTH && y in 0 until LcdFrame.HEIGHT && pixels[y * LcdFrame.WIDTH + x]

    fun plot(x: Int, y: Int, on: Boolean = true) {
        if (clip.contains(x, y)) pixels[y * LcdFrame.WIDTH + x] = on
    }

    fun fill(x: Int, y: Int, width: Int, height: Int, on: Boolean = true) {
        for (row in y until y + height) for (col in x until x + width) plot(col, row, on)
    }

    /** Inverts every dot in the rectangle: a highlighted editor cell, a MODE-menu number cell. */
    fun invert(x: Int, y: Int, width: Int, height: Int) {
        for (row in y until y + height) for (col in x until x + width) plot(col, row, !this[col, row])
    }

    fun horizontal(x: Int, y: Int, length: Int) = fill(x, y, length, 1)

    fun vertical(x: Int, y: Int, length: Int) = fill(x, y, 1, length)

    /**
     * Draws one glyph with its top-left ink dot at ([x], [y]). [inverse] clears the glyph's dots
     * instead of setting them, which is how light text is written into a dark cell.
     */
    fun glyph(font: LcdFont, key: String, x: Int, y: Int, inverse: Boolean = false) {
        val glyph = font.glyph(key) ?: font.missing
        for (row in 0 until glyph.height) {
            for (col in 0 until glyph.width) {
                if (glyph[col, row]) plot(x + col, y + row, !inverse)
            }
        }
    }

    /**
     * Draws [text] in [font] starting at cell column [x] — the cell's leading blank column — with
     * the glyphs' top row at [y]. Returns the column after the last cell.
     */
    fun text(font: LcdFont, text: String, x: Int, y: Int, inverse: Boolean = false): Int {
        var cell = x
        for (key in font.keys(text)) {
            glyph(font, key, cell + 1, y, inverse)
            cell += font.advance(key)
        }
        return cell
    }

    /** Runs [block] with drawing confined to the given rectangle (right and bottom exclusive). */
    fun clipped(left: Int, top: Int, right: Int, bottom: Int, block: () -> Unit) {
        val saved = clip
        clip = Clip(
            maxOf(saved.left, left),
            maxOf(saved.top, top),
            minOf(saved.right, right),
            minOf(saved.bottom, bottom),
        )
        try {
            block()
        } finally {
            clip = saved
        }
    }

    fun freeze(indicators: Set<Indicator>, cursor: LcdCursor?, text: String): LcdFrame =
        LcdFrame(pixels.copyOf(), indicators, cursor, text = text)

    private data class Clip(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
    }
}
