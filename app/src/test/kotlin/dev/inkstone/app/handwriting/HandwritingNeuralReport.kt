package dev.inkstone.app.handwriting

import dev.inkstone.core.handwriting.GlyphIndex
import dev.inkstone.core.handwriting.LabelBook
import dev.inkstone.core.handwriting.NeuralBackend
import dev.inkstone.core.handwriting.PenDynamics
import dev.inkstone.core.handwriting.SourcePreference
import dev.inkstone.core.handwriting.WordSample
import dev.inkstone.core.handwriting.WordSampleCodec
import dev.inkstone.core.handwriting.neural.KotlinSynthesisNetwork
import dev.inkstone.core.handwriting.neural.LicencePolicy
import dev.inkstone.core.handwriting.neural.ModelLoad
import dev.inkstone.core.handwriting.neural.Primed
import dev.inkstone.core.handwriting.neural.WeightsFile
import dev.inkstone.core.model.PenTrajectory
import java.io.File
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test

/**
 * docs/HANDWRITING-SYNTHESIS.md H4 — **reports, not assertions**, over the user's own profile and the
 * shipped model, through the Kotlin network. SVG, a renderer that is not `androidx.ink`.
 *
 * ```
 * INKSTONE_HW_PROFILE=<dir holding words/<n>.hws>   — a pulled profile
 * INKSTONE_HW_OUT=<dir>                           — where the sheets go
 *   ./gradlew :app:testDebugUnitTest --tests '*HandwritingNeuralReport' --rerun
 * ```
 *
 * - `segmentation-v2.svg`: §5.4's real-ink check, by eye. Each learned word twice, coloured by letter:
 *   on the left as v1 cut it (the harvester's spans, by stroke), on the right as v2 reads it — the
 *   network's attention, teacher-forced over the user's own ink, through a monotonic Viterbi pass.
 * - `neural-words.svg`: words the network writes, unprimed and primed with the user's own words, at
 *   three neatnesses — what the neural backend offers the gate.
 *
 * ⚠️ It skips without a profile or a model, which is the normal state of a clone.
 */
class HandwritingNeuralReport {

    private val out = System.getenv("INKSTONE_HW_OUT")?.let(::File)
        ?: File(System.getProperty("java.io.tmpdir"), "inkstone-hw")
    private val asset = File("src/main/assets/${AssetModelSource.PATH}")

    private fun inputs(): Pair<List<WordSample>, KotlinSynthesisNetwork>? {
        val dir = System.getenv("INKSTONE_HW_PROFILE")?.let(::File)
        if (dir == null || !dir.isDirectory || !asset.exists()) {
            println("SKIPPED — set INKSTONE_HW_PROFILE to a pulled profile directory (and export a model).")
            return null
        }
        val samples = File(dir, "words").listFiles { f -> f.name.endsWith(".hws") }.orEmpty()
            .sortedBy { it.name }
            .flatMap { WordSampleCodec.decode(it.readBytes()).orEmpty() }
        val load = WeightsFile.read(asset.readBytes(), LicencePolicy.RELEASE) as? ModelLoad.Loaded ?: return null
        out.mkdirs()
        return samples to KotlinSynthesisNetwork(load.weights)
    }

    @Test
    fun `v2 segmentation of the users own words`() {
        val (samples, net) = inputs() ?: return
        // The index's own word units are normalised *and* self-scaled (GlyphIndex.selfScaled, §18.2 3):
        // a sample's frame is its line's, and on this profile that is sometimes twice the word's size.
        val units = GlyphIndex.build(samples, LabelBook()).wholeWords().associateBy { it.source.sampleId }
        val svg = Svg()
        var aligned = 0
        var agreeingPoints = 0
        var comparedPoints = 0
        for (sample in samples.sortedBy { it.label }) {
            val normal = units[sample.id]?.trajectory?.takeIf { it.strokeCount == sample.trajectory.strokeCount }
                ?: sample.frame.normalise(sample.trajectory)
            val v2 = normal?.let { net.align(sample.label, it) }?.letters() ?: continue
            requireNotNull(normal)
            val v1 = IntArray(normal.pointCount) { -1 }
            sample.glyphs.forEachIndexed { span, glyph ->
                for (stroke in glyph.strokes) {
                    if (stroke in 0 until normal.strokeCount) {
                        for (i in normal.strokeStart(stroke) until normal.strokeEnd(stroke)) v1[i] = span
                    }
                }
            }
            // Where v1 cut every letter, compare: the same letter index per point when spans are one letter each.
            if (sample.glyphs.isNotEmpty() && sample.glyphs.all { it.text.length == 1 } && v1.none { it < 0 }) {
                comparedPoints += v1.size
                agreeingPoints += v1.indices.count { v1[it] == v2[it] }
            }
            svg.label(
                "${sample.label}  (${sample.labelSource}, ${normal.pointCount} points, v1 spans ${sample.glyphs.size})",
            )
            svg.coloured(normal, v1, 0f)
            svg.coloured(normal, v2, COLUMN)
            aligned++
        }
        svg.write(File(out, "segmentation-v2.svg"))
        println(
            String.format(
                Locale.ROOT,
                "segmentation: %d words aligned; where v1 cut every letter, v2 agrees on %.1f%% of %d points",
                aligned,
                if (comparedPoints == 0) 0.0 else 100.0 * agreeingPoints / comparedPoints,
                comparedPoints,
            ),
        )
    }

    @Test
    fun `words the network writes`() {
        val (samples, net) = inputs() ?: return
        val index = GlyphIndex.build(samples, LabelBook())
        val backend = NeuralBackend(net, PenDynamics.fit(index.wholeWords().map { it.trajectory }, index.stats.penUpMs))
        val words = (System.getenv("INKSTONE_HW_WORDS") ?: "bob jab cation buzzing quiz Kelvin").split(' ')
        val svg = Svg()
        val primed = backend.prime(index, SourcePreference(), Random(7))
        println("primed=${primed.isPrimed}")
        for ((label, prime) in listOf("unprimed" to Primed.NONE, "primed with the user's words" to primed)) {
            for (neatness in listOf(0.25f, 0.5f, 0.9f)) {
                svg.label("$label, neatness $neatness")
                val written = backend.write(words, prime, neatness, 1, Random(11))
                svg.plain(rowOf(written))
                val steps = written.sumOf { it?.steps ?: 0 }
                println("$label n=$neatness: ${written.count { it != null }}/${words.size} finished, steps=$steps")
            }
        }
        svg.write(File(out, "neural-words.svg"))
    }

    /** Words side by side on one line; a word the network failed leaves a gap. */
    private fun rowOf(written: List<NeuralBackend.Word?>): List<PenTrajectory> {
        var x = 0f
        val row = ArrayList<PenTrajectory>()
        for (w in written) {
            val moved = w?.trajectory?.transformed(dev.inkstone.core.model.Matrix3.translate(x, 0f))
            if (moved != null) row += moved
            x += (w?.width ?: FAILED_WIDTH) + WORD_GAP
        }
        return row
    }

    /** A sheet in x-heights, y up, rows of [ROW] x-heights. */
    private class Svg {
        private val body = StringBuilder()
        private var y = 2.5f

        fun label(text: String) {
            body.append("<text x='4' y='${(y - 1.6f) * SCALE}' font-size='11' fill='#666'>${escape(text)}</text>")
        }

        fun coloured(t: PenTrajectory, letters: IntArray, dx: Float) {
            for (s in 0 until t.strokeCount) {
                for (i in t.strokeStart(s) + 1 until t.strokeEnd(s)) {
                    val colour = if (letters[i] < 0) "#bbb" else PALETTE[letters[i] % PALETTE.size]
                    line(t.x[i - 1] + dx, t.y[i - 1], t.x[i] + dx, t.y[i], colour)
                }
            }
            if (dx > 0f) y += ROW
        }

        fun plain(strokes: List<PenTrajectory>) {
            for (t in strokes) {
                for (s in 0 until t.strokeCount) {
                    for (i in t.strokeStart(s) + 1 until t.strokeEnd(s)) {
                        line(t.x[i - 1], t.y[i - 1], t.x[i], t.y[i], "#111")
                    }
                }
            }
            y += ROW
        }

        private fun line(x0: Float, y0: Float, x1: Float, y1: Float, colour: String) {
            body.append(
                "<line x1='${LEFT + x0 * SCALE}' y1='${(y - y0) * SCALE}' " +
                    "x2='${LEFT + x1 * SCALE}' y2='${(y - y1) * SCALE}' " +
                    "stroke='$colour' stroke-width='1.6' stroke-linecap='round'/>",
            )
        }

        fun write(file: File) {
            val height = (y + 1f) * SCALE
            file.writeText(
                "<svg xmlns='http://www.w3.org/2000/svg' width='1600' height='$height'>" +
                    "<rect width='100%' height='100%' fill='white'/>$body</svg>",
            )
            println("wrote ${file.absolutePath}")
        }

        private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private companion object {
        const val SCALE = 26f
        const val LEFT = 20f
        const val ROW = 3.2f
        const val COLUMN = 24f
        const val WORD_GAP = 1.2f
        const val FAILED_WIDTH = 0.8f
        val PALETTE = listOf("#c82828", "#286ec8", "#1e963c", "#c87800", "#8232aa", "#0096a0")
    }
}
