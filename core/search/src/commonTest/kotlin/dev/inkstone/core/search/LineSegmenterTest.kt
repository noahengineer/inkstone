package dev.inkstone.core.search

import dev.inkstone.core.model.InkSample
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.model.SkipReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SPEC §8.2. These are the assertions that stand behind M7's headline number, and they run on
 * the JVM with no emulator, no ML Kit and no `androidx.ink` — which is the entire reason
 * [LineSegmenter] takes plain boxes rather than strokes.
 *
 * The fixtures are built in points at a 12 pt x-height, which is roughly a 4 mm hand on a
 * Letter page. Coordinates are page space: y increases **downward** (SPEC §4.4), so line two
 * has the larger `top`.
 */
class LineSegmenterTest {

    @Test
    fun `an empty page segments to nothing`() {
        assertTrue(LineSegmenter.segment(emptyList()).isEmpty)
        assertTrue(LineSegmenter.segment(emptyList()).skipped.isEmpty())
    }

    @Test
    fun `two lines of writing are two lines`() {
        val page = line(top = 100f, letters = 6) + line(top = 130f, letters = 5)

        val result = LineSegmenter.segment(page)

        assertEquals(2, result.lines.size)
        assertEquals(6, result.lines[0].strokes.size)
        assertEquals(5, result.lines[1].strokes.size)
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun `lines come back top to bottom whatever order the strokes arrived in`() {
        // Draw order is not reading order — a writer who goes back to dot an i or add a
        // forgotten word produces exactly this, and out-of-order strokes degrade the
        // recogniser (SPEC §8.1).
        val page = line(top = 160f, letters = 3) + line(top = 100f, letters = 3) + line(top = 130f, letters = 3)

        val tops = LineSegmenter.segment(page).lines.map { it.writingArea.top }

        assertEquals(tops.sorted(), tops, "lines must be emitted top to bottom")
    }

    @Test
    fun `strokes within a line keep the order they were written in`() {
        // ⚠️ **Not left-to-right, and SPEC §8.2 step 3 has been corrected.** The recogniser is
        // trained on natural writing, where a `t` is crossed and an `i` dotted after the word
        // is finished; sorting by x moves those marks into the middle of the word and is
        // exactly the "out-of-order strokes degrade results" case SPEC §8.1 warns about.
        // Measured on the 20-page sample: word recall 0.378 in draw order against 0.313 in x
        // order. Only the *lines* are ordered spatially.
        val written = line(top = 100f, letters = 5).reversed()

        val strokes = LineSegmenter.segment(written).lines.single().strokes

        assertEquals(written.map { it.id }, strokes.map { it.id }, "draw order must survive segmentation")
    }

    @Test
    fun `an ascender and a descender stay on their own line`() {
        // The case a centre-of-box rule gets wrong: `l` and `g` have centres a third of an
        // x-height either side of `o`, so clustering on centres puts three letters of one
        // word on three different lines.
        //
        // Descender depth is ~0.42 of an x-height in ordinary type (0.21 em against 0.5 em),
        // so a 12 pt x-height drops about 5 pt below the baseline. An earlier version of this
        // fixture dropped a full 12 pt — twice any real hand — and then disagreed with the
        // baseline rule that 20 real pages had just calibrated. The fixture was wrong.
        val base = line(top = 100f, letters = 4)
        val ascender = stroke("asc", PageRect(left = 148f, top = 88f, right = 156f, bottom = 112f))
        val descender = stroke("desc", PageRect(left = 160f, top = 100f, right = 168f, bottom = 117f))

        val result = LineSegmenter.segment(base + ascender + descender)

        assertEquals(1, result.lines.size, "an ascender and a descender do not start new lines")
        assertEquals(6, result.lines.single().strokes.size)
    }

    @Test
    fun `the writing area is the line and not the page`() {
        // ⭐ The whole milestone. A WritingArea sized to the page tells ML Kit the writing is a
        // hundredth of the height it really is, and that is what produces garbage.
        val result = LineSegmenter.segment(line(top = 100f, letters = 6))

        val area = result.lines.single().writingArea
        assertEquals(100f, area.top)
        assertEquals(112f, area.bottom)
        assertTrue(area.height < 20f, "a line's writing area is a line high, was ${area.height}")
        assertTrue(area.width < 120f, "and only as wide as the writing, was ${area.width}")
    }

    @Test
    fun `a bracket spanning four lines does not fuse them`() {
        // Without the oversize rejection this is one connected component and therefore one
        // "line" containing the whole paragraph — the failure mode that makes a page of
        // perfectly ordinary notes unrecognisable.
        val page = (0 until 4).flatMap { line(top = 100f + it * 30f, letters = 4) }
        val bracket = stroke("bracket", PageRect(left = 90f, top = 96f, right = 96f, bottom = 216f))

        val result = LineSegmenter.segment(page + bracket)

        assertEquals(4, result.lines.size)
        assertEquals(listOf(SkipReason.NOT_WRITING), result.skipped.map { it.reason })
    }

    @Test
    fun `an underline is not a word`() {
        val page = line(top = 100f, letters = 5)
        val rule = stroke("rule", PageRect(left = 100f, top = 114f, right = 260f, bottom = 115f))

        val result = LineSegmenter.segment(page + rule)

        assertEquals(1, result.lines.size)
        assertEquals(5, result.lines.single().strokes.size, "the rule is not handed to the recogniser")
        assertEquals(listOf(SkipReason.NOT_WRITING), result.skipped.map { it.reason })
    }

    @Test
    fun `two columns are detected and skipped rather than read across the gutter`() {
        // SPEC §8.2 — "detect and skip rather than emitting nonsense". Read straight across,
        // these four lines say "the left the right" twice.
        val left = (0 until 4).flatMap { line(top = 100f + it * 30f, letters = 4, startX = 100f) }
        val right = (0 until 4).flatMap { line(top = 100f + it * 30f, letters = 4, startX = 320f) }

        val result = LineSegmenter.segment(left + right)

        assertTrue(result.lines.isEmpty(), "nothing is recognised on a two-column page")
        assertEquals(listOf(SkipReason.MULTI_COLUMN), result.skipped.map { it.reason })
    }

    @Test
    fun `one indented line is not a second column`() {
        val body = (0 until 4).flatMap { line(top = 100f + it * 30f, letters = 5, startX = 100f) }
        val indented = line(top = 220f, letters = 4, startX = 160f)

        val result = LineSegmenter.segment(body + indented)

        assertEquals(5, result.lines.size)
        assertTrue(result.skipped.isEmpty(), "an indent is a paragraph, not a column")
    }

    @Test
    fun `writing up the margin is skipped as rotated`() {
        val rotated = (0 until 6).map { index ->
            val top = 100f + index * 14f
            stroke("rot$index", PageRect(left = 60f, top = top, right = 72f, bottom = top + 12f))
        }

        val result = LineSegmenter.segment(rotated)

        assertTrue(result.lines.isEmpty())
        assertEquals(listOf(SkipReason.ROTATED), result.skipped.map { it.reason })
    }

    @Test
    fun `a gently sloping hand is still one line`() {
        // Unruled paper. Six letters drifting 6 pt over 120 pt is a slope of 0.05 — well
        // inside the tolerance, and rejecting it would make the feature useless on blank pages.
        val drifting = (0 until 6).map { index ->
            val top = 100f + index * 1.2f
            val left = 100f + index * 20f
            stroke("d$index", PageRect(left = left, top = top, right = left + 12f, bottom = top + 12f))
        }

        val result = LineSegmenter.segment(drifting)

        assertEquals(1, result.lines.size, "a sloping hand is not rotated text")
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun `a page of only diagram yields no lines and says why`() {
        val diagram = listOf(
            stroke("box-l", PageRect(left = 100f, top = 100f, right = 102f, bottom = 260f)),
            stroke("box-r", PageRect(left = 300f, top = 100f, right = 302f, bottom = 260f)),
            stroke("box-t", PageRect(left = 100f, top = 100f, right = 302f, bottom = 102f)),
        )

        val result = LineSegmenter.segment(diagram)

        assertTrue(result.lines.isEmpty())
        assertTrue(result.skipped.isNotEmpty(), "silence and 'this is a diagram' must be different answers")
    }

    @Test
    fun `scale is taken from the writing and not from a diagram sharing the page`() {
        // A page that is mostly diagram: if the median height came from the diagram strokes,
        // every real letter would measure as "flat" and be thrown away.
        val diagram = (0 until 8).map { index ->
            val left = 300f + index * 20f
            stroke("d$index", PageRect(left = left, top = 200f, right = left + 16f, bottom = 380f))
        }
        val writing = line(top = 100f, letters = 5)

        val result = LineSegmenter.segment(diagram + writing)

        assertEquals(1, result.lines.size)
        assertEquals(5, result.lines.single().strokes.size)
    }

    // -----------------------------------------------------------------------

    /** One line of `letters` 12 pt-high boxes, 12 pt wide on a 20 pt pitch. */
    private fun line(top: Float, letters: Int, startX: Float = 100f): List<SampledStroke> =
        (0 until letters).map { index ->
            val left = startX + index * 20f
            stroke("s-$top-$index", PageRect(left = left, top = top, right = left + 12f, bottom = top + 12f))
        }

    /**
     * Samples are the box's corners. [LineSegmenter] reads only [SampledStroke.bounds], but a
     * fixture that left them empty would let a future version quietly start reading samples and
     * still pass.
     */
    private fun stroke(id: String, bounds: PageRect): SampledStroke = SampledStroke(
        id = ObjectId(id),
        samples = listOf(
            InkSample(bounds.left, bounds.top, 0L),
            InkSample(bounds.right, bounds.bottom, 10L),
        ),
        bounds = bounds,
    )

    private operator fun List<SampledStroke>.plus(one: SampledStroke): List<SampledStroke> = this + listOf(one)
}
