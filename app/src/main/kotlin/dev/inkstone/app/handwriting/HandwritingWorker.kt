package dev.inkstone.app.handwriting

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * docs/HANDWRITING-SYNTHESIS.md §5.6 — learning, **never in the editor**.
 *
 * Two schedules, one job ([WorkManagerScheduler][dev.inkstone.app.platform.WorkManagerScheduler]):
 * periodically while the tablet is charging and idle — `RecognitionWorker`'s constraints, for its
 * reasons, and no network because nothing here needs one — and once, unconstrained, when the user
 * presses *Learn now*.
 *
 * ⚠️ **Always `success`.** A missing recognition model is fixed by the user pressing a button, not by
 * WorkManager asking again in thirty seconds — the backoff would *be* the retry loop, the same reason
 * `RecognitionWorker` and a 401 (CLAUDE.md rule 5) do not retry. A pass that ran out of time has
 * recorded where it got to, so the next scheduled one simply continues.
 */
internal class HandwritingWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, HandwritingEntryPoint::class.java)
        entryPoint.learner().learn(budgetMillis = BUDGET_MILLIS)
        return Result.success()
    }

    internal companion object {
        /** WorkManager stops a job at ten minutes; finishing well inside it is what saves progress. */
        const val BUDGET_MILLIS: Long = 8L * 60 * 1000
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface HandwritingEntryPoint {
    fun learner(): HandwritingLearner
}
