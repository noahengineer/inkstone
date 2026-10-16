package dev.inkstone.android.ink

import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SPEC §8.4 — when a search hit's box is drawn, and when it goes.
 *
 * ⚠️ **What this cannot see is when the camera is centred**, which was the half that was broken:
 * `InkSurfaceView.highlight` moved the camera before the page load's refit, and the refit won.
 * That ordering lives in the view (`fitOrFrame` ends by centring), and the emulator walk is what
 * checks it: search, tap the hit, and the word is in the middle of the screen.
 */
class SearchHitTrackerTest {

    private val word = PageRect(left = 4f, top = 1032f, right = 92f, bottom = 1052f)
    private val hitPage = PageId("hit-page")
    private val otherPage = PageId("other-page")

    @Test
    fun `a hit that arrives before any page has loaded is drawn on the first page to load`() {
        // The note opens because of the hit, so the hit can be delivered before the surface has
        // shown anything at all.
        val tracker = SearchHitTracker()
        tracker.hit(word)
        assertNull("nothing is on screen to draw it on", tracker.drawn)

        tracker.pageLoading(hitPage)

        assertEquals(word, tracker.drawn)
    }

    @Test
    fun `a hit that arrives while its page is on screen is drawn at once`() {
        val tracker = SearchHitTracker()
        tracker.pageLoading(hitPage)

        tracker.hit(word)

        assertEquals(word, tracker.drawn)
    }

    @Test
    fun `before it has been centred a hit survives another page loading`() {
        // The open loads the page the note was left on, then turns to the hit's page. A hit that
        // went with the first load would never be seen.
        val tracker = SearchHitTracker()
        tracker.hit(word)
        tracker.pageLoading(otherPage)
        tracker.pageLoading(hitPage)

        assertEquals(word, tracker.drawn)
    }

    @Test
    fun `once centred a hit belongs to its page and goes when the user leaves it`() {
        // Kept, its box was drawn at the same coordinates on every page turned to afterwards.
        val tracker = SearchHitTracker()
        tracker.pageLoading(hitPage)
        tracker.hit(word)
        tracker.centred()

        tracker.pageLoading(otherPage)
        assertNull("the hit was drawn on a page it is not on", tracker.drawn)

        tracker.pageLoading(hitPage)
        assertNull("a hit the user has seen and left does not come back", tracker.drawn)
    }

    @Test
    fun `reloading the page the hit is on keeps it`() {
        val tracker = SearchHitTracker()
        tracker.pageLoading(hitPage)
        tracker.hit(word)
        tracker.centred()

        tracker.pageLoading(hitPage)

        assertEquals(word, tracker.drawn)
    }

    @Test
    fun `null clears the hit`() {
        val tracker = SearchHitTracker()
        tracker.pageLoading(hitPage)
        tracker.hit(word)

        tracker.hit(null)

        assertNull(tracker.drawn)
    }
}
