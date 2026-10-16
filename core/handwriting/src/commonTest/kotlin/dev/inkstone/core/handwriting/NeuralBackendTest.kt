package dev.inkstone.core.handwriting

import dev.inkstone.core.handwriting.neural.Alignment
import dev.inkstone.core.handwriting.neural.KotlinSynthesisNetwork
import dev.inkstone.core.handwriting.neural.LicencePolicy
import dev.inkstone.core.handwriting.neural.ModelLoad
import dev.inkstone.core.handwriting.neural.NetState
import dev.inkstone.core.handwriting.neural.NetworkWord
import dev.inkstone.core.handwriting.neural.Prime
import dev.inkstone.core.handwriting.neural.Primed
import dev.inkstone.core.handwriting.neural.SynthesisNetwork
import dev.inkstone.core.handwriting.neural.TinyModelFixture
import dev.inkstone.core.handwriting.neural.WeightsFile
import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PenTrajectory
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/HANDWRITING-SYNTHESIS.md H4 — the neural backend writes what assembly cannot, every word it
 * writes is read by the same gate, its strokes say which model wrote them, and a corrupt model turns
 * it off while assembly carries on.
 *
 * ⚠️ The network here is [TinyModelFixture]'s: real weights through the real Kotlin network, but
 * random, so its ink is scribble. The recogniser is [ScriptedReader], which passes or fails a word by
 * script without looking. So these tests prove the *routing* — which backend gets which word, the
 * ladder, the tags, the fault — and nothing about whether neural ink reads. That is the device's
 * question, against the shipped model and real ML Kit.
 */
class NeuralBackendTest {

    /** The fixture hand writes n, u, m, l, i, x; the tiny model's alphabet is " abcd". */
    private val index = GlyphIndex.build(listOf(HandFixtures.sample("nun"), HandFixtures.sample("mix")), LabelBook())
    private val style =
        WriteStyle(xHeightPt = 6f, slantRad = 0.1f, angleRad = 0f, wrapWidthPt = 400f, linePitchPt = 20f)
    private val weights = assertIs<ModelLoad.Loaded>(
        WeightsFile.read(TinyModelFixture.bytes, LicencePolicy.RELEASE),
    ).weights
    private val dynamics = PenDynamics.fit(index.wholeWords().map { it.trajectory }, index.stats.penUpMs)
    private val backend = NeuralBackend(KotlinSynthesisNetwork(weights), dynamics)

    private fun request(text: String, seed: Long = 3L) = WriteRequest(text, style, seed)

    @Test
    fun aWordWithALetterNeverWrittenIsWrittenByTheNetworkAndTaggedWithItsModel() = runTest {
        val reader = ScriptedReader(listOf(listOf(listOf("nun"), listOf("bad"))))
        val phrase = HandwritingSynthesizer(ReliabilityGate(reader), backend).write(request("nun bad"), index)

        assertTrue(phrase.complete, "both words written: ${phrase.refusals}")
        val (nun, bad) = phrase.words.map { assertIs<WordOutcome.Written>(it) }
        assertEquals(GeneratedInk.ASSEMBLY, nun.backend)
        assertEquals(GeneratedInk.NO_MODEL, nun.model)
        assertEquals(GeneratedInk.NEURAL, bad.backend)
        assertEquals(TinyModelFixture.SHA256.take(8), bad.model)
        assertEquals(1, reader.calls.size, "one gate call for the round, both backends in it")
        val tags = phrase.taggedStrokes(PagePoint(10f, 20f), "group").map { it.second }
        assertEquals(phrase.strokes(PagePoint(10f, 20f)).size, tags.size)
        assertEquals(
            nun.ink.strokeCount + bad.ink.strokeCount,
            tags.size,
            "every stroke of both words, tagged",
        )
        assertTrue(tags.take(nun.ink.strokeCount).all { it.backend == GeneratedInk.ASSEMBLY })
        assertTrue(tags.drop(nun.ink.strokeCount).all { it.backend == GeneratedInk.NEURAL && it.model == bad.model })
        assertTrue(tags.all { it.group == "group" })
    }

    /** §8.2: a neural word the gate refuses is tried [NeuralBackend.ATTEMPTS] times, then refused. */
    @Test
    fun theGateJudgesANeuralWordAndAFailingOneIsRefusedNotDrawn() = runTest {
        val reader = ScriptedReader(fallback = { listOf("???") })
        val phrase = HandwritingSynthesizer(ReliabilityGate(reader), backend).write(request("bad"), index)

        assertFalse(phrase.complete)
        assertEquals(Refusal.MissingLetters(listOf("b", "a", "d")), phrase.refusals.single().reason)
        assertTrue(reader.calls.size in 1..NeuralBackend.ATTEMPTS, "${reader.calls.size} gate calls")
        assertTrue(phrase.strokes(PagePoint(0f, 0f)).isEmpty(), "a refused word draws nothing")
    }

    /** ⭐ H4's fault test, core half: a corrupted model file, and the phrase is still written by assembly. */
    @Test
    fun aCorruptModelTurnsTheBackendOffAndAssemblyCarriesOn() = runTest {
        val corrupt = TinyModelFixture.bytes.copyOf().also {
            it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte()
        }
        val load = WeightsFile.read(corrupt, LicencePolicy.RELEASE)
        val neural = backendOf(load)
        assertEquals(ModelLoad.Reason.CORRUPT, assertIs<ModelLoad.Refused>(load).reason)
        assertNull(neural)

        val reader = ScriptedReader(listOf(listOf(listOf("nun"), listOf("mix"))))
        val written = HandwritingSynthesizer(ReliabilityGate(reader), neural).write(request("nun mix"), index)
        assertTrue(written.complete)
        assertTrue(written.words.all { (it as WordOutcome.Written).backend == GeneratedInk.ASSEMBLY })

        val missing = HandwritingSynthesizer(ReliabilityGate(reader), neural).write(request("bad"), index)
        assertEquals(Refusal.MissingLetters(listOf("b", "a", "d")), missing.refusals.single().reason)
    }

    @Test
    fun aLetterOutsideTheModelsAlphabetStaysWithAssemblysRefusal() = runTest {
        val reader = ScriptedReader(fallback = { listOf("zed") })
        val phrase = HandwritingSynthesizer(ReliabilityGate(reader), backend).write(request("zed"), index)

        assertEquals(Refusal.MissingLetters(listOf("z", "e", "d")), phrase.refusals.single().reason)
        assertTrue(reader.calls.isEmpty(), "nothing was written, so nothing was read")
    }

    @Test
    fun neatnessIsGravesBiasRaisedOneStepPerAttempt() {
        assertEquals(0f, NeuralBackend.biasFor(0f, 1))
        assertEquals(1f, NeuralBackend.biasFor(0.5f, 1))
        assertEquals(2f, NeuralBackend.biasFor(1f, 1))
        assertEquals(1.5f, NeuralBackend.biasFor(0.5f, 2))
        assertEquals(2f, NeuralBackend.biasFor(0.5f, 3))
        assertEquals(2f, NeuralBackend.biasFor(7f, 1), "clamped")
    }

    /** §7.4 and D15: the prime is the user's own words — this page's first — laid out as one line. */
    @Test
    fun thePrimeIsTheUsersOwnWordsThisPageFirst() {
        val recorder = RecordingNetwork("numilx ")
        val here = HandFixtures.sample("lul", noteId = "note-1", pageId = "page-1")
        val elsewhere = listOf("nun", "mix", "lix").map {
            HandFixtures.sample(it, noteId = "note-2", pageId = "page-9")
        }
        val idx = GlyphIndex.build(elsewhere + here, LabelBook())
        val primed = NeuralBackend(recorder, PenDynamics.Unmeasured)
            .prime(idx, SourcePreference(noteId = "note-1", pageId = "page-1"), Random(1))

        assertTrue(primed.isPrimed)
        val prime = recorder.primes.single()
        assertEquals("lul", prime.text.split(' ').first(), "this page's word first")
        assertEquals(3, prime.text.split(' ').size)
        val xs = prime.ink.x
        assertTrue(xs.max() > 3f, "the words are laid side by side, not stacked: ${xs.max()}")
    }

    /** §7.4: the prime is read once per drop, however many words and rounds need the network. */
    @Test
    fun thePrimeIsReadOncePerDropAcrossWordsAndRounds() = runTest {
        val network = RecordingNetwork(" abdnumilx")
        // `nun` is assembly's and passes; the network fails `bad` and `dab` every time (it returns nothing).
        val reader = ScriptedReader(listOf(listOf(listOf("nun"))))
        val phrase = HandwritingSynthesizer(ReliabilityGate(reader), NeuralBackend(network, PenDynamics.Unmeasured))
            .write(request("nun bad dab"), index)
        assertEquals(1, network.primes.size, "one prime for the whole drop")
        assertEquals(network.writes, NeuralBackend.ATTEMPTS, "the network's rungs, one batched call per round")
        assertEquals(2, phrase.refusals.count { it.reason is Refusal.MissingLetters })
    }

    /**
     * §8.2's second route: a word assembly *can* build, but that the gate refuses through every one of
     * assembly's rungs, goes to the network afterwards — and the network's word is what lands.
     */
    @Test
    fun aWordAssemblyCannotMakeLegibleIsWrittenByTheNetworkAfterAssemblysRungs() = runTest {
        val network = RecordingNetwork(" numilx") { word -> zigzag(word) }
        val assemblyRounds = HandwritingSynthesizer.VARIED_ATTEMPTS + 1
        val reader = ScriptedReader(List(assemblyRounds) { listOf(listOf("???")) } + listOf(listOf(listOf("nun"))))
        val phrase = HandwritingSynthesizer(ReliabilityGate(reader), NeuralBackend(network, PenDynamics.Unmeasured))
            .write(request("nun"), index)

        assertTrue(phrase.complete, "${phrase.refusals}")
        val nun = assertIs<WordOutcome.Written>(phrase.words.single())
        assertEquals(GeneratedInk.NEURAL, nun.backend)
        assertEquals(network.modelId, nun.model)
        assertEquals(assemblyRounds + 1, reader.calls.size, "every assembly rung first, then one neural round")
        assertEquals(1, network.writes, "the network was not asked until assembly had run out of rungs")
    }

    @Test
    fun noWordsTheModelCanReadMeansUnprimed() {
        val primed = backend.prime(index, SourcePreference(), Random(1))
        assertFalse(primed.isPrimed, "the fixture hand's words use letters outside the tiny alphabet")
    }

    /** The app's decision, `HandwritingWriter.network`'s: a backend for a loaded model, none otherwise. */
    private fun backendOf(load: ModelLoad): NeuralBackend? = when (load) {
        is ModelLoad.Loaded -> NeuralBackend(KotlinSynthesisNetwork(load.weights), dynamics)
        is ModelLoad.Refused -> null
    }

    /** One stroke of [ZIGZAG_POINTS] points: valid ink the scripted gate never looks at. */
    private fun zigzag(word: String): NetworkWord {
        val x = FloatArray(ZIGZAG_POINTS) { it * 0.1f }
        val y = FloatArray(ZIGZAG_POINTS) { (it % 2).toFloat() }
        return NetworkWord(word, x, y, intArrayOf(ZIGZAG_POINTS), ZIGZAG_POINTS)
    }

    /**
     * A network that records what it was asked, for [thePrimeIsTheUsersOwnWordsThisPageFirst]; it
     * writes [ink]'s word, or fails every word by default.
     */
    private class RecordingNetwork(
        override val alphabet: String,
        private val ink: (String) -> NetworkWord? = { null },
    ) : SynthesisNetwork {
        val primes = ArrayList<Prime>()
        override val modelId = "00000000"
        override val pointsPerChar = 14f

        var writes = 0

        override fun prime(prime: Prime?): Primed {
            prime?.let { primes += it }
            if (prime == null) return Primed.NONE
            val state = NetState(arrayOf(FloatArray(1)), arrayOf(FloatArray(1)), FloatArray(1), FloatArray(1))
            return Primed(state, prime.text)
        }

        override fun write(
            words: List<String>,
            primed: Primed,
            bias: Float,
            random: Random,
            batch: Int,
        ): List<NetworkWord?> {
            writes++
            return words.map(ink)
        }

        override fun align(text: String, trajectory: PenTrajectory): Alignment? = null
    }

    private companion object {
        const val ZIGZAG_POINTS = 20
    }
}
