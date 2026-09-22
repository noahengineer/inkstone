package dev.inkstone.core.model

import kotlin.math.max
import kotlin.math.min

/**
 * ROADMAP M8a — what a selection gesture writes down, as a pure function of the
 * objects it selected.
 *
 * ## Why this is in `:core:model` and not in the ink surface
 *
 * The gesture happens in `:android:ink` — hit-testing a lasso against a stroke needs
 * the geometry blob decoded, and only that module can decode it (SPEC §5.4). But the
 * *decision* — which ops a move, a resize, a restyle or a reorder produces — is
 * arithmetic over plain data, and CLAUDE.md's definition-of-done #6 is explicit about
 * where that belongs:
 *
 * > Hoist the **decision** into a pure function over plain data so a test can at least
 * > assert the decision was made.
 *
 * So this file is the whole of M8a's op-writing, reachable from a JVM test, from the
 * iOS target, and — the reason that matters — from SPEC §13.2's merge matrix, whose
 * rows 3 and 7 previously hand-built the ops they asserted on. A row that builds its
 * own `SetTransform` is testing the row.
 *
 * ## The one rule every function here obeys
 *
 * ⭐ **Absolute, never a delta** (SPEC §4.2, CLAUDE.md rule 8). A resize writes the
 * matrix the object ends up with, not the scale the finger applied. The gesture is
 * composed here, once, and the *result* is the op. SPEC §4.2's worked example is what
 * happens otherwise, and it happens silently.
 *
 * That is also why [transform] takes the objects rather than their ids: composing
 * `gesture × current` needs the current absolute matrix, and reading it from the fold
 * at the moment the gesture commits is what makes the write absolute.
 *
 * ## No new op types
 *
 * Everything here emits ops M2 already defined. M8a adds behaviour, not a format
 * version — extending the vocabulary after sync is live is the milestone where
 * everything breaks (SPEC §4.2).
 */
public object SelectionEdit {

    /**
     * Move, resize or rotate — one function, because to an op log they are the same thing.
     *
     * [gesture] is a page-space transform, composed on the **left** of each object's own
     * absolute matrix, and the product is what gets written. A caller that applies the
     * same gesture twice must compose against the transforms the objects had when the
     * gesture *started*, not against the last frame's — which is why a live drag previews
     * without emitting and emits once, on release.
     *
     * @return one [PageOp.SetTransform] per object, or an empty list for a degenerate
     *   [gesture]. Degenerate is refused rather than written: a singular matrix collapses
     *   the object to a line the user cannot grab again, and [Matrix3.inverse] answers null
     *   for one — which is what maps an eraser into a transformed object's own space
     *   ([ErasedArea] is in that space). Undo would still recover it, but "the object
     *   vanished and the eraser stopped working on it" is not a state worth being able to
     *   reach.
     */
    public fun transform(
        objects: List<SelectedObject>,
        gesture: Matrix3,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> {
        if (gesture.isDegenerate()) return emptyList()
        return objects.map { obj ->
            PageOp.SetTransform(nextOpId(), nextClock(), obj.id, gesture * obj.transform)
        }
    }

    /**
     * ⭐ One absolute `SetTransform` per object, each computed from **that** object.
     *
     * [transform]'s sibling, and [restyle]'s argument applies word for word: a gesture is one
     * matrix for the whole selection because a drag moves everything together, while *"make each of
     * these a perfect circle"* is a different matrix per shape — one shared value would resize them
     * all to whichever one happened to be measured. Which is why this exists rather than a loop at
     * the call site: the loop would have to build the ops, and op-building is what this object is.
     *
     * @param matrixOf null for an object with nothing to do — already regular, already on the
     *   lattice, or not a kind this action applies to — and no op is written for it. A degenerate
     *   matrix is refused for [transform]'s reason: a shape that arrives singular can never be
     *   moved, erased or attached to again.
     */
    public fun transformEach(
        objects: List<SelectedObject>,
        matrixOf: (SelectedObject) -> Matrix3?,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> = objects.mapNotNull { obj ->
        val matrix = matrixOf(obj)?.takeIf { !it.isDegenerate() } ?: return@mapNotNull null
        PageOp.SetTransform(nextOpId(), nextClock(), obj.id, matrix)
    }

    /**
     * Recolour, rewidth or fade a selection.
     *
     * The style is absolute and complete (see [ObjectStyle]). A stroke's capture-time colour
     * lives in its geometry blob and a null style means "as captured"; this is the override
     * that starts null and, once written, is the truth. Passing null is therefore *not* "leave
     * it alone" and is not offered — reverting to as-captured would need a register that can be
     * un-set, which an LWW register is not.
     *
     * ⚠️ **[styleOf] is per object, not one style for the selection**, and that is not
     * generality for its own sake. `SetStyle` carries a *complete* style, so a single shared
     * value would make "make these red" also make them all the same width — silently
     * flattening a selection of a 3 pt pen and a 16 pt highlighter into one. The caller
     * composes each object's new style from its current one; only the field the user changed
     * differs.
     */
    public fun restyle(
        objects: List<SelectedObject>,
        styleOf: (SelectedObject) -> ObjectStyle,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> = objects.map { obj ->
        PageOp.SetStyle(nextOpId(), nextClock(), obj.id, styleOf(obj))
    }

    /**
     * Bring to front: new keys above everything currently on [page], in the selection's
     * own order so a multi-object selection keeps its internal stacking.
     *
     * Fractional keys rather than a renumbering pass, for the reason SPEC §4.3 gives: two
     * devices can each bring something to the front offline and both keep their result,
     * ordered rather than one silently overwriting the other.
     */
    public fun bringToFront(
        objects: List<SelectedObject>,
        page: List<SelectedObject>,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> = restack(objects, page, toFront = true, nextOpId, nextClock)

    /** Send to back — [bringToFront] in the other direction. */
    public fun sendToBack(
        objects: List<SelectedObject>,
        page: List<SelectedObject>,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> = restack(objects, page, toFront = false, nextOpId, nextClock)

    /**
     * A tombstone per object (SPEC §4.2). Never a removal — the `AddObject` and its
     * geometry stay in the log, which is what makes undo a re-add rather than a rebuild
     * and what CLAUDE.md rule 1 requires.
     */
    public fun delete(
        objects: List<SelectedObject>,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> = objects.map { obj ->
        PageOp.DeleteObject(nextOpId(), nextClock(), obj.id)
    }

    /**
     * [count] z keys above everything on [page], in ascending order — what a duplicate gets.
     *
     * ## Why duplication is *this* and not a `duplicate()` that returns ops
     *
     * A stroke's payload is its geometry blob, and producing one means `androidx.ink`'s
     * encoder — which lives in `:android:ink`, must not run on the ink path (CLAUDE.md rule
     * 7), and is already deferred to a worker by `PageOpRecorder`. A core function that
     * built the whole `AddObject` could therefore only ever be called by a test, and a
     * *tested, uncalled* op-builder next to the real one is precisely the shape of bug
     * CLAUDE.md's definition-of-done #6 is about.
     *
     * So what is hoisted here is the part that is a decision — where the copy goes in the
     * stack — and the mechanical half stays where the encoder is. The copy's offset is
     * [SelectionGesture.move], shared the same way.
     *
     * Keys are generated against the *well-formed* maximum: a malformed key on the page
     * (see [FractionalKey.isWellFormed]) is ignored rather than thrown on, because "paste
     * crashes on this one note" is a worse answer than "the copy lands above everything
     * legible".
     */
    public fun keysAbove(top: FractionalKey?, count: Int): List<FractionalKey> {
        require(count >= 0) { "count must not be negative, was $count" }
        var above = top
        return List(count) {
            val z = keyAbove(above)
            above = z
            z
        }
    }

    /**
     * One key above [top] — where the next thing added to a page goes.
     *
     * ⚠️ Takes the current maximum rather than the page, because the caller that runs most
     * often is the one that finishes a **stroke**, and a scan there would be O(strokes) on the
     * ink path (CLAUDE.md rule 7). `StrokeStore` maintains the maximum incrementally and hands
     * it in; that it does so *at all* is M8a's doing — `PageOpRecorder` kept a private counter
     * that `SetZOrder` could silently overtake, so bringing a stroke to the front made the next
     * stroke drawn land underneath it.
     *
     * A [top] that is not [FractionalKey.isWellFormed] is treated as no bound rather than
     * thrown on: a malformed key can only have come from a file, and refusing to draw is a
     * worse answer than putting the stroke above everything legible.
     */
    public fun keyAbove(top: FractionalKey?): FractionalKey = FractionalKey.between(top?.usable, null)

    private fun restack(
        objects: List<SelectedObject>,
        page: List<SelectedObject>,
        toFront: Boolean,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> {
        if (objects.isEmpty()) return emptyList()
        val moving = objects.mapTo(HashSet()) { it.id }
        // The extreme among the objects that are *staying*. Measuring against the whole page
        // would include the selection itself, so "bring to front" on something already at the
        // front would keep lengthening its key for no visible change.
        val staying = page.filter { it.id !in moving }.mapNotNull { it.z.usable }
        var bound: FractionalKey? = if (toFront) staying.maxOrNull() else staying.minOrNull()
        // In selection order for the front, reversed for the back, so that whichever end the
        // keys are generated from, the selection's own stacking survives the move.
        val ordered = objects.sortedBy { it.z }.let { if (toFront) it else it.asReversed() }
        val ops = ordered.map { obj ->
            val z = if (toFront) FractionalKey.between(bound, null) else FractionalKey.between(null, bound)
            bound = z
            PageOp.SetZOrder(nextOpId(), nextClock(), obj.id, z)
        }
        return ops
    }
}

/**
 * This key, or null if it is not one [FractionalKey.between] can build against.
 *
 * A z key comes out of a fold, and a fold will happily carry one a foreign tool or an
 * older build wrote — `PageOrder.repair` exists for exactly that on the page side, and
 * there is no equivalent for objects. `between` *throws* on a malformed bound, so without
 * this a single bad key on the page would make "bring to front" crash rather than
 * misorder. Ignoring it puts the new object at the top of the well-formed keys, which is
 * where the user asked for it; the malformed one keeps whatever place string comparison
 * gives it.
 */
private val FractionalKey.usable: FractionalKey? get() = takeIf { it.isWellFormed }

/**
 * One object as a selection sees it: an identity and the two registers a selection edit
 * reads before writing.
 *
 * ## Why not just `InkObject`
 *
 * Because the selection tool does not have one. `:android:ink` holds a *render cache* — a
 * decoded `androidx.ink.Stroke` and its registers — and never the `StrokeObject` it came
 * from; the geometry blob was consumed at load. A [SelectionEdit] taking `InkObject` would
 * therefore have forced the surface to fabricate one, with a fake blob, purely to satisfy
 * a signature — and a fabricated payload reaching an `AddObject` by accident is data loss
 * of the quiet kind.
 *
 * The style is deliberately absent: [SelectionEdit.restyle] writes an absolute value and
 * reads nothing, so carrying the old one would be a field that exists to be ignored.
 */
public data class SelectedObject(val id: ObjectId, val transform: Matrix3, val z: FractionalKey)

/** The view of an object a selection edit needs. */
public val InkObject.selected: SelectedObject get() = SelectedObject(id, transform, z)

/**
 * The page-space transforms a selection gesture produces, as pure arithmetic.
 *
 * Separate from [SelectionEdit] because the two answer different questions and are
 * tested against different things: this turns fingers into a matrix, that turns a matrix
 * into ops. Everything here is composed **about an explicit pivot**, because a scale or a
 * rotation with an implied origin is a translation nobody asked for.
 */
public object SelectionGesture {

    public fun move(dx: Float, dy: Float): Matrix3 = Matrix3.translate(dx, dy)

    /**
     * Map [from] onto [to] — the resize-handle drag.
     *
     * @return null when either box has collapsed. A caller that clamps its handles never
     *   sees this; a caller that does not would otherwise write a singular matrix (see
     *   [SelectionEdit.transform]).
     */
    public fun resize(from: PageRect, to: PageRect): Matrix3? {
        if (from.width <= 0f || from.height <= 0f) return null
        if (to.width <= 0f || to.height <= 0f) return null
        return Matrix3.translate(to.left, to.top) *
            Matrix3.scale(to.width / from.width, to.height / from.height) *
            Matrix3.translate(-from.left, -from.top)
    }

    /** [radians] about ([pivotX], [pivotY]) in page space — y-down, so positive is clockwise. */
    public fun rotate(pivotX: Float, pivotY: Float, radians: Float): Matrix3 =
        Matrix3.translate(pivotX, pivotY) * Matrix3.rotate(radians) * Matrix3.translate(-pivotX, -pivotY)
}

/**
 * A page-space polygon: the lasso, freehand or rectangular.
 *
 * ## Why the containment test is here and not in `:android:ink`
 *
 * `android.graphics.Region` would do this, and doing it there would put the one rule a
 * user experiences as *"why did it not select that"* behind a device test. Point-in-polygon
 * is fifteen lines of arithmetic; the ink module's irreducible job is turning a stroke's
 * opaque geometry into points, and it hands them here.
 *
 * The polygon is implicitly closed — the last point joins the first — because that is what
 * a lasso is, and requiring the caller to close it makes an unclosed one representable.
 */
public class Lasso private constructor(public val points: List<PagePoint>) {

    /** Null for fewer than three points: a polygon with no area selects nothing. */
    public val bounds: PageRect? = if (points.size < MIN_POLYGON_POINTS) null else PageRect.around(points)

    /**
     * Even-odd ray casting along +x.
     *
     * The `(yi > y) != (yj > y)` form is the standard half-open edge test: it counts an
     * edge whose lower endpoint is exactly on the ray and skips the upper one, so a point
     * level with a shared vertex crosses once rather than twice or not at all. Getting that
     * wrong shows up only for a ray that grazes a vertex, which on real handwriting means a
     * stroke that occasionally refuses to be selected and cannot be reproduced on demand.
     */
    public fun contains(x: Float, y: Float): Boolean {
        val box = bounds ?: return false
        // The bounding box first, because this runs once per sampled point per object and a page
        // holds thousands of both. Split in two so each half reads as one axis.
        val outsideX = x < box.left || x > box.right
        val outsideY = y < box.top || y > box.bottom
        if (outsideX || outsideY) return false
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val yi = points[i].y
            val yj = points[j].y
            if ((yi > y) != (yj > y)) {
                val crossX = points[i].x + (y - yi) / (yj - yi) * (points[j].x - points[i].x)
                if (x < crossX) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Whether a lasso takes an object whose visible geometry sampled to [sampled].
     *
     * ⭐ **Majority, not "any part" and not "all of it".** All three are defensible and the
     * middle one is the only one that behaves: *any part* means a lasso round one word also
     * takes the underline running beneath the whole line, and *all of it* means a lasso that
     * clips one ascender takes nothing and gives the user no clue why. Majority draws the
     * boundary where the eye does — the thing you drew round is the thing that moves.
     *
     * @param sampled points on the object, in **page space** — i.e. with its own transform
     *   already applied. Which points they are is the caller's business ([contains] does not
     *   care), but they must cover the object: sampling only a stroke's input path would let
     *   a lasso inside a thick loop select nothing.
     */
    public fun selects(sampled: List<PagePoint>, minimumFraction: Float = MAJORITY): Boolean {
        if (sampled.isEmpty() || bounds == null) return false
        var inside = 0
        for (point in sampled) {
            if (contains(point.x, point.y)) inside++
        }
        return inside.toFloat() / sampled.size >= minimumFraction
    }

    public companion object {
        /**
         * A freehand lasso. Fewer than three points is a tap, not a lasso, and answers
         * false to everything rather than throwing — a stylus can produce a one-sample
         * gesture and that is not an error.
         */
        public fun freehand(points: List<PagePoint>): Lasso = Lasso(points)

        /** The rectangular marquee, as the same polygon so there is one containment rule. */
        public fun rectangle(a: PagePoint, b: PagePoint): Lasso {
            val left = min(a.x, b.x)
            val right = max(a.x, b.x)
            val top = min(a.y, b.y)
            val bottom = max(a.y, b.y)
            return Lasso(
                listOf(
                    PagePoint(left, top),
                    PagePoint(right, top),
                    PagePoint(right, bottom),
                    PagePoint(left, bottom),
                ),
            )
        }

        private const val MIN_POLYGON_POINTS = 3

        /** Strictly more than half, so a stroke exactly straddling the boundary is taken. */
        private const val MAJORITY = 0.5f
    }
}
