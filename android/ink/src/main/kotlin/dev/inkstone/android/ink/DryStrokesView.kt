package dev.inkstone.android.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.ink.brush.TextureBitmapStore
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
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

    /**
     * Paper, template, ink and erasures — shared with the thumbnail renderer.
     *
     * M3 moved this out of here. The library shows a picture of the same page, and two
     * renderers maintained side by side is how a preview ends up with different line
     * spacing or the highlighter behind the ink. See [PagePainter].
     */
    private val painter = PagePainter(textureStore)

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

    /**
     * The rasterised source page, when this page came from an imported PDF
     * (SPEC §6.2). Null until `:android:pdf` has rendered it, and null forever for
     * a page that never had one.
     *
     * Held, not owned: whoever rendered it also recycles it, and does so only after
     * clearing this. A recycled bitmap is checked for rather than trusted, because
     * the alternative is a crash in `onDraw` if a page is scrolled away mid-render.
     */
    var sourcePage: Bitmap? = null
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
        painter.paintBackground(canvas, pageSize, pageBackground, sourcePage)
        painter.paintStrokes(canvas, store, pageToView, visibleBoundsInPageSpace())
        painter.paintBorder(canvas, pageSize)
        highlight?.let { painter.paintHighlight(canvas, it) }
        canvas.restoreToCount(saved)
    }

    /**
     * SPEC §8.4 — the box a search hit landed on, or null.
     *
     * Drawn inside the same page-space transform as the ink, so it stays on the word through a
     * pan or a zoom rather than being a rectangle pinned to the screen.
     */
    var highlight: dev.inkstone.core.model.PageRect? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
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
        const val CULL_MARGIN_PT = 24f
    }
}
