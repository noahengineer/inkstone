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
            outbox.remove(entry.noteId, entry.opType)
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
        if (accounts.find(binding.accountId) == null) {
            outbox.remove(entry.noteId, entry.opType)
            bindings.remove(entry.noteId)
            notes.setSyncState(entry.noteId, NoteSyncState.LOCAL_ONLY, null)
            return report.copy(orphaned = report.orphaned + 1)
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
            outbox.remove(entry.noteId, entry.opType)
            report.completed(entry.opType)
        }

        // A successful upload of a note that changed mid-flight. The row stays, its
        // revision is refreshed, and the 30 s interval keeps it honest.
        is OpOutcome.Requeued -> report.completed(entry.opType).copy(requeued = report.requeued + 1)

        is OpOutcome.Deferred -> {
            // Not an error — `attempts` is untouched on purpose (rule 4).
            outbox.reschedule(entry.noteId, entry.opType, entry.attempts, outcome.untilEpochMillis)
            report.copy(deferred = report.deferred + 1)
        }

        is OpOutcome.Retry -> {
            val attempts = entry.attempts + 1
            outbox.reschedule(
                entry.noteId,
                entry.opType,
                attempts,
                outcome.retryAfterMillis?.let { now + it } ?: Backoff.nextAttemptAt(attempts, now, jitter()),
            )
            notes.setSyncState(entry.noteId, NoteSyncState.PENDING_UPLOAD, outcome.failure.describe)
            report.copy(failed = report.failed + 1)
        }

        is OpOutcome.Parked -> {
            // SPEC §7.7 rule 3: "a conflicted note is flagged and skipped; everything else
            // keeps syncing." The row stays so M6 can resolve it; it stops being due.
            outbox.reschedule(entry.noteId, entry.opType, entry.attempts, now + PARK_MILLIS)
            notes.setSyncState(entry.noteId, outcome.state, outcome.reason)
            report.copy(parked = report.parked + 1)
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

            OutboxOpType.DELETE -> performDelete(binding, session)
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
    }
}

/** What one drain pass did. Reported to WorkManager so a failed pass can be retried. */
public data class DrainReport(
    val uploaded: Int = 0,
    val moved: Int = 0,
    val deleted: Int = 0,
    val foldersCreated: Int = 0,
    val requeued: Int = 0,
    val deferred: Int = 0,
    val failed: Int = 0,
    val parked: Int = 0,
    val skipped: Int = 0,
    /**
     * Rows dropped because their account no longer exists — a sign-out, or a Keystore key
     * that can no longer decrypt. Their notes are back to `LOCAL_ONLY`; nothing was lost
     * locally and nothing was touched remotely.
     */
    val orphaned: Int = 0,
    val haltedAccounts: Set<String> = emptySet(),
) {
    /** True when something is still owed and running again later would help. */
    public val shouldRunAgain: Boolean get() = deferred > 0 || failed > 0 || requeued > 0

    internal fun completed(opType: OutboxOpType): DrainReport = when (opType) {
        OutboxOpType.MKCOL -> copy(foldersCreated = foldersCreated + 1)
        OutboxOpType.MOVE -> copy(moved = moved + 1)
        OutboxOpType.UPLOAD -> copy(uploaded = uploaded + 1)
        OutboxOpType.DELETE -> copy(deleted = deleted + 1)
    }
}
