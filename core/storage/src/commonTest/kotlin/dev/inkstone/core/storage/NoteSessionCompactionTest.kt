package dev.inkstone.core.storage

import dev.inkstone.core.model.Compaction
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FoldOutcome
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.LamportClockGenerator
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * SPEC §4.7 — **that compaction can happen at all**, and that it still refuses everything it
 * is supposed to refuse.
 *
 * ⚠️ **Most of these reach the gate through `close()`, which is the way the app reaches it**, and
 * that is the whole reason the file exists. Both halves of `Compaction.isAllowed`'s inputs were
 * read off values the materialize on the line above had just reset — an 8-byte journal against a
 * 2 MB threshold, and a `modifiedEpochMillis` stamped `now` against a 30-day horizon — so no note
 * in this app had ever been compacted, and every page has re-folded its entire lifetime log at
 * every open since M2.
 *
 * Four milestones of tests missed it because the ones that made compaction *fire* all reach it
 * the other way: `NoteStoreTest` and `MergeFoundationTest` write the snapshot by hand or call
 * `compactIfDue` on a session that flushed a large journal and never materialized. Both are
 * legitimate — `a large journal still compacts on a session that has never materialized` keeps
 * the second working — and neither performs the two resets, so the suite could show compaction
 * behaving correctly while the feature was switched off for every user. CLAUDE.md's *"a feature
 * can be correct in every test and inert in the app"*, in the shape where the fixture is not
 * more generous than production but merely differently ordered.
 */
class NoteSessionCompactionTest {

    private val fs = FakeFileSystem()
    private val clock = TestClock()
    private val ids = IdFactory(clock, Random(7))
    private val store = NoteStore(fs, clock, ids)

    private var counter = 0L

    private fun addOp(id: String) = PageOp.AddObject(
        opId = ids.newOpId(),
        clock = LamportClock(++counter, "device-a"),
        obj = StrokeObject(id = ObjectId(id), geometry = StrokeGeometry(ByteArray(4) { it.toByte() })),
    )

    /** Real bytes rather than a count — the same 72 KiB stroke `MergeFoundationTest` uses. */
    private fun bulkOp(id: String) = PageOp.AddObject(
        opId = ids.newOpId(),
        clock = LamportClock(++counter, "device-a"),
        obj = StrokeObject(
            id = ObjectId(id),
            geometry = StrokeGeometry(ByteArray(BULK_GEOMETRY_BYTES) { (it + id.length).toByte() }),
        ),
    )

    private suspend fun session(note: OpenNote) = NoteSession.open(store, ids, clock, note, deviceId = "device-a")

    private suspend fun reopen(id: DocumentId) = assertNotNull(store.open(id))

    /**
     * A note carrying [ops] ops on its one page, materialized and closed at the current time.
     *
     * `replay`, not `apply`: an undo entry per op would have every flush re-serialise the whole
     * stack, and the accumulated **log** is the only thing this file is measuring.
     */
    private suspend fun writtenNote(ops: Int): DocumentId {
        val note = store.create("Notebook", "device-a")
        val page = note.document.pages.single().id
        val session = session(note)
        session.replay(page, List(ops) { addOp("obj-$it") })
        session.close(NoteSyncState.LOCAL_ONLY)
        return note.document.id
    }

    @Test
    fun `a note untouched for the horizon is compacted when it is next closed`() = runTest {
        val id = writtenNote(OPS_OVER_THRESHOLD)
        val page = reopen(id).document.pages.single().id
        assertNull(store.readSnapshot(id, page), "a note written to seconds ago was compacted")

        clock.advance(Compaction.STABILITY_HORIZON_MILLIS + 1)
        // Opened and closed without drawing anything: reading last term's notebook, which is
        // exactly the note whose open is slow and exactly the note SPEC §4.7 is written for.
        session(reopen(id)).close(NoteSyncState.LOCAL_ONLY)

        assertNotNull(
            store.readSnapshot(id, page),
            "SPEC §4.7 compaction did not fire on a ${Compaction.STABILITY_HORIZON_MILLIS}-ms-old note",
        )
    }

    @Test
    fun `compaction refuses a note this session has just written to`() = runTest {
        val id = writtenNote(OPS_OVER_THRESHOLD)
        val page = reopen(id).document.pages.single().id
        clock.advance(Compaction.STABILITY_HORIZON_MILLIS + 1)

        // Identical to the test above but for one stroke — which is the invariant, stated as a
        // difference: an op written seconds ago is one a peer may still hold something older
        // than, and folding it into a snapshot buys a cache that the next pull throws away.
        val session = session(reopen(id))
        session.replay(page, listOf(addOp("drawn-today")))
        session.close(NoteSyncState.LOCAL_ONLY)

        assertNull(store.readSnapshot(id, page), "an op written seconds ago was folded into a snapshot")
    }

    @Test
    fun `compaction refuses a log too small to be worth folding`() = runTest {
        val id = writtenNote(ops = 4)
        val page = reopen(id).document.pages.single().id
        clock.advance(Compaction.STABILITY_HORIZON_MILLIS + 1)

        session(reopen(id)).close(NoteSyncState.LOCAL_ONLY)

        assertNull(store.readSnapshot(id, page), "a four-op note cleared SPEC §4.7's size threshold")
    }

    @Test
    fun `a CONFLICTED note is refused at the call site that used to default it`() = runTest {
        val id = writtenNote(OPS_OVER_THRESHOLD)
        val page = reopen(id).document.pages.single().id
        clock.advance(Compaction.STABILITY_HORIZON_MILLIS + 1)

        // SPEC §4.7's first and most important refusal. It is asserted here rather than only in
        // `CompactionTest` because it is the one the *call site* used to defeat, by taking a
        // default — and a refusal is only real where the gate is actually reached from.
        session(reopen(id)).close(NoteSyncState.CONFLICTED)

        assertNull(store.readSnapshot(id, page), "a CONFLICTED note was compacted")
    }

    @Test
    fun `a large journal still compacts on a session that has never materialized`() = runTest {
        // The shape `MergeFoundationTest` and SPEC §13.2 row 8 both drive: flush a real 2 MB
        // journal, reopen, call the gate directly. It is the *only* shape in which the gate
        // used to be able to pass, which is why the suite could show compaction working while
        // no user had ever had a note compacted — so counting the bundle must **add** to the
        // journal rather than replace it. Row 8 talks to a real Nextcloud and takes minutes;
        // this is the cheap guard for it.
        val note = store.create("Notebook", "device-a")
        val page = note.document.pages.single().id
        val writing = session(note)
        repeat(BULK_OPS) { writing.replay(page, listOf(bulkOp("bulk-$it"))) }
        writing.flush()
        clock.advance(Compaction.STABILITY_HORIZON_MILLIS + 1)

        val compacting = session(reopen(note.document.id))
        compacting.compactIfDue(NoteSyncState.SYNCED, oldestAckedCounter = null)

        assertNotNull(
            store.readSnapshot(note.document.id, page),
            "a large unmaterialized journal no longer reaches SPEC §4.7's threshold",
        )
    }

    @Test
    fun `an unknown modified timestamp is not read as fifty years old`() = runTest {
        val id = writtenNote(OPS_OVER_THRESHOLD)
        val page = reopen(id).document.pages.single().id
        clock.advance(Compaction.STABILITY_HORIZON_MILLIS + 1)

        // 0 is `modifiedEpochMillis`'s default, and therefore what a manifest written before the
        // field existed reads back as (`LibraryRepository` spells the same sentinel out with
        // `takeIf { it > 0 }`). Read literally it is 1970 — an age that clears every horizon
        // there could ever be, so the one input the gate cannot verify would be the one input
        // that always says yes.
        val opened = reopen(id)
        val unknown = opened.copy(document = opened.document.copy(modifiedEpochMillis = 0L))
        NoteSession(
            store = store,
            ids = ids,
            clock = clock,
            note = unknown,
            clocks = LamportClockGenerator("device-a", unknown.highestClockCounter),
        ).close(NoteSyncState.LOCAL_ONLY)

        assertNull(store.readSnapshot(id, page), "an absent modified timestamp was read as an ancient note")
    }

    @Test
    fun `a note that has stopped changing is snapshotted once and not again`() = runTest {
        val id = writtenNote(OPS_OVER_THRESHOLD)
        val page = reopen(id).document.pages.single().id

        clock.advance(Compaction.STABILITY_HORIZON_MILLIS + 1)
        session(reopen(id)).close(NoteSyncState.LOCAL_ONLY)
        val first = fs.readBytes(NoteLayout.snapshot(id, page))

        // Another horizon later the gate passes again, on a note nothing has touched in between.
        clock.advance(Compaction.STABILITY_HORIZON_MILLIS + 1)
        session(reopen(id)).close(NoteSyncState.LOCAL_ONLY)

        // `writeSnapshot` mints a fresh opId per object, so a rewrite is visible in the bytes
        // even though the fold it describes is identical. Worth pinning: the gate passes for a
        // note *nothing is changing*, which is precisely the note that would otherwise
        // re-serialise every object it has on every close for the rest of its life.
        assertContentEquals(first, fs.readBytes(NoteLayout.snapshot(id, page)), "the unchanged page was rewritten")
    }

    @Test
    fun `a snapshot written through the gate is still discarded by a late peer op`() = runTest {
        // CLAUDE.md rule 9, against a snapshot the *gate* produced rather than one the test
        // wrote by hand. A snapshot is a cache and the raw log is kept, so this is what makes a
        // wrongly-timed compaction cost a re-fold instead of a stroke.
        val id = writtenNote(OPS_OVER_THRESHOLD)
        val page = reopen(id).document.pages.single().id
        clock.advance(Compaction.STABILITY_HORIZON_MILLIS + 1)
        session(reopen(id)).close(NoteSyncState.LOCAL_ONLY)
        assertNotNull(store.readSnapshot(id, page), "nothing was compacted so there is nothing to invalidate")

        val late = PageOp.AddObject(
            opId = ids.newOpId(),
            clock = LamportClock(1, "device-b"),
            obj = StrokeObject(id = ObjectId("from-the-peer"), geometry = StrokeGeometry(ByteArray(4))),
        )
        val fresh = session(reopen(id))

        assertEquals(FoldOutcome.SNAPSHOT_STALE, fresh.merge(page, listOf(late)))
        assertNull(store.readSnapshot(id, page), "the stale snapshot was kept")
        assertEquals(
            OPS_OVER_THRESHOLD + 1,
            fresh.page(page).objects.size,
            "the peer's op was lost in the re-fold",
        )
    }

    private companion object {
        /**
         * Enough ops to clear SPEC §4.7's 2 MB threshold at `NoteSession`'s 512-byte-per-op
         * estimate, which puts the bar at 4,096.
         *
         * Written as a margin over a derivation rather than a bare number: if the estimate
         * changes, `a note untouched for the horizon is compacted when it is next closed` fails
         * saying compaction did not fire, which points here.
         */
        const val OPS_OVER_THRESHOLD = 4_200

        /** 40 × 72 KiB, past the threshold in real bytes. Same numbers as `MergeFoundationTest`. */
        const val BULK_OPS = 40
        const val BULK_GEOMETRY_BYTES = 72 * 1024
    }
}
