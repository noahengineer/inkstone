package dev.inkstone.core.handwriting

import dev.inkstone.core.model.Background
import dev.inkstone.core.model.CapturedStroke
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.InkSample
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PenKind
import dev.inkstone.core.model.PenStyle
import dev.inkstone.core.model.PenTrajectory
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.model.SnapGrid
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeObject
import dev.inkstone.core.model.StrokeReader
import dev.inkstone.core.model.StrokeSampler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * D15 — a phrase takes the place, size, slant and pen of the user's writing around where it lands.
 *
 * The lines here are measured values written by hand, not ink, so these tests check the *rules*:
 * which paragraph, which lattice, which fallback. Whether [LineStyles] measures a real hand right is
 * the real-page check (docs §12, H3), done on the emulator's copy of the physics note.
 */
class PlacementTest {

    private val blue = PenStyle(PenKind.BALLPOINT, 0xFF1E40AF.toInt(), 1.0f)
    private val black = PenStyle(PenKind.FOUNTAIN, 0xFF111111.toInt(), 2.0f)

    /** A line whose baseline is at [baseline], from [left] to [right], written at [xHeight]. */
    private fun line(baseline: Float, left: Float, right: Float, xHeight: Float, pen: PenStyle, slant: Float = 0.2f) =
        MeasuredLine(
            style = LineStyle(
                baseline,
                0f,
                xHeight,
                slant,
                PageRect(left, baseline - xHeight * 2f, right, baseline + xHeight),
            ),
            pen = pen,
            strokes = 20,
        )

    /** Paragraph A: three blue lines 20 pt apart at x-height 5. Paragraph B: one big black line far below. */
    private val written = PageWriting(
        listOf(
            line(100f, 50f, 300f, 5f, blue),
            line(120f, 52f, 290f, 5f, blue),
            line(140f, 50f, 260f, 5f, blue),
            line(400f, 50f, 200f, 9f, black, slant = -0.05f),
        ),
        SnapGrid.forBackground(Background.Blank),
    )

    private val hand = GlyphIndex.build(listOf(HandFixtures.sample("nun")), LabelBook()).stats

    @Test
    fun `dropped below a paragraph - the phrase continues it on its lattice and at its margin`() {
        val placement = Placements.resolve(PagePoint(60f, 165f), PlaceMode.DROP, written, hand, wrapWidthPt = 400f)

        assertEquals(PlacementSource.PARAGRAPH, placement.source)
        assertEquals(PagePoint(50f, 160f), placement.origin)
        assertEquals(5f, placement.xHeightPt)
        assertEquals(20f, placement.linePitchPt)
        assertEquals(0.2f, placement.slantRad)
        assertEquals(blue, placement.pen)
        // It wraps at the paragraph's right edge, not the panel's width.
        assertEquals(250f, placement.wrapWidthPt)
    }

    @Test
    fun `dropped mid line - it keeps its own x and still lands on a baseline`() {
        val placement = Placements.resolve(PagePoint(200f, 118f), PlaceMode.DROP, written, hand, wrapWidthPt = 400f)

        assertEquals(PagePoint(200f, 120f), placement.origin)
    }

    @Test
    fun `a tap continues the nearest paragraph on the line after its last`() {
        val placement = Placements.resolve(PagePoint(200f, 150f), PlaceMode.TAP, written, hand, wrapWidthPt = 400f)

        assertEquals(PagePoint(50f, 160f), placement.origin)
        assertEquals(PlacementSource.PARAGRAPH, placement.source)
    }

    @Test
    fun `beside the big black writing - the phrase is big and black`() {
        val placement = Placements.resolve(PagePoint(60f, 425f), PlaceMode.DROP, written, hand, wrapWidthPt = 400f)

        assertEquals(9f, placement.xHeightPt)
        assertEquals(black, placement.pen)
        assertEquals(-0.05f, placement.slantRad)
    }

    @Test
    fun `away from any paragraph - it takes the page's size and the nearest pen`() {
        val placement = Placements.resolve(PagePoint(500f, 700f), PlaceMode.DROP, written, hand, wrapWidthPt = 400f)

        assertEquals(PlacementSource.PAGE, placement.source)
        assertEquals(PagePoint(500f, 700f), placement.origin)
        assertEquals(5f, placement.xHeightPt)
        assertEquals(black, placement.pen)
    }

    @Test
    fun `on empty ruled paper the baseline snaps to a rule - in the hand's own size`() {
        val ruling = SnapGrid(spacingXPt = 0f, spacingYPt = 24f, originYPt = 36f, drawnByThePaper = true)
        val ruled = PageWriting.empty(ruling)
        val placement = Placements.resolve(PagePoint(100f, 95f), PlaceMode.DROP, ruled, hand, wrapWidthPt = 400f)

        assertEquals(PlacementSource.RULING, placement.source)
        assertEquals(PagePoint(100f, 84f), placement.origin)
        assertEquals(24f, placement.linePitchPt)
        // The user's size, not the ruling's: on the tablet the ruling's 9.6 pt was twice the hand.
        assertEquals(hand.xHeightPt, placement.xHeightPt, 1e-4f)
        assertNull(placement.pen)
    }

    @Test
    fun `a hand too big for the ruling is made to fit between two rules`() {
        val big = HandStats(12f, 0.1f, 0f, emptyMap(), emptyList(), emptyList(), 100, emptyMap(), emptyMap())
        val ruling = SnapGrid(spacingXPt = 0f, spacingYPt = 24f, originYPt = 36f, drawnByThePaper = true)
        val placement = Placements.resolve(PagePoint(100f, 95f), PlaceMode.DROP, PageWriting.empty(ruling), big, 400f)

        assertEquals(24f * 0.3f, placement.xHeightPt, 1e-4f)
        assertEquals(24f, placement.linePitchPt)
    }

    @Test
    fun `on an empty blank page it takes the profile's own size - in the current pen`() {
        val blank = PageWriting.empty(SnapGrid.forBackground(Background.Blank))
        val placement = Placements.resolve(PagePoint(100f, 95f), PlaceMode.DROP, blank, hand, wrapWidthPt = 400f)

        assertEquals(PlacementSource.PROFILE, placement.source)
        assertEquals(hand.xHeightPt, placement.xHeightPt)
        assertNull(placement.pen)
    }

    @Test
    fun `match the note off ignores the page`() {
        val placement = Placements.resolve(PagePoint(60f, 165f), PlaceMode.DROP, written, hand, 400f, matchNote = false)

        assertEquals(PlacementSource.PROFILE, placement.source)
        assertEquals(PagePoint(60f, 165f), placement.origin)
        assertNull(placement.pen)
    }

    @Test
    fun `two columns are two paragraphs`() {
        val columns = listOf(
            line(100f, 50f, 250f, 5f, blue),
            line(100f, 320f, 520f, 5f, black),
            line(120f, 50f, 250f, 5f, blue),
            line(120f, 320f, 520f, 5f, black),
        )

        assertEquals(2, Placements.paragraphs(columns).size)
    }

    @Test
    fun `generated ink is not the user's writing and is not measured`() = runTest {
        val generated = ObjectId("g")
        val ids = listOf("a", "b", "c", "g").map(::ObjectId)
        val page = FoldedPage(
            pageId = PageId("p"),
            canvas = PageCanvas.fixed(PageSize.Letter),
            background = Background.Blank,
            objects = ids.mapIndexed { i, id ->
                StrokeObject(id, StrokeGeometry(byteArrayOf(i.toByte())), z = FractionalKey("${'a' + i}"))
            },
            deleted = emptySet(),
            throughClock = null,
            opCount = 0,
            formats = mapOf(
                generated to GeneratedInk("phrase", GeneratedInk.ASSEMBLY).applyTo(ObjectFormat()).encode(),
            ),
        )
        val sampled = ids.mapIndexed { i, id -> letterAt(id, 50f + i * 12f) }
        val sampler = StrokeSampler { sampled }
        val reader = StrokeReader { _, wanted ->
            wanted.associateWith { id ->
                val pen = if (id == generated) black else blue
                val trajectory = PenTrajectory.of(
                    floatArrayOf(0f, 1f),
                    floatArrayOf(0f, 1f),
                    intArrayOf(0, 1),
                    null,
                    null,
                    intArrayOf(2),
                )
                CapturedStroke(id, assertNotNull(trajectory), pen, null)
            }
        }

        val writing = PageWriting.measure(page, sampler, reader, emptyMap(), HarvestPolicy(learnFromOlderNotes = true))

        assertEquals(3, writing.lines.single().strokes)
        assertEquals(blue, writing.lines.single().pen)
    }

    /** A letter-sized zig-zag at [left], baseline 100, x-height 6. */
    private fun letterAt(id: ObjectId, left: Float): SampledStroke {
        val points = listOf(0f to 100f, 2f to 94f, 4f to 100f, 6f to 94f, 8f to 100f)
        val samples = points.mapIndexed { i, (x, y) -> InkSample(left + x, y, i * 10L) }
        return SampledStroke(id, samples, PageRect(left, 94f, left + 8f, 100f))
    }
}
