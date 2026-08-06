package dev.inkstone.android.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.ink.brush.TextureBitmapStore
import androidx.ink.geometry.Box
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.PageSize

/**
 * Draws a page — paper, template, ink, erasures — onto a [Canvas] that already carries the
 * page→canvas transform.
 *
 * ## Why this is its own class
 *
 * It is used by two things that must not disagree: [DryStrokesView], which draws what the
 * user is looking at, and [InkPageThumbnailRenderer], which draws the picture the library
 * shows of the same page. A thumbnail whose rules are spaced differently, or whose
 * highlighter sits behind the ink, is a bug the user sees as "the preview is wrong"
 * — and it is exactly what happens when two renderers are maintained side by side.
 *
 * The three things that have to be shared, and were duplicated before this existed:
 *
 * 1. **Render order.** [StrokeStore.forEachInRenderOrder] emits highlighters *last*, with
 *    plain alpha, so a highlight tints the writing it covers (SPEC §3.4 — reversed after
 *    M4; [InkBrushKind.drawsOverInk] records why, and why a multiply blend was rejected).
 * 2. **Erasure compositing.** An erased stroke is drawn into an offscreen layer and the
 *    swept region punched out with `DST_OUT` (SPEC §3.5). Clipping would be cheaper and
 *    its edge is only as good as the hardware renderer's clip antialiasing.
 * 3. **The `strokeToScreenTransform` argument.** It is not a drawing transform — the
 *    canvas carries that. Its linear component sizes the antialiasing feather, in stroke
 *    units, so passing the wrong one makes every edge soft. [DryStrokesView] documents the
 *    device bug that taught us this.
 *
 * Not thread-safe: an instance owns reusable paints and a scratch rect, and `onDraw` must
 * not allocate (CLAUDE.md rule 7). The thumbnail renderer creates its own.
 *
 * ## Software canvases
 *
 * A thumbnail draws onto a `Bitmap`-backed [Canvas], which is not hardware accelerated, so
 * ink's `drawMesh` fast path is unavailable there. **The renderer decides that per draw
 * call**, from the canvas it is handed — nothing here has to ask for it.
 *
 * It would be marginally cheaper to say so up front, and `CanvasStrokeRenderer` has a
 * `create(forcePathRendering, textureStore)` overload that does exactly that. It is
 * `@RestrictTo(LIBRARY_GROUP)`, and lint fails the build on it — one more entry in the list
 * CLAUDE.md keeps of `androidx.ink` 1.0.0's stable-but-restricted surface. The single-argument
 * `create` is public and does the right thing anyway.
 */
internal class PagePainter(textureStore: TextureBitmapStore = NoTextures) {

    private val renderer = CanvasStrokeRenderer.create(textureStore)

    /** ROADMAP M2's templates, ruled in page space so zoom cannot change the spacing. */
    private val template = TemplateBackground()

    private val paperPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = false
    }

    private val borderPaint = Paint().apply {
        color = PAGE_BORDER_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }

    /** Punches the erased region out of the layer a stroke was drawn into. */
    private val eraserPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    private val layerBounds = RectF()

    /** Reused every frame — `onDraw` must not allocate (CLAUDE.md rule 7). */
    private val pageBounds = RectF()

    /**
     * Filtered, because a source page is rasterised once at a fixed width and then
     * scaled by the camera; unfiltered it goes to visible pixel blocks the moment
     * anyone zooms in, which is most of the time on a tablet.
     */
    private val imagePaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }

    /**
     * White paper, the imported page if there is one, and the template rules.
     * Everything under the ink.
     *
     * @param sourcePage a rasterised [Background.SourcePdfPage], or null. Passed in
     *   rather than fetched: rendering a PDF page is `:android:pdf`'s job (SPEC §6.1)
     *   and it is not work to do inside `onDraw`. A null is a page whose source could
     *   not be rendered — the ink still draws, on white paper, which is the honest
     *   failure. It is never a reason not to show a note.
     *
     *   Drawn into the page's own rectangle in **page space**, so it lands under the
     *   strokes at every zoom by construction: the canvas already carries the camera,
     *   and page space is defined as the CropBox with `/Rotate` applied (SPEC §4.4) —
     *   which is exactly the rectangle a rendered page fills.
     */
    fun paintBackground(canvas: Canvas, pageSize: PageSize, background: Background, sourcePage: Bitmap? = null) {
        canvas.drawRect(0f, 0f, pageSize.widthPt, pageSize.heightPt, paperPaint)
        if (sourcePage != null && !sourcePage.isRecycled) {
            pageBounds.set(0f, 0f, pageSize.widthPt, pageSize.heightPt)
            canvas.drawBitmap(sourcePage, null, pageBounds, imagePaint)
        }
        // Under the ink, over the paper. Culling it would mean clipping every rule to the
        // viewport, and a page's worth of lines is a rounding error next to 5,000 strokes.
        template.draw(canvas, background, pageSize)
    }

    /** The page's edge. Drawn last so ink cannot sit on top of it. */
    fun paintBorder(canvas: Canvas, pageSize: PageSize) {
        canvas.drawRect(0f, 0f, pageSize.widthPt, pageSize.heightPt, borderPaint)
    }

    /**
     * Every stroke in [store], in render order.
     *
     * @param pageToCanvas the transform the canvas already carries. Passed on to the ink
     *   renderer as its antialiasing scale — see the class KDoc, point 3.
     * @param visibleBounds page-space culling rect, or null to draw everything. A thumbnail
     *   passes null: the whole page is "on screen" by definition.
     */
    fun paintStrokes(canvas: Canvas, store: StrokeStore, pageToCanvas: Matrix, visibleBounds: Box?) {
        store.forEachInRenderOrder(visibleBounds) { dry -> paintStroke(canvas, dry, pageToCanvas) }
    }

    /**
     * Draws one stroke, minus whatever an eraser has taken out of it.
     *
     * The layer is bounded to the stroke, and only strokes that have actually been erased
     * pay for one at all, so the common case is untouched.
     */
    private fun paintStroke(canvas: Canvas, dry: DryStroke, pageToCanvas: Matrix) {
        val erased = dry.erased
        val bounds = dry.bounds
        if (erased == null || bounds == null) {
            renderer.draw(canvas, dry.stroke, pageToCanvas)
            return
        }

        // The layer must cover the whole stroke, because the whole stroke is drawn into it.
        // Padding keeps the brush's own antialiased edge from being cut off by the layer
        // boundary.
        layerBounds.set(
            bounds.xMin - LAYER_PADDING_PT,
            bounds.yMin - LAYER_PADDING_PT,
            bounds.xMax + LAYER_PADDING_PT,
            bounds.yMax + LAYER_PADDING_PT,
        )
        val layer = canvas.saveLayer(layerBounds, null)
        renderer.draw(canvas, dry.stroke, pageToCanvas)
        // One stroked draw per constant-radius run. Skia expands the polyline into the swept
        // capsule, caches the tessellation, and resolves the path's self-overlap in a single
        // coverage pass — so a scribbled erase does not double-darken its own crossings.
        val runs = erased.runs
        for (index in runs.indices) {
            val run = runs[index]
            eraserPaint.strokeWidth = run.radius * 2f
            canvas.drawPath(run.path, eraserPaint)
        }
        canvas.restoreToCount(layer)
    }

    private companion object {
        const val PAGE_BORDER_COLOR = 0x22000000
        const val LAYER_PADDING_PT = 2f
    }
}
