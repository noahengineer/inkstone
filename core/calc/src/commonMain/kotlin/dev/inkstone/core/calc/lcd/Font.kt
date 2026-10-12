package dev.inkstone.core.calc.lcd

/**
 * One bitmap glyph: [width] × [height] dots of ink, row-major, `'#'` = dark.
 *
 * Kept as the source rows rather than packed bits: a font is a few hundred of these, read once per
 * key press, and the rows are what a reviewer compares against research 01 §3.6.
 */
internal class Glyph(private val rows: List<String>) {
    val width: Int = rows.firstOrNull()?.length ?: 0
    val height: Int = rows.size

    init {
        require(rows.all { row -> row.length == width && row.all { it == INK || it == PAPER } }) {
            "a glyph is a rectangle of '#' and '.': $rows"
        }
    }

    operator fun get(x: Int, y: Int): Boolean = rows[y][x] == INK

    companion object {
        const val INK = '#'
        const val PAPER = '.'
    }
}

/**
 * A bitmap font for the 96 × 31 matrix (decision D1: our own glyphs on Casio's measured cells).
 *
 * Every character occupies a **cell** of one blank leading column followed by its ink: the
 * measured screens put the first glyph of a line on columns 1–5 and the last glyph of a result on
 * columns 91–95 (see [LcdGeometry] for why that is one column right of research 01 §3.6's
 * numbers). [advance] is that cell width — 6 for the two monospace fonts, ink width + 1 for the
 * proportional [TINY] font of the editors.
 *
 * Text is split into **keys**, not characters: a base letter and its combining marks (`x̄`, `x̂`)
 * are one glyph, and so is the compact `×₁₀` of the `×10ˣ` key (Tok.EXP10). A key with no glyph
 * draws as a hollow box ([missing]) so a gap in the tables is visible on screen, never silent.
 */
internal class LcdFont(
    /** Glyph rows. */
    val height: Int,
    /** The math axis: the row of a glyph's `−` bar, where fraction bars line up. */
    val axis: Int,
    /** Ink width of an ordinary glyph, also the size of the missing-glyph box. */
    val inkWidth: Int,
    /** 0 = proportional (advance = glyph width + 1). */
    private val fixedAdvance: Int,
    table: List<Pair<String, List<String>>>,
) {
    private val glyphs: Map<String, Glyph> = table.associate { (key, rows) ->
        require(rows.size == height) { "glyph '$key' has ${rows.size} rows, the font has $height" }
        key to Glyph(rows)
    }

    /** Keys longer than one character that are not a base plus combining marks: just `×₁₀`. */
    private val ligatures: List<String> = glyphs.keys
        .filter { key -> key.length > 1 && key.drop(1).any { !it.isCombining() } }
        .sortedByDescending { it.length }

    val missing: Glyph = Glyph(boxRows(inkWidth, height))

    /** Rows below the axis, the axis row included. */
    val below: Int get() = height - axis

    /** Every key this font draws. */
    val glyphKeys: Set<String> get() = glyphs.keys

    fun glyph(key: String): Glyph? = glyphs[key]

    fun has(key: String): Boolean = key in glyphs

    fun advance(key: String): Int = if (fixedAdvance > 0) fixedAdvance else (glyphs[key] ?: missing).width + 1

    fun width(text: String): Int = keys(text).sumOf { advance(it) }

    /** Splits [text] into glyph keys: ligatures first, then a character with its combining marks. */
    fun keys(text: String): List<String> {
        val out = ArrayList<String>(text.length)
        var i = 0
        while (i < text.length) {
            val ligature = ligatures.firstOrNull { text.startsWith(it, i) }
            val end = if (ligature != null) i + ligature.length else combiningEnd(text, i + 1)
            out += text.substring(i, end)
            i = end
        }
        return out
    }

    companion object {
        /** 5 × 9 on a 6-dot advance: input and result lines (research 01 §3.6). */
        val LARGE: LcdFont by lazy { LcdFont(LARGE_HEIGHT, LARGE_AXIS, FULL_INK, CELL, largeGlyphs()) }

        /** 5 × 6 on a 6 × 8 cell: menus, prompts, and everything nested in Natural display. */
        val SMALL: LcdFont by lazy { LcdFont(SMALL_HEIGHT, SMALL_AXIS, FULL_INK, CELL, smallGlyphs()) }

        /** 3 × 5, proportional: editor cells, headers and row numbers (SA EQN captures). */
        val TINY: LcdFont by lazy { LcdFont(TINY_HEIGHT, TINY_AXIS, TINY_INK, 0, tinyGlyphs()) }

        /** 5 × 7 capitals of the MODE menu's labels (SA slides 2 and 15). */
        val MENU: LcdFont by lazy { LcdFont(MENU_HEIGHT, MENU_AXIS, FULL_INK, CELL, menuGlyphs()) }

        /** 5 × 5 digits drawn light inside the MODE menu's dark 7 × 7 number cells. */
        val INVERTED: LcdFont by lazy { LcdFont(INVERTED_HEIGHT, INVERTED_AXIS, FULL_INK, CELL, invertedDigits()) }

        const val CELL = 6
        const val FULL_INK = 5
        const val LARGE_HEIGHT = 9
        const val LARGE_AXIS = 4
        const val SMALL_HEIGHT = 6
        const val SMALL_AXIS = 3
        const val TINY_HEIGHT = 5
        const val TINY_AXIS = 2
        const val TINY_INK = 3
        const val MENU_HEIGHT = 7
        const val MENU_AXIS = 3
        const val INVERTED_HEIGHT = 5
        const val INVERTED_AXIS = 2
    }
}

private const val COMBINING_FIRST = '̀'
private const val COMBINING_LAST = 'ͯ'

private fun Char.isCombining(): Boolean = this in COMBINING_FIRST..COMBINING_LAST

private fun combiningEnd(text: String, from: Int): Int {
    var end = from
    while (end < text.length && text[end].isCombining()) end++
    return end
}

private fun boxRows(width: Int, height: Int): List<String> = List(height) { y ->
    if (y == 0 || y == height - 1) {
        "#".repeat(width)
    } else {
        "#" + ".".repeat(width - 2) + "#"
    }
}

/** The picture of [key]'s glyph (or of the missing-glyph box), one string per row. */
internal fun LcdFont.rowsOf(key: String): List<String> {
    val glyph = glyph(key) ?: missing
    return List(glyph.height) { y ->
        CharArray(glyph.width) { x -> if (glyph[x, y]) Glyph.INK else Glyph.PAPER }.concatToString()
    }
}

/** Splits a one-line glyph picture, rows separated by spaces: `".#. ##. .#."`. */
internal fun rows(picture: String): List<String> = picture.split(' ')

/** A glyph whose picture is [picture], rows separated by spaces. */
internal infix fun String.drawn(picture: String): Pair<String, List<String>> = this to rows(picture)

/**
 * Places another font's glyph inside a taller or wider cell: [top] blank rows above it, padded to
 * [height] rows and centred in [width] columns. This is how a superscript `²` in the large font is
 * literally the small `2` raised to the top of the cell, and a subscript `₁` is the small `1` sitting
 * on the baseline — research 01 §3.6 measures `X₁=` as exactly that.
 */
internal fun placed(source: List<String>, width: Int, height: Int, top: Int): List<String> {
    val sourceWidth = source.firstOrNull()?.length ?: 0
    val left = (width - sourceWidth) / 2
    val right = width - sourceWidth - left
    val blank = ".".repeat(width)
    return List(height) { y ->
        val sourceRow = source.getOrNull(y - top)
        if (sourceRow == null) blank else ".".repeat(left) + sourceRow + ".".repeat(right)
    }
}
