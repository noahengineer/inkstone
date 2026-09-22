package dev.inkstone.core.sync

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.model.WorkScheduler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⭐ **Writing on a PDF that was already on the user's Nextcloud** — [SyncEngine.bindAdopted].
 *
 * The whole of this feature's risk is in one header. The file **exists**, so the create-guard
 * every other first upload uses is exactly wrong for it:
 *
 * - `If-None-Match: *` says *"only if there is nothing here"*, which a file the user just picked
 *   fails by definition. A permanent 412, parked for a day, the note flagged `CONFLICTED`, and
 *   the park inherited by every later edit — so the note is silently dead for the whole session
 *   and the app reports success. That is what [SyncEngine.bind] would have sent, and asserting
 *   that it does is half of this file, because a future refactor that routes adoption back
 *   through `bind` would otherwise be green.
 * - `If-Match: "<etag off the GET>"` says *"only if it is still the file I read"*, which is both
 *   the correct write guard and CLAUDE.md rule 1's refusal to overwrite bytes this device has
 *   not seen.
 *
 * The rest asserts the four facts the binding has to carry out of the download, since every one
 * of them is a documented way to lose a day: the ETag (the guard above), the `oc:id` (a move is
 * matched on it, SPEC §7.4 rule 2), `lastUploadAtEpochMillis` (what makes
 * [RemoteBinding.everUploaded] true when a proxy strips both validators), and `lastSyncedRev = 0`
 * (the server holds none of *this note's* revisions, only the PDF it was made from).
 */
class AdoptedFileTest {

    private val note = DocumentId("note-adopted")
    private val remotePath = "Documents/Chemistry handout.pdf"
    private val remoteEtag = ETag("serverside-etag", weak = false)
    private val remoteOcId = "00000042ocidinstance"

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

        val engine = SyncEngine(
            stores = SyncStores(accounts, bindings, outbox, notes, recordings),
            clock = clock,
            scheduler = NoOpScheduler,
        )

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
            jitter = { 0.5 },
        )
    }

    /** What the download reported, which is what the binding must be built from. */
    private fun observed() = RemoteVersion(etag = remoteEtag, ocId = remoteOcId, checksum = null, syncedRev = null)

    private fun harnessWithNote(rev: Long = 1L) = Harness().apply { notes.setRev(note, rev) }

    // -----------------------------------------------------------------------

    @Test
    fun `adopting a remote file records the version the download reported`() = runTest {
        val harness = harnessWithNote()

        val binding = assertNotNull(
            harness.engine.bindAdopted(note, testAccount.id, remotePath, observed()),
            "the account exists, so a binding was owed",
        )

        assertEquals(remotePath, binding.remotePath, "the note must be bound to the file it came from")
        assertEquals(remoteEtag, binding.lastSyncedEtag, "without this the PUT has nothing to send as If-Match")
        assertEquals(remoteOcId, binding.remoteOcId, "SPEC 7.4 rule 2 matches a moved file on its oc:id")
        assertEquals(0L, binding.lastSyncedRev, "the server holds the source PDF and none of this note's revisions")
        assertTrue(binding.everUploaded, "a file that demonstrably exists must never be created with If-None-Match")
    }

    @Test
    fun `adopting enqueues exactly one upload and no MKCOL`() = runTest {
        val harness = harnessWithNote()

        harness.engine.bindAdopted(note, testAccount.id, remotePath, observed())

        val rows = harness.outbox.pendingFor(note)
        assertEquals(1, rows.size, "one upload, and nothing else: the folder was listed to pick the file out of")
        assertEquals(OutboxOpType.UPLOAD, rows.single().opType)
        assertEquals(NoteSyncState.PENDING_UPLOAD, harness.notes.states[note.value])
    }

    @Test
    fun `the adopted upload carries If-Match and never If-None-Match star`() = runTest {
        // ⭐ The one that matters. Everything else in this file is a fact about a row; this is the
        // header that decides whether the user's ink reaches their handout or 412s for a day.
        val harness = harnessWithNote()
        harness.engine.bindAdopted(note, testAccount.id, remotePath, observed())
        // SPEC 7.6's 30 s floor is measured from lastUploadAtEpochMillis, which the adoption
        // stamps — see the test below for why that is right rather than merely tolerated.
        harness.clock.advance(UPLOAD_FLOOR_MILLIS + 1)

        val drainer = harness.drainer {
            respond(
                "",
                HttpStatusCode.NoContent,
                headersOf("OC-ETag" to listOf("\"after-our-write\""), "OC-FileId" to listOf(remoteOcId)),
            )
        }
        val report = drainer.drain()

        assertEquals(1, report.uploaded, "the adopted file was not uploaded at all")
        val put = harness.recorded.of("PUT").single()
        assertEquals("\"serverside-etag\"", put.headers["If-Match"], "the write must be guarded by the version read")
        assertNull(put.headers["If-None-Match"], "If-None-Match star against an existing file is a permanent 412")
    }

    @Test
    fun `an adopted file is not written the instant it is picked`() = runTest {
        // ⭐ Deliberate, and the reason is the user rather than the server. Adopting stamps
        // `lastUploadAtEpochMillis`, so SPEC 7.6's floor applies from the moment the file is
        // picked — and the note opens immediately, so the first thirty seconds are exactly when
        // the first strokes get drawn. The marker and that ink therefore land in **one** PUT
        // rather than two, and a user who changes their mind and unbinds inside that window has
        // not had their document rewritten at all.
        val harness = harnessWithNote()
        harness.engine.bindAdopted(note, testAccount.id, remotePath, observed())

        val drainer = harness.drainer { respond("", HttpStatusCode.NoContent, headersOf()) }
        val report = drainer.drain()

        assertEquals(0, report.uploaded, "the floor is measured from the download, not from zero")
        assertTrue(harness.recorded.of("PUT").isEmpty(), "nothing may be written to the user's file yet")
        assertEquals(1, harness.outbox.pendingFor(note).size, "and the work is still owed, not dropped")
    }

    @Test
    fun `binding the same note as a new note would send If-None-Match star instead`() = runTest {
        // The trap this method exists to make unreachable, demonstrated on the very same fixture.
        // If adoption is ever refactored back through `bind`, the assertion above goes red and
        // this one says why.
        val harness = harnessWithNote()

        harness.engine.bind(note, testAccount.id, title = "Chemistry handout", remoteFolder = "Documents")

        val drainer = harness.drainer { respond("", HttpStatusCode.Created, headersOf()) }
        drainer.drain()

        val put = harness.recorded.of("PUT").single()
        assertEquals("*", put.headers["If-None-Match"], "bind is the create path, and that is why it is not used here")
        assertNull(put.headers["If-Match"])
    }

    @Test
    fun `a halted account is bound but queues nothing`() = runTest {
        // The same refusal `noteChanged` makes. A row that cannot drain is M5's silent stall:
        // the note reads PENDING_UPLOAD forever while the worker reports success on every pass.
        val harness = harnessWithNote()
        harness.accounts.setState(testAccount.id, AccountState.NEEDS_REAUTH, "revoked")

        val binding = assertNotNull(harness.engine.bindAdopted(note, testAccount.id, remotePath, observed()))

        assertEquals(remoteEtag, binding.lastSyncedEtag, "the version read is still worth remembering")
        assertTrue(harness.outbox.pendingFor(note).isEmpty(), "nothing may be queued for an account that cannot send")
    }

    @Test
    fun `an unknown account binds nothing at all`() = runTest {
        val harness = harnessWithNote()

        assertNull(harness.engine.bindAdopted(note, "acct-that-does-not-exist", remotePath, observed()))
        assertNull(harness.bindings.find(note), "a binding to an account that is not there can never drain")
        assertTrue(harness.outbox.pendingFor(note).isEmpty())
    }

    @Test
    fun `an adopted note that is then edited still coalesces to one upload`() = runTest {
        // The adoption's upload and the first stroke's are the same row (SPEC 7.6's UPSERT), so
        // adding a marker to the file does not cost the user a second round trip.
        val harness = harnessWithNote()
        harness.engine.bindAdopted(note, testAccount.id, remotePath, observed())

        for (rev in 2L..20L) {
            harness.notes.setRev(note, rev)
            harness.engine.noteChanged(note)
        }

        assertEquals(1, harness.outbox.count(), "the adoption and nineteen edits left more than one row")
    }

    private companion object {
        /** `OutboxDrainer.MIN_UPLOAD_INTERVAL_MILLIS`, which is private to it. SPEC 7.6 rule 4. */
        const val UPLOAD_FLOOR_MILLIS = 30_000L
    }

    private object NoOpScheduler : WorkScheduler {
        override fun enqueueOutboxDrain() = Unit

        override fun enqueueMaterialization(noteId: DocumentId) = Unit
    }
}
