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
import kotlin.math.hypot

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
        val points = selection.points

        // ⭐ **No frame when the shape has its own points**, in the user's words: *"i dont like how
        // it gives a box around the shapes, it can make it quite confusing"*. Right, and the reason
        // is that the box stopped being the thing you act on the moment the handles arrived — it
        // was chrome describing a gesture (drag any edge to resize) that the points had replaced,
        // and for a curve or a diagonal line it is mostly empty paper. It is still the **move**
        // target and is still hit-tested; it is simply not drawn, so what is on screen is the shape
        // and its points.
        if (points.isEmpty()) {
            canvas.drawRect(
                toViewX(box.left),
                toViewY(box.top),
                toViewX(box.right),
                toViewY(box.bottom),
                boxPaint,
            )
        }

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
        // ⭐ **Shrunk when the shape is smaller than its own handles**, which for a segment it very
        // often is: two 13 dp squares and a 12 dp ring is about 18 pt of chrome at fit width, so a
        // 30 pt line was three-fifths covered by the handles that describe it and a 24 pt one
        // disappeared under them entirely — a filled square, a ring and a filled square with no
        // visible line between. *"A line that does not have the proper points … this weird image
        // like thing."*
        //
        // The **touch** target is untouched (`SelectionController.HANDLE_TOUCH_DP`): a handle you
        // can see is a drawing question and a handle you can hit is a finger question, and tying
        // them together is what would make a small shape unusable rather than merely unreadable.
        val scale = handleScale(points)
        val half = VERTEX_HALF_DP * density * scale
        val ring = BEND_RADIUS_DP * density * scale
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
     * How far to shrink the point handles so that neighbours do not merge, in `[HANDLE_MIN_SCALE, 1]`.
     *
     * The closest pair decides it, not the box: a rectangle can be wide and short, and it is the
     * short side that would collide. [HANDLE_GAP] of the spacing is the target diameter, which
     * leaves clear paper between two handles at every size.
     *
     * Floored rather than allowed to vanish — a handle too small to see is worse than one that
     * still overlaps, because the second at least says *"there is something here to drag"*. Below
     * the floor a shape is a few points across and there is nothing to draw between the handles
     * anyway.
     */
    private fun handleScale(points: List<PointHandle>): Float {
        if (points.size < 2) return 1f
        var closest = Float.MAX_VALUE
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                val dx = toViewX(points[i].page.x) - toViewX(points[j].page.x)
                val dy = toViewY(points[i].page.y) - toViewY(points[j].page.y)
                val distance = hypot(dx, dy)
                if (distance < closest) closest = distance
            }
        }
        val room = closest * HANDLE_GAP / (VERTEX_HALF_DP * 2f * density)
        return room.coerceIn(HANDLE_MIN_SCALE, 1f)
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
        /**
         * ⭐ ROADMAP M10 — sky-500, the redesign's selection hue.
         *
         * ⚠️ Sky-**500** and not the sky-400 `Ink.Focus` the chrome uses, and the difference is the
         * surface. Chrome sits on a near-black canvas, where a lighter blue is what reads; these
         * handles are drawn on **white paper**, where sky-400 is a pale line on a bright field. The
         * design's own mock makes the same distinction — its canvas objects are outlined
         * `border-sky-500` while the chrome's focus ring is `#38bdf8`.
         */
        const val ACCENT = 0xFF0EA5E9.toInt()
        const val ACCENT_FILL = 0x140EA5E9

        /**
         * Alignment guides, and deliberately a *different* hue from [ACCENT].
         *
         * ROADMAP M8a — *"dashed = centre, solid = edge"*. A guide says where the thing you are
         * dragging lines up with something else; a handle says where you may grab it. Painting both
         * in the selection colour would make a guide look like a handle you cannot grip.
         */
        const val GUIDE = 0xFFEC4899.toInt()
        const val GRIP_RADIUS_DP = 6f

        /** Half a vertex square's side. A shade larger than a grip, because it is the precise one. */
        const val VERTEX_HALF_DP = 6.5f
        const val BEND_RADIUS_DP = 6f

        /** How much of the gap between two neighbouring handles one of them may fill. */
        const val HANDLE_GAP = 0.34f

        /** Below this a handle stops shrinking — see [handleScale]. */
        const val HANDLE_MIN_SCALE = 0.3f

        /** The ring's inner circle, as a fraction of its radius. */
        const val BEND_HOLE = 0.45f
    }
}
