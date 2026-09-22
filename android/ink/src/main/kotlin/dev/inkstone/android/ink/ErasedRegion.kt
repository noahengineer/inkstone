package dev.inkstone.android.ink

import android.graphics.Path
import androidx.ink.geometry.MutableVec
import androidx.ink.geometry.PartitionedMesh
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import kotlin.math.hypot

/**
 * The area an eraser has removed from one stroke, in page space.
 *
 * ## Why an area and not a list of points
 *
 * Erasing by rebuilding the stroke from surviving input samples can only remove
 * **whole samples**, so it always takes the full width of the line and only cuts
 * where a sample happens to be. Shaving the side off a thick stroke is not
 * expressible that way. Reported from the tablet as *"you can't just erase a bit
 * out of a line, you have to cut it"*, which is a property of the model rather
 * than of the tuning.
 *
 * So the eraser records the region it swept and that region is subtracted at
 * draw time. Precision becomes the eraser's own geometry rather than the stylus
 * sampling rate.
 *
 * ## The representation, and why it is not one big filled path
 *
 * The first version flattened every capsule into a filled outline and unioned
 * them into a single [Path]. It worked, and then it **froze and crashed the app
 * when the erase gesture ended** — a few seconds of scribbling at 240 Hz is
 * thousands of capsules, so that path carried tens of thousands of segments, and
 * `Path.op` on it is a hang. It also grew the per-frame fill cost without bound.
 *
 * What is stored instead is the eraser's **centre line**, as a polyline, grouped
 * into [runs] of constant tip radius. Drawing is then one `drawPath` per run with
 * a round-capped stroking paint: Skia expands the line to the swept capsule at
 * draw time, caches the tessellation, and resolves self-overlap in a single
 * coverage pass so overlapping passes do not double-erase at the joins. The
 * stored path stays proportional to the number of samples, with no boolean ops
 * anywhere.
 *
 * A new run starts only when the tip radius changes, which happens when the zoom
 * changes — so a normal erase gesture is exactly one run.
 *
 * ## What this buys beyond the obvious
 *
 * **The original stroke is never destroyed.** Its inputs and its mesh are
 * untouched; erasing only ever appends to a region beside them (CLAUDE.md rule
 * 1), and un-erasing later is dropping entries rather than reconstructing
 * geometry that no longer exists.
 *
 * ## What M2 owes this
 *
 * SPEC §4.2's op vocabulary cannot say "part of this object is erased". The
 * addition is `SetErasedRegion(target, absolute)` — an **absolute** LWW register
 * carrying the whole region, exactly as `SetTransform` carries a whole matrix
 * and for the same reason (rule 8: deltas break silently under concurrency). A
 * region op on a tombstoned object is a trivial no-op.
 *
 * [capsules] is the durable form: five floats each, resolution-independent,
 * replayable anywhere. [runs] is a render cache.
 */
internal class ErasedRegion {

    /** One eraser sample: the segment it swept, and its tip radius, in page units. */
    data class Capsule(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float, val radius: Float)

    /** A polyline swept at one constant tip radius. */
    class Run(val radius: Float) {
        val path: Path = Path()

        /** Where the polyline currently ends, so a gap can be detected. */
        var endX: Float = Float.NaN
            private set
        var endY: Float = Float.NaN
            private set

        fun extendTo(fromX: Float, fromY: Float, toX: Float, toY: Float) {
            // A capsule that does not start where the last one ended is a new
            // gesture, not a continuation. Without the moveTo, the polyline
            // would run from the end of the previous erase to the start of this
            // one and erase everything in between.
            if (fromX != endX || fromY != endY) path.moveTo(fromX, fromY)
            path.lineTo(toX, toY)
            endX = toX
            endY = toY
        }
    }

    private val _capsules = ArrayList<Capsule>()
    val capsules: List<Capsule> get() = _capsules

    private val _runs = ArrayList<Run>()
    val runs: List<Run> get() = _runs

    val isEmpty: Boolean get() = _capsules.isEmpty()

    // The region's own bounding box, maintained incrementally. It is the cheap
    // rejection test in `isFullyErased` and costs four compares per sample.
    private var minX = Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE
    private var maxY = -Float.MAX_VALUE

    fun add(capsule: Capsule) {
        _capsules += capsule

        val last = _runs.lastOrNull()
        val run = if (last != null && last.radius == capsule.radius) {
            last
        } else {
            Run(capsule.radius).also { _runs += it }
        }
        run.extendTo(capsule.fromX, capsule.fromY, capsule.toX, capsule.toY)

        expandBounds(capsule.fromX, capsule.fromY, capsule.radius)
        expandBounds(capsule.toX, capsule.toY, capsule.radius)
    }

    /**
     * Drops back to [size] capsules, for undo.
     *
     * Runs and bounds are rebuilt from scratch rather than unwound, because
     * unwinding a `Path` is not possible and the bounds are not invertible. This
     * is O(capsules) and runs once per undo, never on an input path.
     */
    fun truncateTo(size: Int) {
        if (size >= _capsules.size) return
        val kept = ArrayList(_capsules.subList(0, size))
        _capsules.clear()
        _runs.clear()
        minX = Float.MAX_VALUE
        minY = Float.MAX_VALUE
        maxX = -Float.MAX_VALUE
        maxY = -Float.MAX_VALUE
        for (capsule in kept) add(capsule)
    }

    private fun expandBounds(x: Float, y: Float, radius: Float) {
        if (x - radius < minX) minX = x - radius
        if (y - radius < minY) minY = y - radius
        if (x + radius > maxX) maxX = x + radius
        if (y + radius > maxY) maxY = y + radius
    }

    fun contains(bounds: PageBounds): Boolean =
        !isEmpty && bounds.xMin >= minX && bounds.yMin >= minY && bounds.xMax <= maxX && bounds.yMax <= maxY

    /** True when [x], [y] lies under some capsule of this region. */
    fun covers(x: Float, y: Float): Boolean = coveringCapsule(x, y, 0) >= 0

    /**
     * ⭐ The index of a capsule covering [x], [y], or -1 — starting the scan at [hint] and wrapping.
     *
     * Exactly [covers]'s answer: every capsule is still visited at most once, so the start point
     * changes only *how long* the search takes, never what it finds.
     *
     * It matters because [isFullyErased] asks this once per point of a stroke, in order, against a
     * capsule list that is also in gesture order — and consecutive points of a stroke are near each
     * other, so the capsule that covered the last one very often covers this one too. Feeding the
     * previous answer back turns the common case from a scan of the whole region into a hit on the
     * first compare. Without it the pass is `points × capsules` in the literal sense, which for a
     * scribbled-over stroke is millions of `hypot` calls and is the reason the budget below exists
     * at all.
     */
    fun coveringCapsule(x: Float, y: Float, hint: Int): Int {
        val size = _capsules.size
        if (size == 0) return -1
        val start = if (hint in 0 until size) hint else 0
        for (step in 0 until size) {
            val index = if (start + step < size) start + step else start + step - size
            val c = _capsules[index]
            if (distanceToSegment(x, y, c.fromX, c.fromY, c.toX, c.toY) <= c.radius) return index
        }
        return -1
    }
}

/**
 * True when [region] covers every part of [stroke].
 *
 * A fully erased stroke is invisible but still costs a layer and a draw call
 * every frame, so it is worth dropping — but only when it really is gone.
 *
 * **No `Path.op`.** That is what froze and killed the app; see [ErasedRegion].
 * This is a coverage test with three early exits, in increasing cost order, so a
 * stroke that is obviously still visible is rejected almost immediately:
 *
 *  1. the stroke's bounds must sit inside the region's bounds — four compares;
 *  2. every **input point** must be covered;
 *  3. every **outline vertex** must be covered.
 *
 * Both 2 and 3 are needed, and testing only one is a real bug in each direction.
 * Input points alone would call a thick stroke erased when a thin eraser has
 * only cleared its centre line — which is precisely the partial-width case this
 * whole design exists to support. Outline vertices alone would call a stroke
 * erased when a ring-shaped erase cleared its edges and left the middle. Ink is
 * only dropped when both agree, because CLAUDE.md rule 1 means a false positive
 * here silently destroys something the user drew.
 */
internal fun isFullyErased(stroke: Stroke, bounds: PageBounds?, region: ErasedRegion): Boolean {
    if (region.isEmpty) return false
    if (bounds == null || !region.contains(bounds)) return false

    // The rolling hint, threaded through every point of this stroke — see [ErasedRegion.coveringCapsule].
    val cursor = intArrayOf(0)

    val inputs = stroke.inputs
    val scratch = StrokeInput()
    for (index in 0 until inputs.size) {
        inputs.populate(index, scratch)
        if (!region.covering(scratch.x, scratch.y, cursor)) return false
    }

    val shape = stroke.shape
    val point = MutableVec()
    for (group in 0 until shape.getRenderGroupCount()) {
        if (!outlinesCovered(shape, group, region, point, cursor)) return false
    }
    return true
}

/** [ErasedRegion.coveringCapsule], remembering its answer in [cursor] for the next point. */
private fun ErasedRegion.covering(x: Float, y: Float, cursor: IntArray): Boolean {
    val found = coveringCapsule(x, y, cursor[0])
    if (found < 0) return false
    cursor[0] = found
    return true
}

/** True when every silhouette vertex of one render group lies inside [region]. */
private fun outlinesCovered(
    shape: PartitionedMesh,
    group: Int,
    region: ErasedRegion,
    point: MutableVec,
    cursor: IntArray,
): Boolean {
    for (outline in 0 until shape.getOutlineCount(group)) {
        for (vertex in 0 until shape.getOutlineVertexCount(group, outline)) {
            shape.populateOutlinePosition(group, outline, vertex, point)
            if (!region.covering(point.x, point.y, cursor)) return false
        }
    }
    return true
}

/** Distance from a point to the segment `a`–`b`. */
private fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val lengthSquared = dx * dx + dy * dy
    val t = if (lengthSquared <= 0f) {
        0f
    } else {
        (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
    }
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}
