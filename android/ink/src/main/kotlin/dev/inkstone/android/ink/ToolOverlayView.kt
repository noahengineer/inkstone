package dev.inkstone.android.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.SnapGrid
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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

    /**
     * ⭐ The snap lattice to draw, or null — see [InkSurfaceView.gridSnapEnabled].
     *
     * ⚠️ **Chrome, and it must stay chrome.** The obvious home for this is
     * [TemplateBackground], which is where every other lattice in this app is drawn — and that
     * class is shared with the thumbnail renderer and states the rule that it rules a page
     * *"identically on screen, in a thumbnail and in the published PDF"*. A grid that exists
     * because a **setting** is on belongs in none of those: it would print, it would sync, and it
     * would be in a PDF opened on a device where the setting is off. Here it is a view, over the
     * dry layer, seen by nobody but the person who switched it on.
     *
     * A lattice the paper already draws is set here too and simply not drawn — see [drawSnapGrid].
     * Nulling it out at the caller instead would put *"is this mine to draw"* somewhere other than
     * the thing that draws it.
     */
    var snapGrid: SnapGrid? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** The paper, so the lattice stops at its edge. Ink does too (SPEC §4.5). */
    var pageCanvas: PageCanvas = PageCanvas.fixed(PageSize.Letter)
        set(value) {
            if (field == value) return
            field = value
            if (snapGrid != null) invalidate()
        }

    /** Called by the owner when either tool changed under it. */
    fun onToolsChanged() {
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        drawSnapGrid(canvas)
        drawRuler(canvas)
        drawLaser(canvas)
    }

    /**
     * The snap lattice, hairline-faint, over the part of the paper that is on screen.
     *
     * ⚠️ **Bounded twice, and both bounds matter.** The loop runs over the intersection of the
     * paper and the viewport rather than the whole page, so an infinite canvas cannot turn this
     * into an unbounded loop; and a lattice whose cells have shrunk below [MIN_CELL_DP] on the
     * glass is not drawn at all, because at that point it is a grey wash rather than a grid and the
     * line count is whatever the zoom says. M10's dot-grid note is the precedent — a per-cell draw
     * call behind the ink is exactly where rule 7 says nothing heavy may go, and the cheap fix is
     * to stop before the count is large rather than to draw it faster.
     *
     * An axis with no spacing draws no lines, which is what makes ruled paper's chrome (none, since
     * its rules are the paper's own) and a free x axis fall out with no special case.
     */
    private fun drawSnapGrid(canvas: Canvas) {
        val grid = snapGrid ?: return
        // The paper's own lattice is the paper's to draw; a second one over it is the page
        // moiréing against itself.
        if (grid.isEmpty || grid.drawnByThePaper) return
        val scale = camera.scale
        if (scale <= 0f) return
        val paper = pageCanvas.rect
        // The viewport in page space, intersected with the paper.
        val left = max(paper.left, -camera.translate.x / scale)
        val top = max(paper.top, -camera.translate.y / scale)
        val right = min(paper.right, (width - camera.translate.x) / scale)
        val bottom = min(paper.bottom, (height - camera.translate.y) / scale)
        if (right <= left || bottom <= top) return

        gridPaint.strokeWidth = GRID_WIDTH_DP * density
        val minCellPt = MIN_CELL_DP * density / scale
        if (grid.snapsX && grid.spacingXPt >= minCellPt) {
            var x = firstLine(left, grid.originXPt, grid.spacingXPt)
            while (x <= right) {
                val viewX = toViewX(x)
                canvas.drawLine(viewX, toViewY(top), viewX, toViewY(bottom), gridPaint)
                x += grid.spacingXPt
            }
        }
        if (grid.snapsY && grid.spacingYPt >= minCellPt) {
            var y = firstLine(top, grid.originYPt, grid.spacingYPt)
            while (y <= bottom) {
                val viewY = toViewY(y)
                canvas.drawLine(toViewX(left), viewY, toViewX(right), viewY, gridPaint)
                y += grid.spacingYPt
            }
        }
    }

    /** The first lattice line at or after [from]. */
    private fun firstLine(from: Float, origin: Float, spacing: Float): Float =
        origin + ceil((from - origin) / spacing) * spacing

    /**
     * The edge: a bright line strokes snap to, with a translucent body hanging **off one side** of
     * it and a mark at each end where a finger rotates it.
     *
     * ⚠️ **The bright line is the line the projection uses, and it is not covered by anything.**
     * The body used to be centred on it, on the argument that chrome disagreeing with the geometry
     * by half a slab would land every ruled stroke visibly beside the guide. That argument is
     * sound and it had a third answer: *move the body, keep the line*. Centred, the ruler sat on
     * top of the line it had just ruled and on whatever was being ruled against — the report was
     * *"otherwise it covers it"* — and a real straight edge has nothing between the nib and the
     * paper either.
     *
     * ⚠️ The offset is [RulerController.bodyNormalX]/[RulerController.bodyNormalY], not a sign worked out here.
     * [RulerController.begin] gives a finger the width of the body on that same side, and a chrome
     * side that disagreed with the grab side would be a ruler you can see and cannot pick up.
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

        // A stroked line is centred on its own path, so the body's centre line is half a slab off
        // the guide — which puts its near face exactly on the guide and the rest of it beyond.
        val slab = ruler.bodyWidthDp * density
        val offsetX = ruler.bodyNormalX * slab / 2f
        val offsetY = ruler.bodyNormalY * slab / 2f
        slabPaint.strokeWidth = slab
        canvas.drawLine(fromX + offsetX, fromY + offsetY, toX + offsetX, toY + offsetY, slabPaint)

        edgePaint.strokeWidth = EDGE_WIDTH_DP * density
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

    /**
     * The line strokes actually snap to, and the two end grips.
     *
     * ⚠️ Its width is set per draw, in dp. It was a raw `1.5f` — **pixels** — which on a 320 dpi
     * tablet is half the hairline this class's own KDoc promises every size here would be, and it
     * was the one measurement in the file that did not go through [density].
     */
    private val edgePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = EDGE_COLOR
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

    /** Fainter than [TemplateBackground]'s own rules: this is a setting, not the paper. */
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = GRID_COLOR
        isAntiAlias = false
    }

    private companion object {
        // ⚠️ The body's width is `InkRuler.bodyWidthDp`, which is also what decides what a
        // finger has grabbed. See its KDoc on why there is one of it and not one each.
        const val SLAB_COLOR = 0x1F1E293B
        const val EDGE_COLOR = 0xFF1E293B.toInt()
        const val EDGE_WIDTH_DP = 1.5f
        const val GRIP_RADIUS_DP = 7f

        const val LASER_COLOR = 0xFFEF4444.toInt()
        const val DOT_RADIUS_DP = 5f
        const val TRAIL_WIDTH_DP = 4f

        /** The trail is dimmer than the dot, so the live point reads as the pointer. */
        const val TRAIL_ALPHA = 0.55f
        const val FULL_ALPHA = 255f

        const val GRID_COLOR = 0x142E4A6B
        const val GRID_WIDTH_DP = 0.5f

        /** Below this on the glass a lattice is a wash, and its line count is the zoom's business. */
        const val MIN_CELL_DP = 7f
    }
}
