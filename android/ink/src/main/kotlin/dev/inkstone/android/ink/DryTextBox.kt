package dev.inkstone.android.ink

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import dev.inkstone.android.ink.InkColor.withOpacity
import dev.inkstone.core.model.ListStyle
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.TextAlign
import dev.inkstone.core.model.TextBoxObject
import dev.inkstone.core.model.TextFont
import kotlin.math.max

/**
 * ⭐ M8c — a [TextBoxObject] the dry layer actually **draws**.
 *
 * Replaces `DryPlaceholder` for text, which is what that type's KDoc always asked for: *"a kind
 * that paints itself replaces this type rather than configuring it"*.
 *
 * ## The words and the formatting come from two registers, and that is the format's whole point
 *
 * [DryBox.content] is `SetTextContent` and [DryBox.decodedFormat] is `SetFormat`. SPEC §4.9.2
 * separates them so that changing the font on one device cannot discard a paragraph typed on the
 * other; nothing here may merge them, and nothing here may reconstruct one from the other.
 *
 * Colour and opacity are **not** in the format record — `ObjectStyle` already carried them before
 * M8c, which is why the scope line's "colour" needed no new field at all (SPEC §4.9.2).
 *
 * ## ⚠️ The layout is built when something changes, never in `onDraw`
 *
 * `StaticLayout` measures every glyph, breaks every line and allocates. CLAUDE.md rule 7 forbids
 * that on the ink path, and a text box on a page being written on is redrawn on every dry-layer
 * invalidation. So it is rebuilt eagerly in [onPresentationChanged] — which is reached only from a
 * toolbar action, a page load, an undo or a peer's op, all of them off the pen-to-pixel path — and
 * `onDraw` does nothing but [StaticLayout.draw].
 *
 * **The box's width never changes**, which is what makes that cache sound: `widthPt` is on the
 * first-write-wins payload (SPEC §4.2) and a resize is a `SetTransform`, so the wrap width is fixed
 * for the object's life and a scaled text box re-wraps nothing. That is also the honest behaviour —
 * dragging a corner scales the words with the frame, exactly as it scales a photo.
 *
 * ## ⚠️ Laid out at [LAYOUT_SCALE]×, drawn at 1/[LAYOUT_SCALE]
 *
 * The canvas is in **page points** when this is painted, so the natural thing is to lay out with
 * `textSize = 14` and a wrap width of `120`. Two things in `StaticLayout` are integers, and at
 * those magnitudes both are coarse: the wrap width is an `Int`, and line height comes from
 * `Paint.getFontMetricsInt`. A 14 pt line rounds to a 16 pt line, which is a 4 % error compounded
 * once per line — twenty lines of a paragraph end up several points from where they belong, and
 * the published PDF (which lays text out in floats, SPEC §4.9.5) would then disagree with the
 * screen about where every line after the first sits. Laying out eight times larger and scaling the
 * canvas down moves both quantisations to an eighth of a point, which is below what any of this
 * can express. It costs one `canvas.scale` per text box per frame.
 *
 * ## Overflow is drawn, not clipped
 *
 * A box whose words no longer fit its declared height paints past it. Clipping would hide what the
 * user typed because the box was created a line too short, and nothing in the vocabulary lets a
 * height grow on its own (`heightPt` is payload). [DryObject.bounds] is widened to the painted
 * extent so culling, the erase layer and the lasso all agree with what is on the glass; the
 * **silhouette** deliberately stays the declared rectangle ([DryBox.forEachPagePoint]), because the
 * frame a resize is computed against has to be the object's own box.
 */
internal class DryTextBox(box: TextBoxObject) : DryBox(box, box.widthPt, box.heightPt, box.text) {

    /**
     * ⚠️ `isLinearText` and `isSubpixelText` are not polish.
     *
     * Without them glyph advances are hinted to whole units at the size the paint was measured at,
     * and this paint is then drawn through a canvas the camera has scaled — so the hinting error is
     * multiplied along with everything else and the letters of a word visibly mis-space when the
     * user zooms in. They are the two flags that make a paint safe to measure at one size and draw
     * at another, which is exactly what a page-space layout does.
     */
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        isLinearText = true
        isSubpixelText = true
    }

    /**
     * The laid-out text, in [LAYOUT_SCALE]× page points. Rebuilt only by [onPresentationChanged].
     *
     * A property initialiser rather than a lazy, so that the first frame after an edit costs a draw
     * and nothing else — see the class KDoc.
     */
    var layout: StaticLayout = buildLayout()
        private set

    init {
        widenBoundsToText()
    }

    override fun onPresentationChanged() {
        layout = buildLayout()
        widenBoundsToText()
    }

    /**
     * The painted extent, in the object's own space.
     *
     * Through [DryObject.setBounds], so the page-space box is rebuilt with the own-space one — the
     * pair is written together or not at all, which is the invariant [DryObject] exists to own.
     */
    private fun widenBoundsToText() {
        setBounds(PageBounds(0f, 0f, widthPt, max(heightPt, layout.height / LAYOUT_SCALE)))
    }

    private fun buildLayout(): StaticLayout {
        val format = decodedFormat
        val style = effectiveStyle()
        paint.typeface = typefaceFor(format.font)
        paint.textSize = format.sizePt * LAYOUT_SCALE
        paint.color = style.colorArgb.withOpacity(style.opacity)
        val text = decorated(content.orEmpty(), format)
        // At least one unit wide: `StaticLayout` rejects a negative width and loops forever on a
        // zero one, and a text box resized to nothing is expressible from a peer running any build.
        val width = (widthPt * LAYOUT_SCALE).toInt().coerceAtLeast(1)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignmentFor(format.align))
            // The page's own spacing, with no platform padding above the first line or below the
            // last — a text box's top edge is where the user put it, not where a font's recommended
            // leading happens to fall.
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }

    /**
     * The content with list markers and indentation applied, or the content itself when it is
     * neither a list nor indented.
     *
     * ## Why the marker is text and the indent is a span
     *
     * A bullet has to participate in line breaking and in alignment, so it belongs *in* the string.
     * The indent does not — and a wrapped line has to hang under the first word rather than under
     * the bullet, which is what [LeadingMarginSpan.Standard]'s two-argument form says and what
     * makes a list read as a list rather than as an indented paragraph.
     *
     * ⚠️ **[ObjectFormat] carries one list style and one level for the whole object**, because it
     * is a per-object register (SPEC §4.9.2) and not a per-paragraph one. So every paragraph is an
     * item, and a numbered list numbers them from one. `listLevel` is the indent and is applied
     * even when `listStyle` is `NONE` — the two fields are independent, and refusing to indent an
     * unmarked block would make a level nobody could see.
     */
    private fun decorated(text: String, format: ObjectFormat): CharSequence {
        val indent = format.listLevel * INDENT_PT * LAYOUT_SCALE
        if (format.listStyle == ListStyle.NONE && indent <= 0f) return text
        val builder = SpannableStringBuilder()
        var number = 1
        // ⚠️ The separator is decided by the **index**, not by whether anything has been appended
        // yet. A first paragraph that is empty — a text box beginning with a blank line, which is
        // how somebody makes room above a heading — leaves the builder empty after one pass, and an
        // `isNotEmpty` test would then swallow that newline and shift the whole box up a line.
        for ((index, paragraph) in text.split('\n').withIndex()) {
            if (index > 0) builder.append('\n')
            val marker = when (format.listStyle) {
                ListStyle.NONE -> ""
                ListStyle.BULLET -> BULLET
                ListStyle.NUMBERED -> "${number++}. "
            }
            val start = builder.length
            builder.append(marker).append(paragraph)
            // Whole paragraphs, first character to last: a `ParagraphStyle` attached across a
            // newline is applied to neither side of it and the indent silently disappears.
            builder.setSpan(
                LeadingMarginSpan.Standard(indent.toInt(), (indent + paint.measureText(marker)).toInt()),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return builder
    }

    /**
     * ⚠️ **False, deliberately, even though the coverage test is easy here.**
     *
     * `pruneFullyErased` drops what this returns true for, and dropping a text box means the words
     * are off the screen until the page is reloaded — an undo of the erase writes `SetErasedRegion`
     * at an object the store no longer holds, and `applyRegister` answers false for one. For a
     * stroke that trade is already accepted (the geometry is in the log either way); for a box the
     * saving is one `saveLayer` and the cost is the user's paragraph disappearing from a gesture
     * they can undo everywhere else. Not worth it.
     */
    override fun isErasedAway(region: ErasedRegion): Boolean = false

    private fun typefaceFor(font: TextFont): Typeface = when (font) {
        TextFont.SANS -> Typeface.SANS_SERIF
        TextFont.SERIF -> Typeface.SERIF
        TextFont.MONO -> Typeface.MONOSPACE
    }

    private fun alignmentFor(align: TextAlign): Layout.Alignment = when (align) {
        // NORMAL/OPPOSITE rather than LEFT/RIGHT, which is why the enum is START/END: a text box
        // typed in Arabic through the IME the font decision was made for (SPEC §4.9.5) starts on
        // the right, and an alignment named for a side would put it on the wrong one.
        TextAlign.START -> Layout.Alignment.ALIGN_NORMAL

        TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER

        TextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
    }

    internal companion object {
        /** See the class KDoc — this is what keeps `StaticLayout`'s two integers out of the way. */
        const val LAYOUT_SCALE = 8f

        /** One nesting level, in page points. Roughly two characters at the default 14 pt. */
        const val INDENT_PT = 18f

        /** U+2022 and two spaces — the marker plus the gap, so `measureText` sizes the hang. */
        const val BULLET = "•  "
    }
}
