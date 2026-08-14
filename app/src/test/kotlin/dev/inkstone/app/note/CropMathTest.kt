package dev.inkstone.app.note

import dev.inkstone.core.model.ImageCrop
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PagePoint
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⭐ ROADMAP M8c — a crop drag, normalised to the **source image's own box** (SPEC §4.9.2).
 *
 * The normalisation is the whole of the correctness here. A crop stored in page points would have
 * to be rewritten by every resize, rotate and flip — and those are `SetTransform`, a *different*
 * register, so the two would have to be written together and would then be two absolute values that
 * can disagree. Normalised, the crop is carried by the object's matrix like any other point on it,
 * and a resize writes one op.
 *
 * These are the tests that would catch getting it the other way round: the same drag over the same
 * pixels has to produce the same fractions whatever the object's transform is.
 */
class CropMathTest {

    @Test
    fun `a drag over the middle of an untransformed image is the middle of the source`() {
        val image = imageAt(Matrix3.translate(100f, 200f))
        // The middle half on both axes: local 50..150 of a 200 pt box.
        val crop = CropMath.rectOf(image, PagePoint(150f, 250f), PagePoint(250f, 350f))
        assertNotNull(crop)
        assertEquals(0.25f, crop.left, TOLERANCE)
        assertEquals(0.75f, crop.right, TOLERANCE)
        assertEquals(0.25f, crop.top, TOLERANCE)
        assertEquals(0.75f, crop.bottom, TOLERANCE)
    }

    /** The two axes are normalised independently — a drag off-centre in y is not off-centre in x. */
    @Test
    fun `each axis is normalised by its own extent`() {
        val image = imageAt(Matrix3.translate(100f, 200f))
        val crop = CropMath.rectOf(image, PagePoint(150f, 225f), PagePoint(250f, 275f))
        assertNotNull(crop)
        assertEquals(0.25f, crop.left, TOLERANCE)
        assertEquals(0.125f, crop.top, TOLERANCE)
        assertEquals(0.375f, crop.bottom, TOLERANCE)
    }

    /**
     * ⭐ The property the whole design turns on: **scaling the object must not change the crop.**
     *
     * The same visible half of the same picture, at 1× and at 3×, is the same pair of fractions. A
     * page-space crop would answer three times the numbers and would need rewriting on every
     * resize — in a register that is not the one the resize wrote.
     */
    @Test
    fun `the same visible half crops identically at any scale`() {
        val small = imageAt(Matrix3.Identity)
        val large = imageAt(Matrix3.scale(3f))
        val fromSmall = CropMath.rectOf(small, PagePoint(0f, 0f), PagePoint(100f, 100f))
        val fromLarge = CropMath.rectOf(large, PagePoint(0f, 0f), PagePoint(300f, 300f))
        assertEquals(fromSmall, fromLarge)
    }

    /** A rotated image crops in its **own** axes, so the fractions still describe the picture. */
    @Test
    fun `a rotated image is cropped in its own space`() {
        val quarter = (PI / 2).toFloat()
        val rotated = imageAt(Matrix3.rotate(quarter))
        // The local point (0,0)..(100,100) maps to page (0,0)..(-100,100) under a quarter turn.
        val crop = CropMath.rectOf(rotated, PagePoint(0f, 0f), PagePoint(-100f, 100f))
        assertNotNull(crop)
        assertEquals(0f, crop.left, TOLERANCE)
        assertEquals(0.5f, crop.right, TOLERANCE)
        assertEquals(0f, crop.top, TOLERANCE)
        assertEquals(0.5f, crop.bottom, TOLERANCE)
    }

    @Test
    fun `a drag that overshoots the picture crops to its edge rather than inventing pixels`() {
        val image = imageAt(Matrix3.Identity)
        val crop = CropMath.rectOf(image, PagePoint(-500f, -500f), PagePoint(900f, 900f))
        assertNotNull(crop)
        assertTrue(crop.isWhole, "an overshooting drag should crop to the whole image, got $crop")
    }

    /**
     * ⚠️ A degenerate crop is refused rather than written. `ObjectFormatCodec.decodeRect` will not
     * read one back, so writing it would put a register in the log that this build cannot decode —
     * and the image would render with no pixels and no way back through the UI.
     */
    @Test
    fun `a collapsed drag is refused`() {
        val image = imageAt(Matrix3.Identity)
        assertNull(CropMath.rectOf(image, PagePoint(50f, 50f), PagePoint(50f, 50f)))
        assertNull(CropMath.rectOf(image, PagePoint(50f, 10f), PagePoint(50.2f, 90f)))
    }

    @Test
    fun `a singular transform has nothing to map back through`() {
        val collapsed = imageAt(Matrix3.scale(1f, 0f))
        assertNull(CropMath.rectOf(collapsed, PagePoint(0f, 0f), PagePoint(50f, 50f)))
    }

    @Test
    fun `a freehand crop is a closed polygon of fractions`() {
        val image = imageAt(Matrix3.translate(10f, 10f))
        val crop = CropMath.polygonOf(
            image,
            listOf(PagePoint(10f, 10f), PagePoint(110f, 10f), PagePoint(110f, 110f)),
        )
        assertNotNull(crop)
        assertEquals(3, crop.pointCount)
        assertEquals(listOf(0f, 0f, 0.5f, 0f, 0.5f, 0.5f), crop.points)
    }

    /** Fewer than three points is a tap; the codec refuses one, so it is never written. */
    @Test
    fun `a two point trace is not a polygon`() {
        val image = imageAt(Matrix3.Identity)
        assertNull(CropMath.polygonOf(image, listOf(PagePoint(0f, 0f), PagePoint(10f, 10f))))
    }

    @Test
    fun `handles are drawn where the picture actually is`() {
        val image = imageAt(Matrix3.translate(40f, 60f))
        val corners = CropMath.handlesOf(image, ImageCrop.Rect(0f, 0f, 0.5f, 0.5f), WIDTH_PT, HEIGHT_PT)
        assertEquals(PagePoint(40f, 60f), corners.first())
        assertEquals(PagePoint(140f, 160f), corners[2])
    }

    private fun imageAt(transform: Matrix3) = ImageObject(
        id = ObjectId("018f0000-0000-7000-8000-00000000aaaa"),
        asset = "assets/img-018f0000-0000-7000-8000-00000000aaaa.webp",
        widthPt = WIDTH_PT,
        heightPt = HEIGHT_PT,
        transform = transform,
        style = ObjectStyle(colorArgb = -1, widthPt = 1f),
    )

    private companion object {
        const val WIDTH_PT = 200f
        const val HEIGHT_PT = 200f
        const val TOLERANCE = 0.001f
    }
}
