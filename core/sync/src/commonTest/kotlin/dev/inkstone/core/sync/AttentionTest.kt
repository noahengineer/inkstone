package dev.inkstone.core.sync

import dev.inkstone.core.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC §7.7's *"resolve automatically, surface in a list afterwards"* — the mapping, as a table.
 *
 * The point of [Attention.entryFor] being pure is that this policy can be read in one place. A
 * policy expressed as fifteen `attention.add(...)` calls scattered along a walk is a policy nobody
 * can check, and the thing being checked here is what the *user* is told — which is the part of
 * CLAUDE.md rule 6 that a test can hold on to.
 */
class AttentionTest {

    private val note = DocumentId("note-1")
    private val copy = DocumentId("note-1-copy")

    private fun entryFor(outcome: ReconcileOutcome) = Attention.entryFor(outcome, "acct-1", nowEpochMillis = 500L)

    // -----------------------------------------------------------------------
    // Layer 1 — the 95 % case, and the user hears nothing
    // -----------------------------------------------------------------------

    @Test
    fun `an ordinary merge says nothing to the user at all`() {
        // SPEC §7.7 layer 1: "Two people writing in different corners of a page both keep their
        // work, automatically, no prompt." Not a badge, not a notification, not a row.
        assertNull(entryFor(ReconcileOutcome.Merged(note, remoteNovelOps = 40, overriddenTitle = null)))
        assertNull(entryFor(ReconcileOutcome.Unchanged))
        assertNull(entryFor(ReconcileOutcome.Moved(note, "a/x.pdf", "b/x.pdf")))
        assertNull(entryFor(ReconcileOutcome.Adopted(note, "a/x.pdf")))
        assertNull(entryFor(ReconcileOutcome.Ignored("a/stranger.pdf", "no marker")))
        assertNull(entryFor(ReconcileOutcome.Deferred(note, "a/x.pdf", "not merged yet")))
    }

    @Test
    fun `a transient failure is not put in a list the user comes back to`() {
        // A list that fills with rows which fix themselves is a list nobody reads. These three are
        // all "try again in a minute", and the next pass does.
        assertNull(entryFor(ReconcileOutcome.Failed(note, "a/x.pdf", DavFailure.Transport("offline"))))
        assertNull(entryFor(ReconcileOutcome.Failed(note, "a/x.pdf", DavFailure.Locked("Ada"))))
        assertNull(entryFor(ReconcileOutcome.Failed(note, "a/x.pdf", DavFailure.Server(503, null))))
        assertNull(entryFor(ReconcileOutcome.Failed(note, "a/x.pdf", DavFailure.RateLimited(30))))
    }

    // -----------------------------------------------------------------------
    // Layer 2 — only when a string somebody typed was actually discarded
    // -----------------------------------------------------------------------

    @Test
    fun `a title override records the string the user typed and lost`() {
        // SPEC §7.7 layer 2 requires the override be "recorded in history", and the only string
        // worth recording is the one the user no longer has. Dropping it is the single place layer
        // 2 could quietly discard something somebody typed.
        val entry = Attention.entryFor(
            ReconcileOutcome.Merged(note, remoteNovelOps = 1, overriddenTitle = "Thermodynamics wk 4"),
            "acct-1",
            500L,
        )

        assertEquals(AttentionKind.TITLE_OVERRIDDEN, entry?.kindOrNull)
        assertEquals("Thermodynamics wk 4", entry?.detail)
        assertEquals(note, entry?.noteId)
        assertEquals(true, entry?.isOpen)
    }

    // -----------------------------------------------------------------------
    // Layer 3
    // -----------------------------------------------------------------------

    @Test
    fun `every layer three trigger becomes its own kind`() {
        assertEquals(
            AttentionKind.REMOTE_TOO_NEW,
            entryFor(ReconcileOutcome.TooNew(note, "a/x.pdf", remoteSchemaVersion = 3))?.kindOrNull,
        )
        assertEquals(
            AttentionKind.REMOTE_NOT_RECOVERABLE,
            entryFor(ReconcileOutcome.NotRecoverable(note, "a/x.pdf"))?.kindOrNull,
        )
        assertEquals(
            AttentionKind.CONFLICTED_COPY,
            entryFor(ReconcileOutcome.Unmergeable(note, "a/x.pdf", "unreadable", copy, "oc-9"))?.kindOrNull,
        )
        assertEquals(
            AttentionKind.REMOTE_DELETED,
            entryFor(ReconcileOutcome.DeletedRemotely(note, "a/x.pdf"))?.kindOrNull,
        )
    }

    @Test
    fun `a too new entry carries the schema version rather than a bare complaint`() {
        // The difference between an unactionable "written by a newer build" and "written against
        // bundle schema 3; this build reads 1" is this number.
        assertEquals("3", entryFor(ReconcileOutcome.TooNew(note, "a/x.pdf", 3))?.detail)
    }

    @Test
    fun `a conflicted copy carries the note the user can open`() {
        // Without it the copy is a mystery file the user deletes.
        val entry = entryFor(ReconcileOutcome.Unmergeable(note, "Inkstone/x.pdf", "unreadable", copy, "oc-9"))

        assertEquals(copy, entry?.conflictedCopyNoteId)
        assertEquals("Inkstone/x.pdf", entry?.remotePath)
    }

    // -----------------------------------------------------------------------
    // Dedup — the id is derived from the problem, never minted
    // -----------------------------------------------------------------------

    @Test
    fun `rediscovering one problem produces one id`() {
        // [AttentionLog.add] is an UPSERT on the id, and a poll at SPEC §7.6's sixty-second floor
        // rediscovers a parked conflict 1 440 times a day. A UUIDv7 here would be 1 440 rows.
        val first = entryFor(ReconcileOutcome.TooNew(note, "a/x.pdf", 3))
        val second = Attention.entryFor(ReconcileOutcome.TooNew(note, "a/x.pdf", 3), "acct-1", 90_000L)

        assertEquals(first?.id, second?.id)
    }

    @Test
    fun `the same remote file conflicting twice is one row whatever copy it names`() {
        // ⭐ The id is keyed on the *conflict* — this note against this remote file — and not on the
        // copy. Keyed on the copy it changed every time a copy was made, which is the opposite of a
        // dedup handle: the reconciler asks this row whether a copy already exists before it makes
        // one, so a moving id meant a new copy on every poll for as long as the condition lasted.
        val first = entryFor(ReconcileOutcome.Unmergeable(note, "a/x.pdf", "r", DocumentId("copy-1"), "oc-9"))
        val second = entryFor(ReconcileOutcome.Unmergeable(note, "a/x.pdf", "r", DocumentId("copy-2"), "oc-9"))

        assertEquals(first?.id, second?.id)
        assertEquals(Attention.conflictId("acct-1", note, "oc-9"), first?.id)
    }

    @Test
    fun `a renamed remote file is the same conflict and a different file is not`() {
        // Identity is the `oc:id`, never the path (SPEC §7.4 rule 2): a peer renaming the file they
        // could not merge is not a second thing to keep. Two genuinely different files are.
        val renamed = entryFor(ReconcileOutcome.Unmergeable(note, "a/renamed.pdf", "r", copy, "oc-9"))
        val other = entryFor(ReconcileOutcome.Unmergeable(note, "a/x.pdf", "r", copy, "oc-10"))

        assertEquals(Attention.conflictId("acct-1", note, "oc-9"), renamed?.id)
        assertNotEquals(renamed?.id, other?.id)
    }

    @Test
    fun `two different kinds on one note are two rows`() {
        // The whole reason this is a table and not a state on the note: a note can need attention
        // for two reasons at once, and `note.syncState` is one value.
        val overridden = Attention.entryFor(
            ReconcileOutcome.Merged(note, 1, overriddenTitle = "old"),
            "acct-1",
            500L,
        )
        val deleted = entryFor(ReconcileOutcome.DeletedRemotely(note, "a/x.pdf"))

        assertNotEquals(overridden?.id, deleted?.id)
    }

    // -----------------------------------------------------------------------
    // Account-level rows
    // -----------------------------------------------------------------------

    @Test
    fun `a reauth row is about the account and not the file that happened to hit it`() {
        val entry = entryFor(ReconcileOutcome.Failed(note, "Inkstone/x.pdf", DavFailure.Unauthorized))

        assertEquals(AttentionKind.NEEDS_REAUTH, entry?.kindOrNull)
        assertNull(entry?.noteId, "the credential is not a property of one note")
        assertEquals(Attention.idFor(AttentionKind.NEEDS_REAUTH, "acct-1", null), entry?.id)
    }

    @Test
    fun `a quota and a forbidden folder stay attached to the note that hit them`() {
        val quota = entryFor(ReconcileOutcome.Failed(note, "Inkstone/x.pdf", DavFailure.QuotaExceeded))
        val forbidden = entryFor(ReconcileOutcome.Failed(note, "Inkstone/x.pdf", DavFailure.Forbidden("read only")))

        assertEquals(AttentionKind.QUOTA_EXCEEDED, quota?.kindOrNull)
        assertEquals(note, quota?.noteId)
        assertEquals(AttentionKind.FORBIDDEN, forbidden?.kindOrNull)
        assertTrue(forbidden?.detail?.contains("read only") == true)
    }

    // -----------------------------------------------------------------------
    // Storage shape
    // -----------------------------------------------------------------------

    @Test
    fun `an unknown kind from a newer build is kept and shown as itself`() {
        // Dropping it would hide a conflict, which is the one thing this list exists not to do.
        // That is why the column is a String and `kindOrNull` is the typed read rather than a
        // parse that can fail.
        val row = AttentionEntry(id = "x", accountId = "acct-1", kind = "SOMETHING_M9_ADDED", createdAtEpochMillis = 1L)

        assertNull(row.kindOrNull)
        assertEquals("SOMETHING_M9_ADDED", row.kind)
        assertTrue(row.isOpen)
    }

    @Test
    fun `two rows differing only in when they were noticed say the same thing`() {
        // ⭐ The reconciler's dedup comparison, and the reason it is not equality. Every pass stamps a
        // fresh `createdAt` and a rediscovered row arrives with `acknowledgedAt` back at 0, so
        // comparing whole entries would say "new" 1 440 times a day and un-dismiss the row each time.
        val first = entryFor(ReconcileOutcome.TooNew(note, "a/x.pdf", 3))!!
        val later = Attention.entryFor(ReconcileOutcome.TooNew(note, "a/x.pdf", 3), "acct-1", 90_000L)!!

        assertTrue(first.saysTheSameAs(later))
        assertTrue(first.copy(acknowledgedAtEpochMillis = 900L).saysTheSameAs(later), "a dismissal is not news")
    }

    @Test
    fun `a row with something new to say is not the row the user dismissed`() {
        // The other direction: a dismissal covers the problem that was read, not the note. "The peer's
        // schema is now 4" has to reach the list again, and so does a copy the entry did not name
        // before — both are things the screen draws.
        val three = entryFor(ReconcileOutcome.TooNew(note, "a/x.pdf", 3))!!
        val four = entryFor(ReconcileOutcome.TooNew(note, "a/x.pdf", 4))!!

        assertEquals(three.id, four.id, "the same problem, so the same row")
        assertFalse(three.saysTheSameAs(four))
        assertFalse(three.saysTheSameAs(three.copy(conflictedCopyNoteId = copy)))
    }

    @Test
    fun `acknowledging is a tombstone and not a delete`() {
        // CLAUDE.md rule 1 reaches this far: what sync had to do to somebody's notes is worth being
        // able to look up afterwards, so the row stays and only stops being open.
        val row = AttentionEntry("x", "acct-1", AttentionKind.REMOTE_DELETED.name, 1L)

        assertTrue(row.isOpen)
        assertEquals(false, row.copy(acknowledgedAtEpochMillis = 900L).isOpen)
    }
}
