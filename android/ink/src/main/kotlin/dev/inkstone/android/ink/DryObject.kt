package dev.inkstone.android.ink

import androidx.ink.geometry.ImmutableParallelogram
import androidx.ink.geometry.ImmutableSegment
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.MutableVec
import androidx.ink.geometry.Parallelogram
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.SelectedObject

/**
 * ⭐ M8c — **anything [StrokeStore] holds**, whatever it is made of.
 *
 * ## Why this exists, and why it is not a second store
 *
 * Until M8c the dry layer was strokes and nothing else: `StrokeStore.addObject` opened with
 * `as? StrokeObject ?: return false` and [InkSurfaceView] loaded a page through
 * `filterIsInstance<StrokeObject>()`. So an image or a text box arriving from a peer, from an
 * undo's re-add, or from a page reload was **dropped from the screen while being perfectly
 * correct in the log** — SPEC §4.9.3's *"shows nothing at all"*, which was an accepted degrade
 * only for as long as no build could draw one.
 *
 * The z-order consequence is the one that is not merely invisible. [StrokeStore] keeps the page's
 * **top z key** (see `StrokeStore.topZ`), and it can only do that because it sees every writer of
 * one. Give the non-stroke kinds a store or a counter of their own and the key goes stale the
 * moment either side allocates: insert an image, draw a stroke, and **the stroke lands underneath
 * it** — on screen, in the published PDF's `/Annots` order and on every peer, with nothing
 * connecting the two actions. That is M8a's recorded regression (`docs/M8-SELECTION.md` §6, first
 * bullet) arriving from a new direction, and one list is also what lets `(z, id)` interleave a
 * shape and a stroke at all.
 *
 * ## Why a sealed *class* and not an interface
 *
 * `docs/M8-CONNECTORS.md` §6 asks for an interface. It is a class because of the invariant in the
 * next paragraph: [bounds] and [pageBounds] must be written together, and an interface can only
 * *document* that while a base class **owns** it. [setBounds] is the single writer of the pair, so
 * a new kind cannot update one and leave the other stale — which is the failure that is invisible
 * until something is moved. Sealed for the other half: the painter, and every `when` over a kind,
 * fails to compile when a kind is added without a decision (SPEC §4.9.3 is what happens when one
 * is added without one).
 *
 * ## ⚠️ Two boxes, deliberately
 *
 * [bounds] is in the object's **own** space and [pageBounds] is in **page** space. They are the
 * same four numbers for anything the user has not moved, which is why asking the wrong one is
 * invisible until they can differ:
 *
 * | Question | Box |
 * |---|---|
 * | the erased region, the eraser's coverage test, `isFullyErased` | [bounds] |
 * | viewport culling, the lasso, the tap test | [pageBounds] |
 *
 * Not thread-safe: everything here runs on the UI thread beside ink capture.
 *
 * @param transform the object's **absolute** placement (SPEC §4.2, CLAUDE.md rule 8). Identity for
 *   ink, which is authored directly in page space (SPEC §4.4 rule 1).
 * @param style null means "as captured" — see [ObjectStyle]. Only a stroke has a capture-time
 *   style to fall back on; SPEC §4.9.2 requires text boxes and images to be created with one.
 */
internal sealed class DryObject(transform: Matrix3, style: ObjectStyle?, z: FractionalKey) {

    abstract val id: ObjectId

    /**
     * Which of [StrokeStore]'s two bands this belongs in — SPEC §3.4.
     *
     * A band is not a z-order: highlighters draw over ink so that a highlight tints the writing it
     * covers, and `(z, id)` orders *within* a band. Only a highlighter stroke answers true, which
     * is why every non-stroke kind sits in the ink band: a highlighter drawn across a photo must
     * tint the photo, exactly as it tints writing.
     */
    abstract val drawsOverInk: Boolean

    var transform: Matrix3 = transform
        private set

    /** Null means "as captured" — see [ObjectStyle]. */
    var style: ObjectStyle? = style
        private set

    /** Render order within this object's band, resolved by `(z, id)`. */
    var z: FractionalKey = z
        private set

    /**
     * The bounding box in the object's **own** space, or null when it has no extent this build can
     * compute — which then simply cannot be culled.
     *
     * Cached rather than recomputed per use: a hit-test per stroke per erase sample has to be a
     * float compare, not a native call.
     */
    var bounds: PageBounds? = null
        private set

    /** [bounds] under [transform]. See the class KDoc for why both are kept. */
    var pageBounds: PageBounds? = null
        private set

    /**
     * Page space → this object's space, and the linear scale that goes with it.
     *
     * The eraser and the tap test arrive in page space while [ErasedRegion]'s capsules are in
     * object space, so every sample crosses this. Cached because a scribble-erase crosses it a few
     * hundred times a second, and null only for a transform that has collapsed — `SelectionEdit`
     * refuses to write one, but a peer running a future build might, and a null here means "this
     * object cannot be erased or tapped" rather than a crash.
     */
    private var inverse: Matrix3? = transform.inverse()
    private var inverseScale: Float = transform.linearScale()

    /**
     * What an eraser has taken out of this object, in its **own** space. Null until something is
     * erased, so an untouched object costs nothing.
     *
     * ⭐ Object space, and M8a is what forced the distinction: an erasure recorded in page space
     * stays behind when the object is dragged away — a hole in the paper rather than a bite out of
     * the line. Stored in the object's coordinates, it travels with it for free.
     */
    var erased: ErasedRegion? = null
        private set

    /**
     * The one writer of the two boxes, so a subclass cannot update one and forget the other.
     *
     * Called from a subclass's initialiser and again whenever its own-space geometry is rebuilt —
     * a restyle re-tessellates a stroke, and `Brush.size` is in stroke units, so the mesh really
     * does change shape.
     */
    protected fun setBounds(objectSpace: PageBounds?) {
        bounds = objectSpace
        pageBounds = objectSpace?.under(transform)
    }

    /** The render side of `SetTransform`. Absolute: the whole matrix, never a composition. */
    fun setTransform(absolute: Matrix3) {
        transform = absolute
        pageBounds = bounds?.under(absolute)
        inverse = absolute.inverse()
        inverseScale = absolute.linearScale()
    }

    /** The render side of `SetZOrder`. */
    fun setZ(key: FractionalKey) {
        z = key
    }

    /**
     * The render side of `SetStyle`.
     *
     * The base records the register, which is all a style *is* (SPEC §4.2). [DryStroke] overrides
     * to rebuild its brush as well, because for a stroke the style is also geometry.
     */
    open fun restyle(style: ObjectStyle?, brushes: InkBrushFactory) {
        this.style = style
    }

    /**
     * Replace the erased region wholesale — the render side of `SetErasedRegion`, which is an
     * **absolute** register (CLAUDE.md rule 8). This is how an undone erase drops the capsules that
     * gesture added, and how a loaded page restores the region it was saved with.
     */
    fun setErased(region: ErasedRegion?) {
        erased = region
    }

    /** Extend the region by one eraser sample, in this object's own space. */
    fun eraseCapsule(capsule: ErasedRegion.Capsule) {
        val region = erased ?: ErasedRegion().also { erased = it }
        region.add(capsule)
    }

    /** A page-space point in this object's own space, or false if [transform] cannot be inverted. */
    fun toLocal(x: Float, y: Float, into: PagePointF): Boolean {
        val matrix = inverse ?: return false
        val mapped = matrix.map(PagePoint(x, y))
        into.set(mapped.x, mapped.y)
        return true
    }

    /** A page-space length in this object's own space. See [Matrix3.linearScale] on the fudge. */
    fun toLocalLength(pageLength: Float): Float = pageLength / inverseScale

    /** The registers a selection edit reads — id, transform and z, and deliberately nothing else. */
    fun asSelected(): SelectedObject = SelectedObject(id, transform, z)

    /**
     * True when the eraser's sweep — in **this object's own space** — covers any painted part of it.
     *
     * The caller maps the query here with [toLocal] rather than handing a `meshToQuery` transform
     * down, so the query stays a genuine [Parallelogram] and the erased region this decides to
     * extend is already in the right space.
     *
     * ⚠️ **The same sweep arrives twice, in two forms, and each kind uses exactly one** (M8b).
     * [DryStroke]'s painted area is a tessellated mesh, and only `androidx.ink` can measure it —
     * it takes [sweptTip]. [DryShape]'s painted area is a handful of segments and cubics, and the
     * answer is arithmetic over [capsule] (see `ShapeHit`). Passing both is deliberately preferred
     * to reconstructing one from the other: a parallelogram rebuilt from a capsule, or vice versa,
     * is a second definition of "what the eraser swept" that could drift from the one the region
     * actually records.
     */
    abstract fun hitBy(sweptTip: Parallelogram, capsule: ErasedRegion.Capsule): Boolean

    /**
     * True when a **tap** at `(localX, localY)`, in this object's own space, is on it.
     *
     * ⚠️ **Deliberately not [hitBy], and the difference is only about a hollow shape.** The eraser's
     * rule is that an unfilled outline is empty in the middle — you can drag an eraser straight
     * through a hollow box without biting it, which is what its emptiness *looks like*, and
     * `ShapeHit`'s KDoc argues that case well. A tap is the opposite: nobody aims at the two-point
     * line of a rectangle's edge, and *"tap the shape to select it"* means the shape, not its
     * perimeter.
     *
     * Sharing one predicate for both — which is what M8b did — made a snapped rectangle
     * approximately unselectable: the shape carries no fill (`ShapeSnapController` writes
     * `ShapeRecord.None`), so `filled` was false, so `ShapeHit` skipped the interior test entirely
     * and the user had to land inside 8 dp of a hairline. That is most of *"it does not let me
     * adjust it after"* — the shape was adjustable and could not be got hold of.
     *
     * The default is [hitBy], so a stroke and a text box are unaffected; only [DryShape] overrides.
     */
    open fun tappedAt(localX: Float, localY: Float, reach: Float): Boolean {
        val tip = ImmutableParallelogram.fromSegmentAndPadding(
            ImmutableSegment(ImmutableVec(localX, localY), ImmutableVec(localX, localY)),
            reach,
        )
        return hitBy(tip, ErasedRegion.Capsule(localX, localY, localX, localY, reach))
    }

    /**
     * Every silhouette vertex, in **page space** — what a lasso is tested against.
     *
     * The painted outline rather than any input path: a lasso drawn inside a thick loop must select
     * the loop, and the input path of a 16 pt highlighter stroke is a line through the middle of a
     * band four millimetres wide.
     */
    abstract fun forEachPagePoint(scratch: MutableVec, action: (Float, Float) -> Unit)

    /**
     * True when [region] has covered every painted part of this object, so `pruneFullyErased` may
     * drop it from the render state.
     *
     * ⚠️ **Dropping it emits no ops.** The object's `SetErasedRegion` already describes it and its
     * geometry stays in the log (CLAUDE.md rule 1), so this is a render optimisation and nothing
     * else — false costs a little drawing, true costs nothing that cannot come back.
     *
     * ⭐ M8c: on the object rather than as a `when` in the prune loop, which is where the same
     * decision already lives for erasing ([hitBy]). Default **false**, which is the whole answer
     * for every kind but a stroke: pruning is only worth it when the thing dropped is expensive to
     * keep drawing, and it costs the user their object until the page is reloaded — an undo of the
     * erase writes `SetErasedRegion` at an id the store no longer holds, and `applyRegister`
     * answers false for one.
     */
    open fun isErasedAway(region: ErasedRegion): Boolean = false

    /**
     * The number of point-against-capsule distance tests [isErasedAway] would run if called
     * now — the unit `pruneFullyErased`'s budget is denominated in — or 0 when a cheap gate
     * already answers it.
     *
     * The base answers 0 because the base [isErasedAway] answers false without work. [DryStroke]
     * overrides with the real estimate; the budget used to charge the *capsule count* while the
     * work was points × capsules, an under-count of about the point count (~100×) that let one
     * eraser pen-up run for hundreds of milliseconds inside the very bound built to prevent it.
     */
    open fun eraseCoverageCost(region: ErasedRegion): Long = 0L
}
