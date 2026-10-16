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
import androidx.work.await
import dev.inkstone.app.handwriting.HandwritingWorker
import dev.inkstone.app.handwriting.PersonalisationWorker
import dev.inkstone.app.search.RecognitionJob
import dev.inkstone.app.search.RecognitionWorker
import dev.inkstone.app.sync.MaterializeWorker
import dev.inkstone.app.sync.OutboxDrainWorker
import dev.inkstone.app.sync.ReconcileWorker
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.WorkScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
     * SPEC §8.3 — the recognition backlog, *"when charging and idle"*.
     *
     * ⚠️ **No network constraint, and that is deliberate.** Recognition is entirely on-device;
     * requiring a connection would stop the batch on exactly the tablet this feature is for —
     * one in a bag, charging overnight, with no wifi. The one part that does need a network is
     * the model download, and that is a user-initiated act on a settings screen, not this.
     *
     * KEEP rather than REPLACE: the work is *"read whatever has not been read"*, so a pending
     * request is not superseded by a newer one, it is the same request.
     */
    fun ensurePeriodicRecognition() {
        workManager.enqueueUniquePeriodicWork(
            RECOGNITION_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RecognitionWorker>(RECOGNITION_PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresDeviceIdle(true)
                        .build(),
                )
                .build(),
        )
    }

    /**
     * The library's first read once the model arrives — one job per note, now, as one batch the
     * download's panel can count (`RecognitionModelController`).
     *
     * Unconstrained: the user asked for it and is watching. That is the same reasoning
     * [enqueueMaterialization] uses, and the reason the periodic version above is the one with
     * the charging constraint. One job per note rather than one for the library, so no single job
     * comes near WorkManager's ten-minute limit on a large one.
     *
     * ⚠️ **`APPEND_OR_REPLACE`, not `KEEP`.** `KEEP` drops the request when that note's job from an
     * earlier batch is still pending, so this batch would be one job short for good and its bar
     * would never reach the end. Appending runs it after the pending one — a pass over pages that
     * are already current costs nothing (`NoteRecogniser`) — and never cancels a pass mid-write,
     * which `REPLACE` would.
     *
     * ⚠️ **Suspends until every row is written.** WorkManager enqueues on a background thread, and the
     * panel counts the batch from the rows: returning first would let it count zero jobs, all of
     * them finished, and say *ready* before anything had been read.
     */
    suspend fun enqueueRecognition(batch: Long, notes: List<DocumentId>) {
        notes.map { noteId ->
            workManager.enqueueUniqueWork(
                "$RECOGNITION_ONE_NAME.${noteId.value}",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<RecognitionWorker>()
                    .setInputData(Data.Builder().putString(RecognitionWorker.KEY_NOTE_ID, noteId.value).build())
                    .addTag(RecognitionJob.TAG)
                    .addTag("${RecognitionJob.BATCH_PREFIX}$batch")
                    .build(),
            )
        }.forEach { it.await() }
    }

    /** Every job [enqueueRecognition] has queued that WorkManager still remembers (about a day). */
    fun recognitionJobs(): Flow<List<RecognitionJob>> =
        workManager.getWorkInfosByTagFlow(RecognitionJob.TAG).map { infos -> infos.map(RecognitionJob::of) }

    /**
     * docs/HANDWRITING-SYNTHESIS.md §5.6 — learning the user's handwriting, *"when charging and
     * idle"*, on [ensurePeriodicRecognition]'s terms and for its reasons: entirely on-device, so no
     * network constraint; KEEP, because the work is *"learn whatever has not been learned"*.
     */
    fun ensurePeriodicHandwriting() {
        workManager.enqueueUniquePeriodicWork(
            HANDWRITING_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HandwritingWorker>(RECOGNITION_PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresDeviceIdle(true)
                        .build(),
                )
                .build(),
        )
    }

    /**
     * docs/HANDWRITING-SYNTHESIS.md §8 — personalisation, **only while charging and idle**, and nothing
     * else in the app ever schedules it: there is no *"personalise now"* on screen, because a fit is
     * minutes of a big core (`PersonaliseNowReceiver` is an adb-only hook for checking a device). Every
     * fifteen minutes, WorkManager's least: a round (P2, P3, P1, a measurement) is four or five
     * five-minute slices on the tablet, so hourly would take most of a night on the charger for one;
     * the constraints, not the period, are what keep it off a tablet in use.
     */
    fun ensurePeriodicPersonalisation() {
        workManager.enqueueUniquePeriodicWork(
            PERSONALISE_PERIODIC_NAME,
            // UPDATE, not KEEP: a changed period or constraint reaches a tablet that already has the job,
            // and the pass's own progress is in its checkpoint, not in WorkManager.
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<PersonalisationWorker>(PERSONALISE_PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresDeviceIdle(true)
                        .build(),
                )
                .build(),
        )
    }

    /**
     * *My handwriting*'s **Learn now**: one pass, unconstrained, because the user asked and is
     * watching. KEEP, so pressing it twice is one pass, not two queued behind each other.
     */
    fun enqueueHandwritingNow() {
        workManager.enqueueUniqueWork(
            HANDWRITING_NOW_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<HandwritingWorker>().build(),
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

        const val RECOGNITION_PERIODIC_NAME = "inkstone.recognition.periodic"
        const val RECOGNITION_ONE_NAME = "inkstone.recognition"
        const val HANDWRITING_PERIODIC_NAME = "inkstone.handwriting.periodic"
        const val HANDWRITING_NOW_NAME = "inkstone.handwriting.now"
        const val PERSONALISE_PERIODIC_NAME = "inkstone.handwriting.personalise"
        const val PERSONALISE_PERIOD_MINUTES = 15L

        /**
         * Six hours. WorkManager will not run it more often than the device is idle and
         * charging anyway, so a shorter period buys nothing and a longer one leaves a
         * newly-installed model waiting a day before old notes become searchable.
         */
        const val RECOGNITION_PERIOD_HOURS = 6L
    }
}
