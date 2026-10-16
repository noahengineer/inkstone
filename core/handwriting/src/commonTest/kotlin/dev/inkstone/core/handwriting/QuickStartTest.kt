package dev.inkstone.core.handwriting

import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.IdFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * D14's quick start — prompted writing becomes `CONFIRMED` samples with no recogniser involved. There
 * is no `InkReader` in [QuickStart.samples]' signature at all, which is the strongest form of that
 * claim a test can make.
 */
class QuickStartTest {

    private val ids = IdFactory(object : Clock {
        override fun nowEpochMillis(): Long = 1_760_000_000_000L
    })

    /** [words] written left to right on one baseline, letters a quarter x-height apart, words two apart. */
    private fun written(vararg words: String) = spaced(0.25f, *words)

    private fun spaced(letterGap: Float, vararg words: String) = run {
        val strokes = ArrayList<List<Pair<Float, Float>>>()
        var left = 0f
        for (word in words) {
            for (letter in word) {
                val shape = HandFixtures.LETTERS.getValue(letter)
                val shift = left
                strokes += shape.map { stroke -> stroke.map { (u, v) -> (u + shift) to v } }
                left += shape.flatten().maxOf { it.first } + letterGap
            }
            left += 2f
        }
        HandFixtures.pageTrajectoryOf(strokes, NormalFrame(30f, 200f, 0f, 0f, 8f))
    }

    @Test
    fun `each prompted word becomes a confirmed sample with its letters`() {
        val result = QuickStart.samples("nun mix", 0, written("nun", "mix"), HandFixtures.PEN, 0.0353f, ids, 20_000L)
        val samples = assertIs<QuickStartResult.Learned>(result).samples

        assertEquals(listOf("nun", "mix"), samples.map { it.label })
        assertTrue(samples.all { it.labelSource == LabelSource.CONFIRMED })
        assertTrue(samples.all { it.noteId.value == QuickStart.NOTE_ID && it.split == SampleSplit.TRAIN })
        assertEquals(listOf("n", "u", "n"), samples[0].glyphs.map { it.text })
        // The i's dot is part of the i.
        assertEquals(listOf(1, 2), samples[1].glyphs[1].strokes)
        assertTrue(samples[0].frame.xHeightPt in 6f..10f, "x-height ${samples[0].frame.xHeightPt}")
    }

    @Test
    fun `assembly can write from the quick start alone`() {
        val result = QuickStart.samples("nun mix", 0, written("nun", "mix"), HandFixtures.PEN, null, ids, 20_000L)
        val index = GlyphIndex.build(assertIs<QuickStartResult.Learned>(result).samples, LabelBook())

        assertTrue(index.variants("x").isNotEmpty())
        assertTrue(index.variants("nu").isNotEmpty())
    }

    @Test
    fun `a hand that leaves air between its letters still splits into the prompt's words`() {
        // Letter gaps of 0.7 x-heights: past M7's word-space line (a third of the median stroke
        // height), so `WordBoxes` would read every letter as a word. The prompt says there are two.
        val result = QuickStart.samples("nun mix", 0, spaced(0.7f, "nun", "mix"), HandFixtures.PEN, null, ids, 20_000L)

        assertEquals(listOf("nun", "mix"), assertIs<QuickStartResult.Learned>(result).samples.map { it.label })
    }

    /**
     * What the one-line check is for, kept by docs/HANDWRITING-MATHS.md §2.4's change to it: setting
     * marks aside must not let a prompt that ran onto a second line through.
     */
    @Test
    fun `a prompt written on two lines is refused`() {
        fun moved(letter: Char, du: Float, dv: Float) =
            HandFixtures.LETTERS.getValue(letter).map { s -> s.map { (u, v) -> (u + du) to (v + dv) } }
        // `nu` on one line, then `m` four x-heights lower, back under it at the left.
        val strokes = moved('n', 0f, 0f) + moved('u', 1.2f, 0f) + moved('m', 0.2f, -4f)
        val written = HandFixtures.pageTrajectoryOf(strokes, NormalFrame(30f, 200f, 0f, 0f, 8f))

        val result = QuickStart.samples("nu m", 0, written, HandFixtures.PEN, null, ids, 20_000L)

        assertEquals(QuickStartResult.NotOneLine, result)
    }

    @Test
    fun `words that do not split as the prompt does are not learned`() {
        val result = QuickStart.samples("nun mix lul", 0, written("nun", "mix"), HandFixtures.PEN, null, ids, 20_000L)

        assertEquals(QuickStartResult.WordsDidNotSplit(expected = 3), result)
    }

    @Test
    fun `a purged note never takes the quick start with it`() {
        val result = QuickStart.samples("nun", 0, written("nun"), HandFixtures.PEN, null, ids, 20_000L)
        val samples = assertIs<QuickStartResult.Learned>(result).samples

        assertTrue(Departures.of(samples, ProfileState(), onDevice = setOf("some-note")).isEmpty())
    }
}
