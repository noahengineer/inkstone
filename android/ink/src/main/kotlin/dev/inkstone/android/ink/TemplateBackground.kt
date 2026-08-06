package dev.inkstone.android.ink

import android.graphics.Canvas
import android.graphics.Paint
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PageTemplate

/**
 * Draws the five built-in page templates (ROADMAP M2), in **page space**.
 *
 * Page space, not view space, is what makes this reusable: the canvas already carries the
 * camera when this runs, so the same code rules a page on screen, in a thumbnail (SPEC §5.7)
 * and — at M4 — in the published PDF. Ruling a page in view space would mean line spacing that
 * changed with zoom, which is the kind of thing that looks fine until you print it.
 *
 * The metrics come from [PageTemplate] rather than living here, for the same reason.
 *
 * [Background.SourcePdfPage] draws nothing: the page image is `:android:pdf`'s to render
 * (M4), and drawing a placeholder rule over someone's lecture slides would be worse than
 * drawing nothing.
 */
internal class TemplateBackground {

    private val linePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = LINE_WIDTH_PT
        color = LINE_COLOR
    }

    private val heavyPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = HEAVY_LINE_WIDTH_PT
        color = HEAVY_LINE_COLOR
    }

    private val dotPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = HEAVY_LINE_COLOR
    }

    fun draw(canvas: Canvas, background: Background, page: PageSize) {
        val template = (background as? Background.Template)?.template ?: return
        when (template) {
            PageTemplate.BLANK -> Unit
            PageTemplate.RULED -> drawRules(canvas, page, fromX = 0f, toX = page.widthPt)
            PageTemplate.GRID -> drawGrid(canvas, page, template.spacingPt)
            PageTemplate.DOT_GRID -> drawDots(canvas, page, template.spacingPt)
            PageTemplate.CORNELL -> drawCornell(canvas, page, template.spacingPt)
        }
    }

    private fun drawRules(canvas: Canvas, page: PageSize, fromX: Float, toX: Float, stopY: Float = page.heightPt) {
        val spacing = PageTemplate.RULED.spacingPt
        var y = PageTemplate.RULED_MARGIN_PT
        while (y < stopY - PageTemplate.RULED_MARGIN_PT / 2f) {
            canvas.drawLine(fromX, y, toX, y, linePaint)
            y += spacing
        }
    }

    private fun drawGrid(canvas: Canvas, page: PageSize, spacing: Float) {
        var y = spacing
        while (y < page.heightPt) {
            canvas.drawLine(0f, y, page.widthPt, y, linePaint)
            y += spacing
        }
        var x = spacing
        while (x < page.widthPt) {
            canvas.drawLine(x, 0f, x, page.heightPt, linePaint)
            x += spacing
        }
    }

    private fun drawDots(canvas: Canvas, page: PageSize, spacing: Float) {
        // One dot per intersection. A path of circles would be fewer draw calls but the dots
        // are already the cheapest template — a Letter page at 18pt is about 1,400 of them, and
        // this runs once per invalidation, not per stroke.
        var y = spacing
        while (y < page.heightPt) {
            var x = spacing
            while (x < page.widthPt) {
                canvas.drawCircle(x, y, DOT_RADIUS_PT, dotPaint)
                x += spacing
            }
            y += spacing
        }
    }

    /**
     * Cornell notes: a cue column on the left, a summary band at the bottom, rules in the note
     * area. The proportions are the standard ones and live on [PageTemplate].
     */
    private fun drawCornell(canvas: Canvas, page: PageSize, spacing: Float) {
        val cueX = page.widthPt * PageTemplate.CORNELL_CUE_FRACTION
        val summaryY = page.heightPt * (1f - PageTemplate.CORNELL_SUMMARY_FRACTION)
        drawRules(canvas, page, fromX = cueX, toX = page.widthPt, stopY = summaryY)
        canvas.drawLine(cueX, 0f, cueX, summaryY, heavyPaint)
        canvas.drawLine(0f, summaryY, page.widthPt, summaryY, heavyPaint)
        // Deliberately unruled: the cue column is for keywords and the summary band for prose,
        // and ruling either one is what makes a Cornell template feel like a form.
        if (spacing <= 0f) return
    }

    private companion object {
        const val LINE_COLOR = 0x1A2E4A6B
        const val HEAVY_LINE_COLOR = 0x33334455
        const val LINE_WIDTH_PT = 0.5f
        const val HEAVY_LINE_WIDTH_PT = 0.9f
        const val DOT_RADIUS_PT = 0.7f
    }
}
