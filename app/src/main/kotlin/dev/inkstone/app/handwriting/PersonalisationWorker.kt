package dev.inkstone.app.handwriting

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.inkstone.core.handwriting.quality.Stage

/**
 * docs/HANDWRITING-SYNTHESIS.md §8.1 and §10 — personalisation **only while the tablet is charging and
 * idle** ([WorkManagerScheduler.ensurePeriodicPersonalisation][dev.inkstone.app.platform.WorkManagerScheduler]
 * sets both constraints, and nothing else schedules this worker).
 *
 * A pass fits in slices: it stops at a checkpoint when its [FIT_BUDGET_MILLIS] is spent — leaving the
 * rest of WorkManager's ten minutes for the evaluation a finished fit owes — or when WorkManager says
 * stop because a constraint lapsed ([isStopped]), and the next run carries on from the checkpoint. A
 * process kill in between loses at most one checkpoint interval (`FitPlan.checkpointEvery` iterations,
 * `CrashSafetyTest`).
 *
 * ⚠️ **Always `success`**, for `HandwritingWorker`'s reason: progress is in the checkpoint, and
 * WorkManager's backoff would only be a retry loop.
 */
internal class PersonalisationWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, PersonalisationEntryPoint::class.java)
        val from = inputData.getString(KEY_FROM)?.let { name -> Stage.entries.firstOrNull { it.name == name } }
        entryPoint.personaliser().run(FIT_BUDGET_MILLIS, from) { isStopped }
        return Result.success()
    }

    internal companion object {
        /** `PersonaliseNowReceiver`'s adb-only *start a round at this stage*. */
        const val KEY_FROM = "from"

        /**
         * Five minutes of fitting. A finished rung's acceptance — fidelity on every word no fit saw, and
         * a hundred-odd words through the real gate — takes one to two more on the tablet, and WorkManager
         * stops a job at ten.
         */
        const val FIT_BUDGET_MILLIS: Long = 5L * 60 * 1000
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PersonalisationEntryPoint {
    fun personaliser(): HandwritingPersonaliser
}
