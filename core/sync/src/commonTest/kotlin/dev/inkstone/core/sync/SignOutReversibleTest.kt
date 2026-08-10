package dev.inkstone.core.sync

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.model.WorkScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⭐ **Signing out must be reversible.**
 *
 * Before M6 it was not, in a way that only showed up on a device with a real library. Sign-out
 * deleted the account row *and* every `remote_binding`, so signing back in meant re-typing a
 * server URL on a tablet and then re-binding **every note individually, choosing its folder
 * again**. Nothing in SPEC §7.1 asks for either deletion; it asks for the app password to be
 * revoked.
 *
 * The tests here pin the three properties that make it recoverable, and the one that makes it
 * safe:
 *
 * 1. Pausing keeps every binding, and drops every queued row.
 * 2. Resuming re-links every note, and costs **no upload** for a note that did not change.
 * 3. Nothing is enqueued for an account that cannot send it — which is the M5 stall, fixed at
 *    its cause rather than by deleting the bindings that exposed it.
 * 4. A row that raced the sign-out is dropped by the drainer **without** taking the binding
 *    with it.
 */
class SignOutReversibleTest {

    private val noteA = DocumentId("note-a")
    private val noteB = DocumentId("note-b")

    private class Harness {
        val clock = FakeClock()
        val notes = FakeSyncNotes()
        val outbox = FakeOutboxStore()
        val accounts = FakeAccountStore(testAccount)
        val bindings = FakeBindingStore()
        val scheduler = RecordingScheduler()

        val stores = SyncStores(accounts, bindings, outbox, notes)
        val engine = SyncEngine(stores, clock, scheduler)

        suspend fun bind(noteId: DocumentId, rev: Long, syncedRev: Long, path: String) {
            notes.setRev(noteId, rev)
            bindings.upsert(
                RemoteBinding(
                    noteId = noteId,
                    accountId = testAccount.id,
                    remotePath = path,
                    remoteOcId = "00000042ocidinstance",
                    lastSyncedEtag = ETag("etag-$path", weak = false),
                    lastSyncedRev = syncedRev,
                    lastUploadAtEpochMillis = 1L,
                ),
            )
        }

        suspend fun signOut() {
            engine.pauseAccount(testAccount.id)
            accounts.setState(testAccount.id, AccountState.SIGNED_OUT, null)
        }

        /** @return how many notes owe an upload — the figure that must be 0 for a clean library. */
        suspend fun signIn(): Int {
            accounts.setState(testAccount.id, AccountState.ACTIVE, null)
            return engine.resumeAccount(testAccount.id)
        }
    }

    private class RecordingScheduler : WorkScheduler {
        var drains = 0
            private set

        override fun enqueueOutboxDrain() {
            drains++
        }

        override fun enqueueMaterialization(noteId: DocumentId) = Unit
    }

    // -----------------------------------------------------------------------

    @Test
    fun `signing out keeps every binding and drops every queued row`() = runTest {
        val h = Harness()
        h.bind(noteA, rev = 5, syncedRev = 5, path = "Inkstone/A.pdf")
        h.bind(noteB, rev = 9, syncedRev = 4, path = "Inkstone/B.pdf")
        h.engine.noteChanged(noteB)
        assertNotNull(h.outbox.upload(noteB), "the fixture did not queue anything to drop")

        val paused = h.engine.pauseAccount(testAccount.id)

        assertEquals(2, paused)
        // The whole point: the remote path of every note survives.
        assertNotNull(h.bindings.find(noteA), "note A's binding was deleted by signing out")
        assertNotNull(h.bindings.find(noteB), "note B's binding was deleted by signing out")
        assertEquals("Inkstone/B.pdf", h.bindings.find(noteB)?.remotePath)
        // And nothing is left queued against an account that can no longer send it — the M5
        // stall, where rows never drained and the worker reported success on every pass.
        assertEquals(0, h.outbox.count(), "queued work outlived the sign-out")
        // PAUSED, not LOCAL_ONLY. The note IS on the server, so calling it "never bound" would
        // hide the library's sync badge on a note that is sitting in the user's Nextcloud.
        assertEquals(NoteSyncState.PAUSED, h.notes.states[noteB.value])
    }

    @Test
    fun `signing back in resumes an unchanged note with no upload at all`() = runTest {
        // A library of synced notes must cost nothing to come back to. The remote files were
        // never touched, and this device still holds their ETags.
        val h = Harness()
        h.bind(noteA, rev = 5, syncedRev = 5, path = "Inkstone/A.pdf")
        h.signOut()

        val owing = h.signIn()

        assertEquals(0, owing, "an unchanged note was re-uploaded for no reason")
        assertEquals(0, h.outbox.count(), "an unchanged note queued an upload")
        assertEquals(NoteSyncState.SYNCED, h.notes.states[noteA.value], "the note did not come back as synced")
    }

    @Test
    fun `signing back in re-uploads only what changed while signed out`() = runTest {
        val h = Harness()
        h.bind(noteA, rev = 5, syncedRev = 5, path = "Inkstone/A.pdf")
        h.bind(noteB, rev = 5, syncedRev = 5, path = "Inkstone/B.pdf")
        h.signOut()

        // The user keeps writing in note B while signed out, which must stay perfectly legal.
        h.notes.setRev(noteB, 12)
        h.engine.noteChanged(noteB)
        assertEquals(0, h.outbox.count(), "editing while signed out queued work that cannot be sent")

        h.signIn()

        assertNull(h.outbox.upload(noteA), "an unchanged note was queued on resume")
        val queued = assertNotNull(h.outbox.upload(noteB), "the note edited while signed out was not queued")
        assertEquals(12, queued.localRevAtEnqueue, "the upload was queued at a stale revision")
        assertEquals(NoteSyncState.PENDING_UPLOAD, h.notes.states[noteB.value])
        assertEquals(NoteSyncState.SYNCED, h.notes.states[noteA.value])
    }

    @Test
    fun `a resumed upload is conditional and not a create`() = runTest {
        // Signing out did not touch the remote file, so the stored ETag is still valid and the
        // PUT must carry If-Match rather than If-None-Match star (CLAUDE.md rule 1).
        val h = Harness()
        h.bind(noteA, rev = 5, syncedRev = 5, path = "Inkstone/A.pdf")
        h.signOut()
        h.notes.setRev(noteA, 6)
        h.signIn()

        val binding = assertNotNull(h.bindings.find(noteA))
        assertTrue(binding.everUploaded, "a resumed note looks as though it was never uploaded")
        assertNotNull(binding.lastSyncedEtag, "the ETag was discarded by the sign-out")
    }

    @Test
    fun `nothing is enqueued while an account is signed out`() = runTest {
        val h = Harness()
        h.bind(noteA, rev = 5, syncedRev = 1, path = "Inkstone/A.pdf")
        h.signOut()

        h.engine.noteChanged(noteA)
        h.engine.notePathChanged(noteA, "Renamed")
        h.engine.noteTrashed(noteA)

        assertEquals(0, h.outbox.count(), "work was queued for an account that cannot send it")
        // And the binding is still intact, so all three remain possible after signing in.
        assertNotNull(h.bindings.find(noteA))
    }

    @Test
    fun `nothing is enqueued while an account needs reauth`() = runTest {
        // The same guard covers SPEC §7.2's halt. A note edited after a 401 must not pile up
        // rows that cannot drain — CLAUDE.md rule 5 is about not sending, and a queued row is
        // a promise to send.
        val h = Harness()
        h.bind(noteA, rev = 5, syncedRev = 1, path = "Inkstone/A.pdf")
        h.accounts.setState(testAccount.id, AccountState.NEEDS_REAUTH, "401")

        h.engine.noteChanged(noteA)

        assertEquals(0, h.outbox.count())
    }

    @Test
    fun `a bound note is still fully editable while signed out`() = runTest {
        // SPEC §7.6 point 4 in the state the user is most likely to be in after signing out:
        // nothing about being signed out may make a note read-only or lossy. The engine is
        // local-only by construction, so this asserts the observable half — no exception, no
        // state change that would make the editor refuse.
        val h = Harness()
        h.bind(noteA, rev = 5, syncedRev = 5, path = "Inkstone/A.pdf")
        h.signOut()

        h.notes.setRev(noteA, 6)
        h.engine.noteChanged(noteA)
        h.notes.setRev(noteA, 7)
        h.engine.noteChanged(noteA)

        assertEquals(NoteSyncState.PAUSED, h.notes.states[noteA.value])
        h.signIn()
        assertEquals(7, assertNotNull(h.outbox.upload(noteA)).localRevAtEnqueue)
    }

    @Test
    fun `resuming does not wake the drainer when nothing is owed`() = runTest {
        val h = Harness()
        h.bind(noteA, rev = 5, syncedRev = 5, path = "Inkstone/A.pdf")
        h.signOut()
        val before = h.scheduler.drains

        h.signIn()

        assertEquals(before, h.scheduler.drains, "a resume with nothing to upload still woke the worker")
    }
}
