package dev.inkstone.core.handwriting

import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.InkReader
import dev.inkstone.core.model.InkReading
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.ReadCandidate
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * docs/HANDWRITING-MATHS.md — the *Maths* switch through the real synthesizer: the same ladder, the
 * same gate, the same layout code the app calls.
 *
 * ⚠️ The reader here answers every ink with every run's text, so every run passes the gate: these tests
 * prove what is written where and with which tag, and nothing about whether ML Kit can read a run. That
 * is the emulator's question (§5).
 */
class MathsWritingTest {

    private val index = GlyphIndex.build(
        listOf(
            HandFixtures.sample("nun"),
            HandFixtures.sample("mix"),
            HandFixtures.sample("lul"),
            HandFixtures.sample("Nix3"),
        ),
        LabelBook(),
    )
    private val style =
        WriteStyle(xHeightPt = 6f, slantRad = 0.1f, angleRad = 0f, wrapWidthPt = 400f, linePitchPt = 20f)

    /** Reads every ink as any of [texts]: every run passes, so the layout is what is under test. */
    private fun trusting(vararg texts: String): InkReader {
        val candidates = texts.map { ReadCandidate(it, null) }
        return InkReader { inks, _ -> InkReading.Read(inks.map { candidates }) }
    }

    // A run with a digit is judged on its first reading that holds one (docs/HANDWRITING-SYNTHESIS.md §21.4),
    // so the one digit run these tests write, `3`, comes before `Nix3`; every other run may be any candidate.
    private val reader = trusting("3", "x", "n", "u", "m", "i", "l", "nun", "mix", "lul", "Nix3", "x+", "nix")

    private suspend fun write(text: String, maths: Boolean = true, style: WriteStyle = this.style) =
        HandwritingSynthesizer(
            ReliabilityGate(reader),
        ).write(WriteRequest(text, style, seed = 7L, maths = maths), index)

    @Test
    fun `a fraction is written as one word of maths`() = runTest {
        val phrase = write("x/n")
        assertTrue(phrase.complete, "written: ${phrase.refusals}")
        val word = assertIs<WordOutcome.Written>(phrase.words.single())
        assertTrue(word.isMaths)
        val tags = phrase.taggedStrokes(PagePoint(0f, 0f), "g")
        assertEquals(word.ink.strokeCount, tags.size, "one tag per stroke")
        assertTrue(tags.all { it.second.group == "g" }, "one phrase: a tap on any stroke selects all of it")
        assertTrue(tags.any { it.second.backend == GeneratedInk.ASSEMBLY }, "the bar is drawn and marked")
    }

    @Test
    fun `roots and scripts and bars are written`() = runTest {
        for (text in listOf("√x", "∛x", "x^3", "|x|", "x_3", "(x/n)^3")) {
            val phrase = write(text)
            assertTrue(phrase.complete, "$text: ${phrase.refusals}")
            assertTrue(assertIs<WordOutcome.Written>(phrase.words.single()).isMaths, text)
        }
    }

    @Test
    fun `with the switch off the same text is one run of letters`() = runTest {
        // No `/` has ever been written, and there is no network: written plainly, it cannot be.
        val phrase = write("x/n", maths = false)
        assertFalse(phrase.complete)
        assertEquals(Refusal.MissingLetters(listOf("/")), phrase.refusals.single().reason)
    }

    @Test
    fun `plain words are written exactly as they are with the switch off`() = runTest {
        val on = write("nun mix", maths = true)
        val off = write("nun mix", maths = false)
        assertEquals(off.strokes(PagePoint(0f, 0f)), on.strokes(PagePoint(0f, 0f)))
    }

    @Test
    fun `an expression with a letter never written is refused and names the letter`() = runTest {
        val phrase = write("x/q")
        val refused = phrase.refusals.single()
        assertEquals("x/q", refused.text)
        assertEquals(Refusal.MissingLetters(listOf("q")), refused.reason)
        assertTrue(phrase.strokes(PagePoint(0f, 0f)).isEmpty(), "and draws nothing")
    }

    @Test
    fun `a line of maths too tall for the pitch pushes the next line down a whole pitch`() = runTest {
        // A fraction reaches about 1.6 x-heights up and 0.6 down: on a 1.5 x-height pitch it cannot fit.
        val tight = style.copy(linePitchPt = style.xHeightPt * 1.5f)
        val phrase = write("x/n\nnun", style = tight)
        assertTrue(phrase.complete)
        val lines = phrase.placed.map { it.line }
        assertEquals(0, lines[0])
        assertTrue(lines[1] >= 2, "the second line skips a ruled line: $lines")
        // Plain writing on the same pitch is not moved.
        assertEquals(listOf(0, 1), write("nun\nmix", style = tight).placed.map { it.line })
    }
}
