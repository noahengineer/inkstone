package dev.inkstone.app.debug

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * **Debug builds only.** Runs what [InkExportReceiver], [LineReadReceiver] and [RecognitionModelReceiver]
 * are asked to do, as an
 * expedited job, so the work survives a locked tablet.
 *
 * ⚠️ **A receiver's own coroutine is frozen.** Android's cached-apps freezer suspends a background
 * process a few seconds after its broadcast returns, every thread in `do_freezer_trap`, and the
 * work then neither finishes nor fails. On the emulator that looked exactly like an ML Kit deadlock.
 * On the tablet, which is usually locked when these run, it froze the export outright. `goAsync`
 * holds a broadcast open for only about a minute. A running job keeps its process out of the
 * freezer, and an expedited one runs in Doze. Jobs are queued one after another, so several plans
 * can be sent at once.
 *
 * Expedited work on API 30 would need a foreground notification (`getForegroundInfo`). Every device
 * this has run on is newer, and a debug tool does without one.
 */
internal class DebugTaskWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        @Suppress("TooGenericExceptionCaught") // A debug tool reports its failure; it must not crash the app.
        try {
            when (inputData.getString(KEY_TASK)) {
                TASK_EXPORT -> InkExportReceiver.export(applicationContext, inputData.getString(KEY_ARGUMENT))
                TASK_READ -> LineReadReceiver.read(applicationContext, inputData.getString(KEY_ARGUMENT))
                TASK_MODEL -> RecognitionModelReceiver.run(applicationContext, inputData.getString(KEY_ARGUMENT))
                TASK_NEURAL_GATE -> NeuralGateReceiver.run(applicationContext, inputData.getString(KEY_ARGUMENT))
                TASK_MATHS_GATE -> MathsGateReceiver.run(applicationContext, inputData.getString(KEY_ARGUMENT))
                TASK_PERSONALISE -> PersonaliseReceiver.run(applicationContext, inputData.getString(KEY_ARGUMENT))
                else -> Log.w(TAG, "unknown task ${inputData.getString(KEY_TASK)}")
            }
        } catch (failure: Exception) {
            Log.e(TAG, "${inputData.getString(KEY_TASK)} failed", failure)
        }
        // Never a retry: these are run by hand, and a failure is read off the log.
        return Result.success()
    }

    companion object {
        private const val TAG = "DebugTask"
        private const val KEY_TASK = "task"
        private const val KEY_ARGUMENT = "argument"
        private const val QUEUE = "inkstone-debug-tasks"
        const val TASK_EXPORT = "export"
        const val TASK_READ = "read"
        const val TASK_MODEL = "model"
        const val TASK_NEURAL_GATE = "neural-gate"
        const val TASK_MATHS_GATE = "maths-gate"
        const val TASK_PERSONALISE = "personalise"

        /**
         * ⚠️ **Waits for the work to be written down.** WorkManager enqueues on a background thread,
         * and a receiver that returned at once was frozen before that thread ran: the job was never
         * scheduled, and nothing said so. Writing a row takes milliseconds.
         */
        fun enqueue(context: Context, task: String, argument: String?) {
            val request = OneTimeWorkRequestBuilder<DebugTaskWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(Data.Builder().putString(KEY_TASK, task).putString(KEY_ARGUMENT, argument).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
                .result
                .get()
            Log.i(TAG, "queued $task ${argument.orEmpty()}")
        }
    }
}
