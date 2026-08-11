package dev.inkstone.core.sync

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.CredentialStore
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.DocumentMaterializer
import dev.inkstone.core.model.ExtractedRemote
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FoldOutcome
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.model.MaterializedFile
import dev.inkstone.core.model.NextcloudCredential
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.model.RemoteBundleExtractor
import dev.inkstone.core.storage.MergeReport
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/*
 * In-memory stand-ins for M5's ports.
 *
 * These exist so SPEC §7.6's *semantics* can be tested exhaustively without a database or
 * a device — which is the whole reason the ports are declared in `:core:sync` rather than
 * in `:core:database`. The SQL implementations are one transaction each and are exercised
 * by `:core:database`'s instrumented tests against real Android SQLite.
 *
 * They are deliberately not "smart": `FakeOutboxStore` applies whatever plan it is given
 * and does no coalescing of its own, so a test that passes here is testing
 * [OutboxCoalescing] and not the fake.
 */

internal class FakeClock(var now: Long = 1_000_000L) : Clock {
    override fun nowEpochMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}

/**
 * ⚠️ **The map's key is the table's PRIMARY KEY, and keeping the two in step is not cosmetic.**
 *
 * It was `Pair<String, OutboxOpType>` — exactly as lossy as the two-column key it modelled — so a
 * test asking *"do two recordings survive?"* would have watched the fake collapse them and called
 * that correct. That is the fixture failure CLAUDE.md warns about, inverted: a fixture that is
 * exactly as wrong as production hides nothing **and proves nothing**, and it is the harder one to
 * notice because every existing test stays green.
 *
 * So the key is `(noteId, opType, subject)` here for the same reason it is there.
 */
internal class FakeOutboxStore : OutboxStore {
    val rows = LinkedHashMap<Triple<String, OutboxOpType, String>, OutboxEntry>()

    private fun keyOf(noteId: DocumentId, key: OutboxKey) = Triple(noteId.value, key.opType, key.subject)

    override suspend fun mutate(noteId: DocumentId, plan: (List<OutboxEntry>) -> OutboxPlan) {
        val pending = rows.values.filter { it.noteId == noteId }
        val decided = plan(pending)
        for (key in decided.removals) rows.remove(keyOf(noteId, key))
        for (entry in decided.upserts) rows[keyOf(entry.noteId, entry.key)] = entry
    }

    override suspend fun due(nowEpochMillis: Long, limit: Int): List<OutboxEntry> = rows.values
        .filter { it.nextAttemptAt <= nowEpochMillis }
        .sortedWith(compareBy({ it.opType.drainPriority }, { it.createdAt }))
        .take(limit)

    override suspend fun pendingFor(noteId: DocumentId): List<OutboxEntry> = rows.values.filter { it.noteId == noteId }

    override suspend fun all(): List<OutboxEntry> = rows.values.toList()

    override suspend fun reschedule(noteId: DocumentId, key: OutboxKey, attempts: Int, nextAttemptAt: Long) {
        val mapKey = keyOf(noteId, key)
        rows[mapKey]?.let { rows[mapKey] = it.copy(attempts = attempts, nextAttemptAt = nextAttemptAt) }
    }

    override suspend fun remove(noteId: DocumentId, key: OutboxKey) {
        rows.remove(keyOf(noteId, key))
    }

    override suspend fun removeAll(noteId: DocumentId) {
        rows.keys.removeAll { it.first == noteId.value }
    }

    override suspend fun count(): Int = rows.size

    fun upload(noteId: DocumentId): OutboxEntry? = rows[Triple(noteId.value, OutboxOpType.UPLOAD, NO_SUBJECT)]
}

/**
 * M12's [RecordingUploads], in memory.
 *
 * ⚠️ [find] reproduces the real store's filter — **null once uploaded** — because that is the
 * contract the drainer's crash window depends on, not an implementation detail of the SQL. A fake
 * that answered from the map regardless would let a re-PUT of an uploaded lecture pass.
 */
internal class FakeRecordingUploads(vararg initial: PendingRecording) : RecordingUploads {

    val rows = LinkedHashMap<String, PendingRecording>().apply {
        initial.forEach { put(it.recordingId, it) }
    }

    val uploadedAt = LinkedHashMap<String, Long>()

    override suspend fun find(recordingId: String): PendingRecording? =
        rows[recordingId]?.takeIf { recordingId !in uploadedAt }

    override suspend fun pendingFor(noteId: DocumentId): List<PendingRecording> =
        rows.values.filter { it.noteId == noteId && it.recordingId !in uploadedAt }

    override suspend fun markUploaded(recordingId: String, atEpochMillis: Long) {
        uploadedAt[recordingId] = atEpochMillis
    }
}

/**
 * ⚠️ `lastSeenRemoteAt` is a **second map**, not a field on [RemoteBinding], because that is how
 * the real table works: the column is carried forward inside `upsertBinding` and `stampBindingSeen`
 * is its only writer (Sync.sq). A fake that put it on the row would let an ordinary binding write
 * reset it to zero, and every note would then look absent remotely on the next pass — which is a
 * bug the fake would be hiding rather than reproducing.
 */
internal class FakeBindingStore(vararg initial: RemoteBinding) : BindingStore {
    val rows = LinkedHashMap<String, RemoteBinding>().apply {
        initial.forEach { put(it.noteId.value, it) }
    }

    private val seenAt = LinkedHashMap<String, Long>()

    override suspend fun find(noteId: DocumentId): RemoteBinding? = rows[noteId.value]

    override suspend fun forAccount(accountId: String): List<RemoteBinding> =
        rows.values.filter { it.accountId == accountId }

    override suspend fun pathsOn(accountId: String): Set<String> =
        rows.values.filter { it.accountId == accountId }.mapTo(HashSet()) { it.remotePath }

    override suspend fun upsert(binding: RemoteBinding) {
        rows[binding.noteId.value] = binding
    }

    override suspend fun remove(noteId: DocumentId) {
        rows.remove(noteId.value)
        seenAt.remove(noteId.value)
    }

    override suspend fun findByOcId(remoteOcId: String): List<RemoteBinding> =
        rows.values.filter { it.remoteOcId == remoteOcId }

    override suspend fun findByPath(accountId: String, remotePath: String): List<RemoteBinding> =
        rows.values.filter { it.accountId == accountId && it.remotePath == remotePath }

    override suspend fun stampSeen(noteIds: Collection<DocumentId>, seenAtEpochMillis: Long) {
        for (noteId in noteIds) seenAt[noteId.value] = seenAtEpochMillis
    }

    override suspend fun notSeenSince(accountId: String, sinceEpochMillis: Long): List<StaleBinding> = rows.values
        .filter { it.accountId == accountId && (seenAt[it.noteId.value] ?: 0L) < sinceEpochMillis }
        .map { StaleBinding(it.noteId, it.remotePath) }
}

internal class FakeAccountStore(vararg initial: SyncAccount) : AccountStore {
    val rows = LinkedHashMap<String, SyncAccount>().apply { initial.forEach { put(it.id, it) } }

    override suspend fun all(): List<SyncAccount> = rows.values.toList()

    override suspend fun find(accountId: String): SyncAccount? = rows[accountId]

    override suspend fun upsert(account: SyncAccount) {
        rows[account.id] = account
    }

    override suspend fun setState(accountId: String, state: AccountState, lastError: String?) {
        rows[accountId]?.let { rows[accountId] = it.copy(state = state, lastError = lastError) }
    }

    override suspend fun remove(accountId: String) {
        rows.remove(accountId)
    }
}

internal class FakeSyncNotes(private val revisions: MutableMap<String, Long> = mutableMapOf()) : SyncNotes {
    val states = LinkedHashMap<String, NoteSyncState>()
    val errors = LinkedHashMap<String, String?>()

    override suspend fun localRev(noteId: DocumentId): Long? = revisions[noteId.value]

    override suspend fun setSyncState(noteId: DocumentId, state: NoteSyncState, lastError: String?) {
        states[noteId.value] = state
        errors[noteId.value] = lastError
    }

    fun setRev(noteId: DocumentId, rev: Long) {
        revisions[noteId.value] = rev
    }
}

internal class FakeCredentialStore(vararg initial: Pair<String, NextcloudCredential>) : CredentialStore {
    val rows = LinkedHashMap<String, NextcloudCredential>().apply { initial.forEach { put(it.first, it.second) } }

    override suspend fun put(accountId: String, credential: NextcloudCredential) {
        rows[accountId] = credential
    }

    override suspend fun get(accountId: String): NextcloudCredential? = rows[accountId]

    override suspend fun remove(accountId: String) {
        rows.remove(accountId)
    }
}

/** Just enough to hand the drainer some bytes; only `readBytes` is ever called. */
internal class FakeFileSystem(private val files: MutableMap<String, ByteArray> = mutableMapOf()) : FileSystem {
    override suspend fun exists(path: String): Boolean = path in files

    override suspend fun size(path: String): Long = files[path]?.size?.toLong() ?: -1L

    override suspend fun readBytes(path: String): ByteArray = files[path] ?: ByteArray(0)

    override suspend fun readRange(path: String, offset: Long, length: Int): ByteArray {
        val whole = files[path] ?: return ByteArray(0)
        val start = offset.toInt().coerceAtMost(whole.size)
        return whole.copyOfRange(start, (start + length).coerceAtMost(whole.size))
    }

    override suspend fun writeBytesAtomically(path: String, bytes: ByteArray) {
        files[path] = bytes
    }

    override suspend fun appendBytes(path: String, bytes: ByteArray) {
        files[path] = (files[path] ?: ByteArray(0)) + bytes
    }

    override suspend fun createDirectories(path: String) = Unit

    override suspend fun move(from: String, to: String) {
        files.remove(from)?.let { files[to] = it }
    }

    override suspend fun delete(path: String) {
        files.remove(path)
    }

    override suspend fun list(path: String): List<String> = files.keys.filter { it.startsWith("$path/") }
}

/**
 * Reports whatever revision the note has *now*, which is what the real materializer does:
 * it opens the note, folds the journal, and stamps the result with the revision it found.
 *
 * That is the detail SPEC §7.6's mid-flight guard turns on — the uploaded bytes carry the
 * revision they were built from, not the one the outbox row happened to record.
 */
internal class FakeMaterializer(
    private val notes: FakeSyncNotes,
    private val fs: FakeFileSystem,
    private val bytesFor: (DocumentId, Long) -> ByteArray = { id, rev -> "pdf:${id.value}:$rev".encodeToByteArray() },
) : DocumentMaterializer {
    var materializations = 0
        private set

    override suspend fun materialize(noteId: DocumentId): MaterializedFile {
        materializations++
        val rev = notes.localRev(noteId) ?: 0L
        val path = "notes/${noteId.value}/document.pdf"
        val bytes = bytesFor(noteId, rev)
        fs.writeBytesAtomically(path, bytes)
        return MaterializedFile(path = path, sizeBytes = bytes.size.toLong(), sha256 = "", localRev = rev)
    }
}

// ---------------------------------------------------------------------------
// M6 — the pull side
// ---------------------------------------------------------------------------

internal class FakeDirectoryCache(vararg initial: RemoteDirectory) : DirectoryCache {
    val rows = LinkedHashMap<Pair<String, String>, RemoteDirectory>().apply {
        initial.forEach { put(it.accountId to it.path, it) }
    }

    var clears = 0
        private set

    override suspend fun forAccount(accountId: String): List<RemoteDirectory> =
        rows.values.filter { it.accountId == accountId }

    override suspend fun upsert(directory: RemoteDirectory) {
        rows[directory.accountId to directory.path] = directory
    }

    override suspend fun upsertAll(directories: Collection<RemoteDirectory>) {
        for (directory in directories) upsert(directory)
    }

    override suspend fun clearFor(accountId: String) {
        clears++
        rows.keys.filter { it.first == accountId }.forEach { rows.remove(it) }
    }
}

internal class FakeSyncCursorStore(vararg initial: SyncCursor) : SyncCursorStore {
    val rows = LinkedHashMap<String, SyncCursor>().apply { initial.forEach { put(it.accountId, it) } }

    override suspend fun find(accountId: String): SyncCursor? = rows[accountId]

    override suspend fun upsert(cursor: SyncCursor) {
        rows[cursor.accountId] = cursor
    }
}

internal class FakePendingDownloadStore : PendingDownloadStore {
    val rows = LinkedHashMap<String, PendingDownload>()

    override suspend fun find(noteId: DocumentId): PendingDownload? = rows[noteId.value]

    override suspend fun upsert(download: PendingDownload) {
        rows[download.noteId.value] = download
    }

    override suspend fun remove(noteId: DocumentId) {
        rows.remove(noteId.value)
    }
}

/** SPEC §5.6, negatively: the remote PDFs a pass has already looked inside and rejected. */
internal class FakeForeignRemoteCache : ForeignRemoteCache {
    val rows = LinkedHashMap<Pair<String, String>, ForeignRemote>()

    override suspend fun forAccount(accountId: String): List<ForeignRemote> =
        rows.values.filter { it.accountId == accountId }

    override suspend fun upsert(file: ForeignRemote) {
        rows[file.accountId to file.fileKey] = file
    }
}

internal class FakeAttentionLog : AttentionLog {
    val rows = LinkedHashMap<String, AttentionEntry>()

    override suspend fun open(): List<AttentionEntry> = rows.values.filter { it.isOpen }

    /**
     * ⚠️ Acknowledged rows included, like the real `SELECT … WHERE id = ?`. A fake that filtered
     * them out here would let the dismissal bug pass its own test.
     */
    override suspend fun find(id: String): AttentionEntry? = rows[id]

    /** REPLACE, like `insertAttention` — including over an acknowledgement. */
    override suspend fun add(entry: AttentionEntry) {
        rows[entry.id] = entry
    }

    override suspend fun acknowledge(id: String, acknowledgedAtEpochMillis: Long) {
        rows[id]?.let { rows[id] = it.copy(acknowledgedAtEpochMillis = acknowledgedAtEpochMillis) }
    }

    fun kinds(): List<AttentionKind?> = rows.values.map { it.kindOrNull }
}

internal class FakeFolderBindingStore(vararg initial: FolderBinding) : FolderBindingStore {
    val rows = LinkedHashMap<String, FolderBinding>().apply { initial.forEach { put(it.folderId.value, it) } }

    override suspend fun find(folderId: FolderId): FolderBinding? = rows[folderId.value]

    override suspend fun findByOcId(accountId: String, remoteOcId: String): List<FolderBinding> =
        rows.values.filter { it.accountId == accountId && it.remoteOcId == remoteOcId }

    override suspend fun pathsForAccount(accountId: String): Map<FolderId, String> =
        rows.values.filter { it.accountId == accountId }.associate { it.folderId to it.remotePath }

    override suspend fun upsert(binding: FolderBinding) {
        rows[binding.folderId.value] = binding
    }

    override suspend fun remove(folderId: FolderId) {
        rows.remove(folderId.value)
    }
}

internal class FakePeerClockStore : PeerClockStore {
    val rows = LinkedHashMap<Pair<String, String>, PeerClock>()

    override suspend fun forNote(noteId: DocumentId): List<PeerClock> = rows.values.filter { it.noteId == noteId }

    override suspend fun record(
        noteId: DocumentId,
        peerDeviceId: String,
        highestCounter: Long,
        seenAtEpochMillis: Long,
    ) {
        val key = noteId.value to peerDeviceId
        val existing = rows[key]
        // Monotonic, like the real UPSERT. A fake that let the counter fall would make SPEC §4.7's
        // horizon look safe in a test and unsafe on a device.
        rows[key] = PeerClock(
            noteId = noteId,
            peerDeviceId = peerDeviceId,
            highestCounter = maxOf(existing?.highestCounter ?: 0L, highestCounter),
            seenAtEpochMillis = seenAtEpochMillis,
        )
    }

    override suspend fun oldestAckedCounter(noteId: DocumentId): Long? =
        forNote(noteId).minOfOrNull { it.highestCounter }
}

/**
 * The extractor, scripted per staged path.
 *
 * `:android:pdf` owns the real one and it needs PdfBox and a real published PDF. What the
 * reconciler's own logic turns on is only *which of the four verdicts came back*, and every one of
 * them is reachable from a healthy server — so scripting them is the honest unit under test here.
 */
internal class FakeExtractor(
    private val bundleBytes: ByteArray = "bundle".encodeToByteArray(),
    private val fs: FakeFileSystem? = null,
    private val verdictFor: (String) -> ExtractedRemote,
) : RemoteBundleExtractor {
    val calls = mutableListOf<String>()

    override suspend fun extract(pdfPath: String, bundlePath: String): ExtractedRemote {
        calls += pdfPath
        val verdict = verdictFor(pdfPath)
        if (verdict.bundleWritten) fs?.writeBytesAtomically(bundlePath, bundleBytes)
        return verdict
    }
}

/**
 * `:app`'s single-writer merge gate, recorded.
 *
 * Every method is scripted rather than simulated: this fake stands in for `NoteSession`,
 * `NoteMerge`, `NoteStore` and `LibraryRepository` at once, and simulating any of them here would
 * be testing the simulation. What the reconciler must get right is *which* method it calls, with
 * what, and in what order relative to the binding writes — which is exactly what a recorder shows.
 */
internal class FakeMergeGate(
    private val report: (DocumentId) -> MergeReport = { defaultMerged() },
    private val localRev: Long = 2L,
    private val installs: (RemoteInstall) -> DocumentId? = { it.noteId },
    /**
     * Which id a copy gets, given how many this gate has been asked for including this one.
     *
     * ⚠️ The default mints a **fresh** id per call because the real `conflictedCopyOf` does — a new
     * `DocumentId` is mandatory (SPEC §5.6 inverted: a copy that kept the id would be merged straight
     * back into the note it could not merge with). A fake that answered the same id twice made a
     * second copy indistinguishable from the first, which is precisely how layer 3 minting one per
     * poll looked correct.
     */
    private val copies: (ConflictedCopy, Int) -> DocumentId? = { copy, ordinal ->
        DocumentId("copy-$ordinal-of-${copy.localNoteId?.value ?: "peer"}")
    },
    /**
     * Whether the trash succeeded. False is `NoteSessionHost`'s answer while a live editor holds the
     * note — trashing it then leaves it **unrestorable** (see [RemoteMergeGate.trashRemotelyDeleted]),
     * so the reconciler has to treat a refusal as "later" and not as "done".
     */
    private val trashes: (DocumentId) -> Boolean = { true },
    /**
     * Runs inside [merge], before it answers. The seam a test parks a pass in.
     *
     * A suspending hook rather than a scripted delay because what needs testing is what a *second*
     * pass does while the first one is mid-merge — holding a staged bundle, holding a
     * `pendingDownload` row, and not yet having written a cache row. There is no shorter way to
     * reach that state, and it is the state two overlapping passes corrupt.
     */
    private val onMerge: suspend (DocumentId) -> Unit = {},
) : RemoteMergeGate {
    val merges = mutableListOf<Pair<DocumentId, String>>()
    val installed = mutableListOf<RemoteInstall>()
    val conflicted = mutableListOf<ConflictedCopy>()

    /**
     * The ids handed back, one per copy that was actually made.
     *
     * The count is the assertion for layer 3's idempotence: `conflicted` says how many times the gate
     * was *asked*, and this says how many notes the user ended up with.
     */
    val minted = mutableListOf<DocumentId>()
    val trashed = mutableListOf<DocumentId>()

    /** Asked for, refused. The reconciler must treat these as "not yet", never as "done". */
    val refusedTrash = mutableListOf<DocumentId>()
    val refoldered = mutableListOf<Pair<DocumentId, FolderId>>()

    override suspend fun merge(noteId: DocumentId, bundlePath: String): MergeCommit {
        merges += noteId to bundlePath
        onMerge(noteId)
        return MergeCommit(report(noteId), localRev)
    }

    override suspend fun install(install: RemoteInstall): DocumentId? {
        installed += install
        return installs(install)
    }

    override suspend fun conflictedCopy(copy: ConflictedCopy): DocumentId? {
        conflicted += copy
        return copies(copy, conflicted.size)?.also { minted += it }
    }

    override suspend fun trashRemotelyDeleted(noteId: DocumentId, reason: String): Boolean {
        val trashed = trashes(noteId)
        if (trashed) this.trashed += noteId else refusedTrash += noteId
        return trashed
    }

    override suspend fun noteFolderChanged(noteId: DocumentId, folderId: FolderId) {
        refoldered += noteId to folderId
    }

    companion object {
        /** A merge that found nothing novel — the common case, and the one that must not upload. */
        fun defaultMerged(remoteNovelOps: Int = 0, overriddenTitle: String? = null): MergeReport.Merged =
            MergeReport.Merged(
                remoteNovelOps = remoteNovelOps,
                pagesTouched = emptyList(),
                outcome = FoldOutcome.APPENDED,
                titleOverridden = overriddenTitle != null,
                overriddenTitle = overriddenTitle,
                carried = emptyMap(),
                carriedManifest = emptyMap(),
                // SPEC §5.5 (M7). Zero because this fake merges nothing: recognised text is
                // settled by `NoteSession.reconcileText`, which a fake with no session cannot
                // reach — stated here rather than defaulted, so the next report field is
                // noticed too (the `undoable` lesson, applied to a data class).
                textDropped = 0,
                textAdopted = 0,
                peerDeviceId = "peer-device",
                peerHighestCounter = 41L,
            )
    }
}

/** Every request the client made, so a test can assert on headers it must or must not send. */
internal class RecordedRequests {
    val all = mutableListOf<HttpRequestData>()

    fun of(method: String): List<HttpRequestData> = all.filter { it.method.value == method }

    fun count(method: String): Int = of(method).size
}

/**
 * A [NextcloudTransport] over Ktor's `MockEngine`.
 *
 * SPEC §13 requires the *sync* tests — the merge matrix, fileid churn, brute-force
 * lockout — to run against a real Nextcloud in Docker, and M6 does that. What a mock is
 * genuinely the right tool for is the part tested here: it can emit a weak ETag, a 507, or
 * a 401 on demand, which a healthy server will not do to order.
 */
internal fun mockTransport(
    recorded: RecordedRequests = RecordedRequests(),
    handler: MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
): NextcloudTransport = NextcloudTransport(
    HttpClient(MockEngine) {
        install(UserAgent) { agent = INKSTONE_USER_AGENT }
        expectSuccess = false
        engine {
            addHandler { request ->
                recorded.all += request
                handler(request)
            }
        }
    },
)

internal fun MockRequestHandleScope.xml(body: String, status: HttpStatusCode = HttpStatusCode.MultiStatus) =
    respond(body, status, headersOf("Content-Type", "application/xml"))

internal fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(body, status, headersOf("Content-Type", "application/json"))

internal val testCredential = NextcloudCredential(
    serverUrl = "https://cloud.example.com",
    // Deliberately an email address: SPEC §0.1 #3 calls "loginName is not the WebDAV path
    // segment" the single most likely bug in the project, and it is only visible when the
    // two differ.
    loginName = "noah@example.com",
    uid = "noah",
    appPassword = "app-password-value",
)

internal val testAccount = SyncAccount(
    id = "acct-1",
    serverUrl = testCredential.serverUrl,
    loginName = testCredential.loginName,
    uid = testCredential.uid,
)
