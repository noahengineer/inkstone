package dev.inkstone.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⭐ **Growing a page must not move anything already on it** — the identity the infinite canvas's
 * whole export story rests on, and the one part of it that is not obvious by inspection.
 *
 * Page space has its origin at the box's *displayed top-left* (SPEC §4.4). So enlarging the box to
 * reach a canvas that has grown upwards moves the very corner every conversion is measured from,
 * and every annotation on the page would slide by however much it grew — an imported lecture whose
 * ink walks off the writing the first time somebody scribbles in the margin above it.
 *
 * [PdfPageGeometry.origin] cancels that exactly. These assert it, for all four `/Rotate` values,
 * because the rotation is where an off-by-a-corner survives a spot check: an unrotated page's
 * growth happens to touch only `x0` and `y1`, which are the two the conversion reads, and the
 * quarter turns read the other two.
 */
class PageCanvasPdfTest {

    /** A cropped, non-zero-origin box — the shape SPEC §4.4 says most scanned material has. */
    private val sourceBox = PdfBox(x0 = 20f, y0 = 30f, x1 = 632f, y1 = 822f)

    private val rotations = listOf(0, 90, 180, 270)

    @Test
    fun growingThePageLeavesEveryExistingPointWhereItWas() {
        for (rotate in rotations) {
            val anchor = PdfPageGeometry(sourceBox, rotate)
            // The paper has grown a slab up, left, right and down around the source page.
            val canvas = PageCanvas.fixed(anchor.pageSize)
                .copy(infinite = true)
                .grownToInclude(PageRect(-300f, -300f, anchor.pageSize.widthPt + 300f, anchor.pageSize.heightPt + 300f))
            val published = PdfPageGeometry(anchor.boxFor(canvas), rotate, origin = canvas.origin)

            for (point in samplePoints(anchor.pageSize)) {
                val was = anchor.toPdfUser(point)
                val now = published.toPdfUser(point)
                // ⚠️ A tolerance, and it is the *right* assertion rather than a weakened one. The
                // cancellation is exact in arithmetic — `(y1 - oy) - (p.y - oy)` against
                // `y1 - p.y` — and in `Float` the two groupings differ in the last bit or two.
                // Measured worst case here: 1e-4 pt, which is 35 nanometres of paper.
                assertEquals(was.x, now.x, TOLERANCE, "/Rotate $rotate: x moved for $point")
                assertEquals(was.y, now.y, TOLERANCE, "/Rotate $rotate: y moved for $point")
            }
        }
    }

    @Test
    fun theGrownBoxHoldsTheWholeCanvas() {
        for (rotate in rotations) {
            val anchor = PdfPageGeometry(sourceBox, rotate)
            val canvas = PageCanvas.fixed(anchor.pageSize)
                .copy(infinite = true)
                .grownToInclude(PageRect(-500f, -100f, anchor.pageSize.widthPt + 40f, anchor.pageSize.heightPt + 900f))
            val box = anchor.boxFor(canvas)
            val published = PdfPageGeometry(box, rotate, origin = canvas.origin)

            for (corner in canvasCorners(canvas)) {
                val user = published.toPdfUser(corner)
                assertTrue(
                    user.x >= box.x0 - TOLERANCE && user.x <= box.x1 + TOLERANCE &&
                        user.y >= box.y0 - TOLERANCE && user.y <= box.y1 + TOLERANCE,
                    "/Rotate $rotate: the paper's own corner $corner landed at $user, outside the box $box",
                )
            }
            // And the box is the size a reader will show, which is the paper (SPEC §4.4 rule 3).
            assertEquals(canvas.size.widthPt, published.pageSize.widthPt, TOLERANCE, "/Rotate $rotate width")
            assertEquals(canvas.size.heightPt, published.pageSize.heightPt, TOLERANCE, "/Rotate $rotate height")
        }
    }

    @Test
    fun aFixedPageIsUntouchedByAnyOfThis() {
        for (rotate in rotations) {
            val anchor = PdfPageGeometry(sourceBox, rotate)
            val fixed = PageCanvas.fixed(anchor.pageSize)

            assertEquals(sourceBox, anchor.boxFor(fixed), "/Rotate $rotate: a fixed page's box must not change")
        }
    }

    @Test
    fun theConversionIsStillItsOwnInverse() {
        val anchor = PdfPageGeometry(sourceBox, 90)
        val canvas = PageCanvas.fixed(anchor.pageSize)
            .copy(infinite = true)
            .grownToInclude(PageRect(-200f, -200f, 0f, 0f))
        val published = PdfPageGeometry(anchor.boxFor(canvas), 90, origin = canvas.origin)

        for (point in samplePoints(anchor.pageSize) + canvasCorners(canvas)) {
            val round = published.toPage(published.toPdfUser(point))
            assertEquals(point.x, round.x, TOLERANCE, "x did not round-trip")
            assertEquals(point.y, round.y, TOLERANCE, "y did not round-trip")
        }
    }

    private fun samplePoints(size: PageSize) = listOf(
        PagePoint(0f, 0f),
        PagePoint(size.widthPt, 0f),
        PagePoint(size.widthPt, size.heightPt),
        PagePoint(0f, size.heightPt),
        PagePoint(size.widthPt / 3f, size.heightPt / 7f),
    )

    private fun canvasCorners(canvas: PageCanvas) = listOf(
        PagePoint(canvas.left, canvas.top),
        PagePoint(canvas.right, canvas.top),
        PagePoint(canvas.right, canvas.bottom),
        PagePoint(canvas.left, canvas.bottom),
    )

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
