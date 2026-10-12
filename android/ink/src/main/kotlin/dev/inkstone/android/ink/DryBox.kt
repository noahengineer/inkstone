package dev.inkstone.android.ink

import androidx.ink.geometry.MutableVec
import androidx.ink.geometry.Parallelogram
import dev.inkstone.core.model.InkObject
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.ShapeGeometry
import dev.inkstone.core.model.ShapeHit
import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.ShapePath

/**
 * ⭐ M8c — the half a text box and an image have in common: **a rectangle, two record registers,
 * and a style that is never unset**.
 *
 * ## Why an intermediate class rather than two copies
 *
 * [DryObject]'s KDoc gives the rule this follows: a base class *owns* an invariant where an
 * interface can only document one. Three of the four things below are invariants that must not
 * drift between the two kinds, and every one of them fails silently if they do:
 *
 * - **The silhouette and the hit test must be the same rectangle.** They are what a lasso and a
 *   tap measure against, and an object you can select with a lasso but not with a tap — or erase
 *   but not select — is a bug nobody would ever trace back to two copies of a corner list.
 * - **[bounds] and [pageBounds] are written together**, through [setBounds]. A subclass that
 *   rebuilds its own-space extent (a text box does, whenever its words or its font change) must
 *   not update one and leave the other stale.
 * - **The style register is never unset.** SPEC §4.9.2 requires both kinds to be *created* with a
 *   non-null `style`; [effectiveStyle] is what makes a peer's object that arrives without one
 *   still paint, and — more importantly — what a first recolour's baseline writes so that
 *   *"change the colour, press undo"* has something to invert to.
 *
 * ## ⚠️ Both registers, and only one of them is words
 *
 * SPEC §4.9.2 splits presentation from content deliberately: put the font and the paragraph in one
 * LWW register and *changing the font on one device discards a paragraph typed on the other*. So
 * [content] is `SetTextContent` and [format] is `SetFormat`, they are separate, and this class
 * never merges them. [format] is kept as the **encoded string** rather than as a decoded
 * [ObjectFormat] so that a key written by a later build survives the render state exactly as it
 * survives the log (SPEC §5.8) — the decode is a cached derivation beside it, never a replacement.
 *
 * @property obj the payload as it was folded. ⚠️ **Read the registers off `this`, never off [obj]**
 *   — the payload is first-write-wins (SPEC §4.2) and carries the values it was folded with. It is
 *   kept for what it alone says: an asset name, an extent, the words a box was created with.
 */
internal sealed class DryBox(val obj: InkObject, widthPt: Float, heightPt: Float, content: String?) :
    DryObject(obj.transform, obj.style, obj.z) {

    /**
     * The frame this object occupies in its **own** space.
     *
     * ⚠️ **Not `obj.widthPt`, and the difference is a whole register.** The payload is
     * first-write-wins (SPEC §4.2), so it says how big the box was *placed*; `SetFormat` may carry
     * a frame the user has since dragged ([ObjectFormat.boxWidthPt]). Every consumer here — the
     * silhouette a lasso is tested against, the rectangle a tap is tested against, the wrap width
     * [DryTextBox] lays out to — has to be the live one, or the object is selectable somewhere it
     * is not drawn.
     *
     * Only [DryTextBox] ever moves it. Resizing a picture is a scale, because scaling a photo is
     * what that means; resizing a paragraph is a different wrap width, which is why the two kinds
     * answer differently.
     */
    var widthPt: Float = widthPt
        private set

    var heightPt: Float = heightPt
        private set

    final override val id: ObjectId get() = obj.id

    /** The ink band: a highlighter drawn across a photo must tint the photo, exactly as it tints writing. */
    final override val drawsOverInk: Boolean get() = false

    /**
     * The `SetTextContent` register — a text box's words.
     *
     * Seeded from the payload because a text box created with text and never edited has **no**
     * `SetTextContent` op at all: its words are on its `AddObject` and the fold's `texts` table is
     * empty for it. `PageFolder.materialise` mirrors exactly this. An image's is free and unused
     * (SPEC §4.9.2's table), and it is held rather than refused so that a peer writing one cannot
     * lose it here.
     */
    var content: String? = content
        private set

    // ⭐ The `SetFormat` register and its parsed view moved to [DryObject] with knockout (SPEC
    // §4.9.6): every kind can carry one now. [onFormatChanged] is how this kind still hears about it.

    /**
     * The rectangle this object occupies in its **own** space, as a [ShapePath].
     *
     * A real rectangle from [ShapeGeometry] rather than four hand-written corners, because the
     * predicate it is fed to — [ShapeHit] — is the same one the eraser and the tap test already
     * share for shapes. `docs/M8-SELECTION.md` §7 said hit-testing an image would be *"a few lines
     * of rectangle containment"*; this is those lines, spent on reuse instead. One predicate for
     * the tap, the lasso and the eraser is what stops an image you can erase from being an image
     * you cannot select.
     */
    private var outline: ShapePath = ShapeGeometry.outline(ShapeKind.RECTANGLE, widthPt, heightPt)

    init {
        setBounds(PageBounds(0f, 0f, widthPt, heightPt))
        setErased(obj.erased.toRegion())
    }

    /**
     * A new frame, from a register write.
     *
     * ⚠️ [outline] is rebuilt here and nowhere else. It is what the tap test and the eraser measure
     * against, and leaving it at the placed size would give a resized box a hit area that does not
     * match the one it is drawn at — the *"selectable with a lasso but not with a tap"* failure this
     * class's KDoc exists to make impossible, arrived at through a stale cache instead of a second
     * corner list. The caller sets [bounds]: a text box's painted extent is not its frame.
     */
    protected fun setFrame(widthPt: Float, heightPt: Float) {
        if (this.widthPt == widthPt && this.heightPt == heightPt) return
        this.widthPt = widthPt
        this.heightPt = heightPt
        outline = ShapeGeometry.outline(ShapeKind.RECTANGLE, widthPt, heightPt)
    }

    /** The render side of `SetTextContent`. Absolute, whole-string LWW (SPEC §4.2). */
    fun setContent(text: String?) {
        if (content == text) return
        content = text
        invalidateSilhouette()
        onPresentationChanged()
    }

    /** The render side of `SetFormat` (SPEC §4.9.2) — the base records it; the box re-lays-out. */
    override fun onFormatChanged() = onPresentationChanged()

    override fun restyle(style: ObjectStyle?, brushes: InkBrushFactory) {
        super.restyle(style, brushes)
        onPresentationChanged()
    }

    /**
     * Something that changes what this object looks like has been written.
     *
     * Called off the ink path in every case — a toolbar action, a page load, a peer's op, an undo —
     * which is what lets [DryTextBox] rebuild its [android.text.StaticLayout] here rather than
     * lazily in `onDraw`, where CLAUDE.md rule 7 forbids it.
     */
    protected open fun onPresentationChanged() = Unit

    /**
     * The style this object is painted with right now — the live register, then the payload's, then
     * a default.
     *
     * ⚠️ **The fallback is not decoration.** SPEC §4.9.2 requires a text box and an image to be
     * *created* with a non-null style precisely because `SetStyle` cannot express "unset", so the
     * first recolour of one that has none has no inverse and *"change the colour, press undo"*
     * silently eats the press. This module cannot make a peer obey that rule; what it can do is
     * make the baseline a first restyle writes be **what is on the screen** rather than an invented
     * black, which is what `SelectionController.restyle` reads this for.
     */
    fun effectiveStyle(): ObjectStyle = style ?: obj.style ?: DEFAULT_STYLE

    /**
     * Rectangle containment in this object's **own** space — `docs/M8-SELECTION.md` §7, exactly.
     *
     * `filled = true` because both kinds are solid: an eraser dragged through the middle of a photo
     * must bite the photo, and a tap in the white space between two words must select the text box
     * the words are in. [sweptTip] is ignored for the reason [DryObject.hitBy] gives — it is the
     * query `androidx.ink` wants for a tessellated mesh, and this is arithmetic over four segments.
     */
    final override fun hitBy(sweptTip: Parallelogram, capsule: ErasedRegion.Capsule): Boolean = ShapeHit.hitsCapsule(
        path = outline,
        filled = true,
        fromX = capsule.fromX,
        fromY = capsule.fromY,
        toX = capsule.toX,
        toY = capsule.toY,
        radius = capsule.radius,
    )

    /**
     * The outline in page space — what a lasso is tested against.
     *
     * The **declared** box and not the painted extent, so that a text box whose words overflow it
     * is still lassoed by the frame the user can see handles on. [DryTextBox] widens [bounds] to
     * cover the overflow so that culling and erasing do not clip it; selection deliberately does
     * not follow, because the frame a resize is computed against has to be the object's own box.
     *
     * ⚠️ **The edges are walked, not just the four corners**, and the difference is why a lasso used
     * to slide off a photo. `Lasso.selects` takes a **majority** of the points offered, so an object
     * that offers four had to have two full corners inside the loop: a lasso drawn *within* a large
     * picture enclosed none of them and selected nothing, and a band dragged across its middle
     * enclosed none either — while the identical gesture over handwriting worked, because a stroke
     * offers every vertex of its outline and a shape offers its flattened path. The box kinds were
     * the only ones sampled so sparsely that the majority rule could not see them.
     */
    final override fun forEachPagePoint(scratch: MutableVec, action: (Float, Float) -> Unit) {
        edge(0f, 0f, widthPt, 0f, action)
        edge(widthPt, 0f, widthPt, heightPt, action)
        edge(widthPt, heightPt, 0f, heightPt, action)
        edge(0f, heightPt, 0f, 0f, action)
    }

    /**
     * One edge, sampled at [EDGE_SAMPLES] points including its start and excluding its end — the
     * next edge's start. Sampling in the object's own space keeps it uniform under any transform.
     */
    private inline fun edge(x0: Float, y0: Float, x1: Float, y1: Float, action: (Float, Float) -> Unit) {
        for (step in 0 until EDGE_SAMPLES) {
            val t = step.toFloat() / EDGE_SAMPLES
            corner(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, action)
        }
    }

    private inline fun corner(x: Float, y: Float, action: (Float, Float) -> Unit) {
        val mapped = transform.map(PagePoint(x, y))
        action(mapped.x, mapped.y)
    }

    private companion object {
        /**
         * Points per edge for [forEachPagePoint]. Eight puts 32 around the box, which is the same
         * order a small shape's flattened outline offers, so the majority rule treats the kinds
         * alike. Cheap: this runs once per object per lasso settle, not per frame.
         */
        const val EDGE_SAMPLES = 8

        /**
         * What an object created without a style is painted with — see [effectiveStyle].
         *
         * The same near-black the pen defaults to, so a peer's unstyled text box reads as writing
         * rather than as an error. `widthPt` is unused by both kinds and is the pen's default so
         * that a set-width applied to a mixed selection does not resize anything to nothing.
         */
        val DEFAULT_STYLE = ObjectStyle(colorArgb = 0xFF1A1A1A.toInt(), widthPt = 2f)
    }
}
