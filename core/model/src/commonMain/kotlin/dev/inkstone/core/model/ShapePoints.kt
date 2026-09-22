// The halves and quarters here are the geometry of a box's own corners and diameters, not
// configuration — `w / 2f` says where the top edge's midpoint is, and a constant named HALF would
// say less and still have to be checked against the shape it describes. Same reason `Shapes.kt` and
// `ShapeSnap.kt` carry this. The one number that *is* tuned is named, with its reasoning attached.
@file:Suppress("MagicNumber")

package dev.inkstone.core.model

import kotlin.math.hypot

/**
 * ⭐ **Editing a shape by its points** — where the handles are, and what dragging one writes.
 *
 * A selection frame moves, scales and rotates a shape as a lump. That is the wrong instrument for a
 * shape whose *points* are the thing being drawn: a triangle cannot be made into a different
 * triangle by scaling it, an axis-aligned line's frame has no height to grab, and a rotated
 * rectangle's frame is not even the rectangle — it is the axis-aligned box around it, so its corners
 * sit off the shape entirely.
 *
 * ## ⭐ One rule: a drag moves **one point**, and nothing else moves
 *
 * | Kind | Handles | A drag writes |
 * |---|---|---|
 * | [ShapeKind.RECTANGLE] / [ShapeKind.ELLIPSE] / [ShapeKind.TRIANGLE] | its vertices | [ShapeRecord.points] |
 * | [ShapeKind.LINE] / [ShapeKind.ARROW] | 2 ends + 1 bend | [ShapeRecord.points] for an end, [ShapeRecord.bend] for the middle |
 *
 * Nothing here writes a `SetTransform`. That is the whole file, and it took two reports to get to.
 *
 * ⚠️ **It began as the opposite** — every drag solved as a new *placement*, on the observation that
 * three point correspondences determine an affine map:
 *
 * > *"editing the points is just resizeing the shape from those points rather then actually editing
 * > the shape, which is not what i want at all"*
 *
 * Exactly right for a closed shape. A rectangle has **four** corners and an affine has three degrees
 * of freedom, so *"move this corner and leave the other three"* has no solution as a matrix; the
 * nearest a matrix can do is scale about the opposite corner, which is what *"resizing from those
 * points"* describes. Those three kinds moved to stored vertices.
 *
 * ⚠️ **A segment was left on the placement route, and that was wrong too:**
 *
 * > *"straight lines … have the stretching by size changing, i dont want any shape to do that no
 * > matter what"*
 *
 * The defence had been that a similarity expresses a segment *exactly* — map one end anywhere, pin
 * the other, no residue. True, and beside the point: a similarity scales the whole local frame, so a
 * curve's bulge grew as its chord was dragged out, and what was being edited was still the placement
 * rather than the points. "Expressible" is not "what should happen". A segment stores its two ends
 * now, like everything else, and the three transform solves this file used to carry — a similarity,
 * an axis scale and an exact affine — are **deleted** rather than left unreachable.
 *
 * ## What still uses the transform
 *
 * Moving, resizing and rotating the shape *as a whole*, which is `SelectionEdit.transform` and one
 * absolute `SetTransform` (SPEC §4.8.3). Points are local, so they ride along for free.
 *
 * ## Where this runs
 *
 * `:core:model`, for CLAUDE.md definition-of-done #6's reason: *"hoist the decision into a pure
 * function over plain data so a test can at least assert the decision was made."* The gesture is a
 * `MotionEvent` in `:android:ink`; the arithmetic is here, where a JVM test and the iOS target can
 * both reach it. Nothing here is on the ink path — a handle drag is a selection gesture.
 */
public object ShapePoints {

    /**
     * The handles [kind] offers, in the order a UI should draw them.
     *
     * @param bendLocal the live [ShapeRecord.bend]. A straight line's bend handle sits at the
     *   **middle of the chord**, which is the whole affordance: the way to make a curve is to grab
     *   the middle of a line and pull, and a handle that only appeared once the line was already
     *   curved would be a feature nobody could find.
     * @param points the live [ShapeRecord.points] — the shape's own vertices once any has been
     *   dragged, and null until then.
     * @return empty for a degenerate box: a shape with no extent has no points worth grabbing.
     */
    public fun of(
        kind: ShapeKind,
        widthPt: Float,
        heightPt: Float,
        bendLocal: PagePoint? = null,
        points: List<PagePoint>? = null,
    ): List<ShapePoint> {
        val w = widthPt
        val h = heightPt
        if (w < ShapeGeometry.MIN_EXTENT_PT || h < ShapeGeometry.MIN_EXTENT_PT) return emptyList()

        if (ShapeGeometry.bends(kind)) {
            // ⚠️ The bend sits **between** the two ends, so a segment's handle indices are not its
            // vertex indices — see [moved], which is the one place that mapping lives.
            val (a, b) = ShapeGeometry.endpointsOf(w, h, points)
            return listOf(
                ShapePoint(LINE_START, a, ShapePointRole.VERTEX),
                ShapePoint(LINE_BEND, bendLocal ?: midpointOf(a, b), ShapePointRole.BEND),
                ShapePoint(LINE_END, b, ShapePointRole.VERTEX),
            )
        }

        // Already edited: the stored vertices *are* the handles, in their own order.
        points?.takeIf { it.size >= MIN_VERTICES }?.let { edited ->
            return edited.mapIndexed { index, local -> ShapePoint(index, local, ShapePointRole.VERTEX) }
        }
        return when (kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> error("handled above")

            ShapeKind.RECTANGLE -> listOf(
                vertex(0, 0f, 0f),
                vertex(1, w, 0f),
                vertex(2, w, h),
                vertex(3, 0f, h),
            )

            ShapeKind.ELLIPSE -> listOf(
                vertex(0, w / 2f, 0f),
                vertex(1, w, h / 2f),
                vertex(2, w / 2f, h),
                vertex(3, 0f, h / 2f),
            )

            ShapeKind.TRIANGLE -> listOf(
                vertex(0, w / 2f, 0f),
                vertex(1, w, h),
                vertex(2, 0f, h),
            )
        }
    }

    /**
     * ⭐ **The vertex list a drag of the handle at [index] to [toLocal] produces — one point moved,
     * every other one untouched.**
     *
     * @param current the record's points, or **null on the first ever drag**, in which case the list
     *   is seeded from [kind]'s own outline. The seeds are exactly what [ShapeGeometry] draws, so
     *   seeding is invisible: the shape does not change at the moment it becomes editable, only when
     *   the finger moves it.
     * @return absolute, whole, and ready for the register (CLAUDE.md rule 8 — never a delta). Null
     *   for a handle that is not a vertex, or an index that is not one of this kind's.
     */
    public fun moved(
        kind: ShapeKind,
        widthPt: Float,
        heightPt: Float,
        current: List<PagePoint>?,
        index: Int,
        toLocal: PagePoint,
    ): List<PagePoint>? {
        if (ShapeGeometry.bends(kind)) {
            // A segment's handles are start / bend / end, so handle 2 is vertex 1. The bend has no
            // entry in this list at all — it is its own register, and [BendDrag] is its drag.
            val end = when (index) {
                LINE_START -> 0
                LINE_END -> 1
                else -> return null
            }
            val (a, b) = ShapeGeometry.endpointsOf(widthPt, heightPt, current)
            return if (end == 0) listOf(toLocal, b) else listOf(a, toLocal)
        }
        val seeded = current?.takeIf { it.size >= MIN_VERTICES }
            ?: of(kind, widthPt, heightPt).map { it.local }.takeIf { it.size >= MIN_VERTICES }
            ?: return null
        if (index !in seeded.indices) return null
        return seeded.mapIndexed { at, point -> if (at == index) toLocal else point }
    }

    /** [toPage] in the shape's own space — where a drag has actually put the point. */
    public fun toLocal(transform: Matrix3, toPage: PagePoint): PagePoint? = transform.inverse()?.map(toPage)

    /**
     * ⭐ What a drag of the middle handle to [toPage] means — see [BendDrag] for why it is two
     * answers and not one nullable.
     *
     * @param widthPt and [heightPt] the payload extents, which with [points] give the chord the bend
     *   is measured against. ⚠️ **The chord is the *edited* one**: once an end has been dragged the
     *   segment is no longer `(0, h/2) → (w, h/2)`, and measuring "is this straight" against the
     *   payload's chord would straighten a curve that is nowhere near it.
     * @param straightenTolerancePt how near the chord counts as straight, in **page** points. The
     *   offset is measured in page space so the tolerance is a real distance on the paper whatever
     *   the placement scales by — the conversion `AlignmentGuides` and
     *   `SelectionController.snapTolerance` both got wrong once, in the same direction.
     */
    public fun bend(
        widthPt: Float,
        heightPt: Float,
        transform: Matrix3,
        points: List<PagePoint>?,
        toPage: PagePoint,
        straightenTolerancePt: Float,
    ): BendDrag? {
        val inverse = transform.inverse() ?: return null
        val local = inverse.map(toPage)
        val (a, b) = ShapeGeometry.endpointsOf(widthPt, heightPt, points)
        val footLocal = footOnChord(a, b, local) ?: return null
        val foot = transform.map(footLocal)
        val straightened = hypot(toPage.x - foot.x, toPage.y - foot.y) < straightenTolerancePt
        return BendDrag(preview = if (straightened) footLocal else local, stored = if (straightened) null else local)
    }

    /** Where [point] lands when dropped perpendicularly onto the infinite line `a → b`. */
    private fun footOnChord(a: PagePoint, b: PagePoint, point: PagePoint): PagePoint? {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared < EPSILON) return null
        val along = ((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared
        return PagePoint(a.x + dx * along, a.y + dy * along)
    }

    private fun midpointOf(a: PagePoint, b: PagePoint) = PagePoint((a.x + b.x) / 2f, (a.y + b.y) / 2f)

    private fun vertex(index: Int, x: Float, y: Float) = ShapePoint(index, PagePoint(x, y), ShapePointRole.VERTEX)

    /**
     * How near the chord a bend counts as straight, on the glass.
     *
     * A finger's width, deliberately: overshooting into a barely-visible curve is a line the user
     * believes is straight and a peer renders as a cubic, and the cost of the snap is that a genuine
     * 4 dp bulge cannot be authored — which is not a curve anybody is drawing on purpose.
     */
    public const val STRAIGHTEN_DP: Float = 14f

    /** A segment's handles, in [of]'s order. The bend is between the ends, not after them. */
    private const val LINE_START = 0
    private const val LINE_BEND = 1
    private const val LINE_END = 2

    /** A closed outline needs three vertices. */
    private const val MIN_VERTICES = 3

    private const val EPSILON = 1e-6f
}

/**
 * One editable point of a shape, in the shape's **own** space.
 *
 * ⚠️ [index] is the identity, not the position: a gesture reads the handle at pen-down and asks for
 * its meaning at every frame after, by which time [local] may be several frames stale for a handle
 * that moves. Everything that has to be re-derived is re-derived from the index.
 */
public data class ShapePoint(val index: Int, val local: PagePoint, val role: ShapePointRole)

/**
 * What a handle does when dragged — and therefore which register it writes.
 *
 * The distinction is not cosmetic: a [VERTEX] writes [ShapeRecord.points] and a [BEND] writes
 * [ShapeRecord.bend]. They merge independently (SPEC §4.8.2), and a caller that treated them alike
 * would silently write the wrong one.
 */
public enum class ShapePointRole {
    /** A point of the outline. Dragging it moves that point and nothing else. */
    VERTEX,

    /** ⭐ The middle of a segment. Dragging it curves the line — see [ShapeRecord.bend]. */
    BEND,
}

/**
 * ⭐ What a drag of the bend handle means — **what to draw** and **what to store**, which are not
 * the same value and must not be told apart twice.
 *
 * ## The bug this type exists to make unrepresentable
 *
 * [ShapePoints.bend] used to answer a single nullable point: the bend, or null once the drag had
 * come within the straighten tolerance of the chord. The renderer's preview took that same nullable
 * and read null as *"no preview"* — so the moment a curve was flattened, the shape fell back to the
 * bend in its **record** and sprang back to its original arc with the finger still down. Reported
 * exactly: *"if you move that center point to the middle so it would act more like a straight line
 * it snapes to its original curve point which is quite weird"*.
 *
 * One nullable carrying two meanings — *"straight"* to the register and *"nothing to say"* to the
 * renderer — is the whole of it. [preview] is therefore never null: a straightened drag previews the
 * **foot of the perpendicular**, which draws straight ([ShapeGeometry.outline] renders a bend on the
 * chord as a line) and leaves the handle under the finger instead of snapping it to the middle.
 * [stored] is null for straight, because straight is *no bend* in the record and not a point that
 * happens to lie on the chord.
 */
public data class BendDrag(val preview: PagePoint, val stored: PagePoint?)
