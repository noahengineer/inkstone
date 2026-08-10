package dev.inkstone.core.storage

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.FoldOutcome
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.LamportClockGenerator
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PageTemplate
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC §5.1 — the journal model, and ROADMAP M2's crash-safety criterion:
 *
 * > 50-page notebook, write on 20 pages, `adb shell am force-stop` mid-stroke → reopen →
 * > **at most the in-progress stroke lost**
 *
 * The device half of that is a manual gate. This is the mechanism underneath it, where a
 * kill mid-append can be produced on demand ([FakeFileSystem.truncateNextAppendTo]).
 */
class NoteStoreTest {

    private val fs = FakeFileSystem()
    private val clock = TestClock()
    private val ids = IdFactory(clock, Random(42))
    private val store = NoteStore(fs, clock, ids)

    private var counter = 0L

    private fun stroke(id: String, bytes: Int = 128) = StrokeObject(
        id = ObjectId(id),
        geometry = StrokeGeometry(ByteArray(bytes) { (it + id.length).toByte() }),
    )

    private fun addOp(id: String) = PageOp.AddObject(
        opId = ids.newOpId(),
        clock = dev.inkstone.core.model.LamportClock(++counter, "device-a"),
        obj = stroke(id),
    )

    private suspend fun session(note: OpenNote) = NoteSession.open(store, ids, clock, note, deviceId = "device-a")

    @Test
    fun `autosave appends and does not rewrite the bundle`() = runTest {
        val note = store.create("Notebook", "device-a")
        val bundleWrites = fs.atomicWrites
        val session = session(note)
        val page = note.document.pages.single().id

        repeat(FLUSHES) { flush ->
            session.apply(page, listOf(addOp("obj-$flush")))
            session.flush()
        }

        // SPEC §5.1 — "autosave = append to journal.ops. Cheap, bounded, crash-safe."
        assertEquals(FLUSHES, fs.appends, "each flush must be exactly one append")
        // The only atomic writes are the undo sidecar, one per flush. The bundle is
        // untouched: a two-second autosave must not rewrite the document.
        assertEquals(bundleWrites + FLUSHES, fs.atomicWrites)
        assertTrue(fs.size(NoteLayout.journal(note.document.id)) > Journal.HEADER_SIZE)
    }

    @Test
    fun `local_rev is bumped on every committed change`() = runTest {
        val note = store.create("Notebook", "device-a")
        val session = session(note)
        val page = note.document.pages.single().id
        val start = session.document.localRev

        session.apply(page, listOf(addOp("obj-1")))
        assertEquals(start, session.document.localRev, "an unflushed edit is not committed")
        session.flush()
        assertEquals(start + 1, session.document.localRev)
        session.flush()
        assertEquals(start + 1, session.document.localRev, "an empty flush is not a change")
    }

    @Test
    fun `reopening replays the journal on top of the bundle`() = runTest {
        val note = store.create("Notebook", "device-a")
        val page = note.document.pages.single().id
        val session = session(note)
        session.apply(page, listOf(addOp("obj-1"), addOp("obj-2")))
        session.flush()

        // No materialize: everything is in the journal, which is the state a crash leaves.
        val reopened = assertNotNull(store.open(note.document.id))
        val restored = session(reopened)

        assertEquals(2, restored.page(page).objects.size)
        assertEquals(session.document.localRev, restored.document.localRev)
    }

    @Test
    fun `a kill mid-append loses only the record it was writing`() = runTest {
        val note = store.create("Notebook", "device-a")
        val page = note.document.pages.single().id
        val session = session(note)

        session.apply(page, listOf(addOp("obj-1")))
        session.flush()
        session.apply(page, listOf(addOp("obj-2")))
        session.flush()

        // The stroke that was in flight when the process died: its record reaches disk
        // half-written.
        session.apply(page, listOf(addOp("obj-3")))
        fs.truncateNextAppendTo = TORN_BYTES
        session.flush()

        val reopened = assertNotNull(store.open(note.document.id))
        val restored = session(reopened)

        assertTrue(reopened.journalWasTruncated, "the torn tail was not detected")
        assertEquals(2, restored.page(page).objects.size, "more than the in-progress stroke was lost")
        assertTrue(restored.recoveredFromTornJournal)
    }

    @Test
    fun `a record whose bytes are corrupt is rejected — not folded`() = runTest {
        // The nastier crash: the right length, the wrong content. A length-only check
        // would accept it and hand a corrupt op to the fold.
        val note = store.create("Notebook", "device-a")
        val page = note.document.pages.single().id
        val session = session(note)
        session.apply(page, listOf(addOp("obj-1")))
        session.flush()

        val path = NoteLayout.journal(note.document.id)
        val bytes = fs.readBytes(path)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        fs.writeBytesAtomically(path, bytes)

        val reopened = assertNotNull(store.open(note.document.id))
        assertTrue(reopened.journalWasTruncated)
        assertEquals(0, session(reopened).page(page).objects.size)
    }

    @Test
    fun `materialize folds the journal into the bundle and resets it`() = runTest {
        val note = store.create("Notebook", "device-a")
        val page = note.document.pages.single().id
        val session = session(note)
        session.apply(page, listOf(addOp("obj-1")))

        session.materialize()

        assertEquals(
            Journal.HEADER_SIZE.toLong(),
            fs.size(NoteLayout.journal(note.document.id)),
            "the journal was not reset",
        )
        // And the op is now in the bundle, so a reopen finds it with an empty journal.
        val reopened = assertNotNull(store.open(note.document.id))
        assertFalse(reopened.journalWasTruncated)
        assertEquals(1, session(reopened).page(page).objects.size)
    }

    @Test
    fun `closing a note with an unmaterialized journal is an assertion failure`() = runTest {
        // SPEC §5.1 — "document.pdf is never more than one editing session stale. State
        // this in the code as an assertion."
        val note = store.create("Notebook", "device-a")
        val page = note.document.pages.single().id
        val session = session(note)
        session.apply(page, listOf(addOp("obj-1")))
        session.flush()

        try {
            store.assertNotStale(note.document.id)
            error("expected the staleness assertion to fire")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("stale"))
        }

        session.close(NoteSyncState.LOCAL_ONLY)
        store.assertNotStale(note.document.id)
    }

    @Test
    fun `page management survives a crash before materialization`() = runTest {
        val note = store.create("Notebook", "device-a", pageCount = 2)
        val session = session(note)
        val original = session.document.livePages.map { it.id }

        val inserted = session.addPage(index = 1, background = Background.Template(PageTemplate.GRID))
        session.deletePage(original[1])
        session.rename("Renamed")
        session.flush()

        val restored = session(assertNotNull(store.open(note.document.id)))

        assertEquals("Renamed", restored.document.title)
        assertEquals(listOf(original[0], inserted), restored.document.livePages.map { it.id })
        // The deleted page is a tombstone, not a removal — its ops are still in the bundle.
        assertEquals(3, restored.document.pages.size)
        assertTrue(assertNotNull(restored.document.page(original[1])).deleted)
        assertEquals(Background.Template(PageTemplate.GRID), restored.page(inserted).background)
    }

    @Test
    fun `duplicating a page gives every object a new identity`() = runTest {
        // SPEC §5.6 names the failure this avoids: two objects with one identity in a
        // grow-only union means one copy's geometry silently wins on both pages.
        val note = store.create("Notebook", "device-a")
        val session = session(note)
        val page = note.document.pages.single().id
        session.apply(page, listOf(addOp("obj-1"), addOp("obj-2")))

        val copy = assertNotNull(session.duplicatePage(page))
        val originalIds = session.page(page).objects.map { it.id }
        val copyIds = session.page(copy).objects.map { it.id }

        assertEquals(2, copyIds.size)
        assertTrue(originalIds.intersect(copyIds.toSet()).isEmpty(), "duplicate reused an ObjectId")
        // Same ink, new identity.
        assertContentEquals(
            session.page(page).objects.map { (it as StrokeObject).geometry.bytes.toList() },
            session.page(copy).objects.map { (it as StrokeObject).geometry.bytes.toList() },
        )
    }

    @Test
    fun `undo state survives a restart`() = runTest {
        val note = store.create("Notebook", "device-a")
        val page = note.document.pages.single().id
        val session = session(note)
        session.apply(page, listOf(addOp("obj-1")))
        session.apply(page, listOf(addOp("obj-2")))
        session.flush()

        val restored = session(assertNotNull(store.open(note.document.id)))

        assertTrue(restored.canUndo(page), "the undo stack did not survive the restart")
        assertTrue(restored.undo(page))
        assertEquals(1, restored.page(page).objects.size)
        assertTrue(restored.redo(page))
        assertEquals(2, restored.page(page).objects.size)
    }

    @Test
    fun `an undo cannot reach a page the gesture did not happen on`() = runTest {
        // ⭐ The bug: the stacks were document-wide and `undo(pageId)` replayed whatever was on top
        // onto the page the *caller* named — which is the page on screen. So changing page 1's paper,
        // turning to page 2 and pressing undo wrote the inverse `SetPageMeta` into page 2's log:
        // page 2's paper changed, was journaled, was uploaded, and pressing undo again popped the
        // next entry rather than putting it right. The `= Unit` in `InkScreen.setPageMeta` was the
        // only reason nobody had seen it.
        val note = store.create("Notebook", "device-a")
        val one = note.document.pages.single().id
        val session = session(note)
        val two = session.addPage(1, background = Background.Template(PageTemplate.GRID))
        val paperOnTwo = session.page(two).background
        val paperOnOne = session.page(one).background

        // The gesture happens on page one.
        val cornell = PageOp.SetPageMeta(
            session.nextOpId(),
            session.nextClock(),
            background = Background.Template(PageTemplate.CORNELL),
        )
        session.apply(one, listOf(cornell))

        // Undo asked for while page two is showing.
        assertFalse(session.canUndo(two), "page two offered an undo for a gesture made on page one")
        assertFalse(session.undo(two), "the undo landed on page two")
        assertEquals(paperOnTwo, session.page(two).background, "page two's paper was changed by page one's undo")

        // And the gesture is still undoable where it belongs — not consumed by the refusal.
        assertTrue(session.canUndo(one))
        assertTrue(session.undo(one))
        assertEquals(paperOnOne, session.page(one).background)
    }

    @Test
    fun `each page keeps its own undo history`() = runTest {
        // The corollary of the fix, and the reason it filters rather than refusing outright: drawing
        // on page two must not consume or block page one's undo.
        val note = store.create("Notebook", "device-a")
        val one = note.document.pages.single().id
        val session = session(note)
        val two = session.addPage(1, background = Background.Template(PageTemplate.GRID))

        session.apply(one, listOf(addOp("on-one")))
        session.apply(two, listOf(addOp("on-two")))

        assertTrue(session.undo(two), "page two could not undo its own stroke")
        assertEquals(0, session.page(two).objects.size)
        assertEquals(1, session.page(one).objects.size, "undoing on page two removed page one's stroke")

        assertTrue(session.undo(one), "page one's undo was consumed by the gesture on page two")
        assertEquals(0, session.page(one).objects.size)
    }

    @Test
    fun `a snapshot is discarded when an op older than it arrives`() = runTest {
        // SPEC §4.7 — "the snapshot is a cache, not the truth."
        val note = store.create("Notebook", "device-a")
        val page = note.document.pages.single().id
        val session = session(note)
        session.apply(page, listOf(addOp("obj-1"), addOp("obj-2")))
        // Materialized first, so the two ops are in the *log* before anything caches a fold
        // of them. A snapshot taken over unflushed work would be a cache of state the log
        // does not contain, and discarding it — which rule 9 requires the moment a late op
        // arrives — would then lose that work outright. `compactIfDue` refuses to do this;
        // the test must not do it either, or it proves the opposite of what it claims.
        session.materialize()
        store.writeSnapshot(note.document.id, session.page(page), "device-a")
        assertNotNull(store.readSnapshot(note.document.id, page))

        // A peer's op that sorts before the snapshot — the case that cannot be patched in.
        val late = PageOp.AddObject(
            opId = ids.newOpId(),
            clock = dev.inkstone.core.model.LamportClock(1, "device-b"),
            obj = stroke("obj-late"),
        )
        val fresh = session(assertNotNull(store.open(note.document.id)))
        // `merge`, because that is what this is: a peer's op arriving. Since M6 it is the
        // only door remote work comes through, and it is the door that reports
        // SNAPSHOT_STALE rather than leaving the caller to notice.
        val outcome = fresh.merge(page, listOf(late))

        assertEquals(FoldOutcome.SNAPSHOT_STALE, outcome, "a late remote op must report the snapshot stale")
        assertNull(store.readSnapshot(note.document.id, page), "the stale snapshot was kept")
        // The late op is not merely tolerated — it is present, which is the half of CLAUDE.md
        // rule 9 that a discarded snapshot alone does not prove.
        assertEquals(3, fresh.page(page).objects.size, "the peer's op was lost in the re-fold")
    }

    @Test
    fun `the cost of opening a note does not grow with what is drawn on its pages`() = runTest {
        // ROADMAP M2: "200-page notebook → interactive first page in < 2 s". The device
        // timing is a manual gate. The property it depends on is checkable here, and it is
        // stronger than a byte budget: opening reads the tail, the central directory and the
        // manifest, all of which scale with page *count* — so ten times the ink must cost
        // the same to open.
        val light = openCost(inkBytesPerPage = LIGHT_INK_BYTES)
        val heavy = openCost(inkBytesPerPage = HEAVY_INK_BYTES)

        assertTrue(heavy.bundleSize > light.bundleSize * 5, "the heavy note was not actually heavier")
        assertEquals(light.openBytes, heavy.openBytes, "opening a note read its page contents")
        assertTrue(
            heavy.onePageBytes < heavy.bundleSize / (BIG_NOTE_PAGES / 4),
            "reading one of $BIG_NOTE_PAGES pages cost ${heavy.onePageBytes} of ${heavy.bundleSize} bytes",
        )
    }

    private class OpenCost(val bundleSize: Long, val openBytes: Int, val onePageBytes: Int)

    private suspend fun openCost(inkBytesPerPage: Int): OpenCost {
        val note = store.create("Big notebook", "device-a", pageCount = BIG_NOTE_PAGES)
        val session = session(note)
        for (ref in session.document.livePages) {
            session.apply(
                ref.id,
                listOf(
                    PageOp.AddObject(
                        opId = ids.newOpId(),
                        clock = dev.inkstone.core.model.LamportClock(++counter, "device-a"),
                        obj = stroke("obj-${ref.id.value}", inkBytesPerPage),
                    ),
                ),
            )
        }
        session.close(NoteSyncState.LOCAL_ONLY)

        val path = NoteLayout.bundle(note.document.id)
        val counting = CountingByteSource(FileByteSource(fs, path))
        val reader = assertNotNull(BundleReader.open(counting))
        val openBytes = counting.bytesRead
        assertEquals(BIG_NOTE_PAGES, reader.document.pages.size)
        assertNotNull(reader.page(reader.document.pages.first().id))
        return OpenCost(fs.size(path), openBytes, counting.bytesRead - openBytes)
    }

    @Test
    fun `materializing keeps the ink on pages that were evicted from the cache`() = runTest {
        // The regression this exists for: materialize collected each page from the cache or
        // from a stale in-memory view of the journal, so every page that had been evicted was
        // written *empty* — destroying the session's work on all but the last few pages.
        val note = store.create("Notebook", "device-a", pageCount = MANY_PAGES)
        val session = NoteSession(
            store = store,
            ids = ids,
            clock = clock,
            note = note,
            clocks = LamportClockGenerator("device-a", note.highestClockCounter),
            maxCachedPages = 2,
        )
        val pages = session.document.livePages.map { it.id }
        for (page in pages) session.apply(page, listOf(addOp("obj-${page.value}")))

        session.materialize()

        // Read it back through a fresh open with an empty journal, so the only place the ink
        // can be coming from is the bundle.
        val reopened = session(assertNotNull(store.open(note.document.id)))
        assertEquals(Journal.HEADER_SIZE.toLong(), fs.size(NoteLayout.journal(note.document.id)))
        for (page in pages) {
            assertEquals(1, reopened.page(page).objects.size, "page ${page.value} was materialized empty")
        }
    }

    @Test
    fun `evicting a cached page loses nothing`() = runTest {
        // The claim behind "memory doesn't grow linearly with page count": eviction is
        // always safe because the journal already holds the ops.
        val note = store.create("Notebook", "device-a", pageCount = MANY_PAGES)
        val session = NoteSession(
            store = store,
            ids = ids,
            clock = clock,
            note = note,
            clocks = LamportClockGenerator("device-a", note.highestClockCounter),
            maxCachedPages = 2,
        )
        val pages = session.document.livePages.map { it.id }
        for (page in pages) {
            session.apply(page, listOf(addOp("obj-${page.value}")))
        }

        // Every page has been evicted at least once by now; re-folding must find its ink.
        for (page in pages) {
            assertEquals(1, session.page(page).objects.size, "page ${page.value} lost its ink to eviction")
        }
    }

    // -----------------------------------------------------------------------
    // view.json — where this device left off
    // -----------------------------------------------------------------------

    @Test
    fun `a note remembers the page it was left on`() = runTest {
        val note = store.create(title = "n", deviceId = "d", background = Background.Template(PageTemplate.RULED))
        val session = NoteSession.open(store, ids, clock, note, "d")
        val second = session.addPage(1, background = Background.Template(PageTemplate.BLANK))
        session.flush()

        store.saveViewState(note.document.id, second)

        assertEquals(second, store.loadViewState(note.document.id))
    }

    @Test
    fun `a note never opened on this device remembers nothing`() = runTest {
        val note = store.create(title = "n", deviceId = "d", background = Background.Template(PageTemplate.RULED))

        assertNull(store.loadViewState(note.document.id))
    }

    /**
     * The file is a convenience, so a damaged one must degrade to "open on page one" rather
     * than take the note down with it — the note itself is elsewhere and is fine.
     */
    @Test
    fun `a corrupt view state is not a corrupt note`() = runTest {
        val note = store.create(title = "n", deviceId = "d", background = Background.Template(PageTemplate.RULED))
        fs.writeBytesAtomically(NoteLayout.viewState(note.document.id), "{ this is not json".encodeToByteArray())

        assertNull(store.loadViewState(note.document.id))
        assertNotNull(store.open(note.document.id))
    }

    private class CountingByteSource(private val delegate: ByteSource) : ByteSource {
        var bytesRead: Int = 0
            private set

        override suspend fun size(): Long = delegate.size()

        override suspend fun read(offset: Long, length: Int): ByteArray =
            delegate.read(offset, length).also { bytesRead += it.size }
    }

    private companion object {
        const val FLUSHES = 5
        const val TORN_BYTES = 20
        const val BIG_NOTE_PAGES = 200
        const val LIGHT_INK_BYTES = 32
        const val HEAVY_INK_BYTES = 8192
        const val MANY_PAGES = 8
    }
}
