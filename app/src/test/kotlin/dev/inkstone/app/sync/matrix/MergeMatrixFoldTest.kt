package dev.inkstone.app.sync.matrix

import dev.inkstone.core.model.FoldOutcome
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.storage.MergeReport
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.sync.AttentionKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * SPEC §13.2 rows 8, 9 and 10 — **compaction, layer 3, and the undo stack.**
 *
 * These are the three rows whose failure is not a wrong picture but a wrong *record*: a snapshot
 * that stayed valid when it should not have, a peer's lossy file folded into full-fidelity local
 * data, and a peer's strokes on the local undo stack where the user's next two-finger tap deletes
 * them. All three are silent, and all three survive a restart.
 */
class MergeMatrixFoldTest {

    /**
     * ⚑-adjacent Row 8 — an op older than a snapshot forces a re-fold (SPEC §4.7, CLAUDE.md rule 9).
     *
     * On a **three-page** note, with the peer's op on **page 2**. That detail is the row: while
     * snapshots were per-document, every page but the last had no snapshot at all, so the row
     * passed or failed according to which page it happened to pick — and a green result proved
     * nothing.
     *
     * The journal is driven past SPEC §4.7's real 2 MB threshold and the clock past its real 30-day
     * horizon. Neither constant is touched: shrinking them would test a different rule.
     */
    @Test
    fun `row 8 - a peer op older than a page snapshot discards it and re-folds without loss`() = matrixRow(
        row = "row 8 (snapshot invalidation)",
        proven = "SNAPSHOT_STALE, the snapshot file gone, and the peer's strokes present after a real pull",
        notProven = "nothing extra — compaction is entirely local",
        timeout = 8.minutes,
    ) { devices ->
        val id = devices.share("Row8", pageCount = 3)
        val pages = assertNotNull(devices.a.document(id), "no note").livePages
        val page2 = pages[1].id

        // Enough real journal to pass Compaction.JOURNAL_THRESHOLD_BYTES, flushed and never
        // materialized — a materialize resets the journal to a bare header, which is why
        // `compactIfDue` is reached from a *reopened* session below rather than from `close()`.
        val writing = devices.a.session(id)
        repeat(BULK_STROKES) {
            writing.apply(page2, listOf(bulkStroke(devices.a, writing)))
            writing.flush()
        }
        devices.a.clock.advance(THIRTY_ONE_DAYS)

        val compacting = devices.a.session(id)
        // Explicitly no peer clock: A has not pulled anything yet, so `peerClock` holds nothing
        // for this note and the 30-day horizon above is the whole gate. That is what makes the
        // snapshot appear at all, and therefore what gives B's older op something to invalidate.
        compacting.compactIfDue(NoteSyncState.SYNCED, oldestAckedCounter = null)
        assertTrue(
            devices.a.fs.exists(NoteLayout.snapshot(id, page2)),
            "page 2 has no snapshot, so there is nothing for the peer's op to invalidate",
        )

        // B has been offline the whole time, so its clock is far behind and its op sorts before
        // everything in the snapshot.
        val fromB = devices.b.drawStrokeOn(id, page2)
        devices.b.drain()

        val pull = devices.a.pull()
        assertEquals(1, pull.merged, "A did not merge B's older op")
        val merged = assertIs<MergeReport.Merged>(devices.a.gate.merges.last(), "the merge did not report Merged")
        assertEquals(FoldOutcome.SNAPSHOT_STALE, merged.outcome, "an op older than the snapshot did not invalidate it")
        assertFalse(devices.a.fs.exists(NoteLayout.snapshot(id, page2)), "the stale snapshot was kept")

        val folded = devices.a.page(id, page2)
        assertTrue(folded.objects.any { it.id == fromB }, "the peer's late op was lost in the re-fold")
        assertEquals(BULK_STROKES + 1, folded.objects.size, "the re-fold did not reproduce A's own work")
    }

    /**
     * ⚑ Row 9, first variant — **a pulled file this build cannot read is never auto-recovered.**
     *
     * Recovery *replaces* an op log rather than unioning with it, and stamps the result at
     * `highestKnownCounter + 1_000_000`. Doing that to a peer's damaged file would let it win every
     * LWW register against this device's full-fidelity note, and look like a successful sync while
     * doing it. So it goes to layer 3: the local note is not touched, and the binding's ETag is
     * deliberately **not** advanced so the file is fetched again next pass.
     *
     * The mangling is a truncated download, which a healthy server never produces — that is why
     * this row is marked as needing a scripted step. The other mangling SPEC names, a third-party
     * editor stripping the embedded bundle, needs PdfBox to author and is covered over real bytes
     * by `:android:pdf`'s `PulledPdfExtractorTest`.
     */
    @Test
    fun `row 9 - an unreadable remote file becomes a conflict and never overwrites the local note`() = matrixRow(
        row = "row 9 (never auto-recover a pull)",
        proven = "layer 3 on an unreadable pull with the local note and its ETag untouched",
        notProven = "that a real Nextcloud preview generator or antivirus produces this shape of damage",
    ) { devices ->
        val id = devices.share("Row9")
        val page = devices.a.firstPage(id)
        val mine = devices.a.drawStroke(id)
        devices.a.drain()
        val path = devices.a.remotePath(id)
        val revBefore = assertNotNull(devices.a.document(id)).localRev
        val etagBefore = assertNotNull(devices.a.binding(id)).lastSyncedEtag

        val published = assertNotNull(devices.rawBytes(path), "nothing was uploaded")
        devices.rawWrite(path, published.copyOfRange(0, published.size / 2))

        val pull = devices.a.pull()

        assertEquals(1, pull.conflicted, "an unreadable pull was not treated as a conflict")
        assertEquals(0, pull.merged, "an unreadable pull was merged")
        assertTrue(devices.a.page(id, page).objects.any { it.id == mine }, "the local note lost its own stroke")
        assertEquals(revBefore, assertNotNull(devices.a.document(id)).localRev, "the local note was rewritten")
        assertEquals(
            etagBefore,
            assertNotNull(devices.a.binding(id)).lastSyncedEtag,
            "the ETag advanced over bytes that were never merged, so the file will never be fetched again",
        )
        assertEquals(NoteSyncState.CONFLICTED, assertNotNull(devices.a.summary(id)).syncState)
        assertTrue(
            devices.a.attention().any { it.kindOrNull == AttentionKind.REMOTE_NOT_RECOVERABLE },
            "nothing was put in the list the user comes back to",
        )
    }

    /**
     * ⚑ Row 9, second variant — `payloads.getOrPut` is **first-write-wins**, per `ObjectId`.
     *
     * A pull re-delivers the peer's whole page log, most of which is this device's own ops coming
     * back. Re-merging the same bytes must therefore change nothing at all: one object per
     * `ObjectId`, the same page count, and no upload owed. Replace-not-union is what makes that
     * true, and a fold that unioned payloads would double every stroke on every poll.
     */
    @Test
    fun `row 9b - re-merging the same remote bytes leaves one object per identity`() = matrixRow(
        row = "row 9b (first-write-wins payloads)",
        proven = "an idempotent re-merge of identical bytes at a new ETag",
        notProven = "nothing extra",
    ) { devices ->
        val id = devices.share("Row9b")
        val page = devices.a.firstPage(id)
        devices.a.drawStroke(id)
        devices.b.drawStroke(id)
        devices.syncBothWays()

        val before = devices.a.page(id, page).objects.map { it.id }.sortedBy { it.value }
        val pageCountBefore = assertNotNull(devices.a.document(id)).livePages.size

        // Same bytes, new ETag — which is exactly what a server does when a peer re-uploads an
        // unchanged file, and is the cheapest way to force the whole merge path to run again.
        val path = devices.a.remotePath(id)
        devices.rawWrite(path, assertNotNull(devices.rawBytes(path)))

        val pull = devices.a.pull()
        assertEquals(1, pull.merged, "the re-delivered file was not merged")
        val merged = assertIs<MergeReport.Merged>(devices.a.gate.merges.last())
        assertEquals(0, merged.remoteNovelOps, "ops this device already had were counted as novel")

        assertEquals(before, devices.a.page(id, page).objects.map { it.id }.sortedBy { it.value })
        assertEquals(pageCountBefore, assertNotNull(devices.a.document(id)).livePages.size)
    }

    /**
     * ⚑ Row 10, durable half — **a peer's ops never enter the local undo stack.**
     *
     * SPEC §4.6: *"an incoming remote op does not enter your undo stack, and undoing does not undo
     * someone else's work."* This used to be a property of the call site alone: `apply` took
     * `undoable: Boolean = true`, so a reconciler that forgot the third argument put a
     * collaborator's strokes on the local stack, `flush()` persisted that to `undo.json`, and the
     * user's next two-finger tap deleted work they had never made — surviving a restart.
     * `NoteSession.merge` is now the only door remote ops come through and it cannot reach
     * `undoStack` at all.
     *
     * Asserted through a **reopened** session, because `undo.json` is where the bug was durable.
     *
     * ⚠️ The other half of row 10 — that a stylus stroke in progress survives a merge landing, and
     * that the camera is not refit — is not JVM-testable: `activeStrokeId` is private to
     * `InkSurfaceView` and the camera lives in a `SurfaceView`. `NoteEditorRemoteOpsTest` covers
     * the editor's side of it against a fake surface; the wet-ink half needs the tablet.
     */
    @Test
    fun `row 10 - undo after a pull removes this devices stroke and not the peers`() = matrixRow(
        row = "row 10 (remote ops and undo)",
        proven = "undo.json holding only this device's gestures after a real pull",
        notProven = "that an in-progress stylus stroke survives the merge, and that the camera is not refit",
    ) { devices ->
        val id = devices.share("Row10")
        val page = devices.b.firstPage(id)

        val mine = devices.b.drawStroke(id)
        val theirs = devices.a.drawStroke(id)
        devices.a.drain()
        assertEquals(1, devices.b.pull().merged, "B did not merge A's stroke")

        // Reopened: this is the session a restart would build, and `undo.json` is what it reads.
        val reopened = devices.b.session(id)
        // Per page since the wrong-page-undo fix: an entry's inverse is only meaningful on the page
        // it was computed against, so the question "is there anything to undo" only has an answer
        // relative to a page. This also proves the page survived the `undo.json` round trip — an
        // entry whose page could not be read is dropped on load, so a lost `p` field would read here
        // as "B lost its own undo history".
        assertTrue(reopened.canUndo(page), "B lost its own undo history across the merge")
        assertFalse(reopened.canRedo(page), "something was pushed onto the redo stack by a merge")
        assertTrue(reopened.undo(page), "undo did nothing")

        val remaining = reopened.page(page).objects.map { it.id }
        assertTrue(theirs in remaining, "undo deleted the peer's stroke — SPEC 4.6 forbids exactly this")
        assertFalse(mine in remaining, "undo did not remove B's own stroke")
    }

    // -----------------------------------------------------------------------

    /**
     * One stroke big enough that a few dozen of them pass SPEC §4.7's byte threshold.
     *
     * The threshold is in bytes and not op count on purpose (SPEC §4.7), so the honest way to reach
     * it is with real bytes rather than with thousands of tiny ops.
     */
    private fun bulkStroke(device: Device, session: dev.inkstone.core.storage.NoteSession): PageOp.AddObject =
        PageOp.AddObject(
            opId = session.nextOpId(),
            clock = session.nextClock(),
            obj = StrokeObject(
                id = device.ids.newObjectId(),
                geometry = StrokeGeometry(Random.nextBytes(BULK_GEOMETRY_BYTES)),
            ),
        )

    private companion object {
        /** 40 x 72 KiB is comfortably past `Compaction.JOURNAL_THRESHOLD_BYTES` (2 MiB). */
        const val BULK_STROKES = 40
        const val BULK_GEOMETRY_BYTES = 72 * 1024

        /** Just past `Compaction.STABILITY_HORIZON_MILLIS`. Driven, never shortened. */
        const val THIRTY_ONE_DAYS = 31L * 24 * 60 * 60 * 1000
    }
}
