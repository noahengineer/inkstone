package dev.inkstone.android.pdf

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PageTemplate

/**
 * SPEC §4.1's five built-in page templates, drawn as vector content.
 *
 * ## Why it is a file of its own
 *
 * Split out of [PdfContent] in M8c, and the split is along a real seam rather than a size one.
 * Everything left in that file converts **page-space geometry the user made** into PDF operators
 * — a stroke's outline, a recognised word's box, a text box's lines. This draws **paper**: the
 * rules are part of the page, they are written into the generated base rather than into the update
 * section (SPEC §5.2), and nothing here has ever needed a [dev.inkstone.core.model.PdfPageGeometry]
 * because a generated base page's CropBox is `[0 0 w h]` with no rotation, so a y-flip is the only
 * conversion there is.
 *
 * The metrics all come from [PageTemplate] in `:core:model`, for the reason that section gives:
 * one set of numbers, so the screen, the thumbnail and the PDF cannot rule a page three different
 * ways.
 */
internal object PdfTemplateContent {

    /**
     * A page template as vector content (SPEC §4.1's five built-ins).
     *
     * Drawn into the generated base page, not into the update section: the rules
     * are part of the paper, and a note exported to someone else should show the
     * lines the writer saw. The metrics come from [PageTemplate] in `:core:model`
     * for exactly that reason — one set of numbers, so the screen, the thumbnail
     * and the PDF cannot rule a page three different ways.
     */
    fun forPage(background: Background, size: PageSize): ByteArray {
        val template = (background as? Background.Template)?.template ?: return ByteArray(0)
        if (template == PageTemplate.BLANK) return ByteArray(0)
        return ContentBuilder().apply {
            line("q")
            line("${PdfContent.number(RULE_GRAY)} ${PdfContent.number(RULE_GRAY)} ${PdfContent.number(RULE_GRAY)} RG")
            line("${PdfContent.number(RULE_WIDTH_PT)} w")
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
        }.toByteArray()
    }

    private fun ContentBuilder.horizontalRules(size: PageSize, spacing: Float, margin: Float) {
        var y = margin
        while (y <= size.heightPt - margin) {
            rule(0f, size.heightPt - y, size.widthPt, size.heightPt - y)
            y += spacing
        }
    }

    private fun ContentBuilder.rule(x0: Float, y0: Float, x1: Float, y1: Float) {
        line(
            "${PdfContent.number(x0)} ${PdfContent.number(y0)} m ${PdfContent.number(x1)} ${PdfContent.number(y1)} l S",
        )
    }

    private fun ContentBuilder.verticalRules(size: PageSize, spacing: Float) {
        var x = spacing
        while (x < size.widthPt) {
            rule(x, 0f, x, size.heightPt)
            x += spacing
        }
    }

    private fun ContentBuilder.dots(size: PageSize, spacing: Float) {
        line("${PdfContent.number(RULE_GRAY)} ${PdfContent.number(RULE_GRAY)} ${PdfContent.number(RULE_GRAY)} rg")
        // A dot is a tiny square: a circle would be four Béziers each, and at this
        // radius nothing on earth can tell the difference.
        val side = PdfContent.number(DOT_RADIUS_PT * 2)
        var y = spacing
        while (y < size.heightPt) {
            var x = spacing
            while (x < size.widthPt) {
                val left = PdfContent.number(x - DOT_RADIUS_PT)
                val bottom = PdfContent.number(size.heightPt - y - DOT_RADIUS_PT)
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

    private const val RULE_GRAY = 0.78f
    private const val RULE_WIDTH_PT = 0.5f
    private const val DOT_RADIUS_PT = 0.6f
}
