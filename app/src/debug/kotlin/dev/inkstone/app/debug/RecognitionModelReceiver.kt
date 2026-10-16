package dev.inkstone.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.inkstone.android.ocr.MlKitRecogniser
import dev.inkstone.app.search.RecognitionModelController
import dev.inkstone.core.model.RecognitionModels
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * **Debug builds only.** Puts a device back into the state a fresh install is in — no recognition
 * model — so the download's panel can be walked through the app the way a user reaches it:
 *
 * ```
 * adb shell am broadcast -n dev.inkstone/.app.debug.RecognitionModelReceiver --es action delete
 * adb shell am broadcast -n dev.inkstone/.app.debug.RecognitionModelReceiver --es action status
 * ```
 *
 * `delete` is ML Kit's own `deleteDownloadedModel`, which is the one ROADMAP M7's *"delete the language
 * model → app works fully, search degrades to titles"* names; nothing else is touched — the words
 * already in the search index stay. It then has the controller ask again, because it asks only at
 * start-up and after a download. Never downloads: that is a press in the app (SPEC §8.1). Results are
 * logged under `ModelDebug`.
 */
class RecognitionModelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        DebugTaskWorker.enqueue(context, DebugTaskWorker.TASK_MODEL, intent.getStringExtra(EXTRA_ACTION))
    }

    internal companion object {
        private const val TAG = "ModelDebug"
        private const val EXTRA_ACTION = "action"

        suspend fun run(context: Context, action: String?) {
            val entry =
                EntryPointAccessors.fromApplication(context.applicationContext, ModelDebugEntryPoint::class.java)
            val language = MlKitRecogniser.DEFAULT_LANGUAGE
            when (action) {
                "delete" -> Log.i(TAG, "delete $language: ${entry.models().delete(language)}")
                "status" -> Unit
                else -> Log.w(TAG, "unknown action $action; use delete or status")
            }
            val here = entry.models().isDownloaded(language)
            val controller = entry.controller()
            controller.refresh()
            // The refresh is the controller's own coroutine; wait for it, so the panel logged is the
            // one the app is now drawing rather than the one before.
            withTimeoutOrNull(REFRESH_TIMEOUT_MS) { controller.downloaded.first { it == here } }
            Log.i(TAG, "downloaded=$here panel=${controller.panel.value}")
        }

        private const val REFRESH_TIMEOUT_MS = 5_000L
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ModelDebugEntryPoint {
    fun models(): RecognitionModels

    fun controller(): RecognitionModelController
}
