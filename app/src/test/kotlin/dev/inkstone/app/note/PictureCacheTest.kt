package dev.inkstone.app.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PictureCache] — the policy half of the editor's picture cache, over a fake picture whose size is
 * its only property.
 *
 * Driven the way `PageAssetCache` drives it: the painter calls [PictureCache.get] while recording a
 * page, a decode takes what is wanted and reports each name back. `Bitmap` cannot be built in a JVM
 * test, so the Android half — `BitmapFactory`, the dispatcher hop, the surface being told — is
 * covered on the emulator instead (docs/IMAGES-PERF-2026-10.md §4), and this file says so rather than
 * letting a green tick imply it.
 */
class PictureCacheTest {

    /** A decoded picture, as far as the cache can tell: a size, and whether it has been recycled. */
    private class Picture(val bytes: Long, var recycled: Boolean = false)

    private fun cache(budget: Long) = PictureCache<Picture>(
        budgetBytes = budget,
        sizeOf = { it.bytes },
        isUsable = { !it.recycled },
    )

    /** One decode pass: everything wanted, decoded at [bytesEach], reported back. */
    private fun PictureCache<Picture>.decodeWanted(bytesEach: Long): Set<String> {
        val batch = takeWanted()
        for (name in batch) decoded(name, Picture(bytesEach))
        return batch
    }

    @Test
    fun `a page of eleven photographs all stays held - the physics note`() {
        // The bug: 48 MiB was four 12 MiB photographs, so seven of eleven drew the crossed box and
        // every edit decoded them again. The budget the tablet gets now is 192 MiB.
        val cache = cache(budget = 192 * MIB)
        val names = (1..11).map { "assets/img-$it.webp" }
        cache.beginPage(names)
        assertEquals(1, cache.sampleSizeFor(names.map { PHOTO }))
        assertEquals(names.toSet(), cache.decodeWanted(PHOTO))

        for (name in names) assertNotNull("$name was evicted", cache.get(name))
        assertFalse("nothing should be wanted again once the page is held", cache.hasWanted())
    }

    @Test
    fun `the old budget evicts the page it is drawing - which is the bug`() {
        // Kept as a test so the arithmetic in the KDoc is checked rather than asserted: at the old
        // 48 MiB, a page of eleven holds four — and with the page protected from eviction it holds
        // all eleven anyway, over budget, rather than thrash.
        val cache = cache(budget = 48 * MIB)
        val names = (1..11).map { "assets/img-$it.webp" }
        cache.beginPage(names)
        // What the new policy does with too small a budget: decode smaller, not partly.
        assertEquals(2, cache.sampleSizeFor(names.map { PHOTO }))
        cache.decodeWanted(PHOTO / 4)
        for (name in names) assertNotNull("$name was evicted", cache.get(name))
        assertTrue(cache.held <= 48 * MIB)
    }

    @Test
    fun `a page too big for the budget is decoded smaller rather than partly`() {
        val cache = cache(budget = 192 * MIB)
        val thirty = (1..30).map { "assets/img-$it.webp" }
        cache.beginPage(thirty)
        // 30 × 12 MiB = 360 MiB > 192; at a 2× downsample it is 90 MiB.
        assertEquals(2, cache.sampleSizeFor(thirty.map { PHOTO }))
        // And the cap holds for an absurd page.
        assertEquals(PictureCache.MAX_SAMPLE, cache.sampleSizeFor(List(10_000) { PHOTO }))
    }

    @Test
    fun `a miss asks for the picture once and is pending until it lands`() {
        val cache = cache(budget = 192 * MIB)
        assertNull(cache.get("assets/a.webp"))
        assertTrue(cache.isPending("assets/a.webp"))
        // Asked again while the first request is queued or in flight: still one request.
        assertNull(cache.get("assets/a.webp"))
        val batch = cache.takeWanted()
        assertEquals(setOf("assets/a.webp"), batch)
        assertNull(cache.get("assets/a.webp"))
        assertTrue("in flight is still pending", cache.isPending("assets/a.webp"))
        assertFalse("an in-flight picture must not be requested twice", cache.hasWanted())

        cache.decoded("assets/a.webp", Picture(PHOTO))
        assertFalse(cache.isPending("assets/a.webp"))
        assertNotNull(cache.get("assets/a.webp"))
    }

    @Test
    fun `a picture with no bytes is missing rather than pending until the note changes`() {
        // A peer's image arrives as an object one poll before its bytes. Asking for it on every
        // frame would be a decode loop; drawing it as "loading" for ever would be a lie.
        val cache = cache(budget = 192 * MIB)
        cache.get("assets/peer.webp")
        cache.takeWanted()
        cache.decoded("assets/peer.webp", null)

        assertFalse(cache.isPending("assets/peer.webp"))
        assertNull(cache.get("assets/peer.webp"))
        assertFalse("a missing picture must not be re-requested per frame", cache.hasWanted())

        assertTrue(cache.retryAbsent())
        assertTrue(cache.hasWanted())
        assertTrue(cache.isPending("assets/peer.webp"))
        assertFalse("nothing left to retry", cache.retryAbsent())
    }

    @Test
    fun `pictures from another page are what gets dropped - least recently drawn first`() {
        val cache = cache(budget = 3 * PHOTO)
        cache.beginPage(listOf("p1-a", "p1-b"))
        cache.decodeWanted(PHOTO)
        cache.get("p1-a") // p1-b is now the least recently drawn

        cache.beginPage(listOf("p2-a", "p2-b"))
        cache.decodeWanted(PHOTO)

        assertNull("the least recently drawn picture of the old page goes first", cache.get("p1-b"))
        assertNotNull(cache.get("p1-a"))
        assertNotNull(cache.get("p2-a"))
        assertNotNull(cache.get("p2-b"))
    }

    @Test
    fun `a picture on screen is never dropped to make room`() {
        val cache = cache(budget = 2 * PHOTO)
        cache.beginPage(listOf("a", "b", "c"))
        cache.decodeWanted(PHOTO)
        // Over budget with only this page's pictures held: tolerated, never thrashed.
        for (name in listOf("a", "b", "c")) assertNotNull("$name was dropped while on screen", cache.get(name))
    }

    @Test
    fun `a recycled picture is decoded again rather than drawn as nothing`() {
        val cache = cache(budget = 192 * MIB)
        cache.beginPage(listOf("a"))
        val decoded = Picture(PHOTO)
        cache.takeWanted()
        cache.decoded("a", decoded)
        decoded.recycled = true
        assertNull(cache.get("a"))
        assertTrue(cache.isPending("a"))
        assertEquals(0L, cache.held)
    }

    private companion object {
        const val MIB = 1024L * 1024L

        /** A 2,048 × 1,536 photograph decoded to ARGB_8888 — every asset on the physics note. */
        const val PHOTO = 2048L * 1536L * 4L
    }
}
