package dev.inkstone.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.inkstone.app.handwriting.HandwritingPersonaliser

/**
 * **Debug builds only.** docs/HANDWRITING-SYNTHESIS.md H5 — one personalisation pass now, ignoring the
 * charging-and-idle constraints the real worker has, so a device check does not wait for a night on the
 * charger.
 *
 * ```
 * adb shell am broadcast -n dev.inkstone/.app.debug.PersonaliseReceiver --ei seconds 300
 * adb logcat -s HandwritingPersonal DebugTask
 * ```
 *
 * The pass is the production one ([HandwritingPersonaliser]); only the trigger is different. A release
 * build has no receiver: there the periodic job is forced with `cmd jobscheduler run -f`.
 */
class PersonaliseReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val seconds = intent.getIntExtra(EXTRA_SECONDS, DEFAULT_SECONDS)
        DebugTaskWorker.enqueue(context, DebugTaskWorker.TASK_PERSONALISE, seconds.toString())
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Deps {
        fun personaliser(): HandwritingPersonaliser
    }

    internal companion object {
        private const val TAG = "HandwritingPersonal"
        private const val EXTRA_SECONDS = "seconds"
        private const val DEFAULT_SECONDS = 300
        private const val MILLIS = 1000L

        suspend fun run(context: Context, argument: String?) {
            val seconds = argument?.toIntOrNull() ?: DEFAULT_SECONDS
            val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
            val outcome = deps.personaliser().run(seconds * MILLIS)
            Log.i(TAG, "debug pass done: $outcome")
        }
    }
}
