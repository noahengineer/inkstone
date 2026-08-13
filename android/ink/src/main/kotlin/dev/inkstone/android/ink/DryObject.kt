package dev.inkstone.android.ink

import androidx.ink.geometry.MutableVec
import androidx.ink.geometry.Parallelogram
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.InkObject
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.SelectedObject
import dev.inkstone.core.model.ShapeObject
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.TextBoxObject

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
     * True when [sweptTip] — in **this object's own space** — covers any painted part of it.
     *
     * The caller maps the query here with [toLocal] rather than handing a `meshToQuery` transform
     * down, so the query stays a genuine [Parallelogram] and the erased region this decides to
     * extend is already in the right space.
     */
    abstract fun hitBy(sweptTip: Parallelogram): Boolean

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
     * decision already lives for erasing ([hitBy]). Default **false**, and the default is the
     * whole answer for a [DryPlaceholder]: it cannot be erased by anything here, so a region on
     * one arrived from a build that *can* draw it, and this build must keep it rather than act on
     * a coverage test it has no geometry to run.
     */
    open fun isErasedAway(region: ErasedRegion): Boolean = false
}

/**
 * ⭐ M8c — an object the store **orders, carries and converges**, and that this build cannot paint.
 *
 * ## What it is for
 *
 * SPEC §4.9.3 describes what an older build does with a note containing a text box or an image:
 * it *"opens the note, keeps every byte, and shows an unformatted text box and an uncropped
 * image — which in practice means it shows nothing at all"*. This type is that degrade made
 * **local and non-destructive** instead of structural. The object is in the store, so:
 *
 * - it takes a z key from [StrokeStore.allocateTopZ] like everything else, so the next stroke the
 *   user draws lands *above* it rather than under it;
 * - it holds its absolute registers, so a peer's `SetTransform`, `SetStyle`, `SetZOrder`,
 *   `SetErasedRegion`, `SetTextContent` and `SetFormat` reach the render state and are not silently
 *   swallowed by a `when` that had no branch for it;
 * - it is in the one `(z, id)` order, so `SelectionEdit`'s bring-to-front sees the whole page and
 *   cannot put a stroke above something it did not know was there.
 *
 * ## ⚠️ It paints nothing, and that is a decision with a rule attached
 *
 * The rule is **nothing the user cannot see may be selected or erased**, and every geometric
 * predicate below is that one sentence: [hitBy] is false, so a tap and an eraser pass through it;
 * [forEachPagePoint] emits nothing, and `Lasso.selects` answers false for an empty point list, so
 * a lasso cannot take it. A selection box drawn round an invisible object, or an eraser silently
 * biting a photo, would both be worse than a blank rectangle — and a bite would be *stored*, in a
 * register SPEC §4.9.2 deliberately keeps separate from an image's crop.
 *
 * A kind that paints itself replaces this type rather than configuring it: it is a new [DryObject]
 * subclass with its own silhouette, its own coverage test and its own branch in [PagePainter], and
 * the sealed hierarchy is what makes forgetting the painter a compile error rather than an empty
 * rectangle. `docs/M8-CONNECTORS.md` §6 item 2 is the next one owed.
 *
 * @property obj the payload as it was folded. ⚠️ **Read the registers off `this`, never off
 *   [obj]** — the payload is first-write-wins (SPEC §4.2) and carries the values it was folded
 *   with, while the fields above are the live ones. It is kept because it is the only thing that
 *   says *what* the object is: an asset name, a shape kind, an extent.
 * @property content the `SetTextContent` register — a text box's words, or a shape's
 *   `ShapeRecord`. Seeded from [TextBoxObject.text] because a text box created with text and never
 *   edited has **no** `SetTextContent` op at all: its words are in the payload, and the fold's
 *   `texts` table is empty for it. `PageFolder.materialise` mirrors exactly this.
 * @property format the `SetFormat` register (SPEC §4.9.2) — font, size, alignment and list level
 *   for a text box, crop for an image. Kept as the encoded record rather than a decoded
 *   [dev.inkstone.core.model.ObjectFormat], so a key from a later build survives round-trip
 *   through the render state untouched, exactly as it does through the log.
 */
internal class DryPlaceholder(val obj: InkObject, widthPt: Float, heightPt: Float, content: String? = null) :
    DryObject(obj.transform, obj.style, obj.z) {

    override val id: ObjectId get() = obj.id

    /** The ink band: a highlighter drawn across a photo must tint the photo. See [drawsOverInk]. */
    override val drawsOverInk: Boolean get() = false

    var content: String? = content
        private set

    var format: String? = null
        private set

    init {
        // Every non-stroke kind occupies `[0, widthPt] × [0, heightPt]` in its own space and is
        // placed by its transform (`ShapePaths`' KDoc states it for shapes; `ImageObject` and
        // `TextBoxObject` carry the same two fields for the same reason).
        setBounds(PageBounds(0f, 0f, widthPt, heightPt))
        setErased(obj.erased.toRegion())
    }

    /** The render side of `SetTextContent`. Absolute, whole-string LWW (SPEC §4.2). */
    fun setContent(text: String?) {
        content = text
    }

    /** The render side of `SetFormat` (SPEC §4.9.2). Absolute, like every register here. */
    fun setFormat(record: String?) {
        format = record
    }

    /** False, always — see the class KDoc. An eraser must not bite what it cannot draw. */
    override fun hitBy(sweptTip: Parallelogram): Boolean = false

    /**
     * Emits nothing, and the emptiness is the decision rather than an omission — see the class
     * KDoc. `Lasso.selects` answers false for an empty point list, so this is what "cannot be
     * lassoed" is spelled as.
     */
    override fun forEachPagePoint(scratch: MutableVec, action: (Float, Float) -> Unit) {
        return
    }

    companion object {
        /**
         * The placeholder for [obj], or null when the object has geometry this build can draw.
         *
         * Exhaustive over [InkObject] on purpose: a kind added to `:core:model` without a decision
         * here fails to compile, rather than being dropped from the screen — which is the whole
         * bug this file exists to close.
         */
        fun of(obj: InkObject): DryPlaceholder? = when (obj) {
            // Has a geometry blob; `DryStroke` decodes and draws it.
            is StrokeObject -> null

            is ImageObject -> DryPlaceholder(obj, obj.widthPt, obj.heightPt)

            is TextBoxObject -> DryPlaceholder(obj, obj.widthPt, obj.heightPt, content = obj.text)

            is ShapeObject -> DryPlaceholder(obj, obj.widthPt, obj.heightPt)
        }
    }
}
