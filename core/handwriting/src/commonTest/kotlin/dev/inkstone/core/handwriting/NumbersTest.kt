package dev.inkstone.core.handwriting

import dev.inkstone.core.handwriting.neural.KotlinSynthesisNetwork
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * §18.10 — *"the spacing between words is a bit big … a number or symbol will show up very big …
 * I need this to be really good at math and numbers"*. Each test is one cause found on the tablet's
 * profile, and the numbers in them are that profile's own.
 */
class NumbersTest {

    @Test
    fun `a capital digit or symbol is judged for absurdity and never for size`() {
        // Honest copies on the profile: x-height capitals and digits (`NO2`), the tallest capital, a
        // bracket's tail, a dash across the middle, the one good decimal point.
        assertTrue(LetterFit.fits("N", 0f, 1f))
        assertTrue(LetterFit.fits("2", 0f, 0.94f))
        assertTrue(LetterFit.fits("C", -0.35f, 2.74f))
        assertTrue(LetterFit.fits(")", -1.11f, 1.99f))
        assertTrue(LetterFit.fits("-", 0f, 0.59f))
        assertTrue(LetterFit.fits(".", 0.28f, 0.29f))
        // Misframed ones, each drawn at that size before: `7` and `=` from `=27`, a lone `A`, a `3`
        // seven x-heights deep, and `p.`'s dot below the line.
        assertFalse(LetterFit.fits("7", 0f, 8.57f))
        assertFalse(LetterFit.fits("=", -0.86f, 5.66f))
        assertFalse(LetterFit.fits("A", -0.95f, 10.52f))
        assertFalse(LetterFit.fits("3", -7.03f, 7.65f))
        assertFalse(LetterFit.fits(".", -1.02f, -0.74f))
    }

    @Test
    fun `a subscript digit is kept out of single letters - its word and its other letters are kept`() {
        val subscript = HandFixtures.withShape('3', scale = 0.52f, drop = 0f)
        val index = GlyphIndex.build(listOf(HandFixtures.sample("n3", shapes = subscript)), LabelBook())

        assertTrue(index.variants("3").isEmpty(), "a formula's 3 must not stand in for a number's")
        assertTrue(index.variants("n3").isNotEmpty(), "the whole word is right as written")
        assertTrue(index.variants("n").isNotEmpty())

        val normal = GlyphIndex.build(listOf(HandFixtures.sample("n3")), LabelBook())
        assertTrue(normal.variants("3").isNotEmpty(), "a full-height 3 is a letter unit")
    }

    /**
     * docs/HANDWRITING-MATHS.md §5.2: an exponent's top sits where a digit's top does, so only its height
     * says it is a script. The tablet's profile had a `2` 0.55 x-heights tall, and an exponent built from
     * it came out as a dot.
     */
    @Test
    fun `an exponent digit is kept out of single letters too`() {
        val raised = HandFixtures.withShape('3', scale = 0.45f, drop = -0.35f)
        val index = GlyphIndex.build(listOf(HandFixtures.sample("n3", shapes = raised)), LabelBook())

        assertTrue(index.variants("3").isEmpty(), "an exponent's 3 must not stand in for a number's")
        assertTrue(index.variants("n").isNotEmpty(), "and the letters beside it are kept")
    }

    @Test
    fun `the deliberately clear gaps of the quick start are not the word spacing of the hand`() {
        fun line(noteId: String, pageId: String) = listOf(40f, 75f, 110f).zip(listOf("nun", "mix", "nun"))
            .map { (x, word) ->
                HandFixtures.sample(
                    word,
                    noteId = noteId,
                    pageId = pageId,
                    frame = NormalFrame(originX = x, originY = 100f, angleRad = 0f, slantRad = 0f, xHeightPt = 6f),
                )
            }

        assertEquals(2, GlyphIndex.build(line("note", "page"), LabelBook()).stats.wordGaps.size)
        assertTrue(GlyphIndex.build(line(QuickStart.NOTE_ID, "prompt-0"), LabelBook()).stats.wordGaps.isEmpty())
    }

    @Test
    fun `a decimal point keeps clear of the digits either side however tight the letters of the hand are`() {
        // A hand whose letters overlap by 0.15 x-heights, the floor the tablet's profile sits on: with no
        // gaps of its own, a digit-to-point pair falls back to these.
        val hand = listOf(
            HandFixtures.sample("nun", letterGap = -0.15f),
            HandFixtures.sample("nun", letterGap = -0.15f),
            HandFixtures.sample("3"),
            HandFixtures.sample("."),
        )
        val index = GlyphIndex.build(hand, LabelBook())
        val word =
            assertIs<AssembledWord.Written>(GlyphAssembly(index).word("3.3", Variation.None, Random(1), HashSet()))

        val t = word.trajectory
        assertEquals(3, t.strokeCount)
        val boxes = (0 until 3).map { s -> assertNotNull(t.strokes(listOf(s))).bounds() }
        assertTrue(
            boxes[1].left - boxes[0].right >= POINT_CLEAR,
            "point after a digit: ${boxes[1].left - boxes[0].right}",
        )
        assertTrue(
            boxes[2].left - boxes[1].right >= POINT_CLEAR,
            "digit after a point: ${boxes[2].left - boxes[1].right}",
        )
    }

    @Test
    fun `the quick start opens at the first prompt not yet recorded`() {
        val done = QuickStart.promptsDone(
            listOf(
                HandFixtures.sample("nun", noteId = QuickStart.NOTE_ID, pageId = "prompt-0"),
                HandFixtures.sample("mix", noteId = QuickStart.NOTE_ID, pageId = "prompt-1"),
                HandFixtures.sample("nun", noteId = "note", pageId = "prompt-2"),
            ),
        )
        assertEquals(setOf(0, 1), done, "only quick-start samples count")
        assertEquals(2, QuickStart.firstToDo(done))
        assertEquals(0, QuickStart.firstToDo(QuickStart.PROMPTS.indices.toSet()), "all done: from the start")
    }

    @Test
    fun `after a prime the window is moved up to the end of the prime and never back`() {
        // Two components, equal weight (α̂ = 0), centred at 0.75: three characters behind a prime of 3.
        val lagging = floatArrayOf(0.5f, 1.0f)
        KotlinSynthesisNetwork.catchUp(lagging, floatArrayOf(0f, 0f), k = 2, length = 3)
        assertEquals(listOf(2.75f, 3.25f), lagging.toList(), "moved together, so the window keeps its shape")

        val ahead = floatArrayOf(3.5f, 4.0f)
        KotlinSynthesisNetwork.catchUp(ahead, floatArrayOf(0f, 0f), k = 2, length = 3)
        assertEquals(listOf(3.5f, 4.0f), ahead.toList())
    }

    private companion object {
        const val POINT_CLEAR = 0.2f - 1e-4f
    }
}
