package dev.inkstone.app.note

import dev.inkstone.app.elements.ElementRevision
import dev.inkstone.app.elements.ElementShelf
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.ImageObject
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.MaterializedFile
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PdfPublisher
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.storage.Bundle
import dev.inkstone.core.storage.NoteLayout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertContentEquals

/**
 * ⛔ **What happens to ink when the note is left** — the half of SPEC §5.1 that runs on the way out.
 *
 * Found on 2026-09-24 by drawing three strokes on the emulator and pressing Back at once: the
 * journal still held its 8-byte header and the strokes were gone. Two things were wrong, and this
 * file covers the editor's half of both:
 *
 * - The close-time save ran in `viewModelScope`, which Back cancels. That half is
 *   `NoteSessionHost.saveOnLeaving` and is only reachable on a device — a `ViewModel` cannot be
 *   cleared from a JVM test with the host wired in — so it is covered by the emulator walk in
 *   the change that introduced this file, not here.
 * - Even a save that survived ran *after* the view detached, and [NoteEditor.onSurfaceDetached]
 *   dropped the only reference to the recorder holding the strokes. That half is [the first test].
 *
 * The next two tests are the close path's cost: `onStop` fires for every screen timeout, and it
 * used to rewrite the whole bundle and republish every time.
 *
 * The last two are 2026-10's (`docs/NOTE-STAYS-LOADED.md`): a stop that is not a close, and
 * *"Update them all"* from the Element editor, both now save without materializing, because a
 * materialize behind another screen is what got a big note's own screen destroyed for memory.
 * ⚠️ They prove the editor's half only. That `onStop` picks [NoteEditor.save] unless the activity
 * is finishing is `NoteEditorViewModel.onStopped`, which a JVM test cannot construct; the device
 * walk in that doc is what checks it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorCloseTest {

    private class RecordingLibrary : NoteEditor.LibraryUpdates {
        val saves = ArrayList<Long>()
        val materializations = ArrayList<Long>()

        override suspend fun onSaved(id: DocumentId, localRev: Long, pageCount: Int) {
            saves += localRev
        }

        override suspend fun onMaterialized(id: DocumentId, localRev: Long, pageCount: Int, cover: FoldedPage) {
            materializations += localRev
        }
    }

    private class CountingPublisher : PdfPublisher {
        var publishes = 0

        override suspend fun publish(noteId: DocumentId): MaterializedFile {
            publishes++
            return MaterializedFile(path = "document.pdf", sizeBytes = 1L, sha256 = "", localRev = 0L)
        }
    }

    private fun TestScope.editor(
        library: NoteEditor.LibraryUpdates,
        publisher: PdfPublisher? = null,
        fs: InMemoryFileSystem = InMemoryFileSystem(),
    ) = NoteEditor(
        fs = fs,
        clock = { 1_800_000_000_000 },
        scope = this,
        drainDispatcher = UnconfinedTestDispatcher(testScheduler),
        autosaveIntervalMs = Long.MAX_VALUE,
        library = library,
        publisher = publisher,
    )

    private var counter = 0L

    private fun stroke(id: String) = PageOp.AddObject(
        opId = "op-$id",
        clock = LamportClock(++counter, "device-a"),
        obj = StrokeObject(ObjectId(id), StrokeGeometry(byteArrayOf(1, 2, 3))),
    )

    @Test
    fun `strokes still queued on a surface that has detached are saved by the next drain`() = runTest {
        val library = RecordingLibrary()
        val editor = editor(library)
        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        runCurrent()
        val page = editor.state.value.pageIds.single()

        // Drawn in the two seconds before Back, so still in the surface's recorder…
        surface.queueGesture(listOf(stroke("last-stroke")))
        // …and Back detaches the view before the close-time save gets the lock.
        editor.onSurfaceDetached()
        editor.save()
        runCurrent()

        val folded = requireNotNull(editor.foldedPage(page)) { "the page did not fold" }
        assertEquals(
            "the stroke drawn just before leaving was lost with the detached surface",
            setOf("last-stroke"),
            folded.objects.map { it.id.value }.toSet(),
        )
        assertTrue("the save that took it never reached the library", library.saves.isNotEmpty())
        editor.close()
    }

    @Test
    fun `leaving a note that has not changed does not rewrite or republish it`() = runTest {
        val library = RecordingLibrary()
        val publisher = CountingPublisher()
        val editor = editor(library, publisher)
        editor.onSurfaceAttached(FakeSurface())
        runCurrent()

        editor.materializeIfChanged()
        editor.materializeIfChanged()
        runCurrent()

        assertTrue(
            "a note opened to be read was rewritten on the way out — ${library.materializations.size} times",
            library.materializations.isEmpty(),
        )
        assertEquals("a note opened to be read was republished on the way out", 0, publisher.publishes)
        editor.close()
    }

    @Test
    fun `leaving after writing materializes once and then not again`() = runTest {
        val library = RecordingLibrary()
        val publisher = CountingPublisher()
        val editor = editor(library, publisher)
        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        runCurrent()

        surface.queueGesture(listOf(stroke("written")))
        editor.materializeIfChanged()
        runCurrent()
        assertEquals("writing and leaving did not materialize the note", 1, library.materializations.size)
        assertEquals("writing and leaving did not publish the note", 1, publisher.publishes)

        // The screen dims again with nothing new drawn.
        editor.materializeIfChanged()
        runCurrent()
        assertEquals("a second leave with nothing new rewrote the note again", 1, library.materializations.size)
        assertEquals("a second leave with nothing new republished the note", 1, publisher.publishes)
        editor.close()
    }

    @Test
    fun `a stop that does not close the note saves its strokes and rewrites nothing`() = runTest {
        // The Element editor opening on top of the note, or a switch to another app: `onStop`
        // without `isFinishing`, which is now `save()` and nothing else.
        val fs = InMemoryFileSystem()
        val library = RecordingLibrary()
        val publisher = CountingPublisher()
        val editor = editor(library, publisher, fs)
        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        runCurrent()

        surface.queueGesture(listOf(stroke("drawn-before-switching-away")))
        val appendsBefore = fs.appends
        editor.save()
        runCurrent()

        assertTrue("the stop's strokes never reached the journal", fs.appends > appendsBefore)
        assertTrue("the stop's save was not reported, so nothing would upload it", library.saves.isNotEmpty())
        assertTrue("a stop that is not a close rewrote the bundle", library.materializations.isEmpty())
        assertEquals("a stop that is not a close republished the PDF", 0, publisher.publishes)

        // The close that comes later still catches up what the stop saved.
        editor.materializeIfChanged()
        runCurrent()
        assertEquals("closing after the stop did not materialize its strokes", 1, library.materializations.size)
        assertEquals("closing after the stop did not publish its strokes", 1, publisher.publishes)
        editor.close()
    }

    @Test
    fun `updating an Element's copies saves them and leaves the rewrite to the close`() = runTest {
        val fs = InMemoryFileSystem()
        val library = RecordingLibrary()
        val publisher = CountingPublisher()
        val editor = editor(library, publisher, fs)
        editor.onSurfaceAttached(FakeSurface())
        runCurrent()
        val page = editor.state.value.pageIds.single()
        val noteId = requireNotNull(editor.state.value.documentId) { "the note did not open" }
        val staging = "${NoteLayout.noteDir(noteId)}/${NoteEditor.PENDING_ASSETS_DIR}"

        val shelf = ElementShelf(fs) { 1_700_000_000_000 }
        val original = requireNotNull(
            shelf.save(
                name = "Photo",
                collection = "Icons",
                page = sourcePage(FIRST_PICTURE),
                ids = listOf(ObjectId(FIRST_PICTURE)),
                assets = pictureBytes(FIRST_PICTURE, OLD_PIXELS),
                measured = BOX,
            ),
        )
        editor.stampElement(shelf, original, PagePoint(200f, 200f))
        runCurrent()
        // Stamping a picture materializes on purpose (the note is on screen); count from here.
        val rewrites = library.materializations.size
        val publishes = publisher.publishes
        val saves = library.saves.size

        val revised = requireNotNull(
            shelf.revise(
                original = original,
                page = sourcePage(SECOND_PICTURE),
                ids = listOf(ObjectId(SECOND_PICTURE)),
                assets = pictureBytes(SECOND_PICTURE, NEW_PIXELS),
                measured = BOX,
                pivot = null,
            ),
        )
        val replaced = editor.updateElementCopies(shelf, ElementRevision(revised, PagePoint(0f, 0f)))
        runCurrent()

        assertEquals("the copy was not replaced", 1, replaced)
        assertEquals(
            "Update them all rewrote the note behind the Element editor",
            rewrites,
            library.materializations.size,
        )
        assertEquals("Update them all republished the note behind the Element editor", publishes, publisher.publishes)
        assertTrue("the replacement was not reported, so nothing would upload it", library.saves.size > saves)

        // The new picture is drawable before anything materializes, because its bytes are staged.
        val image = requireNotNull(editor.foldedPage(page)).objects.filterIsInstance<ImageObject>().single()
        assertTrue("the new picture's bytes are not in the staging directory", fs.list(staging).isNotEmpty())
        assertContentEquals(NEW_PIXELS, editor.assets(setOf(image.asset))[image.asset])

        // The close folds them into the bundle and empties the staging directory.
        editor.materializeIfChanged()
        runCurrent()
        assertEquals("closing did not materialize the replaced copies", rewrites + 1, library.materializations.size)
        assertTrue("closing left the picture staged", fs.list(staging).isEmpty())
        assertContentEquals(NEW_PIXELS, editor.assets(setOf(image.asset))[image.asset])
        editor.close()
    }

    private fun sourcePage(pictureId: String) = FoldedPage(
        pageId = PageId("source"),
        canvas = PageCanvas.fixed(PageSize.Letter),
        background = Background.Blank,
        objects = listOf(
            ImageObject(
                id = ObjectId(pictureId),
                asset = Bundle.imageAsset(ObjectId(pictureId)),
                widthPt = 100f,
                heightPt = 100f,
                transform = Matrix3.Identity,
                style = ObjectStyle(colorArgb = 0xFF112233.toInt(), widthPt = 2f),
                z = FractionalKey.Initial,
            ),
        ),
        deleted = emptySet(),
        throughClock = null,
        opCount = 0,
    )

    private fun pictureBytes(pictureId: String, bytes: ByteArray) =
        mapOf(Bundle.imageAsset(ObjectId(pictureId)) to bytes)

    private companion object {
        const val FIRST_PICTURE = "018f0000-0000-7000-8000-0000000000d1"
        const val SECOND_PICTURE = "018f0000-0000-7000-8000-0000000000d2"
        val OLD_PIXELS = byteArrayOf(1, 2, 3, 4)
        val NEW_PIXELS = byteArrayOf(9, 8, 7, 6, 5)
        val BOX = PageRect(0f, 0f, 100f, 100f)
    }
}
