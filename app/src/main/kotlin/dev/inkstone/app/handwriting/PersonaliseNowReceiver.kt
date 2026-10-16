package dev.inkstone.app.handwriting

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * docs/HANDWRITING-SYNTHESIS.md H5 — one personalisation pass **now**, for checking a device by hand;
 * reachable from `adb` and nothing else.
 *
 * ```
 * adb shell am broadcast -n dev.inkstone/.app.handwriting.PersonaliseNowReceiver [--es from P2]
 * ```
 *
 * ⚠️ **Why it exists in a release build at all.** A fit is minutes of arithmetic, and a *debug* build is
 * no use for it: on the tablet it did not reach a checkpoint four iterations on in ten minutes, where the
 * release build did 140 in five (§20). And the release worker is periodic, which WorkManager will not run
 * *"before schedule"* even when JobScheduler forces it. So the device check needs a release-build trigger.
 *
 * ⭐ **Why it is not a way round *"only while charging and idle"*.** The manifest guards it with
 * `android.permission.DUMP`, which the shell holds and no installed app can be granted — so the only
 * thing that can send it is a person at a computer with the tablet plugged in, exactly like
 * `cmd jobscheduler run -f`. Nothing in the app sends it, and nothing on screen offers it.
 */
class PersonaliseNowReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Not expedited: on API 30 that needs a foreground notification this worker does not have.
        // `from` starts a round at that stage now, rather than when the profile has grown by a quarter.
        val from = intent.getStringExtra(PersonalisationWorker.KEY_FROM)
        val request = OneTimeWorkRequestBuilder<PersonalisationWorker>()
            .setInputData(Data.Builder().putString(PersonalisationWorker.KEY_FROM, from).build())
            .build()
        // APPEND: several sent in a row run one after another, each a slice from the last checkpoint.
        // ⚠️ Waits for the row to be written: a receiver that returns at once can be frozen before
        // WorkManager's thread runs, and then no job is ever scheduled (DebugTaskWorker found this).
        WorkManager.getInstance(context)
            .enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            .result
            .get()
    }

    private companion object {
        const val NAME = "inkstone.handwriting.personalise.now"
    }
}
