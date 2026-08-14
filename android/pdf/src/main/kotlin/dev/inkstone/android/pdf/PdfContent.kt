package dev.inkstone.android.pdf

import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PdfPageGeometry
import dev.inkstone.core.model.StrokeOutline
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * PDF content streams, as bytes.
 *
 * Written by hand rather than through `PDPageContentStream` for three reasons,
 * in order of weight:
 *
 * 1. **No font machinery.** `PDPageContentStream.showText` wants a `PDFont`, and
 *    PdfBox-Android's standard-14 fonts come out of the AAR's assets through
 *    `PDFBoxResourceLoader`, which needs a `Context`. The invisible text layer
 *    (SPEC §5.5) needs one font and one operator; going through the font stack for
 *    that would drag an Android dependency into the one part of this module that
 *    does not otherwise need one — and would take the JVM unit tests with it.
 * 2. **Exact output.** A stroke's appearance stream is a few thousand `l`
 *    operators. What they are is decided here, in one readable place, rather than
 *    inferred from a builder's state machine.
 * 3. **Determinism.** Same outlines in, same bytes out. Nothing samples a clock.
 *
 * Everything here takes **page-space** coordinates (SPEC §4.4) and converts to PDF
 * user space through the page's own [PdfPageGeometry]. Nothing in this file knows
 * what a CropBox is; it only knows to ask.
 */
internal object PdfContent {

    /**
     * A stroke's `/AP /N` content: its outline, filled.
     *
     * SPEC §6.4 — `/AP` takes rendering precedence over `/InkList`, so this is what
     * viewers draw, and filling the *outline* is what preserves the pressure
     * variation `/Ink` cannot express.
     *
     * **Nonzero winding** (`f`, not `f*`). A stroke that crosses itself is one
     * shape, and even-odd would punch a hole through every self-intersection.
     * Erasure is already subtracted from the contours before they get here — see
     * [StrokeOutline].
     */
    fun strokeAppearance(outline: StrokeOutline, geometry: PdfPageGeometry, alphaState: String?): ByteArray =
        buildContent {
            // The whole appearance sits inside one q/Q so a viewer that concatenates
            // appearance streams cannot leak this stroke's colour into the next.
            line("q")
            if (alphaState != null) line("/$alphaState gs")
            val color = outline.colorArgb
            line("${component(color shr RED_SHIFT)} ${component(color shr GREEN_SHIFT)} ${component(color)} rg")
            appendPolygons(outline.contours, geometry)
            line("f")
            line("Q")
        }

    /**
     * SPEC §6.4 — the >500-stroke case: *"a single flattened page overlay, no
     * per-stroke annotations"*.
     *
     * The same fills, in the page's own content stream rather than in an
     * annotation. That is a real flatten: no `/Annots` array to walk, so a viewer
     * pays page-content cost and nothing else. The bundle remains the source of
     * truth, and Recovered mode for such a page can only get a flat image back —
     * which is why [PdfPageKeys.FLATTENED] is written beside it.
     */
    fun flattenedOverlay(outlines: List<StrokeOutline>, geometry: PdfPageGeometry) = buildContent {
        line("q")
        var currentAlpha: String? = null
        for (outline in outlines) {
            if (outline.isEmpty) continue
            val state = stateNameFor(outline)
            if (state != currentAlpha) {
                // One `gs` per change rather than per stroke: a page of 2,000
                // strokes is 2,000 redundant operators otherwise, and they are
                // parsed by every viewer on every draw.
                line("/$state gs")
                currentAlpha = state
            }
            val color = outline.colorArgb
            line("${component(color shr RED_SHIFT)} ${component(color shr GREEN_SHIFT)} ${component(color)} rg")
            appendPolygons(outline.contours, geometry)
            line("f")
        }
        line("Q")
    }

    /**
     * SPEC §5.5 — the invisible searchable text layer, rendering mode `3 Tr`.
     *
     * **Scaffolding in M4.** The path is built and written (with an empty word
     * list, so every published file already has the machinery and none of the
     * text); M7 supplies the words from ML Kit and the per-word boxes from the
     * strokes that produced them.
     *
     * Each word is placed and horizontally scaled to fill its own box, which is
     * the thing OCR pipelines approximate and Inkstone knows exactly: the box comes
     * from the strokes, not from a guess about a rasterised glyph.
     */
    fun invisibleText(words: List<InvisibleWord>, geometry: PdfPageGeometry, font: String): ByteArray {
        // A word with no text, or no height to sit in, has no position to be searched at.
        // ⚠️ A word containing anything outside printable ASCII is *skipped*, not written: a
        // literal string's bytes are glyph codes and [ContentBuilder.toByteArray] refuses
        // non-ASCII loudly (M8c), so one accented word must degrade to "not searchable in this
        // PDF" — never fail the whole publish, which is what it did between M8c and this filter.
        // It is not transliterated either: a lookalike puts the wrong word into search. In-app
        // search is unaffected (the word table never comes through here). Carrying such a word
        // in the PDF needs the CID font path ([PdfCidFont]), which has no production caller yet.
        val placeable = words.filter { word ->
            word.text.isNotEmpty() &&
                abs(word.bottomPt - word.topPt) > 0f &&
                word.text.all { it.code in PRINTABLE_ASCII }
        }
        if (placeable.isEmpty()) return ByteArray(0)
        return buildContent {
            line("q")
            line("BT")
            line("3 Tr")
            for (word in placeable) placeWord(word, geometry, font)
            line("ET")
            line("Q")
        }
    }

    /**
     * ⚠️ **The `Tm` is the page's whole geometry, not a converted origin** (M8c).
     *
     * This wrote `1 0 0 1 x y Tm` from M4 to M8b: the right *place*, and the identity *rotation*.
     * On a `/Rotate 90` scan — SPEC §4.4's headline case, and the ordinary shape of a lecture PDF
     * — every recognised word was laid across the handwriting at ninety degrees. Nothing caught
     * it from either side: the layer is invisible, so no visual check could, and `pdftotext`
     * reports the characters whatever the matrix says, so no extraction check could either.
     * [PdfPlacement] is the fix and the KDoc there is the reasoning.
     *
     * On an unrotated page the composition still reduces to `1 0 0 1 x y`, which is why the M4-era
     * assertions in `InvisibleTextLayerTest` are unchanged: the bug was only ever visible on the
     * fixture pages nothing was asserting against.
     */
    private fun ContentBuilder.placeWord(word: InvisibleWord, geometry: PdfPageGeometry, font: String) {
        val height = abs(word.bottomPt - word.topPt)
        // Helvetica's average advance is ~0.5 em; scaling horizontally to the measured
        // box is what makes a text selection follow the handwriting instead of drifting
        // across the line. Ours is exact where OCR's is a guess — the box came from the
        // strokes (SPEC §5.5).
        val width = abs(word.rightPt - word.leftPt)
        val nominal = word.text.length * height * NOMINAL_ADVANCE_EM
        val horizontal = if (nominal > 0f) (width / nominal) * PERCENT else PERCENT
        // A recognised word is in page space at the identity: the strokes that produced it were
        // authored there (SPEC §4.4 rule 1) and nothing has moved them.
        val matrix = PdfPlacement.textLine(geometry, Matrix3.Identity, word.leftPt, word.bottomPt)
        line("/$font ${number(height)} Tf")
        line("${number(horizontal)} Tz")
        line("${PdfPlacement.args(matrix)} Tm")
        line("${literalString(word.text)} Tj")
    }

    // -----------------------------------------------------------------------
    // Primitives
    // -----------------------------------------------------------------------

    private fun ContentBuilder.appendPolygons(contours: List<List<PagePoint>>, geometry: PdfPageGeometry) {
        for (contour in contours) {
            if (contour.size < MIN_POLYGON_POINTS) continue
            for ((index, point) in contour.withIndex()) {
                val user = geometry.toPdfUser(point)
                line("${number(user.x)} ${number(user.y)} ${if (index == 0) "m" else "l"}")
            }
            // `h` closes the subpath explicitly. A fill would close it anyway; saying
            // so keeps the last segment from being a join the renderer has to infer.
            line("h")
        }
    }

    fun alpha(colorArgb: Int): Int = (colorArgb ushr ALPHA_SHIFT) and BYTE_MASK

    /**
     * The `/ExtGState` name for one composited alpha, 0..255.
     *
     * Derived rather than passed as a map so the content stream and the resource dictionary that
     * has to satisfy it cannot name different things: `PdfBoxWriter` calls this too, and M4's
     * defect was exactly a name defined in one place and used in another.
     *
     * Keyed on the **alpha alone**, so a translucent stroke, a translucent text box and a
     * translucent image at the same alpha share one entry — which they should, because a graphics
     * state is a graphics state.
     */
    fun stateName(alpha: Int): String = "GS$alpha"

    /** [stateName] for an outline's own alpha. */
    fun stateNameFor(outline: StrokeOutline): String = stateName(alpha(outline.colorArgb))

    /** The `/ca` value for an ARGB colour, as a PDF real. */
    fun alphaValue(colorArgb: Int): Float = alpha(colorArgb) / BYTE_MASK.toFloat()

    private fun component(shifted: Int): String = number((shifted and BYTE_MASK) / BYTE_MASK.toFloat())

    /**
     * A PDF real, locale-free.
     *
     * `String.format` would honour the device locale and emit `0,5` on half of
     * Europe's tablets, which produces a file that parses as two numbers. PDF has
     * no locale; neither does this.
     */
    fun number(value: Float): String {
        if (!value.isFinite()) return "0"
        val scaled = (value.toDouble() * SCALE).roundToLong()
        if (scaled == 0L) return "0"
        val negative = scaled < 0
        var digits = abs(scaled).toString().padStart(DECIMALS + 1, '0')
        val whole = digits.dropLast(DECIMALS)
        var fraction = digits.takeLast(DECIMALS).trimEnd('0')
        digits = if (fraction.isEmpty()) whole else "$whole.$fraction"
        return if (negative) "-$digits" else digits
    }

    /**
     * ⭐ A PDF **hex string**, `<00480065…>` — how a text box's words reach the content stream.
     *
     * ⚠️ **Never a `(…)` literal, and the reason is structural rather than stylistic.** With
     * `Identity-H` a character is a two-byte CID, and those bytes are freely `0x00`, `0x28` and
     * `0x29` — an unbalanced `(` or a premature `)` inside a literal ends the string early and
     * corrupts every operator after it. Hex has no delimiters to escape, so the class of bug does
     * not exist. It is also the only form `ContentBuilder`'s ASCII invariant can carry: a CID is
     * not a character, and writing it as one is precisely the mod-256 truncation M8c removed.
     *
     * The digits are **checked**. Passing user text here instead of `PdfCidFont.hex`'s output
     * would otherwise produce a syntactically valid string of the wrong glyphs, which renders,
     * extracts, and says something else.
     */
    fun hexString(digits: String): String {
        require(digits.length % HEX_PAIR == 0) {
            "a hex string is whole bytes; '$digits' is ${digits.length} digits"
        }
        require(digits.all { it in HEX_DIGITS }) {
            "'$digits' is not hex — text must come through PdfCidFont.hex, not straight from a user"
        }
        return "<$digits>"
    }

    /**
     * A PDF **literal string**, `(hello)`, escaping `(`, `)` and `\`.
     *
     * The two callers left are the invisible layer (SPEC §5.5, recognised words) and a text box
     * falling back to a standard-14 base font (`PdfPageFont.Standard`). Both are ASCII-only by
     * construction — `ContentBuilder.toByteArray` refuses anything else — so the escaping here is
     * complete for what can actually reach it. Anything wider goes through [hexString].
     */
    fun literalString(text: String): String = buildString(text.length + LITERAL_OVERHEAD) {
        append('(')
        for (char in text) {
            when (char) {
                '(', ')', '\\' -> append('\\').append(char)
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(char)
            }
        }
        append(')')
    }

    private fun buildContent(block: ContentBuilder.() -> Unit): ByteArray = ContentBuilder().apply(block).toByteArray()

    // An ARGB int's channel offsets. These are the layout, not tunables.
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8

    private const val BYTE_MASK = 0xFF
    private const val DECIMALS = 3
    private const val SCALE = 1000.0
    private const val MIN_POLYGON_POINTS = 3
    private const val PERCENT = 100f

    /** Helvetica's rough average advance, in ems. Only used to scale a word to its own box. */
    private const val NOMINAL_ADVANCE_EM = 0.5f

    /** What a literal string may carry — see the filter in [invisibleText]. */
    private const val PRINTABLE_MIN = 0x20
    private const val PRINTABLE_MAX = 0x7E
    private val PRINTABLE_ASCII: IntRange = PRINTABLE_MIN..PRINTABLE_MAX

    /** Two hex digits to a byte. See [hexString]. */
    private const val HEX_PAIR = 2

    /** `(`, `)`, and a little room for escapes. */
    private const val LITERAL_OVERHEAD = 8

    private val HEX_DIGITS: Set<Char> = ('0'..'9').toSet() + ('a'..'f') + ('A'..'F')
}

/** A content stream under construction. `\n` separated, ASCII, no trailing state. */
internal class ContentBuilder {
    private val out = StringBuilder(INITIAL_CAPACITY)

    fun line(text: String) {
        out.append(text).append('\n')
    }

    /**
     * ⭐ **ASCII only, and it is now checked rather than assumed** (M8c).
     *
     * This was `ByteArray(out.length) { out[it].code.toByte() }` with a comment explaining that
     * Latin-1 was preferable to UTF-8 — which is true and was not the problem. The problem is that
     * a `Char` above `U+00FF` truncates mod 256 into a **different, valid** byte: no exception, no
     * corruption a parser would notice, just a different letter. M4 accepted that for an invisible
     * layer holding recognised handwriting; M8c types into a text box through an IME, which is
     * precisely how such characters arrive.
     *
     * Switching to UTF-8 would be *worse*, not better: a literal string's bytes are glyph codes in
     * the font's encoding, so UTF-8 would widen one character into two wrong codes.
     *
     * The real answer is that **no character above ASCII belongs in a content stream at all**.
     * Text is emitted as a hex string of CID codes ([hexString]) and everything else here is PDF
     * syntax. So the invariant is checkable, and an unrepresentable character is a programming
     * error upstream — the one thing CLAUDE.md keeps exceptions for.
     */
    fun toByteArray(): ByteArray = ByteArray(out.length) { index ->
        val code = out[index].code
        require(code <= MAX_ASCII) {
            "content streams are ASCII; U+${code.toString(HEX).uppercase().padStart(CODEPOINT_DIGITS, '0')} " +
                "reached the buffer. Text must go through PdfCidFont.hex, not a literal string."
        }
        code.toByte()
    }

    private companion object {
        const val INITIAL_CAPACITY = 4096
        const val MAX_ASCII = 0x7F
        const val HEX = 16

        /** `U+0041`, not `U+41` — the conventional four-digit form of a BMP code point. */
        const val CODEPOINT_DIGITS = 4
    }
}
