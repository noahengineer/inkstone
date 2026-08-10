package dev.inkstone.app.note

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageTemplate
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC §5.7 — the editor's half of the invalidation contract.
 *
 * The rule the library depends on: **the thumbnail is invalidated when the note is saved,
 * and redrawn when the note is materialized.** Get the first half wrong and the library
 * shows a picture of a page that has since been written on, with nothing to reveal it; get
 * the second half wrong and it shows a placeholder forever.
 *
 * The rendering itself is `:android:ink`'s and needs a device. What is testable here — and
 * what actually broke things when it was missing — is that the reports happen at all, with
 * the right revision.
 *
 * ⚠️ **The structural-edit half was missing from this file for a milestone, and it is the half that
 * broke.** `addPage` / `duplicateCurrentPage` / `deleteCurrentPage` / `movePage` / `setTemplate` each
 * called a bare `session.flush()`, which reaches neither `thumbnails.invalidate` nor
 * `sync.noteChanged` — so the tile's page count was stale all session and **no upload was
 * enqueued**, while every test here stayed green. They all go through `NoteEditor.flushAndReport`
 * now, and the last two tests below are the ones that would have caught it.
 *
 * It was in fact found by driving the app over `adb` and reading `pageCount` back out of
 * `databases/inkstone.db` with zero strokes drawn. Worth being precise about that: the device walk
 * is what *did* catch it, not the only thing that *could* have — this fake was already here, and
 * three lines against it would have done. That is the more useful lesson.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorLibraryTest {

    private class RecordingLibrary : NoteEditor.LibraryUpdates {
        val saves = ArrayList<Triple<DocumentId, Long, Int>>()
        val materializations = ArrayList<Triple<DocumentId, Long, FoldedPage>>()
        val renames = ArrayList<Pair<String, Long>>()

        override suspend fun onSaved(id: DocumentId, localRev: Long, pageCount: Int) {
            saves += Triple(id, localRev, pageCount)
        }

        override suspend fun onMaterialized(id: DocumentId, localRev: Long, pageCount: Int, cover: FoldedPage) {
            materializations += Triple(id, localRev, cover)
        }

        override suspend fun onRenamed(id: DocumentId, title: String, localRev: Long, pageCount: Int) {
            renames += title to localRev
        }
    }

    private fun TestScope.editor(library: NoteEditor.LibraryUpdates) = NoteEditor(
        fs = InMemoryFileSystem(),
        clock = { 1_800_000_000_000 },
        scope = this,
        drainDispatcher = UnconfinedTestDispatcher(testScheduler),
        autosaveIntervalMs = Long.MAX_VALUE,
        library = library,
    )

    private fun stroke(id: String) = PageOp.AddObject(
        opId = "op-$id",
        clock = LamportClock(1, "device-a"),
        obj = StrokeObject(ObjectId(id), StrokeGeometry(byteArrayOf(1, 2, 3))),
    )

    @Test
    fun `a save tells the library, with the revision the thumbnail is invalidated against`() = runTest {
        val library = RecordingLibrary()
        val editor = editor(library)
        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        runCurrent()

        surface.queueGesture(listOf(stroke("obj-1")))
        editor.save()
        runCurrent()

        val save = library.saves.lastOrNull()
        assertNotNull("a save did not reach the library — the grid would show a stale picture", save)
        assertTrue("local_rev did not advance, so the index could not tell the note had changed", save!!.second > 0)
        assertEquals(1, save.third)
        editor.close()
    }

    @Test
    fun `an idle save says nothing`() = runTest {
        // Autosave runs on a timer whether or not anything happened. Reporting every tick
        // would invalidate the thumbnail of a note nobody is writing on, and the library
        // would re-render the open note every two seconds.
        val library = RecordingLibrary()
        val editor = editor(library)
        editor.onSurfaceAttached(FakeSurface())
        runCurrent()
        library.saves.clear()

        editor.save()
        runCurrent()

        assertTrue("an idle autosave invalidated the thumbnail", library.saves.isEmpty())
        editor.close()
    }

    @Test
    fun `a rename is reported as a rename and reaches the bundle`() = runTest {
        // ⭐ SPEC §7.7 layer 2 needs two things of a rename, and reporting it as an ordinary save gave
        // neither: `recordSave` has no title column, so the library tile keeps the old string, and a
        // rename that only *flushes* leaves the title in the journal — while a published PDF embeds
        // `note.inkstone` as it is on disk, so no peer sees it until the note happens to close.
        val library = RecordingLibrary()
        val editor = editor(library)
        editor.onSurfaceAttached(FakeSurface())
        runCurrent()
        library.materializations.clear()

        editor.rename("Thermodynamics wk 4")
        runCurrent()

        val rename = library.renames.lastOrNull()
        assertNotNull("the rename was not reported as a rename, so the library row keeps the old title", rename)
        assertEquals("Thermodynamics wk 4", rename!!.first)
        assertTrue("the rename did not move the revision, so no upload is ever owed for it", rename.second > 0)
        assertTrue(
            "the rename was not materialized, so the bundle a peer receives still holds the old title",
            library.materializations.isNotEmpty(),
        )
        assertEquals("Thermodynamics wk 4", editor.state.value.title)
        editor.close()
    }

    @Test
    fun `renaming to the title it already has says nothing new`() = runTest {
        val library = RecordingLibrary()
        val editor = editor(library)
        editor.onSurfaceAttached(FakeSurface())
        runCurrent()
        val title = editor.state.value.title
        library.materializations.clear()

        editor.rename(title)
        runCurrent()

        // Still reported — the *index* may be the thing that is out of step, and repairing that is
        // what an apparently redundant rename is for — but nothing is rewritten for it.
        assertEquals(1, library.renames.size)
        assertTrue("an identical rename rewrote the bundle", library.materializations.isEmpty())
        editor.close()
    }

    @Test
    fun `materializing hands over the page to redraw from`() = runTest {
        val library = RecordingLibrary()
        val editor = editor(library)
        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        runCurrent()

        surface.queueGesture(listOf(stroke("obj-1")))
        editor.materializeNow()
        runCurrent()

        val materialized = library.materializations.lastOrNull()
        assertNotNull("the library was never told to redraw the thumbnail", materialized)
        // The *first* page, and the fold — not the published PDF, which SPEC §5.1 allows to
        // be one session stale.
        assertEquals(1, materialized!!.third.objects.size)
        assertEquals(ObjectId("obj-1"), materialized.third.objects.single().id)
        editor.close()
    }

    @Test
    fun `adding a page tells the library even though nothing was drawn`() = runTest {
        // ⭐ The regression. A structural edit flushes, and `saveLocked`'s report is gated on
        // `hasUnflushedWork` — which the flush has just cleared. So this reported nothing at all: the
        // grid kept showing "1 page" and, because `onSaved` is the only route to
        // `SyncEngine.noteChanged`, the new page reached the server only if the user later drew a
        // stroke or closed the note.
        val library = RecordingLibrary()
        val editor = editor(library)
        editor.onSurfaceAttached(FakeSurface())
        runCurrent()
        library.saves.clear()

        editor.addPage(PageTemplate.GRID)
        runCurrent()

        val save = library.saves.lastOrNull()
        assertNotNull("adding a page told the library nothing — stale tile, and no upload", save)
        assertEquals("the reported page count is not the new one", 2, save!!.third)
        editor.close()
    }

    @Test
    fun `changing the paper tells the library even though nothing was drawn`() = runTest {
        // The same gate, on the edit least likely to be followed by a stroke: a user who picks a
        // different paper has often just finished writing.
        val library = RecordingLibrary()
        val editor = editor(library)
        editor.onSurfaceAttached(FakeSurface())
        runCurrent()
        library.saves.clear()

        editor.setTemplate(PageTemplate.CORNELL)
        runCurrent()

        assertTrue(
            "a paper change told the library nothing — no thumbnail refresh, no upload",
            library.saves.isNotEmpty(),
        )
        editor.close()
    }
}
