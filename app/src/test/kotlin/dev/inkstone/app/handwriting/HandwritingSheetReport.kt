package dev.inkstone.app.handwriting

import dev.inkstone.core.handwriting.AssembledWord
import dev.inkstone.core.handwriting.GlyphAssembly
import dev.inkstone.core.handwriting.GlyphIndex
import dev.inkstone.core.handwriting.HandwritingSynthesizer
import dev.inkstone.core.handwriting.LabelBook
import dev.inkstone.core.handwriting.LetterFit
import dev.inkstone.core.handwriting.LineStyles
import dev.inkstone.core.handwriting.NeuralBackend
import dev.inkstone.core.handwriting.PageStyle
import dev.inkstone.core.handwriting.PenDynamics
import dev.inkstone.core.handwriting.QuickStart
import dev.inkstone.core.handwriting.ReliabilityGate
import dev.inkstone.core.handwriting.UnitKind
import dev.inkstone.core.handwriting.UnitSource
import dev.inkstone.core.handwriting.Variation
import dev.inkstone.core.handwriting.WordOutcome
import dev.inkstone.core.handwriting.WordSample
import dev.inkstone.core.handwriting.WordSampleCodec
import dev.inkstone.core.handwriting.WriteRequest
import dev.inkstone.core.handwriting.WriteStyle
import dev.inkstone.core.handwriting.neural.KotlinSynthesisNetwork
import dev.inkstone.core.handwriting.neural.LicencePolicy
import dev.inkstone.core.handwriting.neural.ModelLoad
import dev.inkstone.core.handwriting.neural.WeightsFile
import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.InkReader
import dev.inkstone.core.model.InkReading
import dev.inkstone.core.model.InkSample
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PenTrajectory
import dev.inkstone.core.model.ReadCandidate
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.search.LineSegmenter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale
import kotlin.math.tan
import kotlin.random.Random
import kotlin.test.Test

/**
 * docs/HANDWRITING-SYNTHESIS.md §11's visual harness, step 1, and H3's real-page check — **reports,
 * not assertions**, over the user's own data pulled from a device. SVG is drawn here, which is a
 * renderer that is not `androidx.ink` (M4's lesson about checking output with the library that wrote
 * it).
 *
 * ```
 * INKSTONE_HW_PROFILE=<dir holding words/<n>.hws>   — the assembly sheets and the hand's numbers
 * INKSTONE_INK_CORPUS=<ink-export.json>           — PageStyle on real pages (InkExportReceiver)
 * INKSTONE_HW_OUT=<dir>                           — where the sheets go
 * INKSTONE_HW_SENTENCE="…"                        — what the sheet writes
 *   ./gradlew :app:testDebugUnitTest --tests '*HandwritingSheetReport' --rerun
 * ```
 *
 * ⚠️ **It skips without data**, which is the normal state of a clone: the profile and the ink are the
 * user's handwriting and are never committed.
 *
 * ⚠️ **The sheet shows assembly, not the gate's verdict.** The read-back needs ML Kit, which does not
 * run on the JVM, so the sheet's reader accepts every word. What the user sees in the app has passed
 * the real recogniser; what is drawn here is what assembly offers it.
 */
class HandwritingSheetReport {

    private val out = System.getenv("INKSTONE_HW_OUT")?.let(::File)
        ?: File(System.getProperty("java.io.tmpdir"), "inkstone-hw")

    @Test
    fun `assembly sheets from a real profile`() {
        val dir = System.getenv("INKSTONE_HW_PROFILE")?.let(::File)
        if (dir == null || !dir.isDirectory) {
            println("SKIPPED — set INKSTONE_HW_PROFILE to a pulled profile directory.")
            return
        }
        out.mkdirs()
        val samples = File(dir, "words").listFiles { f -> f.name.endsWith(".hws") }.orEmpty()
            .sortedBy { it.name }
            .flatMap { WordSampleCodec.decode(it.readBytes()).orEmpty() }
        val index = GlyphIndex.build(samples, LabelBook())
        report(samples, index)
        val sentence = System.getenv("INKSTONE_HW_SENTENCE") ?: "CaCO3 is a salt so it is not a cation pair"
        drawPhrases(index, sentence)
        drawMaths(index)
        drawUnits(index, samples.associate { it.id to it.label }, sentence.split(' '))
        drawJoins(index)
        println("  sheets in ${out.path}")
    }

    @Test
    fun `page style on real pages`() {
        val corpus = System.getenv("INKSTONE_INK_CORPUS")?.let(::File)
        if (corpus == null || !corpus.exists()) {
            println("SKIPPED — set INKSTONE_INK_CORPUS to an ink export (see InkExportReceiver).")
            return
        }
        out.mkdirs()
        val pages = Json.parseToJsonElement(corpus.readText()).jsonObject.getValue("pages").jsonArray
        for ((n, element) in pages.withIndex()) {
            val page = element.jsonObject
            val strokes = page.getValue("strokes").jsonArray.map { strokeOf(it.jsonObject) }
                .filter { it.samples.isNotEmpty() }
            val all = LineSegmenter.segment(strokes).lines
                .mapNotNull { line -> LineStyles.measure(line.strokes)?.let { line to it } }
            // The same filter the drop uses, so the numbers here are the numbers a drop sees.
            val lines = dev.inkstone.core.handwriting.PageWriting.writingLines(all)
            println("  ${all.size - lines.size} of ${all.size} lines are marks, not writing")
            val title = "page $n ${page.getValue("id").jsonPrimitive.content.takeLast(12)}: strokes=${strokes.size}"
            reportStyle(title, lines.map { it.second })
            val file = File(out, String.format(Locale.ROOT, "page-style-%02d.svg", n))
            drawPage(strokes, lines.map { it.second }, file)
        }
    }

    private fun strokeOf(stroke: JsonObject): SampledStroke {
        val samples = stroke.getValue("points").jsonArray.map { p ->
            val point = p.jsonArray
            InkSample(
                point[0].jsonPrimitive.content.toFloat(),
                point[1].jsonPrimitive.content.toFloat(),
                point[2].jsonPrimitive.content.toLong(),
            )
        }
        return SampledStroke(ObjectId(stroke.getValue("id").jsonPrimitive.content), samples, boxOf(samples))
    }

    private fun reportStyle(title: String, lines: List<dev.inkstone.core.handwriting.LineStyle>) {
        val style = PageStyle.of(lines)
        val slants = lines.map { Math.toDegrees(it.slantRad.toDouble()) }.sorted()
        val angles = lines.map { Math.toDegrees(it.angleRad.toDouble()) }.sorted()
        println(
            String.format(
                Locale.ROOT,
                "%s lines=%d x-height=%.2f pt pitch=%s pt margin=%.1f pt " +
                    "slant median=%.1f° (p10 %.1f, p90 %.1f) angle median=%.1f°",
                title,
                lines.size,
                style.xHeightPt ?: Float.NaN,
                style.linePitchPt?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-",
                style.leftMarginPt ?: Float.NaN,
                slants.getOrElse(slants.size / 2) { 0.0 },
                slants.getOrElse(slants.size / 10) { 0.0 },
                slants.getOrElse(slants.size * 9 / 10) { 0.0 },
                angles.getOrElse(angles.size / 2) { 0.0 },
            ),
        )
        val xh = lines.map { it.xHeightPt }.sorted()
        println(
            String.format(
                Locale.ROOT,
                "  x-height p10 %.2f p50 %.2f p90 %.2f over %d lines",
                xh.getOrElse(xh.size / 10) { 0f },
                xh.getOrElse(xh.size / 2) { 0f },
                xh.getOrElse(xh.size * 9 / 10) { 0f },
                xh.size,
            ),
        )
    }

    private fun report(samples: List<WordSample>, index: GlyphIndex) {
        println("=== the hand ===")
        println("samples=${samples.size} units=${index.unitCount} longest=${index.longestUnit}")
        println("alphabet=${index.alphabet.sorted().joinToString("")}")
        val stats = index.stats
        println(
            String.format(
                Locale.ROOT,
                "x-height median %.2f pt, slant %.1f°, pen-up %d ms",
                stats.xHeightPt,
                Math.toDegrees(stats.slantRad.toDouble()),
                stats.penUpMs,
            ),
        )
        val gaps = { values: List<Float> -> values.sorted().joinToString { "%.2f".format(Locale.ROOT, it) } }
        println("word gaps (x-heights, measured ${stats.wordGaps.size}): " + gaps(stats.wordGaps))
        // §18.10: the quick start asks for "a clear gap between words", so its words give none.
        val quick = samples.count { it.noteId.value == QuickStart.NOTE_ID }
        println("  measured from notes only; the quick start's $quick words give no gaps")
        val done = QuickStart.promptsDone(samples)
        println("quick start: prompts done $done, opens at ${QuickStart.firstToDo(done)} of ${QuickStart.PROMPTS.size}")
        println("letter gaps lower→lower: " + gaps(stats.letterGapsFor("a", "b")))
        val pairs = samples.flatMap { s -> s.label.zipWithNext { a, b -> "$a$b" } }.toSet()
        val counted = pairs.map { it to stats.joinCount(it.substring(0, 1), it.substring(1)) }
            .filter { it.second.total > 0 }
        val joined = counted.filter { it.second.joined > 0 }
        println("pairs joined: " + joined.joinToString { "${it.first}(${it.second.joined}/${it.second.total})" })
        println("pairs lifted only: " + counted.filter { it.second.joined == 0 }.joinToString { it.first })
        val runs = samples.flatMap { s -> s.glyphs.filter { it.text.length > 1 }.map { it.text } }
        println("joined runs cut: ${runs.joinToString()}")
        // §18.10: what a decimal point, a minus and an equals actually are, in x-heights.
        for (mark in listOf(".", ",", "-", "=", "+", "/")) {
            val shapes = index.variants(mark).joinToString { unit ->
                val t = unit.trajectory
                val box = t.bounds()
                "${t.strokeCount}s/${t.pointCount}p ${"%.2f".format(Locale.ROOT, box.width)}" +
                    "×${"%.2f".format(Locale.ROOT, box.height)} at y ${"%.2f".format(Locale.ROOT, box.top)}"
            }
            println("  '$mark' units: ${shapes.ifEmpty { "none" }}")
        }
        reportPlacements(index)
    }

    /** §18.7: where each kind of cut letter sits in its word's frame, and the words set aside. */
    private fun reportPlacements(index: GlyphIndex) {
        val f = { v: Float -> "%.2f".format(Locale.ROOT, v) }
        val byShape = index.placements.groupBy { LetterFit.shapeOf(it.letter) }
        for ((shape, list) in byShape.toSortedMap()) {
            val lows = list.map { it.low }.sorted()
            val highs = list.map { it.high }.sorted()
            val q = { xs: List<Float>, p: Int -> xs[(xs.size - 1) * p / 100] }
            println(
                "letters $shape n=${list.size} low p5 ${f(q(lows, 5))} p50 ${f(q(lows, 50))} p95 ${f(q(lows, 95))}" +
                    " | high p5 ${f(q(highs, 5))} p50 ${f(q(highs, 50))} p95 ${f(q(highs, 95))}",
            )
        }
        // Capitals, digits and symbols (§18.10): each one's spread, the words its extremes are from, and how
        // many copies its window sets aside.
        val lowerCase = setOf(LetterFit.Shape.X_HEIGHT, LetterFit.Shape.TALL, LetterFit.Shape.DESCENDER)
        val others = index.placements.filter { LetterFit.shapeOf(it.letter) !in lowerCase }
        for ((letter, list) in others.groupBy { it.letter }.toSortedMap()) {
            val highs = list.map { it.high }.sorted()
            val lows = list.map { it.low }.sorted()
            val worst = list.sortedByDescending { maxOf(it.high - highs[highs.size / 2], lows[lows.size / 2] - it.low) }
                .take(2).joinToString { "${it.word} ${f(it.low)}..${f(it.high)}${if (it.fits) "" else " ✗"}" }
            println(
                "  '$letter' n=${list.size} out=${list.count { !it.fits }}" +
                    " low ${f(lows.first())}/${f(lows[lows.size / 2])}/${f(lows.last())}" +
                    " high ${f(highs.first())}/${f(highs[highs.size / 2])}/${f(highs.last())} extremes: $worst",
            )
        }
        val misfits = index.placements.filter { !it.fits }
        val words = misfits.groupBy { it.sampleId }
        val judged = index.placements.map { it.sampleId }.toSet().size
        println("words set aside: ${words.size} of $judged judged")
        for (word in words.values) {
            println("  ${word.first().word}: " + word.joinToString { "${it.letter} ${f(it.low)}..${f(it.high)}" })
        }
        println("words not used whole, ink beyond their letters: ${index.strayInkWords.size}: ${index.strayInkWords}")
    }

    /** One sentence, twelve times, at three neatnesses — §11's contact sheet. */
    private fun drawPhrases(index: GlyphIndex, sentence: String) {
        val words = sentence.split(' ').filter { it.isNotBlank() }
        val trusting = InkReader { inks, _ -> InkReading.Read(inks.map { words.map { w -> ReadCandidate(w, null) } }) }
        val synthesizer = HandwritingSynthesizer(ReliabilityGate(trusting))
        val svg = Svg(width = 900f, rowHeight = 46f)
        for (neatness in listOf(0f, 0.5f, 1f)) {
            svg.label("neatness $neatness")
            repeat(TIMES) { seed ->
                val style = WriteStyle(
                    xHeightPt = 9f,
                    slantRad = index.stats.slantRad,
                    angleRad = 0f,
                    wrapWidthPt = 860f,
                    linePitchPt = 30f,
                    neatness = neatness,
                )
                val phrase = runBlocking { synthesizer.write(WriteRequest(sentence, style, seed.toLong() + 1), index) }
                val refused = phrase.refusals.joinToString { "${it.text}: ${it.reason}" }
                svg.row(phrase.strokes(PagePoint(0f, 0f)), note = refused.ifEmpty { null })
            }
        }
        svg.write(File(out, "assembly-sentence.svg"))
    }

    /**
     * §18.10 — numbers and maths through the **whole** synthesizer: assembly first, and the shipped
     * model for what assembly cannot write, exactly as the Write panel runs them. Each row says which
     * backend wrote which word. The reader still accepts everything (see the class KDoc), so a word the
     * real gate would refuse is drawn here; how often that happens is the device's question.
     */
    private fun drawMaths(index: GlyphIndex) {
        val asset = File("src/main/assets/${AssetModelSource.PATH}")
        val load = if (asset.exists()) WeightsFile.read(asset.readBytes(), LicencePolicy.RELEASE) else null
        val neural = (load as? ModelLoad.Loaded)?.let {
            NeuralBackend(
                KotlinSynthesisNetwork(it.weights),
                PenDynamics.fit(index.wholeWords().map { u -> u.trajectory }, index.stats.penUpMs),
            )
        }
        val phrases = (System.getenv("INKSTONE_HW_MATHS") ?: DEFAULT_MATHS).split('|')
        val svg = Svg(width = 900f, rowHeight = 46f)
        for (text in phrases) {
            val words = text.split(' ').filter { it.isNotBlank() }
            val trusting =
                InkReader { inks, _ -> InkReading.Read(inks.map { words.map { w -> ReadCandidate(w, null) } }) }
            val synthesizer = HandwritingSynthesizer(ReliabilityGate(trusting), neural)
            svg.label(text)
            repeat(MATHS_TIMES) { seed ->
                val style = WriteStyle(
                    xHeightPt = 9f,
                    slantRad = index.stats.slantRad,
                    angleRad = 0f,
                    wrapWidthPt = 860f,
                    linePitchPt = 30f,
                    neatness = 0.5f,
                )
                val phrase = runBlocking { synthesizer.write(WriteRequest(text, style, seed.toLong() + 1), index) }
                val who = phrase.words.joinToString(" ") { word ->
                    when (word) {
                        is WordOutcome.Written -> if (word.backend ==
                            GeneratedInk.NEURAL
                        ) {
                            "[${word.text}:net]"
                        } else {
                            word.text
                        }

                        is WordOutcome.Refused -> "{${word.text}: ${word.reason}}"
                    }
                }
                svg.row(phrase.strokes(PagePoint(0f, 0f)), note = who)
            }
        }
        svg.write(File(out, "maths.svg"))
    }

    /** Every letter and run the index holds, as assembly copies them — the material, checked by eye. */
    private fun drawUnits(index: GlyphIndex, labels: Map<String, String>, words: List<String>) {
        val svg = Svg(width = 900f, rowHeight = 40f)
        // The sentence's words and every run inside them: what the sentence sheet can be built from.
        val runs = words.flatMap { w -> (2..minOf(3, w.length)).flatMap { n -> w.windowed(n) } }
        val texts = index.alphabet.sorted() + (words.filter { it.length > 1 } + runs).distinct()
        for (text in texts) {
            val variants = index.variants(text)
            if (variants.isEmpty()) continue
            val kinds = variants.map { it.kind }.toSet().joinToString()
            val from = variants.take(MAX_VARIANTS).joinToString(" | ") { labels[it.source.sampleId].orEmpty() }
            svg.label("'$text' ×${variants.size} ($kinds) from $from")
            svg.row(variants.take(MAX_VARIANTS).flatMapIndexed { i, unit -> scaled(unit.trajectory, 9f, i * 70f) })
        }
        svg.write(File(out, "assembly-units.svg"))
    }

    /** Pairs of letters assembled from single letters: joined where the hand joins, apart where it lifts. */
    private fun drawJoins(index: GlyphIndex) {
        val svg = Svg(width = 900f, rowHeight = 40f)
        val letters = index.alphabet.filter { index.variants(it).any { u -> u.kind == UnitKind.LETTER } }.sorted()
        val assembly = GlyphAssembly(index)
        val seen = letters.flatMap { a -> letters.map { b -> a to b } }
            .filter { (a, b) -> index.stats.joinCount(a, b).total > 0 || index.stats.joins(a, b) }
        val drawn = seen.asSequence().mapIndexedNotNull { i, (a, b) ->
            val word = assembly.word(a + b, Variation.None, Random(i), HashSet<UnitSource>())
            (word as? AssembledWord.Written)?.let { Triple(a, b, it) }
        }.take(MAX_PAIRS)
        for ((a, b, word) in drawn) {
            val count = index.stats.joinCount(a, b)
            val hand = if (index.stats.joins(a, b)) "joins" else "lifts"
            svg.label(
                "$a$b: hand $hand (${count.joined} joined / ${count.total} seen) → " +
                    "${word.joins} bridge(s), ${word.trajectory.strokeCount} strokes",
            )
            svg.row(scaled(word.trajectory, 9f, 0f))
        }
        svg.write(File(out, "assembly-joins.svg"))
    }

    /** A normalised trajectory (y up, x-height 1) as page strokes at [xh] points, shifted right by [dx]. */
    private fun scaled(t: PenTrajectory, xh: Float, dx: Float): List<PenTrajectory> =
        (0 until t.strokeCount).mapNotNull { s ->
            t.strokes(listOf(s))?.let { one ->
                PenTrajectory.of(
                    FloatArray(one.pointCount) { one.x[it] * xh + dx },
                    FloatArray(one.pointCount) { -one.y[it] * xh },
                    one.tMs,
                    null,
                    null,
                    one.strokeEnds,
                )
            }
        }

    private fun drawPage(
        strokes: List<SampledStroke>,
        lines: List<dev.inkstone.core.handwriting.LineStyle>,
        file: File,
    ) {
        val box = strokes.map { it.bounds }.reduce(PageRect::union)
        val frame = "x='${box.left - 10}' y='${box.top - 10}' width='${box.width + 20}' height='${box.height + 20}'"
        val sb = StringBuilder()
        sb.append("<svg xmlns='http://www.w3.org/2000/svg' viewBox='${box.left - 10} ${box.top - 10} ")
        sb.append("${box.width + 20} ${box.height + 20}'>")
        sb.append("<rect $frame fill='white'/>")
        for (stroke in strokes) {
            sb.append("<polyline fill='none' stroke='#555' stroke-width='0.6' points='")
            stroke.samples.forEach { sb.append(String.format(Locale.ROOT, "%.1f,%.1f ", it.x, it.y)) }
            sb.append("'/>")
        }
        for (line in lines) {
            val l = line.bounds.left
            val r = line.bounds.right
            fun y(x: Float) = line.baselineY(x)
            sb.append(
                String.format(
                    Locale.ROOT,
                    "<line x1='%.1f' y1='%.1f' x2='%.1f' y2='%.1f' stroke='#2563eb' stroke-width='0.5'/>",
                    l,
                    y(l),
                    r,
                    y(r),
                ),
            )
            sb.append(
                String.format(
                    Locale.ROOT,
                    "<line x1='%.1f' y1='%.1f' x2='%.1f' y2='%.1f' stroke='#16a34a' stroke-width='0.4'/>",
                    l,
                    y(l) - line.xHeightPt,
                    r,
                    y(r) - line.xHeightPt,
                ),
            )
            // The slant: a tick from the baseline up one x-height, leaning as the line leans.
            sb.append(
                String.format(
                    Locale.ROOT,
                    "<line x1='%.1f' y1='%.1f' x2='%.1f' y2='%.1f' stroke='#dc2626' stroke-width='0.6'/>",
                    l,
                    y(l),
                    l + line.xHeightPt * tan(line.slantRad),
                    y(l) - line.xHeightPt,
                ),
            )
        }
        sb.append("</svg>")
        file.writeText(sb.toString())
    }

    private fun boxOf(samples: List<InkSample>): PageRect =
        PageRect(samples.minOf { it.x }, samples.minOf { it.y }, samples.maxOf { it.x }, samples.maxOf { it.y })

    /** Rows of strokes, top to bottom, each with an optional caption. */
    private class Svg(private val width: Float, private val rowHeight: Float) {
        private val body = StringBuilder()
        private var y = 20f

        fun label(text: String) {
            body.append("<text x='4' y='${y + 10}' font-family='monospace' font-size='9' fill='#666'>")
            body.append(escape(text)).append("</text>")
            y += 14f
        }

        fun row(strokes: List<PenTrajectory>, note: String? = null) {
            val baseline = y + rowHeight * 0.7f
            for (stroke in strokes) {
                // The app's own proportion — a 1.35 pt pen at a 5.57 pt x-height, 0.24 x-heights — so a
                // decimal point shows here as it does on the page. At 0.9 a dot drew as nothing (§18.10).
                body.append("<polyline fill='none' stroke='#111' stroke-width='2.2' ")
                body.append("stroke-linecap='round' stroke-linejoin='round' points='")
                for (i in 0 until stroke.pointCount) {
                    body.append(String.format(Locale.ROOT, "%.2f,%.2f ", stroke.x[i] + 10f, stroke.y[i] + baseline))
                }
                body.append("'/>")
            }
            note?.let {
                body.append("<text x='4' y='${y + rowHeight - 2}' font-size='8' fill='#b91c1c'>")
                body.append(escape(it)).append("</text>")
            }
            y += rowHeight
        }

        fun write(file: File) {
            file.writeText(
                "<svg xmlns='http://www.w3.org/2000/svg' width='$width' height='${y + 20}'>" +
                    "<rect width='100%' height='100%' fill='white'/>$body</svg>",
            )
        }

        private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private companion object {
        const val TIMES = 12
        const val MAX_VARIANTS = 12
        const val MAX_PAIRS = 40
        const val MATHS_TIMES = 3

        /** `|`-separated, so a phrase can hold spaces. Only characters the shipped model's alphabet has. */
        const val DEFAULT_MATHS = "3.14159|x = 2.5|0.0375|12.875 + 4.06 = 16.935|y = -0.25x + 3|" +
            "v = 9.81 m/s|45% of 260|(2.1, -3.4)|1/2 + 3/4 = 5/4|-17.08 and 2024|F = ma = 7.2 N"
    }
}
