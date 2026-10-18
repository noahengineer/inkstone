package dev.inkstone.core.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The geometry behind scrolling through a note's pages like a PDF (`PageStrip`).
 *
 * The property that matters is the last block: a page turn must leave every point on the glass
 * where it was, because that is what makes two separate editable pages look like one scroll.
 */
class PageStripTest {

    private val letter = PageSize.Letter
    private val paper = PageCanvas.fixed(letter).rect

    private fun close(expected: Float, actual: Float) =
        assertTrue(abs(expected - actual) < TOLERANCE, "expected $expected but was $actual")

    @Test
    fun `the page below sits a gap under the paper and is centred on it`() {
        val below = PageStrip.below(paper, PageSize(400f, 500f))

        close(paper.bottom + PageStrip.GAP_PT, below.top)
        close(paper.centreX, below.centreX)
        close(500f, below.height)
    }

    @Test
    fun `the page above sits a gap over the paper and is centred on it`() {
        val above = PageStrip.above(paper, PageSize(400f, 500f))

        close(paper.top - PageStrip.GAP_PT, above.bottom)
        close(paper.centreX, above.centreX)
        close(500f, above.height)
    }

    @Test
    fun `a paper that has grown upwards is stacked from its own top`() {
        // An infinite page grown up has a negative top; the page above must hang from *that*.
        val grown = PageRect(0f, -396f, 612f, 792f)

        close(-396f - PageStrip.GAP_PT, PageStrip.above(grown, letter).bottom)
    }

    @Test
    fun `reach covers the paper and both neighbours and nothing else`() {
        val above = PageStrip.above(paper, letter)
        val below = PageStrip.below(paper, letter)

        val reach = PageStrip.reach(PageCanvas.fixed(letter), above, below).rect

        close(above.top, reach.top)
        close(below.bottom, reach.bottom)
        close(paper.left, reach.left)
        close(paper.right, reach.right)
    }

    @Test
    fun `reach with no neighbours is the paper`() {
        assertEquals(paper, PageStrip.reach(PageCanvas.fixed(letter), null, null).rect)
    }

    @Test
    fun `a wider neighbour widens the reach on both sides`() {
        val wide = PageStrip.below(paper, PageSize(1000f, 500f))

        val reach = PageStrip.reach(PageCanvas.fixed(letter), null, wide).rect

        close(wide.left, reach.left)
        close(wide.right, reach.right)
    }

    @Test
    fun `the paper itself never turns`() {
        val above = PageStrip.above(paper, letter)
        val below = PageStrip.below(paper, letter)

        assertEquals(PageStrip.STAY, PageStrip.turnFor(paper.top, above, below))
        assertEquals(PageStrip.STAY, PageStrip.turnFor(paper.centreY, above, below))
        assertEquals(PageStrip.STAY, PageStrip.turnFor(paper.bottom, above, below))
    }

    @Test
    fun `the gap does not turn - only entering a page does`() {
        val above = PageStrip.above(paper, letter)
        val below = PageStrip.below(paper, letter)

        // Dead centre of each gap, and a hair short of the neighbour.
        assertEquals(PageStrip.STAY, PageStrip.turnFor(paper.bottom + PageStrip.GAP_PT / 2f, above, below))
        assertEquals(PageStrip.STAY, PageStrip.turnFor(below.top - 0.01f, above, below))
        assertEquals(PageStrip.STAY, PageStrip.turnFor(paper.top - PageStrip.GAP_PT / 2f, above, below))
        assertEquals(PageStrip.STAY, PageStrip.turnFor(above.bottom + 0.01f, above, below))
    }

    @Test
    fun `entering a neighbour turns to it`() {
        val above = PageStrip.above(paper, letter)
        val below = PageStrip.below(paper, letter)

        assertEquals(PageStrip.NEXT, PageStrip.turnFor(below.top + 0.01f, above, below))
        assertEquals(PageStrip.PREVIOUS, PageStrip.turnFor(above.bottom - 0.01f, above, below))
    }

    @Test
    fun `there is nowhere to turn past the first or last page`() {
        val below = PageStrip.below(paper, letter)
        val above = PageStrip.above(paper, letter)

        // First page: nothing above. Last page: nothing below.
        assertEquals(PageStrip.STAY, PageStrip.turnFor(above.top, null, below))
        assertEquals(PageStrip.STAY, PageStrip.turnFor(below.bottom, above, null))
        assertEquals(PageStrip.STAY, PageStrip.turnFor(0f, null, null))
    }

    @Test
    fun `a pen landing on a neighbour finds it and one landing in the gap does not`() {
        val above = PageStrip.above(paper, letter)
        val below = PageStrip.below(paper, letter)

        assertEquals(PageStrip.NEXT, PageStrip.neighbourAt(300f, below.top + 10f, above, below))
        assertEquals(PageStrip.PREVIOUS, PageStrip.neighbourAt(300f, above.bottom - 10f, above, below))
        assertEquals(PageStrip.STAY, PageStrip.neighbourAt(300f, paper.bottom + PageStrip.GAP_PT / 2f, above, below))
        assertEquals(PageStrip.STAY, PageStrip.neighbourAt(-50f, below.top + 10f, above, below))
    }

    // --- The property the whole feature turns on ------------------------------------------------

    /**
     * Whatever the camera, a turn must leave the neighbour's pixels exactly where they were: the
     * neighbour's top-left corner on the glass before equals the new paper's top-left on the glass
     * after. If this failed the page would jump at the instant it became editable.
     */
    @Test
    fun `a turn leaves the page where it was on the glass`() {
        val camera = Camera(Offset(-37.5f, -812.25f), scale = 2.4f)
        val below = PageStrip.below(paper, letter)
        val newPaper = PageCanvas.fixed(letter).rect

        val after = PageStrip.carried(camera, below, newPaper)

        val before = pageToView(PagePoint(below.left, below.top), camera)
        val now = pageToView(PagePoint(newPaper.left, newPaper.top), after)
        close(before.x, now.x)
        close(before.y, now.y)
        close(camera.scale, after.scale)
    }

    @Test
    fun `a turn lands the new page at the top of the view when the centre had just crossed in`() {
        // Viewport 1000 x 1400 px at 2x. The centre (700 px) is on the boundary of the page below.
        val below = PageStrip.below(paper, letter)
        val scale = 2f
        val camera = Camera(Offset(0f, 700f - below.top * scale), scale)

        val after = PageStrip.carried(camera, below, PageCanvas.fixed(letter).rect)

        // The new page's top is where the old neighbour's top was: at the middle of the view.
        close(700f, after.translate.y)
    }

    @Test
    fun `a turn onto a page of a different width keeps its left edge in place`() {
        val camera = Camera(Offset(10f, -500f), scale = 1.5f)
        val narrow = PageStrip.below(paper, PageSize(400f, 500f))
        val newPaper = PageCanvas.fixed(PageSize(400f, 500f)).rect

        val after = PageStrip.carried(camera, narrow, newPaper)

        val before = pageToView(PagePoint(narrow.left, narrow.top), camera)
        val now = pageToView(PagePoint(0f, 0f), after)
        close(before.x, now.x)
        close(before.y, now.y)
    }

    @Test
    fun `turning forward and then back returns the camera it started with`() {
        val camera = Camera(Offset(-20f, -900f), scale = 2.2f)
        val below = PageStrip.below(paper, letter)
        val forward = PageStrip.carried(camera, below, paper)

        // In the new page's space the page we left is the one above.
        val above = PageStrip.above(paper, letter)
        val back = PageStrip.carried(forward, above, paper)

        close(camera.translate.x, back.translate.x)
        close(camera.translate.y, back.translate.y)
    }

    @Test
    fun `a picture is never wider than the screen or the ceiling`() {
        assertEquals(1200, PageStrip.pictureWidthPx(viewportWidthPx = 2560, ceilingPx = 1200))
        assertEquals(800, PageStrip.pictureWidthPx(viewportWidthPx = 800, ceilingPx = 1200))
        assertEquals(1, PageStrip.pictureWidthPx(viewportWidthPx = 0, ceilingPx = 1200))
    }

    private companion object {
        const val TOLERANCE = 1e-3f
    }
}
