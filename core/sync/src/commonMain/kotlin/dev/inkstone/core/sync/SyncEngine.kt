package dev.inkstone.core.sync

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.NoteSyncState
import dev.inkstone.core.model.RemoteName
import dev.inkstone.core.model.WorkScheduler

/**
 * The write side of sync: bind a note to a place on the server, and enqueue what is owed.
 *
 * **Nothing here touches the network** (CLAUDE.md rule 4). Every method ends in an outbox
 * row and a nudge to [WorkScheduler]; [OutboxDrainer] is the only thing that connects.
 * That is what makes SPEC §7.6 point 4 true — *"the note is fully editable throughout"* —
 * because binding a note is three local writes and returns immediately whether or not
 * there is a network.
 */
public class SyncEngine(
    private val stores: SyncStores,
    private val clock: Clock,
    private val scheduler: WorkScheduler,
) {

    private val accounts get() = stores.accounts
    private val bindings get() = stores.bindings
    private val outbox get() = stores.outbox
    private val notes get() = stores.notes

    /** The file extension a note publishes as. SPEC §5.2 — notes are ordinary PDFs. */
    public val extension: String get() = PDF_EXTENSION

    // -----------------------------------------------------------------------
    // Binding
    // -----------------------------------------------------------------------

    /**
     * SPEC §7.6 — *"Save to Nextcloud…"*, steps 3 to 5.
     *
     * Creates the binding, enqueues `MKCOL` then `UPLOAD`, and sets `PENDING_UPLOAD`.
     * The MKCOL row is always written rather than conditionally: finding out whether the
     * folder exists is a network call, this method must not make one, and a MKCOL against
     * an existing collection is a 405 that [WebDavClient.mkcolRecursive] already reads as
     * success.
     *
     * @param remoteFolder a remote path such as `Notes/Physics`, or null for the
     *   account's default folder — SPEC §7.6's *"a default folder per account makes
     *   step 2 one tap"*.
     * @return the binding, or null when the account is unknown.
     */
    public suspend fun bind(
        noteId: DocumentId,
        accountId: String,
        title: String,
        remoteFolder: String? = null,
    ): RemoteBinding? {
        val account = accounts.find(accountId) ?: return null
        val folder = (remoteFolder ?: account.defaultFolder).trim('/')
        val path = uniquePath(accountId, folder, title, account, excluding = noteId)

        val binding = RemoteBinding(noteId = noteId, accountId = accountId, remotePath = path)
        bindings.upsert(binding)

        val localRev = notes.localRev(noteId) ?: 0L
        val now = clock.nowEpochMillis()
        if (folder.isNotEmpty()) enqueue(noteId, OutboxOpType.MKCOL, localRev, everUploaded = false, now = now)
        enqueue(noteId, OutboxOpType.UPLOAD, localRev, everUploaded = false, now = now)
        notes.setSyncState(noteId, NoteSyncState.PENDING_UPLOAD, null)
        scheduler.enqueueOutboxDrain()
        return binding
    }

    /**
     * Stop syncing a note, leaving the remote file where it is.
     *
     * Not a delete. The user asked to unbind, not to lose the copy on their server —
     * and CLAUDE.md rule 1 does not distinguish between the two just because one of them
     * is remote.
     */
    public suspend fun unbind(noteId: DocumentId) {
        for (opType in OutboxOpType.entries) outbox.remove(noteId, opType)
        bindings.remove(noteId)
        notes.setSyncState(noteId, NoteSyncState.LOCAL_ONLY, null)
    }

    public suspend fun bindingOf(noteId: DocumentId): RemoteBinding? = bindings.find(noteId)

    // -----------------------------------------------------------------------
    // What the rest of the app reports
    // -----------------------------------------------------------------------

    /**
     * The note was saved (SPEC §5.1's autosave, or a materialization).
     *
     * Safe to call on every autosave tick: the UPSERT coalesces (SPEC §7.6), so 500
     * offline edits leave exactly one row, and the drainer's 30-second floor turns
     * "upload immediately if online" into "at most once per 30 s".
     *
     * A note with no binding is not an error — it is most notes.
     */
    public suspend fun noteChanged(noteId: DocumentId) {
        val binding = bindings.find(noteId) ?: return
        val localRev = notes.localRev(noteId) ?: return
        if (localRev <= binding.lastSyncedRev && outbox.pendingFor(noteId).isEmpty()) return
        enqueue(noteId, OutboxOpType.UPLOAD, localRev, binding.everUploaded, clock.nowEpochMillis())
        notes.setSyncState(noteId, NoteSyncState.PENDING_UPLOAD, null)
        scheduler.enqueueOutboxDrain()
    }

    /**
     * The note's title or folder changed, so its remote path does.
     *
     * SPEC §7.6 — *"MOVE always drains before UPLOAD for the same note"*, which the
     * drainer enforces through [OutboxOpType.drainPriority]. Both rows are kept.
     *
     * `movedFromPath` records where the file still *is*; `remotePath` is always where it
     * *should* be, so nothing downstream has to work out which of two paths is current.
     * A second rename before the first move drains keeps the original `movedFromPath` —
     * that is where the file actually is, and overwriting it with the intermediate name
     * would move from a path that never existed on the server.
     */
    public suspend fun notePathChanged(noteId: DocumentId, title: String, remoteFolder: String? = null) {
        val binding = bindings.find(noteId) ?: return
        val account = accounts.find(binding.accountId) ?: return
        val folder = (remoteFolder ?: binding.remotePath.substringBeforeLast('/', "")).trim('/')
        val path = uniquePath(binding.accountId, folder, title, account, excluding = noteId)
        if (path == binding.remotePath && binding.movedFromPath == null) return

        val now = clock.nowEpochMillis()
        bindings.upsert(
            binding.copy(
                remotePath = path,
                movedFromPath = binding.movedFromPath ?: binding.remotePath,
            ),
        )
        val localRev = notes.localRev(noteId) ?: binding.lastSyncedRev
        if (folder.isNotEmpty()) enqueue(noteId, OutboxOpType.MKCOL, localRev, binding.everUploaded, now)
        enqueue(noteId, OutboxOpType.MOVE, localRev, binding.everUploaded, now)
        scheduler.enqueueOutboxDrain()
    }

    /**
     * The note went to the local trash. SPEC §7.6: *"local trash immediately + enqueue
     * remote `DELETE`"*, which lands in Nextcloud's trash rather than destroying anything.
     *
     * [OutboxCoalescing] annihilates any pending UPLOAD here — otherwise a note deleted
     * with an upload still queued would be re-created on the server moments later.
     */
    public suspend fun noteTrashed(noteId: DocumentId) {
        val binding = bindings.find(noteId) ?: return
        val localRev = notes.localRev(noteId) ?: binding.lastSyncedRev
        enqueue(noteId, OutboxOpType.DELETE, localRev, binding.everUploaded, clock.nowEpochMillis())
        scheduler.enqueueOutboxDrain()
    }

    /**
     * Restored from the local trash within 30 days. SPEC §7.6: re-enqueue an `UPLOAD`.
     *
     * ⚠️ *"a Nextcloud trash-restore **changes the fileid**, so re-bind by path and repair
     * `remote_oc_id` from the PUT response"* — which is what dropping [RemoteBinding.remoteOcId]
     * and [RemoteBinding.lastSyncedEtag] here does. Keeping the old ETag would send an
     * `If-Match` for a version of the file that no longer exists and 412 forever.
     */
    public suspend fun noteRestored(noteId: DocumentId) {
        val binding = bindings.find(noteId) ?: return
        outbox.remove(noteId, OutboxOpType.DELETE)
        bindings.upsert(
            binding.copy(
                remoteOcId = null,
                lastSyncedEtag = null,
                lastChecksum = null,
                // Also cleared, so the re-upload is a *create* (`If-None-Match: *`): the
                // remote DELETE that went with the trashing removed the file, and this is
                // what [RemoteBinding.everUploaded] reads to decide.
                lastUploadAtEpochMillis = 0L,
            ),
        )
        val localRev = notes.localRev(noteId) ?: binding.lastSyncedRev
        val now = clock.nowEpochMillis()
        enqueue(noteId, OutboxOpType.MKCOL, localRev, everUploaded = false, now = now)
        enqueue(noteId, OutboxOpType.UPLOAD, localRev, everUploaded = false, now = now)
        notes.setSyncState(noteId, NoteSyncState.PENDING_UPLOAD, null)
        scheduler.enqueueOutboxDrain()
    }

    // -----------------------------------------------------------------------

    private suspend fun enqueue(
        noteId: DocumentId,
        opType: OutboxOpType,
        localRev: Long,
        everUploaded: Boolean,
        now: Long,
    ) {
        outbox.mutate(noteId) { pending ->
            OutboxCoalescing.plan(pending, opType, noteId, localRev, everUploaded, now)
        }
    }

    /**
     * `<folder>/<legal title>.pdf`, made unique against the paths already claimed on
     * this account.
     *
     * Two notes can legitimately be called "Untitled". Letting them share a remote path
     * would mean the second one's `If-None-Match: *` failing forever, or — worse, without
     * it — one note silently overwriting the other. A numeric suffix is ugly and keeps
     * both, which is the trade CLAUDE.md rule 1 asks for.
     *
     * The name goes through the account's own [NextcloudCapabilities.FileNameRules] when
     * they are known (SPEC §7.9), and through [RemoteName] when they are not — which is
     * already the stricter Windows set, so the fallback errs toward legal (SPEC §7.8).
     */
    private suspend fun uniquePath(
        accountId: String,
        folder: String,
        title: String,
        account: SyncAccount,
        excluding: DocumentId,
    ): String {
        val rules = account.capabilities?.files
        val base = rules?.sanitise(title) ?: RemoteName.sanitise(title)
        val taken = bindings.forAccount(accountId)
            .filter { it.noteId != excluding }
            .mapTo(HashSet()) { it.remotePath.lowercase() }

        val prefix = if (folder.isEmpty()) "" else "$folder/"
        var candidate = "$prefix$base$PDF_EXTENSION"
        var suffix = 2
        while (candidate.lowercase() in taken && suffix < MAX_SUFFIX) {
            candidate = "$prefix$base ($suffix)$PDF_EXTENSION"
            suffix++
        }
        return candidate
    }

    private companion object {
        const val PDF_EXTENSION = ".pdf"
        const val MAX_SUFFIX = 1000
    }
}
