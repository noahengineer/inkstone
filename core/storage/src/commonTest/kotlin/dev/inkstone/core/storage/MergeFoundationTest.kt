package dev.inkstone.core.storage

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Document
import dev.inkstone.core.model.FoldOutcome
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageRef
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The foundations M6's merge rests on, each of which was broken or absent before it.
 *
 * Every test here corresponds to a way the app could have lost or diverged data silently —
 * all ops present, no exception, nothing in a log. They are unit tests rather than part of
 * SPEC §13.2's merge matrix on purpose: the matrix proves the *system* converges against a
 * real server, and these prove the *mechanisms* it converges with, which is where a
 * regression would otherwise be invisible until two tablets disagreed for a week.
 */
class MergeFoundationTest {

    private val fs = FakeFileSystem()
    private val clock = TestClock()
    private val ids = IdFactory(clock, Random(7))
    private val store = NoteStore(fs, clock, ids)

    private var counter = 0L

    private fun stroke(id: String) = StrokeObject(
        id = ObjectId(id),
        geometry = StrokeGeometry(ByteArray(64) { (it + id.length).toByte() }),
    )

    private fun addOp(id: String, device: String = "device-a", at: Long = ++counter) = PageOp.AddObject(
        opId = "op-$id-$device",
        clock = LamportClock(at, device),
        obj = stroke(id),
    )

    private fun bulkOp(id: String, device: String = "device-a") = PageOp.AddObject(
        opId = "op-$id-$device",
        clock = LamportClock(++counter, device),
        obj = StrokeObject(
            id = ObjectId(id),
            geometry = StrokeGeometry(ByteArray(BULK_GEOMETRY_BYTES) { (it + id.length).toByte() }),
        ),
    )

    private suspend fun session(note: OpenNote, device: String = "device-a") =
        NoteSession.open(store, ids, clock, note, deviceId = device)

    // -----------------------------------------------------------------------
    // Device identity — SPEC §4.1's tiebreak
    // -----------------------------------------------------------------------

    @Test
    fun `the device id is stable across instances and across a restart`() = runTest {
        val first = DeviceIdentity(fs, ids).get()
        // A second instance is a second launch: nothing is cached between them but the file.
        val second = DeviceIdentity(fs, ids).get()
        val again = DeviceIdentity(fs, ids).get()

        assertTrue(first.isNotEmpty())
        assertEquals(first, second, "a restart minted a new device id")
        assertEquals(first, again)
    }

    @Test
    fun `two installs get different device ids`() = runTest {
        // The property SPEC §4.1's tiebreak needs and did not have: two devices editing one
        // note must stamp *different* deviceIds, or two concurrent ops at the same counter
        // compare equal and each device keeps whichever it folded first — permanently.
        val a = DeviceIdentity(FakeFileSystem(), IdFactory(clock, Random(1))).get()
        val b = DeviceIdentity(FakeFileSystem(), IdFactory(clock, Random(2))).get()

        assertNotEquals(a, b)
    }

    @Test
    fun `the device id does not come from the note that created it`() = runTest {
        // The M2/M5 bug: `deviceId` was read out of the manifest, so every device that
        // opened a note adopted the *creating* device's identity.
        val installed = DeviceIdentity(fs, ids).get()
        val note = store.create("Notebook", deviceId = "some-other-device")

        assertEquals("some-other-device", note.document.deviceId, "the manifest records who created it")
        assertNotEquals(installed, note.document.deviceId, "and that is not this install")
    }

    // -----------------------------------------------------------------------
    // merge vs apply — SPEC §4.6 and §7.7
    // -----------------------------------------------------------------------

    @Test
    fun `a merged remote op never enters the local undo stack`() = runTest {
        // SPEC §4.6: "an incoming remote op does not enter your undo stack, and undoing does
        // not undo someone else's work." Before M6 this was a boolean parameter defaulting to
        // the wrong value, so forgetting it put a peer's strokes on the stack — and `flush`
        // persisted that to undo.json, so the next two-finger tap deleted a collaborator's
        // work durably.
        val note = store.create("Notebook", "device-a")
        val page = note.document.pages.single().id
        val session = session(note)

        session.merge(page, listOf(addOp("remote-1", device = "device-b")))

        assertFalse(session.canUndo(page), "a remote op is undoable locally")
        assertEquals(1, session.page(page).objects.size, "the remote op was not applied at all")
    }

    @Test
    fun `a local gesture is undoable and a replay is not`() = runTest {
        val note = store.create("Notebook", "device-a")
        val page = note.document.pages.single().id
        val session = session(note)

        session.apply(page, listOf(addOp("local-1")))
        assertTrue(session.canUndo(page), "a local gesture must be undoable")

        val fresh = session(assertNotNull(store.open(note.document.id)))
        fresh.replay(page, listOf(addOp("replayed-1")))
        assertFalse(fresh.canUndo(page), "a replay must not add an undo entry")
    }

    @Test
    fun `merge subtracts ops this page already has`() = runTest {
        // A pull delivers the peer's *whole* page log, most of which is this device's own ops
        // coming back. The fold survives duplicates — every op is an absolute register — but
        // the journal would grow by the size of the note on every poll, forever.
        val note = store.create("Notebook", "device-a")
        val page = note.document.pages.single().id
        val session = session(note)
        val mine = addOp("obj-1")
        session.apply(page, listOf(mine))
        session.flush()

        val before = fs.size(NoteLayout.journal(note.document.id))
        // The peer echoes our op back, plus one of its own.
        val outcome = session.merge(page, listOf(mine, addOp("obj-2", device = "device-b")))
        session.flush()

        assertEquals(2, session.page(page).objects.size, "the peer's op did not land")
        assertEquals(FoldOutcome.APPENDED, outcome)
        val grew = fs.size(NoteLayout.journal(note.document.id)) - before
        // One op's worth, not two. The echo must cost nothing.
        assertTrue(grew > 0, "the peer's op was not journaled at all")
        val reopened = session(assertNotNull(store.open(note.document.id)))
        assertEquals(2, reopened.page(page).objects.size, "the merge did not survive a reopen")
    }

    @Test
    fun `merging an op this page already has is a no-op`() = runTest {
        val note = store.create("Notebook", "device-a")
        val page = note.document.pages.single().id
        val session = session(note)
        val mine = addOp("obj-1")
        session.apply(page, listOf(mine))
        session.flush()
        val before = fs.size(NoteLayout.journal(note.document.id))

        session.merge(page, listOf(mine))
        session.flush()

        assertEquals(before, fs.size(NoteLayout.journal(note.document.id)), "an echoed op grew the journal")
    }

    // -----------------------------------------------------------------------
    // mergeDocument — SPEC §4.3 and §7.7 layer 2
    // -----------------------------------------------------------------------

    @Test
    fun `a page only the peer has is appended and never dropped`() = runTest {
        // SPEC §4.3: "any page not present in a merged order is appended at the end, never
        // dropped." A plain LWW list loses one device's new page and orphans its file.
        val note = store.create("Notebook", "device-a", pageCount = 2)
        val session = session(note)
        val mine = session.document.pages.map { it.id }
        val theirs = PageId("peer-page")

        session.mergeDocument(
            Document(
                id = note.document.id,
                title = note.document.title,
                pages = note.document.pages + PageRef(theirs, FractionalKey.between(null, null)),
            ),
        )

        val ids = session.document.pages.map { it.id }
        assertTrue(ids.containsAll(mine), "a local page was dropped by the merge")
        assertTrue(theirs in ids, "the peer's page was dropped by the merge")
        assertEquals(3, session.document.livePages.size)
    }

    @Test
    fun `a merged page survives materialize`() = runTest {
        // ⚠️ The worst failure available in this module. `merge` accepts ops for a page the
        // document has not heard of; `BundleWriter` iterates `document.pages` and skips any
        // page missing from it; `materialize` then resets the journal. A remote page's ops
        // journaled without its PageRef are destroyed permanently, and the result is a valid
        // PDF with the right page count and a missing page. `mergeDocument` must run first.
        val note = store.create("Notebook", "device-a")
        val session = session(note)
        val theirs = PageId("peer-page")

        session.mergeDocument(
            Document(
                id = note.document.id,
                title = note.document.title,
                pages = note.document.pages + PageRef(theirs, FractionalKey.between(null, null)),
            ),
        )
        session.merge(theirs, listOf(addOp("peer-obj", device = "device-b")))
        session.materialize()

        val reopened = assertNotNull(store.open(note.document.id))
        val restored = session(reopened)
        assertTrue(theirs in reopened.document.pages.map { it.id }, "the merged page is not in the manifest")
        assertEquals(1, restored.page(theirs).objects.size, "the merged page's ops were destroyed by materialize")
    }

    @Test
    fun `the later rename wins and the loser is reported`() = runTest {
        val note = store.create("Local title", "device-a")
        val session = session(note)
        session.rename("Renamed here")
        val localClock = assertNotNull(session.document.titleClock)

        val overridden = session.mergeDocument(
            Document(
                id = note.document.id,
                title = "Renamed on the peer",
                pages = session.document.pages,
                titleClock = LamportClock(localClock.counter + 1, "device-b"),
            ),
        )

        assertTrue(overridden, "the caller was not told the local title lost")
        assertEquals("Renamed on the peer", session.document.title)
    }

    @Test
    fun `an earlier rename loses and is not reported as an override`() = runTest {
        val note = store.create("Local title", "device-a")
        val session = session(note)
        session.rename("Renamed here")
        val localClock = assertNotNull(session.document.titleClock)

        val overridden = session.mergeDocument(
            Document(
                id = note.document.id,
                title = "Older peer rename",
                pages = session.document.pages,
                titleClock = LamportClock(localClock.counter - 1, "device-b"),
            ),
        )

        assertFalse(overridden)
        assertEquals("Renamed here", session.document.title)
    }

    @Test
    fun `a peer that never renamed cannot overwrite a local rename`() = runTest {
        // A null clock means "never renamed, or written by a build older than M6". It must
        // lose to any recorded rename, or every merge with an older peer reverts the title.
        val note = store.create("Original", "device-a")
        val session = session(note)
        session.rename("Renamed here")

        val overridden = session.mergeDocument(
            Document(id = note.document.id, title = "Original", pages = session.document.pages, titleClock = null),
        )

        assertFalse(overridden)
        assertEquals("Renamed here", session.document.title)
    }

    @Test
    fun `a second rename in a later session sorts after the first`() = runTest {
        // ⭐ `rename` takes its clock from the note's Lamport generator, and `materialize` computed
        // the bundle's `highestClockCounter` over **page ops only** — so the counter a rename
        // consumed was never recorded and the next session re-issued it. A peer that merged the
        // first rename then holds the identical clock, `resolveTitle` requires *strictly* later, and
        // each device refuses the other's title forever: `titleOverridden` false, no attention row,
        // every op present. Reached with no concurrency whatsoever — a rename, a close, a rename.
        val note = store.create("Notebook", "device-a")
        val first = session(note)
        first.rename("Renamed")
        val firstClock = assertNotNull(first.document.titleClock)
        first.materialize()

        val reopened = assertNotNull(store.open(note.document.id))
        val second = session(reopened)
        second.rename("Renamed again")

        val secondClock = assertNotNull(second.document.titleClock)
        assertTrue(
            secondClock > firstClock,
            "the second rename was stamped $secondClock, not after the first's $firstClock — " +
                "a peer holding the first rename can never accept the second",
        )
    }

    @Test
    fun `a rename that was only flushed still advances the next sessions clock`() = runTest {
        // The half of the same bug the manifest cannot fix. A flushed-but-not-materialized rename
        // lives in the journal's document record and in no op, so `NoteStore.open` has to fold its
        // counter in too — and a crash between an autosave and the next materialization is SPEC
        // §5.1's ordinary case, not an exotic one.
        val note = store.create("Notebook", "device-a")
        val first = session(note)
        first.rename("Renamed")
        val firstClock = assertNotNull(first.document.titleClock)
        first.flush()

        val reopened = assertNotNull(store.open(note.document.id))

        assertTrue(
            reopened.highestClockCounter >= firstClock.counter,
            "the reopened note claims ${reopened.highestClockCounter} as its highest clock while its " +
                "own title was stamped at ${firstClock.counter}",
        )
        val second = session(reopened)
        second.rename("Renamed again")
        assertTrue(assertNotNull(second.document.titleClock) > firstClock, "the counter was issued twice")
    }

    @Test
    fun `a peers rename advances this devices clock`() = runTest {
        // `mergeDocument` adopted a peer's `titleClock` without observing it, so the next rename made
        // here was issued from a counter behind the peer's. The two clocks then came out with equal
        // counters and which title survived was settled by comparing two UUIDv7 device ids — on the
        // losing half of that coin flip the user's rename showed here, was refused there, and was
        // reverted by the next pull.
        val note = store.create("Local title", "device-a")
        val session = session(note)
        val peerClock = LamportClock(50, "device-b")

        session.mergeDocument(
            Document(
                id = note.document.id,
                title = "Renamed on the peer",
                pages = session.document.pages,
                titleClock = peerClock,
            ),
        )
        session.rename("Renamed here afterwards")

        assertTrue(
            assertNotNull(session.document.titleClock) > peerClock,
            "a rename made after merging the peer's does not sort after it, so the peer will refuse it",
        )
    }

    @Test
    fun `merging does not adopt the peers local_rev`() = runTest {
        // `local_rev` is this device's count of this device's commits, and the outbox compares
        // it against what it recorded at enqueue (SPEC §7.6). Taking a peer's higher value
        // jumps the counter past that and makes the merged work invisible to sync forever.
        val note = store.create("Notebook", "device-a")
        val session = session(note)
        val before = session.document.localRev

        session.mergeDocument(
            Document(
                id = note.document.id,
                title = note.document.title,
                pages = session.document.pages,
                localRev = before + 9_000,
            ),
        )

        assertEquals(before, session.document.localRev, "a peer's local_rev was adopted")
    }

    // -----------------------------------------------------------------------
    // Snapshots — SPEC §4.7
    // -----------------------------------------------------------------------

    @Test
    fun `every page gets its own snapshot`() = runTest {
        // One file per document meant each page overwrote the last, so every page but the
        // final one had no snapshot — which made SPEC §13.2 row 8 pass or fail according to
        // which page the test picked.
        val note = store.create("Notebook", "device-a", pageCount = 3)
        val session = session(note)
        val pages = note.document.pages.map { it.id }
        for (page in pages) session.apply(page, listOf(addOp("obj-${page.value}")))
        session.materialize()

        for (page in pages) store.writeSnapshot(note.document.id, session.page(page), "device-a")

        for (page in pages) {
            assertNotNull(store.readSnapshot(note.document.id, page), "page ${page.value} has no snapshot")
        }
    }

    @Test
    fun `compaction is refused for a note the server has not caught up with`() = runTest {
        // SPEC §4.7's first refusal, which was dead code in production because every caller
        // took `close()`'s LOCAL_ONLY default.
        val note = store.create("Notebook", "device-a")
        val page = note.document.pages.single().id
        val session = session(note)
        session.apply(page, listOf(addOp("obj-1")))
        session.materialize()
        clock.millis += TWO_MONTHS_MILLIS

        // No peer clock is known in this fixture, so the counter is genuinely null — the
        // refusal under test is the sync state's.
        session.compactIfDue(NoteSyncState.CONFLICTED, oldestAckedCounter = null)

        assertNull(store.readSnapshot(note.document.id, page), "a CONFLICTED note was compacted")
    }

    @Test
    fun `compaction is refused while work is unflushed`() = runTest {
        // A snapshot is a cache of the log. One folded over work that is only in memory would
        // contain state the log does not — and discarding it, which rule 9 requires when a
        // late op arrives, would lose that work outright.
        val note = store.create("Notebook", "device-a")
        val page = note.document.pages.single().id
        val session = session(note)
        session.apply(page, listOf(addOp("obj-1")))
        clock.millis += TWO_MONTHS_MILLIS

        session.compactIfDue(NoteSyncState.SYNCED, oldestAckedCounter = null)

        assertNull(store.readSnapshot(note.document.id, page), "a snapshot was taken over unflushed work")
    }

    @Test
    fun `compaction is refused while a peer has not acknowledged the newest ops`() = runTest {
        // SPEC §4.7's *other* horizon, and the one M6 is the first milestone able to supply:
        // thirty days of wall clock says nothing about a tablet that has been in a drawer.
        // `Compaction.isAllowed` has always implemented it; what was missing was anyone
        // passing the counter, and `compactIfDue` defaulting it to null is what hid that.
        // So this asserts the *threading*: same note, same journal, same clock, two answers
        // decided by the counter alone.
        val note = store.create("Notebook", "device-a")
        val page = note.document.pages.single().id
        val writing = session(note)
        // Real bytes past the real threshold, flushed and never materialized — a materialize
        // resets the journal to a bare header. Reopening is what makes `journalBytes` the
        // file's actual size rather than the session's per-op estimate.
        repeat(BULK_OPS) { writing.apply(page, listOf(bulkOp("bulk-$it"))) }
        writing.flush()
        clock.millis += TWO_MONTHS_MILLIS
        val compacting = session(assertNotNull(store.open(note.document.id), "the note did not reopen"))

        compacting.compactIfDue(NoteSyncState.SYNCED, oldestAckedCounter = 0)
        assertNull(
            store.readSnapshot(note.document.id, page),
            "a peer that has acked nothing still had this device's newest ops folded away",
        )

        compacting.compactIfDue(NoteSyncState.SYNCED, oldestAckedCounter = Long.MAX_VALUE)
        assertNotNull(
            store.readSnapshot(note.document.id, page),
            "the counter is being ignored rather than threaded: nothing else changed between these two calls",
        )
    }

    // -----------------------------------------------------------------------
    // Forward compatibility — SPEC §5.8
    // -----------------------------------------------------------------------

    @Test
    fun `the title clock survives a bundle round trip`() = runTest {
        val note = store.create("Notebook", "device-a")
        val session = session(note)
        session.rename("Renamed")
        val stamped = assertNotNull(session.document.titleClock)
        session.materialize()

        val reopened = assertNotNull(store.open(note.document.id))

        assertEquals("Renamed", reopened.document.title)
        assertEquals(stamped, reopened.document.titleClock, "the LWW clock did not survive the round trip")
        // ⭐ The assertion this test stopped one short of, which is why M6 shipped with the clock
        // surviving and the *counter* not: a clock that comes back is no use if the session that
        // comes back with it issues the same counter again. See `a second rename in a later session`.
        assertTrue(
            reopened.highestClockCounter >= stamped.counter,
            "the note records ${reopened.highestClockCounter} as its highest clock while its own title " +
                "was stamped at ${stamped.counter} — the next session re-issues that counter",
        )
    }

    @Test
    fun `an unknown manifest field survives a round trip`() = runTest {
        // SPEC §5.8 — "unknown ops and unknown JSON fields survive untouched". Only the op
        // half was honoured before M6; the manifest was rebuilt from a fixed list of names and
        // everything else was dropped. That is what lets `titleClock` be additive at schema 1.
        val note = store.create("Notebook", "device-a")
        val contents = BundleContents(
            document = note.document,
            pages = onePage(note),
            highestClockCounter = 0L,
            carriedManifest = mapOf("aFieldFromTheFuture" to JsonPrimitive("keep me")),
        )
        fs.writeBytesAtomically(NoteLayout.bundle(note.document.id), BundleWriter.write(contents))

        val reader = assertNotNull(BundleReader.open(FileByteSource(fs, NoteLayout.bundle(note.document.id))))
        assertEquals(
            JsonPrimitive("keep me"),
            reader.carriedManifest["aFieldFromTheFuture"],
            "an unknown manifest field was dropped on read",
        )

        // And it is still there after this build writes the note back out.
        val rewritten = BundleWriter.write(
            BundleContents(
                document = reader.document,
                pages = onePage(note),
                highestClockCounter = 0L,
                carriedManifest = reader.carriedManifest,
            ),
        )
        fs.writeBytesAtomically(NoteLayout.bundle(note.document.id), rewritten)
        val again = assertNotNull(BundleReader.open(FileByteSource(fs, NoteLayout.bundle(note.document.id))))
        assertEquals(JsonPrimitive("keep me"), again.carriedManifest["aFieldFromTheFuture"])
    }

    @Test
    fun `a peers bundle entry survives the first materialize after a merge`() = runTest {
        // `materialize` used to read `carried` from the *local* reader alone, so any entry that
        // existed only in the remote bundle — a peer's recognised text, or anything a newer
        // build added — was deleted by the act of merging. The lost entries are by definition
        // the ones this build cannot render, so nothing would ever have looked wrong.
        val note = store.create("Notebook", "device-a")
        val session = session(note)
        val peerEntry = "text/peer-page.json"
        val peerBytes = "recognised by a newer build".encodeToByteArray()

        session.materialize(extraCarried = mapOf(peerEntry to peerBytes))

        val reader = assertNotNull(BundleReader.open(FileByteSource(fs, NoteLayout.bundle(note.document.id))))
        assertTrue(peerEntry in reader.carriedNames, "the peer's bundle entry was dropped")
        assertContentEquals(peerBytes, assertNotNull(reader.carried()[peerEntry]))
    }

    /** A single-page `pages` map, for the tests that hand-build a bundle. */
    private fun onePage(note: OpenNote): Map<PageId, PageContents> =
        mapOf(note.document.pages.single().id to PageContents(PageSize.Letter, Background.Blank, emptyList()))

    private companion object {

        /** Comfortably past SPEC §4.7's 30-day causal-stability horizon. */
        const val TWO_MONTHS_MILLIS = 60L * 24 * 60 * 60 * 1000

        /**
         * 40 × 72 KiB is comfortably past `Compaction.JOURNAL_THRESHOLD_BYTES` (2 MiB).
         *
         * The threshold is in bytes and not op count on purpose (SPEC §4.7), so the honest way
         * to reach it is with real bytes rather than with thousands of tiny ops. Same numbers as
         * matrix row 8, which drives the same gate through the whole system.
         */
        const val BULK_OPS = 40
        const val BULK_GEOMETRY_BYTES = 72 * 1024
    }
}
