package dev.inkstone.android.ocr

import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import dev.inkstone.core.model.InkReader
import dev.inkstone.core.model.InkReading
import dev.inkstone.core.model.InkToRead
import dev.inkstone.core.model.ReadCandidate
import dev.inkstone.core.model.RecognitionUnavailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ML Kit Digital Ink Recognition, one piece of ink at a time, **with alternatives** — the
 * [InkReader] handwriting synthesis needs (docs/HANDWRITING-SYNTHESIS.md §5.3, §8.2).
 *
 * [MlKitRecogniser] reads a page, keeps the top guess per line, and is right for search. This asks a
 * narrower question about each piece it is handed — a word, a letter, a generated phrase — and
 * returns up to `candidates` readings each, because *"does this read as X"* is answered by whether
 * X is among them, not by whether it was first.
 *
 * Built from the same three helpers as the recogniser ([inkOf], [contextFor], [modelFor]), so the
 * two cannot drift apart on the details M7 measured: ink translated to its area's corner, the area
 * sized to the line, the text before it as `preContext`.
 *
 * **One client per call, not per piece**: a harvest pass reads every word on a page in one batch.
 * Every failure is an [InkReading.Unavailable], never a throw, on the recogniser's own terms.
 */
public class MlKitInkReader(
    private val languageTag: String = MlKitRecogniser.DEFAULT_LANGUAGE,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val models: MlKitModels = MlKitModels(),
) : InkReader {

    override suspend fun read(inks: List<InkToRead>, candidates: Int): InkReading = withContext(dispatcher) {
        val model = modelFor(languageTag)
            ?: return@withContext InkReading.Unavailable(RecognitionUnavailable.PLAY_SERVICES_MISSING)
        if (!models.isDownloaded(languageTag)) {
            return@withContext InkReading.Unavailable(RecognitionUnavailable.MODEL_NOT_DOWNLOADED)
        }
        val options = DigitalInkRecognizerOptions.builder(model).setMaxResultCount(candidates.coerceAtLeast(1)).build()
        val recogniser = DigitalInkRecognition.getClient(options)
        try {
            InkReading.Read(inks.map { readOne(recogniser, it) })
        } catch (failure: MlKitException) {
            // The same distinction MlKitRecogniser draws: a model deleted mid-pass is the one failure
            // the user can act on.
            InkReading.Unavailable(
                if (failure.errorCode == MlKitException.UNAVAILABLE) {
                    RecognitionUnavailable.MODEL_NOT_DOWNLOADED
                } else {
                    RecognitionUnavailable.RECOGNISER_FAILED
                },
            )
        } finally {
            recogniser.close()
        }
    }

    private suspend fun readOne(recogniser: DigitalInkRecognizer, piece: InkToRead): List<ReadCandidate> {
        val ink = inkOf(piece.strokes, piece.area)
        if (ink.strokes.isEmpty()) return emptyList()
        val result = recogniser.recognize(ink, contextFor(piece.area, piece.preContext)).await()
        return result.candidates.map { ReadCandidate(it.text, it.score) }
    }
}
