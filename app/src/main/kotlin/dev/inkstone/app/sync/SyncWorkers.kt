package dev.inkstone.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.WorkScheduler
import dev.inkstone.core.sync.OutboxDrainer
import dev.inkstone.core.sync.Reconciler

/**
 * SPEC §2.4 — *"Sync: **WorkManager only**"*, because it must survive process death.
 *
 * ## Why an `EntryPoint` rather than `@HiltWorker`
 *
 * `@HiltWorker` needs `androidx.hilt:hilt-work` and a `Configuration.Provider` on the
 * `Application`. That is a new pinned coordinate for two workers that each need three
 * objects, and SPEC §0.2 makes adding a coordinate a deliberate act rather than a
 * convenience. `EntryPointAccessors.fromApplication` reaches the same singleton graph
 * with no new dependency and no custom `WorkerFactory` to keep in step.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SyncWorkEntryPoint {
    fun drainer(): OutboxDrainer

    /**
     * The single-writer gate (M6). [MaterializeWorker] publishes through it rather than through
     * `PdfPublisher`, because an open note has an editor that also writes both files.
     *
     * Since M6's gate-5 fix the host **is** the app's only `DocumentMaterializer`, so
     * `OutboxDrainer` goes through it too — it used to hold the raw publisher, which is how an
     * upload came to record a revision the server did not have.
     */
    fun sessions(): NoteSessionHost

    /** SPEC §7.6's pull side (M6). See [ReconcileWorker]. */
    fun reconciler(): Reconciler

    /** So a 412 can ask for the pass that resolves it — see [OutboxDrainWorker.doWork]. */
    fun scheduler(): WorkScheduler
}

/**
 * Drains the outbox (SPEC §7.6). The **only** thing in the app that talks to Nextcloud
 * (CLAUDE.md rule 4).
 */
class OutboxDrainWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, SyncWorkEntryPoint::class.java)
        val report = entryPoint.drainer().drain()

        // ⭐ SPEC §7.6's 412 → reconcile → merge → re-upload cycle, and this is the hop that
        // closes it. The drainer has no scheduler on purpose (a `:core:sync` class that could
        // wake work is one that could decide to retry a credential), so it names the notes whose
        // PUT was refused and this asks for a pass over each. `ExistingWorkPolicy` is per-note —
        // see `ReconcileWorker.nameFor` — so two conflicts do not cancel one another.
        for (noteId in report.conflicted) entryPoint.scheduler().enqueueReconcile(noteId)

        return when {
            // ⚠️ SPEC §7.2 / CLAUDE.md rule 5. An account halted on a 401 must **not** be
            // retried — WorkManager's backoff would become exactly the credential retry
            // loop that gets real users IP-blocked by their own server. `success` here
            // means "this pass is finished", not "everything uploaded": the account is
            // flagged NEEDS_REAUTH and the UI asks for a login.
            report.haltedAccounts.isNotEmpty() -> Result.success()

            // Something is still owed and running later would help. WorkManager's own
            // backoff decides *when*; the rows' `next_attempt_at` decides whether a given
            // row is actually due, so a too-early wake-up is a cheap no-op rather than a
            // request.
            report.shouldRunAgain -> Result.retry()

            else -> Result.success()
        }
    }

    companion object {
        /** Unique work name — a second enqueue while one is pending replaces it, never queues a duplicate. */
        const val NAME: String = "inkstone.outbox.drain"

        /** The periodic safety net. SPEC §7.6: *"Background: WorkManager periodic ≥15 min."* */
        const val PERIODIC_NAME: String = "inkstone.outbox.periodic"
    }
}

/**
 * ⭐ **SPEC §7.6's pull side (M6) — the mirror of [OutboxDrainWorker].**
 *
 * Looks for remote changes on every *usable* account, merges what it finds, and — when a merge
 * leaves the local note ahead of the server — asks the outbox to upload. It performs no remote
 * write itself; CLAUDE.md rule 4 has no pull-side exemption.
 *
 * The cheap case is the common one and that is what makes a periodic job affordable at all: one
 * `PROPFIND Depth:0` per account, and if its ETag matches the stored cursor, nothing else (SPEC
 * §7.4 rule 1). There is no "am I online" question to ask first either — `NetworkType.CONNECTED`
 * on the request answers it, which is what keeps airplane-mode editing free of requests.
 */
class ReconcileWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, SyncWorkEntryPoint::class.java)
        val report = entryPoint.reconciler().reconcile()

        return when {
            // ⚠️ SPEC §7.2 / CLAUDE.md rule 5, and it is the same sentence [OutboxDrainWorker]
            // makes for the same reason. An account halted on a 401 must **not** be retried:
            // WorkManager's backoff would become exactly the credential retry loop that gets real
            // users IP-blocked by their own server, on a doubling ladder, against an instance whose
            // brute-force protection is on by default and shared across a NAT. `success` means
            // "this pass is finished", not "everything is up to date" — the account is flagged
            // NEEDS_REAUTH, there is a "Needs attention" row for it, and the UI asks for a login.
            //
            // This is checked **before** `shouldRunAgain` deliberately. A pass that halted one
            // account and deferred a file on another is still a pass that must not come back on
            // backoff, because the halted account is what the backoff would be retrying against.
            report.haltedAccounts.isNotEmpty() -> Result.success()

            // Something is still owed and running later would help — an unmerged download, a
            // directory the limit stopped short of, or another pass already in flight when this one
            // asked. `ReconcileReport.shouldRunAgain` deliberately excludes skipped and halted
            // accounts **and every 401**, and deliberately includes `busy`; see it.
            //
            // `busy` is why this line is reached far more often than it used to be, and why it is
            // cheap when it is: a turned-away pass sends no request at all, so the cost of the
            // three work names racing is a wake-up rather than a walk.
            //
            // ⚠️ The 401 exclusion is the one that shipped wrong, and this line is where it landed.
            // SPEC §7.2's confirmation call has three answers and only "the credential is dead"
            // halts the account, so a 401 the probe answered `true` or could not reach left
            // `haltedAccounts` empty with the 401 counted as a *failure* — and fell through to here.
            // `ReconcileReport.refused` exists so that arithmetic cannot be got wrong again.
            report.shouldRunAgain -> Result.retry()

            else -> Result.success()
        }
    }

    companion object {
        /** Unique work name — a second enqueue while one is pending is dropped, never queued. */
        const val NAME: String = "inkstone.reconcile"

        /** The periodic poll. SPEC §7.6: *"Background: WorkManager periodic ≥15 min."* */
        const val PERIODIC_NAME: String = "inkstone.reconcile.periodic"

        /**
         * SPEC §7.7 — the work name for the pass a 412 on [noteId] schedules.
         *
         * Named per note so that two 412s on two notes do not cancel each other, which
         * `ExistingWorkPolicy.KEEP` under one name would do — and losing one of them means that
         * note's conflict is not resolved until the next periodic wake-up.
         *
         * ⚠️ The pass itself is **not** narrowed to that note, because [Reconciler] has no per-note
         * entry point: a reconcile is defined per account, from the roots down. That is not a
         * shortcut. A 412 means the remote file changed, which moved its directory's ETag and the
         * root's, so the pass is going to walk to that note anyway — and if nothing *else* changed,
         * SPEC §7.4 rule 1's pruning makes the rest of the tree one `PROPFIND Depth:0` plus one
         * `Depth:1` per directory on the path. The saving from a narrower call would be a request or
         * two; the cost would be a second code path through step 3.
         */
        fun nameFor(noteId: DocumentId): String = "inkstone.reconcile.${noteId.value}"
    }
}

/**
 * SPEC §5.1 — rewrite `document.pdf` off the ink path.
 *
 * Materialization also happens in the editor's `onStop`, which covers the ordinary case.
 * This is for the ones it does not: a note bound to Nextcloud from the library without
 * ever being opened, and "idle while charging".
 *
 * ⚠️ **Through [NoteSessionHost], never through `PdfPublisher` directly.** For a note the
 * user has open that is two writers over one pair of files, and the failure is silent: the
 * materializer reads `note.inkstone`, embeds it and records its hash in the XMP marker, so a bundle
 * rewritten by the editor in between leaves this worker publishing a marker that describes a bundle
 * which no longer exists — read at the next library rebuild as `Recovered(BUNDLE_HASH_MISMATCH)`,
 * which is a lossy REPLACE that then wins the next sync. The host holds the note's mutex and hands
 * the job to the live editor when there is one.
 */
class MaterializeWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val noteId = inputData.getString(KEY_NOTE_ID)?.let(::DocumentId) ?: return Result.failure()
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, SyncWorkEntryPoint::class.java)

        @Suppress("TooGenericExceptionCaught")
        // Materializing tessellates every stroke and re-serialises a PDF; it can fail for
        // reasons from a missing note to a full disk. Retrying is right for all of them and
        // the note is untouched either way — `document.pdf` is written atomically, so a
        // failed pass leaves the previous published file exactly as it was.
        return try {
            entryPoint.sessions().materialize(noteId)
            Result.success()
        } catch (failure: Exception) {
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        const val KEY_NOTE_ID: String = "noteId"
        private const val MAX_ATTEMPTS = 3

        fun nameFor(noteId: DocumentId): String = "inkstone.materialize.${noteId.value}"
    }
}
