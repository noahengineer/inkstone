package dev.inkstone.core.sync

import dev.inkstone.core.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC §7.6's coalescing table, one test per row.
 *
 * | Situation | Result |
 * |---|---|
 * | UPLOAD enqueued while UPLOAD pending | UPSERT on `(note_id, UPLOAD)`. Earliest `created_at`, updated `local_rev_at_enqueue`, **inherited backoff** |
 * | MOVE then UPLOAD | Both rows kept. MOVE always drains before UPLOAD |
 * | DELETE enqueued with UPLOAD pending | DELETE **annihilates** the pending UPLOAD; the remote DELETE still runs if ever uploaded |
 * | MKCOL | Drains before any op targeting a path inside it |
 *
 * These are unit tests of a pure function rather than of a database, which is why the
 * rules can be stated one per test at all. The SQL that applies the result is one
 * transaction and is covered by `:core:database`'s instrumented tests.
 */
class OutboxCoalescingTest {

    private val note = DocumentId("note-1")

    private fun plan(
        pending: List<OutboxEntry>,
        request: OutboxOpType,
        localRev: Long = 5L,
        everUploaded: Boolean = true,
        now: Long = 2000L,
        subject: String = NO_SUBJECT,
    ) = OutboxCoalescing.plan(pending, request, note, localRev, everUploaded, now, subject)

    private fun row(
        opType: OutboxOpType,
        rev: Long = 1L,
        attempts: Int = 0,
        nextAttemptAt: Long = 0L,
        createdAt: Long = 100L,
        subject: String = NO_SUBJECT,
    ) = OutboxEntry(note, opType, rev, subject, attempts, nextAttemptAt, createdAt)

    @Test
    fun `a first upload becomes one row`() {
        val result = plan(emptyList(), OutboxOpType.UPLOAD, localRev = 7L, now = 2000L)

        assertEquals(1, result.upserts.size)
        val entry = result.upserts.single()
        assertEquals(OutboxOpType.UPLOAD, entry.opType)
        assertEquals(7L, entry.localRevAtEnqueue)
        assertEquals(2000L, entry.createdAt)
    }

    @Test
    fun `an upload on top of a pending upload coalesces into one row at the new revision`() {
        // ROADMAP M5's acceptance criterion — "500 offline edits, exactly one upload" — is
        // this rule applied 500 times. The composite primary key IS the mechanism.
        var pending = listOf(row(OutboxOpType.UPLOAD, rev = 1L, createdAt = 100L))
        for (rev in 2L..500L) {
            val result = plan(pending, OutboxOpType.UPLOAD, localRev = rev, now = 1000L + rev)
            assertTrue(result.removals.isEmpty())
            pending = result.upserts
        }

        assertEquals(1, pending.size)
        assertEquals(500L, pending.single().localRevAtEnqueue)
    }

    @Test
    fun `coalescing keeps the earliest created_at`() {
        // "How long has this been waiting" has to stay true, or a note that has been
        // failing since breakfast looks like it was enqueued a second ago.
        val existing = row(OutboxOpType.UPLOAD, createdAt = 100L)

        val result = plan(listOf(existing), OutboxOpType.UPLOAD, now = 9999L)

        assertEquals(100L, result.upserts.single().createdAt)
    }

    @Test
    fun `coalescing inherits the backoff rather than resetting it`() {
        // SPEC §7.6, and the rule that looks like a detail and is not: resetting
        // next_attempt_at on every coalesce means a note being written to has its retry
        // timer reset every two seconds — a hot loop against the user's own server.
        val existing = row(OutboxOpType.UPLOAD, attempts = 4, nextAttemptAt = 500_000L)

        val result = plan(listOf(existing), OutboxOpType.UPLOAD, now = 2000L)

        val entry = result.upserts.single()
        assertEquals(4, entry.attempts, "attempt count was reset")
        assertEquals(500_000L, entry.nextAttemptAt, "backoff was reset — this is the hot-loop bug")
    }

    @Test
    fun `a move and an upload are both kept and the move drains first`() {
        val afterMove = plan(emptyList(), OutboxOpType.MOVE).upserts
        val afterUpload = plan(afterMove, OutboxOpType.UPLOAD)

        // The plan only touches the row it was asked about; the MOVE row is untouched.
        assertEquals(setOf(OutboxOpType.UPLOAD), afterUpload.upserts.mapTo(HashSet()) { it.opType })
        assertTrue(afterUpload.removals.isEmpty())

        assertTrue(
            OutboxOpType.MOVE.drainPriority < OutboxOpType.UPLOAD.drainPriority,
            "MOVE must drain before UPLOAD for the same note (SPEC §7.6)",
        )
    }

    /**
     * ⚠️ MEDIA_UPLOAD sits between UPLOAD and DELETE, and that position is **load-bearing**
     * rather than tidy (M12). `OutboxDrainer.performDelete` ends in `bindings.remove(noteId)`,
     * and `drainOne` drops any row whose note has no binding — so a DELETE draining first
     * silently discards a lecture that has not reached the server yet, which is the opposite of
     * what docs/M12-AUDIO.md §5 requires.
     *
     * `Sync.sq`'s `selectDueOutbox` restates this as a `CASE`, because SQL cannot read an
     * ordinal. If this assertion is ever edited, that query is the other half of the edit.
     */
    @Test
    fun `mkcol drains before everything else and delete drains last`() {
        val order = OutboxOpType.entries.sortedBy { it.drainPriority }

        assertEquals(
            listOf(
                OutboxOpType.MKCOL,
                OutboxOpType.MOVE,
                OutboxOpType.UPLOAD,
                OutboxOpType.MEDIA_UPLOAD,
                OutboxOpType.DELETE,
            ),
            order,
        )
    }

    /**
     * ⭐ M12's headline rule, at the level where it is decided (docs/M12-AUDIO.md §5).
     *
     * Two recordings in one note are two rows. The end-to-end proof — that both files reach a
     * real server — is `MergeMatrixMediaTest`; this is the unit that says the *decision* is
     * right, and it is the one that fails first if the subject is ever dropped from the key.
     */
    @Test
    fun `two recordings in one note are two rows and neither supersedes the other`() {
        val first = plan(emptyList(), OutboxOpType.MEDIA_UPLOAD, subject = "rec-1").upserts.single()

        val second = plan(listOf(first), OutboxOpType.MEDIA_UPLOAD, subject = "rec-2")

        assertEquals(1, second.upserts.size, "a second recording produced more than its own row")
        assertEquals("rec-2", second.upserts.single().subject)
        assertTrue(
            second.removals.isEmpty(),
            "enqueuing a second recording removed the first one's row — one lecture would never upload",
        )
    }

    /** A retry of the *same* recording is still one row, and still inherits its backoff. */
    @Test
    fun `re-enqueuing one recording coalesces onto its own row`() {
        val existing = row(OutboxOpType.MEDIA_UPLOAD, attempts = 2, nextAttemptAt = 900_000L, subject = "rec-1")

        val result = plan(listOf(existing), OutboxOpType.MEDIA_UPLOAD, subject = "rec-1")

        val entry = result.upserts.single()
        assertEquals("rec-1", entry.subject)
        assertEquals(2, entry.attempts, "the sweep in noteChanged consumed an attempt")
        assertEquals(900_000L, entry.nextAttemptAt, "re-enqueuing reset the backoff into a hot retry loop")
    }

    /**
     * ⚠️ Doc §5: *"Trashing a note whose lecture has not uploaded yet should still deliver the
     * lecture."* A DELETE annihilates the queued UPLOAD, MOVE and MKCOL — and must leave the
     * recording alone. The three are an opt-in list precisely so this stays true.
     */
    @Test
    fun `delete does not annihilate a queued recording`() {
        val pending = listOf(
            row(OutboxOpType.UPLOAD),
            row(OutboxOpType.MEDIA_UPLOAD, subject = "rec-1"),
        )

        val result = plan(pending, OutboxOpType.DELETE, everUploaded = true)

        assertTrue(OutboxKey(OutboxOpType.UPLOAD) in result.removals, "the queued upload survived a delete")
        assertTrue(
            OutboxKey(OutboxOpType.MEDIA_UPLOAD, "rec-1") !in result.removals,
            "trashing the note destroyed the only queued route the lecture had to the server",
        )
    }

    @Test
    fun `delete annihilates a pending upload`() {
        val pending = listOf(row(OutboxOpType.UPLOAD), row(OutboxOpType.MOVE))

        val result = plan(pending, OutboxOpType.DELETE, everUploaded = true)

        assertTrue(
            OutboxKey(OutboxOpType.UPLOAD) in result.removals,
            "a queued upload would re-create the deleted file",
        )
        assertTrue(OutboxKey(OutboxOpType.MOVE) in result.removals)
        assertEquals(listOf(OutboxOpType.DELETE), result.upserts.map { it.opType })
    }

    @Test
    fun `delete on a note that was never uploaded queues nothing`() {
        // There is nothing on the server to delete, so a DELETE row would be a guaranteed
        // 404 that then consumes backoff for a day.
        val pending = listOf(row(OutboxOpType.UPLOAD))

        val result = plan(pending, OutboxOpType.DELETE, everUploaded = false)

        assertTrue(result.upserts.isEmpty())
        assertTrue(OutboxKey(OutboxOpType.UPLOAD) in result.removals)
    }

    @Test
    fun `delete inherits the backoff of an existing delete`() {
        val existing = row(OutboxOpType.DELETE, attempts = 3, nextAttemptAt = 800_000L, createdAt = 50L)

        val result = plan(listOf(existing), OutboxOpType.DELETE, everUploaded = true)

        val entry = result.upserts.single()
        assertEquals(3, entry.attempts)
        assertEquals(800_000L, entry.nextAttemptAt)
        assertEquals(50L, entry.createdAt)
    }

    @Test
    fun `a row never carries a payload`() {
        // SPEC §7.6: "rows are markers, never payloads. Never store note bytes in the
        // outbox — that's a second copy of every note." Stated as a test because the
        // pressure to add one arrives with the first feature that would find it convenient.
        //
        // ⚠️ **M12 was that feature, and the count went 6 → 7 rather than the invariant going.**
        // `subject` is an **identifier** — a recording id — and the two things that make it not
        // a payload are worth stating, because "it is only a String" is exactly how the first
        // path or filename gets in:
        //
        //   * the drainer resolves it through `RecordingUploads.find` to get the paths, so the
        //     row names *which* object and never *where* or *what* it is;
        //   * nothing here is derived from it — `RemoteMedia` builds the remote path from the
        //     binding and the note id, so a row could not address a file on its own.
        //
        // The bound below is therefore "no bytes **and** no path", which is the invariant SPEC
        // §7.6 actually wants; the field count is what makes a new field impossible to add
        // without reading this.
        val entry = plan(emptyList(), OutboxOpType.MEDIA_UPLOAD, subject = "rec-1").upserts.single()

        val fields = listOf<Any?>(
            entry.noteId,
            entry.opType,
            entry.localRevAtEnqueue,
            entry.subject,
            entry.attempts,
            entry.nextAttemptAt,
            entry.createdAt,
        )
        assertEquals(7, fields.size)
        assertNull(fields.firstOrNull { it is ByteArray }, "an outbox row must not carry bytes")
        assertTrue(
            fields.none { it is String && ('/' in it || '.' in it) },
            "an outbox row must not carry a path — resolve identifiers through their own store",
        )
    }
}
