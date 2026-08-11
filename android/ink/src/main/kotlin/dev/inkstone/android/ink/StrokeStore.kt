package dev.inkstone.android.ink

import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.Box
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableParallelogram
import androidx.ink.geometry.ImmutableSegment
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Parallelogram
import androidx.ink.strokes.Stroke
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.StrokeObject

/**
 * A finished stroke, in **page space** (SPEC §4.4 rule 1).
 *
 * @property id the object's [ObjectId] — a UUIDv7, minted when the stroke finished. M1 used a
 *   process-local counter here because there was nothing to persist; ops target objects by id,
 *   so M2 replaces it (CLAUDE.md, "Identity": never derive identity from array position).
 * @property bounds cached at insertion. Recomputing it per hit-test would make erasing
 *   O(strokes) native calls instead of O(strokes) float compares. Null for a stroke whose mesh
 *   came out empty, which then simply cannot be culled.
 */
internal class DryStroke(val id: ObjectId, val stroke: Stroke, val kind: InkBrushKind, val bounds: PageBounds?) {

    /**
     * What an eraser has taken out of this stroke, in page space. Null until something is
     * erased, so an untouched stroke costs nothing. See [ErasedRegion] for why erasing is an
     * area rather than a point list.
     */
    var erased: ErasedRegion? = null
        private set

    fun eraseCapsule(capsule: ErasedRegion.Capsule) {
        val region = erased ?: ErasedRegion().also { erased = it }
        region.add(capsule)
    }

    /**
     * Replace the region wholesale — the render side of `SetErasedRegion`, which is an
     * **absolute** register (CLAUDE.md rule 8). This is how an undone erase drops the capsules
     * that gesture added, and how a loaded page restores the region it was saved with.
     */
    fun setErased(region: ErasedRegion?) {
        erased = region
    }

    /**
     * `PartitionedMesh` builds a spatial index lazily and it is not cheap, so it is built on
     * the first hit-test that actually reaches this stroke — never when the stroke finishes,
     * which is still the ink path (CLAUDE.md rule 7).
     */
    private var spatialIndexReady = false

    private fun ensureSpatialIndex() {
        if (!spatialIndexReady) {
            stroke.shape.initializeSpatialIndex()
            spatialIndexReady = true
        }
    }

    /** True when [sweptTip] covers any painted part of this stroke. */
    fun hitBy(sweptTip: Parallelogram): Boolean {
        ensureSpatialIndex()
        return stroke.shape.computeCoverageIsGreaterThan(sweptTip, 0f, AffineTransform.IDENTITY)
    }
}

/**
 * The dry layer's contents — and, since M2, the render side of a page's op log.
 *
 * ## What M2 changed
 *
 * M1 kept its own undo stack here (`EditHistory`), because there was no log to append to. SPEC
 * §4.6 says undo is *"a new op appended to the log, never a pop"*, and M1's own note said M2
 * would replace the mechanism and keep the behaviour. That is what happened:
 *
 * - Edits are reported to a [PageOpRecorder], one batch per **gesture** — a whole stroke, or a
 *   whole erase drag however many samples it contained. The grain is unchanged, and it is
 *   still [beginEdit]/[endEdit] that marks it.
 * - Undo and redo are no longer implemented here. They arrive as ops through [applyOps], so
 *   the folded log is the single source of truth for what is on the page. The two mechanisms
 *   cannot disagree, because there is only one.
 * - Restoring a removed stroke no longer needs its old list index: z-order lives on the object
 *   as a fractional key, so a re-added stroke sorts back where it was by construction.
 *
 * [eraseAlong] still supports both modes of [EraseMode] and still **never mutates a stroke's
 * point list** (SPEC §3.5, CLAUDE.md rule 8).
 *
 * Not thread-safe: everything here runs on the UI thread, alongside ink capture.
 */
internal class StrokeStore {

    /**
     * SPEC §3.4 — the highlighter draws **over** ink, multiplied ([InkBrushKind.drawsOverInk]
     * records why that reversed after M4). Two lists rather than one sorted list, so render
     * order is free rather than a per-frame sort of 5,000 elements.
     *
     * This is a *band*, not a z-order: within each band, order is insertion order, which
     * matches the fractional keys the recorder hands out in the same sequence.
     */
    private val ink = ArrayList<DryStroke>()
    private val highlight = ArrayList<DryStroke>()

    private val byId = HashMap<ObjectId, DryStroke>()

    /**
     * Where edits are reported. Null only before a page is bound — a store with no recorder is
     * a store whose edits go nowhere, which is correct for the synthetic-load harness and for
     * nothing else.
     */
    var recorder: PageOpRecorder? = null

    /** Which input model finished strokes were captured with, for the geometry blob. */
    var inputModel: InkInputModel = InkInputModel.SPRING

    /** Bumped on every change, so views can skip redundant invalidations. */
    var revision: Long = 0L
        private set

    val size: Int get() = ink.size + highlight.size

    // --- Gesture brackets ---------------------------------------------------

    private var inGesture = false
    private val removedThisGesture = ArrayList<ObjectId>()
    private val erasedThisGesture = ArrayList<DryStroke>()

    /**
     * Groups everything until [endEdit] into one op batch — a whole stroke, or a whole erase
     * drag. Nested calls are ignored rather than rejected: a stylus gesture and a toolbar
     * action can overlap in ways not worth defending against, and the worst case is that both
     * land in one batch.
     */
    fun beginEdit() {
        if (inGesture) return
        inGesture = true
        removedThisGesture.clear()
        erasedThisGesture.clear()
        recorder?.beginGesture()
    }

    /**
     * Closes the batch, emitting one op per thing the gesture changed.
     *
     * Erasures are emitted here and not per sample: a scribble-erase at 240 Hz would otherwise
     * produce thousands of ops, each carrying a slightly longer copy of the same absolute
     * region.
     */
    fun endEdit() {
        if (!inGesture) return
        recorder?.let { recorder ->
            for (id in removedThisGesture) recorder.deleted(id)
            for (dry in erasedThisGesture) recorder.erasedRegionChanged(dry.id, dry.erased)
            recorder.endGesture()
        }
        removedThisGesture.clear()
        erasedThisGesture.clear()
        inGesture = false
    }

    // --- Insertion ----------------------------------------------------------

    /**
     * A stroke the user just drew. Assigns its [ObjectId] and queues its `AddObject`.
     *
     * @return the id it was given, or **null** when there is no recorder and the fallback id was
     *   used. M12 needs it for the timeline, and the distinction matters: an unbound surface
     *   records nothing to disk, so an `unbound-N` id names an object no log will ever contain and
     *   must not be written into a recording's timeline either. (An unbound surface is a real
     *   state, not a bug — see `NoteEditor.bindSurface` for the ordering race it exists to
     *   tolerate.)
     */
    fun add(stroke: Stroke, kind: InkBrushKind): ObjectId? {
        val recorded = recorder?.strokeFinished(stroke, kind, inputModel)
        val id = recorded ?: ObjectId(UNBOUND_ID_PREFIX + size)
        insert(DryStroke(id, stroke, kind, PageBounds.of(stroke.shape.computeBoundingBox())))
        revision++
        return recorded
    }

    /**
     * Bulk insert, for the synthetic load the SPEC §3.6 frame-rate gate needs.
     *
     * ⚠️ Deliberately does **not** report the ids. This is the "load 5,000 strokes" button, and
     * M12's timeline hangs off `onStrokesFinished` rather than off [add] precisely so that pressing
     * it during a lecture does not put five thousand entries in the timeline in one frame.
     */
    fun addAll(strokes: List<Pair<Stroke, InkBrushKind>>) {
        for ((stroke, kind) in strokes) add(stroke, kind)
    }

    private fun insert(dry: DryStroke) {
        (if (dry.kind.drawsOverInk) highlight else ink).add(dry)
        byId[dry.id] = dry
    }

    private fun remove(dry: DryStroke) {
        (if (dry.kind.drawsOverInk) highlight else ink).remove(dry)
        byId.remove(dry.id)
    }

    /**
     * Apply ops to the render state — the load path, and the undo/redo path.
     *
     * This is the only way anything other than a live gesture changes what is drawn, which is
     * what keeps the folded log and the screen in agreement. Ops this layer cannot draw
     * (transforms, styles, text) are ignored rather than rejected: they are M8's, and an op
     * arriving before the code that draws it must not be an error.
     *
     * @param record true when these ops originated here and still need journaling. A page
     *   being loaded is already on disk and must not be written back.
     */
    fun applyOps(ops: List<PageOp>, brushes: InkBrushFactory, record: Boolean = false) {
        var changed = false
        for (op in ops) {
            if (applyOp(op, brushes, record)) changed = true
        }
        if (changed) revision++
    }

    private fun applyOp(op: PageOp, brushes: InkBrushFactory, record: Boolean): Boolean {
        val changed = when (op) {
            is PageOp.AddObject -> addObject(op, brushes)

            is PageOp.DeleteObject -> byId[op.target]?.let {
                remove(it)
                true
            } ?: false

            // Absolute: the whole region is replaced, never merged.
            is PageOp.SetErasedRegion -> byId[op.target]?.let {
                it.setErased(op.absolute.toRegion())
                true
            } ?: false

            // M8's ops. Journaled if they came from here, but nothing on this layer draws them.
            else -> false
        }
        if (record) recorder?.record(op)
        return changed
    }

    private fun addObject(op: PageOp.AddObject, brushes: InkBrushFactory): Boolean {
        val obj = op.obj as? StrokeObject ?: return false
        // A re-add of something already on screen is an undo of a delete that never took effect
        // here — nothing to do, and inserting twice would double-draw it.
        if (obj.id in byId) return false
        val decoded = StrokeGeometryCodec.decode(obj.geometry, brushes) ?: return false
        val dry = DryStroke(
            id = obj.id,
            stroke = decoded.stroke,
            kind = decoded.kind,
            bounds = PageBounds.of(decoded.stroke.shape.computeBoundingBox()),
        )
        dry.setErased(obj.erased.toRegion())
        insert(dry)
        return true
    }

    /** Drop everything on screen without touching the page's log. Used when switching pages. */
    fun reset() {
        ink.clear()
        highlight.clear()
        byId.clear()
        removedThisGesture.clear()
        erasedThisGesture.clear()
        inGesture = false
        revision++
    }

    /**
     * Clear the page — as ops, so it is undoable and so it reaches disk.
     *
     * M1 made this deliberately non-undoable because there was nowhere to put the strokes.
     * There is now: a delete is a tombstone and the geometry stays in the log (CLAUDE.md rule
     * 1), so clearing a page is exactly as recoverable as erasing it stroke by stroke.
     */
    fun clear() {
        beginEdit()
        for (dry in byId.values.toList()) {
            removedThisGesture += dry.id
            remove(dry)
        }
        endEdit()
        revision++
    }

    // --- Rendering ----------------------------------------------------------

    /**
     * Visit every stroke in render order — ink first, highlighters last, so a highlight
     * multiplies over the writing it was drawn across.
     *
     * [visibleBounds] culls in page space. It does nothing when the whole page is on screen; it
     * earns its keep when zoomed in, which is where the frame budget is tightest because each
     * visible stroke covers more pixels.
     */
    fun forEachInRenderOrder(visibleBounds: Box?, action: (DryStroke) -> Unit) {
        emit(ink, visibleBounds, action)
        emit(highlight, visibleBounds, action)
    }

    private fun emit(strokes: List<DryStroke>, visibleBounds: Box?, action: (DryStroke) -> Unit) {
        for (index in strokes.indices) {
            val dry = strokes[index]
            if (visibleBounds == null || overlaps(dry.bounds, visibleBounds)) action(dry)
        }
    }

    private fun overlaps(a: PageBounds?, b: Box): Boolean =
        a == null || (a.xMin <= b.xMax && a.xMax >= b.xMin && a.yMin <= b.yMax && a.yMax >= b.yMin)

    // --- Erasing ------------------------------------------------------------

    /**
     * Erase along the segment the eraser travelled since the last sample, with
     * [radiusPageUnits] of slop around the tip.
     *
     * Testing the *segment* rather than only the endpoints is what makes a fast eraser flick
     * work: with a hand moving 30 cm/s, endpoints alone leave un-erased gaps between samples —
     * the same failure mode as skipping historical points when drawing.
     *
     * @return the number of strokes the eraser changed.
     */
    fun eraseAlong(from: PagePointF, to: PagePointF, radiusPageUnits: Float, mode: EraseMode): Int {
        // The exact region the eraser tip swept: the segment expanded by the tip radius, as a
        // rotated rectangle. A bounding box would over-erase on a diagonal flick; a bare
        // segment has no width and would under-erase a slow, precise dab.
        val sweptTip = ImmutableParallelogram.fromSegmentAndPadding(
            ImmutableSegment(ImmutableVec(from.x, from.y), ImmutableVec(to.x, to.y)),
            radiusPageUnits,
        )
        val coarse = ImmutableBox.fromTwoPoints(
            ImmutableVec(minOf(from.x, to.x) - radiusPageUnits, minOf(from.y, to.y) - radiusPageUnits),
            ImmutableVec(maxOf(from.x, to.x) + radiusPageUnits, maxOf(from.y, to.y) + radiusPageUnits),
        )
        val capsule = ErasedRegion.Capsule(from.x, from.y, to.x, to.y, radiusPageUnits)

        val touched = applyErase(ink, coarse, sweptTip, capsule, mode) +
            applyErase(highlight, coarse, sweptTip, capsule, mode)
        if (touched > 0) revision++
        return touched
    }

    private fun applyErase(
        strokes: MutableList<DryStroke>,
        coarse: ImmutableBox,
        sweptTip: Parallelogram,
        capsule: ErasedRegion.Capsule,
        mode: EraseMode,
    ): Int {
        var touched = 0
        val iterator = strokes.iterator()
        while (iterator.hasNext()) {
            val dry = iterator.next()
            // Cheap float compares first. Only strokes whose bounds the eraser actually swept
            // get a native coverage test, which keeps erasing usable with 5,000 strokes.
            if (!overlaps(dry.bounds, coarse) || !dry.hitBy(sweptTip)) continue

            touched++
            if (mode == EraseMode.WHOLE_STROKE) {
                removedThisGesture += dry.id
                iterator.remove()
                byId.remove(dry.id)
            } else {
                dry.eraseCapsule(capsule)
                // One entry per stroke per gesture; the op carries the whole region at the end.
                if (erasedThisGesture.none { it === dry }) erasedThisGesture += dry
            }
        }
        return touched
    }

    /**
     * Drop strokes that have been erased away completely.
     *
     * Runs when an erase gesture ends, never per sample (CLAUDE.md rule 7). Until it runs, a
     * fully erased stroke is simply invisible, which the user cannot tell apart from gone.
     *
     * **This emits no ops.** A fully erased stroke is not deleted — its `SetErasedRegion`
     * already describes it, and dropping it here is a render optimisation. Emitting a delete
     * would make un-erasing it impossible, and would be the one place in this file that
     * destroyed something.
     *
     * **Bounded work.** [isFullyErased] costs roughly `points × capsules` for a stroke that
     * really is covered, and a determined scribble-erase produces thousands of capsules. The
     * budget below means the pathological case degrades into *not pruning yet* rather than into
     * a frozen UI. An earlier version had no such bound and used `Path.op`, and it hung and
     * killed the app on exactly this input.
     */
    fun pruneFullyErased() {
        var removed = false
        var budget = PRUNE_BUDGET
        for (list in listOf(ink, highlight)) {
            val iterator = list.iterator()
            while (iterator.hasNext() && budget > 0) {
                val dry = iterator.next()
                val region = dry.erased ?: continue
                budget -= region.capsules.size
                if (isFullyErased(dry.stroke, dry.bounds, region)) {
                    iterator.remove()
                    byId.remove(dry.id)
                    removed = true
                }
            }
        }
        if (removed) revision++
    }

    private companion object {
        /**
         * Capsule-tests allowed per prune pass. Sized so the worst case stays comfortably
         * inside one frame rather than by measurement — the number to trust is whatever the
         * device says, not this one.
         */
        const val PRUNE_BUDGET = 200_000

        /**
         * Ids for a store with no page bound — the synthetic-load harness and previews. They
         * never reach disk, and they are visibly not UUIDs so that one appearing in a file
         * would be obvious rather than plausible.
         */
        const val UNBOUND_ID_PREFIX = "unbound-"
    }
}

/**
 * SPEC §3.5, revised twice in M1 after device testing.
 *
 * The spec deferred stroke-splitting to M8a and made whole-stroke erase the M1 behaviour. On
 * the tablet that turned out to be the wrong default: an eraser that deletes an entire line
 * because you clipped its tail is startling, and every other note app on the device does it the
 * other way.
 *
 * [PARTIAL] is therefore the default. [WHOLE_STROKE] stays as a setting, because it is
 * genuinely cheaper — it touches no region — and some people prefer it.
 *
 * [PARTIAL] erases an **area**, not a set of input samples. See [ErasedRegion] for why:
 * sample-based erasing can only ever remove the full width of a line, at whatever granularity
 * the stylus happened to sample, so it cuts rather than erases. Neither mode mutates a stroke's
 * point list.
 */
public enum class EraseMode {
    /** Erase exactly the area the eraser tip passed over, keeping the rest of the stroke. */
    PARTIAL,

    /** Delete any stroke the eraser touches, whole. */
    WHOLE_STROKE,
}

/**
 * A stroke's bounding box in page space, as four plain floats.
 *
 * `androidx.ink` has a perfectly good [ImmutableBox], but the only way to get one from a [Box]
 * is `Box.toImmutable()`, which is `@RestrictTo(LIBRARY_GROUP)` in 1.0.0 — see the note on
 * [InkBrushKind]. Copying four floats out is both allowed and cheaper: this is read once per
 * stroke per erase sample, and a float compare beats a native call by a wide margin.
 */
internal class PageBounds(val xMin: Float, val yMin: Float, val xMax: Float, val yMax: Float) {
    companion object {
        fun of(box: Box?): PageBounds? = box?.let { PageBounds(it.xMin, it.yMin, it.xMax, it.yMax) }
    }
}

/**
 * A mutable page-space point used on the input path.
 *
 * [dev.inkstone.core.model.PagePoint] is the immutable type that crosses module boundaries;
 * this one exists so that erasing at 240 samples a second does not allocate 240 objects a
 * second (CLAUDE.md rule 7).
 */
internal class PagePointF(var x: Float = 0f, var y: Float = 0f) {
    fun set(x: Float, y: Float) {
        this.x = x
        this.y = y
    }

    fun set(other: PagePointF) {
        set(other.x, other.y)
    }
}
