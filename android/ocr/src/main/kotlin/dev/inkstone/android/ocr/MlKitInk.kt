package dev.inkstone.android.ocr

import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.SampledStroke
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * ⚠️ The package is `com.google.mlkit.vision.digitalink.recognition`, NOT
 * `com.google.mlkit.vision.digitalink`.
 *
 * Google's own guide, and every tutorial and answer written from it, imports
 * `com.google.mlkit.vision.digitalink.Ink`. That package does not exist in
 * digital-ink-recognition 19.0.0 — the recognition types moved down a level and the shared
 * ones (`RecognitionResult`, `RecognitionCandidate`) live in `...digitalink.common`, which
 * ships in a separate artifact. Every signature used in this module was read off the AAR
 * with `javap` rather than from documentation (CLAUDE.md rule 3's habit, applied to an API
 * surface rather than to a version number).
 */

/**
 * Build one ML Kit [Ink] from a group of strokes, **translated so the group's own top-left
 * corner is the origin**.
 *
 * ⭐ This is half of the accuracy lever and it is easy to miss, because passing page
 * coordinates compiles and returns plausible-looking text. [WritingArea] is *"width and
 * height"* and nothing else — it has no origin — so ML Kit reads the ink coordinates as
 * being inside a box of that size anchored at zero. Give it page coordinates and a
 * line-sized writing area and every point is outside the area it was told about; give it a
 * page-sized area and it is told the writing is a page tall. The strokes have to move.
 *
 * @param area the box the coordinates are made relative to — the line for SPEC §8.2's
 *   segmented path, the whole page for the unsegmented baseline.
 */
internal fun inkOf(strokes: List<SampledStroke>, area: PageRect): Ink {
    val ink = Ink.builder()
    for (stroke in strokes) {
        if (stroke.samples.isEmpty()) continue
        val builder = Ink.Stroke.builder()
        for (sample in stroke.samples) {
            builder.addPoint(Ink.Point.create(sample.x - area.left, sample.y - area.top, sample.tMillis))
        }
        ink.addStroke(builder.build())
    }
    return ink.build()
}

/**
 * The recognition context for one line.
 *
 * @param preContext the previous line's text, truncated to SPEC §8.1's 20 characters. It is
 *   the language model's only clue about what came before, and feeding it the *end* of the
 *   previous line rather than the start is the point — `take(20)` would hand it the beginning
 *   of a sentence that finished two lines ago.
 */
internal fun contextFor(area: PageRect, preContext: String?): RecognitionContext = RecognitionContext.builder()
    .setWritingArea(WritingArea(area.width, area.height))
    .setPreContext(preContext?.takeLast(MAX_PRE_CONTEXT).orEmpty())
    .build()

/** SPEC §8.1 — `preContext` is capped at 20 characters. */
internal const val MAX_PRE_CONTEXT: Int = 20

/**
 * The model for a BCP-47 tag, or null when ML Kit has none — **which is a `null`, not an
 * exception**, despite `fromLanguageTag` being declared `throws MlKitException`.
 *
 * Both failure modes are real and they are folded together deliberately: the caller's next
 * question is *"can this device read this language"*, and *"no such model"* and *"Play
 * Services could not be asked"* answer it the same way. Which of the two it was survives in
 * `RecognitionUnavailable`, one level up, where it changes what the UI says.
 *
 * `runCatching` rather than `!!` on the identifier: CLAUDE.md forbids `!!` outside tests, and
 * this is exactly the Java-interop nullable return that tempts it.
 */
internal fun modelFor(languageTag: String): DigitalInkRecognitionModel? = runCatching {
    DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
        ?.let { DigitalInkRecognitionModel.builder(it).build() }
}.getOrNull()

/**
 * Await a Play Services [Task] without pulling in `kotlinx-coroutines-play-services`.
 *
 * Ten lines against a new pinned coordinate: CLAUDE.md rule 3 makes adding one a small
 * ceremony (verify it resolves, record the date), and this is the only thing the dependency
 * would have been for.
 */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
