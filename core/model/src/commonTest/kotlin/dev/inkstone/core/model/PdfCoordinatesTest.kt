package dev.inkstone.core.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SPEC §4.4. Both of the things this guards against are common and both break
 * naive implementations: a `/Rotate 90` page (every second scanned lecture PDF)
 * and a CropBox whose origin is not zero (every cropped scan).
 */
class PdfCoordinatesTest {

    /** A cropped, offset box — deliberately never 0,0-based. */
    private val croppedBox = PdfBox(x0 = 20f, y0 = 35f, x1 = 632f, y1 = 827f) // 612 x 792

    private fun assertClose(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) < 1e-3f, "$message expected $expected but was $actual")
    }

    @Test
    fun `rotate normalises negatives and wraps`() {
        assertEquals(0, normalizeRotate(0))
        assertEquals(90, normalizeRotate(90))
        assertEquals(270, normalizeRotate(-90))
        assertEquals(90, normalizeRotate(450))
        assertEquals(180, normalizeRotate(-180))
        assertFailsWith<IllegalArgumentException> { normalizeRotate(45) }
    }

    @Test
    fun `page size is the CropBox with rotate applied`() {
        assertEquals(PageSize(612f, 792f), pageSizeOf(croppedBox, 0))
        assertEquals(PageSize(792f, 612f), pageSizeOf(croppedBox, 90))
        assertEquals(PageSize(612f, 792f), pageSizeOf(croppedBox, 180))
        assertEquals(PageSize(792f, 612f), pageSizeOf(croppedBox, 270))
        // SPEC §4.4 rule 3, verbatim.
        assertEquals(PageSize(792f, 612f), pageSizeOf(PdfBox(0f, 0f, 612f, 792f), 90))
    }

    @Test
    fun `unrotated conversion matches the formula in SPEC 4_4 rule 4`() {
        val p = PagePoint(100f, 250f)
        val pdf = pageToPdfUser(p, croppedBox, rotate = 0)

        assertClose(croppedBox.x0 + p.x, pdf.x, "x:")
        assertClose(croppedBox.y1 - p.y, pdf.y, "y:")
    }

    @Test
    fun `page-space origin is the top-left of the displayed page for every rotation`() {
        // Whatever the rotation, page (0,0) is the corner the reader sees at the
        // top-left, and it must land inside the box.
        for (rotate in listOf(0, 90, 180, 270)) {
            val corner = pageToPdfUser(PagePoint(0f, 0f), croppedBox, rotate)
            assertTrue(
                corner.x in croppedBox.x0..croppedBox.x1 && corner.y in croppedBox.y0..croppedBox.y1,
                "/Rotate $rotate put the page origin at $corner, outside $croppedBox",
            )
        }

        // Unrotated, top-left of the page is the box's upper-left.
        assertEquals(PdfPoint(20f, 827f), pageToPdfUser(PagePoint(0f, 0f), croppedBox, 0))
        // /Rotate 90 turns the page clockwise, so the displayed top-left corner
        // is the box's *lower*-left.
        assertEquals(PdfPoint(20f, 35f), pageToPdfUser(PagePoint(0f, 0f), croppedBox, 90))
        assertEquals(PdfPoint(632f, 35f), pageToPdfUser(PagePoint(0f, 0f), croppedBox, 180))
        assertEquals(PdfPoint(632f, 827f), pageToPdfUser(PagePoint(0f, 0f), croppedBox, 270))
    }

    @Test
    fun `the opposite corner maps to the opposite corner`() {
        for (rotate in listOf(0, 90, 180, 270)) {
            val size = pageSizeOf(croppedBox, rotate)
            val far = pageToPdfUser(PagePoint(size.widthPt, size.heightPt), croppedBox, rotate)
            assertTrue(
                abs(far.x - croppedBox.x0) < 1e-3f || abs(far.x - croppedBox.x1) < 1e-3f,
                "/Rotate $rotate: far corner x $far is not a box edge",
            )
            assertTrue(
                abs(far.y - croppedBox.y0) < 1e-3f || abs(far.y - croppedBox.y1) < 1e-3f,
                "/Rotate $rotate: far corner y $far is not a box edge",
            )
        }
    }

    @Test
    fun `page to pdf to page round-trips for every rotation`() {
        for (rotate in listOf(0, 90, 180, 270, -90, 450)) {
            val size = pageSizeOf(croppedBox, rotate)
            val samples = listOf(
                PagePoint(0f, 0f),
                PagePoint(size.widthPt, size.heightPt),
                PagePoint(size.widthPt / 3f, size.heightPt / 7f),
                PagePoint(1.5f, size.heightPt - 0.25f),
            )
            for (p in samples) {
                val back = pdfUserToPage(pageToPdfUser(p, croppedBox, rotate), croppedBox, rotate)
                assertClose(p.x, back.x, "/Rotate $rotate x:")
                assertClose(p.y, back.y, "/Rotate $rotate y:")
            }
        }
    }

    @Test
    fun `a rotated page keeps points inside the box`() {
        // The failure this catches: treating /Rotate as a no-op, which puts the
        // bottom of a 792-tall page at PDF y = 827 - 792 on a box that is only
        // 612 wide once rotated.
        val size = pageSizeOf(croppedBox, 90)
        val mid = PagePoint(size.widthPt / 2f, size.heightPt / 2f)
        val pdf = pageToPdfUser(mid, croppedBox, 90)

        assertTrue(pdf.x in croppedBox.x0..croppedBox.x1, "x $pdf outside $croppedBox")
        assertTrue(pdf.y in croppedBox.y0..croppedBox.y1, "y $pdf outside $croppedBox")
    }

    /**
     * ROADMAP M2's acceptance criterion, spelled out: *"coordinate unit tests pass,
     * including a page with `/Rotate 90` and a non-zero-origin CropBox"*.
     *
     * The scenario is a scanned lecture handout — landscape slides scanned portrait,
     * so the producer wrote `/Rotate 90`, and the scan was cropped, so the CropBox
     * starts at (20, 35) and is smaller than the MediaBox. A stroke drawn at a known
     * place on screen must land at a known place in the file, and come back.
     */
    @Test
    fun `a rotate 90 page with a cropped — offset box converts exactly and reversibly`() {
        val mediaBox = PdfBox(0f, 0f, 652f, 862f)
        val rotate = 90

        // Rule 2: the page is sized from the CropBox. Sizing from the MediaBox here
        // would be 20pt too wide and 35pt too tall, which is the "annotations are 3mm
        // off" bug in its natural habitat.
        val size = pageSizeOf(croppedBox, rotate)
        assertEquals(PageSize(792f, 612f), size)
        assertTrue(pageSizeOf(mediaBox, rotate) != size, "the boxes must actually differ for this to prove anything")

        // A stroke 100pt right and 50pt down from the displayed top-left corner.
        val inked = PagePoint(100f, 50f)
        val pdf = pageToPdfUser(inked, croppedBox, rotate)

        // /Rotate 90 is applied clockwise by the viewer, so page-space +x runs up the
        // box in PDF user space and page-space +y runs right across it. Both offsets
        // come off the box's own origin, not off zero.
        assertClose(croppedBox.x0 + inked.y, pdf.x, "x:")
        assertClose(croppedBox.y0 + inked.x, pdf.y, "y:")
        assertEquals(PdfPoint(70f, 135f), pdf)

        // Inside the box, and inside the box's *cropped* extent rather than the media
        // one — a point that only fits the MediaBox is a point in the margin a viewer
        // will not show.
        assertTrue(pdf.x in croppedBox.x0..croppedBox.x1 && pdf.y in croppedBox.y0..croppedBox.y1)

        // And back, because M4 reads annotations as well as writing them.
        val back = pdfUserToPage(pdf, croppedBox, rotate)
        assertClose(inked.x, back.x, "round-trip x:")
        assertClose(inked.y, back.y, "round-trip y:")

        // All four displayed corners land on the four box corners — the check that
        // catches a rotation applied in the wrong direction, which round-tripping alone
        // does not, since an inverse of a wrong transform is still self-consistent.
        assertEquals(PdfPoint(20f, 35f), pageToPdfUser(PagePoint(0f, 0f), croppedBox, rotate))
        assertEquals(PdfPoint(20f, 827f), pageToPdfUser(PagePoint(size.widthPt, 0f), croppedBox, rotate))
        assertEquals(PdfPoint(632f, 35f), pageToPdfUser(PagePoint(0f, size.heightPt), croppedBox, rotate))
        assertEquals(
            PdfPoint(632f, 827f),
            pageToPdfUser(PagePoint(size.widthPt, size.heightPt), croppedBox, rotate),
        )
    }

    @Test
    fun `a degenerate box is a programming error`() {
        assertFailsWith<IllegalArgumentException> { PdfBox(10f, 10f, 10f, 20f) }
        assertFailsWith<IllegalArgumentException> { PdfBox(10f, 10f, 20f, 5f) }
    }
}
