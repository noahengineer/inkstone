package dev.inkstone.core.handwriting

import dev.inkstone.core.handwriting.neural.Alignment
import dev.inkstone.core.handwriting.neural.NetworkWord
import dev.inkstone.core.handwriting.neural.Prime
import dev.inkstone.core.handwriting.neural.Primed
import dev.inkstone.core.handwriting.neural.SynthesisNetwork
import dev.inkstone.core.handwriting.quality.Backend
import dev.inkstone.core.handwriting.quality.Route
import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.PenTrajectory
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * docs/HANDWRITING-SYNTHESIS.md §8.2 and §8.4 in the synthesizer: a token the recogniser cannot judge is
 * built from verified letters and never read back or handed to the network; with the network primary,
 * it goes first; a word holding a character routed away goes to assembly when assembly can write it.
 *
 * ⚠️ The network is a fake that writes a zigzag and records what it was asked, and the reader is
 * scripted. These prove routing — who was asked for which word, and what the gate saw — not legibility.
 */
class SwitchingTest {

    private val style = WriteStyle(xHeightPt = 6f, slantRad = 0f, angleRad = 0f, wrapWidthPt = 400f, linePitchPt = 20f)

    private fun request(text: String) = WriteRequest(text, style, 5L)

    /** A digit learned only inside an AGREED word, and the same digit cut on its own. */
    private val numbersIndex = GlyphIndex.build(
        listOf(HandFixtures.sample("33"), HandFixtures.sample("nun")),
        LabelBook(),
    )

    /** NUMBER cannot be judged on this hand; everything else is held to the default rule. */
    private val calibration = GateCalibration(
        1L,
        mapOf(
            TokenClass.NUMBER to ClassCalibration(
                TokenClass.NUMBER, 10, 10, false, emptyMap(), emptyMap(), emptyMap(), GateRule.EXACT, judgeable = false,
            ),
        ),
    )

    @Test
    fun aNumberTheRecogniserCannotJudgeIsBuiltFromVerifiedLettersAndNotReadBack() = runTest {
        val reader = ScriptedReader(fallback = { listOf("nun") })
        val network = Recording(" 0123456789nu") { zigzag(it) }
        val phrase = HandwritingSynthesizer(
            ReliabilityGate(reader, calibration),
            NeuralBackend(network, PenDynamics.Unmeasured),
        )
            .write(request("nun 333"), numbersIndex)

        assertTrue(phrase.complete, "${phrase.refusals}")
        val number = assertIs<WordOutcome.Written>(phrase.words[1])
        assertFalse(number.readBack, "built by construction")
        assertEquals(GeneratedInk.ASSEMBLY, number.backend)
        assertEquals(0, network.asked.count { "333" in it }, "the network is never asked for it")
        assertEquals(1, reader.calls.flatten().size, "the gate read `nun` alone")
    }

    @Test
    fun strictUnitsNeverOverlap() {
        val assembly = GlyphAssembly(numbersIndex)
        repeat(20) { seed ->
            val word = assertIs<AssembledWord.Written>(
                assembly.word("333", Variation.Table, Random(seed), HashSet(), strict = true),
            )
            assertTrue(word.units.all { it.kind == UnitKind.LETTER }, "an AGREED whole word is not a verified unit")
            val boxes = (0 until word.trajectory.strokeCount).map { word.trajectory.strokes(listOf(it))?.bounds() }
            // Each '3' is one stroke; consecutive strokes' boxes stand apart by at least the strict gap.
            for (i in 1 until boxes.size) {
                val gap = (boxes[i]?.left ?: 0f) - (boxes[i - 1]?.right ?: 0f)
                // The wobble may move each side by its bound after placement; the units still never touch.
                assertTrue(
                    gap >= GlyphAssembly.STRICT_GAP - 2 * Variation.Table.wobble,
                    "seed $seed: units $i-1 and $i are $gap apart",
                )
            }
        }
    }

    @Test
    fun aDigitKnownOnlyInsideAnUnvouchedWordIsMissingForTheStrictRoute() {
        val index = GlyphIndex.build(listOf(HandFixtures.sample("33", cut = false)), LabelBook())
        val loose = GlyphAssembly(index).word("33", Variation.None, Random(1), HashSet())
        assertIs<AssembledWord.Written>(loose, "assembly would use the whole word")
        val strict = GlyphAssembly(index).word("33", Variation.None, Random(1), HashSet(), strict = true)
        assertEquals(listOf("3"), assertIs<AssembledWord.Missing>(strict).letters)
    }

    @Test
    fun aWordTheUserVouchedForIsUsableWholeOnTheStrictRoute() {
        val sample = HandFixtures.sample("33", cut = false)
        val index = GlyphIndex.build(listOf(sample), LabelBook(confirmed = setOf(sample.sourceKey)))
        val strict = GlyphAssembly(index).word("33", Variation.None, Random(1), HashSet(), strict = true)
        assertEquals(UnitKind.WORD, assertIs<AssembledWord.Written>(strict).units.single().kind)
    }

    /** H5's maths sheet drew `NO2` as `N·O2`: a full stop cut with the `N` is not part of a verified `N`. */
    @Test
    fun aCutLetterCarryingAStrayDotIsNotAUnit() {
        val dotted =
            HandFixtures.LETTERS +
                ('N' to HandFixtures.LETTERS.getValue('N') + listOf(listOf(1.3f to 0f, 1.33f to 0.02f)))
        val stray = GlyphIndex.build(listOf(HandFixtures.sample("Nn", shapes = dotted)), LabelBook())
        assertTrue(stray.variants("N").none { it.kind == UnitKind.LETTER }, "the dotted N is set aside")
        assertTrue(stray.variants("n").any { it.kind == UnitKind.LETTER }, "its neighbour is not")
        val clean = GlyphIndex.build(listOf(HandFixtures.sample("Nn")), LabelBook())
        assertEquals(1, clean.variants("N").count { it.kind == UnitKind.LETTER })
    }

    @Test
    fun withTheNetworkPrimaryItsRungsComeFirst() = runTest {
        val network = Recording(" nu") { zigzag(it) }
        val reader = ScriptedReader(fallback = { listOf("nun") })
        val phrase = HandwritingSynthesizer(
            ReliabilityGate(reader),
            NeuralBackend(network, PenDynamics.Unmeasured),
            Route(Backend.NEURAL),
        ).write(request("nun"), numbersIndex)

        val nun = assertIs<WordOutcome.Written>(phrase.words.single())
        assertEquals(GeneratedInk.NEURAL, nun.backend, "assembly could write it, and the network went first")
        assertEquals(1, nun.attempts)
    }

    @Test
    fun aRoutedCharacterSendsTheWordToAssemblyWhenItCanWriteIt() = runTest {
        val network = Recording(" nu") { zigzag(it) }
        val reader = ScriptedReader(fallback = { listOf("nun") })
        val phrase = HandwritingSynthesizer(
            ReliabilityGate(reader),
            NeuralBackend(network, PenDynamics.Unmeasured),
            Route(Backend.NEURAL, avoidNeural = setOf('u')),
        ).write(request("nun"), numbersIndex)

        assertEquals(GeneratedInk.ASSEMBLY, assertIs<WordOutcome.Written>(phrase.words.single()).backend)
        assertTrue(network.asked.isEmpty(), "the network was not asked")
    }

    @Test
    fun whenTheNetworkFailsAWordItWentFirstOnAssemblyWritesIt() = runTest {
        val network = Recording(" nu") { zigzag(it) }
        // Three neural rounds read as nonsense, then assembly's word reads.
        val reader = ScriptedReader(List(3) { listOf(listOf("???")) }, fallback = { listOf("nun") })
        val phrase = HandwritingSynthesizer(
            ReliabilityGate(reader),
            NeuralBackend(network, PenDynamics.Unmeasured),
            Route(Backend.NEURAL),
        ).write(request("nun"), numbersIndex)

        val nun = assertIs<WordOutcome.Written>(phrase.words.single())
        assertEquals(GeneratedInk.ASSEMBLY, nun.backend)
        assertEquals(NeuralBackend.ATTEMPTS + 1, nun.attempts)
    }

    private fun zigzag(word: String): NetworkWord {
        val x = FloatArray(POINTS) { it * 0.1f }
        val y = FloatArray(POINTS) { (it % 2).toFloat() }
        return NetworkWord(word, x, y, intArrayOf(POINTS), POINTS)
    }

    /** Records every word it is asked for. */
    private class Recording(override val alphabet: String, private val ink: (String) -> NetworkWord?) :
        SynthesisNetwork {
        val asked = ArrayList<String>()
        override val modelId = "0000beef"
        override val pointsPerChar = 14f

        override fun prime(prime: Prime?): Primed = Primed.NONE

        override fun write(
            words: List<String>,
            primed: Primed,
            bias: Float,
            random: Random,
            batch: Int,
        ): List<NetworkWord?> {
            asked += words
            return words.map(ink)
        }

        override fun align(text: String, trajectory: PenTrajectory): Alignment? = null
    }

    private companion object {
        const val POINTS = 20
    }
}
