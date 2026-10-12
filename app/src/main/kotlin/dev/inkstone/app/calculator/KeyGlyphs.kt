package dev.inkstone.app.calculator

/**
 * The typeface a run of key-cap or legend text is set in (research 01 §7).
 *
 * Words and digits are a clean sans; the mathematical letters *x*, *e*, *i* and *π* are serif
 * italic on the real keys, and that is most of what makes `x²` read as maths rather than as a label.
 */
internal enum class GlyphFace { SANS, SERIF_ITALIC }

/**
 * One piece of printed text on the face, as plain data — what `KeyCapText` lays out and draws.
 *
 * Its own tiny vocabulary rather than a string, because several legends are not a line of text at
 * all: the fraction key is a filled bar **over** a hollow box, `d/dx` is a stack, `∫` and `Σ` carry
 * limit boxes, and Casio's placeholder squares (▪ filled, ▯ hollow) are drawn shapes whose size has
 * to follow the text beside them. A Unicode `■` would be whatever size the fallback font made it.
 */
internal sealed interface Glyph {

    /** Text on the baseline. */
    data class Run(val text: String, val face: GlyphFace = GlyphFace.SANS) : Glyph

    /** A superscript ([up]) or subscript, set smaller. */
    data class Script(val body: List<Glyph>, val up: Boolean) : Glyph

    /** [top] over [bottom], small, centred on each other; a fraction when [bar], limits when not. */
    data class Stack(val top: List<Glyph>, val bottom: List<Glyph>, val bar: Boolean) : Glyph

    /** Casio's placeholder square: ▪ when [filled], ▯ when not. */
    data class Slot(val filled: Boolean) : Glyph

    /** The filled bar ▬ — the fraction key's numerator and the tail of `∫▯▬`. */
    data object Bar : Glyph
}

/**
 * The colours printed on the face (research 01 §5.1). Named for what they mean rather than what they
 * look like, because the meaning is the rule: gold is SHIFT, magenta is ALPHA, cyan is BASE-N,
 * violet is CMPLX, dark grey brackets are "only in that mode".
 */
internal enum class LegendInk { SHIFT, ALPHA, BASE, CMPLX, MODE, LABEL }

/**
 * A legend printed above a key: [glyphs] in [ink], optionally inside corner brackets ┌ ┐ in
 * [bracket] — whose colour may differ from the text's (┌A┐ is a magenta A in **cyan** brackets).
 */
internal data class Legend(val glyphs: List<Glyph>, val ink: LegendInk, val bracket: LegendInk? = null)

// A small builder vocabulary for the key table, so 50 keys read as the printed face rather than as
// constructor calls. Private to the table's package by being `internal`; nothing else should need it.

internal fun t(text: String): Glyph = Glyph.Run(text)

internal fun math(text: String): Glyph = Glyph.Run(text, GlyphFace.SERIF_ITALIC)

internal fun sup(vararg body: Glyph): Glyph = Glyph.Script(body.toList(), up = true)

internal fun sub(vararg body: Glyph): Glyph = Glyph.Script(body.toList(), up = false)

internal fun over(top: Glyph, bottom: Glyph): Glyph = Glyph.Stack(listOf(top), listOf(bottom), bar = true)

internal fun limits(top: Glyph, bottom: Glyph): Glyph = Glyph.Stack(listOf(top), listOf(bottom), bar = false)

internal val FILLED: Glyph = Glyph.Slot(filled = true)
internal val HOLLOW: Glyph = Glyph.Slot(filled = false)

internal fun gold(vararg glyphs: Glyph, bracket: LegendInk? = null) = Legend(glyphs.toList(), LegendInk.SHIFT, bracket)

internal fun magenta(vararg glyphs: Glyph, bracket: LegendInk? = null) =
    Legend(glyphs.toList(), LegendInk.ALPHA, bracket)

internal fun ink(colour: LegendInk, vararg glyphs: Glyph) = Legend(glyphs.toList(), colour)
