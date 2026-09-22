// The numbers in this file are geometry, not configuration: the 3 in a cubic's Bernstein basis
// and the 2 in a half-extent are the formula. Naming them would make each expression harder to
// check against the shape it draws, which is the only thing that matters here.
@file:Suppress("MagicNumber")

package dev.inkstone.core.model

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * ROADMAP M8b — a [ShapeKind]'s outline, as plain arithmetic in the shape's **own** space.
 *
 * ## Why the outline lives here and not in the renderer
 *
 * Three things draw a shape and they must not disagree: the ink surface, the library
 * thumbnail, and the published PDF. M4's lesson is what happens when they are maintained
 * separately — the PDF was *"valid, parseable, and blank"* because it was only ever verified
 * with the library that wrote it. So the outline is computed once, in platform-free
 * arithmetic, and both `android.graphics.Path` and PDF path operators are built from the same
 * [ShapePath].
 *
 * That also makes every geometric rule here reachable from a JVM test and from the iOS target,
 * which is where `:core:model` earns its constraint (SPEC §2.3).
 *
 * ## The local box
 *
 * ⭐ Every kind occupies `[0, widthPt] × [0, heightPt]` in its own space, and [InkObject.transform]
 * places it on the page. Nothing here knows about page space, and that is the property the
 * whole connector design rests on: an anchor is a point in this box, so it follows a translate,
 * a resize, a rotation and a shear for free — one affine map, no special cases (see
 * [ConnectorRouting]).
 *
 * ⚠️ **[ShapeKind.LINE] and [ShapeKind.ARROW] are the exception, and deliberately.** Their
 * segment runs `(0, h/2) → (w, h/2)`, i.e. horizontally through the middle of the box, rather
 * than along its diagonal. A diagonal reads more naturally until you try to author an
 * axis-aligned line: the box then has zero height, its placement matrix has zero determinant,
 * and `SelectionEdit.transform` correctly refuses to write a singular matrix — so the single
 * most common line a person draws would be the one kind this could not express. A horizontal
 * segment placed by a rotation is never singular.
 */
public object ShapeGeometry {

    /** Below this a box is treated as collapsed — see [ShapeKind.LINE]'s note in the class KDoc. */
    public const val MIN_EXTENT_PT: Float = 0.5f

    /** `4/3`, the cubic approximation constant for a circular arc. See [roundedPolygon]. */
    private const val ARC_CUBIC = 4f / 3f

    private const val PI_F = kotlin.math.PI.toFloat()

    /** A filled shape needs an interior. A line has none, whatever colour you ask for. */
    public fun isFillable(kind: ShapeKind): Boolean = when (kind) {
        ShapeKind.LINE, ShapeKind.ARROW -> false
        ShapeKind.RECTANGLE, ShapeKind.ELLIPSE, ShapeKind.TRIANGLE -> true
    }

    /**
     * Every closed shape with corners — which is every one except the ellipse.
     *
     * ⚠️ **This said "only a rectangle" and the reasoning was wrong.** It ran: *"a triangle's corners
     * meet at angles a single radius cannot round consistently — an equilateral apex and a 15° sliver
     * want visibly different radii to look equally rounded, and picking one makes the sliver
     * disappear."* The first half is true and the conclusion does not follow. A fillet is not
     * "a radius applied at a corner", it is *the largest circle up to that radius which fits between
     * the two edges*, and clamping it per corner is what every vector editor does: the 90° corner
     * takes the full radius, the 15° sliver takes what it has room for, and nothing disappears
     * because a corner that cannot round simply does not. See [roundedPolygon].
     *
     * An ellipse still answers false — it has no corners at all, which is a different statement
     * from having awkward ones — so the toolbar hides the control for one rather than offering a
     * button that does nothing.
     */
    public fun roundsCorners(kind: ShapeKind): Boolean = when (kind) {
        ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.ELLIPSE -> false
        ShapeKind.RECTANGLE, ShapeKind.TRIANGLE -> true
    }

    /** Only a segment bends. A box with a bend is a box — see [outline]'s [bendLocal]. */
    public fun bends(kind: ShapeKind): Boolean = kind == ShapeKind.LINE || kind == ShapeKind.ARROW

    /**
     * [kind]'s outline in its own space.
     *
     * @param cornerRadiusPt clamped to half the shorter side, so a large radius degrades into a
     *   stadium rather than into self-intersecting arcs. Ignored where [roundsCorners] is false.
     * @param bendLocal ⭐ the **on-curve midpoint** of a [ShapeKind.LINE] or [ShapeKind.ARROW], in
     *   this shape's own space — the third of [ShapePoints]' three handles. Null, or a point on the
     *   chord, draws the straight segment. Ignored where [bends] is false.
     *
     *   ⚠️ Defaulted, like [cornerRadiusPt] beside it, and CLAUDE.md's *"when a default argument
     *   can lose data, delete the parameter"* was weighed against that. It stays a default because
     *   there is exactly **one** production caller — `DryShape.plainOutline`, which passes
     *   `record.bend` — and because the failure mode is bounded in a way `NoteSession.apply`'s was
     *   not: forgetting it draws a straight line for one frame, while the bend is still in the
     *   record and still on disk. Nothing is destroyed by getting it wrong.
     */
    public fun outline(
        kind: ShapeKind,
        widthPt: Float,
        heightPt: Float,
        cornerRadiusPt: Float = 0f,
        bendLocal: PagePoint? = null,
        points: List<PagePoint>? = null,
    ): ShapePath {
        val w = max(widthPt, 0f)
        val h = max(heightPt, 0f)
        // ⭐ Edited vertices are the outline, not a modifier of one — see [ShapeRecord.points]. The
        // radius now survives them: [roundedPolygon] clamps per corner, so an arbitrary quadrilateral
        // rounds each of its four by as much as that corner has room for. This used to drop the
        // radius, on the same reasoning [roundsCorners] used to give for a triangle, and it was wrong
        // in the same way.
        if (bends(kind)) {
            val ends = endpointsOf(w, h, points)
            return segment(ends.first, ends.second, bendLocal)
        }
        points?.takeIf { it.size >= MIN_VERTICES }?.let { return polygon(kind, it, cornerRadiusPt) }
        return when (kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> error("handled above")

            ShapeKind.RECTANGLE -> rectangle(w, h, cornerRadiusPt)

            ShapeKind.ELLIPSE -> ellipse(w, h)

            // Apex at the top edge's midpoint, base along the bottom — the orientation a drag implies.
            ShapeKind.TRIANGLE ->
                roundedPolygon(listOf(PagePoint(w / 2f, 0f), PagePoint(w, h), PagePoint(0f, h)), cornerRadiusPt)
        }
    }

    /**
     * A segment's two ends in its own space — the record's, or the payload's `(0, h/2) → (w, h/2)`.
     *
     * ⚠️ **Two, not three.** The bend is not one of these: it is a separate register with its own
     * meaning (an *on-curve* point, absent when the line is straight), and folding it into the list
     * would make "straight" and "the middle vertex happens to be on the chord" the same state — the
     * collision [BendDrag] exists to keep apart.
     */
    public fun endpointsOf(widthPt: Float, heightPt: Float, points: List<PagePoint>?): Pair<PagePoint, PagePoint> {
        val stored = points?.takeIf { it.size == SEGMENT_ENDS }
        if (stored != null) return stored[0] to stored[1]
        val y = heightPt / 2f
        return PagePoint(0f, y) to PagePoint(widthPt, y)
    }

    /**
     * [kind]'s outline through explicit [points] — straight edges, except an ellipse, which stays
     * smooth.
     *
     * ⭐ **An ellipse's four seeded quadrant points reproduce [ellipse] exactly**, because the
     * smoothing constant is `KAPPA / 2` rather than the Catmull-Rom ⅙. That is not a detail: the
     * points are seeded on the *first drag*, so any other constant would re-draw the whole shape at
     * the moment the user moved one point of it — which is the complaint this feature exists to
     * answer, arriving from the other direction.
     */
    private fun polygon(kind: ShapeKind, points: List<PagePoint>, cornerRadiusPt: Float = 0f): ShapePath {
        // An ellipse stays smooth; everything else is straight edges, and straight edges round.
        if (kind != ShapeKind.ELLIPSE && cornerRadiusPt > 0f) return roundedPolygon(points, cornerRadiusPt)
        val segments = ArrayList<ShapeSegment>(points.size + 2)
        segments += ShapeSegment.MoveTo(points[0].x, points[0].y)
        if (kind == ShapeKind.ELLIPSE) {
            for (index in points.indices) {
                val previous = points[(index - 1 + points.size) % points.size]
                val start = points[index]
                val end = points[(index + 1) % points.size]
                val after = points[(index + 2) % points.size]
                segments += ShapeSegment.CubicTo(
                    start.x + (end.x - previous.x) * SMOOTHING,
                    start.y + (end.y - previous.y) * SMOOTHING,
                    end.x - (after.x - start.x) * SMOOTHING,
                    end.y - (after.y - start.y) * SMOOTHING,
                    end.x,
                    end.y,
                )
            }
        } else {
            for (index in 1 until points.size) segments += ShapeSegment.LineTo(points[index].x, points[index].y)
        }
        segments += ShapeSegment.Close
        return ShapePath(segments, closed = true)
    }

    /** The centre of [kind]'s local box — where an auto anchor measures its direction from. */
    public fun centre(widthPt: Float, heightPt: Float): PagePoint = PagePoint(widthPt / 2f, heightPt / 2f)

    /**
     * The local point at normalised `(u, v)`, where `(0,0)` is the box's top-left and `(1,1)` its
     * bottom-right.
     *
     * This is the whole of a pinned anchor. Normalised rather than absolute so that a **resize**
     * moves the anchor with the shape without anything being rewritten — which is the register
     * that must not exist (ROADMAP M8b: *"a reroute writes no op"*).
     */
    public fun pinned(widthPt: Float, heightPt: Float, u: Float, v: Float): PagePoint =
        PagePoint(u * widthPt, v * heightPt)

    /**
     * Where a ray from the box's centre toward [towardLocal] leaves [kind]'s outline, in local
     * space.
     *
     * ⭐ **Computed in the shape's own space, never in page space**, and that is what makes an auto
     * anchor correct under a rotation. The caller maps the far end of the connector *into* this
     * space, asks here, and maps the answer back out. Doing it the other way — intersecting a
     * page-space ray with a page-space bounding box — is right only while every transform is the
     * identity, which is precisely the assumption M8a caught itself making about erase capsules.
     *
     * Returns the centre for a degenerate box or a zero-length direction: an anchor that cannot be
     * placed is placed in the middle, which is visible and harmless, rather than NaN, which
     * propagates into a matrix and out to disk.
     */
    public fun boundaryToward(kind: ShapeKind, widthPt: Float, heightPt: Float, towardLocal: PagePoint): PagePoint {
        val centre = centre(widthPt, heightPt)
        if (widthPt < MIN_EXTENT_PT && heightPt < MIN_EXTENT_PT) return centre
        val dx = towardLocal.x - centre.x
        val dy = towardLocal.y - centre.y
        if (abs(dx) < EPSILON && abs(dy) < EPSILON) return centre
        return when (kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> nearestOnSegment(widthPt, heightPt, towardLocal)
            ShapeKind.RECTANGLE -> centre.plus(scaleToBox(dx, dy, widthPt / 2f, heightPt / 2f))
            ShapeKind.ELLIPSE -> centre.plus(scaleToEllipse(dx, dy, widthPt / 2f, heightPt / 2f))
            ShapeKind.TRIANGLE -> triangleBoundary(widthPt, heightPt, centre, dx, dy)
        }
    }

    // -----------------------------------------------------------------------
    // Outlines
    // -----------------------------------------------------------------------

    /**
     * The line, straight or bent.
     *
     * ⭐ **A curve is one quadratic Bézier, written as a cubic, and [bend] is a point *on* it.** Two
     * decisions are worth reading here.
     *
     * The handle is the curve's **midpoint** rather than the Bézier's control point, because a
     * control point sits off the curve and a person dragging one is aiming at a place the ink never
     * goes. The conversion is exact: a quadratic's midpoint is `(A + 2C + B) / 4`, so the control
     * point that puts the curve under the finger is `C = 2M − (A + B)/2`.
     *
     * A **quadratic** rather than a cubic with two handles, because three points is the whole of
     * what was asked for and a quadratic through three points is unique — two independent control
     * points would need four handles and would make *"pull the middle"* ambiguous. [ShapeSegment]
     * has no quadratic (PDF has no quadratic operator — see its KDoc), so the degree is raised here,
     * once, where both renderers and the PDF writer read the same answer.
     *
     * A bend on the chord is not a bend: the curve degenerates to the straight segment anyway, and
     * emitting a cubic for it would make *"is this line straight"* a question about float noise.
     */
    private fun segment(a: PagePoint, b: PagePoint, bend: PagePoint?): ShapePath {
        val start = ShapeSegment.MoveTo(a.x, a.y)
        val straight = ShapePath(listOf(start, ShapeSegment.LineTo(b.x, b.y)), closed = false)
        if (bend == null) return straight
        // Perpendicular distance from the chord — the chord is no longer horizontal now that both
        // ends are editable, so this is a cross product rather than a subtraction of `y`.
        val dx = b.x - a.x
        val dy = b.y - a.y
        val chord = sqrt(dx * dx + dy * dy)
        if (chord < EPSILON) return straight
        if (abs((bend.x - a.x) * dy - (bend.y - a.y) * dx) / chord < STRAIGHT_EPSILON_PT) return straight
        // The quadratic control point that puts the curve's midpoint exactly on the handle.
        val cx = 2f * bend.x - (a.x + b.x) / 2f
        val cy = 2f * bend.y - (a.y + b.y) / 2f
        // Degree-raise: a quadratic (A, C, B) is the cubic (A, A + ⅔(C−A), B + ⅔(C−B), B).
        return ShapePath(
            listOf(
                start,
                ShapeSegment.CubicTo(
                    a.x + THIRDS * (cx - a.x),
                    a.y + THIRDS * (cy - a.y),
                    b.x + THIRDS * (cx - b.x),
                    b.y + THIRDS * (cy - b.y),
                    b.x,
                    b.y,
                ),
            ),
            closed = false,
        )
    }

    private fun rectangle(w: Float, h: Float, radiusPt: Float): ShapePath {
        val r = radiusPt.coerceIn(0f, min(w, h) / 2f)
        if (r <= 0f) {
            return ShapePath(
                listOf(
                    ShapeSegment.MoveTo(0f, 0f),
                    ShapeSegment.LineTo(w, 0f),
                    ShapeSegment.LineTo(w, h),
                    ShapeSegment.LineTo(0f, h),
                    ShapeSegment.Close,
                ),
                closed = true,
            )
        }
        // A quarter-circle as one cubic. `KAPPA` is the standard control-point fraction; the
        // maximum radial error is about 0.02%, which at any page size is far below a pixel.
        val k = r * KAPPA
        return ShapePath(
            listOf(
                ShapeSegment.MoveTo(r, 0f),
                ShapeSegment.LineTo(w - r, 0f),
                ShapeSegment.CubicTo(w - r + k, 0f, w, r - k, w, r),
                ShapeSegment.LineTo(w, h - r),
                ShapeSegment.CubicTo(w, h - r + k, w - r + k, h, w - r, h),
                ShapeSegment.LineTo(r, h),
                ShapeSegment.CubicTo(r - k, h, 0f, h - r + k, 0f, h - r),
                ShapeSegment.LineTo(0f, r),
                ShapeSegment.CubicTo(0f, r - k, r - k, 0f, r, 0f),
                ShapeSegment.Close,
            ),
            closed = true,
        )
    }

    private fun ellipse(w: Float, h: Float): ShapePath {
        val rx = w / 2f
        val ry = h / 2f
        val kx = rx * KAPPA
        val ky = ry * KAPPA
        return ShapePath(
            listOf(
                ShapeSegment.MoveTo(rx, 0f),
                ShapeSegment.CubicTo(rx + kx, 0f, w, ry - ky, w, ry),
                ShapeSegment.CubicTo(w, ry + ky, rx + kx, h, rx, h),
                ShapeSegment.CubicTo(rx - kx, h, 0f, ry + ky, 0f, ry),
                ShapeSegment.CubicTo(0f, ry - ky, rx - kx, 0f, rx, 0f),
                ShapeSegment.Close,
            ),
            closed = true,
        )
    }

    /**
     * ⭐ A closed polygon with each corner filleted by as much as that corner can take.
     *
     * ## Why the radius is clamped per corner and not once for the shape
     *
     * A fillet at a corner is the arc of the largest circle, up to [cornerRadiusPt], that fits
     * between the two edges meeting there. How far along each edge it has to start —
     * `t = r / tan(θ/2)` for interior angle θ — grows without bound as θ closes up, so a single
     * radius applied blindly makes a sharp corner's tangent points run past the ends of its own
     * edges and the outline folds through itself. Clamping `t` to half of the shorter adjacent edge
     * and solving back for `r` is what makes *"round this triangle"* mean something for an
     * equilateral apex and a 15° sliver at the same time: each rounds as much as it has room for.
     *
     * This is the argument [roundsCorners] used to get wrong, and it is why a triangle rounds now.
     *
     * ## The arc
     *
     * One cubic per corner, with control points along the two edges at `(4/3)·tan(φ/4)·r` from the
     * tangent points, where `φ = π − θ` is the arc's sweep. ⚠️ At θ = 90° that factor is exactly
     * [KAPPA] and `t = r`, so this reduces to [rectangle]'s hand-written form — which is why the two
     * agree and why the rectangle is allowed to keep its own exact version.
     */
    private fun roundedPolygon(points: List<PagePoint>, cornerRadiusPt: Float): ShapePath {
        val n = points.size
        // The straight case is exactly what [polygon] already draws; a radius of zero there cannot
        // come back here, so this is a delegation and not a recursion.
        if (n < MIN_VERTICES || cornerRadiusPt <= 0f) return polygon(ShapeKind.RECTANGLE, points)

        // ⚠️ Every corner emits a `LineTo`, and the **first one is rewritten to a `MoveTo`** at the
        // end. Deciding it inside the loop needs a flag and a branch at two different emit sites —
        // and the branch is what made this fall foul of "one break or continue per loop".
        val segments = ArrayList<ShapeSegment>(n * 2 + 2)
        for (index in 0 until n) {
            val corner = points[index]
            val previous = points[(index - 1 + n) % n]
            val next = points[(index + 1) % n]

            val toPreviousLength = hypot(previous.x - corner.x, previous.y - corner.y)
            val toNextLength = hypot(next.x - corner.x, next.y - corner.y)
            val degenerate = toPreviousLength <= EPSILON || toNextLength <= EPSILON
            val px = if (degenerate) 0f else (previous.x - corner.x) / toPreviousLength
            val py = if (degenerate) 0f else (previous.y - corner.y) / toPreviousLength
            val nx = if (degenerate) 0f else (next.x - corner.x) / toNextLength
            val ny = if (degenerate) 0f else (next.y - corner.y) / toNextLength

            // The interior half-angle. A degenerate corner, or one folded flat back on itself, has
            // no fillet and is emitted as the sharp corner it is.
            val halfTheta = if (degenerate) 0f else acos((px * nx + py * ny).coerceIn(-1f, 1f)) / 2f
            val tanHalf = tan(halfTheta)

            if (tanHalf <= EPSILON) {
                segments += ShapeSegment.LineTo(corner.x, corner.y)
                continue
            }
            // How far along each edge the arc must start, clamped so two fillets on one edge cannot
            // overlap — then the radius solved back from what actually fitted.
            val t = min(cornerRadiusPt / tanHalf, min(toPreviousLength, toNextLength) / 2f)
            val handle = ARC_CUBIC * tan((PI_F - 2f * halfTheta) / 4f) * (t * tanHalf)
            val startX = corner.x + px * t
            val startY = corner.y + py * t
            val endX = corner.x + nx * t
            val endY = corner.y + ny * t

            segments += ShapeSegment.LineTo(startX, startY)
            segments += ShapeSegment.CubicTo(
                startX - px * handle,
                startY - py * handle,
                endX - nx * handle,
                endY - ny * handle,
                endX,
                endY,
            )
        }
        val head = segments.firstOrNull()
        if (head is ShapeSegment.LineTo) segments[0] = ShapeSegment.MoveTo(head.x, head.y)
        segments += ShapeSegment.Close
        return ShapePath(segments, closed = true)
    }

    // -----------------------------------------------------------------------
    // Boundaries
    // -----------------------------------------------------------------------

    /** The multiple of `(dx, dy)` that lands on the box's edge — whichever axis saturates first. */
    private fun scaleToBox(dx: Float, dy: Float, halfW: Float, halfH: Float): PagePoint {
        val tx = if (abs(dx) > EPSILON) halfW / abs(dx) else Float.MAX_VALUE
        val ty = if (abs(dy) > EPSILON) halfH / abs(dy) else Float.MAX_VALUE
        val t = min(tx, ty)
        return PagePoint(dx * t, dy * t)
    }

    private fun scaleToEllipse(dx: Float, dy: Float, rx: Float, ry: Float): PagePoint {
        val nx = if (rx > EPSILON) dx / rx else 0f
        val ny = if (ry > EPSILON) dy / ry else 0f
        val norm = sqrt(nx * nx + ny * ny)
        if (norm < EPSILON) return PagePoint(0f, 0f)
        return PagePoint(dx / norm, dy / norm)
    }

    /** Nearest of the three edges the centre-ray crosses. Falls back to the box for a sliver. */
    private fun triangleBoundary(w: Float, h: Float, centre: PagePoint, dx: Float, dy: Float): PagePoint {
        val apex = PagePoint(w / 2f, 0f)
        val right = PagePoint(w, h)
        val left = PagePoint(0f, h)
        var best = Float.MAX_VALUE
        for ((a, b) in listOf(apex to right, right to left, left to apex)) {
            val t = rayHitsEdge(centre, dx, dy, a, b)
            if (t != null && t in 0f..best) best = t
        }
        if (best == Float.MAX_VALUE) return centre.plus(scaleToBox(dx, dy, w / 2f, h / 2f))
        return PagePoint(centre.x + dx * best, centre.y + dy * best)
    }

    /**
     * Where the ray `centre + t·(dx, dy)` meets the segment `a → b`, as `t`, or null.
     *
     * Plain 2-D line intersection by determinants. Parallel lines answer null rather than
     * infinity, which is the same thing said in a form the caller can use.
     */
    @Suppress("LongParameterList") // Two vectors and two points; naming them into a type would not help.
    private fun rayHitsEdge(centre: PagePoint, dx: Float, dy: Float, a: PagePoint, b: PagePoint): Float? {
        val ex = b.x - a.x
        val ey = b.y - a.y
        val denominator = dx * ey - dy * ex
        if (abs(denominator) < EPSILON) return null
        val ax = a.x - centre.x
        val ay = a.y - centre.y
        val t = (ax * ey - ay * ex) / denominator
        val u = (ax * dy - ay * dx) / denominator
        return if (t >= 0f && u in 0f..1f) t else null
    }

    /** A connector attached to a line lands on the line, not on the box around it. */
    private fun nearestOnSegment(w: Float, h: Float, toward: PagePoint): PagePoint {
        val y = h / 2f
        if (w < EPSILON) return PagePoint(0f, y)
        return PagePoint(toward.x.coerceIn(0f, w), y)
    }

    private fun PagePoint.plus(other: PagePoint) = PagePoint(x + other.x, y + other.y)

    /** Circle-to-cubic control fraction: `4/3 · (√2 − 1)`. */
    private const val KAPPA = 0.5522847498f

    /** Quadratic-to-cubic control fraction — the ⅔ in `A + ⅔(C − A)`. */
    private const val THIRDS = 2f / 3f

    /**
     * How far along the neighbour-to-neighbour chord an edited ellipse's control points sit.
     *
     * `KAPPA / 2` and not Catmull-Rom's ⅙: against four points on a circle the chord `Pᵢ₊₁ − Pᵢ₋₁`
     * is twice the tangent, so halving `KAPPA` puts the control point exactly where [ellipse] puts
     * it. See [polygon].
     */
    private const val SMOOTHING = KAPPA / 2f

    /** A closed outline needs three vertices; a segment has exactly two. */
    private const val MIN_VERTICES = 3
    private const val SEGMENT_ENDS = 2

    /**
     * A bend nearer the chord than this is straight.
     *
     * ⚠️ **A numerical guard, not the user's straighten tolerance.** That one is
     * [ShapePoints.STRAIGHTEN_DP], measured on the glass and applied while the finger is still
     * down; by the time a bend reaches here it has already been through it. This only stops a
     * float's worth of offset from turning a line into a cubic that is straight to the eye and not
     * to `ShapePath.segments`.
     */
    private const val STRAIGHT_EPSILON_PT = 0.02f

    private const val EPSILON = 1e-6f
}

/**
 * One step of a [ShapePath].
 *
 * Deliberately the intersection of what `android.graphics.Path` and PDF path operators both
 * offer, and nothing more: a move, a line, a cubic, a close. Quadratics are omitted because PDF
 * has no quadratic operator, so offering one would push a conversion into `:android:pdf` that
 * the two renderers could then disagree about.
 */
public sealed interface ShapeSegment {
    public data class MoveTo(val x: Float, val y: Float) : ShapeSegment

    public data class LineTo(val x: Float, val y: Float) : ShapeSegment

    public data class CubicTo(
        val c1x: Float,
        val c1y: Float,
        val c2x: Float,
        val c2y: Float,
        val x: Float,
        val y: Float,
    ) : ShapeSegment

    public object Close : ShapeSegment
}

/**
 * A shape's outline, in whatever space the producer built it in.
 *
 * [closed] is carried rather than inferred from a trailing [ShapeSegment.Close] because it is
 * also the answer to *"may this be filled"*, and a caller asking that should not have to look at
 * the last element of a list to find out.
 */
public data class ShapePath(val segments: List<ShapeSegment>, val closed: Boolean) {

    /** This path under [transform] — used to take a local outline into page space. */
    public fun under(transform: Matrix3): ShapePath {
        if (transform == Matrix3.Identity) return this
        return ShapePath(segments.map { it.under(transform) }, closed)
    }

    private fun ShapeSegment.under(m: Matrix3): ShapeSegment = when (this) {
        is ShapeSegment.MoveTo -> m.map(PagePoint(x, y)).let { ShapeSegment.MoveTo(it.x, it.y) }

        is ShapeSegment.LineTo -> m.map(PagePoint(x, y)).let { ShapeSegment.LineTo(it.x, it.y) }

        is ShapeSegment.CubicTo -> {
            val c1 = m.map(PagePoint(c1x, c1y))
            val c2 = m.map(PagePoint(c2x, c2y))
            val end = m.map(PagePoint(x, y))
            ShapeSegment.CubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
        }

        is ShapeSegment.Close -> ShapeSegment.Close
    }

    /**
     * The path's points, flattened — cubics sampled at [samplesPerCurve] steps.
     *
     * For the lasso and the tap test, which need points rather than curves, and for the
     * page-space bounding box. The sample count is a straight accuracy/cost trade and is not
     * tuned: a shape has at most a handful of curves, against a stroke's thousands of vertices.
     */
    public fun flattened(samplesPerCurve: Int = DEFAULT_SAMPLES): List<PagePoint> {
        val points = ArrayList<PagePoint>(segments.size * samplesPerCurve)
        var cursor = PagePoint(0f, 0f)
        var start = cursor
        for (segment in segments) {
            when (segment) {
                is ShapeSegment.MoveTo -> {
                    cursor = PagePoint(segment.x, segment.y)
                    start = cursor
                    points += cursor
                }

                is ShapeSegment.LineTo -> {
                    cursor = PagePoint(segment.x, segment.y)
                    points += cursor
                }

                is ShapeSegment.CubicTo -> {
                    for (step in 1..samplesPerCurve) {
                        points += cubicAt(cursor, segment, step.toFloat() / samplesPerCurve)
                    }
                    cursor = PagePoint(segment.x, segment.y)
                }

                is ShapeSegment.Close -> {
                    points += start
                    cursor = start
                }
            }
        }
        return points
    }

    private fun cubicAt(from: PagePoint, curve: ShapeSegment.CubicTo, t: Float): PagePoint {
        val u = 1f - t
        val a = u * u * u
        val b = 3f * u * u * t
        val c = 3f * u * t * t
        val d = t * t * t
        return PagePoint(
            a * from.x + b * curve.c1x + c * curve.c2x + d * curve.x,
            a * from.y + b * curve.c1y + c * curve.c2y + d * curve.y,
        )
    }

    public companion object {
        private const val DEFAULT_SAMPLES = 12

        public val Empty: ShapePath = ShapePath(emptyList(), closed = false)
    }
}
