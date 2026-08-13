package dev.inkstone.core.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * SPEC §4.2 — the **absolute** transform an object carries.
 *
 * A 2D affine transform, stored as the top two rows of a 3×3 matrix (the third
 * row is always `0 0 1`, so keeping it would be six wasted bytes per op):
 *
 * ```
 * | m00 m01 m02 |     x' = m00·x + m01·y + m02
 * | m10 m11 m12 |     y' = m10·x + m11·y + m12
 * |  0   0   1  |
 * ```
 *
 * ## Why absolute, and why this type exists at all
 *
 * `SetTransform` carries a whole matrix rather than a delta because deltas do not
 * commute and the failure is silent (SPEC §4.2, CLAUDE.md rule 8):
 *
 * > Device A scales object Y by 2×. Device B concurrently rotates Y by 45°. A then
 * > undoes with an inverse-scale op. The total order is `scale, rotate, unscale` —
 * > the rotation is smeared by the non-commuting scale and Y ends up skewed.
 * > Consistently, on both devices, with no error anywhere.
 *
 * With absolute matrices there is nothing to smear: concurrent writes are an LWW
 * register, and undo writes back the previous matrix. `PageFoldTest` reproduces
 * the worked example above and asserts the object is one of the two candidate
 * matrices rather than their skewed product.
 *
 * Not to be confused with the row-major `FloatArray` that `Camera.pageToViewAffine`
 * produces. That is a rendering detail on the ink path with a caller-supplied
 * buffer, deliberately allocation-free; this is a persisted value.
 */
public data class Matrix3(
    val m00: Float,
    val m01: Float,
    val m02: Float,
    val m10: Float,
    val m11: Float,
    val m12: Float,
) {

    /** `(a * b).map(p) == a.map(b.map(p))` — [other] is applied first. */
    public operator fun times(other: Matrix3): Matrix3 = Matrix3(
        m00 = m00 * other.m00 + m01 * other.m10,
        m01 = m00 * other.m01 + m01 * other.m11,
        m02 = m00 * other.m02 + m01 * other.m12 + m02,
        m10 = m10 * other.m00 + m11 * other.m10,
        m11 = m10 * other.m01 + m11 * other.m11,
        m12 = m10 * other.m02 + m11 * other.m12 + m12,
    )

    public fun map(p: PagePoint): PagePoint = PagePoint(
        x = m00 * p.x + m01 * p.y + m02,
        y = m10 * p.x + m11 * p.y + m12,
    )

    public val determinant: Float get() = m00 * m11 - m01 * m10

    /** Null for a singular matrix — a degenerate transform is not invertible. */
    public fun inverse(): Matrix3? {
        val det = determinant
        if (abs(det) < EPSILON) return null
        val inverseDet = 1f / det
        val i00 = m11 * inverseDet
        val i01 = -m01 * inverseDet
        val i10 = -m10 * inverseDet
        val i11 = m00 * inverseDet
        return Matrix3(
            m00 = i00,
            m01 = i01,
            m02 = -(i00 * m02 + i01 * m12),
            m10 = i10,
            m11 = i11,
            m12 = -(i10 * m02 + i11 * m12),
        )
    }

    /**
     * True when the transform's two axes are no longer perpendicular — the
     * signature of a delta-composition bug (SPEC §4.2's worked example), and the
     * reason this is on the type rather than in a test helper.
     *
     * Normalised by the axis lengths, so the answer is the cosine of the angle
     * between them and does not drift with scale: a 100× scale is not 100× more
     * skewed than the same shear at 1×.
     */
    public fun isSkewed(tolerance: Float = SKEW_TOLERANCE): Boolean {
        val lengthX = hypot(m00, m10)
        val lengthY = hypot(m01, m11)
        if (lengthX < EPSILON || lengthY < EPSILON) return false
        return abs(m00 * m01 + m10 * m11) / (lengthX * lengthY) > tolerance
    }

    /**
     * True when the two axes have become parallel, or one of them has vanished — the
     * transform has collapsed the object onto a line or a point.
     *
     * Added in M8a, and here rather than in the selection code for the same reason
     * [isSkewed] is: it is a property of the matrix, and the one caller that must refuse to
     * *write* such a matrix ([SelectionEdit.transform]) is not the only one that cares —
     * [inverse] answers null for one, and the inverse is what maps an eraser's page-space
     * sweep into a transformed object's own space.
     *
     * Normalised by the axis lengths, so it asks about *shape* and not about size: a 0.01×
     * uniform scale is a legible thumbnail and must not be refused, while a 1000×-wide
     * zero-height scale must.
     */
    public fun isDegenerate(tolerance: Float = DEGENERATE_TOLERANCE): Boolean {
        val lengthX = hypot(m00, m10)
        val lengthY = hypot(m01, m11)
        if (lengthX < DEGENERATE_AXIS || lengthY < DEGENERATE_AXIS) return true
        return abs(determinant) / (lengthX * lengthY) < tolerance
    }

    public companion object {
        public val Identity: Matrix3 = Matrix3(1f, 0f, 0f, 0f, 1f, 0f)

        public fun translate(tx: Float, ty: Float): Matrix3 = Matrix3(1f, 0f, tx, 0f, 1f, ty)

        public fun scale(sx: Float, sy: Float = sx): Matrix3 = Matrix3(sx, 0f, 0f, 0f, sy, 0f)

        /** [radians] counter-clockwise in page space's y-down convention. */
        public fun rotate(radians: Float): Matrix3 {
            val c = cos(radians)
            val s = sin(radians)
            return Matrix3(c, -s, 0f, s, c, 0f)
        }

        private const val EPSILON = 1e-9f
        private const val SKEW_TOLERANCE = 1e-4f

        /** An axis shorter than this is gone, whatever the determinant says about the pair. */
        private const val DEGENERATE_AXIS = 1e-6f
        private const val DEGENERATE_TOLERANCE = 1e-4f
    }
}
