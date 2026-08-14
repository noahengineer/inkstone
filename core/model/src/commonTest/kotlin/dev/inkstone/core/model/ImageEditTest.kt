package dev.inkstone.core.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ROADMAP M8c — the ops an image gesture writes, and the two matrix helpers behind rotate and flip.
 *
 * ⭐ The involution tests — two flips, four quarter turns — are not arithmetic trivia. They are what
 * distinguishes an **absolute** transform from a delta: a delta-based flip composes onto whatever
 * is there and drifts, and SPEC §4.2's worked example is where it drifts to — silently, on both
 * devices, with no error. CLAUDE.md rule 8.
 */
class ImageEditTest {

    private var opCounter = 0
    private var clockCounter = 0L

    private fun nextOpId(): String = "op-${opCounter++}"

    private fun nextClock(): LamportClock = LamportClock(++clockCounter, DEVICE)

    // -----------------------------------------------------------------------
    // create
    // -----------------------------------------------------------------------

    @Test
    fun `an inserted image names its asset and writes no format when it is uncropped`() {
        val ops = create(ObjectFormat.Default)

        assertEquals(1, ops.size)
        val image = assertNotNull((ops.single() as? PageOp.AddObject)?.obj as? ImageObject)
        assertEquals(ASSET, image.asset)
        assertEquals(STYLE, image.style, "SetStyle cannot say 'unset' — see SPEC 4.9.2")
    }

    @Test
    fun `an image inserted already cropped writes the crop as a second op`() {
        val ops = create(ObjectFormat(crop = ImageCrop.Rect(0.1f, 0f, 0.9f, 1f)))

        assertEquals(2, ops.size)
        val crop = ObjectFormat.decode(assertNotNull(ops[1] as? PageOp.SetFormat).record).crop
        assertEquals(ImageCrop.Rect(0.1f, 0f, 0.9f, 1f), crop)
    }

    // -----------------------------------------------------------------------
    // Crop
    // -----------------------------------------------------------------------

    @Test
    fun `cropping carries the rest of the record forward including a later builds keys`() {
        // ⚠️ The register resolves whole (CLAUDE.md rule 8), so a crop written from a fresh
        // ObjectFormat would silently reset the size *and* destroy `extras` — which is a newer
        // build's data and the one thing this build cannot reconstruct (SPEC §5.8).
        val current = ObjectFormat(sizePt = 22f, extras = mapOf("zz" to "9"))
        val op = assertNotNull(
            ImageEdit.setCrop(IMAGE, current, ImageCrop.Rect(0f, 0f, 0.5f, 1f), ::nextOpId, ::nextClock)
                as? PageOp.SetFormat,
        )

        val written = ObjectFormat.decode(op.record)
        assertEquals(22f, written.sizePt)
        assertEquals(mapOf("zz" to "9"), written.extras)
        assertEquals(ImageCrop.Rect(0f, 0f, 0.5f, 1f), written.crop)
    }

    @Test
    fun `clearing a crop is a write and not an absent op`() {
        val current = ObjectFormat(crop = ImageCrop.Polygon(listOf(0f, 0f, 1f, 0f, 1f, 1f)))
        val op = assertNotNull(
            ImageEdit.setCrop(IMAGE, current, null, ::nextOpId, ::nextClock) as? PageOp.SetFormat,
        )
        assertNull(ObjectFormat.decode(op.record).crop)
    }

    // -----------------------------------------------------------------------
    // Flip — absolute, about the object's own centre
    // -----------------------------------------------------------------------

    @Test
    fun `flipping twice is the identity`() {
        val start = Matrix3.translate(120f, 80f) * Matrix3.rotate(0.4f) * Matrix3.scale(1.5f, 0.8f)
        val once = ImageEdit.flipped(start, WIDTH, HEIGHT, horizontal = true)
        val twice = ImageEdit.flipped(once, WIDTH, HEIGHT, horizontal = true)

        assertNotClose(start, once, "one flip must actually change the matrix")
        assertClose(start, twice)
    }

    @Test
    fun `a flip keeps the image where it is on the page`() {
        // "About its own centre" is what makes this true. Composing on the other side would mirror
        // about the page origin and fling the picture across the paper.
        val start = Matrix3.translate(300f, 200f)
        val flipped = ImageEdit.flipped(start, WIDTH, HEIGHT, horizontal = true)
        val centre = PagePoint(WIDTH / 2f, HEIGHT / 2f)

        assertClose(start.map(centre), flipped.map(centre))
        // The left edge is now where the right edge was, which is what a mirror is.
        assertClose(start.map(PagePoint(WIDTH, 0f)), flipped.map(PagePoint(0f, 0f)))
    }

    @Test
    fun `a vertical flip mirrors the other axis`() {
        val start = Matrix3.Identity
        val flipped = ImageEdit.flipped(start, WIDTH, HEIGHT, horizontal = false)
        assertClose(start.map(PagePoint(0f, HEIGHT)), flipped.map(PagePoint(0f, 0f)))
        assertClose(start.map(PagePoint(0f, 0f)), flipped.map(PagePoint(0f, HEIGHT)))
    }

    // -----------------------------------------------------------------------
    // Rotate — absolute, about the object's own centre
    // -----------------------------------------------------------------------

    @Test
    fun `four quarter turns return the original matrix`() {
        val start = Matrix3.translate(50f, 400f) * Matrix3.scale(2f, 2f)
        var current = start
        repeat(4) { current = ImageEdit.rotated(current, WIDTH, HEIGHT, QUARTER_TURN) }

        assertClose(start, current)
    }

    @Test
    fun `a rotation keeps the image centred where it was`() {
        val start = Matrix3.translate(300f, 200f)
        val rotated = ImageEdit.rotated(start, WIDTH, HEIGHT, QUARTER_TURN)
        val centre = PagePoint(WIDTH / 2f, HEIGHT / 2f)

        assertClose(start.map(centre), rotated.map(centre))
    }

    @Test
    fun `the result is the whole transform and not a delta to compose`() {
        // ⭐ Rule 8, stated as a test. `rotated` already contains `current`, so a caller that
        // composed the answer onto the object's existing matrix would rotate twice — and the
        // symptom of getting this wrong is not an error, it is a picture at the wrong angle.
        val start = Matrix3.translate(10f, 10f)
        val rotated = ImageEdit.rotated(start, WIDTH, HEIGHT, QUARTER_TURN)

        assertNotClose(Matrix3.rotate(QUARTER_TURN * RADIANS_PER_DEGREE), rotated)
        assertClose(start.map(PagePoint(WIDTH / 2f, HEIGHT / 2f)), rotated.map(PagePoint(WIDTH / 2f, HEIGHT / 2f)))
    }

    @Test
    fun `rotating a degenerate image does not produce a nonsense centre`() {
        val rotated = ImageEdit.rotated(Matrix3.Identity, 0f, 0f, QUARTER_TURN)
        assertTrue(rotated.determinant.isFinite())
        assertFalseSkew(rotated)
    }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------

    private fun create(format: ObjectFormat): List<PageOp> = ImageEdit.create(
        id = IMAGE,
        asset = ASSET,
        widthPt = WIDTH,
        heightPt = HEIGHT,
        transform = Matrix3.translate(20f, 20f),
        z = FractionalKey("m"),
        style = STYLE,
        format = format,
        nextOpId = ::nextOpId,
        nextClock = ::nextClock,
    )

    private fun assertFalseSkew(matrix: Matrix3) = assertTrue(!matrix.isSkewed(), "unexpectedly skewed: $matrix")

    private fun assertClose(expected: PagePoint, actual: PagePoint) {
        assertTrue(
            abs(expected.x - actual.x) < TOLERANCE && abs(expected.y - actual.y) < TOLERANCE,
            "expected $expected, was $actual",
        )
    }

    private fun assertClose(expected: Matrix3, actual: Matrix3) {
        assertTrue(distance(expected, actual) < TOLERANCE, "expected $expected, was $actual")
    }

    private fun assertNotClose(unexpected: Matrix3, actual: Matrix3, message: String = "") {
        assertTrue(distance(unexpected, actual) > TOLERANCE, "$message: unexpectedly equal to $unexpected")
    }

    private fun distance(a: Matrix3, b: Matrix3): Float = maxOf(
        abs(a.m00 - b.m00),
        abs(a.m01 - b.m01),
        abs(a.m02 - b.m02),
        abs(a.m10 - b.m10),
        abs(a.m11 - b.m11),
        abs(a.m12 - b.m12),
    )

    private companion object {
        const val DEVICE = "device-a"
        const val ASSET = "assets/img-obj-image.webp"
        const val WIDTH = 160f
        const val HEIGHT = 90f
        const val QUARTER_TURN = 90f
        const val RADIANS_PER_DEGREE = 0.017453292f

        /**
         * Loose enough for four `cos`/`sin` round trips at page-point magnitudes, tight enough that
         * a genuinely wrong composition — a mirror about the page origin, say — fails by hundreds.
         */
        const val TOLERANCE = 1e-3f

        val IMAGE = ObjectId("obj-image")
        val STYLE = ObjectStyle(colorArgb = -0x1000000, widthPt = 0f)
    }
}
