package dev.inkstone.android.ink

import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.Box
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableParallelogram
import androidx.ink.geometry.ImmutableSegment
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.MutableVec
import androidx.ink.geometry.Parallelogram
import androidx.ink.strokes.Stroke
import dev.inkstone.android.ink.InkColor.withOpacity
import dev.inkstone.core.model.AnchorTarget
import dev.inkstone.core.model.ConnectorEndState
import dev.inkstone.core.model.ConnectorTargets
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.InkObject
import dev.inkstone.core.model.Lasso
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.SelectionEdit
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.ShapeRecord
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.TextBoxObject
import dev.inkstone.core.model.asAnchorTarget
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A finished stroke, in **page space** (SPEC §4.4 rule 1) — or, since M8a, in its own space
 * with [DryObject.transform] placing it on the page.
 *
 * ⭐ **M8c moved everything a stroke has in common with an image or a text box up into
 * [DryObject]** — the two boxes and the rule that they are written together, the absolute
 * registers, the cached inverse. What is left here is the half that is genuinely about ink: a
 * tessellated mesh, the brush it was captured with, and a coverage test that can answer what an
 * eraser swept.
 *
 * @property id the object's [ObjectId] — a UUIDv7, minted when the stroke finished. M1 used a
 *   process-local counter here because there was nothing to persist; ops target objects by id,
 *   so M2 replaces it (CLAUDE.md, "Identity": never derive identity from array position).
 */
internal class DryStroke(
    override val id: ObjectId,
    stroke: Stroke,
    val kind: InkBrushKind,
    /**
     * The smoothing model this stroke was **captured** with, not the toolbar's current one.
     *
     * Carried because [restyle] re-tessellates, and re-tessellating with today's setting would
     * silently re-shape a stroke drawn months ago the moment its colour was changed — the same
     * mistake `InkBrushFactory.brushFor(kind, …)` exists to avoid on the load path, arriving by
     * a different door.
     */
    val inputModel: InkInputModel = InkInputModel.SPRING,
    transform: Matrix3 = Matrix3.Identity,
    style: ObjectStyle? = null,
    z: FractionalKey = FractionalKey.Initial,
) : DryObject(transform, style, z) {

    /**
     * The stroke as it is currently drawn.
     *
     * ⚠️ **`var`, and that is not a mutation of user data.** A `SetStyle` changes the brush,
     * and `Brush.size` is in stroke units — so restyling re-tessellates and produces a
     * genuinely different mesh. The *inputs* are untouched and the geometry blob on disk is
     * untouched; what changes is the rendering of them, which is what a style register is
     * (see [ObjectStyle] on why a stroke's captured colour and its style override are two
     * different things and only one is the truth).
     *
     * Everything downstream — erasing, the PDF outline, `isFullyErased` — reads this rather
     * than the captured stroke, because what the user erases is what the user can see.
     */
    var stroke: Stroke = stroke
        private set

    /** SPEC §3.4 — the highlighter band. See [DryObject.drawsOverInk]. */
    override val drawsOverInk: Boolean get() = kind.drawsOverInk

    init {
        // Null for a stroke whose mesh came out empty, which then simply cannot be culled.
        setBounds(PageBounds.of(stroke.shape.computeBoundingBox()))
    }

    /**
     * The render side of `SetStyle`.
     *
     * Rebuilds the brush and therefore the mesh, off the ink path — a restyle is a toolbar
     * action, never something that happens between `ACTION_DOWN` and the front buffer.
     *
     * ⚠️ The erased region is **kept**. It is in this stroke's own space and a restyle does
     * not move the stroke, so the bite stays where the user put it — even though the line may
     * now be a different width through it, which is the honest rendering of "this part is
     * gone" and what the screen and the published PDF will agree on.
     */
    override fun restyle(style: ObjectStyle?, brushes: InkBrushFactory) {
        super.restyle(style, brushes)
        if (style == null) return
        // The captured-vs-style rule lives on the factory, shared with the load path, so a
        // stroke restyled here and one reloaded from disk cannot tessellate differently.
        val brush = brushes.brushFor(
            kind = kind,
            capturedColorArgb = stroke.brush.colorIntArgb,
            capturedSizePt = stroke.brush.size,
            epsilonPt = stroke.brush.epsilon,
            inputModel = inputModel,
            style = style,
        )
        stroke = Stroke(brush, stroke.inputs)
        // Through [DryObject.setBounds], so the page-space box is rebuilt with the object-space
        // one. Re-tessellation is the one thing that changes a stroke's own extent.
        setBounds(PageBounds.of(stroke.shape.computeBoundingBox()))
        spatialIndexReady = false
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

    /**
     * True when [sweptTip] covers any painted part of this stroke.
     *
     * [sweptTip] is in **this stroke's own space** — the caller maps it there with [toLocal],
     * rather than handing ink a `meshToQuery` transform. Two reasons: the query stays a
     * genuine [Parallelogram] (an affine image of one is, but ink's constructors build them
     * from a segment and a padding, so re-expressing a sheared one is not free), and the
     * erased region this test decides to extend is in that space anyway.
     *
     * [capsule] is the same sweep in the form a vector outline needs and is unused here — a
     * tessellated mesh is measured by ink's own coverage test. See [DryObject.hitBy].
     */
    override fun hitBy(sweptTip: Parallelogram, capsule: ErasedRegion.Capsule): Boolean {
        ensureSpatialIndex()
        return stroke.shape.computeCoverageIsGreaterThan(sweptTip, 0f, AffineTransform.IDENTITY)
    }

    /**
     * Both the mesh and [DryObject.bounds] are in this stroke's **own** space — which is what makes
     * the comparison correct for a moved stroke without any mapping at all.
     */
    override fun isErasedAway(region: ErasedRegion): Boolean = isFullyErased(stroke, bounds, region)

    override fun eraseCoverageCost(region: ErasedRegion): Long {
        val bounds = bounds
        // The same cheap gates `isFullyErased` opens with: a stroke they reject costs four
        // compares, and charging it the full estimate would spend the whole budget on strokes
        // the test never actually measures.
        if (region.isEmpty || bounds == null || !region.contains(bounds)) return 0L
        // Input points plus outline vertices, and the outline runs to roughly two vertices per
        // input. An upper-boundish estimate is all a budget needs.
        return stroke.inputs.size.toLong() * COVERAGE_POINTS_PER_INPUT * region.capsules.size
    }

    private companion object {
        const val COVERAGE_POINTS_PER_INPUT = 3L
    }

    /**
     * Every silhouette vertex, in **page space** — what a lasso is tested against.
     *
     * The outline rather than the input path, because a lasso drawn inside a thick loop must
     * select the loop: the input path of a 16 pt highlighter stroke is a line through the
     * middle of a band four millimetres wide, and testing only that would make selection
     * disagree with what is on the screen by half the brush width in every direction.
     */
    override fun forEachPagePoint(scratch: MutableVec, action: (Float, Float) -> Unit) {
        val shape = stroke.shape
        for (group in 0 until shape.getRenderGroupCount()) {
            for (outline in 0 until shape.getOutlineCount(group)) {
                for (vertex in 0 until shape.getOutlineVertexCount(group, outline)) {
                    shape.populateOutlinePosition(group, outline, vertex, scratch)
                    val mapped = transform.map(PagePoint(scratch.x, scratch.y))
                    action(mapped.x, mapped.y)
                }
            }
        }
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
 * ## ⭐ What M8a changed
 *
 * `SetTransform`, `SetStyle` and `SetZOrder` used to fall through [applyOps]'s `else` branch
 * with the comment *"M8's ops … nothing on this layer draws them"*. They are drawn now, and
 * that sentence was the whole feature: an op the fold resolved correctly, journaled, uploaded
 * and merged, and that the screen ignored. This layer therefore carries the three registers
 * per object, and — the part that is easy to miss — every **page-space** question in the file
 * now asks [DryObject.pageBounds] while every **object-space** one asks [DryObject.bounds].
 *
 * [eraseAlong] still supports both modes of [EraseMode] and still **never mutates a stroke's
 * point list** (SPEC §3.5, CLAUDE.md rule 8).
 *
 * ## ⭐ What M8c changed
 *
 * It holds [DryObject]s, not only strokes. The name is now a small lie and is kept anyway: every
 * caller in the app and in two other modules names it, and renaming a type is not evidence of
 * anything. What matters is the reason it must be **one** store — [topZ]. Every other design puts
 * a second counter beside this one and the first `SetZOrder` makes them disagree, silently, in the
 * direction of *"the stroke I just drew went behind the picture"*. See [DryObject].
 *
 * Not thread-safe: everything here runs on the UI thread, alongside ink capture.
 */
// This class is the render side of the whole op vocabulary, so it has one small function per op
// kind by construction — and SPEC §4.2 froze that vocabulary, which is what bounds the count.
// Merging branches to satisfy a total is the opposite of what makes it checkable: the value of
// `applyRegister` and its two helpers is that each writes one whole absolute value, visibly.
// Suppressed rather than raising the shared threshold so nothing else inherits the exemption.
@Suppress("TooManyFunctions")
internal class StrokeStore {

    /**
     * SPEC §3.4 — the highlighter draws **over** ink, multiplied ([InkBrushKind.drawsOverInk]
     * records why that reversed after M4). Two lists rather than one sorted list, so render
     * order is free rather than a per-frame sort of 5,000 elements.
     *
     * This is a *band*, not a z-order. Within a band the order is `(z, id)` — the same
     * comparator `FoldedPage` sorts by — which since M8a is a real sort rather than insertion
     * order, because `SetZOrder` can now move something. It is done lazily and only when a key
     * has actually changed: a page load inserts in fold order, which is already sorted.
     *
     * ⭐ Since M8c the ink band holds every non-stroke object too ([DryObject.drawsOverInk]), so
     * `(z, id)` interleaves a picture and the writing over it in one order rather than in two that
     * have to be reconciled at draw time.
     */
    private val ink = ArrayList<DryObject>()
    private val highlight = ArrayList<DryObject>()

    private val byId = HashMap<ObjectId, DryObject>()

    /**
     * ⭐ Strokes [pruneFullyErased] has taken off the page, held so an **un-erase can put them
     * back**.
     *
     * ⚠️ Without this, erasing a stroke away and pressing undo did nothing on screen and stayed
     * wrong until the page was reloaded. The chain is entirely reasonable one link at a time:
     * pruning emits no op (a fully erased stroke is not deleted — its `SetErasedRegion` already
     * describes it), so the object is still in the fold; `NoteEditor.reconcile` therefore sees it
     * in **both** folds and emits a bare `SetErasedRegion` rather than an `AddObject`; and
     * [applyRegister] opened with `byId[target] ?: return false`. Correct op, correct fold, one
     * consumer that could not act on it — the shape CLAUDE.md's definition-of-done #6 is about,
     * and the same one `DryShape`'s missing `setErased` had (M8 follow-up 3). It was intermittent
     * because pruning is capped by [PRUNE_BUDGET], so whether a given stroke had actually left the
     * page depended on how much else had been erased.
     *
     * Retaining the object costs the memory the page was already carrying: pruning is a **render**
     * optimisation by its own KDoc, not a way to release a stroke, and nothing about it was ever
     * meant to be one-way.
     *
     * Only strokes ever appear here — [DryObject.isErasedAway] answers true for nothing else — so
     * this map holds no [DryShape] and [shapes] has nothing to say about it.
     */
    private val prunedByErase = HashMap<ObjectId, DryObject>()

    /** Set when a z key changed or an object was inserted out of order. See [ink]. */
    private var orderDirty = false

    /**
     * ⭐ M8b — set whenever anything a connector's route could depend on changed.
     *
     * Re-routing is lazy and happens at most once per frame, in [forEachInRenderOrder], because a
     * drag emits a `previewTransform` per frame and re-routing eagerly on each would do the work
     * twice for the frames that never get drawn. It is O(shapes), not O(objects) — see [shapes],
     * which is where that stopped being merely an aspiration.
     *
     * ⚠️ **This is not a cache of a persisted value.** Nothing about a route is ever written down
     * (SPEC §4.8.3); this flag only decides *when* to recompute something that is always derived.
     */
    private var routesDirty = false

    /**
     * ⭐ Every [DryShape] on the page, in insertion order — the whole population
     * [rerouteConnectors] walks.
     *
     * ## Why this exists
     *
     * [routesDirty]'s KDoc has claimed since M8b that the re-route is *"O(connectors), not
     * O(objects): a page has a handful of connectors and thousands of strokes"*. That was true of
     * the **work** and false of the **scan that found it**: the pass walked [ink] looking for
     * connectors, so a page with 5,000 strokes and *no* connectors paid a 5,000-element scan, per
     * drawn frame, to find something it could not contain. And [routesDirty] is set by ordinary
     * object mutation — an insert, a delete, a `SetTransform`, every frame of a drag's
     * [previewTransform] — so it is set more or less continuously while the user is editing. A
     * KDoc that describes the cost of the answer rather than the cost of the search is the
     * expensive kind; the claim is true of both halves now.
     *
     * ## ⚠️ Why it indexes shapes and not connectors
     *
     * Being a *connector* is a property of the record register, and `SetTextContent` can turn it
     * on and off (SPEC §4.8.2, [ShapeRecord.isConnector]). A set of *connectors* would therefore
     * need maintaining at every writer of that register — and a writer that forgets is a connector
     * that silently stops following the box it is attached to, which reads to the user as *"the
     * arrow doesn't move"* and to a reviewer as nothing at all.
     *
     * Being a *shape* is a property of the object's kind and cannot change while it is on the
     * page. So membership is decided in exactly the two places an object enters ([insert]) and
     * leaves ([unindex]) the store — the same two places `byId` is maintained, so forgetting one
     * breaks the id lookup first and loudly. What is left at re-route time is a field read per
     * shape, and the shapes on a page are the handful the original claim was always about.
     */
    private val shapes = LinkedHashSet<DryShape>()

    /**
     * How many objects one re-route pass visits — the entire cost of [rerouteConnectors].
     *
     * Exposed because the only honest thing to assert about that pass is a **bound**, and a bound
     * cannot be read off a screen or a screenshot: the pass was O(objects) for as long as
     * connectors have existed while its KDoc said O(connectors), and every test of it passed
     * throughout. `StrokeStoreConnectorIndexTest` asserts this is **zero** for a page of pure
     * handwriting.
     */
    internal val reroutePassSize: Int get() = shapes.size

    /**
     * Ids this store has seen deleted, so a detached connector can say *which* kind of detached.
     *
     * Grows with the page's deletions and is cleared by [reset], which is the same lifetime as
     * everything else here. See [tombstone]; [clear] is the only other writer, in bulk.
     */
    private val tombstones = HashSet<ObjectId>()

    /**
     * ⭐ The highest z key on the page — where the **next** stroke goes.
     *
     * ## Why this moved here in M8a
     *
     * `PageOpRecorder` used to keep its own `lastZ` and advance it per stroke. That was correct
     * for as long as nothing else could write a z key, and `SetZOrder` is exactly the thing that
     * can: bring one stroke to the front and the recorder's private counter is now *below* the
     * top of the page, so **the next stroke the user draws lands underneath it** — on screen, in
     * the published PDF's `/Annots` order, and on every peer. No error, and no reason for anyone
     * to connect the two actions.
     *
     * The store is the one object that sees every source of a key — a capture, a load, a peer's
     * op, an undo — so it is the only place the maximum can be maintained without a scan. Kept
     * incrementally, compare-and-raise, because scanning the page per finished stroke would be
     * O(strokes) on the ink path (CLAUDE.md rule 7) and this is O(1).
     */
    private var topZ: FractionalKey? = null

    private fun raiseTop(key: FractionalKey) {
        if (!key.isWellFormed) return
        val current = topZ
        if (current == null || key > current) topZ = key
    }

    /** The next key above everything on the page, and it becomes the new top. */
    private fun allocateTopZ(): FractionalKey = SelectionEdit.keyAbove(topZ).also { topZ = it }

    /**
     * [count] keys above everything, ascending — for a duplicate, which lands on top, and for a
     * tool that inserts an image or a text box.
     *
     * ⭐ *Everything*, and since M8c that includes the objects this build cannot paint. A key
     * allocated above only the strokes would put a new stroke under a picture that is already
     * there, which is the same regression as a private counter and no easier to trace.
     */
    fun allocateTopZ(count: Int): List<FractionalKey> =
        SelectionEdit.keysAbove(topZ, count).also { keys -> keys.lastOrNull()?.let { topZ = it } }

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

    /**
     * How many objects are on the page — since M8c that is strokes *and* anything else the store
     * holds, so an image counts. `InkSurfaceView.strokeCount` reports this to a stat label; the
     * label is the only consumer that cares about the difference and it is not worth a second
     * counter maintained at five mutation points.
     */
    val size: Int get() = ink.size + highlight.size

    // --- Gesture brackets ---------------------------------------------------

    private var inGesture = false
    private val removedThisGesture = ArrayList<ObjectId>()
    private val erasedThisGesture = ArrayList<DryObject>()

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
        val z = allocateTopZ()
        val recorded = recorder?.strokeFinished(stroke, kind, inputModel, z)
        val id = recorded ?: ObjectId(UNBOUND_ID_PREFIX + size)
        val dry = DryStroke(id, stroke, kind, inputModel, z = z)
        insert(dry)
        revision++
        noteAppendedOnTop(dry)
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

    // --- The append log, so a full page need not be re-recorded per stroke -------------------

    /**
     * ⭐ Objects that went on **top of everything** and the revision each arrived at.
     *
     * ## Why this exists
     *
     * `DryStrokesView` caches the page as a display list keyed on [revision], so writing on a full
     * page re-recorded every object roughly once per six strokes — **measured at 48–105 ms per
     * re-record at 1,121 objects**, at fit-width, where nothing is off screen to cull. That is the
     * stutter a user reports as *"it lags when there are a lot of strokes"*, and no amount of
     * culling helps: at fit-width the whole page is visible by definition.
     *
     * ## Why it is safe, which is the only interesting part
     *
     * [appendsSince] answers **null — meaning "re-record everything"— unless it can prove the
     * store did nothing but append.** The proof is arithmetic, not bookkeeping: [add] is the one
     * path that files an entry here and it bumps [revision] by exactly one when it does, so the
     * filed revisions must come out **contiguous and complete** across the queried range. Any
     * other mutation — an erase, a transform, a peer's op, an undo, a restyle, a delete — bumps
     * [revision] without filing anything, which leaves a gap, which is refused.
     *
     * That direction matters. A mutation site added later that forgets this list costs a
     * re-record; it cannot leave a stale picture on the glass. The opposite design — a flag each
     * mutation must remember to clear — fails the other way, and CLAUDE.md has six entries about
     * features that were correct in every test and wrong in the app.
     */
    private class Append(val revision: Long, val dry: DryObject)

    private val appends = ArrayDeque<Append>()

    /**
     * File [dry] as an append, if drawing it *after* everything already recorded is the same
     * picture as re-sorting the page.
     *
     * Two conditions, both about render order. Its key must genuinely be the page's top — [add]
     * allocates one, but `SetZOrder` may since have put something above it. And it must not be an
     * ink stroke on a page that has highlighters: the cached recording holds both bands, and
     * SPEC §3.4 draws the highlighter band **last**, so appending ink after it would put the
     * writing over the highlight instead of under it.
     */
    private fun noteAppendedOnTop(dry: DryObject) {
        if (!dry.drawsOverInk && highlight.isNotEmpty()) return
        if (topZ != dry.z) return
        appends.addLast(Append(revision, dry))
        while (appends.size > MAX_APPENDS) appends.removeFirst()
    }

    /**
     * Fills [out] with the objects appended since [since], or returns **false if the page must be
     * re-recorded**.
     *
     * ⚠️ **The caller supplies the list because this is called from `onDraw`** (CLAUDE.md rule 7 —
     * no allocation there). The first version returned `List<DryObject>?` and built it with
     * `map`, which allocated an `ArrayList` of up to [MAX_APPENDS] on every frame the user was
     * writing — in the one loop this whole mechanism exists to make cheap.
     *
     * @param since the revision the caller last recorded the whole page at.
     */
    fun appendsSince(since: Long, out: MutableList<DryObject>): Boolean {
        out.clear()
        if (since == revision) return true
        if (since > revision) return false
        val wanted = revision - since
        if (wanted > appends.size) return false
        val first = appends.size - wanted.toInt()
        // Contiguous and complete, or something that is not an append happened in the gap.
        for (index in first until appends.size) {
            if (appends[index].revision != since + (index - first) + 1) {
                out.clear()
                return false
            }
            out += appends[index].dry
        }
        return true
    }

    /**
     * How many objects carry an erased region — each of which costs a `saveLayer` plus a
     * `DST_OUT` composite **per frame** (SPEC §3.5), not merely a draw call.
     *
     * Exposed because the erase half of the frame budget is otherwise unmeasurable from outside
     * the process, so *"is this note slow because of draw calls or because of render-pass
     * switches?"* cannot be answered on a real user's real note. Memoised on [revision]: the
     * Measure panel recomposes on every camera change, and an O(N) walk there would land once
     * per pan frame — measuring the measurement, which is the trap [LatencyStats] documents.
     */
    val erasedCount: Int
        get() {
            if (erasedCountRevision != revision) {
                erasedCountValue = ink.count { it.erased != null } + highlight.count { it.erased != null }
                erasedCountRevision = revision
            }
            return erasedCountValue
        }

    private var erasedCountValue = 0
    private var erasedCountRevision = -1L

    /**
     * Whether the page holds any highlighter object.
     *
     * ⚠️ Worth surfacing because **one highlighter silently disables the append fast path** for
     * every pen stroke on the page — [noteAppendedOnTop] refuses ink while the highlight band is
     * occupied, since the recording holds both bands and SPEC §3.4 draws highlighters last. A note
     * that is slow to write on while a visually identical one is not is otherwise a mystery.
     */
    val hasHighlighter: Boolean get() = highlight.isNotEmpty()

    private fun insert(dry: DryObject) {
        (if (dry.drawsOverInk) highlight else ink).add(dry)
        byId[dry.id] = dry
        // ⭐ The re-route pass walks this instead of every object on the page. Beside `byId`
        // deliberately: this is the one door into the store, and an index maintained where the id
        // lookup is maintained is one that cannot be half-updated. See [shapes].
        if (dry is DryShape) shapes += dry
        // ⭐ Every object, whatever kind. A key that reached the page without passing through here
        // is a key `topZ` has not seen, and the next stroke then lands below it.
        raiseTop(dry.z)
        orderDirty = true
        // An object arriving is a target appearing: a connector bound to it must re-resolve, which
        // is how a detached end re-attaches with no op at all (SPEC §4.8.4).
        tombstones -= dry.id
        routesDirty = true
    }

    private fun remove(dry: DryObject) {
        (if (dry.drawsOverInk) highlight else ink).remove(dry)
        tombstone(dry)
    }

    /**
     * Drop [dry] from the lookups — **the only place besides [reset] and [clear] that `byId` and
     * [shapes] lose an entry.**
     *
     * ⚠️ Every path that takes an object off the page comes through here or through [tombstone],
     * and it has to: a path that removed an object from its band and forgot this would leave a
     * [DryShape] in [shapes] re-routing forever against a page it is no longer on, and `byId` still
     * answering [objectOf] for something nothing draws.
     *
     * Deliberately says nothing about *why* the object went. [tombstone] is a deletion; this is the
     * render-only drop [pruneFullyErased] does, which records no deletion at all because a fully
     * erased stroke is not deleted — its `SetErasedRegion` already describes it.
     */
    private fun unindex(dry: DryObject) {
        byId.remove(dry.id)
        if (dry is DryShape) shapes -= dry
    }

    /**
     * [unindex], plus the two things a **deletion** owes the rest of the page.
     *
     * ⚠️ The tombstone is kept so `unresolvedState` can tell a *deleted* target from one whose
     * `AddObject` has not arrived (SPEC §4.8.4). The geometry is identical either way; only the
     * label differs, and a store that dropped the id could never say which.
     *
     * ⚠️ And [routesDirty], because an object vanishing is an **anchor target vanishing**: a
     * connector attached to it has to fall back to its own authored segment and start reporting
     * that end as detached, with no op of its own. The eraser's whole-object branch removed an
     * object by iterator and dropped it straight out of `byId` instead of coming through here, so
     * rubbing out a box left the arrow pointing at where the box used to be until something else
     * happened to dirty the routes — which, being a lazy flag, could be the next gesture or never.
     */
    private fun tombstone(dry: DryObject) {
        unindex(dry)
        tombstones += dry.id
        routesDirty = true
    }

    /**
     * Write [region] onto a stroke [pruneFullyErased] took off the page, and put it back if the
     * region no longer covers it.
     *
     * The inverse of the drop in [pruneIfAffordable], and the render half of undoing an erase.
     * A region that still covers the stroke leaves it pruned and reports **no change**, because
     * nothing on screen moved — bumping the revision there would repaint the page for a stroke
     * that is invisible either way.
     *
     * ⚠️ [DryObject.isErasedAway] is the same points × capsules test [PRUNE_BUDGET] exists to
     * ration, and it is unrationed here on purpose: this runs once per op in an undo, not once
     * per eraser sample, and the alternative — restoring optimistically and re-pruning later —
     * leaves a fully erased stroke back in the render list until the next erase gesture ends.
     */
    private fun unprune(dry: DryObject, region: ErasedRegion?): Boolean {
        dry.setErased(region)
        if (region != null && dry.isErasedAway(region)) return false
        prunedByErase -= dry.id
        insert(dry)
        return true
    }

    /**
     * Apply ops to the render state — the load path, the undo/redo path, and a peer's edits.
     *
     * This is the only way anything other than a live gesture changes what is drawn, which is
     * what keeps the folded log and the screen in agreement.
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

            // ⚠️ The pruned branch is not tidiness: an object dropped from [prunedByErase]
            // without a tombstone would leave a connector aimed at it reporting *not yet
            // arrived* rather than *deleted* (SPEC §4.8.4), and the entry would outlive the
            // page. Nothing is drawn either way, so this reports no change.
            is PageOp.DeleteObject -> byId[op.target]?.let {
                remove(it)
                true
            } ?: prunedByErase.remove(op.target)?.let {
                tombstone(it)
                false
            } ?: false

            else -> applyRegister(op, brushes)
        }
        if (record) recorder?.record(op)
        return changed
    }

    /**
     * The absolute LWW registers (SPEC §4.2) — the value on the op is what the object ends up
     * with, never something composed with what is already here (CLAUDE.md rule 8).
     *
     * Split out of [applyOp] because it is the half that grew: `SetErasedRegion` was the only
     * register this layer drew until M8a added three more, and the `when` became long enough that
     * the thing worth reading — *every branch writes a whole value* — stopped being visible in one
     * screen.
     *
     * A register op for an object that is not here is **not an error**: SPEC §4.2 allows one to
     * arrive before the `AddObject` it targets, and the fold keeps it either way. Same for page
     * metadata, which this layer does not draw at all.
     */
    private fun applyRegister(op: PageOp, brushes: InkBrushFactory): Boolean {
        val target = op.target ?: return false
        // ⭐ The one register that can name a stroke which is not on the page: an un-erase.
        // See [prunedByErase] — the lookup below would answer null and the op would be dropped.
        if (op is PageOp.SetErasedRegion) {
            prunedByErase[target]?.let { return unprune(it, op.absolute.toRegion()) }
        }
        val dry = byId[target] ?: return false
        return when (op) {
            // Absolute: the whole region is replaced, never merged.
            is PageOp.SetErasedRegion -> {
                dry.setErased(op.absolute.toRegion())
                true
            }

            is PageOp.SetTransform -> {
                dry.setTransform(op.absolute)
                // A peer's move re-routes every connector attached to it, here, with no op of its
                // own — which is why `NoteEditor.reconcile` needs no connector case at all.
                routesDirty = true
                true
            }

            is PageOp.SetStyle -> {
                dry.restyle(op.style, brushes)
                true
            }

            is PageOp.SetZOrder -> {
                dry.setZ(op.z)
                raiseTop(op.z)
                orderDirty = true
                true
            }

            // ⭐ M8c — the two record registers. Both are absolute strings and neither belongs to a
            // stroke: `SetTextContent` carries a text box's words or a shape's `ShapeRecord`
            // (SPEC §4.8.2), `SetFormat` carries presentation (SPEC §4.9.2). The `when` over the
            // sealed kind is exhaustive on purpose — a kind added without deciding what a record
            // means for it will not compile, which is the alternative to it being dropped in
            // silence.
            is PageOp.SetTextContent -> applyContent(dry, op.text)

            is PageOp.SetFormat -> applyFormat(dry, op.record)

            else -> false
        }
    }

    /**
     * `SetTextContent` — a text box's words, or a shape's [ShapeRecord] (SPEC §4.8.2).
     *
     * Split out of [applyRegister] with [applyFormat] so that the thing worth reading there —
     * *every branch writes a whole absolute value* — stays visible in one screen. The `when` over
     * the sealed kind is exhaustive on purpose: a kind added without deciding what a record means
     * for it will not compile, which is the alternative to it being dropped in silence.
     */
    private fun applyContent(dry: DryObject, text: String): Boolean = when (dry) {
        is DryStroke -> false

        // ⭐ M8b — for a shape this register *is* the fill, the corner radius and the connector's
        // attachment, so a record arriving from a peer, from an undo, or a moment after its own
        // `AddObject` has to reach the screen.
        is DryShape -> {
            dry.setRecord(text)
            routesDirty = true
            true
        }

        // ⭐ M8c — a text box's **words**. An image's content register is free (SPEC §4.9.2's
        // table) and is still applied rather than refused, so a peer writing one cannot lose it
        // here; nothing draws it.
        is DryBox -> {
            dry.setContent(text)
            true
        }
    }

    /** `SetFormat` — SPEC §4.9.2's presentation record. */
    private fun applyFormat(dry: DryObject, record: String): Boolean = when (dry) {
        is DryStroke -> false

        // Nothing: a format record is a text box's font and an image's crop, and a shape has
        // neither — its own record is `SetTextContent` (SPEC §4.8.2). Carried in the log by the
        // fold either way, so a build that gives a shape a format later loses nothing.
        is DryShape -> false

        // ⭐ M8c — the font, size, alignment and list for a text box; the crop for an image.
        is DryBox -> {
            dry.setFormat(record)
            true
        }
    }

    private fun addObject(op: PageOp.AddObject, brushes: InkBrushFactory): Boolean {
        val obj = op.obj
        // A re-add of something already on screen is an undo of a delete that never took effect
        // here — nothing to do, and inserting twice would double-draw it.
        if (obj.id in byId) return false
        insert(dryObjectFor(obj, brushes) ?: return false)
        return true
    }

    /**
     * ⭐ M8c — the render state for one folded object, whatever kind it is.
     *
     * This opened with `as? StrokeObject ?: return false` for six milestones, which was the whole
     * of SPEC §4.9.3's *"shows nothing at all"*: an image or a text box arriving from a peer, from
     * an undo's re-add or from a page reload was correct in the log, correct in the fold, and gone
     * from the screen. Worse than gone — absent from [topZ], so the next stroke the user drew
     * landed underneath it.
     *
     * A stroke is the one kind whose geometry only this module can decode; every other kind is
     * built straight from its payload. The `when` is exhaustive over [InkObject] on purpose, so
     * that a kind added to `:core:model` is a compile error here rather than a silent drop.
     * (Adding one is separately a note older builds cannot open — SPEC §4.9.1's first row — so
     * this is not the only thing standing in the way of one.)
     *
     * ⭐ **M8c: every kind paints itself now.** `DryPlaceholder` — which held its place in the
     * z-order, converged, and drew nothing — has no members left and is gone. Its own KDoc always
     * said this was how it would end: *"a kind that paints itself replaces this type rather than
     * configuring it"*.
     *
     * @return null only when the object has geometry that could not be decoded, which is a stroke
     *   whose blob this build cannot read. Everything else is held.
     */
    private fun dryObjectFor(obj: InkObject, brushes: InkBrushFactory): DryObject? = buildDryObject(obj, brushes)

    /**
     * ⭐ Replace everything on the page with a [PreparedPage] built elsewhere.
     *
     * The main-thread half of an asynchronous page load: [reset] plus one [insert] per object,
     * which is list appends and index maintenance and nothing else. Every expensive part — the
     * geometry decode and the native tessellation — already happened in [PreparedPage.of], on
     * whatever thread the caller chose.
     *
     * The registers come after **all** the adds rather than interleaved with them, which is what
     * `InkPageThumbnailRenderer.toStrokeStore` already does and for the same reason: a record may
     * name an object that sorts after it, and applying it before that object exists drops it.
     */
    fun install(prepared: PreparedPage, brushes: InkBrushFactory) {
        reset()
        for (dry in prepared.objects) insert(dry)
        if (prepared.registers.isNotEmpty()) applyOps(prepared.registers, brushes, record = false)
        revision++
    }

    /** Drop everything on screen without touching the page's log. Used when switching pages. */
    fun reset() {
        ink.clear()
        highlight.clear()
        byId.clear()
        // Another page's erased-away strokes are not this page's to restore.
        prunedByErase.clear()
        // Beside `byId.clear()` for the reason [unindex] gives: the two are one index in two maps,
        // and a page's shapes outliving the page would re-route against the next one's objects.
        shapes.clear()
        removedThisGesture.clear()
        erasedThisGesture.clear()
        inGesture = false
        orderDirty = false
        // Another page's tombstones would make a connector on *this* page report a target as
        // deleted when it had merely never been here — the wrong half of SPEC §4.8.4's pair.
        tombstones.clear()
        routesDirty = false
        // A different page's keys say nothing about this one's. Leaving it would put the first
        // stroke on the new page above or below everything for no reason the user could see.
        topZ = null
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
        // The same bookkeeping [remove] does per object, in bulk: `ArrayList.remove` is a linear
        // scan plus an arraycopy, so removing every object one at a time made clearing a page
        // O(N²) — ~12.5M element shifts for 5,000 strokes, in one frame, on the UI thread.
        for (dry in byId.values) {
            removedThisGesture += dry.id
            tombstones += dry.id
        }
        ink.clear()
        highlight.clear()
        byId.clear()
        prunedByErase.clear()
        shapes.clear()
        routesDirty = true
        endEdit()
        revision++
    }

    // --- Selection (M8a) ----------------------------------------------------

    /**
     * ⚠️ **A live drag's preview, which emits nothing.**
     *
     * A move or a resize has to be visible while the finger is down, and it must produce
     * exactly one op per object when the finger lifts — not one per frame. So the drag writes
     * here and the *commit* goes through `SelectionEdit.transform` and the recorder, composed
     * against the transforms the objects had when the gesture started.
     *
     * That split is the reason this method exists at all, and the reason it is named for what
     * it is: anything that changes the page without telling the recorder is one forgotten call
     * away from an edit that never reaches disk, so it should be impossible to reach by
     * accident.
     */
    fun previewTransform(id: ObjectId, absolute: Matrix3) {
        val dry = byId[id] ?: return
        dry.setTransform(absolute)
        // ⭐ Dragging a shape re-routes every connector attached to it, live, while the finger is
        // down — and writes nothing (SPEC §4.8.3). This one line is what makes that visible; the
        // op count is unchanged because a preview emits no ops at all.
        routesDirty = true
        revision++
    }

    /**
     * ⚠️ **The curve's half of [previewTransform], and it is a different register.**
     *
     * A vertex drag previews through a transform; the middle handle of a line previews through
     * this, because a bend is not expressible as a placement (see `ShapePoints`). Everything that
     * makes [previewTransform] safe applies unchanged: it emits nothing, the commit goes through
     * [applyOps] with the recorder, and passing null puts the shape back to whatever its record
     * says — which is what a cancelled pointer needs.
     *
     * Silently ignores an id that is not a shape. A bend on a stroke is not an error a caller can
     * do anything about; it is a selection that changed under a gesture, which `onStoreChanged`
     * already handles one layer up.
     */
    fun previewBend(id: ObjectId, local: PagePoint) {
        val shape = byId[id] as? DryShape ?: return
        if (shape.previewBend(local)) revision++
    }

    /** [previewBend] for a closed shape's vertices. */
    fun previewPoints(id: ObjectId, local: List<PagePoint>) {
        val shape = byId[id] as? DryShape ?: return
        if (shape.previewPoints(local)) revision++
    }

    /**
     * End a shape's live preview — the drag committed, or the pointer was cancelled.
     *
     * ⚠️ **Its own method, not `preview*(null)`.** Null already means *"straight"* to a bend and
     * *"unedited"* to a vertex list, and overloading it with *"stop previewing"* is what made a
     * flattened curve spring back to its stored arc mid-drag (see [DryShape.previewBend]).
     */
    fun endShapePreview(id: ObjectId) {
        val shape = byId[id] as? DryShape ?: return
        if (shape.endPreview()) revision++
    }

    // --- Shapes and connectors (M8b) ----------------------------------------

    /**
     * A shape the user just made — draw-and-hold's render half.
     *
     * ⚠️ **Takes the ops rather than building them**, unlike [add]. A stroke's `AddObject` carries
     * a geometry blob that only `androidx.ink`'s encoder can produce, so [add] mints the id here
     * and defers the encoding to a worker. A shape's payload is a kind and two extents, so
     * `ShapeEdit.create` builds the whole gesture in `:core:model` where a JVM test can reach it —
     * and this method exists to put the *result* on screen without a second construction path that
     * could disagree with it.
     */
    fun addShape(shape: ShapeObject, record: ShapeRecord) {
        insert(DryShape(shape, record))
        revision++
    }

    /**
     * Re-derive every connector's path from the page as it stands.
     *
     * ⭐ **This writes nothing** (SPEC §4.8.3). It is called from [forEachInRenderOrder] when
     * something has moved, so a re-route costs at most one pass per drawn frame; a connector whose
     * geometry did not actually change is skipped, which is most of them on most frames.
     *
     * ⚠️ **Over [shapes], never over [ink].** It walked the whole ink band for as long as
     * connectors have existed, which made *"at most one pass per drawn frame"* a 5,000-element
     * scan per frame on a page of handwriting that could not contain a connector at all. Nothing
     * about it was visibly wrong; it is a comment that costs frames. See [shapes].
     */
    private fun rerouteConnectors() {
        routesDirty = false
        var changed = false
        for (shape in shapes) {
            if (shape.isConnector && shape.reroute(targets)) changed = true
        }
        if (changed) revision++
    }

    /**
     * How [ConnectorRouting] sees this page.
     *
     * ⚠️ Answers from the **render state**, not from a fold — the surface does not hold one (see
     * `ConnectorRouting.route`). `anchorable` reads the live [DryObject.transform] rather than the
     * payload's, which is the whole point: the payload is first-write-wins and the live one is what
     * a drag and a peer's `SetTransform` have moved.
     */
    private val targets = object : ConnectorTargets {
        override fun anchorable(id: ObjectId): AnchorTarget? {
            val shape = byId[id] as? DryShape ?: return null
            return shape.obj.asAnchorTarget().copy(transform = shape.transform)
        }

        override fun unresolvedState(id: ObjectId): ConnectorEndState = when {
            id in tombstones -> ConnectorEndState.DETACHED_TOMBSTONED
            byId.containsKey(id) -> ConnectorEndState.DETACHED_UNANCHORABLE
            else -> ConnectorEndState.DETACHED_ABSENT
        }
    }

    /**
     * Insert a copy of [source] under a new id — the duplicate gesture's render half.
     *
     * The `androidx.ink` `Stroke` is **shared, not copied**, which is safe because it is
     * immutable: its inputs and its mesh are fixed once the stroke is finished. The op that
     * carries this copy to disk encodes that same immutable stroke on a worker
     * (`PageOpRecorder.strokeDuplicated`), so nothing here touches the geometry blob and
     * nothing runs the encoder on the UI thread (CLAUDE.md rule 7).
     */
    fun duplicate(source: ObjectId, into: ObjectId, transform: Matrix3, z: FractionalKey): DryStroke? {
        // Strokes only, and since the M8a/M8b seam fix that is a rule with one door per kind
        // rather than a gap: a shape's copy travels as ops (`ShapeEdit.duplicate` applied
        // through `applyOps`), because its payload needs no encoder; a stroke's cannot, because
        // its blob does — so `SelectionController.duplicate` routes each kind accordingly.
        // ⭐ M8c: a text box and an image travel as ops too, and an image's copy **names the same
        // asset** rather than copying bytes. That is SPEC §4.9.4's own rule — an asset name is a
        // UUID, its lifetime is refcount-free, and *"a duplicated note and its original can name
        // one entry"* — so within one note there is nothing to copy. Copying bytes is
        // `:core:storage`'s job and only when a copy leaves this note.
        val original = strokeOf(source) ?: return null
        val copy = DryStroke(
            id = into,
            stroke = original.stroke,
            kind = original.kind,
            inputModel = original.inputModel,
            transform = transform,
            style = original.style,
            z = z,
        )
        // The bite travels with the copy: it is in the object's own space and the copy has the
        // same geometry, so a duplicate of a half-erased stroke is half-erased. Anything else
        // would make "duplicate" a way to recover erased ink, which is a surprise either way.
        original.erased?.let { region ->
            val restored = ErasedRegion()
            for (capsule in region.capsules) restored.add(capsule)
            copy.setErased(restored)
        }
        insert(copy)
        revision++
        return copy
    }

    /**
     * For the commit path: the decoded stroke behind an id, so its geometry can be encoded.
     *
     * Null for an id this page does not have **and** for one that is not a stroke. The two are the
     * same answer to every caller here — all of them want ink — and a caller that wants the object
     * whatever it is asks [objectOf].
     */
    fun strokeOf(id: ObjectId): DryStroke? = byId[id] as? DryStroke

    /** The render state behind an id, whatever kind it is. */
    fun objectOf(id: ObjectId): DryObject? = byId[id]

    /**
     * The shape behind an id, or null for any other kind — what the record editors need (M8b).
     *
     * A sibling of [strokeOf] and, like it, a deliberate narrowing rather than a convenience: fill,
     * corner radius and a connector's route are properties of a [ShapeRecord], and a caller holding
     * a [DryStroke] has nothing to write one to.
     */
    fun shapeOf(id: ObjectId): DryShape? = byId[id] as? DryShape

    // --- Rendering ----------------------------------------------------------

    /**
     * Visit every object in render order — ink first, highlighters last, so a highlight
     * multiplies over the writing it was drawn across, and `(z, id)` within each band.
     *
     * [visibleBounds] culls in page space. It does nothing when the whole page is on screen; it
     * earns its keep when zoomed in, which is where the frame budget is tightest because each
     * visible stroke covers more pixels.
     */
    fun forEachInRenderOrder(visibleBounds: Box?, action: (DryObject) -> Unit) {
        // ⭐ Before the cull, because a re-route changes `pageBounds` — routing after culling would
        // drop a connector whose old box was off screen and whose new one is not, which reads as a
        // connector that disappears when you drag the shape it is attached to.
        if (routesDirty) rerouteConnectors()
        if (orderDirty) {
            // `(z, id)`, and not z alone: two devices can pick the same key, and a tie broken
            // by insertion order is a tie broken differently on each device — the same
            // comparator `PageFolder.build` uses, for the same reason.
            ink.sortWith(RENDER_ORDER)
            highlight.sortWith(RENDER_ORDER)
            orderDirty = false
        }
        emit(ink, visibleBounds, action)
        emit(highlight, visibleBounds, action)
    }

    /**
     * The same walk, narrowed to ink.
     *
     * For the two consumers that need a `Stroke` and not merely an object in order — the PDF
     * outliner and the recogniser's sampler. Both would otherwise have to filter, and a filter
     * written at the call site is a filter the next kind's author has to find.
     */
    fun forEachStrokeInRenderOrder(visibleBounds: Box?, action: (DryStroke) -> Unit) {
        forEachInRenderOrder(visibleBounds) { dry -> if (dry is DryStroke) action(dry) }
    }

    private fun emit(objects: List<DryObject>, visibleBounds: Box?, action: (DryObject) -> Unit) {
        for (index in objects.indices) {
            val dry = objects[index]
            if (visibleBounds == null || overlaps(dry.pageBounds, visibleBounds)) action(dry)
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
     * ⭐ **The sweep arrives in page space and is recorded in each stroke's own space** (M8a).
     * For an untransformed stroke — which is every stroke until a selection tool moves one —
     * those are the same numbers and the shared page-space query is reused. For a moved one
     * the segment is mapped through the inverse; see [DryStroke.erased] for why the region
     * cannot simply stay in page space.
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
        val sweep = EraserSweep(
            from,
            to,
            radiusPageUnits,
            sweptTip,
            ErasedRegion.Capsule(
                from.x,
                from.y,
                to.x,
                to.y,
                radiusPageUnits,
            ),
        )

        val touched = applyErase(ink, coarse, sweep, mode) + applyErase(highlight, coarse, sweep, mode)
        if (touched > 0) revision++
        return touched
    }

    /**
     * One eraser sample, in page space, with the query objects an identity transform can reuse.
     *
     * ⚠️ **[pageCapsule] has to be a fresh object per sample and cannot be pooled the way
     * [LocalSweep] is**, however tempting it looks beside a rule-7 allocation count. It is the one
     * thing built here that is *retained*: [DryObject.eraseCapsule] stores it in the object's
     * [ErasedRegion], which is the durable form that becomes `SetErasedRegion`'s payload. A shared,
     * mutated instance would rewrite every capsule already recorded — a whole gesture's erasing
     * collapsing onto its last sample, silently, in the log and on every peer. The same holds for
     * the local capsule [sweptCapsuleFor] builds for a transformed object. What can be pooled is
     * the pair of endpoints, and it is.
     */
    private class EraserSweep(
        val from: PagePointF,
        val to: PagePointF,
        val radius: Float,
        val pageTip: Parallelogram,
        val pageCapsule: ErasedRegion.Capsule,
    )

    /** Reusable endpoints for mapping a sweep into one object's space — no allocation per sample. */
    private class LocalSweep(val from: PagePointF = PagePointF(), val to: PagePointF = PagePointF())

    /**
     * One instance, on the store — this class is single-UI-thread. It used to be constructed
     * inside [applyErase], which runs twice per eraser sample, so the "no allocation per
     * sample" scratch was itself six allocations per sample.
     */
    private val localSweepScratch = LocalSweep()

    /**
     * The capsule this sweep takes out of [dry], **in [dry]'s own space**, or null when it takes
     * none.
     *
     * ⭐ Where M8a's coordinate change actually happens. For an untransformed stroke — which is
     * every stroke until a selection tool moves one — the page-space query objects are reused
     * unchanged, so the common path allocates nothing and is exactly what M1 shipped. For a moved
     * one the segment is mapped through the inverse and the tip radius divided by the transform's
     * linear scale (see [Matrix3.linearScale] on the approximation that costs).
     *
     * ⭐ M8c: every kind answers [DryObject.hitBy] for itself, so a picture and a text box are
     * erasable exactly as a stroke is — the rule was *"nothing the user cannot see may be erased"*
     * and there is now nothing the user cannot see. It stays in one place, the object's own
     * coverage test, rather than as a kind check in this loop.
     */
    private fun sweptCapsuleFor(
        dry: DryObject,
        coarse: ImmutableBox,
        sweep: EraserSweep,
        scratch: LocalSweep,
    ): ErasedRegion.Capsule? {
        // Cheap float compares first, in page space. Only strokes whose bounds the eraser actually
        // swept get a native coverage test, which keeps erasing usable with 5,000 strokes.
        if (!overlaps(dry.pageBounds, coarse)) return null

        if (dry.transform == Matrix3.Identity) {
            return if (dry.hitBy(sweep.pageTip, sweep.pageCapsule)) sweep.pageCapsule else null
        }

        // Both ends or neither: a half-mapped segment is not a shorter erase, it is a wrong one.
        val mapped = dry.toLocal(sweep.from.x, sweep.from.y, scratch.from) &&
            dry.toLocal(sweep.to.x, sweep.to.y, scratch.to)
        if (!mapped) return null
        val radius = dry.toLocalLength(sweep.radius)
        val tip = ImmutableParallelogram.fromSegmentAndPadding(
            ImmutableSegment(
                ImmutableVec(scratch.from.x, scratch.from.y),
                ImmutableVec(scratch.to.x, scratch.to.y),
            ),
            radius,
        )
        val local = ErasedRegion.Capsule(scratch.from.x, scratch.from.y, scratch.to.x, scratch.to.y, radius)
        return if (dry.hitBy(tip, local)) local else null
    }

    private fun applyErase(
        objects: MutableList<DryObject>,
        coarse: ImmutableBox,
        sweep: EraserSweep,
        mode: EraseMode,
    ): Int {
        var touched = 0
        val scratch = localSweepScratch
        val iterator = objects.iterator()
        while (iterator.hasNext()) {
            val dry = iterator.next()
            // One rejection, one `continue`. The three reasons a stroke is not hit — its bounds
            // are elsewhere, its transform cannot be inverted, the tip does not cover it — are
            // the same answer to the caller, and collapsing them keeps this loop readable.
            val capsule = sweptCapsuleFor(dry, coarse, sweep, scratch) ?: continue

            touched++
            if (mode == EraseMode.WHOLE_STROKE) {
                removedThisGesture += dry.id
                iterator.remove()
                // ⚠️ [tombstone], not a bare `byId.remove`. This *is* a delete — [endEdit] emits a
                // `DeleteObject` for everything in `removedThisGesture` — so it owes the page a
                // tombstone and a re-route exactly as `DeleteObject` does. See [tombstone].
                tombstone(dry)
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
        var budget = PRUNE_BUDGET
        // ⛔ **At least one candidate per pass runs whatever it costs, and without this the
        // degradation was not "not pruning yet" but "never pruning".**
        //
        // [pruneIfAffordable] skips a stroke whose estimate exceeds what is left. The estimate is
        // `inputs × 3 × capsules`, the region only ever grows (it is append-only, and
        // `SetErasedRegion` is absolute), and `inputs` is fixed — so a stroke that once exceeded
        // [PRUNE_BUDGET] exceeds it on **every future pass, forever**. The threshold is
        // `inputs × 3 × capsules > 200_000`: a 200-sample stroke needs only ~333 capsules, and one
        // second of scribbled erasing at 240 Hz is ~240. In other words essentially every stroke a
        // user really scribbles over becomes permanently unprunable.
        //
        // What that costs is not the prune — it is the *drawing*. A fully erased stroke is
        // invisible and still costs `PagePainter` a `saveLayer` + `DST_OUT` sandwich in every
        // record of the page, and `DryStrokesView` re-records the whole page 120 ms after any
        // scale change. So the bill is paid on every pinch, in proportion to how much the user has
        // erased — which is exactly the report: *"when there are a lot of erases when you zoom in
        // and out it seem to lag"*.
        //
        // Letting the first candidate through guarantees forward progress: each pass retires at
        // least one over-budget stroke (or settles that it is not erased away), and a prune runs
        // per erase gesture, so a page cleans itself up over the next few gestures instead of
        // never. The work is still bounded — one stroke's coverage test, now with
        // [ErasedRegion.coveringCapsule]'s rolling hint under it — which is what the budget was
        // really protecting against.
        var forceAllowed = true
        for (list in listOf(ink, highlight)) {
            val iterator = list.iterator()
            while (iterator.hasNext() && budget > 0) {
                val dry = iterator.next()
                val cost = coverageCostToSpend(dry, budget, forceAllowed)
                if (cost <= 0L) continue
                // Over budget and taken anyway: that was this pass's one forced candidate.
                if (cost > budget) forceAllowed = false
                budget -= pruneCandidate(iterator, dry, cost)
            }
        }
    }

    /**
     * What examining [dry] would cost, or `0` for *"skip it"*.
     *
     * Zero covers three different noes, and none of them is worth telling apart here: the object
     * has no erased region at all; [DryObject.eraseCoverageCost]'s own cheap gates rejected it in
     * four compares (empty region, no bounds, or the stroke reaches outside the region's box, all
     * of which mean it cannot be fully erased); or it is over budget and this pass has already
     * spent its one forced candidate.
     */
    private fun coverageCostToSpend(dry: DryObject, budget: Long, forceAllowed: Boolean): Long {
        val region = dry.erased ?: return 0L
        val cost = dry.eraseCoverageCost(region)
        if (cost == 0L) return 0L
        return if (cost > budget && !forceAllowed) 0L else cost
    }

    /**
     * Coverage-test [dry], drop it when it is fully erased, and return the [cost] that was spent.
     *
     * ⚠️ **The affordability decision is the caller's**, and it moved there when "too expensive"
     * turned out to mean "forever" — see [pruneFullyErased]. This runs the test unconditionally;
     * it does not get to refuse.
     *
     * [cost] is charged in the unit the work actually happens in — point × capsule distance tests,
     * estimated by the object itself ([DryObject.eraseCoverageCost]). The budget used to charge
     * `capsules.size` while `isErasedAway` ran points × capsules, an under-count of the whole
     * point factor: one pen-up after scribbling over a dense, heavily-erased area ran ~100× the
     * bound and froze the UI at exactly the moment the budget exists to protect.
     *
     * ⭐ M8c: the kind check is [DryObject.isErasedAway]'s default rather than a cast here. Only
     * a stroke answers true; the others refuse for the reason [DryTextBox] gives, which is that
     * dropping one costs the user their object until the page reloads.
     */
    private fun pruneCandidate(iterator: MutableIterator<DryObject>, dry: DryObject, cost: Long): Long {
        val region = dry.erased ?: return 0L
        if (dry.isErasedAway(region)) {
            iterator.remove()
            // [unindex], not [tombstone]: this emits no ops and deletes nothing (see the KDoc
            // above), so claiming a tombstone would make a connector aimed at this object report
            // its target as *deleted* when the log still holds it whole.
            unindex(dry)
            // ⭐ And kept, because the log still holds it whole — so an undo of this erase has
            // something to put back. See [prunedByErase].
            prunedByErase[dry.id] = dry
            revision++
        }
        return cost
    }

    private companion object {
        /**
         * Point-against-capsule distance tests allowed per prune pass — the unit
         * [DryObject.eraseCoverageCost] estimates in. Sized so the worst case stays comfortably
         * inside one frame rather than by measurement — the number to trust is whatever the
         * device says, not this one.
         */
        const val PRUNE_BUDGET = 200_000L

        /**
         * How many appended objects [appendsSince] will answer for before demanding a re-record.
         *
         * Each one is a draw call the dry layer issues *in addition* to replaying the cached
         * recording, so the tail is work repeated every frame while a full re-record is work done
         * once. 48 keeps the per-frame tail far below the cost of the re-record it defers on any
         * page big enough for this to matter, and bounds the deque.
         */
        const val MAX_APPENDS = 48

        /**
         * Ids for a store with no page bound — the synthetic-load harness and previews. They
         * never reach disk, and they are visibly not UUIDs so that one appearing in a file
         * would be obvious rather than plausible.
         */
        const val UNBOUND_ID_PREFIX = "unbound-"

        val RENDER_ORDER = compareBy<DryObject>({ it.z.value }, { it.id.value })
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
 *
 * ⭐ **M8a inherits this rather than replacing it.** ROADMAP M8a's prompt says partial erase
 * *"splits a stroke"* into `DeleteObject + AddObject(fragment) + AddObject(fragment)`. SPEC
 * §3.5 supersedes that in as many words — *"M8a's 'partial erase splits a stroke into
 * fragments' is superseded. Nothing splits."* — and the reason is worth keeping in front of
 * whoever reads the prompt next: splitting is strictly *worse* on the two counts M8a cares
 * about. It destroys the original stroke where the area model keeps it (CLAUDE.md rule 1), and
 * it makes the fragments new objects, so a peer's concurrent `SetTransform` on the original
 * lands on a tombstone and the two devices end up with different object counts — which is
 * SPEC §13.2 row 7, failing.
 */
public enum class EraseMode {
    /** Erase exactly the area the eraser tip passed over, keeping the rest of the stroke. */
    PARTIAL,

    /** Delete any stroke the eraser touches, whole. */
    WHOLE_STROKE,
}

/**
 * A stroke's bounding box, as four plain floats. In the stroke's own space, or in page space —
 * the two are different fields on [DryStroke] and this type is deliberately silent about which
 * it holds, because it is the same four numbers either way.
 *
 * `androidx.ink` has a perfectly good [ImmutableBox], but the only way to get one from a [Box]
 * is `Box.toImmutable()`, which is `@RestrictTo(LIBRARY_GROUP)` in 1.0.0 — see the note on
 * [InkBrushKind]. Copying four floats out is both allowed and cheaper: this is read once per
 * stroke per erase sample, and a float compare beats a native call by a wide margin.
 */
internal class PageBounds(val xMin: Float, val yMin: Float, val xMax: Float, val yMax: Float) {

    /**
     * This box under [transform], as an axis-aligned box in the target space.
     *
     * All four corners, not two: a rotation maps the top-left corner to somewhere that is
     * neither the top nor the left, and mapping only the diagonal gives a box that is wrong
     * for every rotation that is not a multiple of 90°.
     */
    fun under(transform: Matrix3): PageBounds {
        if (transform == Matrix3.Identity) return this
        val corners = listOf(
            transform.map(PagePoint(xMin, yMin)),
            transform.map(PagePoint(xMax, yMin)),
            transform.map(PagePoint(xMax, yMax)),
            transform.map(PagePoint(xMin, yMax)),
        )
        return PageBounds(
            xMin = corners.minOf { it.x },
            yMin = corners.minOf { it.y },
            xMax = corners.maxOf { it.x },
            yMax = corners.maxOf { it.y },
        )
    }

    fun containsWithin(x: Float, y: Float, slop: Float): Boolean =
        x >= xMin - slop && x <= xMax + slop && y >= yMin - slop && y <= yMax + slop

    fun asRect(): PageRect = PageRect(xMin, yMin, xMax, yMax)

    companion object {
        fun of(box: Box?): PageBounds? = box?.let { PageBounds(it.xMin, it.yMin, it.xMax, it.yMax) }
    }
}

/**
 * The transform's average linear scale — how much longer a length gets under it.
 *
 * `sqrt(|det|)` is the geometric mean of the two axis scales: exact for a uniform scale and for
 * any rotation, and an approximation for a non-uniform one. That approximation has exactly one
 * consumer — the eraser tip's radius, mapped into a squashed object's space, where the tip
 * becomes an ellipse and a single radius cannot describe it. The alternative is an elliptical
 * eraser query, which `androidx.ink`'s coverage API cannot take; the error is bounded by the
 * aspect ratio the user squashed the object to, and a squashed object erased with a slightly
 * round tip is not a correctness problem — nothing is destroyed either way (SPEC §3.5).
 */
internal fun Matrix3.linearScale(): Float {
    val scale = sqrt(abs(determinant))
    return if (scale > 0f) scale else 1f
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
