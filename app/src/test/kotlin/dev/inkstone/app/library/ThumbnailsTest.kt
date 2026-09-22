package dev.inkstone.app.library

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.inkstone.app.note.InMemoryFileSystem
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.database.library.LibraryIndex
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.NoteSummary
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PageThumbnailRenderer
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.storage.ThumbnailCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC §5.7 — the three tiers, and the two revisions that decide which of them may answer.
 *
 * ⚠️ **Both halves of this file are regressions that were green in every other test**, because
 * every other test asks whether a thumbnail *looks* right and these ask whether the cache was
 * allowed to answer at all:
 *
 * - An on-demand cover render wrote its WebP and never recorded `thumbnailRev`, so tier 2 — which
 *   is gated on exactly that field — could never read back the file tier 3 had just written. The
 *   disk tier was dead for every note the materialize path had not touched, and every visit to the
 *   library re-rendered every cover from scratch (21 s for one 1000-stroke page on an emulator,
 *   two notes at a time).
 * - Page thumbnails had no freshness rule at all. They were kept honest by deleting the note's
 *   whole page-thumbnail directory on every two-second autosave tick — so while the user wrote,
 *   the page overview of the note they were writing in could never hit a cache.
 *
 * ⚠️ **What this file cannot reach.** `LibraryRepository` needs a `PdfGateway`, which needs
 * `androidx.ink`'s native code, so it cannot be constructed in a JVM test (CLAUDE.md). The
 * assertions below are against [Thumbnails]' own contract; that `LibraryRepository.noteSaved`
 * calls [Thumbnails.forgetDecodedCover] rather than [Thumbnails.invalidate], and that
 * `pageThumbnail` passes the index's `localRev`, is established by reading the call sites and by
 * driving the app — not by anything here. A green tick here is a claim about this class only.
 *
 * The decode itself is `BitmapFactory`'s and returns null under the mockable `android.jar`, so
 * every call below answers null. That is fine and is not what is being measured: what is measured
 * is **how many times the renderer ran** and **what the index and the filesystem hold afterwards**,
 * which is the whole of the question "was the cache allowed to answer".
 */
class ThumbnailsTest {

    private val noteId = DocumentId("note-1")
    private val pageId = PageId("page-1")

    private val fs = InMemoryFileSystem()
    private val cache = ThumbnailCache(fs)

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = InkstoneDatabase(driver).also { InkstoneDatabase.Schema.create(driver) }
    private val index = LibraryIndex(database, Dispatchers.Unconfined)

    /** Counts renders. The encoded bytes are opaque to everything under test. */
    private val renderer = object : PageThumbnailRenderer {
        var renders = 0
            private set

        override suspend fun render(noteId: DocumentId, page: FoldedPage, widthPx: Int): ByteArray? {
            renders++
            return byteArrayOf(1, 2, 3)
        }
    }

    private val thumbnails = Thumbnails(cache, renderer, index)

    private val folded = FoldedPage(
        pageId = pageId,
        canvas = PageCanvas.fixed(PageSize.Letter),
        background = Background.Blank,
        objects = emptyList(),
        deleted = emptySet(),
        throughClock = null,
        opCount = 0,
    )

    private suspend fun insertNoteAt(localRev: Long) = index.insertNote(
        NoteSummary(id = noteId, title = "Notebook", localRev = localRev),
    )

    private suspend fun summary(): NoteSummary = requireNotNull(index.note(noteId)) { "the fixture lost its row" }

    // -----------------------------------------------------------------------
    // The cover: writing a picture and dating it are one act
    // -----------------------------------------------------------------------

    @Test
    fun `an on-demand cover render dates the file it just wrote, so the disk tier can read it back`() = runTest {
        insertNoteAt(localRev = 5L)

        thumbnails.cover(summary()) { folded }

        assertEquals(1, renderer.renders)
        assertTrue("no cover was written at all", fs.exists(NoteLayout.coverThumbnail(noteId)))
        assertEquals(
            "the render did not record `thumbnailRev`, so the file it wrote can never be read back",
            5L,
            // -1 rather than a bare nullable: JUnit's Long/long overloads are ambiguous otherwise,
            // and no real revision is negative.
            summary().thumbnailRev ?: -1L,
        )
        assertTrue(summary().hasFreshThumbnail)

        // The point of recording it: the second visit to the library must not render again.
        thumbnails.cover(summary()) { folded }

        assertEquals("the cover it had just cached was re-rendered — the disk tier is dead", 1, renderer.renders)
    }

    @Test
    fun `a cover is dated at the revision it was folded from, never at the revision it finished at`() = runTest {
        // A render waits for a permit and then folds a page, and a save can land in either window.
        // Recording the revision as of *afterwards* would mark a picture of revision 5 as being of
        // revision 6 — and since `recordSave` clears `thumbnailRev` in the same statement that
        // bumps `localRev`, it would resurrect as fresh the very picture that save invalidated.
        insertNoteAt(localRev = 5L)
        val note = summary()

        thumbnails.cover(note) {
            index.recordSave(noteId, localRev = 6L, pageCount = 1, nowEpochMillis = 0L)
            folded
        }

        assertEquals("the picture was dated with a revision it does not contain", 5L, summary().thumbnailRev ?: -1L)
        assertFalse(
            "a cover of revision 5 was left looking fresh for revision 6 — the user sees a page they " +
                "have since written on",
            summary().hasFreshThumbnail,
        )
    }

    @Test
    fun `a materialized cover is dated too, at the revision the caller folded`() = runTest {
        insertNoteAt(localRev = 9L)

        thumbnails.refreshCover(noteId, localRev = 9L, page = folded)

        assertEquals(9L, summary().thumbnailRev ?: -1L)
        assertTrue(summary().hasFreshThumbnail)
    }

    // -----------------------------------------------------------------------
    // Page thumbnails: a revision instead of a directory delete every two seconds
    // -----------------------------------------------------------------------

    @Test
    fun `a cached page answers again at the same revision and not at the next one`() = runTest {
        thumbnails.page(noteId, pageId, revision = 5L) { folded }
        assertEquals(1, renderer.renders)

        thumbnails.page(noteId, pageId, revision = 5L) { folded }
        assertEquals("the page cache did not answer a repeat request at the same revision", 1, renderer.renders)

        // The autosave that used to delete this file now merely dates it out of the running.
        thumbnails.page(noteId, pageId, revision = 6L) { folded }
        assertEquals("a page thumbnail from revision 5 was served for revision 6", 2, renderer.renders)
    }

    @Test
    fun `a page thumbnail found on disk with no recorded revision is never served`() = runTest {
        // Exactly what a process killed between `recordSave` and the render that follows it leaves
        // behind — and what every process start sees, since the file carries no revision anywhere.
        cache.writePage(noteId, pageId, byteArrayOf(9, 9, 9))

        thumbnails.page(noteId, pageId, revision = 5L) { folded }

        assertEquals("an undated page thumbnail was trusted", 1, renderer.renders)
    }

    @Test
    fun `a page rendered without a known revision is not left looking fresh`() = runTest {
        thumbnails.page(noteId, pageId, revision = 5L) { folded }
        assertEquals(1, renderer.renders)

        // The note has no index row — the caller cannot say which revision this picture is of.
        thumbnails.page(noteId, pageId, revision = null) { folded }
        assertEquals(2, renderer.renders)

        // …so the file it overwrote must not be handed back to the next caller that can.
        thumbnails.page(noteId, pageId, revision = 5L) { folded }
        assertEquals("a picture written at an unknown revision was served as revision 5", 3, renderer.renders)
    }

    // -----------------------------------------------------------------------
    // Invalidation: the autosave one and the real one
    // -----------------------------------------------------------------------

    @Test
    fun `forgetting the decoded cover deletes nothing on disk`() = runTest {
        // This is what an autosave does now. It used to call `invalidate`, which recursively
        // deleted the whole page-thumbnail directory — every two seconds, for the whole time the
        // user was writing, against the one note whose page overview they were most likely to open.
        insertNoteAt(localRev = 5L)
        thumbnails.cover(summary()) { folded }
        thumbnails.page(noteId, pageId, revision = 5L) { folded }
        val rendersBefore = renderer.renders

        thumbnails.forgetDecodedCover(noteId)

        assertTrue("the autosave deleted the cover", fs.exists(NoteLayout.coverThumbnail(noteId)))
        assertTrue("the autosave deleted the page thumbnails", fs.exists(NoteLayout.pageThumbnail(noteId, pageId)))
        thumbnails.page(noteId, pageId, revision = 5L) { folded }
        assertEquals("the page cache was destroyed by an autosave", rendersBefore, renderer.renders)
    }

    @Test
    fun `invalidate still removes the files and forgets the page revisions`() = runTest {
        insertNoteAt(localRev = 5L)
        thumbnails.cover(summary()) { folded }
        thumbnails.page(noteId, pageId, revision = 5L) { folded }
        val rendersBefore = renderer.renders

        thumbnails.invalidate(noteId)

        assertFalse(fs.exists(NoteLayout.coverThumbnail(noteId)))
        assertFalse(fs.exists(NoteLayout.pageThumbnail(noteId, pageId)))
        // Forgetting the recorded revision matters as much as deleting the file: a later render
        // must not be able to hand back whatever happens to be at that path.
        thumbnails.page(noteId, pageId, revision = 5L) { folded }
        assertEquals(rendersBefore + 1, renderer.renders)
    }

    @Test
    fun `a page belonging to another note is left alone by an invalidation`() = runTest {
        val other = DocumentId("note-2")
        thumbnails.page(noteId, pageId, revision = 5L) { folded }
        thumbnails.page(other, pageId, revision = 5L) { folded }
        val rendersBefore = renderer.renders

        thumbnails.invalidate(noteId)

        thumbnails.page(other, pageId, revision = 5L) { folded }
        assertEquals("invalidating one note dropped another note's page cache", rendersBefore, renderer.renders)
        assertTrue("the other note's file was deleted too", fs.exists(NoteLayout.pageThumbnail(other, pageId)))
    }
}
