package dev.inkstone.app.note

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageTemplate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ SPEC §8.4 — *"a tap scrolls to and highlights the word"* — when the word is not on the page the
 * note opens at.
 *
 * Found on the tablet, by tapping a search hit in a 37-page note: the editor opened on page 1 and drew
 * the hit's box on empty paper there. `NoteEditorViewModel` asks for the hit's page from its `init`,
 * which is always before the note has opened, and `showPage` went through `withPage`, which reads
 * `session` before it queues — so the turn was dropped. Every hit in a one-page note still worked,
 * which is what the earlier walk had used.
 *
 * What is asserted here is the editor's half: the turn survives being asked for during the open. The
 * other half — the box is sent only after the turn — is the view model's, which needs Hilt to build,
 * and was checked on the tablet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorShowPageTest {

    private fun TestScope.editor(fs: FileSystem, documentId: DocumentId? = null) = NoteEditor(
        fs = fs,
        clock = Clock { NOW },
        scope = this,
        drainDispatcher = UnconfinedTestDispatcher(testScheduler),
        autosaveIntervalMs = Long.MAX_VALUE,
        documentId = documentId,
    )

    /** A three-page note on [fs], left on its **first** page, so a reopen does not land on the third by itself. */
    private fun TestScope.threePageNote(fs: FileSystem): Pair<DocumentId, List<PageId>> {
        val editor = editor(fs)
        editor.onSurfaceAttached(FakeSurface())
        runCurrent()
        editor.addPage(PageTemplate.BLANK)
        runCurrent()
        editor.addPage(PageTemplate.BLANK)
        runCurrent()
        editor.goToPage(0)
        runCurrent()
        val state = editor.state.value
        assertEquals("the fixture did not make three pages", 3, state.pageIds.size)
        assertEquals("the fixture was not left on its first page", 0, state.pageIndex)
        val id = requireNotNull(state.documentId) { "the fixture note never opened" }
        editor.close()
        return id to state.pageIds
    }

    @Test
    fun `a page asked for while the note is still opening is turned to once it has opened`() = runTest {
        val fs = InMemoryFileSystem()
        val (id, pages) = threePageNote(fs)

        val editor = editor(fs, documentId = id)
        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        // The view model's order: before the open has run at all.
        val turn = editor.showPage(pages[2])
        runCurrent()

        assertTrue("the turn never finished", turn.isCompleted)
        assertEquals("the editor stayed on the page the note opened at", 2, editor.state.value.pageIndex)
        assertEquals("the surface was not given the hit's page", pages[2], surface.loadedPage?.pageId)
        editor.close()
    }

    /** A stale index, or a page deleted on the other tablet since: stay put rather than load nothing. */
    @Test
    fun `a page that is not in the note leaves the note where it is`() = runTest {
        val fs = InMemoryFileSystem()
        val (id, pages) = threePageNote(fs)

        val editor = editor(fs, documentId = id)
        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        // After the open, so this is the guard under test and not the open swallowing the request.
        runCurrent()
        val loads = surface.loadCount
        editor.showPage(PageId("not-a-page-of-this-note"))
        runCurrent()

        assertEquals(0, editor.state.value.pageIndex)
        assertEquals(pages[0], surface.loadedPage?.pageId)
        assertEquals("a page that is not in the note was loaded", loads, surface.loadCount)
        editor.close()
    }

    /** Turning to the page on screen must not load and tessellate it a second time. */
    @Test
    fun `the page already on screen is not loaded again`() = runTest {
        val fs = InMemoryFileSystem()
        val (id, pages) = threePageNote(fs)

        val editor = editor(fs, documentId = id)
        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        runCurrent()
        val loads = surface.loadCount
        editor.showPage(pages[0])
        runCurrent()

        assertEquals(loads, surface.loadCount)
        editor.close()
    }

    private companion object {
        const val NOW = 1_800_000_000_000
    }
}
