package dev.inkstone.app.search

import dev.inkstone.core.model.InkSample
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.model.Segmentation
import dev.inkstone.core.search.LineSegmenter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test

/**
 * `LineSegmenter` over **the user's own notes**, exported from a device with the debug-only
 * `InkExportReceiver`. This is a report, not an assertion, because real notes have no ground truth.
 * It prints a line per page and draws every page, with the segmenter's lines (numbered in reading
 * order) and skipped regions (red) over the ink, so the result can be *looked at*. M7's lesson: when a
 * measurement surprises you, draw the input.
 *
 * ```
 * INKSTONE_INK_CORPUS=<ink-export.json> INKSTONE_INK_OUT=<dir> \
 *   ./gradlew :app:testDebugUnitTest --tests '*RealInkSegmentationReport' --rerun
 * ```
 *
 * ⚠️ **It skips without a corpus**, which is the normal state of a clone. The corpus is the user's
 * handwriting and is never committed.
 *
 * The pictures are drawn by [Picture], a few lines of rasteriser, because `java.awt` is not on an
 * Android unit test's compile classpath.
 */
class RealInkSegmentationReport {

    @Test
    fun `report how the segmenter reads real notes`() {
        val corpus = System.getenv("INKSTONE_INK_CORPUS")?.let(::File)
        if (corpus == null || !corpus.exists()) {
            println("SKIPPED — set INKSTONE_INK_CORPUS to an ink export (see InkExportReceiver).")
            return
        }
        val out = File(System.getenv("INKSTONE_INK_OUT") ?: (System.getProperty("java.io.tmpdir") + "/inkstone-ink"))
        out.mkdirs()

        println("=== LineSegmenter over ${corpus.name} ===")
        println(row("#", "note", "strokes", "lines", "wide", "skipped strokes"))
        var allLines = 0
        var allStrokes = 0
        for ((index, page) in pagesOf(corpus).withIndex()) {
            val result = LineSegmenter.segment(page.strokes)
            allLines += result.lines.size
            allStrokes += page.strokes.size
            val skipped = result.skipped.groupingBy { it.reason.name }.fold(0) { n, region -> n + region.strokeCount }
            val wide = result.lines.count { it.writingArea.width > widestSensibleLine(page.strokes) }
            val note = page.id.substringBefore('/').takeLast(12)
            println(row(index, note, page.strokes.size, result.lines.size, wide, skipped))
            if (page.strokes.size >= GAP_HISTOGRAM_MIN_STROKES) {
                println("      gaps: " + gapHistogram(page.strokes, result))
            }
            draw(page, result, File(out, String.format(Locale.ROOT, "%02d-%s.png", index, note)))
        }
        println("  total: $allStrokes strokes, $allLines lines; pictures in ${out.path}")
    }

    private fun row(vararg cells: Any): String = String.format(Locale.ROOT, "%-3s %-12s %7s %6s %6s  %s", *cells)

    /**
     * The gaps between horizontally neighbouring strokes inside each line, in units of the page's
     * median stroke height, as `bin:count`. Word spaces and column gutters are different populations,
     * and this shows where one ends and the other begins on real ink.
     */
    private fun gapHistogram(strokes: List<SampledStroke>, result: Segmentation): String {
        val heights = strokes.map { it.bounds.height }.sorted()
        val scale = max(heights[heights.size / 2], 0.25f)
        val bins = sortedMapOf<Float, Int>()
        for (line in result.lines) {
            val ordered = line.strokes.sortedBy { it.bounds.left }
            var right = ordered.first().bounds.right
            for (stroke in ordered.drop(1)) {
                val gap = (stroke.bounds.left - right) / scale
                if (gap > 0f) {
                    val bin = (gap * 2f).toInt().coerceAtMost(GAP_BINS) / 2f
                    bins[bin] = (bins[bin] ?: 0) + 1
                }
                right = max(right, stroke.bounds.right)
            }
        }
        return "scale ${"%.1f".format(scale)} pt  " + bins.entries.joinToString(" ") { "${it.key}:${it.value}" }
    }

    /** A line wider than this is suspect: about 60 letters at the page's median stroke height. */
    private fun widestSensibleLine(strokes: List<SampledStroke>): Float {
        if (strokes.isEmpty()) return Float.MAX_VALUE
        val heights = strokes.map { it.bounds.height }.sorted()
        return heights[heights.size / 2] * WIDE_LINE_LETTERS
    }

    private fun draw(page: CorpusPage, result: Segmentation, file: File) {
        if (page.strokes.isEmpty()) return
        var box = page.strokes.first().bounds
        for (stroke in page.strokes) box = box.union(stroke.bounds)
        val scale = min(MAX_PIXELS / box.width, MAX_PIXELS / box.height).coerceAtMost(MAX_SCALE)
        val picture = Picture(
            width = max(1, ((box.width + MARGIN * 2) * scale).toInt()),
            height = max(1, ((box.height + MARGIN * 2) * scale).toInt()),
        )
        fun x(value: Float) = ((value - box.left + MARGIN) * scale).toInt()
        fun y(value: Float) = ((value - box.top + MARGIN) * scale).toInt()

        for (stroke in page.strokes) {
            val points = stroke.samples
            for (i in 1 until points.size) {
                picture.line(x(points[i - 1].x), y(points[i - 1].y), x(points[i].x), y(points[i].y), INK)
            }
        }
        for ((index, line) in result.lines.withIndex()) {
            val colour = hue(index)
            val area = line.writingArea
            picture.rect(x(area.left), y(area.top), x(area.right), y(area.bottom), colour, thickness = 2)
            picture.number(index, x(area.left), y(area.top) - DIGIT_SCALE * 6, colour)
        }
        for (region in result.skipped) {
            val area = region.bounds
            picture.rect(
                x(area.left),
                y(area.top),
                x(area.right),
                y(area.bottom),
                SKIPPED,
                thickness = 1,
                dashed = true,
            )
        }
        file.writeBytes(picture.png())
    }

    /** A distinct colour per line, so neighbouring lines can be told apart. */
    private fun hue(index: Int): Int {
        val h = (index * GOLDEN) % 1f * 6f
        val f = h - h.toInt()
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(1f, f, 0f)
            1 -> Triple(1f - f, 1f, 0f)
            2 -> Triple(0f, 1f, f)
            3 -> Triple(0f, 1f - f, 1f)
            4 -> Triple(f, 0f, 1f)
            else -> Triple(1f, 0f, 1f - f)
        }
        return rgb((r * 200).toInt(), (g * 200).toInt(), (b * 200).toInt())
    }

    private class CorpusPage(val id: String, val strokes: List<SampledStroke>)

    /**
     * The export's pages one at a time. ⚠️ **Not one `parseToJsonElement` over the file**: a tablet's
     * whole library is 71 MB of JSON, and its element tree did not fit in the test JVM's heap. Each
     * page object is cut out by bracket depth and parsed on its own.
     */
    private fun pagesOf(file: File): Sequence<CorpusPage> = sequence {
        val text = file.readText()
        var i = text.indexOf('[', text.indexOf("\"pages\"")) + 1
        var depth = 0
        var pageStart = -1
        while (i < text.length && depth >= 0) {
            when (text[i]) {
                '"' -> i = endOfString(text, i)

                '{', '[' -> {
                    if (depth == 0) pageStart = i
                    depth++
                }

                '}', ']' -> {
                    depth--
                    if (depth == 0) yield(pageOf(Json.parseToJsonElement(text.substring(pageStart, i + 1)).jsonObject))
                }
            }
            i++
        }
    }

    /** The index of the quote that closes the string opening at [start]. */
    private fun endOfString(text: String, start: Int): Int {
        var i = start + 1
        while (text[i] != '"') i += if (text[i] == '\\') 2 else 1
        return i
    }

    private fun pageOf(page: JsonObject): CorpusPage = CorpusPage(
        id = page.getValue("id").jsonPrimitive.content,
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

    /** An RGB raster with just enough drawing for this report, and a PNG encoder. */
    private class Picture(val width: Int, val height: Int) {
        private val pixels = IntArray(width * height) { WHITE }

        fun set(x: Int, y: Int, colour: Int) {
            if (x in 0 until width && y in 0 until height) pixels[y * width + x] = colour
        }

        /** Bresenham. */
        fun line(x0: Int, y0: Int, x1: Int, y1: Int, colour: Int) {
            var x = x0
            var y = y0
            val dx = abs(x1 - x0)
            val dy = -abs(y1 - y0)
            val sx = if (x0 < x1) 1 else -1
            val sy = if (y0 < y1) 1 else -1
            var error = dx + dy
            while (true) {
                set(x, y, colour)
                if (x == x1 && y == y1) return
                val twice = 2 * error
                if (twice >= dy) {
                    error += dy
                    x += sx
                }
                if (twice <= dx) {
                    error += dx
                    y += sy
                }
            }
        }

        fun rect(left: Int, top: Int, right: Int, bottom: Int, colour: Int, thickness: Int, dashed: Boolean = false) {
            for (t in 0 until thickness) {
                for (x in left..right) {
                    if (!dashed || (x / DASH) % 2 == 0) {
                        set(x, top + t, colour)
                        set(x, bottom - t, colour)
                    }
                }
                for (y in top..bottom) {
                    if (!dashed || (y / DASH) % 2 == 0) {
                        set(left + t, y, colour)
                        set(right - t, y, colour)
                    }
                }
            }
        }

        /** [value] in a 3×5 pixel font, scaled up. */
        fun number(value: Int, left: Int, top: Int, colour: Int) {
            for ((place, digit) in value.toString().withIndex()) {
                digit(FONT[digit - '0'], left + place * 4 * DIGIT_SCALE, top, colour)
            }
        }

        private fun digit(rows: IntArray, left: Int, top: Int, colour: Int) {
            for ((row, bits) in rows.withIndex()) {
                for (column in 0 until 3) {
                    if (bits and (4 shr column) != 0) dot(left + column * DIGIT_SCALE, top + row * DIGIT_SCALE, colour)
                }
            }
        }

        /** One font pixel: a [DIGIT_SCALE]-square block. */
        private fun dot(left: Int, top: Int, colour: Int) {
            for (dy in 0 until DIGIT_SCALE) {
                for (dx in 0 until DIGIT_SCALE) set(left + dx, top + dy, colour)
            }
        }

        fun png(): ByteArray {
            val raw = ByteArrayOutputStream()
            DeflaterOutputStream(raw).use { deflate ->
                val row = ByteArray(1 + width * 3)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val p = pixels[y * width + x]
                        row[1 + x * 3] = (p shr 16).toByte()
                        row[2 + x * 3] = (p shr 8).toByte()
                        row[3 + x * 3] = p.toByte()
                    }
                    deflate.write(row)
                }
            }
            val out = ByteArrayOutputStream()
            out.write(PNG_SIGNATURE)
            val header = ByteArrayOutputStream()
            DataOutputStream(header).apply {
                writeInt(width)
                writeInt(height)
                write(byteArrayOf(8, 2, 0, 0, 0))
            }
            chunk(out, "IHDR", header.toByteArray())
            chunk(out, "IDAT", raw.toByteArray())
            chunk(out, "IEND", ByteArray(0))
            return out.toByteArray()
        }

        private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
            val stream = DataOutputStream(out)
            stream.writeInt(data.size)
            val typed = type.encodeToByteArray() + data
            stream.write(typed)
            stream.writeInt(CRC32().apply { update(typed) }.value.toInt())
        }
    }

    private companion object {
        const val MAX_PIXELS = 2400f
        const val MAX_SCALE = 3f
        const val MARGIN = 20f
        const val GOLDEN = 0.618034f
        const val WIDE_LINE_LETTERS = 60f
        const val DIGIT_SCALE = 3
        const val DASH = 6
        const val GAP_HISTOGRAM_MIN_STROKES = 100
        const val GAP_BINS = 40
        val PNG_SIGNATURE =
            byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        val WHITE = rgb(255, 255, 255)
        val INK = rgb(70, 70, 70)
        val SKIPPED = rgb(230, 20, 20)

        /** Rows of a 3×5 font, most significant bit on the left. */
        val FONT = arrayOf(
            intArrayOf(7, 5, 5, 5, 7), intArrayOf(2, 6, 2, 2, 7), intArrayOf(7, 1, 7, 4, 7),
            intArrayOf(7, 1, 7, 1, 7), intArrayOf(5, 5, 7, 1, 1), intArrayOf(7, 4, 7, 1, 7),
            intArrayOf(7, 4, 7, 5, 7), intArrayOf(7, 1, 1, 1, 1), intArrayOf(7, 5, 7, 5, 7),
            intArrayOf(7, 5, 7, 1, 7),
        )

        fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b
    }
}
