package dev.inkstone.android.ink

import android.graphics.Path
import androidx.ink.geometry.MutableVec
import androidx.ink.geometry.PartitionedMesh
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput

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
internal class ErasedRegion : CoverageRegion {

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
    override val capsules: List<Capsule> get() = _capsules

    /**
     * How many times [truncateTo] has taken capsules away from this object.
     *
     * ⚠️ The one thing that makes a paused coverage scan untrue ([ResumableCoverage]): everything
     * else that can happen to a region either appends to it — which cannot uncover a point — or
     * replaces the object outright, which the scan notices by identity. Nothing calls [truncateTo]
     * today (an undone erase is an absolute `SetErasedRegion` since M2), which is exactly why it
     * is counted rather than assumed away: the day something does, the prune must not act on
     * progress measured against capsules that are gone.
     */
    override var shrinks: Int = 0
        private set

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
        shrinks++
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

    /**
     * The four-compare gate every coverage test opens with: an object whose box reaches outside the
     * region's box cannot be fully covered by it, and nothing further need be read.
     */
    fun mayCover(bounds: PageBounds?): Boolean = bounds != null && contains(bounds)

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
     * scribbled-over stroke is millions of distance tests.
     *
     * ⚠️ And it is *why* an estimate of `points × capsules` was the wrong currency for the prune's
     * budget: measured on a real note it was 56–101× the tests this actually runs. The arithmetic
     * lives in [coveringCapsuleIn] now, beside the meter that counts it — see [PruneBudgets].
     */
    fun coveringCapsule(x: Float, y: Float, hint: Int): Int = coveringCapsuleIn(_capsules, x, y, hint, meter = null)
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
 *
 * ⭐ **Unmetered, and the metered form is the same scan.** This answers in full, now — the
 * un-erase path needs that (`StrokeStore.unprune`). The prune queue asks the same question through
 * [DryStroke.probeErasedAway], which runs the identical [CoverageScan] over the identical
 * [StrokeCoveragePoints] and can stop and resume; there is one definition of "covered" either way.
 */
internal fun isFullyErased(stroke: Stroke, bounds: PageBounds?, region: ErasedRegion): Boolean {
    if (!region.mayCover(bounds)) return false
    val verdict = CoverageScan().run(
        points = StrokeCoveragePoints(stroke),
        capsules = region.capsules,
        meter = CoverageMeter.unlimited(),
        scratch = PagePointF(),
    )
    return verdict == PruneVerdict.ERASED_AWAY
}

/**
 * A stroke's coverage points — every **input point**, then every **outline vertex**, render group
 * by render group and outline by outline — by index. See [isFullyErased] for why both halves.
 *
 * The order is the one the coverage test has always used, and it matters beyond tidiness: the scan
 * threads a rolling capsule hint from each point to the next, which only pays off because
 * consecutive points here are neighbours on the page.
 *
 * Random access rather than an iterator because a paused scan resumes at an index
 * ([ResumableCoverage]). Reading a point is one JNI call; the outline's layout is read once, here,
 * as a handful of counts — a stroke's mesh has one render group and one or two outlines.
 *
 * Holds the stroke's mesh and batch, which are immutable (`PreparedPage` builds and probes these
 * off the main thread for that reason), plus two scratch objects, so it is confined to one thread at
 * a time like everything else in [StrokeStore].
 */
internal class StrokeCoveragePoints(stroke: Stroke) : CoveragePoints {
    private val inputs = stroke.inputs
    private val shape: PartitionedMesh = stroke.shape
    private val inputCount = inputs.size

    /** Per outline, in iteration order: its render group, its index in that group, and where it ends. */
    private val outlineGroup: IntArray
    private val outlineIndex: IntArray
    private val outlineEnd: IntArray

    override val count: Int

    private val input = StrokeInput()
    private val vertex = MutableVec()

    /** The outline the last [load] landed in — loads arrive in order, so this is nearly always a hit. */
    private var segment = 0

    init {
        var outlines = 0
        for (group in 0 until shape.getRenderGroupCount()) outlines += shape.getOutlineCount(group)
        outlineGroup = IntArray(outlines)
        outlineIndex = IntArray(outlines)
        outlineEnd = IntArray(outlines)
        var at = 0
        var end = inputCount
        for (group in 0 until shape.getRenderGroupCount()) {
            for (outline in 0 until shape.getOutlineCount(group)) {
                end += shape.getOutlineVertexCount(group, outline)
                outlineGroup[at] = group
                outlineIndex[at] = outline
                outlineEnd[at] = end
                at++
            }
        }
        count = end
    }

    override fun load(index: Int, into: PagePointF) {
        if (index < inputCount) {
            inputs.populate(index, input)
            into.set(input.x, input.y)
            return
        }
        if (segment > 0 && index < outlineEnd[segment - 1]) segment = 0
        while (index >= outlineEnd[segment]) segment++
        val start = if (segment == 0) inputCount else outlineEnd[segment - 1]
        shape.populateOutlinePosition(outlineGroup[segment], outlineIndex[segment], index - start, vertex)
        into.set(vertex.x, vertex.y)
    }
}
