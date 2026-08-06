package dev.inkstone.core.storage

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.PageId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC §5.7 — the thumbnail cache.
 *
 * The property worth testing is not that a write can be read back. It is that **a stale
 * thumbnail cannot be read at all**: invalidation deletes rather than overwrites, so a
 * crash between "the note changed" and "the picture was redrawn" is a miss, which costs a
 * render, and never a hit on the wrong picture, which the user has no way to notice.
 */
class ThumbnailCacheTest {

    private val noteA = DocumentId("note-a")
    private val noteB = DocumentId("note-b")
    private val page1 = PageId("page-1")
    private val page2 = PageId("page-2")

    @Test
    fun `a cover round-trips`() = runTest {
        val fs = FakeFileSystem()
        val cache = ThumbnailCache(fs)

        cache.writeCover(noteA, byteArrayOf(1, 2, 3))

        assertContentEquals(byteArrayOf(1, 2, 3), cache.readCover(noteA))
    }

    @Test
    fun `a missing thumbnail reads as null rather than throwing`() = runTest {
        val cache = ThumbnailCache(FakeFileSystem())

        assertNull(cache.readCover(noteA))
        assertNull(cache.readPage(noteA, page1))
    }

    @Test
    fun `nothing is written under the note directory`() = runTest {
        // The rule SPEC §5.7 exists for. A thumbnail inside the bundle inside the PDF would
        // mean 500 PDF opens to draw a library — so if one of these paths ever lands under
        // `notes/`, the design has quietly reverted.
        val fs = FakeFileSystem()
        val cache = ThumbnailCache(fs)

        cache.writeCover(noteA, byteArrayOf(1))
        cache.writePage(noteA, page1, byteArrayOf(2))

        assertTrue(fs.paths.none { it.startsWith(NoteLayout.NOTES_DIR) }, "a thumbnail was written into a note")
        assertTrue(fs.paths.all { it.startsWith(NoteLayout.THUMBNAIL_CACHE_DIR) })
    }

    @Test
    fun `invalidating a note drops its cover and every page`() = runTest {
        val fs = FakeFileSystem()
        val cache = ThumbnailCache(fs)
        cache.writeCover(noteA, byteArrayOf(1))
        cache.writePage(noteA, page1, byteArrayOf(2))
        cache.writePage(noteA, page2, byteArrayOf(3))
        cache.writeCover(noteB, byteArrayOf(4))

        cache.invalidate(noteA)

        assertNull(cache.readCover(noteA))
        assertNull(cache.readPage(noteA, page1))
        assertNull(cache.readPage(noteA, page2))
        // The other note is untouched. Invalidation is per note, and a cache that cleared
        // itself on every save would mean re-rendering the whole library after one stroke.
        assertContentEquals(byteArrayOf(4), cache.readCover(noteB))
    }

    @Test
    fun `eviction removes only the notes it is given`() = runTest {
        val fs = FakeFileSystem()
        val cache = ThumbnailCache(fs)
        cache.writeCover(noteA, byteArrayOf(1))
        cache.writeCover(noteB, byteArrayOf(2))

        assertEquals(1, cache.evict(listOf(noteA)))

        assertNull(cache.readCover(noteA))
        assertContentEquals(byteArrayOf(2), cache.readCover(noteB))
    }

    @Test
    fun `evicting a note with nothing cached is not counted`() = runTest {
        val cache = ThumbnailCache(FakeFileSystem())

        assertEquals(0, cache.evict(listOf(noteA, noteB)))
    }

    @Test
    fun `orphans are swept and known notes are not`() = runTest {
        // A note purged from the trash leaves no row to compare a revision against, so its
        // images would sit there forever. This is the one direction of drift the freshness
        // rule cannot catch.
        val fs = FakeFileSystem()
        val cache = ThumbnailCache(fs)
        cache.writeCover(noteA, byteArrayOf(1))
        cache.writePage(noteA, page1, byteArrayOf(2))
        cache.writeCover(noteB, byteArrayOf(3))

        val swept = cache.sweepOrphans(known = setOf(noteB))

        assertTrue(swept >= 1)
        assertNull(cache.readCover(noteA))
        assertNull(cache.readPage(noteA, page1))
        assertContentEquals(byteArrayOf(3), cache.readCover(noteB))
    }
}
