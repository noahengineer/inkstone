package dev.inkstone.core.handwriting

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.InkSample
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PhrasePlacement
import dev.inkstone.core.model.PhraseRecord
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.model.SnapGrid
import dev.inkstone.core.model.TextLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Three things the first harness sheet from the emulator's real profile showed wrong
 * (docs/HANDWRITING-SYNTHESIS.md §18): a word twice the size of the rest, words run together, and a
 * page pitch smaller than the writing.
 */
class SheetFixesTest {

    @Test
    fun `a word whose line under-measured its x-height is brought back to size by its own letters`() {
        // Written at x-height 6 but stored with a frame that says 3: normalised, it stands two tall.
        val right = HandFixtures.sample("nun")
        val halved = right.copy(id = "halved", sourceKey = "halved", frame = right.frame.copy(xHeightPt = 3f))
        val index = GlyphIndex.build(listOf(halved), LabelBook())

        val word = index.variants("nun").single().trajectory
        assertEquals(1f, word.bounds().bottom, 0.1f)
    }

    @Test
    fun `a word with no x-height letter cut is left as its line measured it`() {
        val right = HandFixtures.sample("nun", cut = false)
        val halved = right.copy(id = "halved", sourceKey = "halved", frame = right.frame.copy(xHeightPt = 3f))
        val word = GlyphIndex.build(listOf(halved), LabelBook()).variants("nun").single().trajectory

        assertEquals(2f, word.bounds().bottom, 0.1f)
    }

    @Test
    fun `letters from different words never overlap by more than a sliver`() {
        val index = GlyphIndex.build(listOf(HandFixtures.sample("nun")), LabelBook())
        assertTrue(index.stats.letterGapsFor("x", "y").all { it >= -0.15f })
    }

    @Test
    fun `a page's word spaces are measured between words and not between letters`() {
        // Letters 1 pt apart, words 9 pt apart, x-height 6.
        val strokes = listOf(0f, 7f, 14f, 30f, 37f).mapIndexed { i, left -> box("s$i", left, left + 6f) }

        val gaps = PageWriting.wordGapsOf(strokes, xHeight = 6f)
        assertEquals(listOf(10f / 6f), gaps)
    }

    @Test
    fun `a dropped phrase takes the paragraph's own word spacing`() {
        val line = { baseline: Float ->
            val style = LineStyle(baseline, 0f, 5f, 0f, PageRect(50f, baseline - 10f, 300f, baseline + 5f))
            MeasuredLine(style, null, 10, listOf(1.8f, 2.0f, 2.2f))
        }
        val writing = PageWriting(listOf(line(100f), line(120f)), SnapGrid.forBackground(Background.Blank))
        val hand = GlyphIndex.build(listOf(HandFixtures.sample("nun")), LabelBook()).stats

        val placement = Placements.resolve(PagePoint(60f, 140f), PlaceMode.DROP, writing, hand, 400f)
        assertEquals(2.0f, placement.wordGapXHeights)
        assertEquals(2.0f, placement.style(0.5f).wordGapXHeights)
    }

    @Test
    fun `a page's pitch is measured within paragraphs - not across a mind map`() {
        // Two columns, each with a 20 pt pitch, offset by 3 pt from each other.
        val lines = (0 until 4).flatMap { i ->
            listOf(
                LineStyle(100f + i * 20f, 0f, 5f, 0f, PageRect(50f, 90f + i * 20f, 200f, 105f + i * 20f)),
                LineStyle(103f + i * 20f, 0f, 5f, 0f, PageRect(400f, 93f + i * 20f, 550f, 108f + i * 20f)),
            )
        }

        assertEquals(20f, assertNotNull(PageStyle.of(lines).linePitchPt), 0.01f)
    }

    @Test
    fun `the word space rides in the phrase record - and an older record without one still reads`() {
        val placement = PhrasePlacement(1f, 2f, 5f, 0f, 0f, 300f, 16f, 0.5f, wordGapXHeights = 1.75f)
        assertEquals(placement, PhraseRecord.decode(PhraseRecord("a b", placement).encode())?.placement)

        val older = PhraseRecord.decode("hw1;x=1;y=2;xh=5;sl=0;an=0;w=300;p=16;n=0.5\na b")
        assertNull(assertNotNull(older?.placement).wordGapXHeights)
    }

    @Test
    fun `subscripts and marks are not lines of writing - so a drop never continues them`() {
        val word = { left: Float -> (0 until 4).map { box("w$left-$it", left + it * 7f, left + it * 7f + 6f) } }
        val lineOf = { strokes: List<SampledStroke>, box: PageRect, baseline: Float, xHeight: Float ->
            TextLine(strokes, box) to LineStyle(baseline, 0f, xHeight, 0f, box)
        }
        val writing = lineOf(word(0f), PageRect(0f, 90f, 30f, 100f), 100f, 6f)
        val writing2 = lineOf(word(100f), PageRect(100f, 90f, 130f, 100f), 100f, 5f)
        val subscripts = lineOf(word(200f).take(3), PageRect(200f, 98f, 230f, 102f), 102f, 1.8f)
        val dot = lineOf(word(300f).take(1), PageRect(300f, 95f, 301f, 96f), 96f, 1f)

        val kept = PageWriting.writingLines(listOf(writing, writing2, subscripts, dot))
        assertEquals(listOf(writing, writing2), kept)
    }

    private fun box(id: String, left: Float, right: Float) = SampledStroke(
        ObjectId(id),
        listOf(InkSample(left, 100f, 0L), InkSample(right, 94f, 10L)),
        PageRect(left, 94f, right, 100f),
    )
}
