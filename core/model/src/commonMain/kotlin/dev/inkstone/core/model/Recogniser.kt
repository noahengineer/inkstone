package dev.inkstone.core.model

/*
 * SPEC §8.1 — the recogniser, as `:core:` and `:app` are allowed to see it.
 *
 * ML Kit is a single point of dependency with no viable alternative: ML Kit GenAI and
 * Gemini Nano do not do handwriting, and image OCR throws away the temporal signal that
 * makes stroke recognition work. The response to that is not to hide the risk behind an
 * abstraction that pretends a second implementation is coming — it is [RecognitionOutcome],
 * which makes *"there is no recogniser"* an ordinary value every caller must handle, so the
 * app cannot accidentally be written as though recognition always works.
 */

/**
 * What one page's recognition produced — or why it produced nothing.
 *
 * Sealed rather than a nullable list because the three cases lead somewhere different:
 * one updates the index, one leaves the previous text alone and asks the user whether to
 * download a model, and one is a page with nothing on it.
 */
public sealed interface RecognitionOutcome {

    /** The page was read. [lines] may still be empty if every region was skipped. */
    public data class Recognised(
        val lines: List<RecognisedLine>,
        val skipped: List<SkippedRegion>,
        val recogniser: String,
    ) : RecognitionOutcome

    /** Nothing on the page could be writing — no strokes, or all of them rejected. */
    public data object NothingToRead : RecognitionOutcome

    /**
     * ⭐ **The case the whole app has to survive** (ROADMAP M7).
     *
     * No model, no Play Services, a download that failed, a device that has never been
     * online. Search degrades to titles, the PDF gets no text layer, and **nothing else
     * changes** — in particular the previous text is not deleted, because a missing model
     * is not evidence that the words were wrong.
     */
    public data class Unavailable(val reason: RecognitionUnavailable) : RecognitionOutcome
}

/** Why recognition could not run, in the terms the UI needs to say something useful. */
public enum class RecognitionUnavailable {
    /** The language model is not on the device. Offer to download it — [RecognitionModels]. */
    MODEL_NOT_DOWNLOADED,

    /** Google Play Services is absent or too old. Nothing the app can do; say so once. */
    PLAY_SERVICES_MISSING,

    /** The model is present but the recogniser failed. Transient; the batch pass retries. */
    RECOGNISER_FAILED,
}

/**
 * SPEC §2.2 — recognise one page's worth of already-segmented writing.
 *
 * Takes [SampledStroke]s rather than a [FoldedPage] so that the implementation needs no
 * `androidx.ink`: `StrokeSampler` has already crossed that boundary. Line segmentation
 * happens inside, because `WritingArea` is an ML Kit type and sizing it to the line is the
 * step that must not be skippable by a caller (SPEC §8.1).
 *
 * ⚠️ **Never on the ink path and never on the UI thread** (SPEC §8.3, CLAUDE.md rule 7).
 * Implementations hop off the caller's thread themselves rather than trusting every caller
 * to remember, exactly as `InkstonePdfMaterializer` does.
 */
public fun interface HandwritingRecogniser {
    public suspend fun recognise(strokes: List<SampledStroke>): RecognitionOutcome
}

/** One downloadable language model, as the settings screen needs to show it. */
public data class RecognitionModel(
    val languageTag: String,
    val displayName: String,
    val downloaded: Boolean,
    val approximateBytes: Long,
)

/** Progress of a model download, for the UI ROADMAP M7 asks for. */
public sealed interface ModelDownload {
    public data object Queued : ModelDownload

    public data object Running : ModelDownload

    public data object Done : ModelDownload

    public data class Failed(val message: String) : ModelDownload
}

/**
 * SPEC §8.1's *"models are ~20 MB per language, downloaded on demand"*, as a port.
 *
 * ⚠️ **[download] must never be called without the user having asked.** ML Kit models come
 * over the network on a device the user may be paying for by the megabyte, and SPEC §8.1
 * also requires disclosing that ML Kit reports anonymous performance metrics to Google —
 * which is a thing to tell someone *before* they opt in, not after.
 */
public interface RecognitionModels {
    /** Languages this build offers, with their download state. */
    public suspend fun available(): List<RecognitionModel>

    public suspend fun isDownloaded(languageTag: String): Boolean

    /** @param unmeteredOnly the default, and the only value the UI should normally pass. */
    public suspend fun download(languageTag: String, unmeteredOnly: Boolean = true): ModelDownload

    /**
     * Remove a model. ROADMAP M7's acceptance criterion — *"delete the language model → app
     * works fully, search degrades to titles, message is clear"* — needs this to be reachable.
     */
    public suspend fun delete(languageTag: String): Boolean
}
