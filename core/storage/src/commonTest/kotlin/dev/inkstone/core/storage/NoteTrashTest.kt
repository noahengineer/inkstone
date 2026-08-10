package dev.inkstone.core.storage

import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CLAUDE.md rule 1 — **no hard deletes**, and the one path M6's review found where the trash was
 * itself where a note went to be destroyed.
 *
 * The trash is the only reversible-by-design operation in the app, so "reversible" has to hold in
 * the states the app can actually be in — including a note being trashed by a background reconcile
 * pass while an editor has it open. This is the mechanism half; the reconciler's half (it refuses to
 * trash a note an editor holds at all) is `ReconcilerFileTest`'s.
 */
class NoteTrashTest {

    private val fs = FakeFileSystem()
    private val clock = TestClock()
    private val ids = IdFactory(clock, Random(7))
    private val store = NoteStore(fs, clock, ids)
    private val trash = NoteTrash(fs, clock)

    private fun addOp(id: String, counter: Long) = PageOp.AddObject(
        opId = ids.newOpId(),
        clock = LamportClock(counter, "device-a"),
        obj = StrokeObject(id = ObjectId(id), geometry = StrokeGeometry(ByteArray(64) { it.toByte() })),
    )

    @Test
    fun `a note trashed under a live session still restores`() = runTest {
        // ⭐ The permanent-loss path, end to end.
        //
        // 1. The reconciler finds the remote file gone and trashes the note — `notes/<id>/` MOVES.
        // 2. The editor is not told. Its next two-second autosave appends to `journal.ops` at the
        //    old path, and every FileSystem write creates the parent directory first, so
        //    `notes/<id>/` comes back holding a journal and an undo sidecar and **no bundle**.
        // 3. The user taps Restore. `restore` saw a directory at `notes/<id>/` and refused — every
        //    time, silently, forever. The note could not be opened either (no bundle), and thirty
        //    days later `purgeExpired` deleted the only copy of every page.
        val note = store.create("Lecture", "device-a")
        val id = note.document.id
        val page = note.document.pages.single().id
        val session = NoteSession.open(store, ids, clock, note, deviceId = "device-a")
        session.apply(page, listOf(addOp("obj-before", 1L)))
        session.flush()
        session.materialize()
        val bundle = fs.readBytes(NoteLayout.bundle(id))

        // One stroke pending when the pass lands, which is the whole window: two seconds wide, and
        // the editor draws in it every time somebody is writing.
        session.apply(page, listOf(addOp("obj-after", 2L)))
        trash.trash(id)
        session.flush()
        assertTrue(fs.exists(NoteLayout.journal(id)), "the fixture did not reproduce the stub")
        assertFalse(fs.exists(NoteLayout.bundle(id)), "a stub is exactly a note directory with no bundle")

        assertTrue(trash.restore(id), "the note must come back")

        assertContentEquals(bundle, fs.readBytes(NoteLayout.bundle(id)), "the restored bundle is the trashed one")
        val reopened = assertNotNull(store.open(id), "a restored note must open")
        assertEquals("Lecture", reopened.document.title)
    }

    @Test
    fun `the ink drawn after the trash is kept rather than deleted`() = runTest {
        // The stub is not a note — a journal with no bundle folds onto nothing — but it is the
        // user's ink, so it goes to the trash and expires on the same thirty-day clock as everything
        // else in there. CLAUDE.md rule 1 has no exception for a directory that arrived by accident.
        val note = store.create("Lecture", "device-a")
        val id = note.document.id
        val page = note.document.pages.single().id
        val session = NoteSession.open(store, ids, clock, note, deviceId = "device-a")
        session.materialize()

        session.apply(page, listOf(addOp("obj-after", 2L)))
        trash.trash(id)
        session.flush()
        val stub = fs.readBytes(NoteLayout.journal(id))

        assertTrue(trash.restore(id))

        val orphan = NoteLayout.orphanedStub(id, clock.nowEpochMillis())
        assertContentEquals(stub, fs.readBytes("$orphan/${NoteLayout.JOURNAL_OPS}"), "the ops must survive")
        assertTrue(trash.entries().any { it.path == orphan }, "and expire on the same clock as any other entry")
        // ⚠️ Invisible to a restore: `entries()` reads the name after the millis as the id, so an
        // entry that answered to this id could be restored *instead of* the note itself.
        assertTrue(trash.entries().none { it.id == id && it.path == orphan })
    }

    @Test
    fun `restoring over a live note is still refused`() = runTest {
        // The guard that has to survive the fix. A directory holding a bundle IS a note, and moving
        // the trashed copy over it is the destructive operation this class exists to prevent.
        val note = store.create("Lecture", "device-a")
        val id = note.document.id
        NoteSession.open(store, ids, clock, note, deviceId = "device-a").materialize()
        val entry = trash.trash(id)
        // A second note under the same id, which is the shape a recovery or an interrupted restore
        // leaves behind.
        store.installBundle(id, fs.readBytes("$entry/${NoteLayout.EMBEDDED_BUNDLE_NAME}"))

        assertFalse(trash.restore(id), "a live note must never be overwritten by a restore")
        assertTrue(fs.exists(NoteLayout.bundle(id)), "and it is left exactly as it was")
        assertTrue(trash.entries().any { it.id == id }, "with the trashed copy still in the trash")
    }
}
