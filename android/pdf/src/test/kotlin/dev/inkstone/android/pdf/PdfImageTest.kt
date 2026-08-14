package dev.inkstone.android.pdf

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.ImageCrop
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.storage.InkstoneMarker
import dev.inkstone.core.storage.sha256Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⭐ ROADMAP M8c — an inserted image, published as a PDF image XObject.
 *
 * SPEC §4.9.4 puts the bytes in the bundle's `assets/` family and **never in the op log**, so the
 * whole of this path is: bundle bytes in, `/XObject /Image` out, placed by a `cm` and masked by
 * the `SetFormat` register's crop.
 *
 * Three ways it can be *valid, parseable and wrong*, each with a test below:
 *
 * | | Symptom |
 * |---|---|
 * | the XObject is not in the **page's** `/Resources` | `/InkstoneIm0 Do` names nothing; a blank rectangle, or the rest of the page's content abandoned |
 * | the unit square's `v` direction is inverted | an upside-down photograph, and no error |
 * | a JPEG is re-encoded rather than passed through | a five-fold larger file that syncs, on every save |
 */
class PdfImageTest {

    private val bundle = "PK pretend this is note.inkstone".toByteArray(Charsets.US_ASCII)
    private val decoder = FakeImageDecoder()
    private val writer = PdfBoxWriter(TestFontFaces, decoder)

    @Test
    fun `an image becomes an XObject defined in the resources the page can see`() {
        val published = publish(PdfFixtures.image("i1", PdfFixtures.FAKE_IMAGE_BYTES))

        PDDocument.load(published).use { document ->
            val page = document.getPage(PdfFixtures.PLAIN_PAGE)
            val content = page.contents.readBytes().toString(Charsets.ISO_8859_1)
            assertTrue("/InkstoneIm0 Do" in content, "the page never draws the image")

            val resources = assertNotNull(ContentScan.effectiveResources(page), "the page has no /Resources")
            val image = assertNotNull(
                (resources.getDictionaryObject(COSName.XOBJECT) as? COSDictionary)
                    ?.getDictionaryObject(COSName.getPDFName("InkstoneIm0")) as? COSStream,
                "/InkstoneIm0 is not defined in the page's /Resources /XObject",
            )
            assertEquals(COSName.IMAGE, image.getDictionaryObject(COSName.SUBTYPE))
            assertEquals(COSName.DEVICERGB, image.getDictionaryObject(COSName.COLORSPACE))
            assertEquals(EIGHT, image.getInt(COSName.BITS_PER_COMPONENT))
            // The bytes are the pixels, so an unmarked stream shows up here as a short read rather
            // than as a missing key — the incremental writer would simply have omitted the object.
            val samples = image.createInputStream().use { it.readBytes() }
            assertEquals(
                image.getInt(COSName.WIDTH) * image.getInt(COSName.HEIGHT) * RGB_COMPONENTS,
                samples.size,
                "the image stream does not hold width x height x 3 samples",
            )
        }
    }

    /**
     * The M4 defect asked of an image: every name the page's content uses, resolved the way a
     * viewer resolves it.
     */
    @Test
    fun `every name a page with an image uses is defined in the resources the page can see`() {
        val published = publish(
            PdfFixtures.image("i1", PdfFixtures.FAKE_IMAGE_BYTES),
            PdfFixtures.image("i2", PdfFixtures.FAKE_IMAGE_BYTES, topPt = 500f, opacity = HALF),
        )

        PDDocument.load(published).use { document ->
            val page = document.getPage(PdfFixtures.PLAIN_PAGE)
            val resources = ContentScan.effectiveResources(page)
            val used = ContentScan.namesUsed(page.contents.readBytes().toString(Charsets.ISO_8859_1))
            for ((category, names) in used) {
                val missing = names - ContentScan.namesDefined(resources, category)
                assertTrue(missing.isEmpty(), "the page uses ${category.name} $missing which its /Resources lack")
            }
        }
    }

    /**
     * ⚠️ **One XObject for one asset, however many objects name it.**
     *
     * `duplicatePage` and an Element re-insert both put the same picture on a page twice, and the
     * materializer reads the bundle's assets once — so two `ImageObject`s naming one asset arrive
     * holding one array. Writing the pixels twice would double the size of a page of stamps.
     */
    @Test
    fun `two objects naming one asset share a single image XObject`() {
        val bytes = PdfFixtures.FAKE_IMAGE_BYTES
        val published = publish(
            PdfFixtures.image("i1", bytes),
            PdfFixtures.image("i2", bytes, topPt = 500f),
        )

        PDDocument.load(published).use { document ->
            val resources = ContentScan.effectiveResources(document.getPage(PdfFixtures.PLAIN_PAGE))
            assertEquals(
                setOf("InkstoneIm0"),
                ContentScan.namesDefined(resources, COSName.XOBJECT),
                "the same asset was embedded twice",
            )
        }
        assertEquals(1, decoder.decoded, "the decoder ran once per object rather than once per asset")
    }

    /**
     * ⭐ **The placement matrix, read back and applied to the unit square.**
     *
     * The `cm` is the whole of where a picture lands, and a transposed or y-flipped one produces a
     * picture that is *there* — so no structural check catches it, and a symmetric fixture would
     * not either. This maps the unit square's corners through the operands the file actually
     * carries and asserts they are the object's box in PDF user space, the right way up.
     */
    @Test
    fun `the cm maps the images first row to the top of the object box`() {
        val image = PdfFixtures.image("i1", PdfFixtures.FAKE_IMAGE_BYTES, leftPt = 100f, topPt = 200f)
        val published = publish(image)

        val matrix = imageMatrix(published)
        // Page 0 is plain US Letter: page space (100, 200) is user space (100, 592).
        assertPoint(100f, LETTER_HEIGHT - 200f, matrix.map(corner(0f, 1f)), "the image's top-left")
        assertPoint(300f, LETTER_HEIGHT - 200f, matrix.map(corner(1f, 1f)), "the image's top-right")
        assertPoint(100f, LETTER_HEIGHT - 350f, matrix.map(corner(0f, 0f)), "the image's bottom-left")
    }

    /** The same picture on the page that has a rotation *and* an offset CropBox. */
    @Test
    fun `an image on a rotated and cropped page lands inside that pages box`() {
        val published = publish(
            PdfFixtures.image("i1", PdfFixtures.FAKE_IMAGE_BYTES, leftPt = 40f, topPt = 60f),
            pageIndex = PdfFixtures.ROTATED_AND_CROPPED_PAGE,
        )

        PDDocument.load(published).use { document ->
            val page = document.getPage(PdfFixtures.ROTATED_AND_CROPPED_PAGE)
            val box = page.cropBox
            val matrix = parseMatrix(page.contents.readBytes().toString(Charsets.ISO_8859_1), "cm")
            for (corner in listOf(corner(0f, 0f), corner(1f, 0f), corner(1f, 1f), corner(0f, 1f))) {
                val user = matrix.map(corner)
                assertTrue(
                    user.x >= box.lowerLeftX - SLACK && user.x <= box.upperRightX + SLACK &&
                        user.y >= box.lowerLeftY - SLACK && user.y <= box.upperRightY + SLACK,
                    "a corner landed at (${user.x}, ${user.y}), outside the CropBox $box",
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Crop — SPEC §4.9.2's SetFormat register
    // -----------------------------------------------------------------------

    /**
     * A rectangular crop **fits**: the cropped region fills the object's box.
     *
     * Read off the file as a ratio rather than as absolute numbers, so this asserts the behaviour
     * — *"cropping to half the width doubles the scale"* — and not one arithmetic result.
     */
    @Test
    fun `a rectangular crop scales the source so the crop fills the box`() {
        val whole = imageMatrix(publish(PdfFixtures.image("i1", PdfFixtures.FAKE_IMAGE_BYTES)))
        val cropped = imageMatrix(
            publish(
                PdfFixtures.image(
                    "i1",
                    PdfFixtures.FAKE_IMAGE_BYTES,
                    crop = ImageCrop.Rect(left = 0.25f, top = 0f, right = 0.75f, bottom = 1f),
                ),
            ),
        )

        assertEquals(2f, cropped.m00 / whole.m00, TOLERANCE, "half the width should be drawn at twice the scale")
        assertEquals(1f, cropped.m11 / whole.m11, TOLERANCE, "the vertical scale should be untouched")
    }

    /**
     * A freehand crop is a clip path, and the *same* fit rule applies to its bounding box.
     *
     * Two behaviours would be the trap: "crop to a square" and "crop to a four-sided polygon" are
     * the same gesture and must produce the same picture.
     */
    @Test
    fun `a polygon crop is written as a clip path`() {
        val published = publish(
            PdfFixtures.image(
                "i1",
                PdfFixtures.FAKE_IMAGE_BYTES,
                crop = ImageCrop.Polygon(listOf(0.1f, 0.1f, 0.9f, 0.2f, 0.5f, 0.9f)),
            ),
        )

        val content = pageContent(published)
        assertTrue("W n" in content, "no clip path was written for a polygon crop")
        val clip = content.substringBefore("W n")
        assertEquals(
            THREE,
            Regex("""[-\d.]+ [-\d.]+ [ml]""").findAll(clip.substringAfterLast("q")).count(),
            "the clip path should have one point per polygon vertex",
        )
    }

    @Test
    fun `an image with no crop still clips to its own box`() {
        val content = pageContent(publish(PdfFixtures.image("i1", PdfFixtures.FAKE_IMAGE_BYTES)))
        assertTrue("W n" in content, "an uncropped image should still be bounded by its own box")
    }

    // -----------------------------------------------------------------------
    // Opacity and alpha
    // -----------------------------------------------------------------------

    @Test
    fun `a translucent image defines the alpha state it names`() {
        val published = publish(PdfFixtures.image("i1", PdfFixtures.FAKE_IMAGE_BYTES, opacity = HALF))

        PDDocument.load(published).use { document ->
            val page = document.getPage(PdfFixtures.PLAIN_PAGE)
            val states = assertNotNull(
                ContentScan.effectiveResources(page)?.getDictionaryObject(COSName.EXT_G_STATE) as? COSDictionary,
                "the page defines no /ExtGState at all",
            )
            val name = states.keySet().first().name
            assertTrue("/$name gs" in page.contents.readBytes().toString(Charsets.ISO_8859_1))
            val ca = (states.getDictionaryObject(name) as? COSDictionary)
                ?.getDictionaryObject(COSName.CA_NS) as? COSNumber
            assertNotNull(ca, "/$name has no /ca — a viewer would draw the picture opaque")
            assertEquals(HALF, ca.floatValue(), ALPHA_TOLERANCE)
        }
    }

    @Test
    fun `an opaque image names no graphics state at all`() {
        val published = publish(PdfFixtures.image("i1", PdfFixtures.FAKE_IMAGE_BYTES))

        PDDocument.load(published).use { document ->
            val page = document.getPage(PdfFixtures.PLAIN_PAGE)
            val defined = ContentScan.namesDefined(ContentScan.effectiveResources(page), COSName.EXT_G_STATE)
            assertTrue(
                defined.none { it.startsWith("GS") },
                "an opaque picture should not cost the page a graphics state: $defined",
            )
        }
    }

    @Test
    fun `an image with alpha carries a soft mask and not a stencil`() {
        val published = PdfBoxWriter(TestFontFaces, FakeImageDecoder(withAlpha = true)).let { alphaWriter ->
            val original = PdfFixtures.sourcePdf()
            alphaWriter.publish(
                original,
                plan(original, PdfFixtures.PLAIN_PAGE, listOf(PdfFixtures.image("i1", PdfFixtures.FAKE_IMAGE_BYTES))),
            )
        }

        PDDocument.load(published).use { document ->
            val image = imageXObject(document)
            val mask = assertNotNull(
                image.getDictionaryObject(COSName.SMASK) as? COSStream,
                "no /SMask — a picture with a soft edge would come out with a hard one",
            )
            assertEquals(COSName.DEVICEGRAY, mask.getDictionaryObject(COSName.COLORSPACE))
            assertNull(image.getDictionaryObject(COSName.MASK), "/Mask is a one-bit stencil and is not what this is")
        }
    }

    // -----------------------------------------------------------------------
    // JPEG passthrough
    // -----------------------------------------------------------------------

    /**
     * ⚠️ A JPEG must reach the file **byte-identical**, under `/DCTDecode`.
     *
     * PDF's DCT filter *is* JPEG, so decoding and re-Flate-ing a photograph multiplies its size by
     * roughly five in a file that syncs on every save. This also proves the decoder is not
     * consulted at all — [FakeImageDecoder] answers null for these bytes, so a publish that
     * produced an XObject cannot have gone through it.
     */
    @Test
    fun `a baseline JPEG is passed through under DCTDecode and never re-encoded`() {
        val jpeg = PdfFixtures.headerOnlyJpeg(widthPx = JPEG_WIDTH, heightPx = JPEG_HEIGHT)
        val published = publish(PdfFixtures.image("i1", jpeg))

        PDDocument.load(published).use { document ->
            val image = imageXObject(document)
            assertEquals(COSName.DCT_DECODE, image.getDictionaryObject(COSName.FILTER))
            assertEquals(JPEG_WIDTH, image.getInt(COSName.WIDTH))
            assertEquals(JPEG_HEIGHT, image.getInt(COSName.HEIGHT))
            assertEquals(COSName.DEVICERGB, image.getDictionaryObject(COSName.COLORSPACE))
            assertTrue(
                jpeg.contentEquals(image.createRawInputStream().use { it.readBytes() }),
                "the JPEG was not carried through verbatim",
            )
        }
        assertEquals(0, decoder.decoded, "a JPEG must not reach the platform decoder")
    }

    @Test
    fun `bytes no decoder understands cost the picture and never the publish`() {
        val published = publish(PdfFixtures.image("i1", "not an image at all".toByteArray(Charsets.US_ASCII)))

        PDDocument.load(published).use { document ->
            val page = document.getPage(PdfFixtures.PLAIN_PAGE)
            assertTrue(
                ContentScan.namesDefined(ContentScan.effectiveResources(page), COSName.XOBJECT).isEmpty(),
                "an undecodable image should leave no XObject behind",
            )
            assertTrue("Do" !in page.contents.readBytes().toString(Charsets.ISO_8859_1), "it must not be drawn either")
        }
    }

    // -----------------------------------------------------------------------

    private fun publish(vararg objects: PdfPageObject, pageIndex: Int = PdfFixtures.PLAIN_PAGE): ByteArray {
        val original = PdfFixtures.sourcePdf()
        return writer.publish(original, plan(original, pageIndex, objects.toList()))
    }

    private fun plan(original: ByteArray, pageIndex: Int, objects: List<PdfPageObject>): PublishPlan = PublishPlan(
        marker = InkstoneMarker(
            documentId = DocumentId("0192f3c4-9d9d-7abc-8def-0123456789ab"),
            schemaVersion = 1,
            bundleSha256 = bundle.sha256Hex(),
            originalLength = original.size.toLong(),
            originalSha256 = original.sha256Hex(),
        ),
        pages = (0 until FIXTURE_PAGES).map { index ->
            PdfFixtures.plan(index, emptyList(), objects = if (index == pageIndex) objects else emptyList())
        },
        bundle = bundle,
    )

    private fun pageContent(published: ByteArray): String = PDDocument.load(published).use { document ->
        document.getPage(PdfFixtures.PLAIN_PAGE).contents.readBytes().toString(Charsets.ISO_8859_1)
    }

    private fun imageXObject(document: PDDocument): COSStream = assertNotNull(
        (
            ContentScan.effectiveResources(document.getPage(PdfFixtures.PLAIN_PAGE))
                ?.getDictionaryObject(COSName.XOBJECT) as? COSDictionary
            )
            ?.getDictionaryObject(COSName.getPDFName("InkstoneIm0")) as? COSStream,
        "no image XObject on the page",
    )

    private fun imageMatrix(published: ByteArray): Matrix3 = parseMatrix(pageContent(published), "cm")

    /** The operands of the last [operator] in [content], back in [Matrix3]'s column-vector order. */
    private fun parseMatrix(content: String, operator: String): Matrix3 {
        val match = assertNotNull(
            Regex("""([-\d.]+) ([-\d.]+) ([-\d.]+) ([-\d.]+) ([-\d.]+) ([-\d.]+) $operator""")
                .findAll(content).lastOrNull(),
            "no $operator operator in the page content",
        )
        val values = FloatArray(MATRIX_VALUES) { match.groupValues[it + 1].toFloat() }
        return Matrix3(
            m00 = values[0],
            m01 = values[2],
            m02 = values[4],
            m10 = values[1],
            m11 = values[3],
            m12 = values[5],
        )
    }

    private fun corner(u: Float, v: Float) = PagePoint(u, v)

    private fun assertPoint(x: Float, y: Float, actual: PagePoint, where: String) {
        assertEquals(x, actual.x, TOLERANCE, "$where x")
        assertEquals(y, actual.y, TOLERANCE, "$where y")
    }

    private companion object {
        const val FIXTURE_PAGES = 4
        const val LETTER_HEIGHT = 792f
        const val MATRIX_VALUES = 6
        const val TOLERANCE = 1e-2f
        const val ALPHA_TOLERANCE = 1e-2f
        const val SLACK = 1f
        const val HALF = 0.5f
        const val EIGHT = 8
        const val THREE = 3
        const val RGB_COMPONENTS = 3
        const val JPEG_WIDTH = 96
        const val JPEG_HEIGHT = 64
    }
}
