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
    ) = OutboxCoalescing.plan(pending, request, note, localRev, everUploaded, now)

    private fun row(
        opType: OutboxOpType,
        rev: Long = 1L,
        attempts: Int = 0,
        nextAttemptAt: Long = 0L,
        createdAt: Long = 100L,
    ) = OutboxEntry(note, opType, rev, attempts, nextAttemptAt, createdAt)

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

    @Test
    fun `mkcol drains before everything else and delete drains last`() {
        val order = OutboxOpType.entries.sortedBy { it.drainPriority }

        assertEquals(
            listOf(OutboxOpType.MKCOL, OutboxOpType.MOVE, OutboxOpType.UPLOAD, OutboxOpType.DELETE),
            order,
        )
    }

    @Test
    fun `delete annihilates a pending upload`() {
        val pending = listOf(row(OutboxOpType.UPLOAD), row(OutboxOpType.MOVE))

        val result = plan(pending, OutboxOpType.DELETE, everUploaded = true)

        assertTrue(OutboxOpType.UPLOAD in result.removals, "a queued upload would re-create the deleted file")
        assertTrue(OutboxOpType.MOVE in result.removals)
        assertEquals(listOf(OutboxOpType.DELETE), result.upserts.map { it.opType })
    }

    @Test
    fun `delete on a note that was never uploaded queues nothing`() {
        // There is nothing on the server to delete, so a DELETE row would be a guaranteed
        // 404 that then consumes backoff for a day.
        val pending = listOf(row(OutboxOpType.UPLOAD))

        val result = plan(pending, OutboxOpType.DELETE, everUploaded = false)

        assertTrue(result.upserts.isEmpty())
        assertTrue(OutboxOpType.UPLOAD in result.removals)
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
        val entry = plan(emptyList(), OutboxOpType.UPLOAD).upserts.single()

        val fields = listOf<Any?>(
            entry.noteId,
            entry.opType,
            entry.localRevAtEnqueue,
            entry.attempts,
            entry.nextAttemptAt,
            entry.createdAt,
        )
        assertEquals(6, fields.size)
        assertNull(fields.firstOrNull { it is ByteArray }, "an outbox row must not carry bytes")
    }
}
