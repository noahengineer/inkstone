package dev.inkstone.core.handwriting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * §18.7 — what the tablet's 136-word profile showed and the emulator's 19 did not: a word cut in a
 * frame that is wrong for it, and a word whose ink holds more than its letters. Both are kept out of
 * assembly rather than corrected. The numbers in the first test are the tablet's own.
 */
class FrameCheckTest {

    private val neat = NormalFrame(originX = 40f, originY = 100f, angleRad = 0f, slantRad = 0f, xHeightPt = 6f)

    @Test
    fun `lower-case letters judge their frame by their shape`() {
        assertTrue(LetterFit.fits("n", -0.02f, 1.0f))
        assertTrue(LetterFit.fits("p", -0.9f, 1.0f))
        assertTrue(LetterFit.fits("d", 0.38f, 2.44f))
        // `tap` on the tablet: its p a whole descender too high, its t one and a half x-heights low.
        assertFalse(LetterFit.fits("p", -0.01f, 3.36f))
        assertFalse(LetterFit.fits("t", -1.52f, 1.12f))
    }

    @Test
    fun `a word cut in a frame a line and a half too high gives no units - a level one does`() {
        val level = HandFixtures.sample("nun")
        val misframed = HandFixtures.sample("nun").copy(frame = neat.copy(originY = 109f))

        assertTrue(GlyphIndex.build(listOf(level), LabelBook()).variants("n").isNotEmpty())
        val index = GlyphIndex.build(listOf(misframed), LabelBook())
        assertTrue(index.variants("n").isEmpty())
        assertTrue(index.variants("nun").isEmpty())
        assertTrue(index.placements.any { !it.fits })
    }

    @Test
    fun `a word whose ink holds more than its letters is not used whole - its cut letters still are`() {
        // Ink for `nun`, labelled `nu`: the tablet's `a` that came with its `)`.
        val extra = HandFixtures.sample("nun").copy(label = "nu")
        val index = GlyphIndex.build(listOf(extra), LabelBook())

        assertTrue(index.variants("nu").isEmpty())
        assertTrue(index.variants("n").isNotEmpty())
        assertEquals(listOf("nu"), index.strayInkWords)
    }

    @Test
    fun `a dot standing off the front of a word is stray ink - a dot in the label is not`() {
        val n = HandFixtures.LETTERS.getValue('n')
        val u = HandFixtures.LETTERS.getValue('u').map { stroke -> stroke.map { (x, y) -> (x + 0.9f) to y } }
        val dot = listOf(listOf(-0.45f to 0.5f, -0.42f to 0.52f))
        val plain = assertNotNull(neat.normalise(HandFixtures.pageTrajectoryOf(n + u, neat)))
        val dotted = assertNotNull(neat.normalise(HandFixtures.pageTrajectoryOf(dot + n + u, neat)))

        assertFalse(GlyphIndex.carriesStrayInk("nu", plain))
        assertTrue(GlyphIndex.carriesStrayInk("nu", dotted))
        assertFalse(GlyphIndex.carriesStrayInk(".nu", dotted))
    }

    /**
     * docs/HANDWRITING-MATHS.md §2.4: a thin stroke inside another's span is one letter. Clustered in
     * x-heights, GlyphClusters' 0.25 pt floor was 0.25 x-heights, so a straight `t` stem under its bar
     * was a cluster of its own and the `t` "stray ink". On the tablet's profile that had dropped 16
     * clean words (`to`, `st`, `output`, `FIVE`, `sphinx` …) and ten single letters.
     */
    @Test
    fun `a straight stem under its bar is one letter and not stray ink`() {
        // A hand's lean on each stem, about a twentieth of an x-height: the tablet's straightest `t`s.
        val t = listOf(listOf(0.2f to 1.6f, 0.26f to 0f), listOf(0f to 1f, 0.5f to 1f))
        val pi = listOf(
            listOf(0f to 0.9f, 0.7f to 0.9f),
            listOf(0.2f to 0.9f, 0.15f to 0f),
            listOf(0.5f to 0.9f, 0.55f to 0f),
        )
        for ((label, shape) in listOf("t" to t, "π" to pi)) {
            val normal = assertNotNull(neat.normalise(HandFixtures.pageTrajectoryOf(shape, neat)))
            assertFalse(GlyphIndex.carriesStrayInk(label, normal), label)
        }
    }
}
