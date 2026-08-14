package dev.inkstone.android.pdf

import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PdfBox
import dev.inkstone.core.model.PdfPageGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⭐ SPEC §4.4 as a matrix — the arithmetic M8c added, checked against the arithmetic that was
 * already there.
 *
 * ## Why this file exists at all
 *
 * `:core:model`'s `pageToPdfUser` has been unit-tested since M1 and is the normative form of the
 * rule. [PdfPlacement.pageToUser] is a **second form** of the same rule, needed because a text box
 * and an image are placed by a whole affine rather than point by point. Two forms of one rule is
 * two things that can disagree, and the disagreement would be the classic *"annotations are 3mm
 * off and rotated"* that SPEC §4.4 opens by naming.
 *
 * So the first test does not assert numbers: it asserts that the matrix and the point conversion
 * **agree**, on every combination of rotation and CropBox origin, at points chosen to break
 * symmetry. That makes this a consistency check rather than a copy of the expected answers, which
 * is the only kind of test worth having about a second implementation.
 */
class PdfPlacementTest {

    private val geometries = listOf(
        "plain" to PdfPageGeometry(PdfBox(0f, 0f, 612f, 792f), 0),
        "rotated" to PdfPageGeometry(PdfBox(0f, 0f, 612f, 792f), QUARTER),
        "cropped" to PdfPageGeometry(PdfBox(36f, 48f, 540f, 696f), 0),
        "cropped and rotated" to PdfPageGeometry(PdfBox(36f, 48f, 540f, 696f), QUARTER),
        "half turn" to PdfPageGeometry(PdfBox(36f, 48f, 540f, 696f), HALF),
        "three quarters" to PdfPageGeometry(PdfBox(36f, 48f, 540f, 696f), THREE_QUARTER),
    )

    /** Asymmetric on both axes, and none of them on a centre line — a square would prove nothing. */
    private val samples = listOf(
        PagePoint(0f, 0f),
        PagePoint(17f, 5f),
        PagePoint(300f, 40f),
        PagePoint(40f, 300f),
        PagePoint(511f, 691f),
    )

    @Test
    fun `the page matrix agrees with SPEC 4-4s point conversion on every geometry`() {
        for ((label, geometry) in geometries) {
            val matrix = PdfPlacement.pageToUser(geometry)
            for (point in samples) {
                val expected = geometry.toPdfUser(point)
                val actual = matrix.map(point)
                assertEquals(expected.x, actual.x, TOLERANCE, "$label x at $point")
                assertEquals(expected.y, actual.y, TOLERANCE, "$label y at $point")
            }
        }
    }

    /**
     * ⚠️ PDF's matrix is row-vector and [Matrix3]'s is column-vector, so the second and third
     * operands swap.
     *
     * Getting this wrong transposes every rotation: a text box rotated 30° comes out rotated -30°
     * and sheared, and on the plain page — where the matrix is diagonal — it does not show at all.
     * That is the shape of bug that reaches a device.
     */
    @Test
    fun `the PDF operands are in row-vector order`() {
        val matrix = Matrix3(m00 = 1f, m01 = 2f, m02 = 3f, m10 = 4f, m11 = 5f, m12 = 6f)
        assertEquals("1 4 2 5 3 6", PdfPlacement.args(matrix))
    }

    /**
     * On an unrotated page with a zero origin the text matrix is exactly what M4 wrote by hand.
     *
     * That equivalence is why `InvisibleTextLayerTest`'s M4-era `1 0 0 1 x y Tm` assertions did
     * not have to change, and it is worth pinning: if this ever stops reducing, those assertions
     * start failing for a reason that has nothing to do with what they are about.
     */
    @Test
    fun `a text line on a plain page reduces to the identity rotation M4 wrote`() {
        val geometry = PdfPageGeometry(PdfBox(0f, 0f, 612f, 792f), 0)
        val matrix = PdfPlacement.textLine(geometry, Matrix3.Identity, leftPt = 72f, baselinePt = 100f)
        assertEquals("1 0 0 1 72 692", PdfPlacement.args(matrix))
    }

    /**
     * ⭐ The two directions a glyph actually cares about, on every geometry and under a rotated,
     * scaled object transform.
     *
     * Advancing one point along the text baseline must be the same displacement as advancing one
     * point in **+x** of the object's own space; a glyph's ascender must point along **-y** of it,
     * because page space is y-down and text space is y-up. Those two facts are the whole content
     * of the `Flip` in [PdfPlacement.textLine], and asserting them is stronger than asserting six
     * numbers: it holds for transforms this test does not enumerate.
     */
    @Test
    fun `text advances along the objects x and ascends against its y`() {
        val transform = Matrix3.translate(90f, 140f) * Matrix3.rotate(0.4f) * Matrix3.scale(1.5f, 0.8f)
        for ((label, geometry) in geometries) {
            val text = PdfPlacement.textLine(geometry, transform, leftPt = 30f, baselinePt = 50f)
            val toUser = PdfPlacement.objectToUser(geometry, transform)

            val origin = text.map(PagePoint(0f, 0f))
            val advance = text.map(PagePoint(1f, 0f))
            val ascend = text.map(PagePoint(0f, 1f))

            val base = toUser.map(PagePoint(30f, 50f))
            val alongX = toUser.map(PagePoint(31f, 50f))
            val againstY = toUser.map(PagePoint(30f, 49f))

            assertEquals(base.x, origin.x, TOLERANCE, "$label origin x")
            assertEquals(base.y, origin.y, TOLERANCE, "$label origin y")
            assertEquals(alongX.x - base.x, advance.x - origin.x, TOLERANCE, "$label advance x")
            assertEquals(alongX.y - base.y, advance.y - origin.y, TOLERANCE, "$label advance y")
            assertEquals(againstY.x - base.x, ascend.x - origin.x, TOLERANCE, "$label ascender x")
            assertEquals(againstY.y - base.y, ascend.y - origin.y, TOLERANCE, "$label ascender y")
        }
    }

    /**
     * ⚠️ A PDF image's unit square has `v = 1` at the image's **first row**.
     *
     * The format draws image space y-down into a y-up square, so the mapping ends up with a
     * negative `d`. Getting it the other way round publishes an upside-down photograph and nothing
     * else — no error, and a symmetric test image would not show it.
     */
    @Test
    fun `the image unit square puts its first row at the top of the object box`() {
        val place = PdfPlacement.unitImageToObject(200f, 100f, left = 0f, top = 0f, right = 1f, bottom = 1f)

        assertPoint(0f, 0f, place.map(PagePoint(0f, 1f)), "the image's top-left")
        assertPoint(200f, 0f, place.map(PagePoint(1f, 1f)), "the image's top-right")
        assertPoint(0f, 100f, place.map(PagePoint(0f, 0f)), "the image's bottom-left")
    }

    /**
     * A crop **fits**: the cropped region fills the object's box, which is what cropping looks
     * like in any editor. The rest of the image lands outside the box and is clipped away.
     */
    @Test
    fun `a rectangular crop scales the cropped region to fill the box`() {
        // The middle horizontal half, and the lower vertical half, of the source.
        val place = PdfPlacement.unitImageToObject(200f, 100f, left = 0.25f, top = 0.5f, right = 0.75f, bottom = 1f)

        assertPoint(0f, 0f, place.map(PagePoint(0.25f, 0.5f)), "the crop's top-left")
        assertPoint(200f, 100f, place.map(PagePoint(0.75f, 0f)), "the crop's bottom-right")
        // Outside the crop, and therefore outside the box the caller clips to.
        assertTrue(place.map(PagePoint(0f, 1f)).x < 0f, "the source's left edge should fall outside the box")
    }

    private fun assertPoint(x: Float, y: Float, actual: PagePoint, where: String) {
        assertEquals(x, actual.x, TOLERANCE, "$where x")
        assertEquals(y, actual.y, TOLERANCE, "$where y")
    }

    private companion object {
        const val TOLERANCE = 1e-3f
        const val QUARTER = 90
        const val HALF = 180
        const val THREE_QUARTER = 270
    }
}
