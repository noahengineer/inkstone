package dev.inkstone.core.sync

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.NoteSyncState
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC §7.6's drainer, and most of ROADMAP M5's acceptance list.
 *
 * Four of these correspond to a criterion the milestone is not done without:
 *
 * - *"500 offline edits → exactly one upload"* — [`five hundred edits produce one upload`]
 * - *"Note edited while its PUT is in flight → not marked SYNCED, re-enqueued"* —
 *   [`a note edited mid-flight is re-enqueued instead of marked SYNCED`]
 * - *"Revoke the app password server-side → re-login prompt, no retry loop"* —
 *   [`a 401 halts the account and sends nothing further`]
 * - *"Delete a bound note → local trash + remote DELETE"* — [`a delete removes the binding`]
 */
class OutboxDrainerTest {

    private val note = DocumentId("note-1")

    private class Harness(
        val clock: FakeClock = FakeClock(),
        val notes: FakeSyncNotes = FakeSyncNotes(),
        val outbox: FakeOutboxStore = FakeOutboxStore(),
        val accounts: FakeAccountStore = FakeAccountStore(testAccount),
        val bindings: FakeBindingStore = FakeBindingStore(),
        val credentials: FakeCredentialStore = FakeCredentialStore(testAccount.id to testCredential),
        val fs: FakeFileSystem = FakeFileSystem(),
        val recorded: RecordedRequests = RecordedRequests(),
        val recordings: FakeRecordingUploads = FakeRecordingUploads(),
    ) {
        val materializer = FakeMaterializer(notes, fs)

        fun drainer(
            handler: io.ktor.client.engine.mock.MockRequestHandleScope.(
                io.ktor.client.request.HttpRequestData,
            ) -> io.ktor.client.request.HttpResponseData,
        ) = OutboxDrainer(
            stores = SyncStores(accounts, bindings, outbox, notes, recordings),
            credentials = credentials,
            materializer = materializer,
            fs = fs,
            clock = clock,
            transport = mockTransport(recorded, handler),
            // Fixed, so a backoff assertion is about the policy and not about luck.
            jitter = { 0.5 },
        )
    }

    private fun okPut(etag: String = "abc123", ocId: String = "00000042ocidinstance") =
        headersOf("OC-ETag" to listOf("\"$etag\""), "OC-FileId" to listOf(ocId))

    // -----------------------------------------------------------------------

    @Test
    fun `five hundred edits produce one upload`() = runTest {
        // ROADMAP M5: "500 offline edits → exactly one upload". The coalescing does the
        // work; this proves it survives contact with the drainer, which is what the
        // criterion is actually about.
        val harness = Harness()
        harness.bindings.upsert(RemoteBinding(note, testAccount.id, "Inkstone/Lecture.pdf"))

        val engine = SyncEngine(
            stores = SyncStores(harness.accounts, harness.bindings, harness.outbox, harness.notes, harness.recordings),
            clock = harness.clock,
            scheduler = NoOpScheduler,
        )
        for (rev in 1L..500L) {
            harness.notes.setRev(note, rev)
            engine.noteChanged(note)
        }

        assertEquals(1, harness.outbox.count(), "500 edits left more than one outbox row")

        val drainer = harness.drainer { respond("", HttpStatusCode.Created, okPut()) }
        val report = drainer.drain()

        assertEquals(1, report.uploaded)
        assertEquals(1, harness.recorded.count("PUT"), "more than one PUT for 500 edits")
        assertEquals(NoteSyncState.SYNCED, harness.notes.states[note.value])
        assertEquals(0, harness.outbox.count())
    }

    @Test
    fun `a note edited mid-flight is re-enqueued instead of marked SYNCED`() = runTest {
        // SPEC §7.6's schema comment names this exactly: "the file changes while a PUT is
        // in flight, 2xx arrives, you mark SYNCED, and the last ten minutes of writing
        // becomes invisible to sync forever."
        val harness = Harness()
        harness.notes.setRev(note, 10L)
        harness.bindings.upsert(RemoteBinding(note, testAccount.id, "Inkstone/Lecture.pdf"))
        harness.outbox.mutate(note) {
            OutboxCoalescing.plan(it, OutboxOpType.UPLOAD, note, 10L, everUploaded = false, now = 0L)
        }

        val drainer = harness.drainer {
            // The edit lands while the PUT is on the wire. The materializer already read
            // rev 10, so the bytes on the wire are rev 10's and the note is now at 11.
            harness.notes.setRev(note, 11L)
            respond("", HttpStatusCode.Created, okPut())
        }
        val report = drainer.drain()

        assertEquals(1, report.uploaded)
        assertEquals(1, report.requeued)
        assertEquals(
            NoteSyncState.PENDING_UPLOAD,
            harness.notes.states[note.value],
            "SYNCED here is the bug — the newer work would never upload",
        )
        val queued = assertNotNull(harness.outbox.upload(note), "the row must survive, at the new revision")
        assertEquals(11L, queued.localRevAtEnqueue)
        // The upload itself succeeded, so the binding records what actually landed.
        assertEquals(10L, harness.bindings.find(note)?.lastSyncedRev)
    }

    @Test
    fun `a second upload within thirty seconds is deferred without consuming an attempt`() = runTest {
        // SPEC §7.6: "minimum 30 s between uploads of the same note while actively
        // editing". Deferring is not failing — charging it an attempt would push a note
        // that is merely being written to into exponential backoff.
        val harness = Harness()
        harness.notes.setRev(note, 1L)
        harness.bindings.upsert(RemoteBinding(note, testAccount.id, "Inkstone/Lecture.pdf"))
        harness.outbox.mutate(note) {
            OutboxCoalescing.plan(it, OutboxOpType.UPLOAD, note, 1L, everUploaded = false, now = 0L)
        }

        val drainer = harness.drainer { respond("", HttpStatusCode.Created, okPut()) }
        assertEquals(1, drainer.drain().uploaded)

        // A second edit five seconds later.
        harness.clock.advance(5_000L)
        harness.notes.setRev(note, 2L)
        harness.outbox.mutate(note) {
            OutboxCoalescing.plan(it, OutboxOpType.UPLOAD, note, 2L, everUploaded = true, now = harness.clock.now)
        }

        val second = drainer.drain()
        assertEquals(0, second.uploaded)
        assertEquals(1, second.deferred)
        assertEquals(1, harness.recorded.count("PUT"), "the 30 s floor did not hold")
        assertEquals(0, harness.outbox.upload(note)?.attempts, "deferring must not consume an attempt")

        // And once the interval has passed it goes.
        harness.clock.advance(30_000L)
        assertEquals(1, drainer.drain().uploaded)
        assertEquals(2, harness.recorded.count("PUT"))
    }

    @Test
    fun `a 401 halts the account and sends nothing further`() = runTest {
        // SPEC §7.2 / CLAUDE.md rule 5. ROADMAP M5: "revoke the app password server-side
        // → re-login prompt, no retry loop — check the Nextcloud log, zero brute-force
        // warnings." The request count is the check.
        val harness = Harness()
        val second = DocumentId("note-2")
        for (id in listOf(note, second)) {
            harness.notes.setRev(id, 1L)
            harness.bindings.upsert(RemoteBinding(id, testAccount.id, "Inkstone/${id.value}.pdf"))
            harness.outbox.mutate(id) {
                OutboxCoalescing.plan(it, OutboxOpType.UPLOAD, id, 1L, everUploaded = false, now = 0L)
            }
        }

        val drainer = harness.drainer { respondError(HttpStatusCode.Unauthorized) }
        val report = drainer.drain()

        assertEquals(setOf(testAccount.id), report.haltedAccounts)
        assertEquals(AccountState.NEEDS_REAUTH, harness.accounts.rows[testAccount.id]?.state)
        assertEquals(NoteSyncState.NEEDS_REAUTH, harness.notes.states[note.value])
        // One PUT, then the single confirmation GET SPEC §7.2 allows, and nothing else.
        // The second note is skipped entirely rather than producing a second 401.
        assertEquals(1, harness.recorded.count("PUT"), "a second credential attempt was made")
        assertEquals(1, harness.recorded.count("GET"), "more than one confirmation call")
        assertEquals(1, report.skipped)
        assertTrue(harness.outbox.count() > 0, "the work must survive a halt, not be dropped")
    }

    @Test
    fun `a 401 that the confirmation call disproves is not a reauth`() = runTest {
        // "Confirm once with GET /ocs/v2.php/cloud/user; if that also 401s, stop and
        // prompt." If it does not, this path is forbidden to us specifically — a withdrawn
        // share — and signing the user out would be wrong.
        val harness = Harness()
        harness.notes.setRev(note, 1L)
        harness.bindings.upsert(RemoteBinding(note, testAccount.id, "Someone/Shared.pdf"))
        harness.outbox.mutate(note) {
            OutboxCoalescing.plan(it, OutboxOpType.UPLOAD, note, 1L, everUploaded = false, now = 0L)
        }

        val drainer = harness.drainer { request ->
            if (request.method.value == "GET") {
                json("""{"ocs":{"data":{"id":"noah"}}}""")
            } else {
                respondError(HttpStatusCode.Unauthorized)
            }
        }
        val report = drainer.drain()

        assertTrue(report.haltedAccounts.isEmpty())
        assertEquals(AccountState.ACTIVE, harness.accounts.rows[testAccount.id]?.state)
        assertEquals(NoteSyncState.ERROR, harness.notes.states[note.value])
    }

    @Test
    fun `a 412 is a conflict — flagged and skipped and never retried`() = runTest {
        // SPEC §7.5: "412 | Conflict — §7.7. Never blind-retry." §7.7 rule 3: "a
        // conflicted note is flagged and skipped; everything else keeps syncing."
        val harness = Harness()
        harness.notes.setRev(note, 3L)
        harness.bindings.upsert(
            RemoteBinding(note, testAccount.id, "Inkstone/Lecture.pdf", lastSyncedEtag = ETag("old", weak = false)),
        )
        harness.outbox.mutate(note) {
            OutboxCoalescing.plan(it, OutboxOpType.UPLOAD, note, 3L, everUploaded = true, now = 0L)
        }

        val drainer = harness.drainer { respondError(HttpStatusCode.PreconditionFailed) }
        val report = drainer.drain()

        assertEquals(1, report.parked)
        assertEquals(NoteSyncState.CONFLICTED, harness.notes.states[note.value])
        assertEquals(1, harness.recorded.count("PUT"), "a 412 must never be blind-retried")
        // The row survives so M6 can resolve it; it is simply no longer due.
        val parked = assertNotNull(harness.outbox.upload(note))
        assertTrue(parked.nextAttemptAt > harness.clock.now)

        assertEquals(0, drainer.drain().uploaded, "a parked row must not come straight back")
    }

    @Test
    fun `an If-Match is sent when the stored ETag is strong and withheld when it is weak`() = runTest {
        // SPEC §7.3: returning a weak ETag in If-Match causes a PERMANENT 412 loop. This
        // is the one place that can happen, so it is the one place worth testing.
        for ((etag, expected) in listOf(ETag("strong", weak = false) to true, ETag("weak", weak = true) to false)) {
            val harness = Harness()
            harness.notes.setRev(note, 1L)
            harness.bindings.upsert(
                RemoteBinding(note, testAccount.id, "Inkstone/Lecture.pdf", remoteOcId = "id", lastSyncedEtag = etag),
            )
            harness.outbox.mutate(note) {
                OutboxCoalescing.plan(it, OutboxOpType.UPLOAD, note, 1L, everUploaded = true, now = 0L)
            }

            harness.drainer { respond("", HttpStatusCode.NoContent, okPut()) }.drain()

            val put = harness.recorded.of("PUT").single()
            assertEquals(
                expected,
                put.headers["If-Match"] != null,
                "If-Match presence was wrong for a ${if (etag.weak) "weak" else "strong"} ETag",
            )
            assertEquals(
                "md5:${Md5.hex(harness.fs.readBytes("notes/${note.value}/document.pdf"))}",
                put.headers["OC-Checksum"],
            )
        }
    }

    @Test
    fun `a move drains before the upload and uses Overwrite F`() = runTest {
        val harness = Harness()
        harness.notes.setRev(note, 2L)
        harness.bindings.upsert(
            RemoteBinding(
                noteId = note,
                accountId = testAccount.id,
                remotePath = "Inkstone/Renamed.pdf",
                movedFromPath = "Inkstone/Original.pdf",
                remoteOcId = "id",
                lastSyncedEtag = ETag("e1", weak = false),
            ),
        )
        for (op in listOf(OutboxOpType.MOVE, OutboxOpType.UPLOAD)) {
            harness.outbox.mutate(note) {
                OutboxCoalescing.plan(it, op, note, 2L, everUploaded = true, now = 0L)
            }
        }

        val drainer = harness.drainer { respond("", HttpStatusCode.Created, okPut()) }
        val report = drainer.drain()

        assertEquals(1, report.moved)
        assertEquals(1, report.uploaded)
        val methods = harness.recorded.all.map { it.method.value }
        assertTrue(
            methods.indexOf("MOVE") < methods.indexOf("PUT"),
            "MOVE must drain before UPLOAD for the same note (SPEC §7.6): $methods",
        )
        val move = harness.recorded.of("MOVE").single()
        assertEquals("F", move.headers["Overwrite"], "a MOVE without Overwrite: F can destroy the destination")
        assertTrue(move.headers["Destination"].orEmpty().endsWith("Inkstone/Renamed.pdf"))
        assertNull(harness.bindings.find(note)?.movedFromPath)
    }

    @Test
    fun `a delete removes the binding and a 404 counts as done`() = runTest {
        val harness = Harness()
        harness.notes.setRev(note, 1L)
        harness.bindings.upsert(
            RemoteBinding(note, testAccount.id, "Inkstone/Gone.pdf", remoteOcId = "id"),
        )
        harness.outbox.mutate(note) {
            OutboxCoalescing.plan(it, OutboxOpType.DELETE, note, 1L, everUploaded = true, now = 0L)
        }

        val drainer = harness.drainer { respondError(HttpStatusCode.NotFound) }
        val report = drainer.drain()

        assertEquals(1, report.deleted)
        assertNull(harness.bindings.find(note), "the binding must go with the file")
        assertEquals(0, harness.outbox.count())
    }

    @Test
    fun `a quota failure parks rather than retrying`() = runTest {
        // SPEC §7.5: "507 | Quota exceeded; pause that account's uploads." Retrying into a
        // full disk is a request per backoff interval that cannot ever succeed.
        val harness = Harness()
        harness.notes.setRev(note, 1L)
        harness.bindings.upsert(RemoteBinding(note, testAccount.id, "Inkstone/Big.pdf"))
        harness.outbox.mutate(note) {
            OutboxCoalescing.plan(it, OutboxOpType.UPLOAD, note, 1L, everUploaded = false, now = 0L)
        }

        val drainer = harness.drainer { respondError(HttpStatusCode.InsufficientStorage) }
        val report = drainer.drain()

        assertEquals(1, report.parked)
        assertEquals(NoteSyncState.ERROR, harness.notes.states[note.value])
        assertEquals(0, drainer.drain().failed, "a parked row must not come straight back")
    }

    @Test
    fun `a transport failure backs off and keeps the work`() = runTest {
        val harness = Harness()
        harness.notes.setRev(note, 1L)
        harness.bindings.upsert(RemoteBinding(note, testAccount.id, "Inkstone/Lecture.pdf"))
        harness.outbox.mutate(note) {
            OutboxCoalescing.plan(it, OutboxOpType.UPLOAD, note, 1L, everUploaded = false, now = 0L)
        }

        val drainer = harness.drainer { throw IllegalStateException("network is down") }
        val report = drainer.drain()

        assertEquals(1, report.failed)
        assertTrue(report.shouldRunAgain)
        val row = assertNotNull(harness.outbox.upload(note))
        assertEquals(1, row.attempts)
        assertTrue(row.nextAttemptAt >= harness.clock.now + Backoff.BASE_MILLIS / 2)
        assertEquals(NoteSyncState.PENDING_UPLOAD, harness.notes.states[note.value])
    }

    @Test
    fun `a 429 honours Retry-After`() = runTest {
        val harness = Harness()
        harness.notes.setRev(note, 1L)
        harness.bindings.upsert(RemoteBinding(note, testAccount.id, "Inkstone/Lecture.pdf"))
        harness.outbox.mutate(note) {
            OutboxCoalescing.plan(it, OutboxOpType.UPLOAD, note, 1L, everUploaded = false, now = 0L)
        }

        val drainer = harness.drainer {
            respond("", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "120"))
        }
        drainer.drain()

        assertEquals(harness.clock.now + 120_000L, harness.outbox.upload(note)?.nextAttemptAt)
    }

    @Test
    fun `a row for a note that is no longer bound is dropped rather than sent`() = runTest {
        val harness = Harness()
        harness.notes.setRev(note, 1L)
        harness.outbox.mutate(note) {
            OutboxCoalescing.plan(it, OutboxOpType.UPLOAD, note, 1L, everUploaded = false, now = 0L)
        }

        val drainer = harness.drainer { respond("", HttpStatusCode.Created, okPut()) }
        drainer.drain()

        assertEquals(0, harness.outbox.count())
        assertTrue(harness.recorded.all.isEmpty(), "nothing should be sent for an unbound note")
    }

    @Test
    fun `a row whose account no longer exists is dropped and the note goes back to local-only`() = runTest {
        // Found on a device in M5 by signing out with work queued. The account row went,
        // the binding and the outbox rows did not, and the drainer reported SUCCESS on
        // every pass — so the note claimed PENDING_UPLOAD forever with nothing behind it.
        val harness = Harness(accounts = FakeAccountStore())
        harness.notes.setRev(note, 4L)
        harness.bindings.upsert(RemoteBinding(note, "an-account-that-is-gone", "Inkstone/Lecture.pdf"))
        harness.outbox.mutate(note) {
            OutboxCoalescing.plan(it, OutboxOpType.UPLOAD, note, 4L, everUploaded = false, now = 0L)
        }

        val report = harness.drainer { respond("", HttpStatusCode.Created, okPut()) }.drain()

        assertEquals(1, report.orphaned)
        assertEquals(0, harness.outbox.count(), "the row would never drain; leaving it strands the note")
        assertNull(harness.bindings.find(note), "the binding points at nothing")
        assertEquals(NoteSyncState.LOCAL_ONLY, harness.notes.states[note.value], "which is what the note now is")
        assertTrue(harness.recorded.all.isEmpty(), "nothing may be sent for an account that is gone")
    }

    @Test
    fun `a row whose account is halted says so on the note rather than sitting silently`() = runTest {
        val halted = FakeAccountStore(testAccount.copy(state = AccountState.NEEDS_REAUTH))
        val harness = Harness(accounts = halted)
        harness.notes.setRev(note, 4L)
        harness.bindings.upsert(RemoteBinding(note, testAccount.id, "Inkstone/Lecture.pdf"))
        harness.outbox.mutate(note) {
            OutboxCoalescing.plan(it, OutboxOpType.UPLOAD, note, 4L, everUploaded = false, now = 0L)
        }

        val report = harness.drainer { respond("", HttpStatusCode.Created, okPut()) }.drain()

        assertEquals(1, report.skipped)
        // The work is kept — this one is recoverable by signing in again.
        assertEquals(1, harness.outbox.count())
        assertEquals(NoteSyncState.NEEDS_REAUTH, harness.notes.states[note.value])
        assertTrue(harness.recorded.all.isEmpty(), "a halted account must not be contacted")
    }

    private object NoOpScheduler : dev.inkstone.core.model.WorkScheduler {
        override fun enqueueOutboxDrain() = Unit

        override fun enqueueMaterialization(noteId: DocumentId) = Unit
    }
}
