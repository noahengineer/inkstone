package dev.inkstone.android.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import dev.inkstone.core.model.InkSample
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.RecognitionOutcome
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.search.TextDistance
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ⭐ **ROADMAP M7's headline measurement: line segmentation versus whole-page-as-one-blob,
 * on a real 20-page handwriting sample, showing both numbers.**
 *
 * ## What it runs against
 *
 * IAMonDo v1.0 (IAM Online Document Database) — 941 pages of genuine A4 handwriting captured
 * with an Anoto pen, each with per-text-line ground-truth transcriptions in the same file.
 * The sample is 20 pages from **20 different writers**, taken from the corpus's own
 * writer-disjoint set 0 in filename order.
 *
 * ⚠️ **The corpus is not in this repo and must not be.** IAMonDo's terms are non-commercial
 * research use and confer no redistribution right. `tools/m7-accuracy.ps1` fetches it, builds
 * the sample and pushes it to the device; only the aggregate numbers are committed, in
 * `docs/M7-RECOGNITION.md`. Without the sample present this test **skips** rather than fails,
 * so `connectedAndroidTest` stays green on a machine that has not fetched it.
 *
 * ## The three arms
 *
 * | Arm | `Ink` | `WritingArea` | What it models |
 * |---|---|---|---|
 * | `segmented` | one per line | the line | SPEC §8.2, what Inkstone ships |
 * | `page-blob` | one per page | the whole A4 page | the naive implementation SPEC §8.1 warns about |
 * | `inkbox-blob` | one per page | the ink's bounding box | the *charitable* baseline |
 *
 * The third arm exists so the comparison cannot be dismissed as a strawman. A page-sized
 * writing area around a half-empty page is a bad deal that a careful implementer might avoid
 * without ever thinking about lines; `inkbox-blob` is the best that implementer could do, and
 * it is still one blob.
 */
@RunWith(AndroidJUnit4::class)
class SegmentationAccuracyTest {

    @Test
    fun `line segmentation beats whole page as one blob`() = runBlocking {
        val sample = File(SAMPLE_PATH)
        if (!sample.exists()) {
            println("$TAG SKIPPED — no sample at $SAMPLE_PATH. Run tools/m7-accuracy.ps1.")
            return@runBlocking
        }

        val models = MlKitModels()
        if (!models.isDownloaded(LANGUAGE)) {
            println("$TAG downloading the $LANGUAGE model…")
            models.download(LANGUAGE, unmeteredOnly = false)
        }
        if (!models.isDownloaded(LANGUAGE)) fail("$TAG could not obtain the $LANGUAGE model")

        val pages = pagesOf(JSONObject(sample.readText()))
        assertTrue("the sample has no pages", pages.isNotEmpty())

        val recogniser = MlKitRecogniser(languageTag = LANGUAGE)
        val client = DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(checkNotNull(modelFor(LANGUAGE))).build(),
        )
        val scores = LinkedHashMap<String, MutableList<Pair<Double, Double>>>()

        var lines = 0
        var truthLines = 0
        try {
            for (page in pages) {
                val reference = page.groundTruth.joinToString(" ")
                record(scores, "segmented", reference, segmented(recogniser, page))
                record(scores, "seg-drawn-order", reference, byLine(client, page, asDrawn = true))
                record(scores, "seg-x-order", reference, byLine(client, page, asDrawn = false))
                record(scores, "page-blob", reference, blob(client, page.strokes, page.pageBox))
                record(scores, "inkbox-blob", reference, blob(client, page.strokes, page.inkBox))
                lines += dev.inkstone.core.search.LineSegmenter.segment(page.strokes).lines.size
                truthLines += page.groundTruth.size
                println("$TAG page ${page.id} (writer ${page.writer}) done")
            }
        } finally {
            client.close()
        }
        println("$TAG segmenter found $lines lines against $truthLines ground-truth text lines")

        report(pages.size, scores)

        val segmentedCer = scores.getValue("segmented").map { it.first }.average()
        val blobCer = scores.getValue("page-blob").map { it.first }.average()
        assertTrue(
            "segmentation must beat the blob: CER $segmentedCer vs $blobCer",
            segmentedCer < blobCer,
        )
    }

    // -----------------------------------------------------------------------
    // The arms
    // -----------------------------------------------------------------------

    /** What Inkstone ships: [MlKitRecogniser], which segments and sizes each area to its line. */
    private suspend fun segmented(recogniser: MlKitRecogniser, page: Page): String =
        when (val outcome = recogniser.recognise(page.strokes)) {
            is RecognitionOutcome.Recognised -> outcome.lines.joinToString(" ") { it.text }
            else -> ""
        }

    /**
     * Segmented, but with the order of strokes *within* each line under test.
     *
     * SPEC §8.2 step 3 says *"strokes left-to-right within"* a line. That is right for laying
     * words out and it is a claim about the recogniser's input that the spec could not have
     * checked. The model is trained on natural writing, where a `t` is crossed and an `i`
     * dotted **after** the word is finished — so sorting by x is precisely the *"out-of-order
     * strokes degrade results"* case SPEC §8.1 warns about, applied by us on purpose. This
     * arm measures the difference instead of arguing about it.
     */
    private suspend fun byLine(client: DigitalInkRecognizer, page: Page, asDrawn: Boolean): String {
        val segmentation = dev.inkstone.core.search.LineSegmenter.segment(page.strokes)
        val drawn = page.strokes.withIndex().associate { (index, stroke) -> stroke.id to index }
        val parts = ArrayList<String>()
        var preContext: String? = null
        for (line in segmentation.lines) {
            val ordered = if (asDrawn) {
                line.strokes.sortedBy { drawn[it.id] ?: 0 }
            } else {
                line.strokes.sortedBy { it.bounds.left }
            }
            val ink = inkOf(ordered, line.writingArea)
            if (ink.strokes.isEmpty()) continue
            val text = client.recognize(ink, contextFor(line.writingArea, preContext)).await()
                .candidates.firstOrNull()?.text.orEmpty()
            if (text.isNotEmpty()) {
                parts += text
                preContext = text
            }
        }
        return parts.joinToString(" ")
    }

    /** One `Ink` for the whole page, one `WritingArea`, one `recognize` call. */
    private suspend fun blob(client: DigitalInkRecognizer, strokes: List<SampledStroke>, area: PageRect): String {
        val ink = inkOf(strokes, area)
        if (ink.strokes.isEmpty()) return ""
        return client.recognize(ink, contextFor(area, preContext = null)).await()
            .candidates.firstOrNull()?.text.orEmpty()
    }

    // -----------------------------------------------------------------------

    private fun record(
        into: MutableMap<String, MutableList<Pair<Double, Double>>>,
        arm: String,
        reference: String,
        hypothesis: String,
    ) {
        into.getOrPut(arm) { ArrayList() } += TextDistance.characterErrorRate(reference, hypothesis) to
            TextDistance.wordRecall(reference, hypothesis)
    }

    private fun report(pages: Int, scores: Map<String, List<Pair<Double, Double>>>) {
        val lines = buildList {
            add("$TAG ==== M7 segmentation accuracy — $pages pages, $LANGUAGE ====")
            add("$TAG arm            CER      word recall")
            for ((arm, values) in scores) {
                val cer = values.map { it.first }.average()
                val recall = values.map { it.second }.average()
                add("$TAG %-14s %6.3f   %6.3f".format(arm, cer, recall))
            }
        }
        lines.forEach(::println)
        // Also to a file, because logcat truncates and this is the milestone's evidence.
        runCatching { File(RESULT_PATH).writeText(lines.joinToString("\n") + "\n") }
    }

    private class Page(
        val id: String,
        val writer: String,
        val groundTruth: List<String>,
        val strokes: List<SampledStroke>,
        val pageBox: PageRect,
    ) {
        val inkBox: PageRect = strokes.map { it.bounds }.reduce(PageRect::union)
    }

    private fun pagesOf(root: JSONObject): List<Page> {
        val array = root.getJSONArray("pages")
        return (0 until array.length()).map { index ->
            val page = array.getJSONObject(index)
            val strokeArray = page.getJSONArray("strokes")
            val strokes = (0 until strokeArray.length()).mapNotNull { strokeAt(strokeArray.getJSONObject(it)) }
            val truth = page.getJSONArray("groundTruth")
            Page(
                id = page.getString("id"),
                writer = page.optString("writer"),
                groundTruth = (0 until truth.length()).map { truth.getString(it) },
                strokes = strokes,
                pageBox = PageRect(
                    0f,
                    0f,
                    page.getDouble("widthPt").toFloat(),
                    page.getDouble("heightPt").toFloat(),
                ),
            )
        }
    }

    private fun strokeAt(stroke: JSONObject): SampledStroke? {
        val points = stroke.getJSONArray("points")
        val samples = (0 until points.length()).map { index ->
            val point = points.getJSONArray(index)
            InkSample(point.getDouble(0).toFloat(), point.getDouble(1).toFloat(), point.getLong(2))
        }
        val bounds = PageRect.around(samples.map { dev.inkstone.core.model.PagePoint(it.x, it.y) }) ?: return null
        return SampledStroke(ObjectId(stroke.getString("id")), samples, bounds)
    }

    private companion object {
        const val TAG = "[M7-ACCURACY]"
        const val LANGUAGE = "en-US"
        const val SAMPLE_PATH = "/data/local/tmp/inkstone-m7/sample.json"
        const val RESULT_PATH = "/data/local/tmp/inkstone-m7/result.txt"
    }
}
