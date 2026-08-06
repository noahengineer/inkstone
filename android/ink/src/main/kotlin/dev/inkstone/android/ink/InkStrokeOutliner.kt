package dev.inkstone.android.ink

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.StrokeInput
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.StrokeOutline
import dev.inkstone.core.model.StrokeOutliner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * SPEC §6.4 — the `:android:ink` half of the ink→PDF handoff.
 *
 * Turns a folded page into filled outlines and centrelines in page space
 * ([StrokeOutline]), which `:android:pdf` writes as `/AP` appearance streams and
 * `/InkList` polylines. Neither module depends on the other; the shape of what
 * passes between them is a platform-free type in `:core:model` (SPEC §2.1).
 *
 * ## ⭐ Why the erasure is subtracted here
 *
 * SPEC §3.5 erases by *area*: the stroke is never touched, and the swept region is
 * removed at draw time. On screen [PagePainter] does that with a `DST_OUT` layer.
 * A PDF has no cheap equivalent, and the two constructions that look like one both
 * fail on the input that matters:
 *
 * - **Even-odd fill** of *stroke ∪ capsules* removes a single capsule correctly and
 *   **re-fills** wherever two capsules overlap — which a scribbled erase produces
 *   on nearly every stroke it touches.
 * - **Reversed-winding subpaths** under nonzero fill fail the same way, for the
 *   same reason: two overlapping subtractions wind back to non-zero.
 *
 * A soft mask would be exact and is a lot of machinery. But there is already an
 * exact boolean path engine on this side of the boundary — Skia — so the
 * subtraction is done with [Path.op] and what crosses the boundary is what is
 * *left*. `:android:pdf` fills a polygon and never learns that an eraser existed.
 *
 * ## Threading
 *
 * `Dispatchers.Default`, hopped to here rather than at each call site (CLAUDE.md
 * rule 7). Tessellating a page of strokes and running boolean ops on the erased
 * ones is tens of milliseconds; materialization is never on the ink path anyway,
 * but a renderer that quietly runs on whatever thread it was called from is how
 * that stops being true.
 */
public class InkStrokeOutliner(
    private val brushes: InkBrushFactory,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : StrokeOutliner {

    override suspend fun outline(page: FoldedPage): List<StrokeOutline> = withContext(dispatcher) {
        val store = StrokeStore()
        // The same decoder the editor and the thumbnail renderer use. A second one
        // would be a second place for render order and absolute erasure to drift —
        // and the published PDF disagreeing with the screen is the one bug in this
        // milestone the user would notice and could not explain.
        store.applyOps(
            ops = page.objects.map { PageOp.AddObject(opId = "", clock = OUTLINE_CLOCK, obj = it) },
            brushes = brushes,
            record = false,
        )

        val result = ArrayList<StrokeOutline>(store.size)
        // Ink first, then highlighters (SPEC §3.4 — reversed after M4). Annotation order is
        // paint order in a PDF too, so this is not merely cosmetic: it is the same z-order
        // the editor draws, and the published file disagreeing with the screen is the one
        // bug in this milestone the user would notice and could not explain. The highlight
        // tints rather than hides what it covers because of its alpha, exactly as on screen.
        store.forEachInRenderOrder(visibleBounds = null) { dry -> result += outlineOf(dry) }
        result
    }

    private fun outlineOf(dry: DryStroke): StrokeOutline {
        val brush = dry.stroke.brush
        return StrokeOutline(
            objectId = dry.id,
            contours = contoursOf(dry),
            centreline = centrelineOf(dry),
            colorArgb = brush.colorIntArgb,
            widthPt = brush.size,
        )
    }

    /**
     * The visible outline, in page space.
     *
     * The untouched case reads the mesh's own outline vertices — exact, and already
     * polylines, because a tessellated stroke has no curves left. Only an erased
     * stroke pays for the round trip through [Path] and [PathMeasure].
     */
    private fun contoursOf(dry: DryStroke): List<List<PagePoint>> {
        val erased = dry.erased
        return if (erased == null) meshContours(dry) else erasedContours(dry, erased)
    }

    private fun meshContours(dry: DryStroke): List<List<PagePoint>> {
        val mesh = dry.stroke.shape
        val scratch = MutableVec()
        val contours = ArrayList<List<PagePoint>>()
        for (group in 0 until mesh.getRenderGroupCount()) {
            for (outline in 0 until mesh.getOutlineCount(group)) {
                val count = mesh.getOutlineVertexCount(group, outline)
                if (count < MIN_POLYGON_POINTS) continue
                val points = ArrayList<PagePoint>(count)
                for (vertex in 0 until count) {
                    mesh.populateOutlinePosition(group, outline, vertex, scratch)
                    points += PagePoint(scratch.x, scratch.y)
                }
                contours += points
            }
        }
        return contours
    }

    /**
     * Stroke minus eraser, exactly, via Skia.
     *
     * The eraser's swept capsules are the same ones [PagePainter] strokes with
     * `DST_OUT`, so the two agree by construction rather than by two people having
     * implemented the same rule twice.
     */
    private fun erasedContours(dry: DryStroke, erased: ErasedRegion): List<List<PagePoint>> {
        // Built from the same exact vertices [meshContours] reads, rather than through
        // ink's `outlinesToPath` extension: one way of getting a stroke's outline, so
        // an erased stroke and an untouched one cannot end up subtly different shapes.
        val stroke = Path()
        for (contour in meshContours(dry)) {
            for ((index, point) in contour.withIndex()) {
                if (index == 0) stroke.moveTo(point.x, point.y) else stroke.lineTo(point.x, point.y)
            }
            stroke.close()
        }

        val eraser = Path()
        val expanded = Path()
        val paint = eraserPaint
        for (run in erased.runs) {
            paint.strokeWidth = run.radius * 2f
            expanded.reset()
            paint.getFillPath(run.path, expanded)
            // UNION rather than addPath: overlapping capsules must become one region
            // before the subtraction, which is the whole point (see the class KDoc).
            eraser.op(expanded, Path.Op.UNION)
        }
        stroke.op(eraser, Path.Op.DIFFERENCE)
        return flatten(stroke)
    }

    /**
     * A [Path]'s contours as polylines.
     *
     * Sampled by arc length rather than read vertex by vertex, because the vertex
     * list of a path is not reachable before API 34 (`PathIterator`) and this app's
     * floor is 30. `Path.approximate` *is* available and is deliberately not used:
     * it returns one flat array with no contour boundaries in it, so a stroke with
     * a hole in it — which is exactly what erasing produces — comes back with the
     * hole's edge joined to the outside by a stray segment.
     */
    private fun flatten(path: Path): List<List<PagePoint>> {
        val measure = PathMeasure(path, false)
        val contours = ArrayList<List<PagePoint>>()
        val position = FloatArray(2)
        do {
            val length = measure.length
            if (length <= 0f) continue
            val steps = (length / FLATTEN_STEP_PT).toInt().coerceIn(MIN_POLYGON_POINTS, MAX_CONTOUR_POINTS)
            val points = ArrayList<PagePoint>(steps + 1)
            for (step in 0..steps) {
                if (!measure.getPosTan(length * step / steps, position, null)) continue
                points += PagePoint(position[0], position[1])
            }
            if (points.size >= MIN_POLYGON_POINTS) contours += points
        } while (measure.nextContour())
        return contours
    }

    /**
     * The stroke's input path, thinned, for `/InkList`.
     *
     * Thinned because `/InkList` is the *interop* representation (SPEC §6.4) and
     * nothing renders from it: a 200 Hz capture is hundreds of points per stroke,
     * and keeping every one would put megabytes of numbers into the PDF to describe
     * a line another editor will draw at uniform width anyway.
     *
     * It is also what Recovered mode reads back ([InkStrokeRebuilder]), so the
     * threshold is a fidelity floor for recovery, not just a size knob — a
     * quarter-point is well under the width of any pen.
     */
    private fun centrelineOf(dry: DryStroke): List<PagePoint> {
        val inputs = dry.stroke.inputs
        if (inputs.size == 0) return emptyList()
        val scratch = StrokeInput()
        val points = ArrayList<PagePoint>(inputs.size)
        var lastX = Float.NaN
        var lastY = Float.NaN
        for (index in 0 until inputs.size) {
            inputs.populate(index, scratch)
            val isLast = index == inputs.size - 1
            if (!isLast && !lastX.isNaN() && hypot(scratch.x - lastX, scratch.y - lastY) < THIN_THRESHOLD_PT) continue
            points += PagePoint(scratch.x, scratch.y)
            lastX = scratch.x
            lastY = scratch.y
        }
        return points
    }

    /** Reused across strokes; this class is confined to [dispatcher] for one call. */
    private val eraserPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private companion object {
        /** Synthetic, never journaled — the same trick [InkPageThumbnailRenderer] uses. */
        val OUTLINE_CLOCK = LamportClock(0, "outline")

        const val MIN_POLYGON_POINTS = 3
        const val FLATTEN_STEP_PT = 0.75f
        const val MAX_CONTOUR_POINTS = 2048
        const val THIN_THRESHOLD_PT = 0.25f
    }
}
