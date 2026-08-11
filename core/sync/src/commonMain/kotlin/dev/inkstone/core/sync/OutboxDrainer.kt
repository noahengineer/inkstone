package dev.inkstone.core.sync

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.CredentialStore
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.DocumentMaterializer
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.NextcloudCredential
import dev.inkstone.core.model.NoteSyncState

/**
 * SPEC §7.6 — the drainer. Everything that talks to the server on the app's behalf
 * goes through exactly this loop (CLAUDE.md rule 4).
 *
 * ## The four rules that are not obvious from the schema
 *
 * **1. Rows are markers.** The bytes are read here, at drain time, from
 * `document.pdf` — which the materializer wrote atomically (CLAUDE.md rule 10), so a
 * whole-file read is either the old file or the new one and never a torn ZIP inside a
 * PDF. That is what makes 500 offline edits cost one upload.
 *
 * **2. A 401 halts and does not retry.** SPEC §7.2, CLAUDE.md rule 5. One confirmation
 * call to `/ocs/v2.php/cloud/user` distinguishes "that path 401s" from "this password is
 * dead"; if the password is dead the account goes to `NEEDS_REAUTH` and **the whole
 * drain stops for that account**. Nextcloud's brute-force protection is on by default
 * and shared across a NAT — retrying is the documented way real users block themselves.
 *
 * **3. `local_rev` is checked *after* the 2xx, not before.** This is the bug SPEC §7.6
 * names in the schema comment: the file changes while the PUT is in flight, the 2xx
 * arrives, you mark SYNCED, and the last ten minutes of writing becomes invisible to
 * sync forever. So SYNCED is set **only if `note.local_rev == outbox.local_rev_at_enqueue`**,
 * and otherwise the row is re-enqueued at the new revision.
 *
 * **4. Thirty seconds between uploads of the same note.** SPEC §7.6 resolves "coalesce
 * aggressively" against "upload immediately if online" by making immediate mean *at most
 * once per 30 s*. A note being actively written to defers rather than failing, and
 * deferring does **not** count as an attempt — it is not an error and must not consume
 * backoff.
 */
public class OutboxDrainer(
    private val stores: SyncStores,
    private val credentials: CredentialStore,
    private val materializer: DocumentMaterializer,
    private val fs: FileSystem,
    private val clock: Clock,
    private val transport: NextcloudTransport,
    /** 0..1. A parameter so a test gets a deterministic backoff; see [Backoff]. */
    private val jitter: () -> Double,
) {

    private val accounts get() = stores.accounts
    private val bindings get() = stores.bindings
    private val outbox get() = stores.outbox
    private val notes get() = stores.notes

    /** SPEC §7.6 — "minimum 30 s between uploads of the same note while actively editing". */
    public val minimumUploadIntervalMillis: Long get() = MIN_UPLOAD_INTERVAL_MILLIS

    /**
     * Drain what is due.
     *
     * @param limit how many rows to consider in one pass. WorkManager gives a job ten
     *   minutes; a bounded pass that reschedules is safer than an unbounded one that is
     *   killed halfway, and the outbox survives either way.
     */
    public suspend fun drain(limit: Int = DEFAULT_LIMIT): DrainReport {
        val now = clock.nowEpochMillis()
        val due = outbox.due(now, limit)
        if (due.isEmpty()) return DrainReport()

        var report = DrainReport()
        val sessions = HashMap<String, NextcloudSession?>()
        val halted = HashSet<String>()

        for (entry in due) {
            report = drainOne(entry, sessions, halted, now, report)
        }
        return report
    }

    /**
     * One row: work out whether it can be sent at all, send it, and record what happened.
     *
     * Split out of [drain] so the loop reads as "for each due row, do this" — the three
     * ways a row can be skipped before any request is made are as much a part of SPEC
     * §7.6 as the request itself, and they were burying it.
     */
    @Suppress("ReturnCount")
    // Five ways a row is not sent, each a different fact about the account and each ending the
    // row's life differently: no binding, account gone, account signed out, account unusable,
    // account already halted this pass. Telling them apart is the point — M5 shipped two of
    // them collapsed into one and a signed-out user's notes stalled silently for it. Nesting
    // them would bury the one line that actually performs the op.
    private suspend fun drainOne(
        entry: OutboxEntry,
        sessions: MutableMap<String, NextcloudSession?>,
        halted: MutableSet<String>,
        now: Long,
        report: DrainReport,
    ): DrainReport {
        val binding = bindings.find(entry.noteId)
        if (binding == null) {
            // The note was unbound (or never bound) while this row waited. Nothing is owed,
            // and keeping the row would retry against a path we no longer claim.
            outbox.remove(entry.noteId, entry.key)
            return report
        }
        // ⭐ The account is gone entirely — signed out, or its credential is unreadable
        // because the Keystore key was invalidated. That is **permanent**, not a wait:
        // nothing will ever drain this row, and leaving it queued strands the note in
        // PENDING_UPLOAD forever while the worker reports success. Found on a device in
        // M5 by signing out with work queued; the note claimed to be uploading for as
        // long as anyone cared to watch.
        //
        // So the binding is dropped and the note goes back to LOCAL_ONLY — which is what
        // it now is. Nothing local is destroyed and nothing remote is touched; the copy
        // on the server stays exactly where it is (CLAUDE.md rule 1).
        val account = accounts.find(binding.accountId)
        if (account == null) {
            outbox.remove(entry.noteId, entry.key)
            bindings.remove(entry.noteId)
            notes.setSyncState(entry.noteId, NoteSyncState.LOCAL_ONLY, null)
            return report.copy(orphaned = report.orphaned + 1)
        }

        // ⭐ Signed out — a third case, and it is neither of the two either side of it (M6).
        //
        // Not "gone": the account row is deliberately still there, and so is this binding,
        // because that is what lets one tap resume every note where it left off rather than
        // re-binding a library by hand (see [AccountState.SIGNED_OUT]).
        //
        // Not "a wait" either: falling through to the unusable-account branch below would
        // keep the row queued and flag the note NEEDS_REAUTH, which is both a lie — nobody
        // refused us, the user signed out — and the M5 stall, because the row would sit due
        // forever while the worker reported success.
        //
        // So the row goes and the binding stays. `pauseAccount` already clears these at
        // sign-out; this is for a row that raced it, which is one autosave away.
        if (account.state == AccountState.SIGNED_OUT) {
            outbox.remove(entry.noteId, entry.key)
            notes.setSyncState(entry.noteId, NoteSyncState.PAUSED, null)
            return report.copy(paused = report.paused + 1)
        }

        // SPEC §7.2 — once an account is halted, nothing else is sent to it this pass.
        if (binding.accountId in halted) return report.copy(skipped = report.skipped + 1)

        val session = sessions.getOrPut(binding.accountId) { openSession(binding.accountId) }
        if (session == null) {
            // The account row exists but is unusable — NEEDS_REAUTH, or no uid yet. That
            // *is* a wait, and the note has to say so rather than sitting silently.
            halted += binding.accountId
            notes.setSyncState(entry.noteId, NoteSyncState.NEEDS_REAUTH, UNUSABLE_ACCOUNT)
            return report.copy(skipped = report.skipped + 1)
        }

        return apply(perform(entry, binding, session), entry, binding, halted, now, report)
    }

    /** Every branch here is one line of SPEC §7.6, and each maps to exactly one outcome. */
    @Suppress("LongParameterList") // The outcome, what produced it, and where to record it.
    private suspend fun apply(
        outcome: OpOutcome,
        entry: OutboxEntry,
        binding: RemoteBinding,
        halted: MutableSet<String>,
        now: Long,
        report: DrainReport,
    ): DrainReport = when (outcome) {
        is OpOutcome.Done -> {
            outbox.remove(entry.noteId, entry.key)
            report.completed(entry.opType)
        }

        // A successful upload of a note that changed mid-flight. The row stays, its
        // revision is refreshed, and the 30 s interval keeps it honest.
        is OpOutcome.Requeued -> report.completed(entry.opType).copy(requeued = report.requeued + 1)

        is OpOutcome.Deferred -> {
            // Not an error — `attempts` is untouched on purpose (rule 4).
            outbox.reschedule(entry.noteId, entry.key, entry.attempts, outcome.untilEpochMillis)
            report.copy(deferred = report.deferred + 1)
        }

        is OpOutcome.Retry -> {
            val attempts = entry.attempts + 1
            outbox.reschedule(
                entry.noteId,
                entry.key,
                attempts,
                outcome.retryAfterMillis?.let { now + it } ?: Backoff.nextAttemptAt(attempts, now, jitter()),
            )
            notes.setSyncState(entry.noteId, NoteSyncState.PENDING_UPLOAD, outcome.failure.describe)
            report.copy(failed = report.failed + 1)
        }

        is OpOutcome.Parked -> {
            // SPEC §7.7 rule 3: "a conflicted note is flagged and skipped; everything else
            // keeps syncing." The row stays so M6 can resolve it; it stops being due.
            outbox.reschedule(entry.noteId, entry.key, entry.attempts, now + PARK_MILLIS)
            notes.setSyncState(entry.noteId, outcome.state, outcome.reason)
            report.copy(
                parked = report.parked + 1,
                // ⭐ Only a **412** goes here, not every park. SPEC §7.6's cycle is
                // 412 → reconcile → merge → re-upload, and the reconcile is the step the
                // drainer cannot take itself: it has no scheduler, deliberately, because a
                // `:core:sync` class that could wake work would be one that could decide to
                // retry a credential. So the note id is reported and the *worker* asks.
                //
                // Without this the note still converges — the periodic pass finds the moved
                // root ETag eventually — but "eventually" is fifteen minutes with the note
                // showing `CONFLICTED` the whole time, and the park is a day long.
                conflicted = if (outcome.state == NoteSyncState.CONFLICTED) {
                    report.conflicted + entry.noteId
                } else {
                    report.conflicted
                },
            )
        }

        is OpOutcome.HaltAccount -> {
            halted += binding.accountId
            accounts.setState(binding.accountId, outcome.state, outcome.reason)
            notes.setSyncState(entry.noteId, NoteSyncState.NEEDS_REAUTH, outcome.reason)
            report.copy(haltedAccounts = report.haltedAccounts + binding.accountId)
        }
    }

    // -----------------------------------------------------------------------
    // One op
    // -----------------------------------------------------------------------

    private suspend fun perform(entry: OutboxEntry, binding: RemoteBinding, session: NextcloudSession): OpOutcome =
        when (entry.opType) {
            OutboxOpType.MKCOL -> classify(session.dav.mkcolRecursive(parentOf(binding.remotePath)), session) {
                OpOutcome.Done
            }

            OutboxOpType.MOVE -> performMove(binding, session)

            OutboxOpType.UPLOAD -> performUpload(entry, binding, session)

            OutboxOpType.MEDIA_UPLOAD -> performMediaUpload(entry, binding, session)

            OutboxOpType.DELETE -> performDelete(binding, session)
        }

    /**
     * ⭐ M12 — one recording's **audio and its timeline**, in one op (docs/M12-AUDIO.md §5).
     *
     * ## Why this is not [performUpload] with a different path
     *
     * It is the obvious refactor and every line of it would be wrong:
     *
     * - [performUpload] defers on `binding.lastUploadAtEpochMillis + 30 s`, SPEC §7.6's floor
     *   between two publishes of *the same note*. A recording is not a republish of anything,
     *   and making it wait behind the note's PDF — or worse, stamping that column and making
     *   the note's next real save wait behind a lecture — is a rule applied to the wrong noun.
     * - [finishUpload] writes `lastSyncedRev` and sets `SYNCED`. For a recording both are
     *   lies, and the second is the expensive kind: a note whose PDF is still owed would read
     *   as up to date, which is exactly the silence SPEC §7.6's mid-flight guard exists to
     *   prevent, arrived at from a different direction.
     *
     * ## The order, and why the timeline goes first
     *
     * The audio is what doc §2's server-side watcher keys on, so it is written **last**: a
     * `.opus` that appears before its `.timeline` is a lecture the server may start
     * transcribing with no alignment data, and a transcript with no timeline is just a
     * transcript. Same shape as materialize-before-publish (SPEC §5.1) and as doc §8's
     * transcript-before-delete — write the thing that makes the work *discoverable* last.
     *
     * ## `If-None-Match: *`, and why a 412 here is success
     *
     * CLAUDE.md rule 1 forbids overwriting a remote file without a precondition, and this
     * keeps it: every media PUT is a **create**. But a recording is a write-once immutable
     * object named by a UUIDv7, so *"something is already at this path"* is not the conflict
     * the rule is about — it is this op having partially succeeded before the connection
     * dropped, which is the common retry. Treating that 412 as a conflict would park an
     * uploaded lecture for a day and flag the note; treating it as success reaches the state
     * we wanted. Nobody else writes these paths, so there is no third party to lose to.
     */
    private suspend fun performMediaUpload(
        entry: OutboxEntry,
        binding: RemoteBinding,
        session: NextcloudSession,
    ): OpOutcome {
        // Null means the server already has it, or the recording was discarded under §10's
        // thirty-second floor after this row was enqueued. Either way nothing is owed — and
        // this is what closes the crash window between `markUploaded` and removing the row.
        // See `RecordingUploads.find`.
        val recording = stores.recordings.find(entry.subject) ?: return OpOutcome.Done

        val audio = fs.readBytes(recording.audioPath)
        if (audio.isEmpty()) {
            // The row outlived its file. Retrying cannot produce bytes that are not there, but
            // parking says so on the note rather than dropping the only trace of the lecture.
            return OpOutcome.Parked(NoteSyncState.ERROR, MEDIA_MISSING)
        }
        // A recording with no strokes in it is ordinary — a lecture you listened to without
        // writing — so an absent timeline is an empty one, not a failure.
        val timeline = if (fs.exists(recording.timelinePath)) fs.readBytes(recording.timelinePath) else ByteArray(0)

        val directory = RemoteMedia.directory(binding.remotePath, binding.noteId)
        // Idempotent (405 is success), and needed on every attempt: the note's own folder may
        // exist while `.inkstone-media/<id>/` does not. `performMove` does the same thing for
        // the same reason.
        session.dav.mkcolRecursive(directory)

        val timelineOutcome = session.dav.put(
            remotePath = RemoteMedia.timeline(binding.remotePath, binding.noteId, recording.recordingId),
            bytes = timeline,
            ifNoneMatchStar = true,
            mtimeEpochSeconds = recording.startedAtEpochMillis / MILLIS_PER_SECOND,
            md5Hex = Md5.hex(timeline),
            contentType = WebDavClient.TIMELINE,
        )
        landed(timelineOutcome, session)?.let { return it }

        val audioOutcome = session.dav.put(
            remotePath = RemoteMedia.audio(binding.remotePath, binding.noteId, recording.recordingId),
            bytes = audio,
            ifNoneMatchStar = true,
            mtimeEpochSeconds = recording.startedAtEpochMillis / MILLIS_PER_SECOND,
            md5Hex = Md5.hex(audio),
            contentType = WebDavClient.AUDIO_OGG,
        )
        landed(audioOutcome, session)?.let { return it }

        stores.recordings.markUploaded(recording.recordingId, clock.nowEpochMillis())
        return OpOutcome.Done
    }

    /**
     * Null when [outcome] means *"this file is now on the server"* — a 2xx, or the 412 that a
     * repeated `If-None-Match: *` create produces. Otherwise the failure, classified.
     *
     * Answers "carry on, or here is the outcome to return" rather than a boolean, so a caller
     * cannot observe a failure and forget to stop: the only way to see one is to be handed the
     * [OpOutcome] that reports it.
     */
    private suspend fun landed(outcome: DavOutcome<UploadReceipt>, session: NextcloudSession): OpOutcome? =
        when (outcome) {
            is DavOutcome.Ok -> null

            is DavOutcome.Failed ->
                if (outcome.error is DavFailure.PreconditionFailed) null else failureOutcome(outcome.error, session)
        }

    /**
     * SPEC §7.6 — MOVE drains before UPLOAD, and SPEC §7.5 — `Overwrite: F`.
     *
     * A MOVE with nothing to move (the note was bound and renamed before its first
     * upload) is a no-op rather than a 404: the UPLOAD behind it will create the file at
     * the new path, which is the same end state with one request instead of two errors.
     */
    private suspend fun performMove(binding: RemoteBinding, session: NextcloudSession): OpOutcome {
        val from = binding.movedFromPath
        if (from == null || from == binding.remotePath || !binding.everUploaded) {
            bindings.upsert(binding.copy(movedFromPath = null))
            return OpOutcome.Done
        }
        // The destination's folder may not exist yet — a move into a folder created on
        // this device while offline. MKCOL is idempotent (405 means "already there").
        session.dav.mkcolRecursive(parentOf(binding.remotePath))
        moveMediaDirectory(from, binding, session)
        return classify(session.dav.move(from, binding.remotePath), session) { receipt ->
            bindings.upsert(
                binding.copy(
                    movedFromPath = null,
                    // SPEC §7.4 rule 2 — a file's ETag does not change when it moves, so the
                    // stored one stays valid. Only take a new one if the server sent it.
                    lastSyncedEtag = receipt.etag ?: binding.lastSyncedEtag,
                    remoteOcId = receipt.ocId ?: binding.remoteOcId,
                ),
            )
            OpOutcome.Done
        }
    }

    /**
     * ⭐ M12 — carry the note's `.inkstone-media/<documentId>/` with it when the note moves.
     *
     * ⚠️ **`performMove` moved `binding.remotePath` and nothing else**, so a note dragged into
     * another folder left its recordings behind — and the next recording's `mkcolRecursive` then
     * created a *second* media directory beside the new location, splitting one note's lectures
     * across two places with nothing reporting it. `RemoteMedia`'s KDoc claimed the binding "is
     * what moves the directory if the note moves"; nothing implemented that half.
     *
     * **A rename is not a move here.** [RemoteMedia.directory] is built from the parent folder and
     * the `documentId` — never the filename, which is the whole point of §6's identity rule — so
     * renaming a note within its folder leaves the media path unchanged and this does nothing.
     * Only a change of folder moves anything.
     *
     * ⚠️ **Failure is deliberately not fatal to the note's MOVE.** The recordings are still on the
     * server either way, just in the old folder; the note moving is the user-visible thing and must
     * not be held hostage to a sidecar. A 404 is the ordinary case — most notes have no recordings
     * at all — and a 412 means the bug above already made a second directory, which is a state to
     * leave alone rather than merge blindly. The server-side watcher keys on the `<documentId>`
     * path segment rather than on "sits beside this PDF" precisely so that none of this can strand
     * a lecture.
     */
    private suspend fun moveMediaDirectory(from: String, binding: RemoteBinding, session: NextcloudSession) {
        val fromDir = RemoteMedia.directory(from, binding.noteId)
        val toDir = RemoteMedia.directory(binding.remotePath, binding.noteId)
        if (fromDir == toDir) return
        session.dav.mkcolRecursive(parentOf(toDir))
        session.dav.move(fromDir, toDir)
    }

    @Suppress("ReturnCount")
    // Four guard clauses before the upload, each a different reason not to send it.
    // Nesting them would bury the one line that actually PUTs.
    private suspend fun performUpload(
        entry: OutboxEntry,
        binding: RemoteBinding,
        session: NextcloudSession,
    ): OpOutcome {
        val now = clock.nowEpochMillis()

        // Rule 4 — the 30 s floor. Deferring, not failing: attempts is not touched.
        val earliest = binding.lastUploadAtEpochMillis + MIN_UPLOAD_INTERVAL_MILLIS
        if (binding.lastUploadAtEpochMillis > 0 && now < earliest) {
            return OpOutcome.Deferred(earliest)
        }

        // Rule 1 — the bytes are produced here, not carried in the row. Materialize first
        // so `document.pdf` is not one session stale (SPEC §5.1).
        val materialized = materializer.materialize(entry.noteId)
        val bytes = fs.readBytes(materialized.path)
        if (bytes.isEmpty()) {
            return OpOutcome.Retry(DavFailure.Malformed("materialized ${entry.noteId.value} to zero bytes"), null)
        }
        val checksum = Md5.hex(bytes)

        val outcome = session.dav.put(
            remotePath = binding.remotePath,
            bytes = bytes,
            // CLAUDE.md rule 1: never overwrite a remote file without If-Match. A weak
            // ETag yields null from asIfMatch — SPEC §7.3 — and the checksum is what
            // stands in for it, which is why lastChecksum is stored.
            ifMatch = binding.lastSyncedEtag,
            ifNoneMatchStar = !binding.everUploaded,
            mtimeEpochSeconds = now / MILLIS_PER_SECOND,
            md5Hex = checksum,
        )

        return classify(outcome, session) { receipt ->
            finishUpload(entry, binding, receipt, materialized.localRev, checksum, now)
        }
    }

    /**
     * SPEC §7.6's mid-flight guard — rule 3 in the class KDoc.
     *
     * The comparison is against the revision the **bytes that were actually uploaded**
     * were built from ([uploadedRev], which the materializer reports), not against the
     * revision that happened to be current when the row was enqueued. Those differ
     * whenever the note was edited between enqueue and drain, which is the common case
     * for a note that has been coalescing all afternoon.
     */
    private suspend fun finishUpload(
        entry: OutboxEntry,
        binding: RemoteBinding,
        receipt: UploadReceipt,
        uploadedRev: Long,
        checksum: String,
        now: Long,
    ): OpOutcome {
        bindings.upsert(
            binding.copy(
                remoteOcId = receipt.ocId ?: binding.remoteOcId,
                lastSyncedEtag = receipt.etag ?: binding.lastSyncedEtag,
                lastSyncedRev = uploadedRev,
                lastUploadAtEpochMillis = now,
                lastChecksum = checksum,
                movedFromPath = null,
            ),
        )

        val current = notes.localRev(entry.noteId)
        return if (current == null || current == uploadedRev) {
            notes.setSyncState(entry.noteId, NoteSyncState.SYNCED, null)
            OpOutcome.Done
        } else {
            // The note changed while the PUT was in the air. Marking SYNCED here is the
            // bug SPEC §7.6 names; instead the row is refreshed to the new revision.
            outbox.mutate(entry.noteId) { pending ->
                OutboxCoalescing.plan(
                    pending = pending,
                    request = OutboxOpType.UPLOAD,
                    noteId = entry.noteId,
                    localRev = current,
                    everUploaded = true,
                    now = now,
                )
            }
            notes.setSyncState(entry.noteId, NoteSyncState.PENDING_UPLOAD, null)
            OpOutcome.Requeued
        }
    }

    /**
     * Remote DELETE — soft on Nextcloud, so the file lands in the server's trash
     * (SPEC §7.7). A 404 is success: something already removed it, and reporting an
     * error would leave the row retrying against a file that is not there.
     */
    private suspend fun performDelete(binding: RemoteBinding, session: NextcloudSession): OpOutcome {
        val path = binding.movedFromPath ?: binding.remotePath
        val outcome = session.dav.delete(path)
        if (outcome is DavOutcome.Failed && outcome.error is DavFailure.NotFound) {
            bindings.remove(binding.noteId)
            return OpOutcome.Done
        }
        return classify(outcome, session) {
            bindings.remove(binding.noteId)
            OpOutcome.Done
        }
    }

    // -----------------------------------------------------------------------
    // Failure policy
    // -----------------------------------------------------------------------

    private suspend inline fun <T> classify(
        outcome: DavOutcome<T>,
        session: NextcloudSession,
        onSuccess: (T) -> OpOutcome,
    ): OpOutcome = when (outcome) {
        is DavOutcome.Ok -> onSuccess(outcome.value)
        is DavOutcome.Failed -> failureOutcome(outcome.error, session)
    }

    @Suppress("CyclomaticComplexMethod")
    // One branch per row of SPEC §7.5's response table plus §7.2's. Collapsing them
    // would mean losing the one-to-one correspondence with the spec, which is the only
    // way to check this is right.
    private suspend fun failureOutcome(failure: DavFailure, session: NextcloudSession): OpOutcome = when (failure) {
        // SPEC §7.2 — confirm once, then halt. Never a second credential attempt.
        DavFailure.Unauthorized -> when (session.confirmStillAuthorised()) {
            false -> OpOutcome.HaltAccount(AccountState.NEEDS_REAUTH, failure.describe)

            // The credential works, so this path is forbidden to us specifically —
            // a withdrawn share, or a folder an admin locked. Not a reauth.
            true -> OpOutcome.Parked(NoteSyncState.ERROR, "The server refused access to this file.")

            // Could not ask. Conclude nothing (see confirmStillAuthorised) and back off.
            null -> OpOutcome.Retry(failure, null)
        }

        // SPEC §7.5 — "412 | Conflict — §7.7. Never blind-retry." M6 resolves; M5 flags
        // and skips, which is SPEC §7.7 rule 3 ("never block sync on user input").
        is DavFailure.PreconditionFailed -> OpOutcome.Parked(
            NoteSyncState.CONFLICTED,
            "Changed on the server since this device last saw it.",
        )

        // SPEC §7.5 — "507 | Quota exceeded; pause that account's uploads."
        DavFailure.QuotaExceeded -> OpOutcome.Parked(NoteSyncState.ERROR, failure.describe)

        is DavFailure.RateLimited -> OpOutcome.Retry(failure, failure.retryAfterSeconds?.times(MILLIS_PER_SECOND))

        is DavFailure.Locked -> OpOutcome.Retry(failure, null)

        is DavFailure.Transport -> OpOutcome.Retry(failure, null)

        is DavFailure.Server -> if (failure.isRetryable) {
            OpOutcome.Retry(failure, null)
        } else {
            OpOutcome.Parked(NoteSyncState.ERROR, failure.describe)
        }

        is DavFailure.Forbidden -> OpOutcome.Parked(NoteSyncState.ERROR, failure.describe)

        is DavFailure.NotFound -> OpOutcome.Parked(NoteSyncState.ERROR, failure.describe)

        is DavFailure.Malformed -> OpOutcome.Retry(failure, null)
    }

    /**
     * An authenticated session, or null when the account cannot be used at all.
     *
     * Null covers three cases and all three mean "stop touching this account": it is
     * flagged `NEEDS_REAUTH`, its credential is gone from the Keystore, or its uid was
     * never resolved (SPEC §0.1 #3 — [NextcloudSession] refuses to be built without one).
     */
    private suspend fun openSession(accountId: String): NextcloudSession? {
        val account = accounts.find(accountId) ?: return null
        if (!account.isUsable) return null
        val credential: NextcloudCredential = credentials.get(accountId) ?: return null
        if (credential.uid.isEmpty()) return null
        return NextcloudSession(account, credential, transport)
    }

    private fun parentOf(remotePath: String): String = remotePath.substringBeforeLast('/', "")

    /** What one op did. Every variant maps to exactly one thing the drain loop then does. */
    private sealed interface OpOutcome {
        data object Done : OpOutcome

        /** A 2xx whose note changed mid-flight (SPEC §7.6). The row was refreshed, not removed. */
        data object Requeued : OpOutcome

        /** Not an error — the 30 s floor. **Does not consume an attempt.** */
        data class Deferred(val untilEpochMillis: Long) : OpOutcome

        data class Retry(val failure: DavFailure, val retryAfterMillis: Long?) : OpOutcome

        /** Flagged and skipped, so everything else keeps syncing (SPEC §7.7 rule 3). */
        data class Parked(val state: NoteSyncState, val reason: String) : OpOutcome

        /** SPEC §7.2 — stop this account entirely for this drain and until re-login. */
        data class HaltAccount(val state: AccountState, val reason: String) : OpOutcome
    }

    private companion object {
        const val MIN_UPLOAD_INTERVAL_MILLIS = 30_000L
        const val MILLIS_PER_SECOND = 1000L
        const val DEFAULT_LIMIT = 50

        /** How long a flagged-and-skipped row stays out of the way. A day; M6 will resolve it sooner. */
        const val PARK_MILLIS = 24L * 60 * 60 * 1000

        const val UNUSABLE_ACCOUNT = "Sign in to this Nextcloud account again to resume syncing."

        /**
         * M12 — the queued lecture's file is not on disk.
         *
         * Parked rather than dropped. The row is the last trace that a recording was made at
         * all, and a user who is told *"a recording could not be uploaded"* can go looking;
         * one whose row was silently removed cannot. Retrying would not help — no number of
         * attempts produces bytes that are not there — which is why this is a park and not a
         * [OpOutcome.Retry].
         */
        const val MEDIA_MISSING = "A recording could not be uploaded: its audio file is missing."
    }
}

/** What one drain pass did. Reported to WorkManager so a failed pass can be retried. */
public data class DrainReport(
    val uploaded: Int = 0,
    val moved: Int = 0,
    val deleted: Int = 0,
    val foldersCreated: Int = 0,
    /**
     * M12 — recordings whose audio **and** timeline both reached the server.
     *
     * Its own counter rather than folded into [uploaded], because the two answer different
     * questions and only one of them is *"is this note's published PDF current"*. A run that
     * uploaded three lectures and no notes should not read as three note uploads.
     */
    val mediaUploaded: Int = 0,
    val requeued: Int = 0,
    val deferred: Int = 0,
    val failed: Int = 0,
    val parked: Int = 0,
    val skipped: Int = 0,
    /**
     * Rows dropped because their account no longer exists — a Keystore key that can no
     * longer decrypt, or a row that outlived its account. Their notes are back to
     * `LOCAL_ONLY` and their bindings are gone; nothing was lost locally and nothing was
     * touched remotely.
     */
    val orphaned: Int = 0,
    /**
     * Rows dropped because the user signed out of their account (M6).
     *
     * Distinct from [orphaned] because the **binding survives** — which is what makes
     * signing back in resume every note instead of re-binding them by hand.
     */
    val paused: Int = 0,
    val haltedAccounts: Set<String> = emptySet(),
    /**
     * ⭐ Notes whose PUT came back **412** (M6).
     *
     * SPEC §7.7's resolution for one of these is a reconcile of that note: download the version
     * this device has not seen, merge it, and let the same UPLOAD row carry the union. The
     * drainer cannot schedule that itself — see the `Parked` branch — so it names the notes and
     * `OutboxDrainWorker` asks `WorkScheduler.enqueueReconcile(noteId)` for each.
     *
     * ⚠️ Deliberately **not** part of [shouldRunAgain]. A 412 is not something a retry of the
     * *same* PUT can fix, and folding it in would put the conflict on WorkManager's backoff
     * ladder — which is the shape CLAUDE.md rule 5 forbids for credentials and is no better here.
     */
    val conflicted: Set<DocumentId> = emptySet(),
) {
    /** True when something is still owed and running again later would help. */
    public val shouldRunAgain: Boolean get() = deferred > 0 || failed > 0 || requeued > 0

    internal fun completed(opType: OutboxOpType): DrainReport = when (opType) {
        OutboxOpType.MKCOL -> copy(foldersCreated = foldersCreated + 1)
        OutboxOpType.MOVE -> copy(moved = moved + 1)
        OutboxOpType.UPLOAD -> copy(uploaded = uploaded + 1)
        OutboxOpType.MEDIA_UPLOAD -> copy(mediaUploaded = mediaUploaded + 1)
        OutboxOpType.DELETE -> copy(deleted = deleted + 1)
    }
}
