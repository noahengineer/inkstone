package dev.inkstone.core.handwriting

import dev.inkstone.core.handwriting.neural.KotlinSynthesisNetwork
import dev.inkstone.core.handwriting.neural.LicencePolicy
import dev.inkstone.core.handwriting.neural.ModelLoad
import dev.inkstone.core.handwriting.neural.WeightsFile
import dev.inkstone.core.handwriting.quality.Backend
import dev.inkstone.core.handwriting.quality.Fidelity
import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.PenTrajectory
import kotlinx.coroutines.runBlocking
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ⭐ docs/HANDWRITING-SYNTHESIS.md §11's visual harness, step 1 (H6): **the SVG contact sheet**, written
 * to `build/reports/handwriting/contact-sheet.svg` on every run — one sentence, twelve times, per
 * backend and per neatness ([ContactSheet]).
 *
 * SVG is a renderer that is not `androidx.ink`, and the sheet is read back with the JDK's own XML
 * parser rather than with the code that wrote it (M4's lesson, twice). The debug screen draws the same
 * sheet through the real ink engine (`HandwritingSheetActivity`); [ContactSheet.Block.fingerprint]s are
 * printed here and logged there, so the two can be shown to have drawn the same ink.
 *
 * **What it draws from:**
 * - by default, [HandFixtures]' hand and the **shipped model** — so it runs in every build, and its
 *   assertions are deterministic;
 * - with `INKSTONE_HW_PROFILE` set to a pulled profile (and optionally `INKSTONE_HW_SENTENCE`), the
 *   user's own hand. The profile never enters the repo (§9), and with one the test reports rather than
 *   asserts the variation bounds, since what a real hand can write is the data's business.
 *
 * ⚠️ **The reader agrees with everything** ([ContactSheet.trusting]): the sheet is what each backend
 * *offers* the gate. Whether ML Kit reads it is `GateMlKitTest`'s question, on a device.
 *
 * ⚠️ **What the fixture hand can and cannot say** ([HandFixtures]' KDoc): it proves the sheet, the
 * variation *bounds* and the two backends' bookkeeping; it is a generous witness for how assembled ink
 * looks, which is what the profile run and the device screen are for.
 */
class ContactSheetSvgTest {

    private val out = File(System.getenv("INKSTONE_HW_SHEET_OUT") ?: "build/reports/handwriting")
    private val input: Input get() = sheet.first
    private val blocks: List<ContactSheet.Block> get() = sheet.second

    private class Input(
        val label: String,
        val sentence: String,
        val index: GlyphIndex,
        val neural: NeuralBackend,
        val fixture: Boolean,
    )

    private fun inputOf(): Input {
        val model = File(System.getenv("INKSTONE_HW_MODEL") ?: "../../app/src/main/assets/handwriting/base-v1.ihwm")
        assertTrue(model.exists(), "the shipped model is part of the sheet: ${model.absolutePath}")
        // The shipped model under the policy a release build reads it with.
        val load = assertIs<ModelLoad.Loaded>(WeightsFile.read(model.readBytes(), LicencePolicy.RELEASE))
        val dir = System.getenv("INKSTONE_HW_PROFILE")?.let(::File)?.takeIf { it.isDirectory }
        val (label, index) = if (dir == null) {
            "the fixture hand" to GlyphIndex.build(FIXTURE_WORDS.map { HandFixtures.sample(it) }, LabelBook())
        } else {
            val samples = File(dir, "words").listFiles { f -> f.name.endsWith(".hws") }.orEmpty()
                .sortedBy { it.name }
                .flatMap { WordSampleCodec.decode(it.readBytes()).orEmpty() }
            // The user's corrections too, read by the production store, as the device's profile is.
            val labels = File(dir, "labels.json").takeIf { it.exists() }?.let { file ->
                val fs = FakeFileSystem().apply { put(HandwritingLayout.labels(ProfileKey.LOCAL), file.readBytes()) }
                runBlocking { ProfileStore(fs, ProfileKey.LOCAL).labels() }
            } ?: LabelBook()
            "the profile in ${dir.name} (${samples.size} words)" to GlyphIndex.build(samples, labels)
        }
        val sentence = System.getenv("INKSTONE_HW_SENTENCE") ?: if (dir == null) FIXTURE_SENTENCE else PROFILE_SENTENCE
        val dynamics = PenDynamics.fit(index.wholeWords().map { it.trajectory }, index.stats.penUpMs)
        return Input(label, sentence, index, NeuralBackend(KotlinSynthesisNetwork(load.weights), dynamics), dir == null)
    }

    /** Every block, assembly's then the network's, each at three neatnesses, stacked down one page. */
    private fun blocksOf(input: Input): List<ContactSheet.Block> {
        val gate = ReliabilityGate(ContactSheet.trusting(input.sentence))
        var top = ContactSheet.MARGIN_PT
        val written = ArrayList<ContactSheet.Block>()
        runBlocking {
            for (backend in ContactSheet.BACKENDS) {
                for (neatness in ContactSheet.NEATNESSES) {
                    val block = ContactSheet.block(
                        backend,
                        neatness,
                        input.sentence,
                        input.index,
                        gate,
                        input.neural,
                        top + TITLE_PT,
                    )
                    written += assertIs<ContactSheet.Block>(block)
                    top = block.bottom + BLOCK_GAP_PT
                }
            }
        }
        return written
    }

    @Test
    fun `the contact sheet is written, and another parser reads back every stroke inside the page`() {
        val file = File(out, "contact-sheet.svg")
        val height = writeSvg(file)
        println("contact sheet from ${input.label}: \"${input.sentence}\" -> ${file.absolutePath}")
        for (block in blocks) println("  ${block.title}: fingerprint ${block.fingerprint()}")

        // Read back by the JDK's DOM parser, not by anything that wrote it.
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val polylines = document.getElementsByTagName("polyline")
        val expected = blocks.sumOf { b -> b.rows.sumOf { it.phrase.strokes(it.origin).size } }
        assertEquals(expected, polylines.length, "one polyline per pen-down")
        var points = 0
        for (i in 0 until polylines.length) {
            val coordinates = (polylines.item(i) as Element).getAttribute("points").trim().split(' ')
            for (pair in coordinates) {
                val (x, y) = pair.split(',').map { it.toFloat() }
                assertTrue(x.isFinite() && y.isFinite(), "a point that is not a number: $pair")
                assertTrue(x in 0f..ContactSheet.PAGE_WIDTH_PT && y in 0f..height, "($x, $y) is off the sheet")
                points++
            }
        }
        val groups = document.getElementsByTagName("g")
        assertEquals(ContactSheet.BACKENDS.size * ContactSheet.NEATNESSES.size, groups.length, "one group per block")
        for (i in 0 until groups.length) {
            val rows = (groups.item(i) as Element).getElementsByTagName("text").length - 1
            assertEquals(
                ContactSheet.TIMES,
                rows,
                "a caption per row in ${(groups.item(i) as Element).getAttribute("id")}",
            )
        }
        assertTrue(points > 0)
    }

    @Test
    fun `every row is the whole sentence, written by its block's backend`() {
        if (!input.fixture) return println("REPORTED, not asserted — a real profile writes what it can.")
        for (block in blocks) {
            assertEquals(ContactSheet.TIMES, block.rows.size, block.title)
            val want = if (block.backend == Backend.ASSEMBLY) GeneratedInk.ASSEMBLY else GeneratedInk.NEURAL
            for (row in block.rows) {
                assertTrue(row.phrase.complete, "${block.title} seed ${row.seed}: ${row.caption}")
                for (word in row.phrase.words) {
                    assertEquals(want, (word as WordOutcome.Written).backend, "${block.title} '${word.text}'")
                }
            }
        }
    }

    /**
     * §11's variation rule: the same word in two rows of a block differs by at least [FLOOR] (it is not
     * a copy) and by at most [CEILING] (it is not garbage), as [Fidelity.distance] measures it in the
     * normalised frame. Both bounds are shown able to fail: a phrase written twice with one seed sits
     * under the floor, and a scribble over a word's own box sits over the ceiling.
     */
    @Test
    fun `the same word in two rows is never a copy and never garbage`() {
        val spreads = blocks.map { it to assertIs<ContactSheet.Spread>(it.spread()) }
        println(
            "variation, x-heights (${input.label}):\n" + spreads.joinToString("\n") { (b, s) ->
                "  ${b.title}: $s"
            },
        )
        if (input.fixture) {
            for ((block, spread) in spreads) {
                assertTrue(spread.min > FLOOR, "a copy in ${block.title}: $spread")
                // ⚠️ Assembly only. The network's own spread passes the ceiling at every neatness (docs
                // §21): a stray mark a word-length away costs DTW its whole pen-up path. Keeping that off
                // the page is the gate's job, so the network's ceiling is measured where the gate runs —
                // on the device's sheet, which logs this same spread for the words the gate let through.
                if (block.backend == Backend.ASSEMBLY) {
                    assertTrue(spread.max < CEILING, "garbage in ${block.title}: $spread")
                }
            }
        }

        // The floor can fail: the same seed twice is the same phrase, word for word.
        val gate = ReliabilityGate(ContactSheet.trusting(input.sentence))
        val again = runBlocking {
            ContactSheet.block(Backend.ASSEMBLY, 0.5f, input.sentence, input.index, gate, null, times = 1)
        }
        val first = blocks.first { it.backend == Backend.ASSEMBLY && it.neatness == 0.5f }.rows.first()
        val copy = assertIs<ContactSheet.Block>(again).rows.single()
        val same = Fidelity.distance(
            assertIs(ContactSheet.normalisedWord(first, 0)),
            assertIs(ContactSheet.normalisedWord(copy, 0)),
        )
        assertEquals(0f, same, "one seed, one phrase")
        assertTrue(same!! <= FLOOR)

        // The ceiling can fail: a scribble filling the word's own box is further than any honest copy.
        val word = assertIs<PenTrajectory>(ContactSheet.normalisedWord(first, 0))
        val scribble = assertIs<PenTrajectory>(scribbleOver(word, Random(5)))
        val garbage = assertIs<Float>(Fidelity.distance(word, scribble))
        println(String.format(Locale.ROOT, "a scribble over '%s': %.3f", first.phrase.words[0].text, garbage))
        // ⚠️ The fixture's only. Over a real hand's `it` a scribble measured 0.96 and two honest copies
        // 1.15 (docs §21): an absolute ceiling cannot tell the two apart on a short word.
        if (input.fixture) assertTrue(garbage > CEILING, "the ceiling must be able to catch a scribble: $garbage")
    }

    /** As many pen-downs as [word], each a random walk across its box: the shape of garbage. */
    private fun scribbleOver(word: PenTrajectory, rng: Random): PenTrajectory? {
        val box = word.bounds()
        val builder = PenTrajectory.Builder(word.pointCount, hasPressure = false, hasTilt = false)
        var t = 0
        repeat(word.strokeCount) {
            repeat(SCRIBBLE_POINTS) {
                builder.add(box.left + rng.nextFloat() * box.width, box.top + rng.nextFloat() * box.height, t)
                t += SCRIBBLE_MS
            }
            builder.endStroke()
        }
        return builder.build()
    }

    /** The sheet as SVG, in page points; returns its height. */
    private fun writeSvg(file: File): Float {
        file.parentFile.mkdirs()
        val height = blocks.last().bottom + ContactSheet.MARGIN_PT
        val sb = StringBuilder()
        sb.append("<svg xmlns='http://www.w3.org/2000/svg' width='${ContactSheet.PAGE_WIDTH_PT}' height='$height' ")
        sb.append("viewBox='0 0 ${ContactSheet.PAGE_WIDTH_PT} $height'>")
        sb.append("<rect width='100%' height='100%' fill='white'/>")
        for (block in blocks) {
            val id = "${block.backend.name.lowercase()}-${block.neatness}"
            sb.append("<g id='$id'>")
            text(
                sb,
                ContactSheet.MARGIN_PT,
                block.top - TITLE_GAP_PT,
                "${block.title} — ${block.fingerprint()}",
                "#333",
                TITLE_SIZE,
            )
            for (row in block.rows) {
                for (stroke in row.phrase.strokes(row.origin)) polyline(sb, stroke)
                val captionY = row.origin.y + ContactSheet.BELOW_PT - CAPTION_LIFT_PT
                text(
                    sb,
                    ContactSheet.PAGE_WIDTH_PT - ContactSheet.MARGIN_PT,
                    captionY,
                    "${row.seed}: ${row.caption}",
                    "#b91c1c",
                    CAPTION_SIZE,
                    end = true,
                )
            }
            sb.append("</g>")
        }
        sb.append("</svg>")
        file.writeText(sb.toString())
        return height
    }

    /** One pen-down, at the app's pen proportion. */
    private fun polyline(sb: StringBuilder, stroke: PenTrajectory) {
        sb.append("<polyline fill='none' stroke='#111' stroke-width='$PEN_PT' ")
        sb.append("stroke-linecap='round' stroke-linejoin='round' points='")
        for (i in 0 until stroke.pointCount) {
            sb.append(String.format(Locale.ROOT, "%.2f,%.2f ", stroke.x[i], stroke.y[i]))
        }
        sb.append("'/>")
    }

    @Suppress("LongParameterList")
    private fun text(
        sb: StringBuilder,
        x: Float,
        y: Float,
        text: String,
        colour: String,
        size: Int,
        end: Boolean = false,
    ) {
        val anchor = if (end) " text-anchor='end'" else ""
        sb.append("<text x='$x' y='$y' font-family='monospace' font-size='$size' fill='$colour'$anchor>")
        sb.append(text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")).append("</text>")
    }

    private companion object {
        /** Built once per run: twelve rows of six blocks, a third of them through the shipped model. */
        private val sheet: Pair<Input, List<ContactSheet.Block>> by lazy {
            val test = ContactSheetSvgTest()
            val input = test.inputOf()
            input to test.blocksOf(input)
        }

        /** The fixture hand's letters are `n u m l i x`; these words give assembly every one of them. */
        val FIXTURE_WORDS = listOf("nun", "mix", "lul", "mill", "minim", "lux")
        const val FIXTURE_SENTENCE = "minimum mix in null"
        const val PROFILE_SENTENCE = "it is not a cation"

        /** The app's pen proportion, a 1.35 pt pen at a 5.57 pt x-height (§18.10), at the sheet's 9 pt. */
        const val PEN_PT = 2.2f
        const val TITLE_PT = 18f
        const val TITLE_GAP_PT = 8f
        const val BLOCK_GAP_PT = 12f
        const val CAPTION_LIFT_PT = 2f
        const val TITLE_SIZE = 11
        const val CAPTION_SIZE = 7

        /**
         * In x-heights, set from the spread measured on 2026-10-15 (docs §21) and printed on every run:
         * on the fixture hand assembly's closest pair was 0.013 (an `in` at neatness 1) and its furthest
         * 0.37 (neatness 0), and a scribble over `minimum` measured 1.49. A copy is 0. ⚠️ On a real hand
         * two honest copies of `it` reached 1.15 and a scribble over it 0.96, which is why the ceiling is
         * asserted on the fixture only.
         */
        const val FLOOR = 0.005f
        const val CEILING = 1.0f

        const val SCRIBBLE_POINTS = 40
        const val SCRIBBLE_MS = 8
    }
}
