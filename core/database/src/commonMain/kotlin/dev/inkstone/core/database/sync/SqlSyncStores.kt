package dev.inkstone.core.database.sync

import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.sync.AccountState
import dev.inkstone.core.sync.AccountStore
import dev.inkstone.core.sync.BindingStore
import dev.inkstone.core.sync.ETag
import dev.inkstone.core.sync.NextcloudCapabilities
import dev.inkstone.core.sync.OutboxEntry
import dev.inkstone.core.sync.OutboxOpType
import dev.inkstone.core.sync.OutboxPlan
import dev.inkstone.core.sync.OutboxStore
import dev.inkstone.core.sync.RemoteBinding
import dev.inkstone.core.sync.StaleBinding
import dev.inkstone.core.sync.SyncAccount
import dev.inkstone.core.sync.SyncNotes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * SPEC §7.6's tables, behind the ports `:core:sync` declares.
 *
 * ## Why this module depends on `:core:sync` and not the other way round
 *
 * The outbox's *semantics* — coalescing, annihilation, backoff inheritance, the
 * mid-flight `local_rev` guard — are the part of M5 that can be got wrong quietly, and
 * they belong with the protocol in `:core:sync` where they can be tested against a fake
 * without a driver or a device. What is left here is rows and one transaction, which is
 * what this module is for. So `:core:sync` declares [OutboxStore] and this implements it.
 *
 * Both are `:core:*`, so SPEC §2.1's one-way rule (`:app` → `:android:*` → `:core:*`) is
 * untouched — this is an edge *within* core, and it is acyclic: `:core:database` →
 * `:core:sync` → `:core:model`.
 *
 * @param dispatcher where SQL runs. A parameter for the reason `LibraryIndex` gives:
 *   `Dispatchers.IO` does not exist in common code.
 */
public class SqlAccountStore(private val database: InkstoneDatabase, private val dispatcher: CoroutineDispatcher) :
    AccountStore {

    private val queries = database.syncQueries

    override suspend fun all(): List<SyncAccount> =
        withContext(dispatcher) { queries.selectAllAccounts().executeAsList().map { it.toAccount() } }

    override suspend fun find(accountId: String): SyncAccount? =
        withContext(dispatcher) { queries.selectAccount(accountId).executeAsOneOrNull()?.toAccount() }

    override suspend fun upsert(account: SyncAccount): Unit = withContext(dispatcher) {
        queries.upsertAccount(
            id = account.id,
            serverUrl = account.serverUrl,
            loginName = account.loginName,
            uid = account.uid,
            displayName = account.displayName,
            defaultFolder = account.defaultFolder,
            state = account.state.name,
            capabilities = account.capabilities?.let { NextcloudCapabilities.encode(it) },
            lastError = account.lastError,
        )
    }

    override suspend fun setState(accountId: String, state: AccountState, lastError: String?): Unit =
        withContext(dispatcher) { queries.updateAccountState(state.name, lastError, accountId) }

    override suspend fun remove(accountId: String): Unit = withContext(dispatcher) { queries.deleteAccount(accountId) }
}

public class SqlBindingStore(private val database: InkstoneDatabase, private val dispatcher: CoroutineDispatcher) :
    BindingStore {

    private val queries = database.syncQueries

    override suspend fun find(noteId: DocumentId): RemoteBinding? =
        withContext(dispatcher) { queries.selectBinding(noteId.value).executeAsOneOrNull()?.toBinding() }

    override suspend fun forAccount(accountId: String): List<RemoteBinding> =
        withContext(dispatcher) { queries.selectBindingsForAccount(accountId).executeAsList().map { it.toBinding() } }

    override suspend fun pathsOn(accountId: String): Set<String> = withContext(dispatcher) {
        queries.selectBindingsForAccount(accountId).executeAsList().mapTo(HashSet()) { it.remotePath }
    }

    // The four below are M6's. SPEC §7.6 step 3 needs to find a binding by things other than its
    // note, and needs one field of it that RemoteBinding deliberately does not carry.

    /**
     * The note bound to a remote file, by `oc:id` — SPEC §7.6 step 3's match.
     *
     * No account argument: `oc:id` is `<padded-fileid><instanceid>` and therefore globally
     * unique (SPEC §7.3), which is the whole reason this project stores it rather than
     * `oc:fileid`.
     *
     * A list rather than one row, because SPEC §7.4's caveats include one `oc:id` at two paths
     * (a file shared in twice) and `executeAsOne` would throw mid-pass.
     */
    override suspend fun findByOcId(remoteOcId: String): List<RemoteBinding> = withContext(dispatcher) {
        queries.selectBindingByOcId(remoteOcId).executeAsList().map { it.toBinding() }
    }

    /**
     * The secondary key SPEC §7.4 requires: a Nextcloud trash-restore **changes the fileid**, so
     * a restored note cannot be found by `oc:id` at all and is re-bound by path (SPEC §7.6).
     */
    override suspend fun findByPath(accountId: String, remotePath: String): List<RemoteBinding> =
        withContext(dispatcher) {
            queries.selectBindingByAccountAndPath(accountId, remotePath).executeAsList().map { it.toBinding() }
        }

    /**
     * Mark these notes as seen by the pass that started at [seenAtEpochMillis].
     *
     * One statement for the whole pass rather than one per note. Empty is a no-op and must be:
     * SQLDelight expands `IN :noteIds` to one placeholder per value, and none is `IN ()`, which
     * is a syntax error.
     */
    override suspend fun stampSeen(noteIds: Collection<DocumentId>, seenAtEpochMillis: Long): Unit =
        withContext(dispatcher) {
            if (noteIds.isNotEmpty()) queries.stampBindingSeen(seenAtEpochMillis, noteIds.map { it.value })
        }

    /**
     * Bindings this pass did not stamp — **candidates** for SPEC §7.6 step 3's fourth branch.
     *
     * ⚠️ Not conclusions. A pruned or aborted subtree leaves its bindings unstamped too, so the
     * caller must intersect these with the directories it actually walked before trashing
     * anything. Trashing the list as it stands is the hundred-notes-in-the-trash bug that
     * `remoteBinding.lastSeenRemoteAt` exists to prevent.
     */
    override suspend fun notSeenSince(accountId: String, sinceEpochMillis: Long): List<StaleBinding> =
        withContext(dispatcher) {
            queries.selectBindingsNotSeenSince(accountId, sinceEpochMillis).executeAsList()
                .map { StaleBinding(DocumentId(it.noteId), it.remotePath) }
        }

    override suspend fun upsert(binding: RemoteBinding): Unit = withContext(dispatcher) {
        queries.upsertBinding(
            noteId = binding.noteId.value,
            accountId = binding.accountId,
            remotePath = binding.remotePath,
            remoteOcId = binding.remoteOcId,
            // Unquoted (SPEC §7.3), with the weak flag beside it — see the column comment.
            lastSyncedEtag = binding.lastSyncedEtag?.value,
            lastSyncedEtagWeak = if (binding.lastSyncedEtag?.weak == true) 1L else 0L,
            lastSyncedRev = binding.lastSyncedRev,
            lastUploadAt = binding.lastUploadAtEpochMillis,
            lastChecksum = binding.lastChecksum,
            movedFromPath = binding.movedFromPath,
        )
    }

    override suspend fun remove(noteId: DocumentId): Unit =
        withContext(dispatcher) { queries.deleteBinding(noteId.value) }
}

public class SqlOutboxStore(private val database: InkstoneDatabase, private val dispatcher: CoroutineDispatcher) :
    OutboxStore {

    private val queries = database.syncQueries

    /**
     * Read, decide, write — in one transaction.
     *
     * The transaction is what makes SPEC §7.6's coalescing correct rather than
     * approximately correct. Two autosave ticks landing at once would otherwise both read
     * "no pending UPLOAD", both insert, and the second would silently win with whichever
     * `created_at` it happened to carry — losing "how long has this been waiting" and, on
     * a failing note, resetting the backoff into a hot loop.
     *
     * [plan] is not `suspend` because a SQLDelight transaction body cannot be. That is a
     * constraint worth having: it forces the decision to be a pure function of the rows,
     * which is why [dev.inkstone.core.sync.OutboxCoalescing] is testable at all.
     */
    override suspend fun mutate(noteId: DocumentId, plan: (List<OutboxEntry>) -> OutboxPlan): Unit =
        withContext(dispatcher) {
            database.transaction {
                val pending = queries.selectOutboxForNote(noteId.value).executeAsList().mapNotNull { it.toEntry() }
                val decided = plan(pending)
                for (opType in decided.removals) queries.deleteOutboxRow(noteId.value, opType.name)
                for (entry in decided.upserts) {
                    queries.upsertOutbox(
                        noteId = entry.noteId.value,
                        opType = entry.opType.name,
                        localRevAtEnqueue = entry.localRevAtEnqueue,
                        attempts = entry.attempts.toLong(),
                        nextAttemptAt = entry.nextAttemptAt,
                        createdAt = entry.createdAt,
                    )
                }
            }
        }

    override suspend fun due(nowEpochMillis: Long, limit: Int): List<OutboxEntry> = withContext(dispatcher) {
        queries.selectDueOutbox(nowEpochMillis, limit.toLong()).executeAsList().mapNotNull { it.toEntry() }
    }

    override suspend fun pendingFor(noteId: DocumentId): List<OutboxEntry> = withContext(dispatcher) {
        queries.selectOutboxForNote(noteId.value).executeAsList().mapNotNull { it.toEntry() }
    }

    override suspend fun all(): List<OutboxEntry> =
        withContext(dispatcher) { queries.selectAllOutbox().executeAsList().mapNotNull { it.toEntry() } }

    override suspend fun reschedule(
        noteId: DocumentId,
        opType: OutboxOpType,
        attempts: Int,
        nextAttemptAt: Long,
    ): Unit = withContext(dispatcher) {
        queries.rescheduleOutbox(attempts.toLong(), nextAttemptAt, noteId.value, opType.name)
    }

    override suspend fun remove(noteId: DocumentId, opType: OutboxOpType): Unit =
        withContext(dispatcher) { queries.deleteOutboxRow(noteId.value, opType.name) }

    override suspend fun count(): Int = withContext(dispatcher) { queries.countOutbox().executeAsOne().toInt() }
}

/**
 * [SyncNotes] over the library index's `note` table.
 *
 * Deliberately not `LibraryIndex` itself: `:core:sync` has no business reading titles or
 * folder membership, and a port that handed it the whole index would let it start.
 */
public class SqlSyncNotes(private val database: InkstoneDatabase, private val dispatcher: CoroutineDispatcher) :
    SyncNotes {

    private val queries = database.libraryQueries

    override suspend fun localRev(noteId: DocumentId): Long? =
        withContext(dispatcher) { queries.selectNote(noteId.value).executeAsOneOrNull()?.localRev }

    override suspend fun setSyncState(noteId: DocumentId, state: NoteSyncState, lastError: String?): Unit =
        withContext(dispatcher) { queries.updateNoteSyncState(state.name, lastError, noteId.value) }
}

// Row → model. Fully qualified receivers for the reason LibraryIndex gives: SQLDelight
// names a row type after its table, so `dev.inkstone.core.database.Account` and the
// model's `SyncAccount` would otherwise be one import collision away from each other.

private fun dev.inkstone.core.database.Account.toAccount() = SyncAccount(
    id = id,
    serverUrl = serverUrl,
    loginName = loginName,
    uid = uid,
    displayName = displayName,
    defaultFolder = defaultFolder,
    // An unrecognised state reads as NEEDS_REAUTH, not ACTIVE. A database written by a
    // newer build must never cause an older one to start sending requests it does not
    // understand the state of — and prompting for a login is recoverable, whereas a 401
    // storm against brute-force protection is not (SPEC §7.2).
    state = AccountState.entries.firstOrNull { it.name == state } ?: AccountState.NEEDS_REAUTH,
    capabilities = capabilities?.let { NextcloudCapabilities.decode(it) },
    lastError = lastError,
)

private fun dev.inkstone.core.database.RemoteBinding.toBinding() = RemoteBinding(
    noteId = DocumentId(noteId),
    accountId = accountId,
    remotePath = remotePath,
    remoteOcId = remoteOcId,
    lastSyncedEtag = lastSyncedEtag?.let { ETag(it, weak = lastSyncedEtagWeak != 0L) },
    lastSyncedRev = lastSyncedRev,
    lastUploadAtEpochMillis = lastUploadAt,
    lastChecksum = lastChecksum,
    movedFromPath = movedFromPath,
)

/**
 * Null for an op type this build does not know about.
 *
 * It can only have come from a newer build, and inventing a meaning for it would send the
 * wrong request at the user's server. The row is left in the table — dropping it would
 * lose work the newer build still owes — and simply not drained here.
 */
private fun dev.inkstone.core.database.Outbox.toEntry(): OutboxEntry? {
    val type = OutboxOpType.entries.firstOrNull { it.name == opType } ?: return null
    return OutboxEntry(
        noteId = DocumentId(noteId),
        opType = type,
        localRevAtEnqueue = localRevAtEnqueue,
        attempts = attempts.toInt(),
        nextAttemptAt = nextAttemptAt,
        createdAt = createdAt,
    )
}
