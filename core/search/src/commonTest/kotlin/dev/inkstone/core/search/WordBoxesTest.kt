package dev.inkstone.core.search

import dev.inkstone.core.model.InkSample
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.model.TextLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SPEC §5.5. ML Kit returns text and **no geometry** — `RecognitionCandidate` is a string and a
 * score, verified against the 19.0.0 artifact — so every box in the invisible text layer and
 * every search-hit highlight is computed here and nowhere else. If this class is wrong, the
 * failure is silent: the text is right, the PDF is valid, and the highlight lands on the wrong
 * ink or across the whole line.
 *
 * Fixtures are in points with y increasing downward (SPEC §4.4). Letters are 12 pt high, 8 pt
 * wide on a 10 pt pitch, which is roughly a 4 mm hand: the word-gap threshold at that height is
 * `12 × 0.33 = 3.96 pt`, comfortably above the 2 pt letter gap and below the 12 pt word gap, so
 * no assertion here turns on a rounding.
 */
class WordBoxesTest {

    @Test
    fun `when the gaps agree with the recogniser every word gets the box of its own ink`() {
        // ⭐ The path that makes this better than an OCR sandwich. A hit on "there" must
        // highlight the ink that spells "there" — not the line, not an estimate from a
        // rasterised glyph.
        val line = textLine(word("hi", startX = 100f, letters = 3) + word("there", startX = 140f, letters = 2))

        val result = WordBoxes.assign(line, "hi there")

        assertEquals(listOf("hi", "there"), result.words.map { it.text })
        assertEquals(PageRect(100f, 100f, 128f, 112f), result.words[0].bounds)
        assertEquals(PageRect(140f, 100f, 158f, 112f), result.words[1].bounds)
        assertEquals(line.writingArea, result.bounds)
    }

    @Test
    fun `when the counts disagree the words are apportioned along the line in order`() {
        // Two stroke clusters, three recognised words — the ordinary disagreement, produced by
        // a recogniser that read a word space that was not there. Dropping the words or giving
        // all three the line box are both defensible and both useless; what a search hit needs
        // is the right line and roughly the right place along it.
        val line = textLine(word("how", startX = 100f, letters = 3) + word("areyou", startX = 140f, letters = 2))
        val box = line.writingArea
        val slack = 0.01f

        val result = WordBoxes.assign(line, "how are you")

        assertEquals(listOf("how", "are", "you"), result.words.map { it.text })
        assertTrue(result.words.all { it.bounds.width > 0f }, "a zero-width box highlights nothing")
        assertTrue(
            result.words.all { it.bounds.top == box.top && it.bounds.bottom == box.bottom },
            "an apportioned word is still on its own line",
        )
        assertTrue(result.words.first().bounds.left >= box.left, "no word starts left of the line")
        assertTrue(result.words.last().bounds.right <= box.right + slack, "no word runs off the end of the line")
        assertTrue(
            result.words.none { it.bounds.width >= box.width },
            "claiming the whole line is the failure this fallback exists to avoid",
        )
        result.words.zipWithNext { left, right ->
            assertTrue(
                right.bounds.left >= left.bounds.right,
                "apportioned words must run left to right without overlapping",
            )
        }
    }

    @Test
    fun `a line the recogniser could not read has no words`() {
        val line = textLine(word("scribble", startX = 100f, letters = 4))

        val result = WordBoxes.assign(line, "   \n  ")

        assertEquals("", result.text)
        assertTrue(result.words.isEmpty(), "empty text must not be split into one empty word")
        assertEquals(line.writingArea, result.bounds)
    }

    @Test
    fun `a line with no strokes is not a crash`() {
        // The guard matters because clustering takes the first of a sorted list. A segmentation
        // that ever hands over an empty line would otherwise take down the whole recognition
        // pass for the page rather than losing one line.
        val line = TextLine(emptyList(), PageRect(100f, 100f, 200f, 112f))

        val result = WordBoxes.assign(line, "words with no ink")

        assertEquals("words with no ink", result.text)
        assertTrue(result.words.isEmpty())
    }

    @Test
    fun `a crossbar added after the word keeps the next stroke in that word`() {
        // ⭐ Why the gap is measured from the furthest right reached so far and not from the
        // previous stroke. "cats" is written c-a-t-s and the t is crossed last, so in left-to-
        // right order the bar (right edge 134) sits before the stem (right edge 128).
        //
        // Measuring from the previous stroke gives 135 - 128 = 7 pt, above the 5.28 pt
        // threshold this line's 16 pt ascender sets: the s becomes its own word, the counts go
        // three against two, and *every* word on the line falls back to an apportioned box.
        // Measuring from the furthest right gives 135 - 134 = 1 pt, which is a letter gap.
        val cats = listOf(
            stroke("c", PageRect(left = 100f, top = 100f, right = 110f, bottom = 112f)),
            stroke("a", PageRect(left = 112f, top = 100f, right = 122f, bottom = 112f)),
            stroke("t-stem", PageRect(left = 124f, top = 96f, right = 128f, bottom = 112f)),
            stroke("s", PageRect(left = 135f, top = 100f, right = 145f, bottom = 112f)),
            stroke("t-bar", PageRect(left = 120f, top = 101f, right = 134f, bottom = 102f)),
        )
        val line = textLine(cats + word("sat", startX = 155f, letters = 3))

        val result = WordBoxes.assign(line, "cats sat")

        assertEquals(listOf("cats", "sat"), result.words.map { it.text })
        assertEquals(PageRect(100f, 96f, 145f, 112f), result.words[0].bounds, "the s belongs to cats")
        assertEquals(PageRect(155f, 100f, 183f, 112f), result.words[1].bounds)
    }

    @Test
    fun `the same layout written three times the size is still one word`() {
        // The threshold is relative to the line's own height, so it follows the hand. An
        // absolute gap in points calibrated on the small hand would be 3.96 pt, and the large
        // hand's 9 pt letter gaps would clear it three times over — turning one word into three
        // clusters, disagreeing with the recogniser, and apportioning a box that was exact.
        val small = WordBoxes.assign(hand(scale = 1f), "the")
        val large = WordBoxes.assign(hand(scale = 3f), "the")

        assertEquals(1, small.words.size)
        assertEquals(1, large.words.size, "a large hand writes larger gaps within a word too")
        assertEquals(PageRect(0f, 0f, 30f, 12f), small.words[0].bounds)
        assertEquals(PageRect(0f, 0f, 90f, 36f), large.words[0].bounds)
    }

    @Test
    fun `a single stroke line gets one word with the true box`() {
        // The last cluster is only emitted after the loop ends, so a line with nothing to loop
        // over is where dropping it would show: one word, zero clusters, and a box apportioned
        // from a line that could have answered exactly.
        val only = stroke("I", PageRect(left = 100f, top = 100f, right = 106f, bottom = 112f))

        val result = WordBoxes.assign(textLine(listOf(only)), "I")

        assertEquals(1, result.words.size)
        assertEquals(only.bounds, result.words[0].bounds)
    }

    @Test
    fun `strokes that arrive out of left to right order are still clustered correctly`() {
        // A line's strokes are in *draw* order, not reading order — the writer who goes back to
        // dot an i or squeeze in a forgotten word produces exactly this, and LineSegmenter
        // preserves it deliberately (SPEC §8.1). Clustering has to sort for itself: fed this
        // line unsorted, the first gap measured is negative, every later one too, and the whole
        // line comes back as a single cluster that agrees with no two-word recogniser output.
        val written = (word("hi", startX = 100f, letters = 3) + word("there", startX = 140f, letters = 2)).reversed()

        val result = WordBoxes.assign(textLine(written), "hi there")

        assertEquals(PageRect(100f, 100f, 128f, 112f), result.words[0].bounds)
        assertEquals(PageRect(140f, 100f, 158f, 112f), result.words[1].bounds)
    }

    // -----------------------------------------------------------------------

    /** One word of [letters] 12 pt-high boxes 8 pt wide on a 10 pt pitch — a 2 pt letter gap. */
    @Test
    fun `strokesOfWords hands each word the strokes that made it in drawing order`() {
        // docs/HANDWRITING-SYNTHESIS.md §5.3 — the grouping assign throws away, kept. The line is
        // given in DRAWING order with the second word's first letter drawn last (a dot, a crossbar
        // added afterwards), and that order has to survive: a trajectory's time runs forwards.
        val hi = word("hi", startX = 100f, letters = 3)
        val there = word("there", startX = 140f, letters = 2)
        val line = textLine(hi + there.drop(1) + there.first())

        val words = WordBoxes.strokesOfWords(line, "hi there")

        assertEquals(listOf("hi", "there"), words?.map { it.text })
        assertEquals(hi.map { it.id }, words?.get(0)?.strokes?.map { it.id })
        assertEquals((there.drop(1) + there.first()).map { it.id }, words?.get(1)?.strokes?.map { it.id })
        assertEquals(WordBoxes.assign(line, "hi there").words.map { it.bounds }, words?.map { it.bounds })
    }

    @Test
    fun `strokesOfWords refuses rather than apportions when the counts disagree`() {
        // assign falls back to slicing the line by characters, which says where a word is and
        // nothing about which strokes it is. Learning a word from the wrong strokes is worse than
        // not learning it, so there is no fallback here.
        val line = textLine(word("how", startX = 100f, letters = 3) + word("areyou", startX = 140f, letters = 2))

        assertEquals(null, WordBoxes.strokesOfWords(line, "how are you"))
        assertEquals(null, WordBoxes.strokesOfWords(line, "   "))
    }

    private fun word(id: String, startX: Float, letters: Int): List<SampledStroke> = (0 until letters).map { index ->
        val left = startX + index * 10f
        stroke("$id-$index", PageRect(left = left, top = 100f, right = left + 8f, bottom = 112f))
    }

    /** Three letters of one word at [scale], with every distance scaled — the gaps included. */
    private fun hand(scale: Float): TextLine = textLine(
        (0 until 3).map { index ->
            val left = index * 11f * scale
            val bounds = PageRect(
                left = left,
                top = 0f,
                right = left + 8f * scale,
                bottom = 12f * scale,
            )
            stroke("h$scale-$index", bounds)
        },
    )

    /**
     * The writing area comes from [LineSegmenter] in production and is the box around the line's
     * ink, so the fixture computes it rather than naming it — a hand-written area wider than the
     * writing would make the apportion assertions pass for the wrong reason.
     */
    private fun textLine(strokes: List<SampledStroke>): TextLine =
        TextLine(strokes, strokes.map { it.bounds }.reduce(PageRect::union))

    /**
     * Samples are the box's corners. [WordBoxes] reads only [SampledStroke.bounds], but a fixture
     * that left them empty would let a future version quietly start reading samples and still pass.
     */
    private fun stroke(id: String, bounds: PageRect): SampledStroke = SampledStroke(
        id = ObjectId(id),
        samples = listOf(
            InkSample(bounds.left, bounds.top, 0L),
            InkSample(bounds.right, bounds.bottom, 10L),
        ),
        bounds = bounds,
    )
}
