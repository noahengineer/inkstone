package dev.inkstone.android.ocr

import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import dev.inkstone.core.model.HandwritingRecogniser
import dev.inkstone.core.model.RecognisedLine
import dev.inkstone.core.model.RecognitionOutcome
import dev.inkstone.core.model.RecognitionUnavailable
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.model.Segmentation
import dev.inkstone.core.model.TextLine
import dev.inkstone.core.search.LineSegmenter
import dev.inkstone.core.search.WordBoxes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap

/**
 * SPEC §8 — ML Kit Digital Ink Recognition, one line at a time.
 *
 * ## ⭐ Why every line is its own `recognize` call
 *
 * *"The recogniser assumes a single line of text. Passing a whole page produces garbage"*
 * (SPEC §8.1). It is the most commonly missed detail in every implementation of this, and it
 * does not fail loudly — a page handed over as one `Ink` comes back with fluent, confident,
 * wrong text. [LineSegmenter] does the grouping and this class does two things with the
 * result that a page-at-a-time implementation cannot: it sizes the `WritingArea` to the line,
 * and it feeds the previous line's tail in as `preContext`.
 *
 * `docs/M7-RECOGNITION.md` has both numbers on a 20-page, 20-writer sample.
 *
 * ## Degrading
 *
 * Every failure path returns a [RecognitionOutcome] rather than throwing. A missing model is
 * the *expected* state on a device that has never been online, not an error — ROADMAP M7 asks
 * that the app then work fully with search degraded to titles, and the way to make that true
 * rather than intended is to give the caller a value it has to handle.
 *
 * ⚠️ **This never deletes previously recognised text.** A model that has gone missing is not
 * evidence that the words it produced were wrong, and a note that silently loses its search
 * text because a phone was reset is CLAUDE.md rule 1 with extra steps.
 */
public class MlKitRecogniser(
    private val languageTag: String = DEFAULT_LANGUAGE,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val models: MlKitModels = MlKitModels(),
) : HandwritingRecogniser {

    /**
     * Named in every `text/<pageId>.json` this produces, and compared by `NoteRecogniser`, so text
     * read by a different model, language or line segmentation is read again. Until
     * `LineSegmenter.VERSION` became part of it, nothing compared it, and its KDoc's promise that a
     * model change would invalidate old text was not kept.
     */
    override val id: String get() = "$RECOGNISER_ID/$languageTag/lines-${LineSegmenter.VERSION}"

    override suspend fun recognise(strokes: List<SampledStroke>): RecognitionOutcome =
        // SPEC §8.3: never on the ink path and never on the UI thread. Hopped here rather
        // than at each call site, for the same reason InkstonePdfMaterializer does it.
        withContext(dispatcher) {
            val segmentation = LineSegmenter.segment(strokes)
            if (segmentation.isEmpty) {
                return@withContext RecognitionOutcome.Recognised(emptyList(), segmentation.skipped, id)
                    .takeIf { segmentation.skipped.isNotEmpty() } ?: RecognitionOutcome.NothingToRead
            }
            recogniseLines(segmentation)
        }

    /**
     * Reads lines somebody else drew, skipping [LineSegmenter]. The debug build's `LineReadReceiver`
     * uses it to compare line segmentations by what ML Kit makes of each one. Production does not
     * call it: [recognise] is the only path that guarantees the lines are this build's.
     */
    public suspend fun recogniseSegmented(segmentation: Segmentation): RecognitionOutcome = withContext(dispatcher) {
        if (segmentation.isEmpty) RecognitionOutcome.NothingToRead else recogniseLines(segmentation)
    }

    private suspend fun recogniseLines(segmentation: Segmentation): RecognitionOutcome {
        // Null here is "this device cannot supply the model at all", which leads somewhere
        // different in the UI from "the model is not downloaded yet" — so the two are asked
        // separately rather than collapsed into one offer-to-download.
        val model = modelFor(languageTag)
            ?: return RecognitionOutcome.Unavailable(RecognitionUnavailable.PLAY_SERVICES_MISSING)
        if (!models.isDownloaded(languageTag)) {
            return RecognitionOutcome.Unavailable(RecognitionUnavailable.MODEL_NOT_DOWNLOADED)
        }

        val recogniser = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        return try {
            RecognitionOutcome.Recognised(readAll(recogniser, segmentation.lines), segmentation.skipped, id)
        } catch (failure: MlKitException) {
            // Not fatal and not silent: the batch pass comes round again. Reporting it as "no
            // text" instead would make a broken recogniser look like an empty page for ever.
            //
            // ⚠️ The code is read rather than discarded. ML Kit raises UNAVAILABLE for a model
            // that has gone missing *between* the check above and this call — a real race,
            // because the user can delete it from Settings while a batch pass is running — and
            // that is the one failure with a fix the user can act on.
            RecognitionOutcome.Unavailable(
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

    private suspend fun readAll(recogniser: DigitalInkRecognizer, lines: List<TextLine>): List<RecognisedLine> {
        val read = ArrayList<RecognisedLine>(lines.size)
        // The text each line was read as, so a later line can take it as its context. By identity:
        // a TextLine is a data class over every sample on the line, too much to hash per lookup.
        val readAs = IdentityHashMap<TextLine, String>(lines.size)
        for (line in lines) {
            // ⚠️ The line *above in the same column*, not the previous line in page order. On a page
            // with columns that is usually the line beside this one, across the gutter.
            val preContext = LineSegmenter.contextLineFor(line, lines)?.let { readAs[it] }
            val text = recognise(recogniser, line, preContext)
            if (text.isNotEmpty()) {
                read += WordBoxes.assign(line, text)
                // The line as read, not as it should have been: preContext is a hint about
                // what the model has just been shown, and lying to it about that is worse
                // than a short context.
                readAs[line] = text
            }
        }
        return read
    }

    private suspend fun recognise(recogniser: DigitalInkRecognizer, line: TextLine, preContext: String?): String {
        val ink = inkOf(line.strokes, line.writingArea)
        if (ink.strokes.isEmpty()) return ""
        val result = recogniser.recognize(ink, contextFor(line.writingArea, preContext)).await()
        return result.candidates.firstOrNull()?.text.orEmpty()
    }

    public companion object {
        /** SPEC §8.1's 300+ languages, one of which has to be the default. */
        public const val DEFAULT_LANGUAGE: String = "en-US"

        /**
         * Recorded in every `text/<pageId>.json`, so a note that syncs between a device with
         * an English model and one with a German model does not keep the wrong text — see
         * `PageText.recogniser`.
         */
        public const val RECOGNISER_ID: String = "mlkit-digital-ink/19.0.0"
    }
}
