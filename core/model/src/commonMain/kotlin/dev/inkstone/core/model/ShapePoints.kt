// Same reason as `Shapes.kt` and `ShapeSnap.kt` next door: the halves and quarters here are the
// geometry of a box's own corners and diameters, not configuration. `w / 2f` says where the top
// edge's midpoint is; a constant named HALF would say less and would have to be checked against the
// shape it describes anyway. The two numbers that *are* tuned — the straighten tolerance and the
// minimum segment — are named constants with their reasoning attached.
@file:Suppress("MagicNumber")

package dev.inkstone.core.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * ⭐ **Editing a shape by its points** — where the handles are, and what dragging one writes.
 *
 * A selection frame can move, scale and rotate a shape as a lump. That is the wrong instrument for
 * a shape whose *points* are the thing being drawn: a triangle cannot be made into a different
 * triangle by scaling it, an axis-aligned line's frame has no height to grab, and a rotated
 * rectangle's frame is not even the rectangle — it is the axis-aligned box around it, so its
 * corners sit off the shape entirely ([SelectionHandle]'s own KDoc in `:android:ink` says why the
 * frame is built that way, and why that is right *for a frame*).
 *
 * ## ⭐ The one idea: three points **are** an affine map, so nothing new needs storing
 *
 * A [ShapeObject] is a kind, a local box, and a [Matrix3] that places it (SPEC §4.8). A 2-D affine
 * map is determined by exactly three point correspondences — so for a triangle, *"put this vertex
 * there and leave the other two"* has one answer, and that answer is a matrix. Every drag here is
 * therefore solved as **a new placement**, and the op it writes is the ordinary absolute
 * `SetTransform` M2 froze into the vocabulary. No new op type, no new object kind, no geometry
 * duplicated beside the payload — and undo, the merge matrix, the PDF and a peer's copy all keep
 * working because none of them can tell this apart from a drag of the whole shape.
 *
 * The one thing that genuinely is not expressible as a placement is the **curve**, because a
 * straight segment and a bent one are different paths and no affine map turns one into the other.
 * That is the single point of new state, and it lives in [ShapeRecord.bend] — see that field for
 * why a sixth [ShapeKind] was not available.
 *
 * ## What each kind offers, and why the rules differ
 *
 * | Kind | Handles | A drag solves |
 * |---|---|---|
 * | [ShapeKind.TRIANGLE] | 3 vertices | the exact affine — the other two vertices are pinned |
 * | [ShapeKind.LINE] / [ShapeKind.ARROW] | 2 ends + 1 bend | a **similarity** about the far end; the bend writes [ShapeRecord] |
 * | [ShapeKind.RECTANGLE] | 4 corners | a scale on **both** local axes about the opposite corner |
 * | [ShapeKind.ELLIPSE] | 4 quadrant points | a scale on **one** local axis about the opposite point |
 *
 * ⚠️ Only the triangle gets the free affine, and the restriction is the feature rather than a
 * shortcut. A rectangle has four corners and an affine has three degrees of freedom, so *"drag one
 * corner, pin the other three"* has **no** solution — the fourth corner is `B + D − A` by
 * construction. Solving it as a free affine instead would silently turn every rectangle into a
 * parallelogram the moment a corner was touched. Scaling the shape's own axes keeps the right
 * angles, and because those axes are the *shape's* and not the page's, the dragged corner still
 * lands exactly under the finger at any rotation.
 *
 * An ellipse's quadrant point is the same solve with one axis held: it can only travel along its
 * own diameter, so the finger's sideways component is dropped rather than sheared in. That is an
 * oblique projection onto the shape's own frame, which for the un-skewed matrices this app writes
 * is the perpendicular one.
 *
 * ## Where this runs
 *
 * `:core:model`, and for CLAUDE.md definition-of-done #6's reason: *"hoist the decision into a pure
 * function over plain data so a test can at least assert the decision was made."* The gesture is a
 * `MotionEvent` in `:android:ink`; the arithmetic that turns it into a matrix is here, where a JVM
 * test, the iOS target and SPEC §13.2's merge matrix can all reach it — the same split
 * [SelectionEdit] and [ShapeEdit] are built on.
 *
 * Nothing here is on the ink path. A handle drag is a selection gesture, not a stroke.
 */
public object ShapePoints {

    /**
     * The handles [kind] offers, in the order a UI should draw them.
     *
     * @param bendLocal the live [ShapeRecord.bend]. A straight line's bend handle sits at the
     *   **middle of the chord**, which is the whole affordance: the way to make a curve is to grab
     *   the middle of a line and pull, and a handle that only appeared once the line was already
     *   curved would be a feature nobody could find.
     * @return empty for a degenerate box — a shape with no extent has no points worth grabbing, and
     *   every drag rule below would divide by its zero.
     */
    public fun of(kind: ShapeKind, widthPt: Float, heightPt: Float, bendLocal: PagePoint? = null): List<ShapePoint> {
        val w = widthPt
        val h = heightPt
        if (w < ShapeGeometry.MIN_EXTENT_PT || h < ShapeGeometry.MIN_EXTENT_PT) return emptyList()
        return when (kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> listOf(
                vertex(0, 0f, h / 2f),
                ShapePoint(1, bendLocal ?: PagePoint(w / 2f, h / 2f), ShapePointRole.BEND),
                vertex(2, w, h / 2f),
            )

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

            ShapeKind.TRIANGLE -> triangleVertices(w, h)
        }
    }

    /**
     * ⭐ The page-space gesture a drag of [point] to [toPage] describes.
     *
     * Composed on the **left** of the object's own matrix by [SelectionEdit.transform], exactly as
     * a move or a resize is, so a point edit is one absolute `SetTransform` and one undo entry.
     *
     * ⚠️ [transform] must be the placement the shape had when the finger went **down**, not the one
     * it has this frame. A live drag previews `gesture × start` and commits `gesture × start` for
     * the same start; feeding back the previewed matrix would apply the drag twice per frame, which
     * looks like acceleration and writes a matrix nobody asked for. `SelectionController` captures
     * it in `begin` for this reason.
     *
     * @return null when the drag cannot be expressed — a [ShapePointRole.BEND] handle (which writes
     *   [bend] instead), a placement that is already singular, or a drag onto the pinned point
     *   itself. Refusing is deliberate: [SelectionEdit.transform] would refuse the resulting
     *   degenerate matrix anyway, and a shape collapsed to a line cannot be grabbed again.
     */
    public fun gesture(
        kind: ShapeKind,
        widthPt: Float,
        heightPt: Float,
        transform: Matrix3,
        point: ShapePoint,
        toPage: PagePoint,
    ): Matrix3? {
        if (point.role == ShapePointRole.BEND) return null
        val handles = of(kind, widthPt, heightPt)
        val moving = handles.getOrNull(point.index)?.takeIf { it.role == ShapePointRole.VERTEX } ?: return null
        return when (kind) {
            // The far end is the pivot, and the whole local frame turns and scales with the drag —
            // so a bend, which is a point in that frame, keeps its shape relative to the line
            // instead of being left behind by its own chord.
            ShapeKind.LINE, ShapeKind.ARROW -> similarity(
                transform,
                // The *other* end. Spelled out rather than derived by arithmetic on the index,
                // because a segment's three handles are not a cycle the way a box's four are — the
                // bend sits between them and would be the answer to any `(index + n) % size`.
                pin = handles[if (point.index == 0) LINE_END else LINE_START].local,
                moving = moving.local,
                to = toPage,
            )

            ShapeKind.RECTANGLE -> axes(
                transform,
                pin = handles[(point.index + OPPOSITE) % CORNERS].local,
                moving = moving.local,
                to = toPage,
                freeX = true,
                freeY = true,
            )

            ShapeKind.ELLIPSE -> axes(
                transform,
                pin = handles[(point.index + OPPOSITE) % CORNERS].local,
                moving = moving.local,
                to = toPage,
                // The vertical pair (top, bottom) may only travel vertically, and the horizontal
                // pair horizontally: a quadrant point is on a diameter and that is the axis it owns.
                freeX = point.index % 2 == 1,
                freeY = point.index % 2 == 0,
            )

            ShapeKind.TRIANGLE -> affine(
                transform,
                locals = triangleVertices(widthPt, heightPt).map { it.local },
                moved = point.index,
                to = toPage,
            )
        }
    }

    /**
     * ⭐ The new [ShapeRecord.bend] a drag of the middle handle to [toPage] describes, in the
     * shape's own space — **or null for straight**, which is a value the record stores as "no
     * curve" rather than as a bend that happens to lie on the chord.
     *
     * @param straightenTolerancePt how near the chord counts as straight, in **page** points. The
     *   offset is measured in page space rather than in the shape's own, so the tolerance is a real
     *   distance on the paper whatever the placement scales by — the conversion `AlignmentGuides`
     *   and `SelectionController.snapTolerance` both got wrong once, in the same direction.
     *
     *   Without this the only way back to a straight line would be to land the handle on the chord
     *   to within a float, and the honest UI for that would have to be a "straighten" button —
     *   a chip on an already-crowded toolbar for something the gesture can say by itself.
     *
     * ⚠️ **No `widthPt`, deliberately.** The bend is not clamped along the chord: dragging the
     * middle handle past either end is a legitimate S-less curve and clamping it would fight a
     * gesture nobody has complained about. A parameter that exists to be ignored is the shape of
     * bug CLAUDE.md's *"when a default argument can lose data, delete the parameter"* is about,
     * one step milder.
     */
    public fun bend(heightPt: Float, transform: Matrix3, toPage: PagePoint, straightenTolerancePt: Float): PagePoint? {
        val inverse = transform.inverse() ?: return null
        val local = inverse.map(toPage)
        // The chord is horizontal in local space by construction (ShapeGeometry's segment runs
        // (0, h/2) → (w, h/2)), so the foot of the perpendicular is this and nothing has to be
        // projected. Mapped back out, because the tolerance is a distance on the glass.
        val onChord = transform.map(PagePoint(local.x, heightPt / 2f))
        if (hypot(toPage.x - onChord.x, toPage.y - onChord.y) < straightenTolerancePt) return null
        return local
    }

    // -----------------------------------------------------------------------
    // The three solves
    // -----------------------------------------------------------------------

    /**
     * Rotate and uniformly scale about [pin] so that [moving] lands on [to].
     *
     * Uniform, so a line keeps its local band's proportions and a bend in that band scales with the
     * chord instead of staying the same size as the line grows. [MIN_SEGMENT_PT] stops a line being
     * dragged into a point; a drag *onto* the pivot has no direction at all and is refused.
     */
    private fun similarity(transform: Matrix3, pin: PagePoint, moving: PagePoint, to: PagePoint): Matrix3? {
        val anchor = transform.map(pin)
        val from = transform.map(moving)
        val fromX = from.x - anchor.x
        val fromY = from.y - anchor.y
        val length = hypot(fromX, fromY)
        if (length < EPSILON) return null
        val toX = to.x - anchor.x
        val toY = to.y - anchor.y
        val target = hypot(toX, toY)
        if (target < EPSILON) return null
        val scale = max(target, MIN_SEGMENT_PT) / length
        val angle = atan2(toY, toX) - atan2(fromY, fromX)
        return Matrix3.translate(anchor.x, anchor.y) *
            Matrix3.rotate(angle) *
            Matrix3.scale(scale) *
            Matrix3.translate(-anchor.x, -anchor.y)
    }

    /**
     * Scale the shape's **own** axes about [pin] so that [moving] lands on [to].
     *
     * The drag is resolved into the placement's own basis — `U = (m00, m10)` and `V = (m01, m11)`,
     * the images of the local unit axes — which is what makes the answer rotation-invariant: at any
     * angle, a corner drag scales along the edges the user can see rather than along the page's.
     *
     * @param freeX whether the local x extent may change; likewise [freeY]. Both for a rectangle's
     *   corner, one for an ellipse's quadrant point.
     */
    // A pin, a mover, a target and which axes are free. A holder type would hide the solve.
    @Suppress("LongParameterList")
    private fun axes(
        transform: Matrix3,
        pin: PagePoint,
        moving: PagePoint,
        to: PagePoint,
        freeX: Boolean,
        freeY: Boolean,
    ): Matrix3? {
        val determinant = transform.determinant
        if (abs(determinant) < EPSILON) return null
        val inverse = transform.inverse() ?: return null
        val anchor = transform.map(pin)
        val dx = to.x - anchor.x
        val dy = to.y - anchor.y
        // (dx, dy) in the (U, V) basis: the coefficients α, β with α·U + β·V = (dx, dy).
        val alpha = (dx * transform.m11 - dy * transform.m01) / determinant
        val beta = (dy * transform.m00 - dx * transform.m10) / determinant
        val spanX = moving.x - pin.x
        val spanY = moving.y - pin.y
        val scaleX = if (freeX && abs(spanX) > EPSILON) alpha / spanX else 1f
        val scaleY = if (freeY && abs(spanY) > EPSILON) beta / spanY else 1f
        // Clamped positive rather than allowed through zero: dragging a corner past its pin would
        // otherwise mirror the shape, which is a second gesture nobody asked this one to perform,
        // and zero is the singular matrix `SelectionEdit.transform` refuses to write.
        val local = Matrix3.translate(pin.x, pin.y) *
            Matrix3.scale(scaleX.coerceAtLeast(MIN_SCALE), scaleY.coerceAtLeast(MIN_SCALE)) *
            Matrix3.translate(-pin.x, -pin.y)
        return transform * local * inverse
    }

    /**
     * The exact affine that moves vertex [moved] to [to] and leaves the others where they are.
     *
     * Three correspondences, three degrees of freedom, one answer — see the class KDoc. The result
     * is generally **skewed**, which is correct and is why [Matrix3.isSkewed] is a diagnostic rather
     * than a guard: a triangle dragged into a different triangle *is* a sheared triangle.
     */
    private fun affine(transform: Matrix3, locals: List<PagePoint>, moved: Int, to: PagePoint): Matrix3? {
        val pages = locals.mapIndexed { index, local -> if (index == moved) to else transform.map(local) }
        val placed = affineThrough(locals, pages) ?: return null
        if (placed.isDegenerate()) return null
        val inverse = transform.inverse() ?: return null
        return placed * inverse
    }

    /** The affine mapping each of [locals] onto the matching [pages], or null for a flat triangle. */
    private fun affineThrough(locals: List<PagePoint>, pages: List<PagePoint>): Matrix3? {
        val e1x = locals[1].x - locals[0].x
        val e1y = locals[1].y - locals[0].y
        val e2x = locals[2].x - locals[0].x
        val e2y = locals[2].y - locals[0].y
        val determinant = e1x * e2y - e2x * e1y
        if (abs(determinant) < EPSILON) return null
        val f1x = pages[1].x - pages[0].x
        val f1y = pages[1].y - pages[0].y
        val f2x = pages[2].x - pages[0].x
        val f2y = pages[2].y - pages[0].y
        // The linear part is F·E⁻¹; the translation is whatever then lands vertex 0 on its target.
        val m00 = (f1x * e2y - f2x * e1y) / determinant
        val m01 = (f2x * e1x - f1x * e2x) / determinant
        val m10 = (f1y * e2y - f2y * e1y) / determinant
        val m11 = (f2y * e1x - f1y * e2x) / determinant
        return Matrix3(
            m00 = m00,
            m01 = m01,
            m02 = pages[0].x - (m00 * locals[0].x + m01 * locals[0].y),
            m10 = m10,
            m11 = m11,
            m12 = pages[0].y - (m10 * locals[0].x + m11 * locals[0].y),
        )
    }

    // -----------------------------------------------------------------------

    /** Apex at the top edge's midpoint, base along the bottom — [ShapeGeometry]'s own triangle. */
    private fun triangleVertices(widthPt: Float, heightPt: Float): List<ShapePoint> = listOf(
        vertex(0, widthPt / 2f, 0f),
        vertex(1, widthPt, heightPt),
        vertex(2, 0f, heightPt),
    )

    private fun vertex(index: Int, x: Float, y: Float) = ShapePoint(index, PagePoint(x, y), ShapePointRole.VERTEX)

    /**
     * How near the chord a bend counts as straight, on the glass.
     *
     * A finger's width, deliberately: overshooting into a barely-visible curve is a line the user
     * believes is straight and a peer renders as a cubic, and the cost of the snap is that a
     * genuine 4 dp bulge cannot be authored — which is not a curve anybody is drawing on purpose.
     */
    public const val STRAIGHTEN_DP: Float = 14f

    /** A segment's handles, in [of]'s order: start, bend, end. */
    private const val LINE_START = 0
    private const val LINE_END = 2

    /** Opposite corner of a four-cycle: index + 2, wrapped. */
    private const val OPPOSITE = 2
    private const val CORNERS = 4

    /** A line may not be dragged shorter than this, in page points. */
    private const val MIN_SEGMENT_PT = 4f

    /** A corner drag may not scale an axis below this fraction of what it was. */
    private const val MIN_SCALE = 0.02f

    private const val EPSILON = 1e-6f
}

/**
 * One editable point of a shape, in the shape's **own** space.
 *
 * ⚠️ [index] is the identity, not the position: a gesture reads the handle at pen-down and asks
 * [ShapePoints.gesture] for a matrix at every frame after, by which time [local] may be several
 * frames stale for a handle that moves (the bend's does). Everything that has to be re-derived is
 * re-derived from the index.
 */
public data class ShapePoint(val index: Int, val local: PagePoint, val role: ShapePointRole)

/**
 * What a handle does when dragged — and therefore what it writes.
 *
 * The distinction is not cosmetic: a [VERTEX] writes `SetTransform`, a [BEND] writes
 * `SetTextContent`. They are different registers, they merge independently (SPEC §4.8.2), and a
 * caller that treated them alike would silently write the wrong one.
 */
public enum class ShapePointRole {
    /** A point of the outline. Dragging it re-places the shape. */
    VERTEX,

    /** ⭐ The middle of a segment. Dragging it curves the line — see [ShapeRecord.bend]. */
    BEND,
}
