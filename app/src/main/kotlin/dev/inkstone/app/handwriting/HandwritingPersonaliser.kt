package dev.inkstone.app.handwriting

import android.util.Log
import dev.inkstone.core.handwriting.Personaliser
import dev.inkstone.core.handwriting.quality.Stage
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.InkReader
import dev.inkstone.core.model.RecognitionModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ⭐ docs/HANDWRITING-SYNTHESIS.md phase 5 — one personalisation pass ([Personaliser]) over the profile
 * the learner owns, for at most a budget of wall-clock time. Run by [PersonalisationWorker], which
 * WorkManager starts only while the tablet is charging and idle.
 *
 * - **The profile's own lock for every `profile.json` change** ([HandwritingLearner.editProfile]); the
 *   fits run outside it, so a correction on *My handwriting* never waits for one.
 * - **The writer's copy of the weights** ([HandwritingWriter.weights]): one 3.5 MB read, not two.
 * - **No recognition model, no pass** (D12): nothing can be calibrated or gated without one.
 * - **Logs counts and verdicts, never a word** (§9).
 */
@Singleton
class HandwritingPersonaliser @Inject constructor(
    private val fs: FileSystem,
    private val clock: Clock,
    private val learner: HandwritingLearner,
    private val writer: HandwritingWriter,
    private val reader: InkReader,
    private val models: RecognitionModels,
) {
    /** One pass at a time: a periodic one and a forced one interleaving would fit twice. */
    private val running = Mutex()

    /**
     * @param stop the worker's own *"stop now"*: WorkManager stopping a job whose constraints lapsed —
     *   the tablet unplugged, or picked up. The fit then pauses at a checkpoint rather than being killed.
     */
    suspend fun run(budgetMillis: Long, from: Stage? = null, stop: () -> Boolean = { false }): Personaliser.Outcome? =
        running.withLock { withContext(Dispatchers.Default) { pass(budgetMillis, from, stop) } }

    private suspend fun pass(budgetMillis: Long, from: Stage?, stop: () -> Boolean): Personaliser.Outcome? {
        val weights = writer.weights() ?: return null.also { Log.i(TAG, "pass: no model") }
        // adb's *start a round here* (PersonaliseNowReceiver); never set from the app itself.
        if (from != null) learner.editProfile { it.copy(personal = it.personal.copy(stage = from)) }
        val snapshot = learner.snapshot()
        if (!models.isDownloaded(snapshot.state.language)) {
            Log.i(TAG, "pass: no recognition model")
            return null
        }
        val started = clock.nowEpochMillis()
        val personaliser = Personaliser(fs, snapshot.key, weights, reader, clock::nowEpochMillis, { Log.i(TAG, it) })
        val outcome = personaliser.pass(
            snapshot.samples,
            snapshot.labels,
            snapshot.state,
            outOfTime = { stop() || clock.nowEpochMillis() - started > budgetMillis },
            edit = { change -> learner.editProfile(change) },
        )
        Log.i(TAG, "pass: $outcome in ${clock.nowEpochMillis() - started} ms")
        return outcome
    }

    private companion object {
        const val TAG = "HandwritingPersonal"
    }
}
