package dev.inkstone.core.storage

import dev.inkstone.core.model.FileIdentity
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.ReadHandle
import dev.inkstone.core.model.SnapshotReadHandle
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⛔ The 2026-09-24 tablet crash, and the three things that had to change together to close it.
 *
 * *"Bundle entry 'pages/….ink' has no local header"*, thrown on the UI thread while the physics note
 * was open: a library thumbnail read `note.inkstone`'s central directory, the note's own editor
 * materialized it — replacing the file by rename — and the thumbnail then read the `.ink` entry at an
 * offset that, in the new file, was the middle of the page JSON. `FileByteSource` re-opened the path
 * on every read, so one reader saw two files.
 *
 * - [a reader keeps reading the file it opened] is the fix itself. Against the old `FileByteSource`
 *   it fails with exactly the tablet's message.
 * - [materialize refuses to overwrite a bundle another writer replaced] is the guard the fix made
 *   necessary: a pinned reader no longer *fails* when its file is replaced, so a session that did not
 *   write the file on disk would otherwise write its own pages over it.
 * - [keeping the page cache after a materialize does not duplicate an op] covers the other change in
 *   this commit that touches what gets written: the cache used to be cleared on the grounds that
 *   keeping it double-counted, and that has to be shown false rather than asserted.
 */
class PinnedReaderTest {

    private val clock = TestClock()
    private val ids = IdFactory(clock, Random(7))
    private var counter = 0L

    private fun addOp(id: String, bytes: Int = 64) = PageOp.AddObject(
        opId = ids.newOpId(),
        clock = LamportClock(++counter, "device-a"),
        obj = StrokeObject(ObjectId(id), StrokeGeometry(ByteArray(bytes) { (it + id.length).toByte() })),
    )

    @Test
    fun `a reader keeps reading the file it opened when the path is replaced under it`() = runTest {
        val fs = FakeFileSystem()
        val store = NoteStore(fs, clock, ids)
        val note = store.create("Electrical energy", "device-a")
        val page = note.document.pages.single().id
        val first = NoteSession.open(store, ids, clock, note, deviceId = "device-a")
        first.apply(page, listOf(addOp("stroke-1")))
        first.materialize()

        // A second reader of the same note — the library's thumbnail — opens it and reads the index.
        val reader = assertNotNull(BundleReader.open(FileByteSource(fs, NoteLayout.bundle(note.document.id))))

        // The editor then writes more ink and materializes, which replaces the file by rename and
        // moves every entry after the page JSON: the `.ink` entry's old offset now lands in JSON.
        first.apply(page, List(40) { addOp("stroke-more-$it", bytes = 256) })
        first.materialize()

        val loaded = assertNotNull(reader.page(page), "the reader lost its page when the file was replaced")
        assertEquals(
            listOf("stroke-1"),
            loaded.ops.filterIsInstance<PageOp.AddObject>().map { it.obj.id.value },
            "the reader must see the version it opened — one file, not the directory of one and the bytes of another",
        )
    }

    @Test
    fun `materialize refuses to overwrite a bundle another writer replaced`() = runTest {
        val fs = GenerationFileSystem(FakeFileSystem())
        val editorStore = NoteStore(fs, clock, ids)
        val note = editorStore.create("Chemical reactions", "device-a")
        val page = note.document.pages.single().id
        val editor = NoteSession.open(editorStore, ids, clock, editorStore.open(note.document.id)!!, deviceId = "device-a")
        editor.apply(page, listOf(addOp("editor-stroke")))
        // The editor reads the page, so its reader holds the file as it was.
        editor.page(page)

        // A second writer — the residual `NoteSessionHost` documents, a merge that raced this
        // session's open — replaces the bundle with work of its own.
        val otherStore = NoteStore(fs, clock, ids)
        val other = NoteSession.open(otherStore, ids, clock, otherStore.open(note.document.id)!!, deviceId = "device-b")
        other.apply(page, listOf(addOp("other-stroke")))
        other.materialize()
        val theirs = fs.readBytes(NoteLayout.bundle(note.document.id))

        assertFailsWith<IllegalStateException>("the session wrote over a bundle it never read") {
            editor.materialize()
        }
        assertTrue(
            theirs.contentEquals(fs.readBytes(NoteLayout.bundle(note.document.id))),
            "the other writer's bundle was changed by a materialize that should have refused",
        )
    }

    @Test
    fun `keeping the page cache after a materialize does not duplicate an op`() = runTest {
        val fs = FakeFileSystem()
        val store = NoteStore(fs, clock, ids)
        val note = store.create("Notebook", "device-a")
        val page = note.document.pages.single().id
        val session = NoteSession.open(store, ids, clock, note, deviceId = "device-a")

        session.apply(page, listOf(addOp("a")))
        session.materialize()
        session.apply(page, listOf(addOp("b")))
        session.materialize()
        session.materialize()

        val reopened = assertNotNull(store.open(note.document.id))
        val ops = store.loadPage(reopened, page).ops
        assertEquals(ops.size, ops.map { it.opId }.toSet().size, "an op was written into the bundle twice")
        assertEquals(
            listOf("a", "b"),
            ops.filterIsInstance<PageOp.AddObject>().map { it.obj.id.value },
            "the materialized page lost or reordered ink",
        )
        assertEquals(
            setOf("a", "b"),
            session.page(page).objects.map { it.id.value }.toSet(),
            "the kept cache disagrees with the file it was written to",
        )
    }

    @Test
    fun `bundleIsCurrent is true only when a materialize would write back what is on disk`() = runTest {
        val fs = FakeFileSystem()
        val store = NoteStore(fs, clock, ids)
        val note = store.create("Notebook", "device-a")
        val page = note.document.pages.single().id
        val session = NoteSession.open(store, ids, clock, note, deviceId = "device-a")

        assertTrue(session.bundleIsCurrent, "a freshly created note has nothing to materialize")
        session.apply(page, listOf(addOp("a")))
        assertFalse(session.bundleIsCurrent, "an applied op is not in the bundle")
        session.flush()
        assertFalse(session.bundleIsCurrent, "a flushed op is in the journal, not the bundle")
        session.materialize()
        assertTrue(session.bundleIsCurrent, "after a materialize the bundle holds everything")

        val reopened = NoteSession.open(store, ids, clock, assertNotNull(store.open(note.document.id)), deviceId = "device-a")
        assertTrue(reopened.bundleIsCurrent, "reopening a materialized note found work owed")

        reopened.apply(page, listOf(addOp("b")))
        reopened.flush()
        val withJournal = NoteSession.open(store, ids, clock, assertNotNull(store.open(note.document.id)), deviceId = "device-a")
        assertFalse(withJournal.bundleIsCurrent, "a note opened with journal records still owes a materialize")
    }
}

/**
 * A filesystem that can say which *version* of a file a handle holds — what `AndroidFileSystem`
 * answers with an inode. Each atomic write to a path is a new file with a new identity.
 */
private class GenerationFileSystem(private val delegate: FileSystem) : FileSystem by delegate {

    private val generations = HashMap<String, Long>()
    private var next = 0L

    override suspend fun writeBytesAtomically(path: String, bytes: ByteArray) {
        delegate.writeBytesAtomically(path, bytes)
        generations[path] = ++next
    }

    override suspend fun openRead(path: String): ReadHandle? {
        if (!delegate.exists(path)) return null
        val snapshot = SnapshotReadHandle(delegate.readBytes(path))
        val identity = FileIdentity(device = 0, inode = generations[path] ?: 0)
        return object : ReadHandle by snapshot {
            override val identity: FileIdentity = identity
        }
    }

    override suspend fun identityOf(path: String): FileIdentity? =
        if (delegate.exists(path)) FileIdentity(device = 0, inode = generations[path] ?: 0) else null
}
