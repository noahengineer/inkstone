package dev.inkstone.core.database.sync

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.database.SelectOpenAttentionWithTitles
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.sync.AttentionEntry
import dev.inkstone.core.sync.AttentionLog
import dev.inkstone.core.sync.CachedFolder
import dev.inkstone.core.sync.CachedListing
import dev.inkstone.core.sync.DirectoryCache
import dev.inkstone.core.sync.ETag
import dev.inkstone.core.sync.FolderBinding
import dev.inkstone.core.sync.FolderBindingStore
import dev.inkstone.core.sync.ForeignRemote
import dev.inkstone.core.sync.ForeignRemoteCache
import dev.inkstone.core.sync.PeerClock
import dev.inkstone.core.sync.PeerClockStore
import dev.inkstone.core.sync.PendingDownload
import dev.inkstone.core.sync.PendingDownloadStore
import dev.inkstone.core.sync.RemoteDirectory
import dev.inkstone.core.sync.RemoteFolderCache
import dev.inkstone.core.sync.StaleBinding
import dev.inkstone.core.sync.SyncCursor
import dev.inkstone.core.sync.SyncCursorStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/*
 * M6's rows: the directory ETag cache, the per-account cursor, in-flight downloads, the
 * "Needs attention" list, folder bindings, and peer clocks. Column-by-column reasoning is in
 * Sync.sq; this file is the typed surface over it.
 *
 * ## Why the row types are declared in `:core:sync` and not here
 *
 * `:core:sync` owns the reconcile *semantics* — what a pruned subtree means, when an absent
 * oc:id is a deletion, which of SPEC §7.7's three layers a conflict lands in — and those are the
 * parts that can be got wrong quietly, so they live where they can be tested against a fake with
 * no driver and no device. That is the same split M5 made for the outbox (see SqlSyncStores.kt).
 *
 * The consequence is that the *ports* and the row types they speak in are `:core:sync`'s, and this
 * file is one transaction per method behind them. Declaring the rows here instead would invert the
 * module edge, which runs `:core:database -> :core:sync` precisely so that the module owning the
 * rules does not depend on the module owning SQLDelight.
 *
 * Block comment rather than KDoc: a file-level KDoc attaches to no declaration, and ktlint's
 * standard:kdoc rule rejects it (the same note is on SyncPorts.kt).
 */

/**
 * One row of SPEC §7.7's "Needs attention" screen: the entry, plus the titles it points at.
 *
 * Declared here rather than in `:core:sync` because it exists only because of *how* the rows are
 * read — one join instead of two Flows — and the join is this module's. `:core:sync` writes
 * [AttentionEntry] and never reads a title; nothing about the reconcile rules changes.
 *
 * @property noteTitle null when the entry is account-level (a full quota, a dead credential), and
 *   also when the note has been forgotten since. The screen shows the entry either way — an entry
 *   the UI filters out is an entry that eventually gets hidden, which is what this list exists not
 *   to do.
 * @property noteTrashed ⚠️ true when the local note is in the trash — which is the *normal* state
 *   for an [AttentionEntry] of kind `REMOTE_DELETED`. The screen must not offer to open it:
 *   `NoteEditor` falls back to creating a note when it cannot open the one it was asked for, so
 *   opening a trashed note would silently mint a new empty one under a new id.
 * @property copyTitle SPEC §7.7 layer 3's second copy, by name, so the screen can offer to open it
 *   without a second query per row.
 */
public data class AttentionItem(
    val entry: AttentionEntry,
    val noteTitle: String? = null,
    val noteTrashed: Boolean = false,
    val copyTitle: String? = null,
)

// ---------------------------------------------------------------------------
// Stores
// ---------------------------------------------------------------------------

/**
 * The directory ETag cache (SPEC §7.4 rule 1).
 *
 * @param dispatcher where SQL runs. A parameter for the reason `LibraryIndex` gives:
 *   `Dispatchers.IO` does not exist in common code.
 */
public class SqlRemoteDirectoryStore(
    private val database: InkstoneDatabase,
    private val dispatcher: CoroutineDispatcher,
) : DirectoryCache {

    private val queries = database.syncQueries

    /**
     * The whole cache for one account.
     *
     * Not one lookup per path: the walk needs the cache to decide what *not* to request, so it
     * needs it before it knows which paths it cares about.
     */
    override suspend fun forAccount(accountId: String): List<RemoteDirectory> = withContext(dispatcher) {
        queries.selectDirectories(accountId).executeAsList().map { it.toDirectory() }
    }

    override suspend fun upsert(directory: RemoteDirectory): Unit = withContext(dispatcher) { write(directory) }

    /**
     * A whole walk's worth, in one transaction.
     *
     * Not a convenience. A kill partway through leaves some directories carrying this pass's
     * ETag and some the last one's, and a directory whose ETag is newer than its contents were
     * observed to be is pruned on the next pass — so a subtree stops being walked, silently,
     * for as long as nothing else in it changes.
     */
    override suspend fun upsertAll(directories: Collection<RemoteDirectory>): Unit = withContext(dispatcher) {
        database.transaction { for (directory in directories) write(directory) }
    }

    /**
     * Drop every cached ETag for an account.
     *
     * Two callers, both correctness: `nc:data-fingerprint` changed (SPEC §7.6 step 4) and
     * sign-out. Cheap to lose — the next pass rebuilds it with one extra walk.
     */
    override suspend fun clearFor(accountId: String): Unit =
        withContext(dispatcher) { queries.deleteDirectoriesFor(accountId) }

    private fun write(directory: RemoteDirectory) = queries.upsertDirectory(
        accountId = directory.accountId,
        path = directory.path,
        etag = directory.etag?.value,
        etagWeak = directory.etag.weakFlag,
        ocId = directory.ocId,
        seenAt = directory.seenAtEpochMillis,
    )
}

/**
 * ⭐ The folder browser's memory of the server's tree — what makes an upload path choosable with
 * no network.
 *
 * ⚠️ **Writes only `browsedFolder`, never `remoteDirectory`.** The two tables look alike and
 * mean opposite things: a `remoteDirectory` row is the reconciler's promise that a directory was
 * *walked to completion* at an ETag, and a row it did not earn stops a subtree being walked or
 * gets a note read as deleted (SPEC §7.4 rule 1). Nothing here may put one there.
 *
 * It does **read** them, once, in [childrenOf]: a folder the reconciler has walked is a folder
 * that demonstrably exists, and showing it costs one query and is the difference between a
 * useful offline picker and an empty one for every user who has never opened the browser while
 * connected. Read-only, into a list of names, and no sync decision sees the result.
 *
 * @param dispatcher where SQL runs — `Dispatchers.IO` does not exist in common code.
 */
public class SqlBrowsedFolderStore(
    private val database: InkstoneDatabase,
    private val dispatcher: CoroutineDispatcher,
) : RemoteFolderCache {

    private val queries = database.syncQueries

    override suspend fun childrenOf(accountId: String, path: String): CachedListing = withContext(dispatcher) {
        val here = path.trim('/')
        val remembered = queries.selectBrowsedChildren(accountId, here).executeAsList().map { it.toCachedFolder() }
        val known = remembered.mapTo(mutableSetOf()) { it.path }

        // The reconciler's walked directories, for display only. A folder it walked exists; the
        // browser may never have been opened here, and an empty picker is the thing being fixed.
        val walked = queries.selectDirectories(accountId).executeAsList()
            .mapNotNull { directory ->
                val candidate = directory.path.trim('/')
                if (candidate.isEmpty() || candidate in known) return@mapNotNull null
                if (candidate.substringBeforeLast('/', "") != here) return@mapNotNull null
                CachedFolder(
                    accountId = accountId,
                    path = candidate,
                    parentPath = here,
                    name = candidate.substringAfterLast('/'),
                    ocId = directory.ocId,
                    seenAtEpochMillis = directory.seenAt,
                )
            }

        CachedListing(
            folders = (remembered + walked).sortedBy { it.name.lowercase() },
            // The cache's own, never the walk's: this number answers "have I listed inside
            // here", and a reconcile pass is not a listing of this folder for the browser's
            // purposes. 0 stays 0, and the screen says "never looked" rather than inventing a
            // time.
            listedAtEpochMillis = queries.selectBrowsedFolder(accountId, here).executeAsOneOrNull()?.listedAt ?: 0L,
        )
    }

    /**
     * One listing, in one transaction — a half-written one is a listing that lies.
     *
     * The sweep is `seenAt < now`, so the rows just marked survive it by construction and
     * anything the server did not name goes. [CachedFolder.pending] rows are exempt: they are
     * absent from the server precisely because the MKCOL the binding enqueued has not run yet.
     */
    override suspend fun remember(
        accountId: String,
        path: String,
        folders: List<CachedFolder>,
        nowEpochMillis: Long,
    ): Unit = withContext(dispatcher) {
        val here = path.trim('/')
        database.transaction {
            insertIfAbsent(accountId, here)
            queries.markBrowsedFolderListed(listedAt = nowEpochMillis, accountId = accountId, path = here)
            for (folder in folders) {
                val child = folder.path.trim('/')
                if (child.isEmpty() || child == here) continue
                insertIfAbsent(accountId, child)
                queries.markBrowsedFolderSeen(
                    parent = here,
                    name = folder.name,
                    ocId = folder.ocId,
                    seenAt = nowEpochMillis,
                    accountId = accountId,
                    path = child,
                )
            }
            queries.deleteBrowsedChildrenNotSeen(accountId = accountId, parent = here, cutoff = nowEpochMillis)
        }
    }

    override suspend fun rememberPending(accountId: String, path: String, nowEpochMillis: Long): Unit =
        withContext(dispatcher) {
            val target = path.trim('/')
            if (target.isEmpty()) return@withContext
            database.transaction {
                insertIfAbsent(accountId, target)
                queries.markBrowsedFolderPending(accountId = accountId, path = target)
            }
        }

    override suspend fun clearFor(accountId: String): Unit =
        withContext(dispatcher) { queries.deleteBrowsedFoldersFor(accountId) }

    /**
     * The row, with its parent and name derived from the path.
     *
     * `INSERT OR IGNORE` rather than `INSERT OR REPLACE`, which would delete the conflicting row
     * and reset every column this statement omits — `listedAt` among them, so re-listing a
     * parent would forget that its children had ever been looked inside
     * (`remoteBinding.lastSeenRemoteAt`'s bug, one table along).
     */
    private fun insertIfAbsent(accountId: String, path: String) = queries.insertBrowsedFolderIfAbsent(
        accountId = accountId,
        path = path,
        parent = path.substringBeforeLast('/', ""),
        name = path.substringAfterLast('/'),
    )
}

/** One row per account: where the last reconcile pass got to (SPEC §7.6 steps 1 and 4). */
public class SqlSyncCursorStore(private val database: InkstoneDatabase, private val dispatcher: CoroutineDispatcher) :
    SyncCursorStore {

    private val queries = database.syncQueries

    /** Null before the first pass — which is not the same as "nothing has changed". */
    override suspend fun find(accountId: String): SyncCursor? = withContext(dispatcher) {
        queries.selectCursor(accountId).executeAsOneOrNull()?.toCursor()
    }

    /**
     * ⚠️ Writes `refusedUntil` like every other column, which is why there is no separate statement
     * for it: `INSERT OR REPLACE` re-inserts the whole row, so a second writer in one pass would
     * reset whatever the first stored — and what it would reset is CLAUDE.md rule 5's cooldown.
     */
    override suspend fun upsert(cursor: SyncCursor): Unit = withContext(dispatcher) {
        queries.upsertCursor(
            accountId = cursor.accountId,
            dataFingerprint = cursor.dataFingerprint,
            rootEtag = cursor.rootEtag?.value,
            rootEtagWeak = cursor.rootEtag.weakFlag,
            lastReconcileAt = cursor.lastReconcileAtEpochMillis,
            lastFullAt = cursor.lastFullAtEpochMillis,
            refusedUntil = cursor.refusedUntilEpochMillis,
        )
    }
}

/**
 * The negative half of SPEC §5.6's identity check: remote `.pdf`s with no Inkstone marker in them.
 *
 * Two methods and no sweep, for [ForeignRemoteCache]'s reason. What the table prevents is in
 * `Sync.sq`: a 40 MB handout beside the notes re-downloaded on every note save, and — the loud half
 * — `Reconciler.DEFAULT_LIMIT` strangers ahead of the notes in listing order starving the pass
 * forever while the poll reported success.
 */
public class SqlForeignRemoteStore(
    private val database: InkstoneDatabase,
    private val dispatcher: CoroutineDispatcher,
) : ForeignRemoteCache {

    private val queries = database.syncQueries

    override suspend fun forAccount(accountId: String): List<ForeignRemote> = withContext(dispatcher) {
        queries.selectForeignRemotes(accountId).executeAsList().map { it.toForeignRemote() }
    }

    override suspend fun upsert(file: ForeignRemote): Unit = withContext(dispatcher) {
        queries.upsertForeignRemote(
            accountId = file.accountId,
            fileKey = file.fileKey,
            remotePath = file.remotePath,
            etag = file.etag?.value,
            etagWeak = file.etag.weakFlag,
            checksum = file.checksum,
            seenAt = file.seenAtEpochMillis,
        )
    }
}

/** Downloads fetched and not yet merged (SPEC §7.6 step 3). */
public class SqlPendingDownloadStore(
    private val database: InkstoneDatabase,
    private val dispatcher: CoroutineDispatcher,
) : PendingDownloadStore {

    private val queries = database.syncQueries

    override suspend fun find(noteId: DocumentId): PendingDownload? = withContext(dispatcher) {
        queries.selectPendingDownload(noteId.value).executeAsOneOrNull()?.toPendingDownload()
    }

    override suspend fun upsert(download: PendingDownload): Unit = withContext(dispatcher) {
        queries.upsertPendingDownload(
            noteId = download.noteId.value,
            accountId = download.accountId,
            remotePath = download.remotePath,
            remoteOcId = download.remoteOcId,
            remoteEtag = download.remoteEtag?.value,
            remoteEtagWeak = download.remoteEtag.weakFlag,
            tempPath = download.tempPath,
            fetchedAt = download.fetchedAtEpochMillis,
        )
    }

    /**
     * Forget a download.
     *
     * **Delete the temp file first.** The row is the only thing that knows its name, so the
     * other order leaks it — and unlike a note, nothing else will ever notice.
     */
    override suspend fun remove(noteId: DocumentId): Unit =
        withContext(dispatcher) { queries.deletePendingDownload(noteId.value) }
}

/** SPEC §7.7's "Needs attention" list. */
public class SqlAttentionStore(private val database: InkstoneDatabase, private val dispatcher: CoroutineDispatcher) :
    AttentionLog {

    private val queries = database.syncQueries

    /** The list, as a stream, for the screen that shows it. Newest first. */
    public fun openStream(): Flow<List<AttentionEntry>> =
        queries.selectOpenAttention().asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toAttention() } }

    /**
     * ⭐ The list the "Needs attention" screen draws: every open entry with the titles it names.
     *
     * ⚠️ **One query joined to `note`, not this Flow combined with the library's in Kotlin.**
     * SQLDelight registers a query against every table it reads and invalidates per table, so a
     * stream over `syncAttention` alone never re-emits when a note is renamed or trashed, and one
     * over `note` alone never re-emits when a row is acknowledged. A `combine` of the two is stale
     * in whichever direction happens not to fire — and the direction that goes stale here leaves
     * the screen offering to open a copy under a name the note no longer has. `Sync.sq` says the
     * rest.
     */
    public fun openWithTitles(): Flow<List<AttentionItem>> =
        queries.selectOpenAttentionWithTitles().asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toItem() } }

    /**
     * Which notes have an open entry — the library's per-tile flag.
     *
     * A set of ids rather than the entries: the tile draws one flag whatever the reason, and
     * holding every entry behind a Flow the library never renders would keep the detail strings
     * of a hundred conflicts alive for the life of the screen.
     */
    public fun noteIdsNeedingAttention(): Flow<Set<DocumentId>> =
        queries.selectNotesNeedingAttention().asFlow().mapToList(dispatcher).map { rows ->
            rows.mapTo(HashSet(), ::DocumentId)
        }

    /** How many entries are open, for the library's badge. */
    public fun openCount(): Flow<Long> = queries.countOpenAttention().asFlow().mapToOne(dispatcher)

    /**
     * The open rows as a snapshot.
     *
     * ⚠️ Deliberately **not** what the reconciler dedups against — see [AttentionLog.open]. An
     * acknowledged row is not in here, so a guard written against this set un-dismisses it.
     */
    override suspend fun open(): List<AttentionEntry> = withContext(dispatcher) {
        queries.selectOpenAttention().executeAsList().map { it.toAttention() }
    }

    /** One row by id, acknowledged or not — the read the dedup and layer 3 both need. */
    override suspend fun find(id: String): AttentionEntry? = withContext(dispatcher) {
        queries.selectAttention(id).executeAsOneOrNull()?.toAttention()
    }

    /**
     * Record something the user should look at.
     *
     * An UPSERT on [AttentionEntry.id], so rediscovering the same problem on the next pass
     * updates one row instead of raising a constraint violation in the middle of a reconcile —
     * SPEC §7.7 rule 3: flagged and skipped, everything else keeps syncing.
     */
    override suspend fun add(entry: AttentionEntry): Unit = withContext(dispatcher) {
        queries.insertAttention(
            id = entry.id,
            accountId = entry.accountId,
            noteId = entry.noteId?.value,
            kind = entry.kind,
            detail = entry.detail,
            conflictedCopyNoteId = entry.conflictedCopyNoteId?.value,
            remotePath = entry.remotePath,
            createdAt = entry.createdAtEpochMillis,
            acknowledgedAt = entry.acknowledgedAtEpochMillis,
        )
    }

    /**
     * The user has seen it.
     *
     * The only mutation, and it is a tombstone: the row stays (CLAUDE.md rule 1), because what
     * sync had to do to someone's notes is worth being able to look up afterwards.
     */
    override suspend fun acknowledge(id: String, acknowledgedAtEpochMillis: Long): Unit =
        withContext(dispatcher) { queries.acknowledgeAttention(acknowledgedAtEpochMillis, id) }
}

/** Which remote directory a library folder mirrors (SPEC §9, the pull side). */
public class SqlFolderBindingStore(
    private val database: InkstoneDatabase,
    private val dispatcher: CoroutineDispatcher,
) : FolderBindingStore {

    private val queries = database.syncQueries

    override suspend fun find(folderId: FolderId): FolderBinding? = withContext(dispatcher) {
        queries.selectFolderBinding(folderId.value).executeAsOneOrNull()?.toFolderBinding()
    }

    /**
     * The folder a walked remote directory belongs to, by `oc:id`.
     *
     * A list rather than one row: SPEC §7.4's caveats include one `oc:id` presented at two
     * paths (a folder shared in twice), and `executeAsOne` would throw — inside a reconcile
     * pass, which is the opposite of the "degrade gracefully" the same paragraph asks for.
     */
    override suspend fun findByOcId(accountId: String, remoteOcId: String): List<FolderBinding> =
        withContext(dispatcher) {
            queries.selectFolderBindingByOcId(accountId, remoteOcId).executeAsList().map { it.toFolderBinding() }
        }

    /**
     * Which folders are pinned, as a live stream — what the library's cloud badge watches.
     *
     * Account-agnostic on purpose: the badge answers *"do the things in here go to Nextcloud"*,
     * and a user with two accounts still wants a cloud on a folder pinned to either.
     */
    public fun pinnedFolderIds(): Flow<Set<FolderId>> =
        queries.selectAllFolderBindings().asFlow().mapToList(dispatcher).map { rows ->
            rows.mapTo(HashSet()) { FolderId(it.folderId) }
        }

    /**
     * Every pin on one account, as `folderId → remotePath` — the shape
     * `FolderTree.remoteFolderFor` takes.
     *
     * The whole set rather than one lookup: which ancestor is pinned is not known until the
     * note's library ancestry has been walked, so a query per level would be a round trip per
     * folder depth on a path the editor waits behind.
     */
    override suspend fun pathsForAccount(accountId: String): Map<FolderId, String> = withContext(dispatcher) {
        queries.selectFolderBindingsForAccount(accountId)
            .executeAsList()
            .associate { FolderId(it.folderId) to it.remotePath }
    }

    override suspend fun upsert(binding: FolderBinding): Unit = withContext(dispatcher) {
        queries.upsertFolderBinding(
            folderId = binding.folderId.value,
            accountId = binding.accountId,
            remotePath = binding.remotePath,
            remoteOcId = binding.remoteOcId,
            remoteEtag = binding.remoteEtag?.value,
            remoteEtagWeak = binding.remoteEtag.weakFlag,
        )
    }

    /** Unbind, not delete. The folder and every note in it are untouched. */
    override suspend fun remove(folderId: FolderId): Unit =
        withContext(dispatcher) { queries.deleteFolderBinding(folderId.value) }
}

/**
 * SPEC §4.7's causal-stability horizon: what each peer has seen, per note.
 *
 * This is what has been missing from `Compaction.isAllowed`'s `oldestAckedCounter` since M2 —
 * the parameter has always been passed null, so 30 days of wall clock was the only guard.
 */
public class SqlPeerClockStore(private val database: InkstoneDatabase, private val dispatcher: CoroutineDispatcher) :
    PeerClockStore {

    private val queries = database.syncQueries

    override suspend fun forNote(noteId: DocumentId): List<PeerClock> = withContext(dispatcher) {
        queries.selectPeerClocks(noteId.value).executeAsList().map { it.toPeerClock() }
    }

    /**
     * Record what a peer's bundle says it has seen — `highestClockCounter` and
     * `document.deviceId`, both of which `NoteMerge.Merged` already reports.
     *
     * Monotonic: the stored counter never goes down, because downloads arrive out of order and
     * a lowered high-water mark is a false statement about the peer (Sync.sq says more).
     */
    override suspend fun record(
        noteId: DocumentId,
        peerDeviceId: String,
        highestCounter: Long,
        seenAtEpochMillis: Long,
    ): Unit = withContext(dispatcher) {
        queries.upsertPeerClock(noteId.value, peerDeviceId, highestCounter, seenAtEpochMillis)
    }

    /**
     * `Compaction.isAllowed(oldestAckedCounter = …)` for this note: the `min` across peers, or
     * **null when no peer is known**.
     *
     * Null is the value that parameter documents as "nothing is known", which leaves the 30-day
     * horizon as the only guard — the correct conservative answer for a one-device setup, and
     * the answer every note got before this table existed.
     *
     * The `min` is taken in Kotlin, not SQL: there are one or two rows, `min()` over none
     * returns NULL and would then have to be told apart from a genuine zero having discarded
     * the row count that distinguishes them.
     */
    override suspend fun oldestAckedCounter(noteId: DocumentId): Long? =
        forNote(noteId).minOfOrNull { it.highestCounter }
}

// ---------------------------------------------------------------------------
// Row → model
// ---------------------------------------------------------------------------

// Fully qualified receivers for the reason SqlSyncStores.kt gives: SQLDelight names a row type
// after its table, so the generated `RemoteDirectory` and the one above would otherwise be one
// import collision apart.

/**
 * The stored `(value, weak)` pair as an [ETag], and back.
 *
 * One place rather than six. [ETag]'s own KDoc names the failure this avoids — the weak flag
 * recomputed at each call site and got wrong at one of them, producing a device that can never
 * upload again and never says why (SPEC §7.3).
 */
private fun etagOf(value: String?, weakFlag: Long): ETag? = value?.let { ETag(it, weak = weakFlag != 0L) }

private val ETag?.weakFlag: Long get() = if (this?.weak == true) 1L else 0L

private fun dev.inkstone.core.database.RemoteDirectory.toDirectory() = RemoteDirectory(
    accountId = accountId,
    path = path,
    etag = etagOf(etag, etagWeak),
    ocId = ocId,
    seenAtEpochMillis = seenAt,
)

private fun dev.inkstone.core.database.SyncCursor.toCursor() = SyncCursor(
    accountId = accountId,
    dataFingerprint = dataFingerprint,
    rootEtag = etagOf(rootEtag, rootEtagWeak),
    lastReconcileAtEpochMillis = lastReconcileAt,
    lastFullAtEpochMillis = lastFullAt,
    refusedUntilEpochMillis = refusedUntil,
)

private fun dev.inkstone.core.database.ForeignRemote.toForeignRemote() = ForeignRemote(
    accountId = accountId,
    fileKey = fileKey,
    remotePath = remotePath,
    etag = etagOf(etag, etagWeak),
    checksum = checksum,
    seenAtEpochMillis = seenAt,
)

private fun dev.inkstone.core.database.PendingDownload.toPendingDownload() = PendingDownload(
    noteId = DocumentId(noteId),
    accountId = accountId,
    remotePath = remotePath,
    remoteOcId = remoteOcId,
    remoteEtag = etagOf(remoteEtag, remoteEtagWeak),
    tempPath = tempPath,
    fetchedAtEpochMillis = fetchedAt,
)

/**
 * A NULL `acknowledgedAt` reads as open.
 *
 * Nothing here writes one — 0 is the sentinel, so that the open-list query can be
 * `acknowledgedAt = 0` rather than `IS NULL`, which would make SQLDelight generate a second row
 * type and mapper for this table (Library.sq's `deletedAt >= 0` avoids the same thing). This
 * coalesce is for a row that arrived by some other route.
 */
private fun dev.inkstone.core.database.SyncAttention.toAttention() = AttentionEntry(
    id = id,
    accountId = accountId,
    kind = kind,
    createdAtEpochMillis = createdAt,
    noteId = noteId?.let(::DocumentId),
    detail = detail,
    conflictedCopyNoteId = conflictedCopyNoteId?.let(::DocumentId),
    remotePath = remotePath,
    acknowledgedAtEpochMillis = acknowledgedAt ?: 0L,
)

/**
 * The joined row for the screen. The entry is rebuilt field for field rather than shared with
 * [toAttention], because the two queries have different generated row types and a mapper that
 * took a common supertype would need one to be invented for it.
 */
private fun SelectOpenAttentionWithTitles.toItem() = AttentionItem(
    entry = AttentionEntry(
        id = id,
        accountId = accountId,
        kind = kind,
        createdAtEpochMillis = createdAt,
        noteId = noteId?.let(::DocumentId),
        detail = detail,
        conflictedCopyNoteId = conflictedCopyNoteId?.let(::DocumentId),
        remotePath = remotePath,
        acknowledgedAtEpochMillis = acknowledgedAt ?: 0L,
    ),
    noteTitle = noteTitle,
    // Non-null means trashed, the same encoding `note.deletedAt` uses everywhere else.
    noteTrashed = noteDeletedAt != null,
    copyTitle = copyTitle,
)

private fun dev.inkstone.core.database.FolderBinding.toFolderBinding() = FolderBinding(
    folderId = FolderId(folderId),
    accountId = accountId,
    remotePath = remotePath,
    remoteOcId = remoteOcId,
    remoteEtag = etagOf(remoteEtag, remoteEtagWeak),
)

private fun dev.inkstone.core.database.PeerClock.toPeerClock() = PeerClock(
    noteId = DocumentId(noteId),
    peerDeviceId = peerDeviceId,
    highestCounter = highestCounter,
    seenAtEpochMillis = seenAt,
)

private fun dev.inkstone.core.database.BrowsedFolder.toCachedFolder() = CachedFolder(
    accountId = accountId,
    path = path,
    parentPath = parentPath,
    name = name,
    ocId = ocId,
    seenAtEpochMillis = seenAt,
    listedAtEpochMillis = listedAt,
    pending = pending != 0L,
)
