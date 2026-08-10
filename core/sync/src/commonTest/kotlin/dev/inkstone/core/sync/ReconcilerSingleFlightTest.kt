package dev.inkstone.core.sync

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.storage.NoteLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⭐ **Two reconcile passes must never overlap.**
 *
 * Nothing outside [Reconciler] can arrange that. Three unique WorkManager names run the same
 * whole-account pass — the foreground ticker's, the fifteen-minute periodic one, and one per note a
 * 412 refused — and `ExistingWorkPolicy` deduplicates only *within* a name, so `K` notes 412ing in
 * one drain enqueue `K` jobs that WorkManager is free to run four at a time.
 *
 * What overlapping passes share is the staging directory, and **every way that sharing goes wrong
 * surfaces as a conflict rather than as an error**:
 *
 * - `download/<id>.pdf` is one fixed path and `writeBytesAtomically` names its temp file after the
 *   destination, so two GETs of the same note interleave into one temp file and one of them renames
 *   a torn PDF into place (CLAUDE.md rule 10). A torn PDF routes `changed` into layer 3 — a
 *   conflicted copy minted from a race, with both versions identical.
 * - `discard` deletes the staged bundle and the `pendingDownload` row. Doing that while the other
 *   pass is between `extract` and `gate.merge` makes its bundle unreadable, which is
 *   `DifferentDocument`, which is layer 3 again.
 * - A directory cache row means *"fully reconciled at this version"*. One pass writing it while the
 *   other holds an unmerged download in that directory prunes the subtree on every later pass, and
 *   the note stops syncing while the poll reports success.
 *
 * So these tests park a pass **mid-merge** — staged bundle on disk, `pendingDownload` row written,
 * no cache row yet — because that is the state a second pass corrupts, and it is not reachable any
 * other way.
 */
class ReconcilerSingleFlightTest {

    private val lecture = DocumentId("note-lecture")

    private fun tree() = FakeServer()
        .directory("Inkstone", etag = "r2", ocId = "oc-root")
        .file("Inkstone/Lecture.pdf", etag = "f2", ocId = "oc-42")

    /**
     * A harness whose first merge parks until the test lets it go.
     *
     * The second and later merges do not park — [CompletableDeferred.complete] answers false once
     * it is already complete — so a pass that wrongly gets in runs to completion and does its
     * damage, which is what makes the assertions below fail before the fix rather than hang.
     */
    private fun parking(reached: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) = ReconcileHarness(
        tree(),
        verdict = { normal(lecture) },
        onMerge = { if (reached.complete(Unit)) release.await() },
    )

    @Test
    fun `a pass that starts while one is in flight makes no request at all`() = runTest {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val h = parking(reached, release)
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))

        val first = async { h.reconciler.reconcile() }
        reached.await()
        val requestsWhileParked = h.recorded.all.size

        val second = h.reconciler.reconcile()

        assertTrue(second.busy, "a second pass ran alongside the first")
        assertEquals(0, second.accountsPolled, "a turned-away pass must not even read the accounts")
        assertEquals(
            requestsWhileParked,
            h.recorded.all.size,
            "the second pass sent requests; K notes 412ing would mean K concurrent walks",
        )
        // And it is not a lost signal either: the caller is told to come back, which for the pass a
        // 412 asked for is the whole point of asking.
        assertTrue(second.shouldRunAgain, "a busy pass that reports nothing owed drops the 412's request")

        release.complete(Unit)
        val report = first.await()
        assertEquals(1, report.merged, "the pass that did get in did not finish")
        assertFalse(report.busy)
    }

    @Test
    fun `a pass in flight keeps its staged download and its resume row`() = runTest {
        // The damage, rather than the mechanism. Pass A is parked in the merge holding both halves
        // of SPEC §13.3's resumable state; pass B — if it runs — fetches the same note to the same
        // fixed path, merges, and then `discard`s the file and the row out from under A. A's merge
        // then reads a bundle that is gone: `DifferentDocument`, layer 3, a conflicted copy and a
        // note flagged CONFLICTED, all from a race between two passes with identical bytes.
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val h = parking(reached, release)
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))

        val first = async { h.reconciler.reconcile() }
        reached.await()
        assertNotNull(h.downloads.find(lecture), "the pass did not park where this test needs it to")

        h.reconciler.reconcile()

        assertTrue(
            h.fs.exists(NoteLayout.download(lecture)),
            "another pass deleted the staged download of the pass still using it",
        )
        assertNotNull(h.downloads.find(lecture), "another pass removed the resume row of the pass still using it")
        assertEquals(1, h.gate.merges.size, "the note was merged twice in one pair of passes")

        release.complete(Unit)
        first.await()
        // And the ordinary end of the pass still cleans up, so the guard is not leaking staging files.
        assertFalse(h.fs.exists(NoteLayout.download(lecture)), "the finished pass left its staged download behind")
    }

    @Test
    fun `the guard is released when a pass throws`() = runTest {
        // One held lock is every later pass returning `busy` for the life of the process — sync
        // stopping while the poll reports success, which is the failure mode this whole class is
        // about, reintroduced by the fix for it. The gate is the collaborator most able to throw:
        // it opens a note, folds a peer's log into it and rewrites a bundle.
        // Once, so that the pass after it is a *healthy* one and `busy` is the only thing that can
        // still be wrong about it. The binding's ETag never advanced, so that pass sees the same work.
        var failed = false
        val h = ReconcileHarness(
            tree(),
            verdict = { normal(lecture) },
            onMerge = {
                if (!failed) {
                    failed = true
                    error("a full disk inside the merge")
                }
            },
        )
        h.bind(lecture, "Inkstone/Lecture.pdf", "oc-42", ETag("f1", weak = false))

        val thrown = runCatching { h.reconciler.reconcile() }
        assertTrue(thrown.isFailure, "this test needs the pass to fail, and it did not")

        val next = h.reconciler.reconcile()

        assertFalse(next.busy, "a thrown pass left the single-flight held; nothing will ever reconcile again")
        assertEquals(1, next.merged, "the pass after the failure did not get to do the work")
    }
}
