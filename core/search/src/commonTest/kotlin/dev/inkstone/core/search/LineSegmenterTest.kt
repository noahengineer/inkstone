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
    fun `two columns are read as two columns and never across the gutter`() {
        // Version 1 skipped this page, and before it did, it had already read it wrong. The
        // columns are at different pitches, which is what real notes do: the left column's line
        // at 130 shares a baseline with the right column's at 124, and with no horizontal limit
        // that pair linked, and the next pair, and the lines chained down the page.
        val left = (0 until 5).flatMap { line(top = 100f + it * 30f, letters = 4, startX = 100f, tag = "L") }
        val right = (0 until 6).flatMap { line(top = 100f + it * 24f, letters = 4, startX = 320f, tag = "R") }

        val result = LineSegmenter.segment(left + right)

        assertEquals(11, result.lines.size)
        for (line in result.lines) {
            val columns = line.strokes.map { it.id.value.first() }.toSet()
            assertEquals(1, columns.size, "a line must stay in one column, was ${line.strokes.map { it.id.value }}")
            assertEquals(4, line.strokes.size)
        }
        assertTrue(result.skipped.isEmpty(), "a page with columns is read, not skipped")
    }

    @Test
    fun `a line takes its context from the line above it in its own column`() {
        // The line before it in page order is the one beside it, across the gutter, and a
        // recogniser told that is what was just written reads the wrong context into it.
        val left = (0 until 3).flatMap { line(top = 100f + it * 30f, letters = 4, startX = 100f, tag = "L") }
        val right = (0 until 3).flatMap { line(top = 100f + it * 24f, letters = 4, startX = 320f, tag = "R") }
        val lines = LineSegmenter.segment(left + right).lines

        fun lineAt(tag: String, top: Float) =
            lines.single { it.strokes.first().id.value.startsWith(tag) && it.writingArea.top == top }

        assertEquals(lineAt("R", 100f), LineSegmenter.contextLineFor(lineAt("R", 124f), lines))
        assertEquals(lineAt("L", 100f), LineSegmenter.contextLineFor(lineAt("L", 130f), lines))
        assertEquals(null, LineSegmenter.contextLineFor(lineAt("L", 100f), lines), "nothing is above the first line")
    }

    @Test
    fun `an i-dot above its word joins the word`() {
        // It shares no baseline and no height with anything, so nothing links it. On its own it
        // would be read as a word, a stray character in the index and a word short of a stroke.
        val word = line(top = 100f, letters = 5)
        val dot = stroke("dot", PageRect(left = 125f, top = 92f, right = 127f, bottom = 94f))

        val result = LineSegmenter.segment(word + dot)

        assertEquals(1, result.lines.size)
        assertEquals(6, result.lines.single().strokes.size)
    }

    @Test
    fun `a dot between two lines joins the line below it`() {
        // Nearer the line above than the line below, as an i-dot over a tall line often is. It
        // belongs below: an accent sits over its letter, and the line above has nothing hanging
        // that low but descenders.
        val page = line(top = 100f, letters = 4, tag = "A") + line(top = 130f, letters = 4, tag = "B")
        val dot = stroke("dot", PageRect(left = 121f, top = 121f, right = 123f, bottom = 123f))

        val result = LineSegmenter.segment(page + dot)

        assertEquals(2, result.lines.size)
        val below = result.lines.single { line -> line.strokes.any { it.id.value.startsWith("B") } }
        assertTrue(below.strokes.any { it.id.value == "dot" }, "the dot must join the line below")
    }

    @Test
    fun `a mark between two lines does not fuse them`() {
        // The failure that moved marks out of the link rule. A `g` hangs from the line above and
        // an `l` rises from the line below into the same band, and a dot there sits within the
        // height of both. Version 1 let a small mark join anything whose height it sat inside,
        // so the dot joined the `g` and the `l`, and the two lines became one.
        val upper = line(top = 100f, letters = 4, tag = "A") +
            stroke("A-g", PageRect(left = 180f, top = 100f, right = 192f, bottom = 119f))
        val lower = line(top = 130f, letters = 4, tag = "B") +
            stroke("B-l", PageRect(left = 180f, top = 115f, right = 186f, bottom = 142f))
        val dot = stroke("dot", PageRect(left = 182f, top = 116f, right = 184f, bottom = 118f))

        val result = LineSegmenter.segment(upper + lower + dot)

        assertEquals(2, result.lines.size, "a dot must not join two lines into one")
    }

    @Test
    fun `a heading written large keeps its letters together`() {
        // The notes set the page's scale at 12 pt. The heading's letters are three times that and
        // wobble 12 pt off their baseline, which is nothing at their size and was half again the
        // tolerance at the notes' size: version 1 cut the heading into fragments.
        val notes = (0 until 3).flatMap { line(top = 300f + it * 30f, letters = 6) }
        val heading = (0 until 5).map { index ->
            val left = 100f + index * 40f
            val top = if (index % 2 == 0) 100f else 112f
            stroke("h$index", PageRect(left = left, top = top, right = left + 30f, bottom = top + 36f))
        }

        val result = LineSegmenter.segment(notes + heading)

        assertEquals(4, result.lines.size)
        assertEquals(5, result.lines.first().strokes.size, "the heading is one line")
    }

    @Test
    fun `a word split at a descender is joined back into its line`() {
        // The user's "Equations with squareds", in boxes. A tall capital and a q whose tail hangs
        // 10 pt below the line link to each other. Neither links to the u after them: the q's foot is
        // too far below the u's, and the capital is further from the u than the gutter. So no pair of
        // strokes joins "Eq" to "uations", and the first draft read them as two lines. Their median
        // baselines agree, which no single descender can change.
        val notes = line(top = 300f, letters = 6) + line(top = 330f, letters = 6)
        val capital = stroke("cap", PageRect(left = 100f, top = 92f, right = 112f, bottom = 112f))
        val q = stroke("q", PageRect(left = 120f, top = 100f, right = 132f, bottom = 122f))
        val rest = (0 until 3).map { index ->
            val left = 136f + index * 20f
            stroke("rest$index", PageRect(left = left, top = 100f, right = left + 12f, bottom = 112f))
        }

        val result = LineSegmenter.segment(notes + capital + q + rest)

        assertEquals(3, result.lines.size)
        assertEquals(5, result.lines.first().strokes.size, "the heading is one line")
    }

    @Test
    fun `a capital I with serifs is a letter and not rotated text`() {
        // Two bars and a stem. Version 1 measured a line's slope from its first stroke to its last,
        // and the first bar's foot is a letter's height above the stem's: a slope of two, and the
        // letter was skipped as writing turned on its side. Notes beside it set the page's scale,
        // as they would on a real page: a lone `I` is mostly bars, and a median of bars is no scale.
        val notes = line(top = 100f, letters = 6) + line(top = 130f, letters = 6)
        val letter = listOf(
            stroke("bar-top", PageRect(left = 400f, top = 100f, right = 412f, bottom = 101f)),
            stroke("stem", PageRect(left = 405f, top = 100f, right = 407f, bottom = 112f)),
            stroke("bar-foot", PageRect(left = 400f, top = 111f, right = 412f, bottom = 112f)),
        )

        val result = LineSegmenter.segment(notes + letter)

        assertEquals(3, result.lines.size)
        val ids = result.lines.single { line -> line.strokes.any { it.id.value == "stem" } }.strokes.map { it.id.value }
        assertEquals(setOf("bar-top", "stem", "bar-foot"), ids.toSet())
        assertTrue(result.skipped.isEmpty(), "was ${result.skipped}")
    }

    @Test
    fun `strokes stacked far apart are not writing up the margin`() {
        // A column of equations leaves a column of lone strokes: a numerator here, a bar there.
        // Letters written up a margin are as close together as letters written across it, so a run
        // of them is held to the same gutter, and these are four line heights apart.
        val notes = (0 until 3).flatMap { line(top = 100f + it * 30f, letters = 6) }
        val stacked = (0 until 6).map { index ->
            val top = 300f + index * 60f
            stroke("eq$index", PageRect(left = 100f, top = top, right = 112f, bottom = top + 12f))
        }

        val result = LineSegmenter.segment(notes + stacked)

        assertTrue(result.skipped.none { it.reason == SkipReason.ROTATED }, "was ${result.skipped}")
        assertEquals(9, result.lines.size)
    }

    @Test
    fun `a line written at fifteen degrees is skipped as rotated`() {
        // Steep enough that its letters still link, and far past the ~11° a sloping hand reaches.
        val slanted = (0 until 8).map { index ->
            val left = 100f + index * 16f
            val top = 100f + index * 4.29f
            stroke("s$index", PageRect(left = left, top = top, right = left + 12f, bottom = top + 12f))
        }

        val result = LineSegmenter.segment(slanted)

        assertTrue(result.lines.isEmpty())
        assertEquals(listOf(SkipReason.ROTATED), result.skipped.map { it.reason })
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

    /** One line of `letters` 12 pt-high boxes, 12 pt wide on a 20 pt pitch, their ids starting [tag]. */
    private fun line(top: Float, letters: Int, startX: Float = 100f, tag: String = "s"): List<SampledStroke> =
        (0 until letters).map { index ->
            val left = startX + index * 20f
            stroke("$tag-$top-$index", PageRect(left = left, top = top, right = left + 12f, bottom = top + 12f))
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
