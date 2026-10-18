package dev.inkstone.app.note

import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.PageId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The pictures beside the page being edited are reused exactly as long as they are still pictures
 * of that page. A picture served after its page changed is a neighbour that shows last week's
 * writing, which is the one thing a stand-in must not do.
 *
 * Nothing here draws a bitmap, so a stand-in object is enough: the cache's job is identity and
 * revision.
 */
class PageStripCacheTest {

    /** A picture is anything the cache can hold by identity; a bitmap cannot be built on the JVM. */
    private fun picture(page: PageId): Any = "picture of ${page.value}".let { StandIn(it) }

    private class StandIn(val label: String)

    private fun revision(opCount: Int, widthPx: Int = WIDTH) =
        PageRevision(opCount, LamportClock(opCount.toLong(), "device"), widthPx)

    @Test
    fun `a picture is served again for the same revision`() {
        val cache = PageStripCache<Any>()
        val page = PageId("p1")
        val stored = picture(page)
        cache.put(page, revision(3), stored)

        assertSame(stored, cache.get(page, revision(3)))
    }

    @Test
    fun `a page that has changed is not served its old picture`() {
        val cache = PageStripCache<Any>()
        val page = PageId("p1")
        cache.put(page, revision(3), picture(page))

        assertNull(cache.get(page, revision(4)))
    }

    @Test
    fun `a picture drawn narrower is not the picture a wider view wants`() {
        val cache = PageStripCache<Any>()
        val page = PageId("p1")
        cache.put(page, revision(3, widthPx = 800), picture(page))

        assertNull(cache.get(page, revision(3, widthPx = 1200)))
    }

    @Test
    fun `pictures of different pages do not shadow one another`() {
        val cache = PageStripCache<Any>()
        val first = PageId("p1")
        val second = PageId("p2")
        val a = picture(first)
        val b = picture(second)
        cache.put(first, revision(1), a)
        cache.put(second, revision(1), b)

        assertSame(a, cache.get(first, revision(1)))
        assertSame(b, cache.get(second, revision(1)))
    }

    @Test
    fun `the cache is bounded and drops the one used longest ago`() {
        val cache = PageStripCache<Any>(capacity = 3)
        val pages = (1..4).map { PageId("p$it") }
        cache.put(pages[0], revision(1), picture(pages[0]))
        cache.put(pages[1], revision(1), picture(pages[1]))
        cache.put(pages[2], revision(1), picture(pages[2]))
        // Touch the first, so the second is now the oldest.
        cache.get(pages[0], revision(1))
        cache.put(pages[3], revision(1), picture(pages[3]))

        assertNotEquals(null, cache.get(pages[0], revision(1)))
        assertNull(cache.get(pages[1], revision(1)))
        assertNotEquals(null, cache.get(pages[2], revision(1)))
        assertNotEquals(null, cache.get(pages[3], revision(1)))
    }

    @Test
    fun `a revision is equal to another only when everything about it is`() {
        assertEquals(revision(2), revision(2))
        assertNotEquals(revision(2), revision(3))
        assertNotEquals(revision(2), PageRevision(2, LamportClock(99, "other"), WIDTH))
    }

    private companion object {
        const val WIDTH = 1200
    }
}
