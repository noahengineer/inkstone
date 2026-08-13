package dev.inkstone.core.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ROADMAP M8b — *"does the eraser's swept tip touch this shape"*, and *"is this point on it"*.
 *
 * ## Why this is here and not in the renderer
 *
 * `DryStroke` answers the same question by handing the swept tip to `androidx.ink`'s native
 * coverage test, because a stroke's painted area is a tessellated mesh and only that library can
 * measure it. A shape's painted area is a handful of line and cubic segments, so the answer is
 * arithmetic — and arithmetic that decides whether an eraser bites belongs where a JVM test and
 * the iOS target can reach it (CLAUDE.md definition-of-done #6).
 *
 * It is also the *same* predicate the tap test and the lasso need, and keeping one implementation
 * is what stops a shape you can erase from being a shape you cannot select.
 *
 * ## What "touching" means
 *
 * The eraser sweeps a **capsule**: the segment its tip travelled, fattened by the tip radius. A
 * shape is hit when that capsule reaches its **outline** — within the tip radius plus half the
 * pen width, because what the user aims at is the drawn line and the drawn line has thickness —
 * or, for a filled shape, when the capsule's centre lands **inside** it.
 *
 * ⚠️ **The fill matters and is not an optimisation.** Without it, dragging an eraser through the
 * middle of a filled rectangle would pass straight through: every outline segment is far away, and
 * the user is looking at solid colour. An unfilled shape deliberately keeps that behaviour — you
 * can erase *through* a hollow box without touching it, which is what its emptiness looks like.
 *
 * Everything here is in one space; the caller maps into the object's own space first, exactly as
 * the eraser already does for a transformed stroke (SPEC §3.5, M8a).
 */
public object ShapeHit {

    /**
     * True when the capsule from `(fromX, fromY)` to `(toX, toY)` with [radius] touches [path].
     *
     * @param filled whether the shape's interior is painted — see the class KDoc on why this
     *   changes the answer rather than merely speeding it up.
     * @param slop half the painted line width, added to [radius]. The outline is a mathematical
     *   curve and the ink on it is not.
     */
    @Suppress("LongParameterList") // A capsule is four coordinates and a radius; splitting it hides it.
    public fun hitsCapsule(
        path: ShapePath,
        filled: Boolean,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        radius: Float,
        slop: Float = 0f,
    ): Boolean {
        val points = path.flattened()
        if (points.isEmpty()) return false
        val reach = radius + slop
        val last = if (path.closed) points.size else points.size - 1
        for (index in 0 until last) {
            val a = points[index]
            val b = points[(index + 1) % points.size]
            if (segmentDistance(a.x, a.y, b.x, b.y, fromX, fromY, toX, toY) <= reach) return true
        }
        if (!filled || !path.closed) return false
        // The capsule's own ends, because a short dab inside a filled shape has no segment near
        // any edge and is still unmistakably on it.
        return contains(points, fromX, fromY) || contains(points, toX, toY)
    }

    /** True when `(x, y)` is on [path] — inside it when [filled], otherwise within [reach] of it. */
    public fun hitsPoint(path: ShapePath, filled: Boolean, x: Float, y: Float, reach: Float): Boolean =
        hitsCapsule(path, filled, x, y, x, y, radius = 0f, slop = reach)

    /**
     * Even-odd ray casting, the same rule and the same half-open edge test as [Lasso.contains].
     *
     * Deliberately the same arithmetic: a point the lasso would call inside and the tap test would
     * call outside is a shape you can select but cannot tap, and nobody would ever find out why.
     */
    public fun contains(polygon: List<PagePoint>, x: Float, y: Float): Boolean {
        if (polygon.size < MIN_POLYGON_POINTS) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val yi = polygon[i].y
            val yj = polygon[j].y
            if ((yi > y) != (yj > y)) {
                val crossX = polygon[i].x + (y - yi) / (yj - yi) * (polygon[j].x - polygon[i].x)
                if (x < crossX) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * The shortest distance between segment `p→q` and segment `r→s`.
     *
     * Sampled rather than solved: the closed form needs four sub-cases for parallel and degenerate
     * inputs, and both of those are routine here — an eraser that has not moved since the last
     * sample is a zero-length segment, and a rectangle's edges are parallel to most flicks. The
     * sample count is small because a shape has tens of segments where a stroke has thousands.
     */
    @Suppress("LongParameterList") // Two segments. Eight floats is what that is.
    private fun segmentDistance(
        px: Float,
        py: Float,
        qx: Float,
        qy: Float,
        rx: Float,
        ry: Float,
        sx: Float,
        sy: Float,
    ): Float {
        var best = Float.MAX_VALUE
        for (step in 0..SAMPLES) {
            val t = step.toFloat() / SAMPLES
            best = min(best, pointToSegment(px + (qx - px) * t, py + (qy - py) * t, rx, ry, sx, sy))
            best = min(best, pointToSegment(rx + (sx - rx) * t, ry + (sy - ry) * t, px, py, qx, qy))
        }
        return best
    }

    private fun pointToSegment(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared < EPSILON) return hypotenuse(x - ax, y - ay)
        val t = (((x - ax) * dx + (y - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypotenuse(x - (ax + dx * t), y - (ay + dy * t))
    }

    /** `kotlin.math.hypot` is not in common Kotlin for Float on every target; this is. */
    private fun hypotenuse(dx: Float, dy: Float): Float {
        val ax = abs(dx)
        val ay = abs(dy)
        if (ax < EPSILON && ay < EPSILON) return 0f
        val large = max(ax, ay)
        val small = min(ax, ay)
        val ratio = small / large
        return large * kotlin.math.sqrt(1f + ratio * ratio)
    }

    private const val SAMPLES = 4
    private const val EPSILON = 1e-6f
    private const val MIN_POLYGON_POINTS = 3
}
