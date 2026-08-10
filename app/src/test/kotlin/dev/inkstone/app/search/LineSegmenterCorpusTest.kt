package dev.inkstone.app.search

import dev.inkstone.core.model.InkSample
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.search.LineSegmenter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `LineSegmenter` against the **real** M7 sample, on the JVM.
 *
 * This exists because the segmenter is pure. Measuring recognition needs an emulator, ML Kit
 * and eight minutes; measuring *segmentation* needs neither, and the segmentation is the part
 * with the tunable numbers in it. Being able to ask "how many lines did it find, and how many
 * are really there" in two seconds is what turned M7's first measurement — 632 lines found
 * against 472 — from a mystery into a fix.
 *
 * ⚠️ **Skips when the sample is absent**, which is the normal state of a fresh clone: the
 * IAMonDo corpus is non-commercial-research-only and is deliberately not in this repo. Run
 * `tools/m7-accuracy.ps1 -PrepareOnly` to produce it.
 */
class LineSegmenterCorpusTest {

    @Test
    fun `segmentation finds about as many lines as the corpus says are there`() {
        val sample = File(SAMPLE)
        if (!sample.exists()) {
            println("SKIPPED — no sample at $SAMPLE. Run tools/m7-accuracy.ps1 -PrepareOnly.")
            return
        }

        var found = 0
        var truth = 0
        val reasons = LinkedHashMap<String, Int>()
        val worst = ArrayList<Triple<String, Int, Int>>()
        val debug = StringBuilder("{\"pages\":[")
        for ((index, page) in pagesOf(sample).withIndex()) {
            val result = LineSegmenter.segment(page.strokes)
            found += result.lines.size
            truth += page.groundTruthLines
            for (region in result.skipped) reasons.merge(region.reason.name, 1, Int::plus)
            worst += Triple(page.id, result.lines.size, page.groundTruthLines)
            if (index > 0) debug.append(',')
            debug.append("{\"id\":\"${page.id}\",\"lines\":[")
            debug.append(result.lines.joinToString(",") { box(it.writingArea) })
            debug.append("],\"skipped\":[")
            debug.append(result.skipped.joinToString(",") { "{\"r\":\"${it.reason}\",\"b\":${box(it.bounds)}}" })
            debug.append("]}")
        }
        debug.append("]}")
        File(DEBUG_OUT).apply { parentFile?.mkdirs() }.writeText(debug.toString())

        println("=== LineSegmenter over the M7 sample ===")
        println("  lines found      : $found")
        println("  ground-truth     : $truth")
        println("  ratio            : ${"%.3f".format(found.toDouble() / truth)}")
        println("  skipped          : $reasons")
        println("  boxes written to : $DEBUG_OUT")
        for ((id, f, t) in worst.sortedByDescending { abs(it.second - it.third) }.take(8)) {
            println("  worst page $id: found $f, truth $t")
        }

        // A band, not a number. Ground truth counts IAMonDo `Textline` annotations, and a
        // human annotator and a clustering rule will never agree exactly — a line broken
        // across a page's fold, a heading the annotator merged. Outside this band, though,
        // something is structurally wrong: too many means lines are being split and each
        // fragment recognised without its neighbours, too few means two lines are being read
        // as one, and both are worth failing over.
        val ratio = found.toDouble() / truth
        assertTrue(ratio in 0.85..1.15, "segmentation ratio $ratio is outside 0.85..1.15")
    }

    // -----------------------------------------------------------------------

    private fun box(rect: PageRect) = "[${rect.left},${rect.top},${rect.right},${rect.bottom}]"

    private class CorpusPage(val id: String, val strokes: List<SampledStroke>, val groundTruthLines: Int)

    private fun pagesOf(file: File): List<CorpusPage> {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        return root.getValue("pages").jsonArray.map { element ->
            val page = element.jsonObject
            CorpusPage(
                id = page.getValue("id").jsonPrimitive.content,
                groundTruthLines = page.getValue("groundTruth").jsonArray.size,
                strokes = page.getValue("strokes").jsonArray.mapNotNull { strokeElement ->
                    val stroke = strokeElement.jsonObject
                    val samples = stroke.getValue("points").jsonArray.map { pointElement ->
                        val point = pointElement.jsonArray
                        InkSample(
                            point[0].jsonPrimitive.content.toFloat(),
                            point[1].jsonPrimitive.content.toFloat(),
                            point[2].jsonPrimitive.content.toLong(),
                        )
                    }
                    val bounds = PageRect.around(samples.map { PagePoint(it.x, it.y) }) ?: return@mapNotNull null
                    SampledStroke(ObjectId(stroke.getValue("id").jsonPrimitive.content), samples, bounds)
                },
            )
        }
    }

    private companion object {
        /**
         * ⚠️ An **environment** variable, not a `-D` system property. A Gradle `Test` task
         * forks a JVM and inherits the environment, but not the launcher's system properties —
         * so `-Dinkstone.m7.sample=…` on the command line reaches Gradle and never reaches the
         * test, which then silently takes the fallback path, finds nothing, and *skips*. A
         * green run that measured nothing is the exact failure this file exists to avoid.
         */
        val DEBUG_OUT: String = System.getenv("INKSTONE_M7_DEBUG")
            ?: (System.getProperty("java.io.tmpdir") + "/inkstone-m7/segmentation.json")

        val SAMPLE: String = System.getenv("INKSTONE_M7_SAMPLE")
            ?: (System.getProperty("java.io.tmpdir") + "/inkstone-m7/sample.json")
    }
}
