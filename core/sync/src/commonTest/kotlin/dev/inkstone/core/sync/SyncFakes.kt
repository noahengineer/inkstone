package dev.inkstone.core.sync

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.CredentialStore
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.DocumentMaterializer
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.MaterializedFile
import dev.inkstone.core.model.NextcloudCredential
import dev.inkstone.core.model.NoteSyncState
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

internal class FakeOutboxStore : OutboxStore {
    val rows = LinkedHashMap<Pair<String, OutboxOpType>, OutboxEntry>()

    override suspend fun mutate(noteId: DocumentId, plan: (List<OutboxEntry>) -> OutboxPlan) {
        val pending = rows.values.filter { it.noteId == noteId }
        val decided = plan(pending)
        for (opType in decided.removals) rows.remove(noteId.value to opType)
        for (entry in decided.upserts) rows[entry.noteId.value to entry.opType] = entry
    }

    override suspend fun due(nowEpochMillis: Long, limit: Int): List<OutboxEntry> = rows.values
        .filter { it.nextAttemptAt <= nowEpochMillis }
        .sortedWith(compareBy({ it.opType.drainPriority }, { it.createdAt }))
        .take(limit)

    override suspend fun pendingFor(noteId: DocumentId): List<OutboxEntry> = rows.values.filter { it.noteId == noteId }

    override suspend fun all(): List<OutboxEntry> = rows.values.toList()

    override suspend fun reschedule(noteId: DocumentId, opType: OutboxOpType, attempts: Int, nextAttemptAt: Long) {
        val key = noteId.value to opType
        rows[key]?.let { rows[key] = it.copy(attempts = attempts, nextAttemptAt = nextAttemptAt) }
    }

    override suspend fun remove(noteId: DocumentId, opType: OutboxOpType) {
        rows.remove(noteId.value to opType)
    }

    override suspend fun count(): Int = rows.size

    fun upload(noteId: DocumentId): OutboxEntry? = rows[noteId.value to OutboxOpType.UPLOAD]
}

internal class FakeBindingStore(vararg initial: RemoteBinding) : BindingStore {
    val rows = LinkedHashMap<String, RemoteBinding>().apply {
        initial.forEach { put(it.noteId.value, it) }
    }

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
    }
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
