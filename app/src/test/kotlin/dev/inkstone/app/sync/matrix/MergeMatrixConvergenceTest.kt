package dev.inkstone.app.sync.matrix

import dev.inkstone.core.sync.AttentionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⭐ **The row SPEC §13.2 does not list and the design demands: the ETag ping-pong terminates.**
 *
 * SPEC §7.6's outbox decision table is not tidiness — it is a termination argument, and without a
 * test the failure is invisible until a user's server is being hammered:
 *
 * | `localNovel` | `remoteNovel` | what happens |
 * |---|---|---|
 * | no | no | the ETag advances, `SYNCED`. **No UPLOAD.** |
 * | no | yes | the ETag *and* the revision advance, `SYNCED`. **No UPLOAD.** |
 * | yes | — | the ETag advances **first**, then one UPLOAD carrying `If-Match:` that ETag. |
 *
 * The obvious wiring — reporting a merge through the library's ordinary save path, which ends in
 * `SyncEngine.noteChanged` — uploads unconditionally. That changes the remote ETag, so the peer sees
 * a remote change on its next poll, merges nothing novel, and uploads back. Forever, at the poll
 * interval, looking exactly like healthy sync: no error, no conflict, both notes correct.
 *
 * So this asserts two things a per-row test cannot: that a two-device merge **settles**, in a
 * bounded number of rounds, and that once settled a poll costs exactly one `PROPFIND Depth:0` per
 * device and nothing else — no GET, no PUT, and no further ETag movement.
 *
 * ⭐ **And the same claim for the case that never settles.** A conflict layer 3 could not merge is
 * re-met on every poll *by design* — the ETag is deliberately not advanced, so the next pass can find
 * out whether the condition cleared — and "recurs forever" has to mean "costs nothing extra forever".
 * It did not: each re-encounter minted another `… (conflicted copy …).pdf`, and each one re-opened the
 * row the user had dismissed. Both are unbounded growth from one unresolved condition, and neither is
 * visible in a test that polls once.
 */
class MergeMatrixConvergenceTest {

    @Test
    fun `after a two-device merge five more polls each cost one request and change nothing`() = matrixRow(
        row = "convergence (ETag ping-pong termination)",
        proven = "a bounded settle and five idle polls that cost one PROPFIND each with no ETag movement",
        notProven = "nothing extra — the loop this rules out is entirely in the decision table",
    ) { devices ->
        val id = devices.share("Converge")
        val page = devices.a.firstPage(id)

        // A real two-way merge: both devices have something the other does not.
        val fromA = devices.a.drawStroke(id)
        val fromB = devices.b.drawStroke(id)

        // Poll-and-drain both devices until neither has anything to say. The bound is the claim.
        var rounds = 0
        while (rounds < ROUND_LIMIT) {
            val onA = devices.a.pull()
            devices.a.drain()
            val onB = devices.b.pull()
            devices.b.drain()
            rounds++
            if (onA.unchanged == 1 && onB.unchanged == 1) break
        }
        assertTrue(
            rounds < ROUND_LIMIT,
            "sync never settled in $ROUND_LIMIT rounds — the two devices are uploading each other's merges",
        )
        assertTrue(rounds <= SETTLE_ROUNDS, "settling took $rounds rounds, which is more than two round trips per side")

        // Both devices agree, and both have both strokes.
        for (device in listOf(devices.a, devices.b)) {
            val objects = device.page(id, page).objects.map { it.id }
            assertTrue(fromA in objects, "${device.name} lost A's stroke")
            assertTrue(fromB in objects, "${device.name} lost B's stroke")
        }
        val etagA = assertNotNull(devices.a.binding(id)).lastSyncedEtag
        val etagB = assertNotNull(devices.b.binding(id)).lastSyncedEtag
        assertEquals(etagA, etagB, "the two devices disagree about which version is on the server")

        // ⭐ Now the measurement. Five polls each, and every one of them must be the cheap case.
        devices.resetCounts()
        repeat(IDLE_POLLS) {
            devices.a.pull()
            devices.b.pull()
        }

        devices.assertRequests("PROPFIND", IDLE_POLLS * 2, "an idle poll cost more than one PROPFIND Depth:0")
        devices.assertRequests("GET", 0, "an idle poll downloaded a file")
        devices.assertRequests("PUT", 0, "an idle poll uploaded — this is the echo loop")
        assertEquals(etagA, assertNotNull(devices.a.binding(id)).lastSyncedEtag, "A's ETag moved while nothing changed")
        assertEquals(etagB, assertNotNull(devices.b.binding(id)).lastSyncedEtag, "B's ETag moved while nothing changed")
        assertTrue(devices.a.outboxRows().isEmpty(), "A still owes the server something after settling")
        assertTrue(devices.b.outboxRows().isEmpty(), "B still owes the server something after settling")
    }

    /**
     * ⭐ **A conflict layer 3 cannot resolve costs one copy and one row, however long it lasts.**
     *
     * Both halves are reached the same way and neither needs anything unusual to happen. Layer 3 does
     * not advance the file's ETag, and a directory's ETag moves every time *any* note in it uploads —
     * so a folder with a second note in it re-exposes the unmergeable one to the walk on every poll,
     * which is the neighbour note below and the reason it is there.
     *
     * - `conflictedCopyOf` mints a new `DocumentId` every time it is asked (it must — a copy keeping
     *   the id would be merged back into the note it could not merge with), so an unkeyed layer 3
     *   produced one local note **and one bound remote file** per poll.
     * - The dedup guard read the *open* rows, and an acknowledged row is not among them, so the UPSERT
     *   put the dismissal back to 0. The user could not put the row away for longer than one poll.
     *
     * The two fixes hold each other up, which is what the last assertion is about: the record of
     * *"this conflict already has a copy"* is the attention row itself, so acknowledging has to stay a
     * tombstone (CLAUDE.md rule 1) for a second and quite separate reason.
     */
    @Test
    fun `an unmergeable file met on four polls leaves one copy and one dismissed row`() = matrixRow(
        row = "convergence (layer 3 is bounded)",
        proven = "one conflicted copy per conflict rather than per poll, and a dismissal that sticks",
        notProven = "nothing extra — both bounds are local decisions about a file left untouched",
    ) { devices ->
        val id = devices.share("Bounded")
        val path = devices.a.remotePath(id)

        // A second note in the same folder. Its uploads are what move the folder's ETag, and without
        // that A's later polls prune the subtree by SPEC §7.4 rule 1 and never look at the file again.
        val neighbour = devices.b.createNote("Neighbour")
        devices.b.bind(neighbour, "Neighbour")
        devices.b.drain()

        // The impossible merge, made as row 13b makes it: another note's published bytes at this
        // note's path, so the marker inside names a document this is not.
        val stranger = devices.b.createNote("Stranger")
        devices.b.publisher.materialize(stranger)
        devices.rawWrite(path, devices.b.publishedBytes(stranger))

        val first = devices.a.pull()
        assertEquals(1, first.conflicted, "the file naming a different note was not treated as a conflict")
        val entry = assertNotNull(
            devices.a.attention().singleOrNull { it.kindOrNull == AttentionKind.CONFLICTED_COPY },
            "layer 3 left ${devices.a.attention().size} rows and not one conflicted-copy row",
        )
        val copyId = assertNotNull(entry.conflictedCopyNoteId, "the row does not name a copy to open")
        val notesAfterFirst = devices.a.index.allNotes().size

        // The user reads it and puts it away — `AttentionViewModel.acknowledge`, which is a timestamp
        // on the row and never a delete.
        devices.a.attentionLog.acknowledge(entry.id, devices.a.clock.nowEpochMillis())

        val later = List(POLLS_AFTER_DISMISSAL) {
            devices.b.drawStroke(neighbour)
            devices.b.drain()
            devices.a.pull()
        }

        assertTrue(later.all { it.conflicted == 1 }, "the later polls never met the file again: $later")
        assertTrue(
            devices.a.attention().none { it.kindOrNull == AttentionKind.CONFLICTED_COPY },
            "the dismissed row came back — at a sixty-second poll floor, twenty-four times an hour",
        )
        assertEquals(notesAfterFirst, devices.a.index.allNotes().size, "layer 3 minted a copy per poll")
        assertEquals(
            copyId,
            assertNotNull(devices.a.attentionLog.find(entry.id), "the tombstone is gone").conflictedCopyNoteId,
            "and the tombstone is what remembers the copy, so it has to still name the same one",
        )
    }

    private companion object {
        /**
         * SPEC §7.6's bound: both devices upload once after a real merge, the loser's PUT 412s, the
         * 412 asks for a reconcile of that note, and that pass finds `localNovel = false` and stops.
         * Two round trips per side, so three rounds of poll-and-drain is generous.
         */
        const val SETTLE_ROUNDS = 3

        /** Where the assertion gives up rather than looping forever on a genuine echo. */
        const val ROUND_LIMIT = 12

        const val IDLE_POLLS = 5

        /** Enough to tell "once" from "per poll"; the unbounded version grows by one each time. */
        const val POLLS_AFTER_DISMISSAL = 3
    }
}
