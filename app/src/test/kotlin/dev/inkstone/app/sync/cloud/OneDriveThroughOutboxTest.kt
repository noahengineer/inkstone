package dev.inkstone.app.sync.cloud

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.inkstone.app.sync.matrix.MatrixClock
import dev.inkstone.app.sync.matrix.TempFileSystem
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.database.sync.SqlAccountStore
import dev.inkstone.core.database.sync.SqlBindingStore
import dev.inkstone.core.database.sync.SqlOutboxStore
import dev.inkstone.core.database.sync.SqlRecordingUploads
import dev.inkstone.core.model.CredentialStore
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.DocumentMaterializer
import dev.inkstone.core.model.MaterializedFile
import dev.inkstone.core.model.NextcloudCredential
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.sync.AccountState
import dev.inkstone.core.sync.MicrosoftOAuthConfig
import dev.inkstone.core.sync.NextcloudTransport
import dev.inkstone.core.sync.OAuthSecret
import dev.inkstone.core.sync.OneDriveStoreFactory
import dev.inkstone.core.sync.OutboxCoalescing
import dev.inkstone.core.sync.OutboxDrainer
import dev.inkstone.core.sync.OutboxOpType
import dev.inkstone.core.sync.ProviderKind
import dev.inkstone.core.sync.ProviderRoutingFactory
import dev.inkstone.core.sync.RemoteBinding
import dev.inkstone.core.sync.SyncAccount
import dev.inkstone.core.sync.SyncNotes
import dev.inkstone.core.sync.SyncStores
import dev.inkstone.core.sync.TokenVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⭐ OneDrive **through the outbox** — the real `OutboxDrainer`, real SQL account/binding/outbox
 * stores, a routing factory, the real transport, and [GraphStandIn] behind a socket.
 *
 * `OneDriveStoreTest` proves the store; this proves the store is *reached*. CLAUDE.md's most common
 * milestone failure is a feature correct in every test and connected to nothing, and "an upload to
 * OneDrive" is only real if a queued row, drained by the production loop, ends up there with the
 * binding recording what came back.
 *
 * What it still cannot show: a real Graph, a device, the sign-in, or a pull — OneDrive is
 * upload-only until its `delta` feed exists (see `PullShape`).
 */
class OneDriveThroughOutboxTest {

    private val server = GraphStandIn()
    private val clock = MatrixClock()
    private val transport = NextcloudTransport.create()
    private val root = createTempDirectory("onedrive-outbox")
    private val fs = TempFileSystem(root)

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = InkstoneDatabase(driver).also { InkstoneDatabase.Schema.create(driver) }
    private val dispatcher = Dispatchers.Unconfined
    private val accounts = SqlAccountStore(database, dispatcher)
    private val bindings = SqlBindingStore(database, dispatcher)
    private val outbox = SqlOutboxStore(database, dispatcher)

    private val note = DocumentId("note-1")
    private var localBytes = "version one".encodeToByteArray()

    private class Notes : SyncNotes {
        val revs = mutableMapOf<String, Long>()
        val states = mutableMapOf<String, NoteSyncState>()

        override suspend fun localRev(noteId: DocumentId): Long? = revs[noteId.value]

        override suspend fun setSyncState(noteId: DocumentId, state: NoteSyncState, lastError: String?) {
            states[noteId.value] = state
        }
    }

    private val notes = Notes()

    private val vault = object : TokenVault {
        var secret: OAuthSecret? = OAuthSecret(server.initialRefreshToken)

        override suspend fun get(accountId: String): OAuthSecret? = secret

        override suspend fun put(accountId: String, secret: OAuthSecret) {
            this.secret = secret
        }

        override suspend fun remove(accountId: String) {
            secret = null
        }
    }

    private val noCredentials = object : CredentialStore {
        override suspend fun put(accountId: String, credential: NextcloudCredential) = Unit

        override suspend fun get(accountId: String): NextcloudCredential? = null

        override suspend fun remove(accountId: String) = Unit
    }

    private val materializer = DocumentMaterializer { id ->
        val path = "docs/${id.value}.pdf"
        fs.writeBytesAtomically(path, localBytes)
        MaterializedFile(path, localBytes.size.toLong(), "sha", notes.revs.getValue(id.value))
    }

    private val drainer = OutboxDrainer(
        stores = SyncStores(accounts, bindings, outbox, notes, SqlRecordingUploads(database, dispatcher)),
        credentials = noCredentials,
        materializer = materializer,
        fs = fs,
        clock = clock,
        transport = transport,
        storeFactory = ProviderRoutingFactory(
            mapOf(
                ProviderKind.ONEDRIVE to OneDriveStoreFactory(
                    MicrosoftOAuthConfig("client-1", tokenEndpoint = server.tokenUrl),
                    vault,
                    transport,
                    clock,
                ),
            ),
        ),
        jitter = { 0.0 },
    )

    @AfterTest
    fun tearDown() {
        server.close()
        transport.close()
        root.toFile().deleteRecursively()
    }

    private suspend fun bindNote() {
        accounts.upsert(
            SyncAccount(
                id = ACCOUNT,
                serverUrl = server.baseUrl,
                loginName = "me@example.com",
                uid = server.driveId,
                provider = ProviderKind.ONEDRIVE,
            ),
        )
        notes.revs[note.value] = 1L
        bindings.upsert(RemoteBinding(note, ACCOUNT, "Inkstone/Lecture.pdf"))
        enqueue(OutboxOpType.MKCOL, everUploaded = false)
        enqueue(OutboxOpType.UPLOAD, everUploaded = false)
    }

    private suspend fun enqueue(op: OutboxOpType, everUploaded: Boolean) {
        val rev = notes.revs.getValue(note.value)
        outbox.mutate(note) { OutboxCoalescing.plan(it, op, note, rev, everUploaded, clock.nowEpochMillis()) }
    }

    private suspend fun edit(text: String) {
        localBytes = text.encodeToByteArray()
        notes.revs[note.value] = notes.revs.getValue(note.value) + 1
        clock.advance(MORE_THAN_THE_FLOOR)
        enqueue(OutboxOpType.UPLOAD, everUploaded = true)
    }

    @Test
    fun `a queued note is created on onedrive and the binding records what came back`() = runTest {
        bindNote()

        val report = drainer.drain()

        assertEquals(1, report.uploaded)
        assertContentEquals("version one".encodeToByteArray(), server.bytesAt("Inkstone/Lecture.pdf"))
        val binding = assertNotNull(bindings.find(note))
        assertEquals(
            server.idAt("Inkstone/Lecture.pdf"),
            binding.remoteOcId,
            "the item id is what later writes address",
        )
        assertEquals(server.etagAt("Inkstone/Lecture.pdf")?.trim('"'), binding.lastSyncedEtag?.value)
        assertEquals(NoteSyncState.SYNCED, notes.states[note.value])
        assertEquals(0, outbox.all().size, "the row must be gone once it has landed")
    }

    @Test
    fun `an edit replaces the file conditionally on the version the binding holds`() = runTest {
        bindNote()
        drainer.drain()
        val versionOne = assertNotNull(bindings.find(note)?.lastSyncedEtag)

        edit("version two")
        val report = drainer.drain()

        assertEquals(1, report.uploaded)
        assertContentEquals("version two".encodeToByteArray(), server.bytesAt("Inkstone/Lecture.pdf"))
        assertEquals(versionOne.asIfMatch, server.sessionIfMatch.last(), "the replace was not conditional")
        assertTrue(bindings.find(note)?.lastSyncedEtag != versionOne, "the binding kept the old version")
    }

    @Test
    fun `an edit made after somebody else changed the file is parked as a conflict and theirs survives`() = runTest {
        bindNote()
        drainer.drain()
        server.editBehindTheirBack("Inkstone/Lecture.pdf", "theirs".encodeToByteArray())
        val fragmentsBefore = server.upload.count

        edit("mine")
        val report = drainer.drain()

        assertEquals(0, report.uploaded)
        assertEquals(1, report.parked)
        assertEquals(setOf(note), report.conflicted, "the 412 must be reported so a reconcile can be asked for")
        assertEquals(NoteSyncState.CONFLICTED, notes.states[note.value])
        assertContentEquals(
            "theirs".encodeToByteArray(),
            server.bytesAt("Inkstone/Lecture.pdf"),
            "rule 1: theirs was overwritten",
        )
        assertEquals(fragmentsBefore, server.upload.count, "a fragment was sent after the conflict")
        assertEquals(1, outbox.all().size, "the row stays — parked, not dropped (rule 1)")
    }

    @Test
    fun `a refused grant halts the account after one token request and nothing is sent afterwards`() = runTest {
        server.grantDead = true
        bindNote()

        val first = drainer.drain()

        assertEquals(setOf(ACCOUNT), first.haltedAccounts)
        assertEquals(AccountState.NEEDS_REAUTH, accounts.find(ACCOUNT)?.state)
        assertEquals(ProviderKind.ONEDRIVE, accounts.find(ACCOUNT)?.provider, "halting an account changed its provider")
        assertEquals(1, server.tokenEndpoint.count)

        // The next drain: the account is not usable, so not one request of any kind goes anywhere.
        clock.advance(MORE_THAN_THE_FLOOR)
        drainer.drain()
        assertEquals(1, server.tokenEndpoint.count, "a halted account asked for a token again")
        assertEquals(0, server.api.count)
    }

    @Test
    fun `an unknown provider is never sent a request`() = runTest {
        accounts.upsert(
            SyncAccount(ACCOUNT, server.baseUrl, "me", server.driveId, provider = ProviderKind.UNKNOWN),
        )
        notes.revs[note.value] = 1L
        bindings.upsert(RemoteBinding(note, ACCOUNT, "Inkstone/Lecture.pdf"))
        enqueue(OutboxOpType.UPLOAD, everUploaded = false)

        drainer.drain()

        assertEquals(0, server.api.count + server.tokenEndpoint.count + server.upload.count)
        assertEquals(
            NoteSyncState.NEEDS_REAUTH,
            notes.states[note.value],
            "an unusable account must say so on the note",
        )
    }

    private companion object {
        const val ACCOUNT = "od-1"
        const val MORE_THAN_THE_FLOOR = 31_000L
    }
}
