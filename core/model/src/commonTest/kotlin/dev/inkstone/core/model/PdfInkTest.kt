package dev.inkstone.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC §6.4's density rule and SPEC §5.6's `/NM` naming — the two decisions on the
 * ink→PDF boundary that are policy rather than geometry, and are therefore testable
 * without a PDF.
 */
class PdfInkTest {

    @Test
    fun `the density threshold is 500 and the boundary is inclusive`() {
        assertEquals(PageAnnotationMode.PER_STROKE, AnnotationDensity.modeFor(0))
        assertEquals(PageAnnotationMode.PER_STROKE, AnnotationDensity.modeFor(499))
        // SPEC §6.4: "<= 500 strokes/page: one annotation per stroke".
        assertEquals(PageAnnotationMode.PER_STROKE, AnnotationDensity.modeFor(500))
        assertEquals(PageAnnotationMode.FLATTENED, AnnotationDensity.modeFor(501))
        // The number SPEC §6.4 says will hang Preview and Chrome's PDFium.
        assertEquals(PageAnnotationMode.FLATTENED, AnnotationDensity.modeFor(5_000))
    }

    @Test
    fun `an annotation name round-trips its ObjectId`() {
        val id = ObjectId("0192f3c4-5678-7abc-8def-0123456789ab")
        assertEquals(id, AnnotationName.parse(AnnotationName.of(id)))
    }

    /**
     * The Foreign case, at the level of one annotation. CLAUDE.md: *"a PDF with ink
     * annotations and no embedded bundle is not necessarily ours — it might be a
     * stranger's Acrobat markup."* A name we did not write must not parse as an id,
     * or a stranger's annotation is adopted as one of our objects.
     */
    @Test
    fun `a name written by something else is not one of ours`() {
        assertNull(AnnotationName.parse(null))
        assertNull(AnnotationName.parse(""))
        assertNull(AnnotationName.parse("inkstone:"))
        assertNull(AnnotationName.parse("A2E9F1"))
        assertNull(AnnotationName.parse("Acrobat-1234"))
    }

    @Test
    fun `page geometry is the CropBox with rotate applied`() {
        // SPEC §4.4 rule 3's worked example, through the carrier type.
        val geometry = PdfPageGeometry(PdfBox(0f, 0f, 612f, 792f), rotate = 90)
        assertEquals(PageSize(792f, 612f), geometry.pageSize)
    }

    /**
     * The combination ROADMAP M4 calls out: `/Rotate 90` **and** an origin that is
     * not zero. Both are ordinary in scanned lecture PDFs and each breaks a
     * different naive implementation.
     */
    @Test
    fun `a rotated non-zero-origin page round-trips through PDF user space`() {
        val geometry = PdfPageGeometry(PdfBox(36f, 48f, 648f, 840f), rotate = 90)
        assertEquals(PageSize(792f, 612f), geometry.pageSize)

        for (point in listOf(PagePoint(0f, 0f), PagePoint(792f, 612f), PagePoint(123.5f, 400.25f))) {
            val back = geometry.toPage(geometry.toPdfUser(point))
            assertTrue(
                kotlin.math.abs(back.x - point.x) < TOLERANCE && kotlin.math.abs(back.y - point.y) < TOLERANCE,
                "round trip moved $point to $back",
            )
        }

        // The page-space origin is the top-left of the *displayed* page, which for a
        // clockwise quarter turn is the unrotated box's bottom-left corner.
        val origin = geometry.toPdfUser(PagePoint(0f, 0f))
        assertEquals(36f, origin.x)
        assertEquals(48f, origin.y)
    }

    @Test
    fun `an outline with no enclosing contour is empty`() {
        val id = ObjectId("x")
        assertTrue(StrokeOutline(id, emptyList(), emptyList(), 0, 1f).isEmpty)
        assertTrue(StrokeOutline(id, listOf(listOf(PagePoint(0f, 0f), PagePoint(1f, 1f))), emptyList(), 0, 1f).isEmpty)
        val triangle = listOf(PagePoint(0f, 0f), PagePoint(1f, 0f), PagePoint(0f, 1f))
        assertTrue(!StrokeOutline(id, listOf(triangle), emptyList(), 0, 1f).isEmpty)
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
