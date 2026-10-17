package dev.inkstone.core.sync

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.NoteSyncState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * docs/CLOUD-PROVIDERS.md §2 — the seam, tested with a store that is **not** Nextcloud.
 *
 * Every other drainer test goes through [NextcloudStoreFactory] by default, which proves P1 changed
 * nothing but not that the drainer really *uses* the seam. This one hands it a different
 * [CloudStore] and asserts on what that store was asked, so a drainer that quietly kept reaching
 * for WebDAV would fail here.
 *
 * What it does NOT prove: that any real second provider behaves — [RecordingStore] is a fake. It
 * proves the drainer's side of the contract (which precondition it asks for, and that it refuses
 * to ask a strict store for an unchecked write); OneDrive's side is P3's stand-in server.
 */
class CloudStoreSeamTest {

    private val note = DocumentId("note-1")

    private class RecordingStore(override val account: SyncAccount, override val supportsUncheckedReplace: Boolean) :
        CloudStore {
        override val kind: ProviderKind = ProviderKind.ONEDRIVE
        override val pullShape: PullShape = PullShape.FEED
        val puts = mutableListOf<Pair<RemoteTarget, WritePrecondition>>()

        override suspend fun ensureFolder(path: String): DavOutcome<Unit> = DavOutcome.Ok(Unit)

        override suspend fun put(
            target: RemoteTarget,
            bytes: ByteArray,
            precondition: WritePrecondition,
            mtimeEpochSeconds: Long?,
            md5Hex: String?,
            kind: UploadKind,
        ): DavOutcome<UploadReceipt> {
            puts += target to precondition
            return DavOutcome.Ok(UploadReceipt(201, ETag("new-version", weak = false), "item-id-9"))
        }

        override suspend fun move(from: RemoteTarget, toPath: String): DavOutcome<UploadReceipt> =
            DavOutcome.Ok(UploadReceipt(200, null, null))

        override suspend fun trash(target: RemoteTarget): DavOutcome<Unit> = DavOutcome.Ok(Unit)

        override suspend fun confirmStillAuthorised(): Boolean? = true

        override suspend fun listing(path: String, depth: ListingDepth): DavOutcome<List<DavResource>> =
            DavOutcome.Ok(emptyList())

        override suspend fun download(path: String): DavOutcome<DownloadedFile> =
            DavOutcome.Failed(DavFailure.NotFound(path))

        override suspend fun locate(fileId: Long): DavOutcome<List<DavResource>> = DavOutcome.Ok(emptyList())
    }

    private class Setup(supportsUnchecked: Boolean) {
        val clock = FakeClock()
        val notes = FakeSyncNotes()
        val outbox = FakeOutboxStore()
        val accounts = FakeAccountStore(testAccount)
        val bindings = FakeBindingStore()
        val fs = FakeFileSystem()
        val recordings = FakeRecordingUploads()
        val store = RecordingStore(testAccount, supportsUnchecked)

        val drainer = OutboxDrainer(
            stores = SyncStores(accounts, bindings, outbox, notes, recordings),
            credentials = FakeCredentialStore(),
            materializer = FakeMaterializer(notes, fs),
            fs = fs,
            clock = clock,
            transport = mockTransport(RecordedRequests()) { error("a CloudStore was supplied; no HTTP expected") },
            storeFactory = { store },
            jitter = { 0.5 },
        )
    }

    private suspend fun Setup.queueUpload(binding: RemoteBinding, everUploaded: Boolean) {
        notes.setRev(note, 1L)
        bindings.upsert(binding)
        outbox.mutate(note) {
            OutboxCoalescing.plan(it, OutboxOpType.UPLOAD, note, 1L, everUploaded = everUploaded, now = 0L)
        }
    }

    @Test
    fun `a first upload asks the store for create-only and carries no id`() = runTest {
        val setup = Setup(supportsUnchecked = false)
        setup.queueUpload(RemoteBinding(note, testAccount.id, "Inkstone/Lecture.pdf"), everUploaded = false)

        val report = setup.drainer.drain()

        assertEquals(1, report.uploaded)
        val (target, precondition) = setup.store.puts.single()
        assertEquals("Inkstone/Lecture.pdf", target.path)
        assertEquals(WritePrecondition.CreateOnly, precondition)
        // The receipt's id and version are what the next write is conditioned on.
        val bound = setup.bindings.find(note)
        assertEquals("item-id-9", bound?.remoteOcId)
        assertEquals("new-version", bound?.lastSyncedEtag?.value)
    }

    @Test
    fun `a later upload is conditioned on the recorded version and addressed by id`() = runTest {
        val setup = Setup(supportsUnchecked = false)
        setup.queueUpload(
            RemoteBinding(
                note,
                testAccount.id,
                "Inkstone/Lecture.pdf",
                remoteOcId = "item-id-9",
                lastSyncedEtag = ETag("v1", weak = false),
                lastUploadAtEpochMillis = 1L,
            ),
            everUploaded = true,
        )
        setup.clock.advance(60_000L)

        setup.drainer.drain()

        val (target, precondition) = setup.store.puts.single()
        assertEquals("item-id-9", target.id, "an id-addressed store must be given the id")
        assertEquals(WritePrecondition.IfMatch(ETag("v1", weak = false)), precondition)
    }

    @Test
    fun `a strict store is never asked to overwrite without a recorded version`() = runTest {
        // CLAUDE.md rule 1. The binding has uploaded before (a timestamp) but holds no ETag — the
        // proxy-stripped case. Nextcloud sends no header and relies on the checksum; a store that
        // cannot do that must not be asked to write at all.
        val setup = Setup(supportsUnchecked = false)
        setup.queueUpload(
            RemoteBinding(note, testAccount.id, "Inkstone/Lecture.pdf", lastUploadAtEpochMillis = 1L),
            everUploaded = true,
        )
        setup.clock.advance(60_000L)

        val report = setup.drainer.drain()

        assertTrue(setup.store.puts.isEmpty(), "a blind overwrite reached a store that forbids it")
        assertEquals(0, report.uploaded)
        assertEquals(1, report.parked)
        assertEquals(NoteSyncState.ERROR, setup.notes.states[note.value])
        assertIs<WritePrecondition.Unchecked>(WritePrecondition.Unchecked)
    }

    @Test
    fun `nextcloud keeps its unchecked replace`() = runTest {
        // The same binding on a store that allows it: P1 must not have changed Nextcloud.
        val setup = Setup(supportsUnchecked = true)
        setup.queueUpload(
            RemoteBinding(note, testAccount.id, "Inkstone/Lecture.pdf", lastUploadAtEpochMillis = 1L),
            everUploaded = true,
        )
        setup.clock.advance(60_000L)

        setup.drainer.drain()

        assertEquals(WritePrecondition.Unchecked, setup.store.puts.single().second)
    }
}
