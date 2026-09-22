package dev.inkstone.app.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.inkstone.app.sync.matrix.CountingScheduler
import dev.inkstone.app.sync.matrix.LocalMatrixServer
import dev.inkstone.app.sync.matrix.MATRIX_ACCOUNT_ID
import dev.inkstone.app.sync.matrix.MatrixClock
import dev.inkstone.app.sync.matrix.MatrixCredentials
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.database.sync.SqlAccountStore
import dev.inkstone.core.database.sync.SqlBindingStore
import dev.inkstone.core.database.sync.SqlBrowsedFolderStore
import dev.inkstone.core.database.sync.SqlOutboxStore
import dev.inkstone.core.database.sync.SqlRecordingUploads
import dev.inkstone.core.database.sync.SqlSyncNotes
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.sync.AccountState
import dev.inkstone.core.sync.NextcloudTransport
import dev.inkstone.core.sync.OutboxOpType
import dev.inkstone.core.sync.SyncAccount
import dev.inkstone.core.sync.SyncEngine
import dev.inkstone.core.sync.SyncStores
import dev.inkstone.core.sync.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⭐ The folder browser with no network — SPEC §7.6 step 2, offline.
 *
 * The complaint this answers: *"you have to be connected to the wifi to set an upload path, since
 * it does not remember the file structure of the server"*. Nothing about **choosing** a path needs
 * a network — [SyncEngine.bind] writes three rows and enqueues MKCOL then UPLOAD without opening a
 * socket, and the MKCOL is unconditional precisely so the folder need not exist yet — so the only
 * part that ever required one was seeing the names.
 *
 * ## Why this is not a fake
 *
 * Everything below the test is production: the real [NextcloudAccounts], the real
 * [NextcloudTransport] (OkHttp), the real [WebDavClient] building SPEC §7.3's PROPFIND, the real
 * multistatus parser, and the real [SqlBrowsedFolderStore] over a real SQLite. The only stand-in
 * is the *server*, and it is a socket rather than a mock — see `MatrixServer`.
 *
 * ⚠️ The offline half is produced by **closing the server**, not by injecting a failure. A test
 * that stubbed `DavFailure.Transport` would agree with whatever this app believes offline looks
 * like; a closed socket is the operating system's opinion instead. (CLAUDE.md: *"a mock of a
 * client agrees with every mistake that client makes"*.)
 */
class OfflineFolderBrowserTest {

    private val server = LocalMatrixServer()
    private val clock = MatrixClock()
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = InkstoneDatabase(driver).also { InkstoneDatabase.Schema.create(driver) }
    private val dispatcher = Dispatchers.Unconfined

    private val accountStore = SqlAccountStore(database, dispatcher)
    private val bindings = SqlBindingStore(database, dispatcher)
    private val outbox = SqlOutboxStore(database, dispatcher)
    private val notes = SqlSyncNotes(database, dispatcher)
    private val recordings = SqlRecordingUploads(database, dispatcher)
    private val cache = SqlBrowsedFolderStore(database, dispatcher)
    private val transport = NextcloudTransport.create()

    private val engine = SyncEngine(
        SyncStores(accountStore, bindings, outbox, notes, recordings),
        clock,
        CountingScheduler(),
    )

    private val accounts = NextcloudAccounts(
        transport = transport,
        accounts = accountStore,
        credentials = MatrixCredentials(server.credential),
        bindings = bindings,
        sync = engine,
        clock = clock,
        folderCache = cache,
    )

    /** The third party — how folders get onto the server without going through the browser. */
    private val raw = WebDavClient(NextcloudTransport.create(), server.credential)

    @AfterTest
    fun tearDown() {
        server.close()
        driver.close()
    }

    @Test
    fun `folders seen while connected are still offered when the server cannot be reached`() = runTest {
        signIn()
        raw.mkcolRecursive("Documents/Physics")
        raw.mkcolRecursive("Documents/Chemistry")

        val live = assertIs<NextcloudAccounts.BrowseResult.Listing>(accounts.browse(MATRIX_ACCOUNT_ID, "Documents"))
        assertNull(live.remembered, "a listing off the wire is not a remembered one")
        assertEquals(listOf("Chemistry", "Physics"), live.folders.map { it.name })

        server.close()
        clock.advance(TWO_HOURS)

        val offline = assertIs<NextcloudAccounts.BrowseResult.Listing>(accounts.browse(MATRIX_ACCOUNT_ID, "Documents"))
        val remembered = assertNotNull(offline.remembered, "an unreachable server must fall back, not fail")
        assertEquals(
            listOf("Chemistry", "Physics"),
            offline.folders.map { it.name },
            "the same folders, from the cache",
        )
        assertEquals(
            listOf("Documents/Chemistry", "Documents/Physics"),
            offline.folders.map { it.path },
            "and their real remote paths, or nothing could be bound to them",
        )
        assertEquals(clock.now - TWO_HOURS, remembered.listedAtEpochMillis, "with when they were seen")
        assertEquals(
            "2 hours ago",
            OfflineBrowsing.ago(remembered.listedAtEpochMillis, clock.now),
            "which is what the screen says",
        )
    }

    /**
     * ⚠️ The whole point of the cache is that the *choice* still lands, so this asserts the
     * binding and its queued work, not just the drawing. A picker that lists folders you cannot
     * pick is worse than one that lists none.
     */
    @Test
    fun `a folder made offline is offered marked and can be picked`() = runTest {
        signIn()
        raw.mkcolRecursive("Documents")
        accounts.browse(MATRIX_ACCOUNT_ID, "Documents")

        server.close()
        val created = accounts.createRemoteFolder(MATRIX_ACCOUNT_ID, "Documents/Term 2")
        assertEquals(
            NextcloudAccounts.FolderCreateResult.Deferred,
            created,
            "no network is not a refusal — the binding's MKCOL is what creates it",
        )

        val offline = assertIs<NextcloudAccounts.BrowseResult.Listing>(accounts.browse(MATRIX_ACCOUNT_ID, "Documents"))
        val made = assertNotNull(
            offline.folders.firstOrNull { it.name == "Term 2" },
            "the folder the user just asked for has to be in the list they are choosing from",
        )
        assertTrue(made.pending, "and marked, because it is not on the server yet")

        // Its own level opens, empty, rather than reporting a failure — a new folder has no
        // children, and that is a sentence the browser has to be able to say offline.
        val inside = assertIs<NextcloudAccounts.BrowseResult.Listing>(accounts.browse(MATRIX_ACCOUNT_ID, made.path))
        assertEquals(emptyList(), inside.folders.map { it.name })
        assertNotNull(inside.remembered)

        // ⭐ And the choice lands. This is the claim the whole feature is for: the note is bound
        // to a folder that exists nowhere but in this cache, and the MKCOL that will create it is
        // already queued — with the server still shut.
        val note = DocumentId("00000000-0000-7000-8000-0000000000ff")
        val binding = assertNotNull(
            engine.bind(note, MATRIX_ACCOUNT_ID, title = "Term 2 notes", remoteFolder = made.path),
        )
        assertEquals("Documents/Term 2/Term 2 notes.pdf", binding.remotePath)
        assertEquals(
            setOf(OutboxOpType.MKCOL, OutboxOpType.UPLOAD),
            outbox.due(clock.now, limit = 10).filter { it.noteId == note }.map { it.opType }.toSet(),
            "the folder is created by the binding's own MKCOL, which is why nothing is queued when " +
                "the folder is made",
        )
    }

    /**
     * ⚠️ A 404 is the server *answering*, and answering about this very path. Serving the cache
     * there would contradict something we have just been told; the fallback is only for "could
     * not be told at all".
     */
    @Test
    fun `a path the server says is not there is not answered from the cache`() = runTest {
        signIn()
        raw.mkcolRecursive("Documents/Physics")
        accounts.browse(MATRIX_ACCOUNT_ID, "Documents")

        val gone = accounts.browse(MATRIX_ACCOUNT_ID, "Documents/Nowhere")
        assertIs<NextcloudAccounts.BrowseResult.Failed>(gone, "a 404 is reported, never remembered")
    }

    /**
     * ⭐ A folder deleted on the server stops being offered.
     *
     * The cache is swept by the listing that contradicts it, which is the only moment anything
     * here can know. ⚠️ Not the `pending` rows, though — those are absent from the server
     * *because* their MKCOL has not run, and sweeping one would take away a destination the user
     * has already chosen.
     */
    @Test
    fun `a folder that has gone from the server stops being offered offline`() = runTest {
        signIn()
        raw.mkcolRecursive("Documents/Physics")
        raw.mkcolRecursive("Documents/Chemistry")
        accounts.browse(MATRIX_ACCOUNT_ID, "Documents")

        raw.delete("Documents/Chemistry")
        clock.advance(ONE_MINUTE)
        accounts.browse(MATRIX_ACCOUNT_ID, "Documents")

        server.close()
        val offline = assertIs<NextcloudAccounts.BrowseResult.Listing>(accounts.browse(MATRIX_ACCOUNT_ID, "Documents"))
        assertEquals(listOf("Physics"), offline.folders.map { it.name })
    }

    private suspend fun signIn() {
        accountStore.upsert(
            SyncAccount(
                id = MATRIX_ACCOUNT_ID,
                serverUrl = server.credential.serverUrl,
                loginName = server.credential.loginName,
                uid = server.credential.uid,
                state = AccountState.ACTIVE,
            ),
        )
    }

    private companion object {
        const val ONE_MINUTE = 60_000L
        const val TWO_HOURS = 2 * 60 * 60 * 1000L
    }
}
