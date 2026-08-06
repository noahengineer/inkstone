package dev.inkstone.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The absolute transform (SPEC §4.2). Small type, but a wrong `times` skews every object. */
class Matrix3Test {

    private fun assertClose(expected: PagePoint, actual: PagePoint) {
        assertTrue(
            abs(expected.x - actual.x) < TOLERANCE && abs(expected.y - actual.y) < TOLERANCE,
            "expected $expected but was $actual",
        )
    }

    @Test
    fun `identity maps a point to itself`() {
        assertEquals(PagePoint(3f, 4f), Matrix3.Identity.map(PagePoint(3f, 4f)))
    }

    @Test
    fun `composition applies the right-hand matrix first`() {
        val translate = Matrix3.translate(10f, 0f)
        val scale = Matrix3.scale(2f)
        val point = PagePoint(1f, 1f)

        // (translate * scale) means scale first: (1,1) -> (2,2) -> (12,2).
        assertClose(PagePoint(12f, 2f), (translate * scale).map(point))
        // The other way round: translate first, then scale: (1,1) -> (11,1) -> (22,2).
        assertClose(PagePoint(22f, 2f), (scale * translate).map(point))
        // Which is the whole reason transforms are stored absolutely — these two do
        // not commute, and a log of deltas would compose them in arrival order.
        assertTrue((translate * scale) != (scale * translate))
    }

    @Test
    fun `inverse undoes the transform`() {
        val transform = Matrix3.translate(5f, -7f) * Matrix3.rotate((PI / 3).toFloat()) * Matrix3.scale(2f, 3f)
        val inverse = assertNotNull(transform.inverse())

        assertClose(PagePoint(11f, 13f), inverse.map(transform.map(PagePoint(11f, 13f))))
    }

    @Test
    fun `a degenerate transform has no inverse`() {
        assertNull(Matrix3.scale(0f, 1f).inverse())
    }

    @Test
    fun `rotation and scale alone are not skewed — but composing them as deltas is`() {
        assertFalse(Matrix3.rotate((PI / 4).toFloat()).isSkewed())
        assertFalse(Matrix3.scale(100f).isSkewed(), "skew detection must not scale with magnitude")
        assertFalse((Matrix3.scale(3f) * Matrix3.rotate(1f)).isSkewed(), "uniform scale then rotate stays square")

        // The delta failure from SPEC §4.2: a non-uniform scale, a rotation, and an
        // inverse scale do not cancel — they shear.
        val skewed = Matrix3.scale(2f, 1f) * Matrix3.rotate((PI / 4).toFloat()) * Matrix3.scale(0.5f, 1f)
        assertTrue(skewed.isSkewed(), "the delta composition should be detectably skewed")
    }

    private companion object {
        const val TOLERANCE = 1e-3f
    }
}
