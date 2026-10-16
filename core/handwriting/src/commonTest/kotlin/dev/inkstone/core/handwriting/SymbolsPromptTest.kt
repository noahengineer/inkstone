package dev.inkstone.core.handwriting

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.IdFactory
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * docs/HANDWRITING-MATHS.md §2.4 — the quick start's maths symbols prompt, through the real quick start,
 * glyph index and synthesizer: what the user writes for `± × ÷ √ π ≤ ≥ ≠ ≈ θ Δ °` becomes their own
 * vouched copies, and a token holding one is then written from them alone, never asked of a recogniser
 * (none can read them, §20.6.4) and never of the network.
 *
 * The symbols are drawn here as plain strokes, a hand's worth of each: this is about what the quick
 * start and the strict route do with them, not about how anyone's `≈` looks.
 */
class SymbolsPromptTest {

    private val ids = IdFactory(object : Clock {
        override fun nowEpochMillis(): Long = 1_760_000_000_000L
    })

    private val prompt = QuickStart.PROMPTS.last()

    private fun circle(cx: Float, cy: Float, rx: Float, ry: Float): List<Pair<Float, Float>> = (0..STEPS).map { k ->
        val a = (2 * PI * k / STEPS).toFloat()
        (cx + rx * cos(a)) to (cy + ry * sin(a))
    }

    /** Each symbol in the normalised frame: x-heights, y up, ink from x = 0. */
    private val shapes: Map<Char, List<List<Pair<Float, Float>>>> = mapOf(
        '±' to listOf(
            listOf(0f to 0.7f, 0.6f to 0.7f),
            // Not perfectly upright: a zero-width stroke cannot overlap anything, so GlyphClusters gives
            // it a cluster of its own, and no stylus draws one. The first draft of this fixture did.
            listOf(0.28f to 1f, 0.32f to 0.4f),
            listOf(0f to 0.15f, 0.6f to 0.15f),
        ),
        '×' to listOf(listOf(0f to 0.8f, 0.6f to 0.1f), listOf(0.6f to 0.8f, 0f to 0.1f)),
        '÷' to listOf(
            listOf(0.28f to 0.85f, 0.32f to 0.88f),
            listOf(0f to 0.45f, 0.6f to 0.45f),
            listOf(0.28f to 0.05f, 0.32f to 0.08f),
        ),
        '√' to listOf(listOf(0f to 0.5f, 0.1f to 0.55f, 0.25f to 0f, 0.45f to 1.2f, 0.8f to 1.2f)),
        'π' to listOf(
            listOf(0f to 0.9f, 0.7f to 0.9f),
            listOf(0.2f to 0.9f, 0.15f to 0f),
            listOf(0.5f to 0.9f, 0.55f to 0f),
        ),
        '≤' to listOf(listOf(0.6f to 1f, 0f to 0.65f, 0.6f to 0.3f), listOf(0f to 0.05f, 0.6f to 0.05f)),
        '≥' to listOf(listOf(0f to 1f, 0.6f to 0.65f, 0f to 0.3f), listOf(0f to 0.05f, 0.6f to 0.05f)),
        '≠' to listOf(
            listOf(0f to 0.6f, 0.6f to 0.6f),
            listOf(0f to 0.3f, 0.6f to 0.3f),
            listOf(0.45f to 0.95f, 0.15f to -0.05f),
        ),
        '≈' to listOf(
            listOf(0f to 0.6f, 0.15f to 0.7f, 0.3f to 0.6f, 0.45f to 0.5f, 0.6f to 0.6f),
            listOf(0f to 0.3f, 0.15f to 0.4f, 0.3f to 0.3f, 0.45f to 0.2f, 0.6f to 0.3f),
        ),
        'θ' to listOf(circle(0.3f, 0.75f, 0.3f, 0.75f), listOf(0.05f to 0.75f, 0.55f to 0.75f)),
        'Δ' to listOf(listOf(0.35f to 1.4f, 0f to 0f, 0.7f to 0f, 0.35f to 1.38f)),
        '°' to listOf(circle(0.15f, 1.3f, 0.12f, 0.12f)),
    )

    /** The prompt written left to right on one line, two x-heights between symbols. */
    internal fun probeLearned() = learned()

    private fun writtenPrompt() = run {
        val strokes = ArrayList<List<Pair<Float, Float>>>()
        var left = 0f
        for (symbol in prompt.split(' ')) {
            val shape = shapes.getValue(symbol.single())
            val shift = left
            strokes += shape.map { stroke -> stroke.map { (u, v) -> (u + shift) to v } }
            left += shape.flatten().maxOf { it.first } + 2f
        }
        HandFixtures.pageTrajectoryOf(strokes, NormalFrame(30f, 200f, 0f, 0f, 8f))
    }

    private fun learned(): List<WordSample> {
        val index = QuickStart.PROMPTS.lastIndex
        val result = QuickStart.samples(prompt, index, writtenPrompt(), HandFixtures.PEN, null, ids, 20_000L)
        return assertIs<QuickStartResult.Learned>(result).samples
    }

    /** The symbols prompt and a hand that has written `x`, `3`, `N`, `i`. */
    private fun index() = GlyphIndex.build(learned() + HandFixtures.sample("Nix3"), LabelBook())

    @Test
    fun `the prompt is the users list and comes last so recorded prompts keep their places`() {
        assertEquals("± × ÷ √ π ≤ ≥ ≠ ≈ θ Δ °", prompt)
        assertTrue(prompt.split(' ').all { it.single() in GateCalibration.UNREADABLE }, "each one is unreadable")
    }

    @Test
    fun `each symbol becomes a vouched copy of the users own`() {
        val samples = learned()
        assertEquals(prompt.split(' '), samples.map { it.label })
        assertTrue(samples.all { it.labelSource == LabelSource.CONFIRMED })
        val index = index()
        for (symbol in prompt.split(' ')) {
            assertTrue(index.variants(symbol).any { it.vouched }, "'$symbol' has a vouched copy")
        }
    }

    @Test
    fun `a token with a symbol is written from the users copies and never read back`() = runTest {
        val reader = ScriptedReader(fallback = { listOf("x≤3") })
        val style = WriteStyle(xHeightPt = 6f, slantRad = 0f, angleRad = 0f, wrapWidthPt = 400f, linePitchPt = 20f)
        val phrase = HandwritingSynthesizer(ReliabilityGate(reader)).write(WriteRequest("x≤3 ±", style, 3L), index())

        assertTrue(phrase.complete, "written: ${phrase.refusals}")
        assertTrue(phrase.words.all { (it as WordOutcome.Written).readBack.not() }, "built, not read")
        assertTrue(reader.calls.isEmpty(), "no recogniser was asked: none can read these")
    }

    @Test
    fun `without the prompt a symbol is refused by name and the network is never asked`() = runTest {
        val reader = ScriptedReader(fallback = { listOf("x≤3") })
        val style = WriteStyle(xHeightPt = 6f, slantRad = 0f, angleRad = 0f, wrapWidthPt = 400f, linePitchPt = 20f)
        val bare = GlyphIndex.build(listOf(HandFixtures.sample("Nix3")), LabelBook())
        val phrase = HandwritingSynthesizer(ReliabilityGate(reader)).write(WriteRequest("x≤3", style, 3L), bare)

        assertEquals(Refusal.MissingLetters(listOf("≤")), phrase.refusals.single().reason)
        assertTrue(reader.calls.isEmpty())
    }

    @Test
    fun `the users own root sign is what the maths layout draws`() {
        val glyph = MathWords.glyphsOf(index(), kotlin.random.Random(1))('√')
        assertNotNull(glyph, "the prompt's √ is there for the radical")
    }

    @Test
    fun `a token with an unreadable character is never judged by the recogniser`() {
        val judged = GateCalibration.UNCALIBRATED
        assertFalse(judged.judgeable("x≤3"))
        assertFalse(judged.judgeable("π"))
        assertFalse(judged.judgeable("30°"))
        assertTrue(judged.judgeable("x=3"), "ordinary maths is read as before")
        assertTrue(judged.judgeable("hello"))
    }

    private companion object {
        const val STEPS = 24
    }
}
