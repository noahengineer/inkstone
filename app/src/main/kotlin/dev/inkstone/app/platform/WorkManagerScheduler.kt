package dev.inkstone.app.platform

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.inkstone.app.sync.MaterializeWorker
import dev.inkstone.app.sync.OutboxDrainWorker
import dev.inkstone.app.sync.ReconcileWorker
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.WorkScheduler
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SPEC §2.4 — the WorkManager half of "sync runs under WorkManager only".
 *
 * ## Why `KEEP` and not `REPLACE`
 *
 * [enqueueOutboxDrain] is called from every autosave of a bound note. `REPLACE` would
 * cancel the drain that is currently uploading and start a new one — so a note being
 * actively written to would never finish an upload at all, which is the opposite of what
 * calling it on every save is meant to achieve. `KEEP` says "a drain is already coming",
 * which is true, and the outbox rows are what carry the work across.
 */
@Singleton
class WorkManagerScheduler @Inject constructor(private val workManager: WorkManager) : WorkScheduler {

    override fun enqueueOutboxDrain() {
        workManager.enqueueUniqueWork(
            OutboxDrainWorker.NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<OutboxDrainWorker>()
                .setConstraints(networkRequired)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
    }

    /**
     * SPEC §7.6 — *"Background: WorkManager periodic ≥15 min."*
     *
     * The safety net, not the mechanism. An edit enqueues a one-time drain immediately;
     * this exists so a device that was offline when the last row was written still uploads
     * once the network comes back, even if the app is never opened again.
     *
     * Fifteen minutes is WorkManager's floor, not a choice — a shorter period is silently
     * clamped, which would make a smaller number here a lie in the code.
     */
    fun ensurePeriodicDrain() {
        workManager.enqueueUniquePeriodicWork(
            OutboxDrainWorker.PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<OutboxDrainWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(networkRequired)
                .build(),
        )
    }

    /**
     * ⭐ SPEC §7.6's pull side (M6) — look for remote changes now.
     *
     * `KEEP` for [enqueueOutboxDrain]'s reason: a pass already coming will see whatever this one
     * would have, because the work is derived from the server's state rather than from a queue, and
     * `REPLACE` would cancel a pass that is mid-download.
     */
    override fun enqueueReconcile() {
        workManager.enqueueUniqueWork(
            ReconcileWorker.NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ReconcileWorker>()
                .setConstraints(networkRequired)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
    }

    /**
     * SPEC §7.7 — the 412 path. `If-Match` failed, so somebody else changed the file, so this is a
     * conflict and not a retry (CLAUDE.md rule 1).
     *
     * Uniquely named per note so two 412s on two notes cannot cancel one another. The pass is a
     * whole-account one; [ReconcileWorker.nameFor] explains why that is the right shape and what it
     * costs.
     */
    override fun enqueueReconcile(noteId: DocumentId) {
        workManager.enqueueUniqueWork(
            ReconcileWorker.nameFor(noteId),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ReconcileWorker>()
                .setConstraints(networkRequired)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
    }

    /**
     * The periodic poll (SPEC §7.6). Registered from `InkstoneApplication`, like [ensurePeriodicDrain].
     *
     * Fifteen minutes because that is WorkManager's floor and a smaller number here would be a lie
     * in the code — it is silently clamped. The *foreground* poll is [dev.inkstone.app.sync.PollTicker]
     * and runs at `capabilities.core.pollinterval`, which is where the sixty-second figure SPEC §7.6
     * asks for actually lives.
     *
     * ⚠️ **`setBackoffCriteria` explicitly**, unlike [ensurePeriodicDrain], which omits it and
     * silently inherits EXPONENTIAL/30 s. Stating it is the point rather than the value: this is the
     * job whose retry ladder CLAUDE.md rule 5 cares about, so the ladder is written down where
     * somebody reading the rule can check it.
     */
    fun ensurePeriodicReconcile() {
        workManager.enqueueUniquePeriodicWork(
            ReconcileWorker.PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReconcileWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(networkRequired)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
    }

    override fun enqueueMaterialization(noteId: DocumentId) {
        workManager.enqueueUniqueWork(
            MaterializeWorker.nameFor(noteId),
            // REPLACE, unlike the drain: materializing produces the newest bytes from the
            // journal, so a pending job for the same note is strictly superseded by this
            // one. Keeping the older request would republish an older revision.
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<MaterializeWorker>()
                .setInputData(Data.Builder().putString(MaterializeWorker.KEY_NOTE_ID, noteId.value).build())
                .build(),
        )
    }

    /**
     * No network, no request. This is what makes airplane-mode editing free: the drainer
     * is never woken to discover it cannot connect.
     */
    private val networkRequired: Constraints
        get() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private companion object {
        const val BACKOFF_SECONDS = 30L
        const val PERIOD_MINUTES = 15L
    }
}
