package dev.inkstone.app.sync.matrix

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.inkstone.android.pdf.InkstonePdfMaterializer
import dev.inkstone.android.pdf.PulledPdfExtractor
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.database.library.LibraryIndex
import dev.inkstone.core.database.recording.RecordingIndex
import dev.inkstone.core.database.sync.SqlAccountStore
import dev.inkstone.core.database.sync.SqlAttentionStore
import dev.inkstone.core.database.sync.SqlBindingStore
import dev.inkstone.core.database.sync.SqlFolderBindingStore
import dev.inkstone.core.database.sync.SqlForeignRemoteStore
import dev.inkstone.core.database.sync.SqlOutboxStore
import dev.inkstone.core.database.sync.SqlPeerClockStore
import dev.inkstone.core.database.sync.SqlPendingDownloadStore
import dev.inkstone.core.database.sync.SqlRecordingUploads
import dev.inkstone.core.database.sync.SqlRemoteDirectoryStore
import dev.inkstone.core.database.sync.SqlSyncCursorStore
import dev.inkstone.core.database.sync.SqlSyncNotes
import dev.inkstone.core.model.Document
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.DocumentMaterializer
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.NoteSummary
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PdfPublisher
import dev.inkstone.core.storage.DeviceIdentity
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.storage.NoteSession
import dev.inkstone.core.storage.NoteStore
import dev.inkstone.core.sync.AttentionEntry
import dev.inkstone.core.sync.CloudStoreFactory
import dev.inkstone.core.sync.DavOutcome
import dev.inkstone.core.sync.DrainReport
import dev.inkstone.core.sync.NextcloudStoreFactory
import dev.inkstone.core.sync.NextcloudTransport
import dev.inkstone.core.sync.OutboxDrainer
import dev.inkstone.core.sync.OutboxEntry
import dev.inkstone.core.sync.ReconcileReport
import dev.inkstone.core.sync.ReconcileStores
import dev.inkstone.core.sync.Reconciler
import dev.inkstone.core.sync.RemoteBinding
import dev.inkstone.core.sync.SyncAccount
import dev.inkstone.core.sync.SyncEngine
import dev.inkstone.core.sync.SyncStores
import dev.inkstone.core.sync.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/*
 * ⭐ SPEC §13.2's fixture: two tablets on one Nextcloud account.
 *
 * Two *independent* stacks — own temp directory, own `NoteStore`, own per-install `deviceId`, own
 * `InkstoneDatabase`, own stores, own `Reconciler`, own `OutboxDrainer` — sharing nothing but the
 * bytes that pass between them over HTTP. That is exactly the relationship two tablets have, and
 * it is what makes the matrix's convergence claims mean anything: a fixture that shared a store
 * would prove that one fold is deterministic, which nobody doubts.
 *
 * `IdFactory.newDeviceId()` is UUIDv7 with random bits, so the two devices get distinct ids even
 * on one fixed clock — and the *whole* Lamport tiebreak depends on that (SPEC §4.1; the M6 brief's
 * pre-work item zero).
 *
 * Block comment, not KDoc: a file-level KDoc attaches to no declaration.
 */

/** The account a [Device] syncs to and the factory that opens it, when it is not the Nextcloud default. */
internal class DeviceWiring(val account: SyncAccount, val factory: CloudStoreFactory)

/** One tablet: everything below the UI, all of it real. */
@Suppress("TooManyFunctions")
// The vocabulary one row of the matrix needs of a device — create, edit, bind, drain, pull, and
// then read back each of the five places a claim can live (the fold, the document, the binding,
// the library row, the attention list). Fewer functions would mean rows reaching into the stores
// directly, and a row that assembles its own query is a row that can assert the wrong thing.
internal class Device(
    val name: String,
    private val server: MatrixServer,
    /** Where this device's notes go. Unique per fixture, so two runs cannot collide. */
    val remoteFolder: String,
    val clock: MatrixClock,
    private val root: Path,
    /** docs/CLOUD-PROVIDERS.md — null is Nextcloud, which is what every existing row uses. */
    private val wiring: DeviceWiring? = null,
) {

    val fs: TempFileSystem = TempFileSystem(root)
    val ids: IdFactory = IdFactory(clock, Random(name.hashCode()))
    val store: NoteStore = NoteStore(fs, clock, ids)

    private val identity = DeviceIdentity(fs, ids)
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = InkstoneDatabase(driver).also { InkstoneDatabase.Schema.create(driver) }
    private val dispatcher = Dispatchers.Unconfined

    val index: LibraryIndex = LibraryIndex(database, dispatcher)
    val accounts: SqlAccountStore = SqlAccountStore(database, dispatcher)
    val bindings: SqlBindingStore = SqlBindingStore(database, dispatcher)
    val outbox: SqlOutboxStore = SqlOutboxStore(database, dispatcher)
    val notes: SqlSyncNotes = SqlSyncNotes(database, dispatcher)
    val directories: SqlRemoteDirectoryStore = SqlRemoteDirectoryStore(database, dispatcher)
    val cursors: SqlSyncCursorStore = SqlSyncCursorStore(database, dispatcher)
    val downloads: SqlPendingDownloadStore = SqlPendingDownloadStore(database, dispatcher)
    val attentionLog: SqlAttentionStore = SqlAttentionStore(database, dispatcher)
    val folderBindings: SqlFolderBindingStore = SqlFolderBindingStore(database, dispatcher)
    val peers: SqlPeerClockStore = SqlPeerClockStore(database, dispatcher)
    val foreignFiles: SqlForeignRemoteStore = SqlForeignRemoteStore(database, dispatcher)

    /**
     * M12 — the two halves of the recording table, both real.
     *
     * [recordings] is what the drainer reads; [recordingIndex] is what `:app` writes with. A test
     * that made a recording by inserting a row by hand would be testing its own SQL, so the
     * matrix's media row goes through the same index the app does.
     */
    val recordings: SqlRecordingUploads = SqlRecordingUploads(database, dispatcher)
    val recordingIndex: RecordingIndex = RecordingIndex(database, dispatcher)

    val credentials: MatrixCredentials = MatrixCredentials(server.credential)
    val scheduler: CountingScheduler = CountingScheduler()

    private val syncStores = SyncStores(accounts, bindings, outbox, notes, recordings)
    private val pullStores =
        ReconcileStores(directories, cursors, downloads, attentionLog, folderBindings, peers, foreignFiles)

    val engine: SyncEngine = SyncEngine(syncStores, clock, scheduler)
    val gate: MatrixGate = MatrixGate(fs, clock, store, ids, index) { deviceId() }

    /** The real one. PdfBox runs as plain Java here — see `:app`'s `isReturnDefaultValues`. */
    val publisher: PdfPublisher = InkstonePdfMaterializer(fs, store, MatrixOutliner, dispatcher)

    /**
     * What the drainer materializes through. Swappable, for row 18's mid-flight edit.
     *
     * ⚠️ **A degenerate gate, and the gap M6's gate-5 run fell into.** In production this is
     * `NoteSessionHost`: a per-note mutex plus a registry of live editors, so publishing a note the
     * user has **open** is handed to that editor's own session. There is no editor here and no
     * mutex, so **no row in this harness can model an open note** — which is exactly the state where
     * the upload path published behind the editor and recorded a `lastSyncedRev` the server did not
     * have. Two emulators found it; 28 green rows did not. Read a pass here as *"the drainer's own
     * ordering is right"*, never as *"publishing is safe while the note is open"*.
     */
    val publishDirect: DocumentMaterializer = DocumentMaterializer { publisher.publish(it) }

    var materializer: DocumentMaterializer = publishDirect

    /**
     * The transport is `NextcloudTransport.create()` — the production one, real OkHttp.
     *
     * There is no way to give it a Ktor engine from here (its constructor is `internal`, which is
     * the point), and that is why the local server is a real socket rather than a `MockEngine`.
     */
    private val transport: NextcloudTransport = NextcloudTransport.create()

    private val storeFactory: CloudStoreFactory = wiring?.factory ?: NextcloudStoreFactory(credentials, transport)

    val reconciler: Reconciler = Reconciler(
        stores = syncStores,
        pull = pullStores,
        credentials = credentials,
        fs = fs,
        clock = clock,
        transport = transport,
        extractor = PulledPdfExtractor(fs, dispatcher),
        gate = gate,
        engine = engine,
        storeFactory = storeFactory,
    )

    /** Zero jitter, so a backoff assertion is about the ladder and not about a random number. */
    val drainer: OutboxDrainer
        get() = OutboxDrainer(syncStores, credentials, materializer, fs, clock, transport, storeFactory) { 0.0 }

    suspend fun start() {
        accounts.upsert(
            wiring?.account ?: SyncAccount(
                id = MATRIX_ACCOUNT_ID,
                serverUrl = server.credential.serverUrl,
                loginName = server.credential.loginName,
                uid = server.credential.uid,
                defaultFolder = remoteFolder,
            ),
        )
    }

    suspend fun deviceId(): String = identity.get()

    // -----------------------------------------------------------------------
    // Notes
    // -----------------------------------------------------------------------

    /** A new note, on disk and in the library index, exactly as the library would create it. */
    suspend fun createNote(title: String, pageCount: Int = 1): DocumentId {
        val note = store.create(title, deviceId(), pageCount = pageCount)
        val now = clock.nowEpochMillis()
        index.insertNote(
            NoteSummary(
                id = note.document.id,
                title = title,
                pageCount = pageCount,
                localRev = note.document.localRev,
                createdEpochMillis = now,
                modifiedEpochMillis = now,
            ),
        )
        return note.document.id
    }

    /**
     * Open a session, run [block], flush, materialize, and tell the library — which is what
     * `LibraryRepository.noteSaved` does, including the `SyncEngine.noteChanged` at the end.
     *
     * ⚠️ `session.materialize()` writes the *bundle*. The published PDF is produced by the
     * **drainer**, immediately before the PUT, so that `document.pdf` can never be one session
     * stale on the wire (SPEC §5.1). Publishing here as well would hide that.
     */
    suspend fun <T> edit(noteId: DocumentId, block: suspend (NoteSession, PageId) -> T): T {
        val session = session(noteId)
        val result = block(session, firstPage(noteId))
        session.flush()
        session.materialize()
        index.recordSave(noteId, session.document.localRev, session.pageCount, clock.nowEpochMillis())
        engine.noteChanged(noteId)
        return result
    }

    suspend fun session(noteId: DocumentId): NoteSession {
        val note = assertNotNull(store.open(noteId), "$name cannot open ${noteId.value}")
        return NoteSession.open(store, ids, clock, note, deviceId())
    }

    suspend fun firstPage(noteId: DocumentId): PageId =
        assertNotNull(document(noteId), "$name has no ${noteId.value}").livePages.first().id

    suspend fun document(noteId: DocumentId): Document? = store.open(noteId)?.document

    suspend fun page(noteId: DocumentId, pageId: PageId): FoldedPage = session(noteId).page(pageId)

    suspend fun summary(noteId: DocumentId): NoteSummary? = index.note(noteId)

    suspend fun binding(noteId: DocumentId): RemoteBinding? = bindings.find(noteId)

    suspend fun attention(): List<AttentionEntry> = attentionLog.open()

    suspend fun outboxRows(): List<OutboxEntry> = outbox.all()

    // -----------------------------------------------------------------------
    // Sync
    // -----------------------------------------------------------------------

    /** *"Save to Nextcloud…"* — a binding plus MKCOL plus UPLOAD, no request (CLAUDE.md rule 4). */
    suspend fun bind(noteId: DocumentId, title: String) {
        engine.bind(noteId, MATRIX_ACCOUNT_ID, title, remoteFolder)
    }

    /**
     * Drain the outbox until nothing is due, up to [rounds].
     *
     * A loop because SPEC §7.6 orders MKCOL before UPLOAD and the drainer takes one pass over
     * what is *due*: a first pass performs the MKCOL and the UPLOAD, but a row that deferred on
     * the 30 s floor needs the clock moved and another pass. Returns the last report.
     */
    suspend fun drain(rounds: Int = 4): DrainReport {
        var report = drainer.drain()
        var remaining = rounds - 1
        while (remaining > 0 && report.shouldRunAgain) {
            clock.advance(THIRTY_ONE_SECONDS)
            report = drainer.drain()
            remaining--
        }
        return report
    }

    /** One reconcile pass. `advance` first so the pass-start stamp is strictly after the last. */
    suspend fun pull(limit: Int = DEFAULT_LIMIT): ReconcileReport {
        clock.advance(1L)
        return reconciler.reconcile(limit)
    }

    fun close() {
        transport.close()
        driver.close()
        recursivelyDelete(root)
    }

    private companion object {
        const val THIRTY_ONE_SECONDS = 31_000L
        const val DEFAULT_LIMIT = 25
    }
}

/**
 * Two devices, one account, and the raw client that stands in for the Nextcloud web UI.
 *
 * @property raw the third party. Rows 11, 13 and 14 are about what happens when *something else*
 *   moves, edits or deletes the file — the user in a browser, or a second app — and the only
 *   honest way to write that is with a client neither device owns.
 */
internal class TwoDevices private constructor(
    val server: MatrixServer,
    val clock: MatrixClock,
    val remoteFolder: String,
    private val roots: List<Path>,
) : AutoCloseable {

    val a: Device = Device("device-a", server, remoteFolder, clock, roots[0])
    val b: Device = Device("device-b", server, remoteFolder, clock, roots[1])

    private val rawTransport = NextcloudTransport.create()
    val raw: WebDavClient = WebDavClient(rawTransport, server.credential)

    /**
     * ⭐ A folder **no walk root can reach** — row 11b's destination.
     *
     * A *sibling* of [remoteFolder], and that is the whole point rather than tidiness.
     * [remoteFolder] is the account's default folder *and* the parent of every binding the fixture
     * makes, so `RemoteWalk.rootsOf` makes it a root and everything inside it is under one by
     * construction. Only a sibling is somewhere the walk has no reason to look, which is the case
     * SPEC §7.4 rule 2 cannot see.
     */
    val outsideFolder: String get() = "$remoteFolder-outside"

    private var madeOutsideFolder = false

    /** MKCOL [outsideFolder] once, so a live `MOVE` has a destination that exists. */
    suspend fun makeOutsideFolder(): String {
        if (!madeOutsideFolder) {
            raw.mkcolRecursive(outsideFolder)
            madeOutsideFolder = true
        }
        return outsideFolder
    }

    suspend fun start() {
        a.start()
        b.start()
        raw.mkcolRecursive(remoteFolder)
        resetCounts()
    }

    fun resetCounts() {
        server.requestCounts?.reset()
    }

    /**
     * Assert a request count — now on both servers, which is what [CountingProxy] was for.
     *
     * ⚠️ Never silently passes. The unobservable branch is still here and still prints, because a
     * count nobody checked must be visible as such in the output; swallowing the difference is how a
     * suite starts reporting success for things it did not test.
     */
    fun assertRequests(method: String, expected: Int, message: String) {
        val counts = server.requestCounts
        if (counts == null) {
            println("    (live) request counts not observable — unchecked: $method == $expected ($message)")
            return
        }
        assertEquals(expected, counts[method], "$message [all counts: $counts]")
    }

    /**
     * Assert how many requests of [method] carried [header].
     *
     * The two rules that are about a header's **absence** — no `If-Match` for a weak validator
     * (SPEC §7.3) and nothing at all after a 401 (§7.2) — are otherwise asserted one layer too
     * early, on a getter rather than on the wire.
     */
    fun assertRequestsWithHeader(method: String, header: String, expected: Int, message: String) {
        val counts = server.requestCounts
        if (counts == null) {
            println("    (live) headers not observable — unchecked: $method $header == $expected ($message)")
            return
        }
        assertEquals(expected, counts.withHeader(method, header), "$message [all counts: $counts]")
    }

    /** Every request of every method — row 20's *"exactly two, and then nothing"*. */
    fun assertTotalRequests(expected: Int, message: String) {
        val counts = server.requestCounts
        if (counts == null) {
            println("    (live) request counts not observable — unchecked: total == $expected ($message)")
            return
        }
        assertEquals(expected, counts.total, "$message [all counts: $counts]")
    }

    fun requests(method: String): Int = server.requestCounts?.get(method) ?: -1

    // -----------------------------------------------------------------------
    // The third party
    // -----------------------------------------------------------------------

    suspend fun rawBytes(remotePath: String): ByteArray? {
        val local = server as? LocalMatrixServer
        if (local != null) return local.bytesAt(remotePath)
        return (raw.get(remotePath) as? DavOutcome.Ok)?.value
    }

    /** Overwrite a file with no preconditions, the way a browser upload does. */
    suspend fun rawWrite(remotePath: String, bytes: ByteArray) {
        val local = server as? LocalMatrixServer
        if (local != null) return local.writeDirectly(remotePath, bytes)
        raw.put(remotePath, bytes)
    }

    /** ⭐ SPEC §7.4 rule 2 — a MOVE keeps the `oc:id` **and** the ETag. Row 11. */
    suspend fun rawMove(from: String, to: String) {
        val local = server as? LocalMatrixServer
        if (local != null) {
            local.moveDirectly(from, to)
            return
        }
        raw.move(from, to)
    }

    /** Soft on Nextcloud (SPEC §7.7): the bytes are in the server's trash, not gone. */
    suspend fun rawDelete(remotePath: String) {
        val local = server as? LocalMatrixServer
        if (local != null) {
            local.deleteDirectly(remotePath)
            return
        }
        raw.delete(remotePath)
    }

    /**
     * The MD5 the **server** publishes in `oc:checksums`, through a real PROPFIND on both servers.
     *
     * SPEC §7.3's fallback compares this against `RemoteBinding.lastChecksum`, and *"Nextcloud
     * verifies it"* is only half the claim — the other half is that it hands it back, which is what
     * row 16 needs and what nothing else here reads.
     */
    suspend fun remoteChecksum(remotePath: String): String? =
        ((raw.propfind(remotePath, WebDavClient.Depth.Zero) as? DavOutcome.Ok)?.value)
            ?.firstOrNull()?.checksum("md5")

    /**
     * ⭐ M12 — the `Content-Type` the PUT that stored [remotePath] declared.
     *
     * In-process only. Live, this would need `d:getcontenttype` in the PROPFIND body, and
     * widening the walk's XML for one assertion is a change to the code path every reconcile
     * pass takes — a poor trade against a claim the local server can make honestly. Null is
     * reported as unchecked by the caller rather than passing quietly, which is the same rule
     * [assertRequests] follows.
     */
    fun declaredContentType(remotePath: String): String? = (server as? LocalMatrixServer)?.contentTypeAt(remotePath)

    suspend fun rawOcId(remotePath: String): String? {
        val local = server as? LocalMatrixServer
        if (local != null) return local.ocIdAt(remotePath)
        return ((raw.propfind(remotePath, WebDavClient.Depth.Zero) as? DavOutcome.Ok)?.value)
            ?.firstOrNull()?.ocId
    }

    suspend fun remoteExists(remotePath: String): Boolean {
        val local = server as? LocalMatrixServer
        if (local != null) return remotePath in local.paths()
        return (raw.exists(remotePath) as? DavOutcome.Ok)?.value == true
    }

    override fun close() {
        a.close()
        b.close()
        // ⚠️ Before anything else: a row that failed part way through may have left the credential
        // switch on, and then this teardown's own DELETE would 401 — leaving the folder behind *and*
        // spending another of the server's brute-force attempts on a request nobody asked for.
        server.refuseAuthorization(false)
        // On a live server the fixture's own folders are removed, so a hundred runs do not leave a
        // hundred conflicted copies behind. `DELETE` is soft, so the bytes are still recoverable
        // from the server's trash if a failure needs looking at (CLAUDE.md rule 1).
        //
        // ⚠️ [outsideFolder] is a *sibling*, so deleting [remoteFolder] does not take it with it.
        if (server.isLive) {
            cleanUp(remoteFolder)
            if (madeOutsideFolder) cleanUp(outsideFolder)
        }
        rawTransport.close()
        server.close()
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    // Best-effort cleanup after the assertions have already run or failed. Letting this throw would
    // replace a real failure with a teardown one.
    private fun cleanUp(remoteFolder: String) {
        try {
            kotlinx.coroutines.runBlocking { raw.delete(remoteFolder) }
        } catch (ignored: Throwable) {
            println("  (live) could not clean up $remoteFolder — remove it by hand")
        }
    }

    companion object {

        /** Unique per fixture, so two test tasks or two runs never share a remote path. */
        private fun uniqueFolder(): String =
            "INKSTONE-M6-MATRIX/run-${System.currentTimeMillis()}-${Random.nextInt(FOLDER_SUFFIX_BOUND)}"

        suspend fun local(): TwoDevices =
            TwoDevices(LocalMatrixServer(), MatrixClock(), uniqueFolder(), tempRoots()).also { it.start() }

        suspend fun live(live: LiveNextcloud): TwoDevices =
            TwoDevices(LiveMatrixServer(live), MatrixClock(), uniqueFolder(), tempRoots()).also { it.start() }

        private fun tempRoots(): List<Path> = List(2) { Files.createTempDirectory("inkstone-matrix-device-$it-") }

        private const val FOLDER_SUFFIX_BOUND = 1_000_000
    }
}

@OptIn(ExperimentalPathApi::class)
private fun recursivelyDelete(root: Path) {
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    // A temp directory that will not delete is the OS holding a mapped PDF open on Windows. It
    // costs disk in a build directory and nothing else; failing the test for it would replace a
    // real result with a housekeeping one.
    try {
        root.deleteRecursively()
    } catch (ignored: Throwable) {
        println("  could not delete $root")
    }
}

/**
 * ⭐ One row of SPEC §13.2, run twice: in-process always, against the real server when there is
 * one.
 *
 * The same body both times, on purpose. A local-only body and a live-only body would drift, and
 * the one that drifted would be the one still passing.
 *
 * @param row the row number, so the output can be read against SPEC's table.
 * @param proven what the in-process run establishes. **Must be true** — this string is what the
 *   skip message tells the reader they still have.
 * @param notProven what only a real Nextcloud settles.
 * @param timeout explicit, because `runTest`'s default is 60 s and a suite that waits on a real
 *   server over a real network will exceed it on the first large upload.
 */
internal fun matrixRow(
    row: String,
    proven: String,
    notProven: String,
    timeout: Duration = 4.minutes,
    body: suspend (TwoDevices) -> Unit,
) = runTest(timeout = timeout) {
    println("SPEC 13.2 $row — in-process server")
    TwoDevices.local().use { body(it) }

    val live = LiveNextcloud.find()
    when {
        live == null -> announceNoLiveServer(row, proven, notProven)

        !MatrixVariant.maysDriveLiveServer ->
            println("SPEC 13.2 $row — live half skipped for ${MatrixVariant.describe} (the debug task runs it)")

        else -> {
            println("SPEC 13.2 $row — LIVE: ${live.credential.serverUrl}")
            TwoDevices.live(live).use { body(it) }
        }
    }
}

/** The published PDF of [noteId] on [device], as the server would have received it. */
internal suspend fun Device.publishedBytes(noteId: DocumentId): ByteArray = fs.readBytes(NoteLayout.documentPdf(noteId))
