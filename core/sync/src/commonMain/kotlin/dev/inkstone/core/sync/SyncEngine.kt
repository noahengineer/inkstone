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
        // M12 — a note can hold recordings before it is bound to anything: you record a lecture,
        // and only later decide the notebook should be on Nextcloud. Without this the lectures
        // already on disk would wait for the next stroke to be drawn in that note, and a
        // notebook you have stopped writing in never gets one.
        enqueuePendingRecordings(noteId)
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
        // ⚠️ `removeAll`, not a loop over `OutboxOpType.entries` — see [OutboxStore.removeAll].
        // The loop could not name a MEDIA_UPLOAD row, whose key carries a recording id it has
        // no way to know, so it would have left one behind pointing at an account this note is
        // no longer bound to. The recording itself is untouched and is re-enqueued if the note
        // is ever bound again ([enqueuePendingRecordings]).
        outbox.removeAll(noteId)
        bindings.remove(noteId)
        notes.setSyncState(noteId, NoteSyncState.LOCAL_ONLY, null)
    }

    /**
     * ⭐ Sign-out's local half: stop syncing every note on [accountId] **without forgetting
     * where any of them lives.**
     *
     * This is [unbind] for a whole account minus the one destructive part — the binding rows
     * survive. That difference is the whole of what makes signing back in one tap instead of
     * re-binding a library by hand (see [AccountState.SIGNED_OUT]).
     *
     * What it does drop is the **outbox rows**, and that is not optional. M5 found this on a
     * device: signing out left the rows behind, so the notes read `PENDING_UPLOAD` forever,
     * `attempts` never incremented, and the drain worker returned success on every pass —
     * a permanent, silent stall. Deleting the bindings was M5's fix for it, and it worked, but
     * it treated the symptom: the real problem was that work stayed queued for an account
     * that could no longer send it.
     *
     * @return how many notes were paused.
     */
    public suspend fun pauseAccount(accountId: String): Int {
        val paused = bindings.forAccount(accountId)
        for (binding in paused) {
            // See [unbind] for why this is not a loop over the op-type vocabulary. A queued
            // lecture is dropped here along with everything else, and [resumeAccount] puts it
            // back from the recording table — which is the whole reason that table records what
            // is owed rather than leaving the queue to remember it.
            outbox.removeAll(binding.noteId)
            // PAUSED, not LOCAL_ONLY: the binding is still there, so this note is not "never
            // bound" — it is bound and dormant. Calling it LOCAL_ONLY would hide the sync badge
            // on a note that is sitting on the server right now.
            notes.setSyncState(binding.noteId, NoteSyncState.PAUSED, null)
        }
        return paused.size
    }

    /**
     * ⭐ Signing in again to an account whose notes were paused by [pauseAccount].
     *
     * Every retained binding becomes live again. The stored `lastSyncedEtag` and `oc:id` are
     * still valid — signing out did not touch the remote files — so a note that has not
     * changed since it was last uploaded needs **no request at all**, and one that has
     * changed re-uploads conditionally with `If-Match` rather than blindly (CLAUDE.md rule 1).
     *
     * That distinction is why this compares revisions rather than simply re-enqueuing
     * everything: a library of 200 synced notes should cost zero uploads to come back to, not
     * 200.
     *
     * @return how many notes actually owe the server an upload.
     */
    public suspend fun resumeAccount(accountId: String): Int {
        val resumed = bindings.forAccount(accountId)
        var owing = 0
        val now = clock.nowEpochMillis()
        for (binding in resumed) {
            // ⚠️ Before the `when` below, and outside it, because every branch of that `when` is
            // about the note's **PDF**. A note unchanged since its last upload takes the
            // `localRev <= lastSyncedRev` branch and is marked SYNCED — and a lecture recorded
            // into it while signed out would then never upload, on a note the app calls synced.
            enqueuePendingRecordings(binding.noteId)
            val localRev = notes.localRev(binding.noteId)
            when {
                // The note itself is gone — a binding the trash cascade left behind. Nothing to
                // resume, and the drainer drops the row if one ever appears for it.
                localRev == null -> Unit

                // Unchanged since the last upload, and the remote copy was never touched, so
                // this note is already in sync and costs no request to come back to.
                localRev <= binding.lastSyncedRev -> notes.setSyncState(binding.noteId, NoteSyncState.SYNCED, null)

                else -> {
                    enqueue(binding.noteId, OutboxOpType.UPLOAD, localRev, binding.everUploaded, now)
                    notes.setSyncState(binding.noteId, NoteSyncState.PENDING_UPLOAD, null)
                    owing++
                }
            }
        }
        if (owing > 0) scheduler.enqueueOutboxDrain()
        return owing
    }

    /**
     * Whether [accountId] can be sent to at all.
     *
     * Consulted before every enqueue below. An outbox row for an account that cannot send it
     * is the stall described in [pauseAccount] — so rather than trusting callers to check,
     * the enqueue paths refuse. A missing account is also false, which covers the window
     * between a sign-out and whatever notices it.
     */
    private suspend fun canSend(accountId: String): Boolean = accounts.find(accountId)?.isUsable == true

    public suspend fun bindingOf(noteId: DocumentId): RemoteBinding? = bindings.find(noteId)

    /**
     * The account, for callers that need its `defaultFolder` to resolve where a note belongs.
     *
     * Read-only on purpose. The library needs to *ask* where notes go by default; changing it
     * is [dev.inkstone.app.sync.NextcloudAccounts]' business, and routing a write through here
     * would give the outbox's owner an opinion about account settings.
     */
    public suspend fun accountOf(accountId: String): SyncAccount? = accounts.find(accountId)

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
        // A bound note on a signed-out or halted account is editable exactly as before — it
        // simply owes nothing yet, because nothing can be sent. Queuing here is what stranded
        // notes in M5: the row never drains and the worker reports success forever.
        if (!canSend(binding.accountId)) return
        // M12 — the self-healing sweep. Cheap (one indexed query on a table with a handful of
        // rows) and it is what makes a lecture impossible to strand: whatever dropped its outbox
        // row — a sign-out, an unbind, a drain that met a momentarily missing binding — the next
        // save puts it back. Coalescing makes a repeat harmless, because a re-enqueue of the same
        // recording *inherits* `nextAttemptAt` rather than resetting it, so a failing upload
        // cannot become a hot loop against the user's server.
        enqueuePendingRecordings(noteId)
        val localRev = notes.localRev(noteId) ?: return
        if (localRev <= binding.lastSyncedRev && outbox.pendingFor(noteId).isEmpty()) return
        enqueue(noteId, OutboxOpType.UPLOAD, localRev, binding.everUploaded, clock.nowEpochMillis())
        notes.setSyncState(noteId, NoteSyncState.PENDING_UPLOAD, null)
        scheduler.enqueueOutboxDrain()
    }

    /**
     * ⭐ M12 — a recording finished and is kept. Enqueue its upload (docs/M12-AUDIO.md §5).
     *
     * ⚠️ **Not [noteChanged] with a different argument.** That path is about the note's
     * published PDF: it compares `localRev` against `lastSyncedRev` and returns early when the
     * note has not changed — which is the *normal* state after a lecture, because listening to
     * one does not modify the notebook. A recording made in a note nobody wrote in would
     * therefore never upload, and nothing would say so.
     *
     * A note that is not bound is not an error and not a loss: the recording stays READY in the
     * table and [enqueuePendingRecordings] picks it up from [bind], [noteRestored],
     * [resumeAccount] or the next save. Enqueuing here anyway would put a row in front of
     * `OutboxDrainer.drainOne`'s first guard, which removes a row whose note has no binding —
     * silently, and with no report field — and that is doc §5's catastrophe arriving through a
     * different door.
     */
    public suspend fun recordingCaptured(noteId: DocumentId, recordingId: String) {
        val binding = bindings.find(noteId) ?: return
        if (!canSend(binding.accountId)) return
        val localRev = notes.localRev(noteId) ?: binding.lastSyncedRev
        enqueue(
            noteId = noteId,
            opType = OutboxOpType.MEDIA_UPLOAD,
            localRev = localRev,
            everUploaded = binding.everUploaded,
            now = clock.nowEpochMillis(),
            subject = recordingId,
        )
        scheduler.enqueueOutboxDrain()
    }

    /**
     * Every finished recording on [noteId] that the server does not have, queued.
     *
     * The rows are the queue; the `recording` table is the **record of what is owed**, and this
     * is the function that turns the second back into the first. Called from every point where a
     * note (re)gains a usable binding — [bind], [noteRestored], [resumeAccount] — and from
     * [noteChanged] as a sweep.
     *
     * Idempotent by construction: a recording already queued coalesces onto its own row, keyed
     * by its own id, inheriting the backoff.
     */
    public suspend fun enqueuePendingRecordings(noteId: DocumentId) {
        val binding = bindings.find(noteId) ?: return
        if (!canSend(binding.accountId)) return
        val pending = stores.recordings.pendingFor(noteId)
        if (pending.isEmpty()) return
        val localRev = notes.localRev(noteId) ?: binding.lastSyncedRev
        val now = clock.nowEpochMillis()
        for (recording in pending) {
            enqueue(
                noteId = noteId,
                opType = OutboxOpType.MEDIA_UPLOAD,
                localRev = localRev,
                everUploaded = binding.everUploaded,
                now = now,
                subject = recording.recordingId,
            )
        }
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
        // The rename still happens locally; it just does not queue a MOVE that cannot be sent.
        // Resuming re-derives the path from the note's current title, so nothing is lost.
        if (!account.isUsable) return
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
        // Trashing locally is unaffected. The remote DELETE is simply not owed to an account
        // that cannot be reached — and enqueuing one would sit undrainable until sign-in, then
        // delete a file the user may by then have decided to keep.
        if (!canSend(binding.accountId)) return
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
        outbox.remove(noteId, OutboxKey(OutboxOpType.DELETE))
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
        // The DELETE that went with the trashing did not annihilate this note's MEDIA_UPLOAD
        // rows (see [OutboxCoalescing]), but `noteDeletedRemotely` and `pauseAccount` both drop
        // every row — so a restore has to put them back rather than assume they survived.
        enqueuePendingRecordings(noteId)
        scheduler.enqueueOutboxDrain()
    }

    /**
     * ⭐ **The remote file is gone, and this device did not delete it** (M6, SPEC §7.6 step 3's
     * fourth branch).
     *
     * ⚠️ **Not [noteTrashed], and the difference is destructive.** `noteTrashed` enqueues a remote
     * `DELETE`, and [OutboxCoalescing] makes a DELETE **annihilate the pending UPLOAD** — which is
     * correct when the user deletes a note and catastrophic here. SPEC §13.2 row 14's whole premise
     * is a note with *local unsynced edits* whose remote copy was deleted from another device, and
     * annihilating that UPLOAD destroys the only queued route those edits had to the server. The
     * comments on that path all read correct while it happens.
     *
     * There is also nothing to delete: the file is already in Nextcloud's own trash, put there by
     * whoever removed it. A DELETE would be a 404 at best.
     *
     * So: every queued row goes (a trashed note owes the server nothing), and the binding **stays**
     * — with its remote identity cleared. Keeping the row is what makes a restore work: `restore`
     * calls [noteRestored], which re-enqueues an UPLOAD as a *create*, and the note comes back on
     * the server where it was. Clearing the identity is what makes that create legal
     * (`If-None-Match: *` needs [RemoteBinding.everUploaded] to be false) and, just as importantly,
     * is what stops the next reconcile pass concluding the same deletion all over again — the
     * fourth branch requires `everUploaded`, and after this it is false.
     *
     * The local note itself is not touched here. Trashing it is `:app`'s, through
     * `RemoteMergeGate.trashRemotelyDeleted` — 30-day retention, never a hard delete (rule 1).
     */
    public suspend fun noteDeletedRemotely(noteId: DocumentId) {
        val binding = bindings.find(noteId) ?: return
        // See [unbind]. The lecture's row goes with the rest — there is nowhere to put it, since
        // this clears the remote identity — and comes back through [noteRestored], which is the
        // path a restore takes and which re-enqueues pending recordings.
        outbox.removeAll(noteId)
        bindings.upsert(
            binding.copy(
                remoteOcId = null,
                lastSyncedEtag = null,
                lastChecksum = null,
                lastUploadAtEpochMillis = 0L,
                movedFromPath = null,
            ),
        )
        notes.setSyncState(noteId, NoteSyncState.LOCAL_ONLY, null)
    }

    /**
     * The reconciler saw a newer version of the remote file and merged it (M6).
     *
     * The one place `lastSyncedEtag` advances on the **pull** side, and it is separate from the
     * drainer's write for a reason SPEC §7.6's decision table makes exact: whether
     * [RemoteVersion.syncedRev] is set is the difference between *"the local note is now the
     * server's superset and owes it an upload"* and *"the two agree"*. Passing the merged revision
     * when the note is in fact ahead of the uploaded bytes is SPEC §7.6's mid-flight bug moved one
     * layer up — the note would be marked as synced and its work would be invisible to sync
     * forever.
     *
     * @param observed what the download reported. A null field means *"keep what is stored"* rather
     *   than *"clear it"*: a reverse proxy that strips `OC-ETag` and `OC-FileId` must not be able to
     *   erase identity we already have, and a server that publishes no `oc:checksums` must not be
     *   able to disarm SPEC §7.3's weak-ETag fallback.
     * @return the updated binding, or null when the note is not bound.
     */
    public suspend fun remoteAdvanced(noteId: DocumentId, observed: RemoteVersion): RemoteBinding? {
        val binding = bindings.find(noteId) ?: return null
        val updated = binding.copy(
            remoteOcId = observed.ocId ?: binding.remoteOcId,
            lastSyncedEtag = observed.etag ?: binding.lastSyncedEtag,
            lastSyncedRev = observed.syncedRev ?: binding.lastSyncedRev,
            lastChecksum = observed.checksum ?: binding.lastChecksum,
        )
        bindings.upsert(updated)
        unparkUpload(noteId)
        return updated
    }

    /**
     * ⭐ **A pulled note is bound where it already is, and owes the server nothing.**
     *
     * *Added while writing SPEC §13.2's matrix, which found the alternative.* [bind] is *"the user
     * asked to save this note to Nextcloud"* and therefore always enqueues MKCOL then UPLOAD. Using
     * it for a note the reconciler has just **downloaded** produces a binding with no ETag and no
     * `oc:id` — so [RemoteBinding.everUploaded] is false, so the drainer's very next PUT sends
     * `If-None-Match: *` at the file it was created from, gets a **permanent 412**, parks the row
     * for a day and flags the note `CONFLICTED`. A note that has just arrived, byte-identical to
     * the server's copy, reads as a conflict; and because a park is inherited by coalescing, the
     * next real edit to that note never uploads either.
     *
     * So this is the pull-side sibling of [bind]: same binding row, filled in with the version that
     * was actually downloaded, and **no outbox row at all**. Nothing is owed, because the bytes came
     * from the server.
     *
     * `lastUploadAtEpochMillis` is stamped even though nothing was uploaded, and the column's real
     * meaning is why: it is *"we have confirmed this note's file exists on the server"* (see
     * [RemoteBinding.everUploaded], which exists because a proxy can strip both validators from a
     * successful PUT). A download confirms that at least as well as an upload does. The side effect
     * is that the note's first *outgoing* upload waits out SPEC §7.6's 30 s floor, which is correct
     * rather than merely harmless — it has just been written to by another device.
     *
     * @param localRev the revision the installed note is at, which becomes `lastSyncedRev`. Without
     *   it the binding claims revision 0, the next pass reads the note as ahead of the server, and
     *   an upload of bytes the server already has is enqueued on every poll.
     * @return the binding, or null when the account is unknown.
     */
    public suspend fun bindPulled(
        noteId: DocumentId,
        accountId: String,
        remotePath: String,
        observed: RemoteVersion,
        localRev: Long,
    ): RemoteBinding? {
        accounts.find(accountId) ?: return null
        val binding = RemoteBinding(
            noteId = noteId,
            accountId = accountId,
            remotePath = remotePath,
            remoteOcId = observed.ocId,
            lastSyncedEtag = observed.etag,
            lastSyncedRev = localRev,
            lastUploadAtEpochMillis = clock.nowEpochMillis(),
            lastChecksum = observed.checksum,
        )
        bindings.upsert(binding)
        notes.setSyncState(noteId, NoteSyncState.SYNCED, null)
        return binding
    }

    // -----------------------------------------------------------------------

    /**
     * ⚠️ A 412 parks the UPLOAD for a day. **A completed merge is what un-parks it.**
     *
     * SPEC §7.7 rule 3 parks a conflicted note's row rather than retrying it, which is right: the
     * PUT was writing over a version this device had not seen, and repeating it changes nothing.
     * What changes it is a reconcile that downloads and merges that version — after which the same
     * upload carries the union and will succeed. Leaving the park in place makes SPEC §7.6's
     * 412 → reconcile → merge → re-upload cycle stop one step short of finishing, and the note then
     * looks like it is syncing while the server never sees the merge.
     *
     * Coalescing cannot do this: [OutboxCoalescing] deliberately *inherits* `next_attempt_at`,
     * because resetting it on every save is a hot retry loop against the user's server. The
     * distinction is that a backoff is about the transport and a park is about a conflict, and only
     * this method knows the conflict is resolved.
     *
     * `attempts` is carried over rather than cleared. If the row also has transport failures behind
     * it, that history is still true — and this pass has just proved the server is reachable, so
     * being due immediately is not a risk.
     */
    private suspend fun unparkUpload(noteId: DocumentId) {
        val row = outbox.pendingFor(noteId).firstOrNull { it.opType == OutboxOpType.UPLOAD } ?: return
        val now = clock.nowEpochMillis()
        if (row.nextAttemptAt <= now) return
        outbox.reschedule(noteId, row.key, row.attempts, now)
    }

    /**
     * ⭐ **"Sync now" means *reconcile what is owed, then drain* — not *drain whatever happens to
     * be queued*.** The one entry point a user's explicit press may use, and nothing else may.
     *
     * Before this existed, [SyncViewModel.syncNow] was `scheduler.enqueueOutboxDrain()` and a
     * hard-coded *"Syncing…"*, which is a no-op against both of M12's real failure modes:
     *
     * 1. **A row that is not due.** A park is 24 h and [unparkUpload] only ever lifted an
     *    `UPLOAD` — a `MEDIA_UPLOAD` park was structurally unliftable, and `due()` filters on
     *    `next_attempt_at`, so every later press opened no socket at all.
     * 2. **A row that was never written.** The Sync screen counts the **recording** table, which
     *    knows nothing about the outbox, so a lecture with no row shows in the header forever
     *    while no button in the app can queue it. Only [bind], [noteRestored], [resumeAccount]
     *    and [noteChanged] rebuilt the queue — i.e. the user had to *draw a stroke in that note
     *    and save*, and finishing a lecture is the archetypal moment someone puts the tablet down.
     *
     * ⚠️ **Why lifting parks is safe here and nowhere else.** [OutboxCoalescing] inherits
     * `next_attempt_at` on purpose: resetting it on every save is a hot retry loop against the
     * user's own server, and that rule stays exactly as it is. A person pressing a button is the
     * one signal in the system that is **not** a loop — it is rate-limited by a human — so it is
     * the one place a park may be retired without a resolved conflict behind it. Keep it that way:
     * if this ever acquires a second caller that is not a user gesture, the reasoning is void.
     */
    public suspend fun syncNowRequested() {
        val now = clock.nowEpochMillis()
        for (row in outbox.all()) {
            if (row.nextAttemptAt > now) outbox.reschedule(row.noteId, row.key, row.attempts, now)
        }
        // Rebuild the queue from the record of what is owed. `pendingFor` is the table that makes
        // this possible at all, and `enqueuePendingRecordings` is already idempotent per recording.
        for (account in accounts.all()) {
            for (binding in bindings.forAccount(account.id)) {
                enqueuePendingRecordings(binding.noteId)
            }
        }
        scheduler.enqueueOutboxDrain()
    }

    @Suppress("LongParameterList") // The op, and the four facts coalescing decides against.
    private suspend fun enqueue(
        noteId: DocumentId,
        opType: OutboxOpType,
        localRev: Long,
        everUploaded: Boolean,
        now: Long,
        subject: String = NO_SUBJECT,
    ) {
        outbox.mutate(noteId) { pending ->
            OutboxCoalescing.plan(pending, opType, noteId, localRev, everUploaded, now, subject)
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

/**
 * What a pull learned about the remote file (SPEC §7.6 step 3) — [SyncEngine.remoteAdvanced]'s
 * input.
 *
 * Every field is nullable and null means **"keep what is stored"**. That is not laziness: each one
 * has a real way of arriving absent, and in every case the stored value is better than nothing.
 *
 * @property etag the ETag of the bytes actually received, off the GET response. A weak one is
 *   stored *as weak* — [ETag.asIfMatch] then returns null and the upload falls back to comparing
 *   checksums, which is what keeps SPEC §7.3's permanent-412 trap unrepresentable rather than
 *   merely remembered.
 * @property syncedRev the local revision the merge produced, and **only** when the local note is
 *   not ahead of the remote bytes. See the KDoc above.
 */
public data class RemoteVersion(val etag: ETag?, val ocId: String?, val checksum: String?, val syncedRev: Long?)
