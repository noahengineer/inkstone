package dev.inkstone.core.handwriting

import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.InkReader
import dev.inkstone.core.model.InkReading
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.ReadCandidate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * docs/HANDWRITING-MATHS.md §5 — **a report**: two-dimensional maths written from a pulled profile and
 * the shipped model, through the real synthesizer with the *Maths* switch on, drawn by java.awt (not
 * `androidx.ink`, not the code that wrote it) to a PNG to be looked at. The model's strokes are blue.
 *
 * ⚠️ The recogniser is replaced by one that passes every run, because ML Kit does not run on the JVM:
 * this sheet is about layout and the user's own letters, not legibility. The emulator walk is where
 * the real gate reads the runs.
 *
 * ```
 * INKSTONE_HW_PROFILE=… ./gradlew :core:handwriting:testDebugUnitTest --tests '*MathsLayoutReport' --rerun
 * ```
 */
class MathsLayoutReport {

    private val expressions = listOf(
        "1/2", "3/4", "2 3/4", "(x+1)/(x-1)", "1/2 + 3/4", "√2", "√(x+1)", "∛27", "root(5, x)",
        "√(a^2+b^2)", "x^2", "x^(n+1)", "e^-x", "a_1", "CO_2", "|x-3|", "(a/b)^2", "∛(8/27)",
        "y = (x+1)/(2x)", "x = (-b ± √(b^2-4ac))/(2a)",
    )

    /** The walk's own expression (§5.3), measured beside the sheet's through the real gate (§5.4). */
    private val walked = listOf("(x+1)/(2)")

    /**
     * §5.4's plan: every expression with the runs the synthesizer will read back, in its order. A
     * device has no `MathNotation` to ask (it is internal), so `MathsGateReceiver` is handed this file
     * and names each reading's run by its pre-context: run *i* is read after the runs before it,
     * joined by spaces. That only works if no run holds a space, which is asserted here.
     */
    @Test
    fun `the runs each expression is read as`() {
        val plan = (expressions + walked).map { e ->
            val pieces = MathWords.of(HandwritingSynthesizer.tokensOf(e, maths = true)).pieces.map { it.text }
            assertTrue(pieces.isNotEmpty() && pieces.none { p -> p.any(Char::isWhitespace) }, "$e: $pieces")
            JsonObject(mapOf("text" to JsonPrimitive(e), "pieces" to JsonArray(pieces.map(::JsonPrimitive))))
        }
        val file = File("build/reports/handwriting/maths-plan.json").apply { parentFile.mkdirs() }
        file.writeText(JsonObject(mapOf("expressions" to JsonArray(plan))).toString())
        println("wrote ${file.absolutePath}")
    }

    @Test
    fun `maths written from the users profile`() = runTest {
        val loaded = ProfileReports.load() ?: return@runTest
        val index = GlyphIndex.build(loaded.samples, loaded.labels)
        val dynamics = PenDynamics.fit(index.wholeWords().map { it.trajectory }, index.stats.penUpMs)
        val neural = NeuralBackend(ProfileReports.network(loaded.weights), dynamics)
        val texts = expressions.flatMap { e ->
            HandwritingSynthesizer.tokensOf(e, maths = true).flatMap { t ->
                MathNotation.parse(t.text)?.let(MathNotation::texts)
                    ?: listOf(t.text)
            }
        }.distinct()
        val reader = trusting(texts)
        val style = WriteStyle(
            xHeightPt = X_HEIGHT,
            slantRad = index.stats.slantRad,
            angleRad = 0f,
            wrapWidthPt = WRAP,
            linePitchPt = PITCH,
        )
        val rows = ArrayList<Row>()
        for ((k, text) in expressions.withIndex()) {
            val phrase = HandwritingSynthesizer(ReliabilityGate(reader), neural)
                .write(WriteRequest(text, style, seed = 100L + k, maths = true), index)
            val tagged = phrase.taggedStrokes(PagePoint(0f, 0f), "g")
            val caption = phrase.words.joinToString(" ") { word ->
                when (word) {
                    is WordOutcome.Written -> {
                        val tags = word.strokeTags ?: List(word.ink.strokeCount) { word.backend to word.model }
                        val net = tags.count { it.first == GeneratedInk.NEURAL }
                        if (net > 0) "${word.text}[net $net/${tags.size}]" else word.text
                    }

                    is WordOutcome.Refused -> "✕${word.text}: ${word.reason}"
                }
            }
            println("MATHS '$text' complete=${phrase.complete} $caption")
            rows += Row(text, caption, tagged.map { it.first to (it.second.backend == GeneratedInk.NEURAL) })
        }
        png(rows, File(ProfileReports.out, "maths-layout.png"))
    }

    private class Row(
        val text: String,
        val caption: String,
        val strokes: List<Pair<dev.inkstone.core.model.PenTrajectory, Boolean>>,
    )

    private fun trusting(texts: List<String>): InkReader {
        val candidates = texts.map { ReadCandidate(it, null) }
        return InkReader { inks, _ -> InkReading.Read(inks.map { candidates }) }
    }

    /** One row per expression: its typed form on the left, its ink at [SCALE] px per point beside it. */
    private fun png(rows: List<Row>, file: File) {
        System.setProperty("java.awt.headless", "true")
        val height = (rows.size * ROW_PX + MARGIN_PX).toInt()
        val image = java.awt.image.BufferedImage(WIDTH_PX, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, WIDTH_PX, height)
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.stroke = java.awt.BasicStroke(PEN_PX, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
        for ((r, row) in rows.withIndex()) {
            val baseline = MARGIN_PX + r * ROW_PX + BASELINE_PX
            g.color = java.awt.Color.GRAY
            g.drawString(row.text, 8, baseline.toInt())
            g.drawString(row.caption.take(CAPTION_CHARS), CAPTION_X, (baseline + CAPTION_DROP).toInt())
            g.color = java.awt.Color(0xEEEEEE)
            g.drawLine(INK_X, baseline.toInt(), WIDTH_PX - 8, baseline.toInt())
            for ((stroke, neural) in row.strokes) {
                g.color = if (neural) java.awt.Color(0x1D4ED8) else java.awt.Color.BLACK
                for (i in 1 until stroke.pointCount) {
                    g.drawLine(
                        (INK_X + stroke.x[i - 1] * SCALE).toInt(),
                        (baseline + stroke.y[i - 1] * SCALE).toInt(),
                        (INK_X + stroke.x[i] * SCALE).toInt(),
                        (baseline + stroke.y[i] * SCALE).toInt(),
                    )
                }
            }
        }
        g.dispose()
        javax.imageio.ImageIO.write(image, "png", file)
        println("wrote ${file.absolutePath}")
    }

    private companion object {
        const val X_HEIGHT = 9f
        const val WRAP = 900f
        const val PITCH = 60f
        const val SCALE = 2.4f
        const val WIDTH_PX = 1400
        const val ROW_PX = 96f
        const val MARGIN_PX = 16f
        const val BASELINE_PX = 58f
        const val INK_X = 260
        const val PEN_PX = 2.2f
        const val CAPTION_X = 900
        const val CAPTION_DROP = 22f
        const val CAPTION_CHARS = 70
    }
}
