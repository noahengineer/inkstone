package dev.inkstone.core.handwriting

import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.RecognitionUnavailable
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D9 and §8.2 — every word is read back before it is drawn, and a word that cannot be written is
 * refused with a reason, never drawn.
 *
 * ⚠️ The recogniser is [ScriptedReader], which answers from a script without looking at the ink. These
 * tests prove the synthesizer's bookkeeping — retries, refusals, layout — and nothing about whether
 * assembled ink reads. That is the emulator's question, against the real ML Kit (§11).
 */
class HandwritingSynthesizerTest {

    private val index = GlyphIndex.build(
        listOf(HandFixtures.sample("nun"), HandFixtures.sample("mix"), HandFixtures.sample("lul")),
        LabelBook(),
    )
    private val style =
        WriteStyle(xHeightPt = 6f, slantRad = 0.1f, angleRad = 0f, wrapWidthPt = 400f, linePitchPt = 20f)

    private fun request(text: String, seed: Long = 1L, style: WriteStyle = this.style, name: String? = null) =
        WriteRequest(text, style, seed, accountName = name)

    @Test
    fun `every word that reads as itself is written`() = runTest {
        val reader = ScriptedReader(listOf(listOf(listOf("nun"), listOf("mix"))))
        val phrase = HandwritingSynthesizer(ReliabilityGate(reader)).write(request("nun mix"), index)

        assertTrue(phrase.complete)
        assertEquals(1, reader.calls.size, "one recogniser call for the whole phrase")
        assertEquals("nun", reader.calls.single()[1].preContext)
        assertEquals(listOf(1, 1), phrase.words.map { (it as WordOutcome.Written).attempts })
    }

    @Test
    fun `a refusing recogniser blocks the drop and names the word`() = runTest {
        val reader = ScriptedReader(fallback = { listOf("???", "nmn") })
        val phrase = HandwritingSynthesizer(ReliabilityGate(reader)).write(request("nun"), index)

        assertFalse(phrase.complete)
        val refused = phrase.refusals.single()
        assertEquals("nun", refused.text)
        assertEquals(Refusal.Illegible("???"), refused.reason)
        // Three varied attempts and one with no variation, then the refusal.
        assertEquals(HandwritingSynthesizer.VARIED_ATTEMPTS + 1, reader.calls.size)
        assertTrue(phrase.strokes(PagePoint(0f, 0f)).isEmpty(), "a refused word draws nothing")
    }

    @Test
    fun `a word that fails is tried again and only it is re-read`() = runTest {
        val reader = ScriptedReader(
            listOf(
                listOf(listOf("nun"), listOf("nix")),
                listOf(listOf("mix")),
            ),
        )
        val phrase = HandwritingSynthesizer(ReliabilityGate(reader)).write(request("nun mix"), index)

        assertTrue(phrase.complete)
        assertEquals(listOf(2, 1), reader.calls.map { it.size })
        assertEquals(2, (phrase.words[1] as WordOutcome.Written).attempts)
    }

    @Test
    fun `a letter never written refuses its word by name without asking the recogniser`() = runTest {
        val reader = ScriptedReader(fallback = { listOf("nun") })
        val phrase = HandwritingSynthesizer(ReliabilityGate(reader)).write(request("nun zen"), index)

        val refused = phrase.refusals.single()
        assertEquals("zen", refused.text)
        assertEquals(Refusal.MissingLetters(listOf("z", "e")), refused.reason)
        assertTrue(phrase.words[0] is WordOutcome.Written)
    }

    @Test
    fun `no recogniser means nothing is written`() = runTest {
        val reader = ScriptedReader(unavailable = RecognitionUnavailable.MODEL_NOT_DOWNLOADED)
        val phrase = HandwritingSynthesizer(ReliabilityGate(reader)).write(request("nun mix"), index)

        assertTrue(phrase.words.all { it is WordOutcome.Refused })
        assertEquals(Refusal.Unavailable(RecognitionUnavailable.MODEL_NOT_DOWNLOADED), phrase.refusals.first().reason)
    }

    @Test
    fun `nothing learned means nothing is written`() = runTest {
        val reader = ScriptedReader(fallback = { listOf("nun") })
        val phrase = HandwritingSynthesizer(ReliabilityGate(reader)).write(request("nun"), GlyphIndex.Empty)

        assertEquals(Refusal.NothingLearned, phrase.refusals.single().reason)
        assertTrue(reader.calls.isEmpty())
    }

    @Test
    fun `the account holder's name is refused as a signature`() = runTest {
        val reader = ScriptedReader(fallback = { listOf("x") })
        val phrase = HandwritingSynthesizer(ReliabilityGate(reader)).write(request("Nun Lul", name = "Nun Lul"), index)

        assertTrue(phrase.refusals.all { it.reason == Refusal.Signature })
        assertTrue(reader.calls.isEmpty())
    }

    @Test
    fun `the same phrase twice is two different phrases`() = runTest {
        val gate = ReliabilityGate(ScriptedReader(fallback = { listOf("nun", "mix") }))
        val synthesizer = HandwritingSynthesizer(gate)
        val drops = (1L..10L).map { seed ->
            synthesizer.write(request("nun mix", seed = seed), index).strokes(PagePoint(0f, 0f))
        }

        for (i in drops.indices) {
            for (j in i + 1 until drops.size) assertNotEquals(drops[i], drops[j], "drops $i and $j")
        }
    }

    @Test
    fun `words wrap at the wrap width and at typed line breaks`() = runTest {
        val gate = ReliabilityGate(ScriptedReader(fallback = { listOf("nun", "mix", "lul") }))
        val narrow = style.copy(wrapWidthPt = 30f)
        val phrase = HandwritingSynthesizer(gate).write(request("nun mix\nlul", style = narrow), index)

        assertEquals(listOf(0, 1, 2), phrase.placed.map { it.line })
        val wide = HandwritingSynthesizer(gate).write(request("nun mix\nlul"), index)
        assertEquals(listOf(0, 0, 1), wide.placed.map { it.line })
    }

    @Test
    fun `placing a phrase only moves what the gate read`() = runTest {
        val gate = ReliabilityGate(ScriptedReader(fallback = { listOf("nun") }))
        val phrase = HandwritingSynthesizer(gate).write(request("nun"), index)
        val word = assertIs<WordOutcome.Written>(phrase.words.single())
        val origin = PagePoint(100f, 200f)
        val at = phrase.pointOf(origin, phrase.placed.single())
        val strokes = phrase.strokes(origin)

        assertEquals(word.ink.strokeCount, strokes.size)
        val first = strokes.first()
        for (i in 0 until first.pointCount) {
            assertEquals(word.ink.x[i] + at.x, first.x[i], 1e-3f)
            assertEquals(word.ink.y[i] + at.y, first.y[i], 1e-3f)
        }
    }

    @Test
    fun `the first line's left baseline point is the origin`() = runTest {
        val gate = ReliabilityGate(ScriptedReader(fallback = { listOf("nun") }))
        val phrase = HandwritingSynthesizer(gate).write(request("nun", style = style.copy(neatness = 1f)), index)
        val box = assertNotNull(phrase.boundsAt(PagePoint(50f, 80f)))

        // An n sits on the baseline and reaches an x-height above it; the drift is a hundredth of that.
        assertEquals(80f, box.bottom, 0.6f)
        assertEquals(80f - 6f, box.top, 0.6f)
        assertEquals(50f, box.left, 0.6f)
    }

    /**
     * §21.4 through the whole synthesizer: a number whose ink reads first as a different number is refused
     * by name and not drawn, though its own text is among the candidates — while a word beside it, read
     * the same way, is written.
     */
    @Test
    fun `a number read first as another number is refused and a word read second is written`() = runTest {
        val hand = GlyphIndex.build(listOf(HandFixtures.sample("33"), HandFixtures.sample("nun")), LabelBook())
        val reader = ScriptedReader(listOf(listOf(listOf("nan", "nun"), listOf("38", "33"))), fallback = {
            listOf("38", "33")
        })
        val phrase = HandwritingSynthesizer(ReliabilityGate(reader)).write(request("nun 33"), hand)

        val nun = assertIs<WordOutcome.Written>(phrase.words[0])
        assertEquals(1, nun.attempts)
        val refused = phrase.refusals.single()
        assertEquals("33", refused.text)
        assertEquals(Refusal.Illegible("38"), refused.reason, "the refusal names what it would have drawn")
        assertEquals(nun.ink.strokeCount, phrase.strokes(PagePoint(0f, 0f)).size, "only nun is drawn")
    }

    /**
     * §21.4.2: readings with no digit are skipped, so a number read first as letters — the user's lone `1`
     * as `l`, their `5` as `s` — is written first time, while one read first as another number is not.
     */
    @Test
    fun `a number read first as letters is written first time`() = runTest {
        val hand = GlyphIndex.build(listOf(HandFixtures.sample("33")), LabelBook())
        val reader = ScriptedReader(fallback = { listOf("BB", "zz", "33") })
        val phrase = HandwritingSynthesizer(ReliabilityGate(reader)).write(request("33"), hand)

        assertEquals(1, assertIs<WordOutcome.Written>(phrase.words.single()).attempts)
        assertEquals(1, reader.calls.size)
    }

    @Test
    fun `the gate's rules`() {
        assertTrue(GateRule.EXACT.passes("sin", listOf("si n", "sin")))
        assertFalse(GateRule.EXACT.passes("CaCO3", listOf("Ca CO3", "CaCO3")), "a digit: its first reading with one")
        assertFalse(GateRule.EXACT.passes("CaCO3", listOf("Ca CO3")))
        assertTrue(GateRule.SPACING.passes("CaCO3", listOf("Ca CO3")))
        assertTrue(GateRule.CASE_ALIKE.passes("cOs", listOf("COS")))
        // A different letter is a different word, whatever the case rules say.
        assertFalse(GateRule.CASE_ALIKE.passes("bat", listOf("but", "Bat")))
        assertFalse(GateRule.DEFAULT.passes("milk", emptyList()))
    }
}
