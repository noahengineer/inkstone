package dev.inkstone.android.pdf

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PageTemplate
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
        val placeable = words.filter { it.text.isNotEmpty() && abs(it.bottomPt - it.topPt) > 0f }
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

    private fun ContentBuilder.placeWord(word: InvisibleWord, geometry: PdfPageGeometry, font: String) {
        val baseline = geometry.toPdfUser(PagePoint(word.leftPt, word.bottomPt))
        val height = abs(word.bottomPt - word.topPt)
        // Helvetica's average advance is ~0.5 em; scaling horizontally to the measured
        // box is what makes a text selection follow the handwriting instead of drifting
        // across the line. Ours is exact where OCR's is a guess — the box came from the
        // strokes (SPEC §5.5).
        val width = abs(word.rightPt - word.leftPt)
        val nominal = word.text.length * height * NOMINAL_ADVANCE_EM
        val horizontal = if (nominal > 0f) (width / nominal) * PERCENT else PERCENT
        line("/$font ${number(height)} Tf")
        line("${number(horizontal)} Tz")
        line("1 0 0 1 ${number(baseline.x)} ${number(baseline.y)} Tm")
        line("(${escapeText(word.text)}) Tj")
    }

    /**
     * A page template as vector content (SPEC §4.1's five built-ins).
     *
     * Drawn into the generated base page, not into the update section: the rules
     * are part of the paper, and a note exported to someone else should show the
     * lines the writer saw. The metrics come from [PageTemplate] in `:core:model`
     * for exactly that reason — one set of numbers, so the screen, the thumbnail
     * and the PDF cannot rule a page three different ways.
     */
    fun template(background: Background, size: PageSize): ByteArray {
        val template = (background as? Background.Template)?.template ?: return ByteArray(0)
        if (template == PageTemplate.BLANK) return ByteArray(0)
        return buildContent {
            line("q")
            line("${number(RULE_GRAY)} ${number(RULE_GRAY)} ${number(RULE_GRAY)} RG")
            line("${number(RULE_WIDTH_PT)} w")
            when (template) {
                PageTemplate.RULED -> horizontalRules(size, template.spacingPt, PageTemplate.RULED_MARGIN_PT)

                PageTemplate.GRID -> {
                    horizontalRules(size, template.spacingPt, 0f)
                    verticalRules(size, template.spacingPt)
                }

                PageTemplate.DOT_GRID -> dots(size, template.spacingPt)

                PageTemplate.CORNELL -> cornell(size, template.spacingPt)

                PageTemplate.BLANK -> Unit
            }
            line("Q")
        }
    }

    // -----------------------------------------------------------------------
    // Template shapes. Page space is y-down; a generated base page's CropBox is
    // [0 0 w h] with no rotation, so the flip is the only conversion needed.
    // -----------------------------------------------------------------------

    private fun ContentBuilder.horizontalRules(size: PageSize, spacing: Float, margin: Float) {
        var y = margin
        while (y <= size.heightPt - margin) {
            rule(0f, size.heightPt - y, size.widthPt, size.heightPt - y)
            y += spacing
        }
    }

    private fun ContentBuilder.rule(x0: Float, y0: Float, x1: Float, y1: Float) {
        line("${number(x0)} ${number(y0)} m ${number(x1)} ${number(y1)} l S")
    }

    private fun ContentBuilder.verticalRules(size: PageSize, spacing: Float) {
        var x = spacing
        while (x < size.widthPt) {
            rule(x, 0f, x, size.heightPt)
            x += spacing
        }
    }

    private fun ContentBuilder.dots(size: PageSize, spacing: Float) {
        line("${number(RULE_GRAY)} ${number(RULE_GRAY)} ${number(RULE_GRAY)} rg")
        // A dot is a tiny square: a circle would be four Béziers each, and at this
        // radius nothing on earth can tell the difference.
        val side = number(DOT_RADIUS_PT * 2)
        var y = spacing
        while (y < size.heightPt) {
            var x = spacing
            while (x < size.widthPt) {
                val left = number(x - DOT_RADIUS_PT)
                val bottom = number(size.heightPt - y - DOT_RADIUS_PT)
                line("$left $bottom $side $side re f")
                x += spacing
            }
            y += spacing
        }
    }

    private fun ContentBuilder.cornell(size: PageSize, spacing: Float) {
        val cue = size.widthPt * PageTemplate.CORNELL_CUE_FRACTION
        val summary = size.heightPt * PageTemplate.CORNELL_SUMMARY_FRACTION
        var y = PageTemplate.RULED_MARGIN_PT
        while (y <= size.heightPt - summary) {
            rule(cue, size.heightPt - y, size.widthPt, size.heightPt - y)
            y += spacing
        }
        rule(cue, 0f, cue, size.heightPt)
        rule(0f, summary, size.widthPt, summary)
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
     * The `/ExtGState` name for an outline's compositing.
     *
     * Derived rather than passed as a map so the content stream and the resource
     * dictionary that has to satisfy it cannot name different things: `PdfBoxWriter`
     * calls this too.
     */
    fun stateNameFor(outline: StrokeOutline): String = "GS${alpha(outline.colorArgb)}"

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

    /** PDF literal strings escape `(`, `)` and `\`. */
    private fun escapeText(text: String): String = buildString {
        for (char in text) {
            when (char) {
                '(', ')', '\\' -> append('\\').append(char)
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(char)
            }
        }
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
    private const val RULE_GRAY = 0.78f
    private const val RULE_WIDTH_PT = 0.5f
    private const val DOT_RADIUS_PT = 0.6f
    private const val PERCENT = 100f

    /** Helvetica's rough average advance, in ems. Only used to scale a word to its own box. */
    private const val NOMINAL_ADVANCE_EM = 0.5f
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
            "content streams are ASCII; U+${code.toString(HEX).uppercase().padStart(4, '0')} " +
                "reached the buffer. Text must go through PdfCidFont.hex, not a literal string."
        }
        code.toByte()
    }

    private companion object {
        const val INITIAL_CAPACITY = 4096
        const val MAX_ASCII = 0x7F
        const val HEX = 16
    }
}
