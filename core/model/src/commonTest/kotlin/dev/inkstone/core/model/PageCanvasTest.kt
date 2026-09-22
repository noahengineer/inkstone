package dev.inkstone.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [PageCanvas] — where the paper is, how big it is, and how it grows.
 *
 * The arithmetic is small; what these assert is the two properties the rest of the design leans
 * on. **Grow-only**, which is what makes the canvas safe to re-derive from a page's own contents
 * after a merge — a device that folds a peer's ink in can only ever end up with a bigger canvas,
 * never one that leaves the peer's work outside the box. And **quantised**, which is what keeps a
 * page of writing from emitting a `SetPageMeta` per stroke.
 */
class PageCanvasTest {

    private val letter = PageCanvas.fixed(PageSize.Letter)

    @Test
    fun aFixedCanvasIsThePageAtTheOrigin() {
        assertEquals(0f, letter.left)
        assertEquals(0f, letter.top)
        assertEquals(PageSize.Letter.widthPt, letter.right)
        assertEquals(PageSize.Letter.heightPt, letter.bottom)
        assertFalse(letter.infinite)
    }

    @Test
    fun containsIsTheRectangleAndTheSlackWidensIt() {
        assertTrue(letter.contains(1f, 1f))
        assertTrue(letter.contains(0f, 0f), "the corner is on the paper")
        assertFalse(letter.contains(-1f, 100f))
        assertFalse(letter.contains(100f, -1f))
        assertFalse(letter.contains(PageSize.Letter.widthPt + 1f, 100f))

        assertTrue(letter.contains(-5f, 100f, slackPt = 6f), "a hand aiming at the edge lands a little past it")
        assertFalse(letter.contains(-7f, 100f, slackPt = 6f))
    }

    @Test
    fun containsFollowsTheOriginRatherThanAssumingZero() {
        val moved = letter.copy(origin = PagePoint(-396f, -396f))

        assertTrue(moved.contains(-300f, -300f), "the paper has grown up and to the left; this is on it")
        assertFalse(moved.contains(-400f, -300f))
        assertEquals(PageSize.Letter.widthPt - 396f, moved.right, "the extent is measured from the origin")
    }

    // -----------------------------------------------------------------------
    // Growth
    // -----------------------------------------------------------------------

    @Test
    fun contentAlreadyInsideGrowsNothingAtAll() {
        val inside = PageRect(100f, 100f, 300f, 300f)

        assertSame(letter, letter.grownToInclude(inside), "an unchanged canvas must be the same value")
    }

    @Test
    fun contentPastTheBottomGrowsDownwardInWholeSlabs() {
        val grown = letter.grownToInclude(PageRect(100f, 100f, 300f, PageSize.Letter.heightPt + 10f))

        assertEquals(0f, grown.top, "growing down must not move the top edge")
        assertEquals(0f, grown.left)
        assertEquals(PageSize.Letter.heightPt + PageCanvas.SLAB_PT, grown.bottom)
    }

    /**
     * The one that the origin exists for: writing *above* the page moves the origin negative and
     * changes nothing else. No object's coordinates move, which is what lets growth be a single
     * absolute op instead of a rewrite of the page (CLAUDE.md rule 8).
     */
    @Test
    fun contentAboveTheTopMovesTheOriginAndNotTheContent() {
        val grown = letter.grownToInclude(PageRect(-10f, -10f, 300f, 300f))

        assertEquals(-PageCanvas.SLAB_PT, grown.top)
        assertEquals(-PageCanvas.SLAB_PT, grown.left)
        assertEquals(PageSize.Letter.widthPt, grown.right, "the far edges must not move when the near ones do")
        assertEquals(PageSize.Letter.heightPt, grown.bottom)
    }

    @Test
    fun theMarginIsKeptClearBeyondTheInk() {
        // Ink stopping exactly at the edge still grows: the margin has to fit beyond it.
        val grown = letter.grownToInclude(PageRect(10f, 10f, PageSize.Letter.widthPt, 300f))

        assertNotEquals(letter.right, grown.right)
        assertTrue(
            grown.right >= PageSize.Letter.widthPt + PageCanvas.MARGIN_PT,
            "the paper's edge came out inside the margin, i.e. right against the writing",
        )
    }

    /**
     * ⭐ The property a merge rests on: growing for one device's ink and then the other's gives the
     * same paper whichever order they arrive in, and it holds both.
     *
     * The quantisation is what makes this worth asserting rather than assuming — the slabs are
     * counted from the *current* edge, so it is not obvious by inspection that two grows commute.
     * (They do: every edge only ever moves to a multiple of the slab away from where it started,
     * and the multiple is the smallest one that clears the furthest ink either device has.)
     */
    @Test
    fun growingIsOrderIndependentSoAMergeCannotLoseAPeersInk() {
        val mine = PageRect(0f, 0f, 300f, 3000f)
        val theirs = PageRect(0f, 0f, 3000f, 300f)

        val minefirst = letter.grownToInclude(mine).grownToInclude(theirs)
        val theirsFirst = letter.grownToInclude(theirs).grownToInclude(mine)

        assertEquals(minefirst.rect, theirsFirst.rect, "growth must not depend on the order content arrives in")
        assertTrue(
            minefirst.bottom >= mine.bottom && minefirst.right >= theirs.right,
            "somebody's ink is off the paper",
        )
    }

    @Test
    fun growthCarriesTheModeAndDoesNotTurnItOn() {
        val endless = letter.copy(infinite = true)

        assertTrue(endless.grownToInclude(PageRect(0f, 0f, 3000f, 300f)).infinite)
        assertFalse(
            letter.grownToInclude(PageRect(0f, 0f, 3000f, 300f)).infinite,
            "growth is not what makes a page infinite",
        )
    }

    @Test
    fun unionOrNullStartsFromNothingRatherThanFromTheOrigin() {
        val far = PageRect(900f, 900f, 950f, 950f)

        assertEquals(far, null.unionOrNull(far), "an empty accumulator must not drag the bounds back to (0, 0)")
        assertEquals(PageRect(100f, 100f, 950f, 950f), PageRect(100f, 100f, 200f, 200f).unionOrNull(far))
    }
}
