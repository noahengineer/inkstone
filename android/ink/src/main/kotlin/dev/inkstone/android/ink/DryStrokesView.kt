package dev.inkstone.android.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.View
import androidx.ink.brush.TextureBitmapStore
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.PageSize

/**
 * The dry layer: every finished stroke, drawn through the camera.
 *
 * It sits *below* [androidx.ink.authoring.InProgressStrokesView] in the view
 * hierarchy, so wet ink is composited over it by the front buffer without this
 * view redrawing at all — which is the whole reason the two are separate.
 *
 * Strokes are held in page space (SPEC §4.4 rule 1) and the camera is applied
 * as a canvas transform, so panning and zooming never touch stroke geometry.
 *
 * ## Why the page→view matrix is passed to `draw` as well as concatenated
 *
 * `CanvasStrokeRenderer.draw`'s `strokeToScreenTransform` is not a drawing
 * transform — the canvas carries that. Its linear component becomes a **shader
 * uniform that sizes the antialiasing feather**, in stroke units. Get it wrong
 * by a factor of N and every edge is feathered N times too wide.
 *
 * `ViewStrokeRenderer` computes it for you, from `Canvas.getMatrix()`. That is
 * the documented path and it is what this view used first — and on the tablet
 * every stroke went soft the instant it left the wet layer, at the right size
 * and in the right place, which is the signature of a feather scaled by the
 * camera. The wet layer was unaffected because `InProgressStrokesView` knows
 * its own transforms and never asks the canvas.
 *
 * So the matrix is passed explicitly. It is already computed for the `concat`,
 * it is exact, and it removes a dependency on a deprecated method whose
 * behaviour on a hardware-accelerated canvas is not something to bet the most
 * important surface in the app on. Only the linear component is read, so the
 * view's own offset on screen is correctly irrelevant.
 */
internal class DryStrokesView(context: Context, private val store: StrokeStore, textureStore: TextureBitmapStore) :
    View(context) {

    /** Reused every frame — `onDraw` must not allocate. */
    private val cameraMatrix = CameraMatrix()
    private val pageBackgroundPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = false
    }
    private val pageBorderPaint = Paint().apply {
        color = PAGE_BORDER_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }

    private val renderer = CanvasStrokeRenderer.create(textureStore)

    /** ROADMAP M2's templates, ruled in page space so zoom cannot change the spacing. */
    private val template = TemplateBackground()

    /** Punches the erased region out of the layer a stroke was drawn into. */
    private val eraserPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val layerBounds = RectF()

    var camera: Camera = Camera.Identity
        set(value) {
            field = value
            invalidate()
        }

    var pageSize: PageSize = PageSize.Letter
        set(value) {
            field = value
            invalidate()
        }

    var pageBackground: Background = Background.Blank
        set(value) {
            field = value
            invalidate()
        }

    private var drawnRevision = -1L

    /** Called by the owner when [store] has changed under it. */
    fun onStoreChanged() {
        if (store.revision != drawnRevision) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        drawnRevision = store.revision

        val pageToView = cameraMatrix.pageToView(camera)
        val saved = canvas.save()
        canvas.concat(pageToView)
        canvas.drawRect(0f, 0f, pageSize.widthPt, pageSize.heightPt, pageBackgroundPaint)
        // Under the ink, over the paper. Culling it would mean clipping every rule to the
        // viewport, and a page's worth of lines is a rounding error next to 5,000 strokes.
        template.draw(canvas, pageBackground, pageSize)

        val visible = visibleBoundsInPageSpace()
        store.forEachInRenderOrder(visible) { dry -> drawStroke(canvas, dry, pageToView) }

        canvas.drawRect(0f, 0f, pageSize.widthPt, pageSize.heightPt, pageBorderPaint)
        canvas.restoreToCount(saved)
    }

    /**
     * Draws one stroke, minus whatever an eraser has taken out of it.
     *
     * The subtraction is `DST_OUT` into an offscreen layer rather than
     * `Canvas.clipOutPath`. Clipping is cheaper — no layer — but its edge is
     * only as good as the hardware renderer's clip antialiasing, and this app
     * has already been through one round of "the ink looks soft". A blend gives
     * the same antialiasing the stroke itself gets.
     *
     * The layer is bounded to the stroke, and only strokes that have actually
     * been erased pay for one at all, so the common case is untouched.
     */
    private fun drawStroke(canvas: Canvas, dry: DryStroke, pageToView: android.graphics.Matrix) {
        val erased = dry.erased
        val bounds = dry.bounds
        if (erased == null || bounds == null) {
            renderer.draw(canvas, dry.stroke, pageToView)
            return
        }

        // The layer must cover the whole stroke, because the whole stroke is
        // drawn into it. Padding keeps the brush's own antialiased edge from
        // being cut off by the layer boundary.
        layerBounds.set(
            bounds.xMin - LAYER_PADDING_PT,
            bounds.yMin - LAYER_PADDING_PT,
            bounds.xMax + LAYER_PADDING_PT,
            bounds.yMax + LAYER_PADDING_PT,
        )
        val layer = canvas.saveLayer(layerBounds, null)
        renderer.draw(canvas, dry.stroke, pageToView)
        // One stroked draw per constant-radius run. Skia expands the polyline
        // into the swept capsule, caches the tessellation, and resolves the
        // path's self-overlap in a single coverage pass — so a scribbled erase
        // does not double-darken its own crossings.
        val runs = erased.runs
        for (index in runs.indices) {
            val run = runs[index]
            eraserPaint.strokeWidth = run.radius * 2f
            canvas.drawPath(run.path, eraserPaint)
        }
        canvas.restoreToCount(layer)
    }

    /**
     * The viewport in page space, with a margin so a stroke whose *centre line*
     * is just off-screen but whose width still bleeds in is not culled.
     */
    private fun visibleBoundsInPageSpace(): ImmutableBox {
        val inverseScale = 1f / camera.scale
        val left = (0f - camera.translate.x) * inverseScale
        val top = (0f - camera.translate.y) * inverseScale
        val right = (width - camera.translate.x) * inverseScale
        val bottom = (height - camera.translate.y) * inverseScale
        val margin = CULL_MARGIN_PT
        return ImmutableBox.fromTwoPoints(
            ImmutableVec(left - margin, top - margin),
            ImmutableVec(right + margin, bottom + margin),
        )
    }

    private companion object {
        const val PAGE_BORDER_COLOR = 0x22000000
        const val CULL_MARGIN_PT = 24f
        const val LAYER_PADDING_PT = 2f
    }
}
