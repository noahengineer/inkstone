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
import dev.inkstone.core.model.AnchorTarget
import dev.inkstone.core.model.ConnectorEndState
import dev.inkstone.core.model.ConnectorTargets
import dev.inkstone.core.model.FractionalKey
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
        val brush = brushes.brushFor(
            kind = kind,
            colorArgb = style.colorArgb.withOpacity(style.opacity),
            sizePt = if (style.widthPt > 0f) style.widthPt else stroke.brush.size,
            epsilonPt = stroke.brush.epsilon,
            inputModel = inputModel,
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

    /** Set when a z key changed or an object was inserted out of order. See [ink]. */
    private var orderDirty = false

    /**
     * ⭐ M8b — set whenever anything a connector's route could depend on changed.
     *
     * Re-routing is lazy and happens at most once per frame, in [forEachInRenderOrder], because a
     * drag emits a `previewTransform` per frame and re-routing eagerly on each would do the work
     * twice for the frames that never get drawn. It is O(connectors), not O(objects): a page has a
     * handful of connectors and thousands of strokes.
     *
     * ⚠️ **This is not a cache of a persisted value.** Nothing about a route is ever written down
     * (SPEC §4.8.3); this flag only decides *when* to recompute something that is always derived.
     */
    private var routesDirty = false

    /**
     * Ids this store has seen deleted, so a detached connector can say *which* kind of detached.
     *
     * Grows with the page's deletions and is cleared by [reset], which is the same lifetime as
     * everything else here. See [remove].
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
        insert(DryStroke(id, stroke, kind, inputModel, z = z))
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

    private fun insert(dry: DryObject) {
        (if (dry.drawsOverInk) highlight else ink).add(dry)
        byId[dry.id] = dry
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
        byId.remove(dry.id)
        // ⚠️ Kept, so `unresolvedState` can tell a *deleted* target from one whose `AddObject` has
        // not arrived (SPEC §4.8.4). The geometry is identical either way; only the label differs,
        // and a store that dropped the id could never say which.
        tombstones += dry.id
        routesDirty = true
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

            is PageOp.DeleteObject -> byId[op.target]?.let {
                remove(it)
                true
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

        is DryPlaceholder -> {
            dry.setContent(text)
            true
        }
    }

    /** `SetFormat` — SPEC §4.9.2's presentation record. */
    private fun applyFormat(dry: DryObject, record: String): Boolean = when (dry) {
        is DryStroke -> false

        // Nothing yet: a format record is a text box's font and an image's crop, and a shape has
        // neither. Carried in the log by the fold either way.
        is DryShape -> false

        is DryPlaceholder -> {
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
     * A stroke is the one kind whose geometry only this module can decode; every other kind goes
     * to [DryPlaceholder.of], whose `when` is exhaustive over [InkObject] so that a kind added to
     * `:core:model` is a compile error rather than a silent drop. (Adding one is separately a note
     * older builds cannot open — SPEC §4.9.1's first row — so this is not the only thing standing
     * in the way of one.)
     *
     * @return null only when the object has geometry that could not be decoded, which is a stroke
     *   whose blob this build cannot read. Everything else is held.
     */
    private fun dryObjectFor(obj: InkObject, brushes: InkBrushFactory): DryObject? = when (obj) {
        is StrokeObject -> dryStrokeFor(obj, brushes)

        // ⭐ M8b — a shape paints itself now. It arrives with no record: the `SetTextContent` that
        // carries one is a separate op and may sort either side of this `AddObject` (SPEC §4.2),
        // so the record is applied by `applyRegister` whenever it turns up and the shape is drawn
        // plain until it does. A connector therefore appears as its own authored segment first and
        // routes on the next op — which is exactly what it does for a detached end anyway.
        is ShapeObject -> DryShape(obj).also { routesDirty = true }

        else -> DryPlaceholder.of(obj)
    }

    private fun dryStrokeFor(obj: StrokeObject, brushes: InkBrushFactory): DryStroke? {
        val decoded = StrokeGeometryCodec.decode(obj.geometry, brushes) ?: return null
        val dry = DryStroke(
            id = obj.id,
            stroke = decoded.stroke,
            kind = decoded.kind,
            inputModel = decoded.inputModel,
            transform = obj.transform,
            z = obj.z,
        )
        // After the constructor, so the rebuilt mesh and its bounds are the ones cached.
        obj.style?.let { dry.restyle(it, brushes) }
        dry.setErased(obj.erased.toRegion())
        return dry
    }

    /** Drop everything on screen without touching the page's log. Used when switching pages. */
    fun reset() {
        ink.clear()
        highlight.clear()
        byId.clear()
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
        for (dry in byId.values.toList()) {
            removedThisGesture += dry.id
            remove(dry)
        }
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
     */
    private fun rerouteConnectors() {
        routesDirty = false
        var changed = false
        for (dry in ink) {
            if (dry is DryShape && dry.isConnector && dry.reroute(targets)) changed = true
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
        // its blob does — so `SelectionController.duplicate` routes each kind accordingly. What
        // is still a gap is an image's asset bytes (SPEC §4.9.4's third consequence), and
        // selection cannot reach a [DryPlaceholder] in this build, so nothing can call this
        // for one.
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

    /** One eraser sample, in page space, with the query objects an identity transform can reuse. */
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
     * The capsule this sweep takes out of [dry], **in [dry]'s own space**, or null when it takes
     * none.
     *
     * ⭐ Where M8a's coordinate change actually happens. For an untransformed stroke — which is
     * every stroke until a selection tool moves one — the page-space query objects are reused
     * unchanged, so the common path allocates nothing and is exactly what M1 shipped. For a moved
     * one the segment is mapped through the inverse and the tip radius divided by the transform's
     * linear scale (see [Matrix3.linearScale] on the approximation that costs).
     *
     * ⭐ M8c: a [DryPlaceholder] answers false to [DryObject.hitBy] and so never yields a capsule.
     * That is the *"nothing the user cannot see may be erased"* rule, and it lives in one place —
     * the object's own coverage test — rather than as a kind check in this loop.
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
        val scratch = LocalSweep()
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
                // ⭐ M8c: the kind check is [DryObject.isErasedAway]'s default rather than a cast
                // here — one rejection, one `continue`, and the same shape [sweptCapsuleFor] uses.
                // A [DryPlaceholder] answers false, so an object this build cannot draw is never
                // pruned on the strength of a coverage test it cannot run.
                if (dry.isErasedAway(region)) {
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

/**
 * An ARGB colour at [opacity] of its own alpha — how [ObjectStyle.opacity] reaches a brush.
 *
 * Multiplied into the existing alpha rather than replacing it, so restyling a highlighter to
 * 50% gives half of *its* translucency and not half of opaque. `androidx.ink`'s brush has no
 * separate opacity, so this is the only place it can go.
 */
private fun Int.withOpacity(opacity: Float): Int {
    if (opacity >= 1f) return this
    val alpha = ((this ushr ALPHA_SHIFT and BYTE_MAX) * opacity).toInt().coerceIn(0, BYTE_MAX)
    return (alpha shl ALPHA_SHIFT) or (this and RGB_MASK)
}

private const val ALPHA_SHIFT = 24
private const val BYTE_MAX = 0xFF
private const val RGB_MASK = 0x00FFFFFF
