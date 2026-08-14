package dev.inkstone.android.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import dev.inkstone.core.model.Camera
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * ⭐ M8c — the chrome for the two tools that are not objects: the straight edge and the laser.
 *
 * ## Why not [SelectionOverlayView]
 *
 * They are both chrome, they both sit between the dry layer and the wet one, and merging them would
 * still have been wrong — because of what a laser costs. A fading trail invalidates **every frame
 * for as long as it is visible**, and the selection overlay is redrawn on every input sample of a
 * lasso already. One view means every laser frame also rebuilds the selection box, the grips and
 * the alignment guides, and every lasso sample re-walks the laser's ring buffer. Two views mean
 * each pays only for itself, and the laser's repaint is confined to [invalidateTrail]'s rectangle
 * rather than the screen.
 *
 * It cannot go in the layer above either, for the reason [SelectionOverlayView] records:
 * `InProgressStrokesView` front-buffers through a `SurfaceView` that SurfaceFlinger composites over
 * the window, and nothing but wet ink belongs there.
 *
 * ## View space, positions from page space
 *
 * Same split as [SelectionOverlayView]: the camera is applied to *positions* by hand and sizes come
 * from dp, so the edge is the same thickness and the laser the same size on the glass at every
 * zoom. A canvas transform would scale both with the page, and a ruler that got thinner as you
 * zoomed out would eventually be a hairline you could not grab.
 */
internal class ToolOverlayView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    var camera: Camera = Camera.Identity
        set(value) {
            field = value
            invalidate()
        }

    /** The straight edge to draw, or null before the surface has built one. */
    var ruler: RulerController? = null

    /** The laser trail to draw. Its own animation is driven from [onDraw]. */
    var laser: LaserController? = null

    /** Called by the owner when either tool changed under it. */
    fun onToolsChanged() {
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        drawRuler(canvas)
        drawLaser(canvas)
    }

    /**
     * The edge: a translucent slab with a bright line down the side strokes snap to, and a mark at
     * each end where a finger rotates it.
     *
     * ⚠️ **The bright line is the line the projection uses**, down the middle of the slab and not
     * along one of its faces. A real ruler's ink lands against its *edge*, so the obvious drawing —
     * a wide bar with the snap line at one border — is what a person expects; it is not what is
     * drawn, because the projection is onto the centre line and chrome that disagreed with the
     * geometry by half the slab's width would make every ruled stroke land visibly beside the guide.
     * If the chrome is ever changed to a one-sided edge, [RulerController.project] has to move with
     * it.
     */
    private fun drawRuler(canvas: Canvas) {
        val ruler = this.ruler ?: return
        if (!ruler.enabled) return
        val radians = Math.toRadians(ruler.angleDegrees.toDouble())
        val dirX = cos(radians).toFloat()
        val dirY = sin(radians).toFloat()
        val half = ruler.lengthPt / 2f
        val centreX = toViewX(ruler.centreXPt)
        val centreY = toViewY(ruler.centreYPt)
        // The length is a page quantity — the edge covers the same part of the drawing at every
        // zoom — while the width is not, so only one of the two goes through the camera.
        val reachX = dirX * half * camera.scale
        val reachY = dirY * half * camera.scale
        val fromX = centreX - reachX
        val fromY = centreY - reachY
        val toX = centreX + reachX
        val toY = centreY + reachY

        slabPaint.strokeWidth = SLAB_WIDTH_DP * density
        canvas.drawLine(fromX, fromY, toX, toY, slabPaint)
        canvas.drawLine(fromX, fromY, toX, toY, edgePaint)
        val grip = GRIP_RADIUS_DP * density
        canvas.drawCircle(fromX, fromY, grip, edgePaint)
        canvas.drawCircle(toX, toY, grip, edgePaint)
    }

    /**
     * The trail, oldest segment first, each at its own brightness, with the live dot on top.
     *
     * One `drawLine` per sample and the sample count is bounded by [LaserController.STEP_PT], so a
     * whole sweep is tens of lines rather than the hundreds a 240 Hz digitiser would give.
     *
     * ⚠️ **The next frame is scheduled from here and nowhere else.** A fade driven by a timer
     * somewhere else would keep running after the view was detached; scheduling from `onDraw` means
     * the animation exists exactly as long as there is something to draw, and stops the moment
     * [LaserController.prune] says the trail is gone.
     */
    private fun drawLaser(canvas: Canvas) {
        val laser = this.laser ?: return
        val now = LaserController.now()
        if (!laser.prune(now)) return

        for (index in 1 until laser.count) {
            trailPaint.alpha = (laser.brightnessAt(index, now) * FULL_ALPHA * TRAIL_ALPHA).roundToInt()
            trailPaint.strokeWidth = TRAIL_WIDTH_DP * density
            canvas.drawLine(
                toViewX(laser.xAt(index - 1)),
                toViewY(laser.yAt(index - 1)),
                toViewX(laser.xAt(index)),
                toViewY(laser.yAt(index)),
                trailPaint,
            )
        }
        val head = laser.count - 1
        dotPaint.alpha = (laser.brightnessAt(head, now) * FULL_ALPHA).roundToInt()
        canvas.drawCircle(toViewX(laser.xAt(head)), toViewY(laser.yAt(head)), DOT_RADIUS_DP * density, dotPaint)
        invalidateTrail(laser)
    }

    /**
     * Schedule the next fade frame over the trail's own rectangle.
     *
     * A full-screen invalidate per frame would repaint the whole overlay for a mark a centimetre
     * across. The padding covers the dot's radius and the trail's stroke width, both of which are
     * drawn centred on the points the bounds were computed from.
     */
    private fun invalidateTrail(laser: LaserController) {
        val padding = (DOT_RADIUS_DP + TRAIL_WIDTH_DP) * density
        postInvalidateOnAnimation(
            (toViewX(laser.minX) - padding).toInt(),
            (toViewY(laser.minY) - padding).toInt(),
            (toViewX(laser.maxX) + padding).toInt() + 1,
            (toViewY(laser.maxY) + padding).toInt() + 1,
        )
    }

    private fun toViewX(pageX: Float): Float = pageX * camera.scale + camera.translate.x

    private fun toViewY(pageY: Float): Float = pageY * camera.scale + camera.translate.y

    /** The body of the straight edge — translucent, so the drawing under it stays readable. */
    private val slabPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = SLAB_COLOR
        isAntiAlias = true
    }

    /** The line strokes actually snap to, and the two end grips. */
    private val edgePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = EDGE_COLOR
        strokeWidth = 1.5f
        isAntiAlias = true
    }

    private val trailPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = LASER_COLOR
        isAntiAlias = true
    }

    private val dotPaint = Paint().apply {
        style = Paint.Style.FILL
        color = LASER_COLOR
        isAntiAlias = true
    }

    private companion object {
        const val SLAB_WIDTH_DP = 44f
        const val SLAB_COLOR = 0x1F1E293B
        const val EDGE_COLOR = 0xFF1E293B.toInt()
        const val GRIP_RADIUS_DP = 7f

        const val LASER_COLOR = 0xFFEF4444.toInt()
        const val DOT_RADIUS_DP = 5f
        const val TRAIL_WIDTH_DP = 4f

        /** The trail is dimmer than the dot, so the live point reads as the pointer. */
        const val TRAIL_ALPHA = 0.55f
        const val FULL_ALPHA = 255f
    }
}
