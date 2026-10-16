package dev.inkstone.core.handwriting

import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.PenTrajectory
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/**
 * docs/HANDWRITING-SYNTHESIS.md H5, §8.2's maths route — **a report**: on a pulled profile, which
 * numbers and formulas the strict route (verified letters and vouched words only, never overlapping) can
 * write, what it lacks, and a sheet of what it draws beside ordinary assembly. SVG, a renderer that is
 * not `androidx.ink`.
 *
 * ```
 * INKSTONE_HW_PROFILE=… ./gradlew :core:handwriting:testDebugUnitTest --tests '*MathsReport' --rerun
 * ```
 */
class MathsReport {

    private val tokens = listOf(
        "0.0375", "12.875", "2.5", "9.81", "-3", "-0.25", "46%", "3/8", "1.625", "0.25", "1.75", "100",
        "16384", "x=2", "2x+3", "y=mx+c", "(4.9", "-2.6)", "/", "=", "+", "-", "CaCO3", "H2O", "NO2", "6.02",
    )

    @Test
    fun `what the strict route can write on the users profile`() {
        val loaded = ProfileReports.load() ?: return
        val index = GlyphIndex.build(loaded.samples, loaded.labels)
        val assembly = GlyphAssembly(index)
        val verified = index.alphabet.filter { a -> index.variants(a).any { it.kind == UnitKind.LETTER } }.sorted()
        val vouched = index.wholeWords().count { it.vouched }
        println("verified single letters: ${verified.joinToString(" ")}")
        println("vouched whole words: $vouched")
        val digits = "0123456789.-+=/%()".map { it.toString() }
        println("maths characters with no verified copy: ${digits.filter { it !in verified }.joinToString(" ")}")
        val rows = ArrayList<Pair<String, List<PenTrajectory>>>()
        for (token in tokens) {
            val strict = assembly.word(
                token,
                Variation.forNeatness(Variation.DEFAULT_NEATNESS),
                Random(1),
                HashSet(),
                strict = true,
            )
            val loose = assembly.word(token, Variation.forNeatness(Variation.DEFAULT_NEATNESS), Random(1), HashSet())
            fun say(w: AssembledWord) = when (w) {
                is AssembledWord.Written -> "written (${w.units.joinToString("|") { it.text }})"
                is AssembledWord.Missing -> "missing ${w.letters}"
            }
            println("'$token' ${TokenClass.of(token)}: strict ${say(strict)}; ordinary ${say(loose)}")
            rows += token to listOfNotNull(
                (loose as? AssembledWord.Written)?.trajectory,
                (strict as? AssembledWord.Written)?.trajectory,
            )
        }
        sheet(rows, File(ProfileReports.out, "maths-strict.svg"))
    }

    /** Each token twice: ordinary assembly on the left, the strict route on the right. */
    private fun sheet(rows: List<Pair<String, List<PenTrajectory>>>, file: File) {
        val body = StringBuilder()
        var y = 2.5f
        for ((token, inks) in rows) {
            body.append("<text x='4' y='${(y - 1.4f) * SCALE}' font-size='11' fill='#666'>${escape(token)}</text>")
            inks.forEachIndexed { k, t ->
                val moved = t.transformed(Matrix3.translate(k * COLUMN, 0f)) ?: return@forEachIndexed
                for (s in 0 until moved.strokeCount) {
                    for (i in moved.strokeStart(s) + 1 until moved.strokeEnd(s)) {
                        body.append(
                            "<line x1='${LEFT + moved.x[i - 1] * SCALE}' y1='${(y - moved.y[i - 1]) * SCALE}' " +
                                "x2='${LEFT + moved.x[i] * SCALE}' y2='${(y - moved.y[i]) * SCALE}' stroke='#111' " +
                                "stroke-width='1.6' stroke-linecap='round'/>",
                        )
                    }
                }
            }
            y += ROW
        }
        file.writeText(
            "<svg xmlns='http://www.w3.org/2000/svg' width='900' height='${(y + 1f) * SCALE}'>" +
                "<rect width='100%' height='100%' fill='white'/>$body</svg>",
        )
        println("wrote ${file.absolutePath}")
        png(rows, File(file.parentFile, file.nameWithoutExtension + ".png"))
    }

    /** The same sheet as a PNG, through java.awt — a third renderer, and one an image viewer opens. */
    private fun png(rows: List<Pair<String, List<PenTrajectory>>>, file: File) {
        System.setProperty("java.awt.headless", "true")
        val height = ((rows.size * ROW + 2f) * SCALE).toInt()
        val image = java.awt.image.BufferedImage(900, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, 900, height)
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.stroke = java.awt.BasicStroke(1.6f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
        var y = 2.5f
        for ((token, inks) in rows) {
            g.color = java.awt.Color.GRAY
            g.drawString(token, 4, ((y - 1.4f) * SCALE).toInt())
            g.color = java.awt.Color.BLACK
            inks.forEachIndexed { k, t ->
                for (s in 0 until t.strokeCount) {
                    for (i in t.strokeStart(s) + 1 until t.strokeEnd(s)) {
                        g.drawLine(
                            (LEFT + (t.x[i - 1] + k * COLUMN) * SCALE).toInt(),
                            ((y - t.y[i - 1]) * SCALE).toInt(),
                            (LEFT + (t.x[i] + k * COLUMN) * SCALE).toInt(),
                            ((y - t.y[i]) * SCALE).toInt(),
                        )
                    }
                }
            }
            y += ROW
        }
        g.dispose()
        javax.imageio.ImageIO.write(image, "png", file)
        println("wrote ${file.absolutePath}")
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        const val SCALE = 22f
        const val LEFT = 90f
        const val ROW = 3.4f
        const val COLUMN = 16f
    }
}
