package dev.inkstone.android.ink

import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PageStrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions behind scrolling through a note's pages like a PDF: which pictures are taken, where
 * they sit, when a crossing asks for a turn, and what the strip is the instant after one.
 *
 * ⚠️ **What this cannot see** is everything Android: that the dry layer draws the pictures, that the
 * camera is carried across a turn without a visible jump, and how any of it feels under a finger.
 * Those are `docs/PAGE-SCROLL.md` §6's device checks. What it *can* see is the part that decides —
 * and a decision is where a ping-pong, a double turn or a stale picture would come from.
 */
class PageStripControllerTest {

    private class Picture(override val pageId: PageId, override val size: PageSize = PageSize.Letter) : PagePicture

    private val first = PageId("first")
    private val second = PageId("second")
    private val third = PageId("third")
    private val paper = PageCanvas.fixed(PageSize.Letter).rect

    private var shownAbove: Placed<Picture>? = null
    private var shownBelow: Placed<Picture>? = null
    private var shown = 0

    private fun controller(enabled: Boolean = true) = PageStripController<Picture> { above, below ->
        shownAbove = above
        shownBelow = below
        shown++
    }.also {
        it.enabled = enabled
        it.activePageId = second
    }

    private fun PageStripController<Picture>.withBoth(): Triple<Picture, Picture, Picture> {
        val above = Picture(first)
        val self = Picture(second)
        val below = Picture(third)
        assertTrue(set(second, above, self, below, paper))
        return Triple(above, self, below)
    }

    // --- What is taken ----------------------------------------------------------------------

    @Test
    fun `pictures for a page that is not on screen are dropped`() {
        val strip = controller()

        // A render finishes after the user has turned the page: it belongs to somebody else.
        assertFalse(strip.set(first, Picture(third), null, Picture(third), paper))
        assertNull(shownAbove)
        assertNull(shownBelow)
    }

    @Test
    fun `nothing is taken while the setting is off`() {
        val strip = controller(enabled = false)

        assertFalse(strip.set(second, Picture(first), null, Picture(third), paper))
        assertNull(strip.aboveRect)
        assertNull(strip.crossing(centreY = 10_000f))
    }

    @Test
    fun `pictures are placed a gap from the paper and handed to the layer that draws them`() {
        val strip = controller()
        val (above, _, below) = strip.withBoth()

        assertSame(above, shownAbove?.picture)
        assertSame(below, shownBelow?.picture)
        assertEquals(PageStrip.above(paper, PageSize.Letter), shownAbove?.rect)
        assertEquals(PageStrip.below(paper, PageSize.Letter), shownBelow?.rect)
    }

    @Test
    fun `a first page has nothing above it`() {
        val strip = controller()
        strip.set(second, null, Picture(second), Picture(third), paper)

        assertNull(shownAbove)
        assertNotNull(shownBelow)
        assertNull("nowhere to go past the first page", strip.crossing(centreY = -10_000f))
    }

    @Test
    fun `the camera may reach the neighbours only when there are some`() {
        val strip = controller()
        val canvas = PageCanvas.fixed(PageSize.Letter)
        assertEquals(canvas, strip.reach(canvas))

        strip.withBoth()
        val reach = strip.reach(canvas).rect
        assertEquals(strip.aboveRect!!.top, reach.top, 0.001f)
        assertEquals(strip.belowRect!!.bottom, reach.bottom, 0.001f)
    }

    // --- When a turn is asked for -------------------------------------------------------------

    @Test
    fun `entering the page below asks to turn to it and the gap does not`() {
        val strip = controller()
        val (_, _, below) = strip.withBoth()
        val belowRect = strip.belowRect!!

        assertNull(strip.crossing(paper.bottom + PageStrip.GAP_PT / 2f))
        val turn = strip.crossing(belowRect.top + 1f)
        assertSame(below, turn?.target)
        assertEquals(belowRect, turn?.rect)
    }

    @Test
    fun `entering the page above asks to turn back to it`() {
        val strip = controller()
        val (above, _, _) = strip.withBoth()

        assertSame(above, strip.crossing(strip.aboveRect!!.bottom - 1f)?.target)
    }

    @Test
    fun `one crossing asks once however many frames it spans`() {
        val strip = controller()
        strip.withBoth()
        val turn = strip.crossing(strip.belowRect!!.top + 1f)!!

        strip.begin(turn)

        // The same pan, a frame later, still inside the neighbour: no second request.
        assertNull(strip.crossing(strip.belowRect!!.top + 5f))
        strip.end()
        assertNotNull("a refused turn must not wedge the next", strip.crossing(strip.belowRect!!.top + 5f))
    }

    @Test
    fun `a pen landing on a neighbour means that page and one in the gap means nothing`() {
        val strip = controller()
        val (_, _, below) = strip.withBoth()
        val belowRect = strip.belowRect!!

        assertSame(below, strip.landing(belowRect.centreX, belowRect.top + 10f)?.target)
        assertNull(strip.landing(belowRect.centreX, paper.bottom + PageStrip.GAP_PT / 2f))
        assertNull(strip.landing(belowRect.right + 50f, belowRect.top + 10f))
    }

    // --- The turn itself ----------------------------------------------------------------------

    @Test
    fun `a turn is taken once and only by the page it was for`() {
        val strip = controller()
        val (_, _, below) = strip.withBoth()
        strip.begin(strip.crossing(strip.belowRect!!.top + 1f)!!)

        // The page bar turned somewhere else first: that is an ordinary load, with the ordinary refit.
        assertNull(strip.take(first))
        // And it consumed the pending turn, so it cannot leak into a later load.
        assertNull(strip.take(third))

        strip.begin(StripTurn(below, strip.belowRect!!))
        assertSame(below, strip.take(third)?.target)
        assertNull(strip.take(third))
    }

    @Test
    fun `after scrolling forward the page just left is above and the page turned to is the active one`() {
        val strip = controller()
        val (_, self, below) = strip.withBoth()

        strip.rotate(below)
        strip.activePageId = third
        strip.layout(paper)

        assertSame("the page just left sits where the camera came from", self, shownAbove?.picture)
        assertNull("what is below the new page is not known yet", shownBelow)
    }

    @Test
    fun `after scrolling back the page just left is below`() {
        val strip = controller()
        val (above, self, _) = strip.withBoth()

        strip.rotate(above)
        strip.activePageId = first
        strip.layout(paper)

        assertSame(self, shownBelow?.picture)
        assertNull(shownAbove)
    }

    @Test
    fun `any other page load empties the strip because it described a different page`() {
        val strip = controller()
        strip.withBoth()

        strip.rotate(null)
        strip.layout(paper)

        assertNull(shownAbove)
        assertNull(shownBelow)
    }

    @Test
    fun `switching the setting off forgets everything and tells the layer`() {
        val strip = controller()
        strip.withBoth()
        val before = shown

        strip.enabled = false

        assertTrue("the layer was told", shown > before)
        assertNull(shownAbove)
        assertNull(shownBelow)
        assertNull(strip.aboveRect)
        assertNull(strip.belowRect)
    }

    @Test
    fun `a neighbour of another width hangs centred on the paper`() {
        val strip = controller()
        strip.set(second, null, null, Picture(third, PageSize(400f, 500f)), paper)

        val rect: PageRect = strip.belowRect!!
        assertEquals(paper.centreX, rect.centreX, 0.001f)
        assertEquals(400f, rect.width, 0.001f)
    }
}
