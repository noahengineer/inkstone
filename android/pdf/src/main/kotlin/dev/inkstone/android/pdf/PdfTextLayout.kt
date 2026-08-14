package dev.inkstone.android.pdf

import dev.inkstone.core.model.ListStyle
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.TextAlign
import dev.inkstone.core.model.TextFont

/**
 * The font a page's content stream names, in the two forms this module can produce.
 *
 * Two, not one, and the split is *coverage against cost*:
 *
 * | | Carries | Costs | Chosen when |
 * |---|---|---|---|
 * | [Embedded] | everything SPEC §4.9.5 measured — Latin-1, Latin Ext-A/B, Cyrillic, most Greek | a subset of the face in every published file | `PdfFontFaces` supplies a face |
 * | [Standard] | printable ASCII | nothing at all | it does not |
 *
 * ⚠️ **[Standard] is capped at ASCII rather than at WinAnsi, and that is a property of the
 * *stream*, not of the font.** A `(…)` literal's bytes are glyph codes, and `ContentBuilder`
 * requires ASCII precisely so that a character above `U+00FF` cannot truncate mod 256 into a
 * different, valid letter (docs/M8-TEXT.md §3.1). Latin-1 would need those bytes. So the honest
 * boundary for the literal path is what the buffer will carry, and anything beyond it is what the
 * embedded face is for.
 *
 * Both answer [show] with an **already-delimited** operand, so a caller cannot pair the wrong
 * delimiter with the wrong encoding — the failure that puts an unbalanced `(` into a content
 * stream and corrupts everything after it.
 */
internal sealed interface PdfPageFont {

    /** The name the content stream uses, and the key the page's `/Resources /Font` must define. */
    val resourceName: String

    /** SPEC §4.9.5's gate: a character this font cannot draw is dropped, never guessed at. */
    fun canRepresent(codePoint: Int): Boolean

    /** [text] as a show-text operand, delimiters included — `<0041…>` or `(A…)`. */
    fun show(text: String): String

    /** The advance of [text] at size 1, in text space. Multiply by the `Tf` size for points. */
    fun widthEm(text: String): Float

    /** A subset-embedded CID font (SPEC §4.9.5). Real metrics, and `/ToUnicode` for extraction. */
    class Embedded(override val resourceName: String, private val font: PdfCidFont) : PdfPageFont {
        override fun canRepresent(codePoint: Int): Boolean = font.canRepresent(codePoint)

        override fun show(text: String): String = PdfContent.hexString(font.hex(text))

        override fun widthEm(text: String): Float = font.widthEm(text)
    }

    /**
     * One of PDF's fourteen base fonts, named and not embedded.
     *
     * [nominalAdvanceEm] is an average, because the real metrics live in an AFM file that
     * pdfbox-android keeps in its assets behind `PDFBoxResourceLoader` — the `Context` this module
     * declines to take. It is used for **wrapping and alignment only**, so being a few percent out
     * moves a line break by at most a word; it never affects which characters appear or whether
     * they extract. Courier is monospaced and therefore exact.
     */
    class Standard(override val resourceName: String, val baseFont: String, private val nominalAdvanceEm: Float) :
        PdfPageFont {
        override fun canRepresent(codePoint: Int): Boolean = codePoint in PRINTABLE_ASCII

        override fun show(text: String): String = PdfContent.literalString(text)

        override fun widthEm(text: String): Float = text.length * nominalAdvanceEm
    }

    companion object {

        /** What a [TextFont] falls back to when no embeddable face is supplied. */
        fun standardFor(resourceName: String, font: TextFont): Standard = when (font) {
            TextFont.SANS -> Standard(resourceName, "Helvetica", HELVETICA_ADVANCE_EM)
            TextFont.SERIF -> Standard(resourceName, "Times-Roman", TIMES_ADVANCE_EM)
            TextFont.MONO -> Standard(resourceName, "Courier", COURIER_ADVANCE_EM)
        }

        private const val HELVETICA_ADVANCE_EM = 0.5f
        private const val TIMES_ADVANCE_EM = 0.47f

        /** Exact: Courier is monospaced at 600/1000 em. */
        private const val COURIER_ADVANCE_EM = 0.6f

        private val PRINTABLE_ASCII: IntRange = 0x20..0x7E
    }
}

/**
 * One line of a text box, ready for a `Tm` — positioned in the **object's own space**, y down,
 * origin at the box's top-left.
 *
 * @property leftPt where the line starts, alignment and list indent already applied.
 * @property baselinePt the baseline's distance below the top of the box.
 */
internal data class LaidLine(val text: String, val leftPt: Float, val baselinePt: Float)

/**
 * ⭐ Turning a text box's `SetTextContent` register into lines a PDF can place.
 *
 * ## What lives here and what deliberately does not
 *
 * The register holds **the words the user typed** and nothing about their presentation (SPEC
 * §4.9.2 — the two are separate registers precisely so that restyling cannot discard a paragraph).
 * So every line break below the user's own `\n`s is computed here, from [ObjectFormat] and the
 * font's metrics.
 *
 * ⚠️ **The editor computes its own line breaks and this is a second implementation of that.** They
 * will not always agree to the pixel: the editor measures with Android's `Paint`, this measures
 * with the embedded face's `/W` advances or a base font's average. A disagreement shows as a word
 * moving between lines in the published file — visible, harmless, and *not* the kind of failure
 * that hides. The alternative, persisting the editor's line breaks, would put a rendering decision
 * into the synced format where a peer with a different screen would inherit it.
 *
 * ## Characters the font cannot draw are dropped here, once
 *
 * SPEC §4.9.5: reported, never guessed. `.notdef` puts a visible box in the PDF and nothing in the
 * extraction; a lookalike substitution puts the **wrong word** into search, which is worse than an
 * absent one. Dropping happens before measuring, so a line's width is the width of what is
 * actually drawn.
 */
internal object TextBoxLayout {

    /**
     * @param widthPt the box's width in its own space. Lines wrap to it.
     */
    fun lay(text: String, format: ObjectFormat, font: PdfPageFont, widthPt: Float): List<LaidLine> {
        val sizePt = sizeOf(format)
        val indentPt = format.listLevel * INDENT_EM * sizePt
        // Never non-positive: a box narrower than its own list indent would otherwise wrap every
        // line to zero width and loop, or divide by nothing. One em is the least-wrong floor.
        val contentPt = (widthPt - indentPt).coerceAtLeast(sizePt)

        val lines = mutableListOf<LaidLine>()
        var ordinal = 1
        var row = 0
        for (paragraph in drawable(text, font).split('\n')) {
            val marker = markerFor(format.listStyle, ordinal, font)
            if (format.listStyle != ListStyle.NONE) ordinal++
            val markerPt = font.widthEm(marker) * sizePt
            val wrapped = wrap(paragraph, font, sizePt, (contentPt - markerPt).coerceAtLeast(sizePt))

            for ((index, line) in wrapped.withIndex()) {
                // A hanging indent: the marker sits in the gutter and continuation lines line up
                // under the first word, not under the bullet. Anything else makes a two-line
                // bullet read as two bullets.
                val body = if (index == 0) marker + line else line
                val hangPt = if (index == 0) 0f else markerPt
                val bodyPt = font.widthEm(body) * sizePt
                lines += LaidLine(
                    text = body,
                    leftPt = indentPt + hangPt + alignmentOffset(format.align, contentPt - hangPt, bodyPt),
                    baselinePt = ASCENT_EM * sizePt + row * LINE_HEIGHT_EM * sizePt,
                )
                row++
            }
        }
        return lines.filter { it.text.isNotEmpty() }
    }

    /**
     * The `Tf` size, guarded.
     *
     * `ObjectFormatCodec` already refuses a non-positive size on decode, so this can only fire for
     * a record built in code. It exists so that the size the layout measures with and the size the
     * `Tf` operator states are the same expression — two guards would eventually be one guard.
     */
    fun sizeOf(format: ObjectFormat): Float = format.sizePt.takeIf { it > 0f } ?: ObjectFormat.DEFAULT_SIZE_PT

    // -----------------------------------------------------------------------

    /**
     * [text] with every code point the font cannot draw removed — and `\n` kept, because the
     * paragraph structure is the user's and not the font's.
     *
     * Iterated by **code point**, not by `Char`: a surrogate half tested on its own is never
     * representable, so a `filter` over chars would delete every astral character even from a font
     * that carried it, and would leave the other half behind to be written as a lone surrogate.
     */
    private fun drawable(text: String, font: PdfPageFont): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val width = Character.charCount(codePoint)
            if (codePoint == '\n'.code || font.canRepresent(codePoint)) {
                out.append(text, index, index + width)
            }
            index += width
        }
        return out.toString()
    }

    /**
     * Greedy wrapping on spaces.
     *
     * A word wider than the whole line gets a line of its own and overflows it: breaking inside a
     * word needs hyphenation rules per language, and a URL cut in half is worse than one that
     * pokes out of its box. The box clips (see `PdfContent.textBox`), so the overflow is bounded.
     *
     * An empty paragraph yields one empty line, which is how a blank line between paragraphs
     * survives — `split` on an empty string would otherwise drop it and close the gap.
     */
    private fun wrap(paragraph: String, font: PdfPageFont, sizePt: Float, maxPt: Float): List<String> {
        val words = paragraph.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && font.widthEm(candidate) * sizePt > maxPt) {
                lines += current.toString()
                current = StringBuilder(word)
            } else {
                current = StringBuilder(candidate)
            }
        }
        lines += current.toString()
        return lines
    }

    private fun alignmentOffset(align: TextAlign, availablePt: Float, linePt: Float): Float {
        val slack = (availablePt - linePt).coerceAtLeast(0f)
        return when (align) {
            TextAlign.START -> 0f
            TextAlign.CENTER -> slack / 2f
            TextAlign.END -> slack
        }
    }

    /**
     * The list marker, or an empty string.
     *
     * ⚠️ The bullet degrades with the font. `U+2022` is outside a base font's ASCII range, so a
     * text box published without an embeddable face would otherwise lose its bullets entirely and
     * a nested list would read as a flat one. A hyphen is a bullet a reader recognises.
     */
    private fun markerFor(style: ListStyle, ordinal: Int, font: PdfPageFont): String = when (style) {
        ListStyle.NONE -> ""
        ListStyle.BULLET -> if (font.canRepresent(BULLET.code)) "$BULLET " else "- "
        ListStyle.NUMBERED -> "$ordinal. "
    }

    private const val BULLET = '•'

    /** One list level, in ems of the text's own size — so indenting scales with the type. */
    private const val INDENT_EM = 1.5f

    /**
     * ⚠️ Typographic constants, **not** read from the face, and the editor has its own copy.
     *
     * Reading `hhea`'s ascender and line gap would be more faithful and would still not agree with
     * the editor, which measures through Android's `Paint`. Two approximations that are close is
     * the honest state of this; two exact numbers from different sources would look authoritative
     * and still differ. If a text box ever looks vertically offset in a viewer next to the editor,
     * these are the two numbers to reconcile — and reconciling means making the editor read them
     * from here, not adding a third.
     */
    private const val ASCENT_EM = 0.8f

    private const val LINE_HEIGHT_EM = 1.2f
}
