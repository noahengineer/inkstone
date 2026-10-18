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
import dev.inkstone.core.sync.AccessTokenSource
import dev.inkstone.core.sync.AccountState
import dev.inkstone.core.sync.BearerResult
import dev.inkstone.core.sync.DriveStoreFactory
import dev.inkstone.core.sync.ForcedRefresh
import dev.inkstone.core.sync.NextcloudTransport
import dev.inkstone.core.sync.OutboxCoalescing
import dev.inkstone.core.sync.OutboxDrainer
import dev.inkstone.core.sync.OutboxOpType
import dev.inkstone.core.sync.ProviderKind
import dev.inkstone.core.sync.ProviderRoutingFactory
import dev.inkstone.core.sync.RemoteBinding
import dev.inkstone.core.sync.SyncAccount
import dev.inkstone.core.sync.SyncNotes
import dev.inkstone.core.sync.SyncStores
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
 * ⭐ Google Drive **through the outbox** — the real `OutboxDrainer`, real SQL account/binding/outbox
 * stores, a routing factory, the real transport, and [DriveStandIn] behind a socket.
 *
 * `DriveStoreTest` proves the store; this proves the store is *reached*: a queued row, drained by the
 * production loop, ends up on Drive with the binding recording the **head revision** the next overwrite
 * will check against.
 *
 * What it still cannot show: a real Google, the sign-in, a device.
 */
class DriveThroughOutboxTest {

    private val server = DriveStandIn()
    private val clock = MatrixClock()
    private val transport = NextcloudTransport.create()
    private val root = createTempDirectory("drive-outbox")
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

    /** A grant that can be killed: the bearer still goes out, but the one refresh a 401 allows is refused. */
    private val tokens = object : AccessTokenSource {
        var dead = false
        var refreshes = 0

        override suspend fun bearer(): BearerResult = BearerResult.Token("tok")

        override suspend fun refreshAfterRefusal(): ForcedRefresh {
            refreshes++
            return if (dead) ForcedRefresh.Dead("revoked") else ForcedRefresh.Refreshed
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
            mapOf(ProviderKind.GOOGLE_DRIVE to DriveStoreFactory(transport) { tokens }),
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
                loginName = "me@gmail.com",
                uid = "perm-1",
                provider = ProviderKind.GOOGLE_DRIVE,
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
    fun `a queued note is created on drive and the binding records the head revision`() = runTest {
        bindNote()

        val report = drainer.drain()

        assertEquals(1, report.uploaded)
        assertContentEquals("version one".encodeToByteArray(), server.bytesAt("Inkstone/Lecture.pdf"))
        val binding = assertNotNull(bindings.find(note))
        assertEquals(server.idAt("Inkstone/Lecture.pdf"), binding.remoteOcId, "the file id is what later writes address")
        assertEquals(server.headRevisionAt("Inkstone/Lecture.pdf"), binding.lastSyncedEtag?.value)
        assertEquals(NoteSyncState.SYNCED, notes.states[note.value])
        assertEquals(0, outbox.all().size, "the row must be gone once it has landed")
    }

    @Test
    fun `an edit replaces the file conditionally on the head revision the binding holds`() = runTest {
        bindNote()
        drainer.drain()
        val before = assertNotNull(bindings.find(note)?.lastSyncedEtag)

        edit("version two")
        val report = drainer.drain()

        assertEquals(1, report.uploaded)
        assertContentEquals("version two".encodeToByteArray(), server.bytesAt("Inkstone/Lecture.pdf"))
        val after = assertNotNull(bindings.find(note)?.lastSyncedEtag)
        assertTrue(after != before, "the binding kept the old head")
        assertEquals(server.headRevisionAt("Inkstone/Lecture.pdf"), after.value)
        assertEquals(listOf("Lecture.pdf"), server.namesIn("Inkstone"), "a clean overwrite left a stray file")
    }

    @Test
    fun `an edit made after somebody else changed the file is parked as a conflict and theirs survives`() = runTest {
        bindNote()
        drainer.drain()
        server.writeBehindTheirBack("Inkstone/Lecture.pdf", "theirs".encodeToByteArray())
        val commits = server.contentCommits.count

        edit("mine")
        val report = drainer.drain()

        assertEquals(0, report.uploaded)
        assertEquals(1, report.parked)
        assertEquals(setOf(note), report.conflicted, "the conflict must be reported so a reconcile can be asked for")
        assertEquals(NoteSyncState.CONFLICTED, notes.states[note.value])
        assertContentEquals("theirs".encodeToByteArray(), server.bytesAt("Inkstone/Lecture.pdf"), "rule 1: theirs was overwritten")
        assertEquals(commits, server.contentCommits.count, "bytes were sent after the conflict was found")
        assertEquals(1, outbox.all().size, "the row stays — parked, not dropped (rule 1)")
    }

    @Test
    fun `a writer who lands inside the window is kept and the upload still counts as done`() = runTest {
        bindNote()
        drainer.drain()
        server.beforeCommit = { server.writeBehindTheirBack("Inkstone/Lecture.pdf", "THEIRS".encodeToByteArray()) }

        edit("MINE")
        val report = drainer.drain()

        assertEquals(1, report.uploaded)
        assertContentEquals("MINE".encodeToByteArray(), server.bytesAt("Inkstone/Lecture.pdf"))
        val copy = server.namesIn("Inkstone").single { it.contains("displaced") }
        assertContentEquals("THEIRS".encodeToByteArray(), server.bytesAt("Inkstone/$copy"), "their bytes were not kept")
        assertEquals(server.headRevisionAt("Inkstone/Lecture.pdf"), bindings.find(note)?.lastSyncedEtag?.value)
    }

    @Test
    fun `a refused grant halts the account after one refresh and nothing is sent afterwards`() = runTest {
        tokens.dead = true
        server.rejectAccessTokens = true
        bindNote()

        val first = drainer.drain()

        assertEquals(setOf(ACCOUNT), first.haltedAccounts)
        assertEquals(AccountState.NEEDS_REAUTH, accounts.find(ACCOUNT)?.state)
        assertEquals(ProviderKind.GOOGLE_DRIVE, accounts.find(ACCOUNT)?.provider)
        assertEquals(1, tokens.refreshes)

        clock.advance(MORE_THAN_THE_FLOOR)
        val apiBefore = server.api.count
        drainer.drain()
        assertEquals(apiBefore, server.api.count, "a halted account sent another request")
        assertEquals(1, tokens.refreshes, "a halted account asked for another token")
    }

    private companion object {
        const val ACCOUNT = "gd-1"
        const val MORE_THAN_THE_FLOOR = 31_000L
    }
}
