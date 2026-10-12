package dev.inkstone.android.ink

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PageTemplate
import kotlin.math.ceil
import kotlin.math.roundToInt

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

    /** Fills the dot lattice through [dotShader] — see [drawDots]. Filtered, never antialiased. */
    private val dotFill = Paint().apply {
        style = Paint.Style.FILL
        isFilterBitmap = true
    }

    /** One cell of the lattice with its dot in the middle, baked at [dotTileCellPx] pixels a side. */
    private var dotShader: BitmapShader? = null
    private var dotTileCellPx = 0
    private var dotTileSpacingPt = 0f
    private val dotShaderMatrix = Matrix()

    /**
     * @param pxPerPt how many device pixels a page point is drawn at — the camera's scale on screen,
     *   the thumbnail's in the library. Only the dot grid reads it; see [drawDots].
     */
    fun draw(canvas: Canvas, background: Background, page: PageSize, pxPerPt: Float = 1f) {
        val template = (background as? Background.Template)?.template ?: return
        when (template) {
            PageTemplate.BLANK -> Unit
            PageTemplate.RULED -> drawRules(canvas, page, fromX = 0f, toX = page.widthPt)
            PageTemplate.GRID -> drawGrid(canvas, page, template.spacingPt)
            PageTemplate.DOT_GRID -> drawDots(canvas, page, template.spacingPt, pxPerPt)
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

    /**
     * One dot at every multiple of [spacing] strictly inside the page — **one draw, however many
     * dots.**
     *
     * ## Why a shader, not a circle per dot
     *
     * This drew one `drawCircle` per dot, on the reasoning that *"a Letter page at 18pt is about
     * 1,400 of them"*. That stopped being the bound when the canvas became infinite (SPEC §4.5): the
     * paper grows to hold the ink, and the physics note's is 5,364 × 2,376 pt — **39,000 circles**,
     * recorded on the UI thread on every re-record of the dry layer, then replayed by the
     * RenderThread. In the open's profile the dots were 11% of the main thread and fourteen times the
     * cost of painting every object on screen, because the objects are culled to the viewport and
     * the dots were not. Culling them would not have been enough either: at fit-width that page
     * shows every dot.
     *
     * So one cell — a square of [spacing] with its dot in the middle — is baked into a tile at the
     * pixel density this page is being drawn at, and repeated across the lattice with a
     * [BitmapShader]: one rectangle, whatever the page's size or the zoom. The same answer M10 gave
     * the editor's backdrop (`rememberDotGridBrush`).
     *
     * ## What it preserves
     *
     * - **The lattice.** Dots sit at exactly `k × spacing` for `k ≥ 1` and stop short of the paper's
     *   far edge, as before — `SnapGrid` snaps to these points and assumes them.
     * - **The look.** The tile is rebuilt whenever the drawn size of a cell changes by a whole pixel,
     *   so each recording rasterises its dots at about 1:1. A pinch in flight stretches it, exactly
     *   as it stretches the cached page around it, and the settle pass rebuilds it sharp.
     *
     * The published PDF draws its own lattice (`PdfTemplateContent`), so nothing here reaches a file.
     */
    private fun drawDots(canvas: Canvas, page: PageSize, spacing: Float, pxPerPt: Float) {
        if (!(spacing > 0f)) return
        // How many dots fit along each axis: k = 1, 2, … while k × spacing < the extent.
        val columns = ceil(page.widthPt / spacing).toInt() - 1
        val rows = ceil(page.heightPt / spacing).toInt() - 1
        if (columns < 1 || rows < 1) return
        dotFill.shader = dotShaderFor(spacing, pxPerPt)
        // Each tile is centred on its dot, so the rectangle runs half a cell either side of the first
        // and last ones — and covers exactly the cells of k = 1..columns and 1..rows.
        val half = spacing / 2f
        canvas.drawRect(half, half, columns * spacing + half, rows * spacing + half, dotFill)
    }

    /**
     * The tile for a cell of [spacing] drawn at [pxPerPt], rebuilt only when that is a different
     * whole number of pixels. The previous tile is not recycled: a recorded display list may still
     * be holding it, and it is a few kilobytes.
     */
    private fun dotShaderFor(spacing: Float, pxPerPt: Float): BitmapShader {
        val cellPx = (spacing * pxPerPt).roundToInt().coerceIn(MIN_TILE_PX, MAX_TILE_PX)
        dotShader?.let { if (cellPx == dotTileCellPx && spacing == dotTileSpacingPt) return it }
        val tile = Bitmap.createBitmap(cellPx, cellPx, Bitmap.Config.ARGB_8888)
        val tilePerPt = cellPx / spacing
        val centre = cellPx / 2f
        Canvas(tile).drawCircle(centre, centre, DOT_RADIUS_PT * tilePerPt, tileDotPaint)
        // Tile pixels to page points, with the tile's centre — its dot — on every multiple of the
        // spacing: scale a cell down to `spacing`, then shift it back half a cell.
        dotShaderMatrix.setScale(1f / tilePerPt, 1f / tilePerPt)
        dotShaderMatrix.postTranslate(-spacing / 2f, -spacing / 2f)
        val shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        shader.setLocalMatrix(dotShaderMatrix)
        dotShader = shader
        dotTileCellPx = cellPx
        dotTileSpacingPt = spacing
        return shader
    }

    /** Draws the one dot into a tile. The colour the per-dot circles used to be painted in. */
    private val tileDotPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = HEAVY_LINE_COLOR
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

        /**
         * The smallest a baked cell may be. Below it the dot is already a fraction of a pixel — a
         * thumbnail of a grown canvas, or the editor zoomed right out — and a tile this size draws
         * the same faint speck the per-dot circles did.
         */
        const val MIN_TILE_PX = 4

        /**
         * The largest — a cell at 1,600% on a 340 dpi tablet is ~600 px, and a tile capped here is
         * stretched by at most that ratio, at a zoom where a dot is a disc twenty pixels across.
         */
        const val MAX_TILE_PX = 512
    }
}
