package dev.inkstone.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.inkstone.android.ocr.MlKitRecogniser
import dev.inkstone.core.model.InkSample
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.RecognitionOutcome
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.model.Segmentation
import dev.inkstone.core.model.TextLine
import dev.inkstone.core.search.LineSegmenter
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * **Debug builds only.** Reads lines with ML Kit and writes down what it made of each one, so that
 * two ways of drawing a page's lines can be compared by the one measure that matters to search:
 * the text that comes back.
 *
 * ```
 * adb push lines-v3.json /data/local/tmp/
 * adb shell run-as dev.inkstone cp /data/local/tmp/lines-v3.json files/debug/
 * adb shell am broadcast -n dev.inkstone/.app.debug.LineReadReceiver --es plan lines-v3
 * adb exec-out run-as dev.inkstone cat files/debug/lines-v3.out.json > lines-v3.out.json
 * ```
 *
 * The ink is `files/debug/ink-export.json`, written by [InkExportReceiver]. The plan names, per page,
 * which strokes make each line: `{"pages":[{"id","lines":[{"strokes":[ids],"rotate":radians}]}]}`.
 * `rotate` turns a line about its centre before it is read, to try a slanted line levelled. The
 * plan name `segmenter` needs no file: every page is read with this build's own [LineSegmenter].
 *
 * ⚠️ **Every line is read on its own, with no context.** Production hands each line the text of the
 * line above it (`LineSegmenter.contextLineFor`). Reading without it treats every plan alike, and a
 * line ML Kit never answers is named in the log rather than stopping the run.
 *
 * The output keeps the plan's order: `{"pages":[{"id","lines":[{"n","box","text"}]}]}`, with an empty
 * text where ML Kit returned nothing. `files/debug/<plan>.out.done` appears when it has been written.
 * The work runs as a [DebugTaskWorker] job, not in this receiver (see there).
 *
 * Nothing here touches a note. It reads two files of its own and writes a third.
 */
class LineReadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        DebugTaskWorker.enqueue(context, DebugTaskWorker.TASK_READ, intent.getStringExtra(EXTRA_PLAN))
    }

    internal companion object {
        private const val TAG = "LineRead"
        private const val DIR = "debug"
        private const val CORPUS = "ink-export.json"
        private const val EXTRA_PLAN = "plan"
        private const val SEGMENTER = "segmenter"
        private const val LINE_TIMEOUT_MS = 20_000L
        private const val TIMED_OUT = "\u0000timeout"

        suspend fun read(context: Context, planName: String?) {
            val plan = planName ?: SEGMENTER
            val dir = File(context.applicationContext.filesDir, DIR)
            File(dir, "$plan.out.done").delete()
            val pages = strokesByPage(File(dir, CORPUS))
            val planned = if (plan == SEGMENTER) {
                null
            } else {
                JSONObject(File(dir, "$plan.json").readText()).getJSONArray("pages")
            }
            val recogniser = MlKitRecogniser()
            val out = JSONArray()
            var lineCount = 0
            val ids = planned?.let { list -> (0 until list.length()).map { list.getJSONObject(it).getString("id") } }
                ?: pages.keys.toList()
            for ((index, pageId) in ids.withIndex()) {
                val strokes = pages[pageId] ?: continue
                val lines = if (planned == null) {
                    LineSegmenter.segment(strokes.values.toList()).lines
                } else {
                    linesOf(planned.getJSONObject(index).getJSONArray("lines"), strokes)
                }
                lineCount += lines.size
                val row = JSONArray()
                for ((number, line) in lines.withIndex()) {
                    val text = readOne(recogniser, line) ?: run {
                        Log.w(TAG, "$plan: line $number of $pageId timed out: ${describe(line)}")
                        TIMED_OUT
                    }
                    val box = line.writingArea
                    row.put(
                        JSONObject()
                            .put("n", line.strokes.size)
                            .put("box", JSONArray(listOf(box.left, box.top, box.right, box.bottom)))
                            .put("text", text),
                    )
                }
                out.put(JSONObject().put("id", pageId).put("lines", row))
            }
            val file = File(dir, "$plan.out.json")
            val temp = File(dir, "$plan.out.json.tmp")
            temp.writeText(JSONObject().put("pages", out).toString())
            check(temp.renameTo(file)) { "could not move ${temp.name} into place" }
            File(dir, "$plan.out.done").writeText("${ids.size} pages, $lineCount lines\n")
            Log.i(TAG, "read $lineCount lines on ${ids.size} pages for $plan")
        }

        /** One line's text, empty when ML Kit read nothing, null when it did not answer at all. */
        private suspend fun readOne(recogniser: MlKitRecogniser, line: TextLine): String? {
            val outcome = withTimeoutOrNull(LINE_TIMEOUT_MS) {
                recogniser.recogniseSegmented(Segmentation(listOf(line), emptyList()))
            } ?: return null
            return when (outcome) {
                is RecognitionOutcome.Recognised -> outcome.lines.singleOrNull()?.text.orEmpty()
                RecognitionOutcome.NothingToRead -> ""
                is RecognitionOutcome.Unavailable -> error("recogniser unavailable: ${outcome.reason}")
            }
        }

        /** Every page of the ink export, its strokes by id in draw order. */
        private fun strokesByPage(file: File): Map<String, LinkedHashMap<String, SampledStroke>> {
            val pages = JSONObject(file.readText()).getJSONArray("pages")
            val byPage = LinkedHashMap<String, LinkedHashMap<String, SampledStroke>>()
            for (p in 0 until pages.length()) {
                val page = pages.getJSONObject(p)
                val strokes = LinkedHashMap<String, SampledStroke>()
                val list = page.getJSONArray("strokes")
                for (s in 0 until list.length()) {
                    val stroke = list.getJSONObject(s)
                    val points = stroke.getJSONArray("points")
                    val samples = (0 until points.length()).map { i ->
                        val point = points.getJSONArray(i)
                        InkSample(point.getDouble(0).toFloat(), point.getDouble(1).toFloat(), point.getLong(2))
                    }
                    val id = stroke.getString("id")
                    strokeOf(id, samples)?.let { strokes[id] = it }
                }
                byPage[page.getString("id")] = strokes
            }
            return byPage
        }

        private fun linesOf(planned: JSONArray, strokes: Map<String, SampledStroke>): List<TextLine> =
            (0 until planned.length()).mapNotNull { i ->
                val line = planned.getJSONObject(i)
                val ids = line.getJSONArray("strokes")
                val members = (0 until ids.length()).mapNotNull { strokes[ids.getString(it)] }
                if (members.isEmpty()) return@mapNotNull null
                val angle = line.optDouble("rotate", 0.0).toFloat()
                val turned = if (angle == 0f) members else rotated(members, angle)
                TextLine(turned, turned.map { it.bounds }.reduce(PageRect::union))
            }

        /** [strokes] turned by [angle] radians about the centre of their box, as one piece. */
        private fun rotated(strokes: List<SampledStroke>, angle: Float): List<SampledStroke> {
            val box = strokes.map { it.bounds }.reduce(PageRect::union)
            val cx = box.centreX
            val cy = box.centreY
            val c = cos(angle)
            val s = sin(angle)
            return strokes.mapNotNull { stroke ->
                val samples = stroke.samples.map { p ->
                    val dx = p.x - cx
                    val dy = p.y - cy
                    InkSample(cx + dx * c - dy * s, cy + dx * s + dy * c, p.tMillis)
                }
                strokeOf(stroke.id.value, samples)
            }
        }

        private fun describe(line: TextLine): String {
            val box = line.writingArea
            val points = line.strokes.sumOf { it.samples.size }
            return "${line.strokes.size} strokes, $points points, box ${box.left},${box.top} ${box.width}x${box.height}"
        }

        private fun strokeOf(id: String, samples: List<InkSample>): SampledStroke? {
            val bounds = PageRect.around(samples.map { PagePoint(it.x, it.y) }) ?: return null
            return SampledStroke(ObjectId(id), samples, bounds)
        }
    }
}
