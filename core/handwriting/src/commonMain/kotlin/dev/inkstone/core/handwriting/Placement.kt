package dev.inkstone.core.handwriting

import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PenStyle
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.model.SnapGrid
import dev.inkstone.core.model.StrokeReader
import dev.inkstone.core.model.StrokeSampler
import dev.inkstone.core.model.TextLine
import dev.inkstone.core.search.LineSegmenter
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One line of the user's own writing on a page, measured: its geometry ([LineStyle]), the pen it was
 * mostly written with, and the spaces between its words.
 *
 * @property wordGaps the gaps between neighbouring words on the line, in the line's x-heights.
 */
public class MeasuredLine(
    public val style: LineStyle,
    public val pen: PenStyle?,
    public val strokes: Int,
    public val wordGaps: List<Float> = emptyList(),
)

/**
 * ⭐ D15's measurement of a page: the lines **the user wrote** on it, and its paper. Generated ink,
 * Elements, ruled lines and highlighter are not the user's hand and are left out, so a phrase
 * dropped beside an earlier phrase matches the writing, not the earlier phrase.
 *
 * Built off the UI thread from the same sampler and segmenter M7 uses, which is the point of §5.2's
 * *"one estimator, two callers"*: the harvester normalises with exactly these lines.
 */
public class PageWriting(public val lines: List<MeasuredLine>, public val grid: SnapGrid) {

    /** The page as a whole, for the notes and for the fallbacks in [Placements]. */
    public val style: PageStyle by lazy { PageStyle.of(lines.map { it.style }) }

    /** Whether the paper is ruled: a horizontal lattice drawn by the paper, and no vertical one. */
    public val ruled: Boolean get() = grid.drawnByThePaper && grid.snapsY && !grid.snapsX

    public companion object {
        /** How many strokes per line are read for their pen: enough to outvote a stray colour. */
        private const val PEN_SAMPLES = 8

        public fun empty(grid: SnapGrid): PageWriting = PageWriting(emptyList(), grid)

        public suspend fun measure(
            page: FoldedPage,
            sampler: StrokeSampler,
            reader: StrokeReader,
            ledger: Map<ObjectId, LedgerEntry>,
            policy: HarvestPolicy,
        ): PageWriting {
            val grid = SnapGrid.forBackground(page.background, page.canvas.left, page.canvas.top)
            val theirs = sampler.sample(page).filter { stroke ->
                Harvester.ineligibility(page, stroke.id, ledger, policy).let { it == null || it == SkipReason.ERASED }
            }
            if (theirs.isEmpty()) return empty(grid)
            val measured = writingLines(
                LineSegmenter.segment(theirs).lines.mapNotNull { line ->
                    LineStyles.measure(line.strokes)?.let { line to it }
                },
            )
            val pens = reader.read(page, measured.flatMap { (line, _) -> line.strokes.take(PEN_SAMPLES).map { it.id } })
            val lines = measured.map { (line, style) ->
                MeasuredLine(
                    style = style,
                    pen = dominantPen(line.strokes.mapNotNull { pens[it.id]?.pen }),
                    strokes = line.strokes.size,
                    wordGaps = wordGapsOf(line.strokes, style.xHeightPt),
                )
            }
            return PageWriting(lines, grid)
        }

        /**
         * ⭐ The lines that are writing, not marks.
         *
         * The segmenter hands back every cluster it finds, and on a dense page many are not lines of
         * writing at all: the subscripts under `Rs = R1 + R2`, an arrowhead, a dot. On the physics note a
         * tenth of its 417 "lines" measured under 0.8 pt tall. Kept, they become paragraphs of their own,
         * and a phrase dropped just below an equation continued the subscripts at an x-height of 1.85 pt,
         * a quarter of the writing beside it — found on the emulator, the first time D15 met a real
         * mind map. A line is writing when it has more than one stroke, is wider than it is tall, and
         * stands at least [MIN_X_HEIGHT_SHARE] of the page's median x-height.
         */
        public fun writingLines(lines: List<Pair<TextLine, LineStyle>>): List<Pair<TextLine, LineStyle>> {
            if (lines.isEmpty()) return lines
            // Weighted by strokes: writing has many and its marks few, so a page dense with subscripts
            // cannot pull the yardstick down to their size.
            val typical = median(lines.flatMap { (line, style) -> List(line.strokes.size) { style.xHeightPt } })
            return lines.filter { (line, style) ->
                line.strokes.size >= MIN_LINE_STROKES &&
                    style.bounds.width >= style.xHeightPt * MIN_LINE_WIDTH_X_HEIGHTS &&
                    style.xHeightPt >= typical * MIN_X_HEIGHT_SHARE
            }
        }

        private const val MIN_LINE_STROKES = 2
        private const val MIN_LINE_WIDTH_X_HEIGHTS = 1.5f
        private const val MIN_X_HEIGHT_SHARE = 0.45f

        /**
         * The spaces between words on one line, in x-heights: the gap from the furthest right any
         * stroke so far reached to the next stroke's left (*furthest*, because a `t` is crossed after
         * its word), kept when it is wider than a letter gap and narrower than a gutter.
         */
        internal fun wordGapsOf(strokes: List<SampledStroke>, xHeight: Float): List<Float> {
            if (xHeight <= 0f) return emptyList()
            val gaps = ArrayList<Float>()
            var reached = Float.NaN
            for (stroke in strokes.sortedBy { it.bounds.left }) {
                if (!reached.isNaN()) {
                    val gap = (stroke.bounds.left - reached) / xHeight
                    if (gap in MIN_WORD_GAP..MAX_WORD_GAP) gaps += gap
                }
                reached = if (reached.isNaN()) stroke.bounds.right else maxOf(reached, stroke.bounds.right)
            }
            return gaps
        }

        /** Narrower than this is a gap between letters, in x-heights. */
        private const val MIN_WORD_GAP = 0.6f

        /** Wider than this is a gutter or a gap in a mind map, not a word space. */
        private const val MAX_WORD_GAP = 4f

        /** The pen most of [pens] share by kind and colour, at the median width those had. */
        internal fun dominantPen(pens: List<PenStyle>): PenStyle? {
            val groups = pens.groupBy { it.kind to it.colorArgb }
            val (key, members) = groups.maxByOrNull { it.value.size } ?: return null
            return PenStyle(key.first, key.second, median(members.map { it.widthPt }))
        }
    }
}

/** Where D15's numbers came from — reported in the panel and in the phase notes. */
public enum class PlacementSource {
    /** The paragraph the phrase continues. */
    PARAGRAPH,

    /** The user's writing elsewhere on the page. */
    PAGE,

    /** The paper's ruling. */
    RULING,

    /** The profile's medians: nothing on the page to match, or *Match the note* is off. */
    PROFILE,
}

/** Whether a phrase was dragged to a point or the page was tapped (§6.2). */
public enum class PlaceMode { DROP, TAP }

/**
 * Where a phrase goes and how it is written there.
 *
 * @property origin the first line's **left baseline point**: text aligns by its baseline, not its
 *   box (§6.2).
 * @property pen the pen to write with, or null for the current one.
 * @property paragraph the box of the paragraph it continues, when it continues one.
 */
public data class Placement(
    val origin: PagePoint,
    val xHeightPt: Float,
    val slantRad: Float,
    val angleRad: Float,
    val linePitchPt: Float,
    val wrapWidthPt: Float,
    val pen: PenStyle?,
    val source: PlacementSource,
    val paragraph: PageRect? = null,
    val wordGapXHeights: Float? = null,
) {
    public fun style(neatness: Float): WriteStyle =
        WriteStyle(xHeightPt, slantRad, angleRad, wrapWidthPt, linePitchPt, neatness, wordGapXHeights)
}

/**
 * ⭐ D15 — *"drag and drop a piece of text in and it looks just like you did it (stays consistent
 * with the note)"*.
 *
 * ## The rules, in the order they are tried
 *
 * 1. **Beside a paragraph, continue it.** Lines of the user's writing that sit one above the other
 *    form a paragraph ([paragraphs]). A phrase dropped on or just below one lands on that paragraph's
 *    line lattice — its baseline a whole number of the paragraph's pitches from its first line — at
 *    its left margin if it was dropped near the margin, and wraps at its right edge. It takes the
 *    paragraph's pen, x-height, slant and baseline angle. A **tap** continues the nearest paragraph on
 *    the line after its last.
 * 2. **Elsewhere on a written page**, the phrase lands where it was dropped, at the page's median
 *    x-height and slant, in the pen of the nearest paragraph — a paragraph, not the nearest few lines,
 *    because a paragraph is one sitting with one pen, and three lines of a long paragraph would
 *    otherwise outvote the single line right beside the drop.
 * 3. **On ruled paper with no writing**, the baseline snaps to the nearest rule and lines follow the
 *    ruling, at the user's **own** size ([HandStats.xHeightPt]) — never bigger than fits between two
 *    rules ([RULED_MAX_X_HEIGHT]). *(Until the tablet, the size followed the ruling: 0.4 of the
 *    spacing, 9.6 pt on 24 pt rules, for a hand that writes at 3.5–5.6 pt and ignores the rules. The
 *    phrase came out twice the user's size and its second line ran into its first.)*
 * 4. **Otherwise**, the profile's own median size and slant, in the current pen.
 *
 * *Match the note* off skips all of it: the drop point, the current pen, the profile's medians.
 */
public object Placements {

    @Suppress("LongParameterList")
    public fun resolve(
        point: PagePoint,
        mode: PlaceMode,
        writing: PageWriting,
        hand: HandStats,
        wrapWidthPt: Float,
        matchNote: Boolean = true,
    ): Placement {
        if (!matchNote) return byProfile(point, hand, wrapWidthPt)
        val paragraphs = paragraphs(writing.lines)
        val continued = when (mode) {
            PlaceMode.DROP -> paragraphs.filter { it.reaches(point) }.minByOrNull { it.distanceTo(point) }
            PlaceMode.TAP -> paragraphs.filter { it.near(point) }.minByOrNull { it.distanceTo(point) }
        }
        if (continued != null) return continuing(continued, point, mode, wrapWidthPt, writing)
        val page = writing.style
        val pageXHeight = page.xHeightPt
        return when {
            pageXHeight != null -> Placement(
                origin = point,
                xHeightPt = pageXHeight,
                slantRad = median(writing.lines.map { it.style.slantRad }),
                angleRad = median(writing.lines.map { it.style.angleRad }),
                linePitchPt = pitchOf(paragraphs) ?: (pageXHeight * DEFAULT_PITCH_X_HEIGHTS),
                wrapWidthPt = wrapWidthPt,
                pen = paragraphs.minByOrNull { it.distanceTo(point) }?.let { nearest ->
                    PageWriting.dominantPen(nearest.lines.mapNotNull { it.pen })
                },
                source = PlacementSource.PAGE,
                wordGapXHeights = gapOf(writing.lines),
            )

            writing.ruled -> {
                val spacing = writing.grid.spacingYPt
                Placement(
                    origin = PagePoint(point.x, writing.grid.snapY(point.y)),
                    xHeightPt = min(hand.xHeightPt, spacing * RULED_MAX_X_HEIGHT),
                    slantRad = hand.slantRad,
                    angleRad = 0f,
                    linePitchPt = spacing,
                    wrapWidthPt = wrapWidthPt,
                    pen = null,
                    source = PlacementSource.RULING,
                )
            }

            else -> byProfile(point, hand, wrapWidthPt)
        }
    }

    private fun byProfile(point: PagePoint, hand: HandStats, wrapWidthPt: Float): Placement = Placement(
        origin = point,
        xHeightPt = hand.xHeightPt,
        slantRad = hand.slantRad,
        angleRad = 0f,
        linePitchPt = hand.xHeightPt * DEFAULT_PITCH_X_HEIGHTS,
        wrapWidthPt = wrapWidthPt,
        pen = null,
        source = PlacementSource.PROFILE,
    )

    private fun continuing(
        paragraph: Paragraph,
        point: PagePoint,
        mode: PlaceMode,
        wrapWidthPt: Float,
        writing: PageWriting,
    ): Placement {
        val xh = paragraph.xHeight
        val pitch = paragraph.pitch ?: pitchOf(paragraphs(writing.lines)) ?: (xh * DEFAULT_PITCH_X_HEIGHTS)
        val first = paragraph.lines.first().style
        val x = when {
            mode == PlaceMode.TAP -> paragraph.margin
            abs(point.x - paragraph.margin) <= xh * MARGIN_SNAP_X_HEIGHTS -> paragraph.margin
            else -> point.x
        }
        val step = when (mode) {
            PlaceMode.TAP -> paragraph.lines.size
            PlaceMode.DROP -> ((point.y - first.baselineY(x)) / pitch).roundToInt().coerceAtLeast(0)
        }
        val room = paragraph.right - x
        return Placement(
            origin = PagePoint(x, first.baselineY(x) + step * pitch),
            xHeightPt = xh,
            slantRad = median(paragraph.lines.map { it.style.slantRad }),
            angleRad = median(paragraph.lines.map { it.style.angleRad }),
            linePitchPt = pitch,
            wrapWidthPt = if (room >= xh * MIN_ROOM_X_HEIGHTS) room else wrapWidthPt,
            pen = PageWriting.dominantPen(paragraph.lines.mapNotNull { it.pen }),
            source = PlacementSource.PARAGRAPH,
            paragraph = paragraph.box,
            wordGapXHeights = gapOf(paragraph.lines) ?: gapOf(writing.lines),
        )
    }

    /** The median word space of [lines], when they hold enough to say. */
    private fun gapOf(lines: List<MeasuredLine>): Float? =
        lines.flatMap { it.wordGaps }.takeIf { it.size >= MIN_WORD_GAPS }?.let(::median)

    private fun pitchOf(paragraphs: List<Paragraph>): Float? =
        paragraphs.mapNotNull { it.pitch }.takeIf { it.isNotEmpty() }?.let(::median)

    /**
     * Lines grouped into paragraphs: a line joins the paragraph whose last line sits a plausible line
     * pitch above it and overlaps it sideways. A page in two columns therefore makes two paragraphs
     * side by side, and a mind map makes many small ones — which is right, because a label continued
     * is continued where it is.
     */
    internal fun paragraphs(lines: List<MeasuredLine>): List<Paragraph> =
        paragraphGroups(lines.map { it.style }).map { group -> Paragraph(group.map { lines[it] }) }

    internal class Paragraph(val lines: List<MeasuredLine>) {
        val xHeight: Float = median(lines.map { it.style.xHeightPt })
        val margin: Float = median(lines.map { it.style.bounds.left })
        val right: Float = lines.maxOf { it.style.bounds.right }
        val pitch: Float? = lines.zipWithNext { a, b ->
            b.style.baselineY(b.style.bounds.centreX) - a.style.baselineY(a.style.bounds.centreX)
        }.takeIf { it.isNotEmpty() }?.let(::median)
        val box: PageRect = lines.map { it.style.bounds }.reduce(PageRect::union)
        private val lastBaseline: Float = lines.last().style.let { it.baselineY(it.bounds.centreX) }

        /** A drop on the paragraph or within a line and a half below it continues it. */
        fun reaches(point: PagePoint): Boolean {
            val step = pitch ?: (xHeight * DEFAULT_PITCH_X_HEIGHTS)
            val side = xHeight * SIDE_REACH_X_HEIGHTS
            return point.x in (box.left - side)..(box.right + side) &&
                point.y in (box.top - xHeight)..(lastBaseline + step * BELOW_REACH_PITCHES)
        }

        /** A tap anywhere around the paragraph, within a few lines of it. */
        fun near(point: PagePoint): Boolean {
            val step = pitch ?: (xHeight * DEFAULT_PITCH_X_HEIGHTS)
            return distance(box, point) <= step * TAP_REACH_PITCHES
        }

        fun distanceTo(point: PagePoint): Float = distance(box, point)
    }

    private fun distance(box: PageRect, point: PagePoint): Float {
        val dx = maxOf(box.left - point.x, 0f, point.x - box.right)
        val dy = maxOf(box.top - point.y, 0f, point.y - box.bottom)
        return kotlin.math.hypot(dx, dy)
    }

    /**
     * Line pitch as a multiple of x-height, when nothing measured one. An estimate: handwriting on
     * unruled paper sits at about three x-heights from baseline to baseline.
     */
    private const val DEFAULT_PITCH_X_HEIGHTS = 3.2f

    /**
     * The biggest x-height that fits between two rules: a hand's tall letters reach about 1.6
     * x-heights and its descenders about 1.15 below the line (the tablet's profile, §18.7), so 2.75
     * x-heights of ink, plus a little air, in one spacing.
     */
    private const val RULED_MAX_X_HEIGHT = 0.3f
    private const val MARGIN_SNAP_X_HEIGHTS = 3f
    private const val MIN_ROOM_X_HEIGHTS = 8f
    private const val SIDE_REACH_X_HEIGHTS = 3f
    private const val BELOW_REACH_PITCHES = 1.5f
    private const val TAP_REACH_PITCHES = 4f
    private const val MIN_WORD_GAPS = 3
}
