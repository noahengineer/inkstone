package dev.inkstone.core.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SPEC §4.7 — and specifically the two things the naive "compact when SYNCED" rule gets wrong. */
class CompactionTest {

    private val now = 1_800_000_000_000L
    private val longAgo = now - Compaction.STABILITY_HORIZON_MILLIS - 1
    private val bigJournal = Compaction.JOURNAL_THRESHOLD_BYTES + 1

    private fun allowed(
        state: NoteSyncState = NoteSyncState.LOCAL_ONLY,
        journalBytes: Long = bigJournal,
        oldestOp: Long = longAgo,
        acked: Long? = null,
        highestFolded: Long = 100,
    ) = Compaction.isAllowed(state, journalBytes, now, oldestOp, acked, highestFolded)

    @Test
    fun `a local-only note is compacted — which the naive rule would never do`() {
        // Most notes are LOCAL_ONLY, and every note is before M5 exists. A rule keyed
        // on SYNCED would mean compaction never runs at all.
        assertTrue(allowed(state = NoteSyncState.LOCAL_ONLY))
        assertTrue(allowed(state = NoteSyncState.SYNCED))
    }

    @Test
    fun `a note with work the server has not seen is not compacted`() {
        assertFalse(allowed(state = NoteSyncState.PENDING_UPLOAD))
        assertFalse(allowed(state = NoteSyncState.CONFLICTED))
        assertFalse(allowed(state = NoteSyncState.NEEDS_REAUTH))
    }

    @Test
    fun `the threshold is bytes — not op count`() {
        assertFalse(allowed(journalBytes = Compaction.JOURNAL_THRESHOLD_BYTES - 1))
        assertTrue(allowed(journalBytes = Compaction.JOURNAL_THRESHOLD_BYTES))
    }

    @Test
    fun `recent ops are not folded away`() {
        assertFalse(allowed(oldestOp = now - 1))
    }

    @Test
    fun `an offline peer that has only acked older ops blocks compaction`() {
        // "SYNCED" means this device uploaded, not that every peer has seen these ops.
        // Compacting here would leave the other tablet's older ops nowhere to land.
        assertFalse(allowed(state = NoteSyncState.SYNCED, acked = 50, highestFolded = 100))
        assertTrue(allowed(state = NoteSyncState.SYNCED, acked = 100, highestFolded = 100))
    }
}
