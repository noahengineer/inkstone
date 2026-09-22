package dev.inkstone.app.note

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/**
 * The seam that broke on the tablet: the view model and the ink surface becoming ready in either
 * order.
 *
 * Every layer below this was tested and correct, and the app still recorded nothing — the view is
 * created during the first layout pass, opening a note is file I/O, so on a real device the
 * **surface attaches before the session exists**. The binding gave up quietly in that case, the
 * surface was left with no recorder, and an unbound surface produces no ops: nothing journaled,
 * nothing surviving a close, and undo greyed out because the undo stack never got an entry.
 *
 * So the order is the thing under test here, in both directions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorBindingTest {

    private companion object {
        /** Generous: a real dispatcher on a loaded CI box, for work that takes milliseconds. */
        const val OPEN_TIMEOUT_MS = 30_000L

        /** The stand-in for `Dispatchers.Main.immediate`, named so an assertion can point at it. */
        const val EDITOR_THREAD = "fake-editor-main"
    }

    /**
     * An editor on the test's own scope and dispatcher.
     *
     * The interval is effectively never and the tests use `runCurrent`, never `advanceUntilIdle`:
     * advancing virtual time would run the autosave loop's `delay` and then loop, forever. Every
     * test drives [NoteEditor.save] itself, and closes the editor so `runTest` is not left waiting
     * on a loop that never ends.
     */
    private fun TestScope.editor(fs: FileSystem = InMemoryFileSystem()) = NoteEditor(
        fs = fs,
        clock = TestClock(),
        scope = this,
        drainDispatcher = UnconfinedTestDispatcher(testScheduler),
        autosaveIntervalMs = Long.MAX_VALUE,
    )

    /**
     * ⭐ **Opening a note does its reading and decoding somewhere other than the editor's own
     * thread.**
     *
     * `NoteEditor.work` is `Dispatchers.Main.immediate` in the app — deliberately, because it is
     * what serialises the session — so every stage of an open ran on the UI thread: the journal
     * read and CRC, the whole op decode, and `NoteStore.loadUndo`, measured at 597,640 B and 1,898
     * ops on one real note. Together with `InkSurfaceView.loadPage` tessellating every object,
     * that was `Skipped 190 frames!` and `Displayed +3s632ms`, halved and no more
     * (docs/M8-PERF-RENDER-CACHE.md §5). A two-finger undo tapped in that window was not slow —
     * the thread it needed was gone.
     *
     * ⚠️ **Nothing about the *result* changes**, which is why this needs a test of its own: the
     * note opens identically either way, and a suite that only checks what was opened cannot tell
     * the two apart. What is asserted is where the work happened.
     *
     * ⚠️ Real dispatchers, and `runBlocking` rather than `runTest`. An `UnconfinedTestDispatcher`
     * for `drainDispatcher` — which every other test here uses, correctly — does not dispatch, so
     * the hop would stay on the calling thread and this would pass against the code it is about.
     */
    @Test
    fun `opening a note does no file work on the editor's own thread`() = runBlocking {
        val fs = ThreadRecordingFileSystem(InMemoryFileSystem())
        // ⚠️ A **single** thread, standing in for `Dispatchers.Main.immediate`. That is what the
        // editor's scope is in the app, and being confined to one thread is the whole property
        // under test: an unconfined or multi-threaded scope has no "own thread" to keep clear.
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, EDITOR_THREAD) }
        val scope = CoroutineScope(executor.asCoroutineDispatcher() + Job())
        val surface = FakeSurface()
        val editor = NoteEditor(
            fs = fs,
            clock = TestClock(),
            scope = scope,
            drainDispatcher = Dispatchers.Default,
            autosaveIntervalMs = Long.MAX_VALUE,
        )
        editor.onSurfaceAttached(surface)

        // Waited for through the published state rather than through an editor call, because every
        // editor call would itself touch the file system and pollute what is being measured.
        withTimeout(OPEN_TIMEOUT_MS) { editor.state.first { it.pageCount > 0 } }

        assertTrue("the open touched no files at all, so this proves nothing", fs.threads.isNotEmpty())
        assertFalse(
            "the open read and decoded the note on the editor's own thread: ${fs.threads}",
            fs.ranOn(EDITOR_THREAD),
        )
        // ⭐ And exactly one load — the readout docs/M8-PERF-RENDER-CACHE.md §5 asked for. `display`
        // and `bindSurface` can both load a page, and for several milestones they both did: every
        // object on the page was decoded and tessellated twice on every open.
        assertEquals("the page was loaded more than once", 1, surface.loadCount)
        editor.close()
        scope.cancel()
        executor.shutdownNow()
        Unit
    }

    @Test
    fun `a surface that attaches before the note is open still gets bound`() = runTest {
        val fs = InMemoryFileSystem()
        val editor = editor(fs)
        val surface = FakeSurface()

        // The device's order: the view exists, the session does not yet. `openOrCreate` is file
        // I/O launched from the constructor, so it has not finished at this point.
        editor.onSurfaceAttached(surface)
        runCurrent()

        assertTrue("the surface was never bound, so nothing the user draws is recorded", surface.bound)
        assertNotNull("the page was never loaded into the surface", surface.loadedPage)
        editor.close()
    }

    @Test
    fun `a surface that attaches after the note is open gets bound`() = runTest {
        val editor = editor()
        // Let the note open first — the other order, as a rotation that recreates the view gives.
        runCurrent()

        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        runCurrent()

        assertTrue(surface.bound)
        editor.close()
    }

    @Test
    fun `binding the same surface twice does not discard what it has queued`() = runTest {
        // `bindPage` installs a fresh recorder, so a second bind would throw away everything the
        // first one had queued but not yet flushed — up to two seconds of writing.
        val editor = editor()
        val surface = FakeSurface()

        editor.onSurfaceAttached(surface)
        runCurrent()
        editor.onSurfaceAttached(surface)
        runCurrent()

        assertEquals("bindPage was called twice for one surface", 1, surface.bindCount)
        editor.close()
    }

    @Test
    fun `a drawn stroke reaches the journal and enables undo`() = runTest {
        val fs = InMemoryFileSystem()
        val editor = editor(fs)
        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        runCurrent()

        // One gesture, as the recorder would hand it over.
        surface.queueGesture(
            listOf(
                PageOp.AddObject(
                    opId = "op-1",
                    clock = LamportClock(1, "device-a"),
                    obj = StrokeObject(ObjectId("obj-1"), StrokeGeometry(byteArrayOf(1, 2, 3))),
                ),
            ),
        )
        editor.save()
        runCurrent()

        assertTrue("nothing was appended to the journal", fs.appends > 0)
        assertTrue("undo was not offered after a stroke", editor.state.value.canUndo)
        assertFalse(editor.state.value.canRedo)
        editor.close()
    }

    @Test
    fun `undo removes the stroke from the surface and redo puts it back`() = runTest {
        val editor = editor()
        val surface = FakeSurface()
        editor.onSurfaceAttached(surface)
        runCurrent()

        val stroke = StrokeObject(ObjectId("obj-1"), StrokeGeometry(byteArrayOf(1, 2, 3)))
        surface.queueGesture(listOf(PageOp.AddObject("op-1", LamportClock(1, "device-a"), stroke)))
        editor.save()
        runCurrent()

        editor.undo()
        runCurrent()

        // The screen is brought in line by diffing the two folds, not by replaying the log — so a
        // delete for exactly the undone object is what the surface should be told about.
        val afterUndo = surface.applied.last()
        assertEquals(1, afterUndo.ops.size)
        assertTrue("undo did not delete the stroke on screen", afterUndo.ops.single() is PageOp.DeleteObject)
        assertEquals(ObjectId("obj-1"), afterUndo.ops.single().target)
        assertFalse("replayed ops must not be journaled again", afterUndo.record)
        assertTrue(editor.state.value.canRedo)

        editor.redo()
        runCurrent()

        val afterRedo = surface.applied.last()
        assertTrue("redo did not put the stroke back", afterRedo.ops.single() is PageOp.AddObject)
        assertEquals(ObjectId("obj-1"), afterRedo.ops.single().target)
        assertTrue(editor.state.value.canUndo)
        editor.close()
    }

    // -----------------------------------------------------------------------

    /** [FakeSurface] and [InMemoryFileSystem] are shared — see `TestDoubles.kt`. */
    private class TestClock : Clock {
        override fun nowEpochMillis(): Long = 1_800_000_000_000
    }
}
