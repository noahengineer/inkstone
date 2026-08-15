package dev.inkstone.android.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.GuideAxis
import dev.inkstone.core.model.GuideKind
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.ShapePointRole

/**
 * ⭐ M8a — the selection chrome: the lasso being drawn, the box round what is selected, the
 * grips, and the alignment guides.
 *
 * ## Why its own view
 *
 * It sits between [DryStrokesView] and `InProgressStrokesView`, and the reason is a frame
 * budget. A lasso is redrawn on every input sample; the dry layer holds up to 5,000
 * tessellated strokes. Drawing the chrome into the dry layer would invalidate all of it per
 * sample, for a dashed rectangle. The one gesture that genuinely *does* need the dry layer
 * redrawn is a move — the strokes themselves are going somewhere — and that pays for itself
 * either way.
 *
 * It also cannot go in the layer above: `InProgressStrokesView` front-buffers through a
 * `SurfaceView` that SurfaceFlinger composites over the window, so nothing but wet ink belongs
 * there (see `InkBrushKind.drawsOverInk` for what that surface does to anything else).
 *
 * ## Screen units, not page units
 *
 * Everything here is drawn in **view space**, with the camera applied to the positions by hand
 * rather than concatenated onto the canvas. A grip must be the same size on the glass at every
 * zoom, and a dashed line's dashes must not stretch — both of which a canvas transform would
 * scale along with the geometry. Positions come from page space, sizes come from dp; that
 * split is the whole of this file.
 */
internal class SelectionOverlayView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

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

    /**
     * What to draw. Set as a whole rather than field by field, so the overlay cannot show a box
     * from one gesture and grips from the next.
     */
    var selection: SelectionController? = null

    private val lassoPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = ACCENT
        isAntiAlias = true
        strokeWidth = 2f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }

    private val lassoFill = Paint().apply {
        style = Paint.Style.FILL
        color = ACCENT_FILL
        isAntiAlias = true
    }

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = ACCENT
        isAntiAlias = true
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f)
    }

    private val gripFill = Paint().apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
        isAntiAlias = true
    }

    private val gripStroke = Paint().apply {
        style = Paint.Style.STROKE
        color = ACCENT
        isAntiAlias = true
        strokeWidth = 1.5f * density
    }

    /** ROADMAP M8a — *"dashed = centre"*. */
    private val centreGuide = Paint().apply {
        style = Paint.Style.STROKE
        color = GUIDE
        isAntiAlias = true
        strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
    }

    /** ROADMAP M8a — *"solid = edge"*. */
    private val edgeGuide = Paint().apply {
        style = Paint.Style.STROKE
        color = GUIDE
        isAntiAlias = true
        strokeWidth = 1f * density
    }

    private val path = Path()

    /** Called by the owner when the selection changed under it. */
    fun onSelectionChanged() {
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val selection = this.selection ?: return
        drawGuides(canvas, selection)
        drawLasso(canvas, selection)
        drawBoxAndGrips(canvas, selection)
    }

    private fun drawLasso(canvas: Canvas, selection: SelectionController) {
        val points = selection.lasso ?: return
        if (points.size < 2) return
        path.reset()
        for ((index, point) in points.withIndex()) {
            val x = toViewX(point.x)
            val y = toViewY(point.y)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        // Closed for the fill and left open for the outline would be two paths; one closed path
        // drawn twice is the same picture and half the geometry. The visual difference is the
        // single chord back to the start, which is exactly what a lasso implies anyway.
        path.close()
        canvas.drawPath(path, lassoFill)
        canvas.drawPath(path, lassoPaint)
    }

    /**
     * The frame, and either the eight resize grips or the shape's own **points**.
     *
     * ⭐ **Either, never both.** A rectangle's corners land exactly on the frame's corner grips, so
     * drawing the two sets together would put one circle on top of another and leave the user
     * guessing which gesture they were about to get — while [SelectionController.begin] gives the
     * point handle the press regardless. One set of grips, one meaning.
     *
     * The rotate grip survives the swap because it floats *outside* the box and collides with
     * nothing, and because rotating a shape is not something its points can express: three of them
     * pinned is a shear, not a turn.
     */
    private fun drawBoxAndGrips(canvas: Canvas, selection: SelectionController) {
        val box = selection.bounds ?: return
        val left = toViewX(box.left)
        val top = toViewY(box.top)
        val right = toViewX(box.right)
        val bottom = toViewY(box.bottom)
        canvas.drawRect(left, top, right, bottom, boxPaint)

        val points = selection.points
        val grips = if (points.isEmpty()) SelectionHandle.entries else listOf(SelectionHandle.ROTATE)
        val radius = GRIP_RADIUS_DP * density
        for (handle in grips) {
            val position = handle.positionOn(box, SelectionHandle.ROTATE_OFFSET_PT)
            val x = toViewX(position.x)
            val y = toViewY(position.y)
            canvas.drawCircle(x, y, radius, gripFill)
            canvas.drawCircle(x, y, radius, gripStroke)
        }
        drawPoints(canvas, points)
    }

    /**
     * ⭐ A shape's editable points: a filled square per vertex, a hollow ring for the bend.
     *
     * Two shapes rather than two colours, and the difference is not decoration — they write
     * different registers and behave differently (a vertex re-places the shape; the bend curves it),
     * so a user who cannot tell them apart until they have dragged one has been given a surprise
     * rather than a handle. A ring also reads as *"pull me"*, which is the one affordance in the app
     * nobody would otherwise discover: a straight line's bend handle is what turns it into a curve.
     */
    private fun drawPoints(canvas: Canvas, points: List<PointHandle>) {
        val half = VERTEX_HALF_DP * density
        val ring = BEND_RADIUS_DP * density
        for (handle in points) {
            val x = toViewX(handle.page.x)
            val y = toViewY(handle.page.y)
            when (handle.point.role) {
                ShapePointRole.VERTEX -> {
                    canvas.drawRect(x - half, y - half, x + half, y + half, gripFill)
                    canvas.drawRect(x - half, y - half, x + half, y + half, gripStroke)
                }

                ShapePointRole.BEND -> {
                    canvas.drawCircle(x, y, ring, gripFill)
                    canvas.drawCircle(x, y, ring, gripStroke)
                    canvas.drawCircle(x, y, ring * BEND_HOLE, gripStroke)
                }
            }
        }
    }

    /**
     * The guides, drawn the full width or height of the **page** rather than of the pair of
     * boxes they relate.
     *
     * A guide clipped to the two objects is ambiguous about which edge it is claiming when they
     * are far apart, and every drawing tool that has this feature draws the long line for that
     * reason.
     */
    private fun drawGuides(canvas: Canvas, selection: SelectionController) {
        for (guide in selection.alignment.guides) {
            val paint = if (guide.kind == GuideKind.CENTRE) centreGuide else edgeGuide
            when (guide.axis) {
                GuideAxis.VERTICAL -> {
                    val x = toViewX(guide.positionPt)
                    canvas.drawLine(x, toViewY(0f), x, toViewY(pageSize.heightPt), paint)
                }

                GuideAxis.HORIZONTAL -> {
                    val y = toViewY(guide.positionPt)
                    canvas.drawLine(toViewX(0f), y, toViewX(pageSize.widthPt), y, paint)
                }
            }
        }
    }

    private fun toViewX(pageX: Float): Float = pageX * camera.scale + camera.translate.x

    private fun toViewY(pageY: Float): Float = pageY * camera.scale + camera.translate.y

    private companion object {
        const val ACCENT = 0xFF2563EB.toInt()
        const val ACCENT_FILL = 0x142563EB
        const val GUIDE = 0xFFEC4899.toInt()
        const val GRIP_RADIUS_DP = 6f

        /** Half a vertex square's side. A shade larger than a grip, because it is the precise one. */
        const val VERTEX_HALF_DP = 6.5f
        const val BEND_RADIUS_DP = 6f

        /** The ring's inner circle, as a fraction of its radius. */
        const val BEND_HOLE = 0.45f
    }
}
