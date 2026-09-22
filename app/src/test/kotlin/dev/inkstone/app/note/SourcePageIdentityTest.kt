package dev.inkstone.app.note

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageRect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ⛔ **An imported PDF opened with white paper where its page should have been, and the note was
 * perfect underneath it.**
 *
 * Reported from the tablet: *"the preview shows the image but when I enter it is completely
 * blank"*, and then the sentence that solved it — *"if I add a page and go back to the first page
 * I can see it properly"*.
 *
 * `NoteEditor.openOrCreate` ends `bindSurface()` → `display()` → `publish()`. `display` is what
 * calls [NoteEditor.NoteSurface.loadPage], and `:app`'s adapter answers it by *launching* a request
 * for the page's source PDF — which asked `NoteEditorState.documentId` **which `publish` has not
 * written yet**. Null means "this page has no PDF behind it", which is a legitimate answer for a
 * blank page, so it painted white paper and said nothing. Nothing retries: the source page is
 * fetched once per `loadPage`, so turning to page 2 and back was the only cure.
 *
 * It is a race, and it was always losable — a note adopted from Nextcloud lost it every time,
 * because that open follows a burst of index, binding and outbox writes and the coroutine ordering
 * changes. That is why the fix is not a re-fetch or a delay: the id is simply read from the wrong
 * place. [NoteEditor.openDocumentId] is what the editor *is*; the state flow is what the screen has
 * been *told*.
 *
 * ⚠️ This test was checked by reverting the fix and watching it go red — a test for an ordering
 * bug that passes either way is worse than none (CLAUDE.md).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourcePageIdentityTest {

    @Test
    fun `the editor can name its note before it loads the first page`() = runTest {
        val fs = InMemoryFileSystem()
        val surface = IdentityProbingSurface()
        val editor = NoteEditor(
            fs = fs,
            clock = TestClock(),
            scope = this,
            drainDispatcher = UnconfinedTestDispatcher(testScheduler),
            autosaveIntervalMs = Long.MAX_VALUE,
        )
        surface.editor = editor
        editor.onSurfaceAttached(surface)
        runCurrent()

        assertNotNull("the page was never loaded, so this test proves nothing", surface.loaded)
        assertEquals("the page was loaded more than once", 1, surface.loadCount)
        // ⭐ The assertion the blank paper was made of. Without it, `sourcePage` answers null for
        // the page the user is looking at and the imported scan never appears.
        assertNotNull(
            "the editor could not say which note it had open while loading that note's first " +
                "page, so anything keyed on the note id answers null and the page draws blank",
            surface.idWhileLoading,
        )
        editor.close()
    }

    @Test
    fun `the published state does not carry the note id yet when the first page loads`() = runTest {
        // ⚠️ Not a wish — a record of *why* the id may not be read from the state flow here, so
        // that a later change putting `sourcePage` back on `state` fails with a reason attached.
        // If this ever goes red because `publish()` moved earlier, that is good news: delete it and
        // say so.
        val surface = IdentityProbingSurface()
        val editor = NoteEditor(
            fs = InMemoryFileSystem(),
            clock = TestClock(),
            scope = this,
            drainDispatcher = UnconfinedTestDispatcher(testScheduler),
            autosaveIntervalMs = Long.MAX_VALUE,
        )
        surface.editor = editor
        editor.onSurfaceAttached(surface)
        runCurrent()

        assertNull(
            "the state flow now carries the id during the first load — `sourcePage` could read " +
                "it again, and this test has outlived its subject",
            surface.stateIdWhileLoading,
        )
        editor.close()
    }

    /** Fixed, like every other editor test here: nothing in this one is about time. */
    private class TestClock : Clock {
        override fun nowEpochMillis(): Long = 1_800_000_000_000
    }

    /**
     * A surface that answers `loadPage` by asking the same two questions `:app`'s real adapter
     * asks, at the same moment.
     *
     * Not [FakeSurface] with a flag: the point is what is readable *during* the call, which a
     * recording fake cannot express — the value has to be sampled inside `loadPage` or the test is
     * about a different instant than the bug.
     */
    private class IdentityProbingSurface : NoteEditor.NoteSurface {
        lateinit var editor: NoteEditor

        var loaded: FoldedPage? = null
            private set

        var loadCount: Int = 0
            private set

        /** What `NoteEditorViewModel.sourcePage` reads now. */
        var idWhileLoading: DocumentId? = null
            private set

        /** What it read before, and what made the page blank. */
        var stateIdWhileLoading: DocumentId? = null
            private set

        override fun bindPage(nextObjectId: () -> ObjectId, nextOpId: () -> String, nextClock: () -> LamportClock) =
            Unit

        override suspend fun loadPage(page: FoldedPage) {
            loaded = page
            loadCount++
            idWhileLoading = editor.openDocumentId
            stateIdWhileLoading = editor.state.value.documentId
        }

        override fun drainOps(): List<List<PageOp>> = emptyList()

        override fun applyOps(ops: List<PageOp>, record: Boolean) = Unit

        override fun highlight(box: PageRect?) = Unit

        override fun setPageMeta(canvas: PageCanvas, background: Background) = Unit
    }
}
