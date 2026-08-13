// The numbers in this file are geometry, not configuration: the 3 in a cubic's Bernstein basis
// and the 2 in a half-extent are the formula. Naming them would make each expression harder to
// check against the shape it draws, which is the only thing that matters here.
@file:Suppress("MagicNumber")

package dev.inkstone.core.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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

    /** A filled shape needs an interior. A line has none, whatever colour you ask for. */
    public fun isFillable(kind: ShapeKind): Boolean = when (kind) {
        ShapeKind.LINE, ShapeKind.ARROW -> false
        ShapeKind.RECTANGLE, ShapeKind.ELLIPSE, ShapeKind.TRIANGLE -> true
    }

    /**
     * ⚠️ **Only a rectangle rounds.** An ellipse has no corners, and a triangle's corners meet at
     * angles a single radius cannot round consistently — an equilateral apex and a 15° sliver want
     * visibly different radii to look equally rounded, and picking one makes the sliver disappear.
     * ROADMAP's *"rounded corners"* is therefore a rectangle feature, and asking for one anywhere
     * else is ignored rather than approximated.
     */
    public fun roundsCorners(kind: ShapeKind): Boolean = kind == ShapeKind.RECTANGLE

    /**
     * [kind]'s outline in its own space.
     *
     * @param cornerRadiusPt clamped to half the shorter side, so a large radius degrades into a
     *   stadium rather than into self-intersecting arcs. Ignored where [roundsCorners] is false.
     */
    public fun outline(kind: ShapeKind, widthPt: Float, heightPt: Float, cornerRadiusPt: Float = 0f): ShapePath {
        val w = max(widthPt, 0f)
        val h = max(heightPt, 0f)
        return when (kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> segment(w, h)
            ShapeKind.RECTANGLE -> rectangle(w, h, cornerRadiusPt)
            ShapeKind.ELLIPSE -> ellipse(w, h)
            ShapeKind.TRIANGLE -> triangle(w, h)
        }
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

    private fun segment(w: Float, h: Float): ShapePath {
        val y = h / 2f
        return ShapePath(listOf(ShapeSegment.MoveTo(0f, y), ShapeSegment.LineTo(w, y)), closed = false)
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

    /** Apex at the top edge's midpoint, base along the bottom — the orientation a drag implies. */
    private fun triangle(w: Float, h: Float): ShapePath = ShapePath(
        listOf(
            ShapeSegment.MoveTo(w / 2f, 0f),
            ShapeSegment.LineTo(w, h),
            ShapeSegment.LineTo(0f, h),
            ShapeSegment.Close,
        ),
        closed = true,
    )

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
