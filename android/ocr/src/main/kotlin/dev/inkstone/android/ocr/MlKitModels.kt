package dev.inkstone.android.ocr

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import dev.inkstone.core.model.ModelDownload
import dev.inkstone.core.model.RecognitionModel
import dev.inkstone.core.model.RecognitionModels
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SPEC §8.1's *"~20 MB per language, downloaded on demand"*.
 *
 * ⚠️ **Nothing here is called speculatively.** A model download is tens of megabytes on a
 * connection the user may be paying for, so [download] happens only when someone presses a
 * button — and SPEC §8.1 also requires disclosing that ML Kit reports anonymous performance
 * metrics to Google, which belongs on that screen next to the size.
 *
 * ⚠️ **`isModelDownloaded` is a `Task` that can fail, and a failure is not `false`.** It
 * throws when Play Services is missing rather than answering, and treating that as *"not
 * downloaded"* would put a device that can never download anything into a permanent
 * offer-to-download loop. Both are folded into `false` here for the recogniser's purposes,
 * but the recogniser asks `modelOrNull()` first so the two still reach the UI apart.
 */
public class MlKitModels(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val manager: RemoteModelManager = RemoteModelManager.getInstance(),
) : RecognitionModels {

    override suspend fun available(): List<RecognitionModel> = withContext(dispatcher) {
        OFFERED.map { tag ->
            RecognitionModel(
                languageTag = tag,
                displayName = tag,
                downloaded = isDownloaded(tag),
                approximateBytes = APPROXIMATE_MODEL_BYTES,
            )
        }
    }

    override suspend fun isDownloaded(languageTag: String): Boolean = withContext(dispatcher) {
        val model = modelFor(languageTag) ?: return@withContext false
        runCatching { manager.isModelDownloaded(model).await() }.getOrDefault(false)
    }

    override suspend fun download(languageTag: String, unmeteredOnly: Boolean): ModelDownload =
        withContext(dispatcher) {
            val model = modelFor(languageTag)
                ?: return@withContext ModelDownload.Failed("no recognition model for '$languageTag'")
            val conditions = DownloadConditions.Builder().apply { if (unmeteredOnly) requireWifi() }.build()
            runCatching { manager.download(model, conditions).await() }
                .fold({ ModelDownload.Done }, { ModelDownload.Failed(it.message ?: it::class.simpleName.orEmpty()) })
        }

    override suspend fun delete(languageTag: String): Boolean = withContext(dispatcher) {
        val model = modelFor(languageTag) ?: return@withContext false
        runCatching { manager.deleteDownloadedModel(model).await() }.isSuccess
    }

    private companion object {
        /**
         * What the settings screen offers. ML Kit supports 300+ tags; a picker with 300 rows
         * is not a feature, and the list is a constant rather than `allModelIdentifiers()`
         * because the display names would then all be BCP-47 tags anyway.
         */
        val OFFERED = listOf("en-US", "en-GB", "de", "fr", "es", "it", "nl", "pt", "pl", "sv")

        /** SPEC §8.1's figure. Shown before a download, so it is deliberately approximate. */
        const val APPROXIMATE_MODEL_BYTES = 20L * 1024 * 1024
    }
}
