package dev.inkstone.app.note

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    /**
     * An editor on the test's own scope and dispatcher.
     *
     * The interval is effectively never and the tests use `runCurrent`, never `advanceUntilIdle`:
     * advancing virtual time would run the autosave loop's `delay` and then loop, forever. Every
     * test drives [NoteEditor.save] itself, and closes the editor so `runTest` is not left waiting
     * on a loop that never ends.
     */
    private fun TestScope.editor(fs: FileSystem = RecordingFileSystem()) = NoteEditor(
        fs = fs,
        clock = TestClock(),
        scope = this,
        drainDispatcher = UnconfinedTestDispatcher(testScheduler),
        autosaveIntervalMs = Long.MAX_VALUE,
    )

    @Test
    fun `a surface that attaches before the note is open still gets bound`() = runTest {
        val fs = RecordingFileSystem()
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
        val fs = RecordingFileSystem()
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

    private class FakeSurface : NoteEditor.NoteSurface {
        var bindCount: Int = 0
            private set

        val bound: Boolean get() = bindCount > 0

        var loadedPage: FoldedPage? = null
            private set

        val applied = ArrayList<Applied>()

        class Applied(val ops: List<PageOp>, val record: Boolean)

        private val gestures = ArrayDeque<List<PageOp>>()

        fun queueGesture(ops: List<PageOp>) {
            gestures += ops
        }

        override fun bindPage(nextObjectId: () -> ObjectId, nextOpId: () -> String, nextClock: () -> LamportClock) {
            bindCount++
        }

        override fun loadPage(page: FoldedPage) {
            loadedPage = page
        }

        override fun drainOps(): List<List<PageOp>> {
            val drained = gestures.toList()
            gestures.clear()
            return drained
        }

        override fun applyOps(ops: List<PageOp>, record: Boolean) {
            applied += Applied(ops, record)
        }
    }

    /** The same in-memory filesystem shape `:core:storage` tests use, with append counting. */
    private class RecordingFileSystem : FileSystem {
        private val files = LinkedHashMap<String, ByteArray>()
        private val directories = LinkedHashSet<String>()

        var appends: Int = 0
            private set

        override suspend fun exists(path: String): Boolean = path in files || path in directories

        override suspend fun size(path: String): Long = files[path]?.size?.toLong() ?: -1L

        override suspend fun readBytes(path: String): ByteArray = files[path] ?: error("no such file: $path")

        override suspend fun readRange(path: String, offset: Long, length: Int): ByteArray {
            val bytes = files[path] ?: error("no such file: $path")
            val start = offset.coerceIn(0L, bytes.size.toLong()).toInt()
            val end = (start.toLong() + length).coerceAtMost(bytes.size.toLong()).toInt()
            return bytes.copyOfRange(start, end)
        }

        override suspend fun writeBytesAtomically(path: String, bytes: ByteArray) {
            files[path] = bytes.copyOf()
            register(path)
        }

        override suspend fun appendBytes(path: String, bytes: ByteArray) {
            appends++
            files[path] = (files[path] ?: ByteArray(0)) + bytes
            register(path)
        }

        override suspend fun createDirectories(path: String) {
            directories += path
        }

        override suspend fun move(from: String, to: String) {
            files.keys.filter { it == from || it.startsWith("$from/") }.forEach {
                files[to + it.removePrefix(from)] = files.getValue(it)
                files.remove(it)
            }
        }

        override suspend fun delete(path: String) {
            files.keys.filter { it == path || it.startsWith("$path/") }.forEach { files.remove(it) }
            directories.removeAll { it == path || it.startsWith("$path/") }
        }

        override suspend fun list(path: String): List<String> = (files.keys + directories)
            .filter { it.startsWith("$path/") }
            .map { it.removePrefix("$path/").substringBefore('/') }
            .distinct()

        private fun register(path: String) {
            var index = path.indexOf('/')
            while (index >= 0) {
                directories += path.substring(0, index)
                index = path.indexOf('/', index + 1)
            }
        }
    }

    private class TestClock : Clock {
        override fun nowEpochMillis(): Long = 1_800_000_000_000
    }
}
