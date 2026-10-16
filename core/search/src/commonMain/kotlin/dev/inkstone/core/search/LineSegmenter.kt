package dev.inkstone.core.search

import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.model.Segmentation
import dev.inkstone.core.model.SkipReason
import dev.inkstone.core.model.SkippedRegion
import dev.inkstone.core.model.TextLine
import dev.inkstone.core.model.isNearHorizontal
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ⭐ SPEC §8.2 — **group a page's strokes into lines before anything recognises them.**
 *
 * ML Kit's recogniser assumes a *single line of text* (SPEC §8.1). Handing it a whole page
 * as one `Ink` does not degrade gracefully; it produces garbage, and it is the single most
 * commonly missed detail in every implementation of this. `docs/M7-RECOGNITION.md` has the
 * measured pair of numbers on a 20-page sample.
 *
 * Pure, and deliberately in `:core:search` rather than `:android:ocr`, so that the decision
 * this milestone turns on can be unit-tested on plain data with no emulator, no ML Kit and no
 * `androidx.ink` native code. Extracting the samples needs a device (SPEC §5.4's blob is a
 * native `StrokeInputBatch`); *choosing the lines* does not, and CLAUDE.md's standing lesson
 * is to hoist the decision out to where a test can reach it.
 *
 * ## The algorithm, and why each step is not the obvious one
 *
 * 1. **Scale comes from the median stroke height, not from the page.** Handwriting size is a
 *    property of the writer, not of the paper; a fixed threshold in points works for one
 *    person and merges every line for another.
 * 2. **Non-writing strokes are removed first** — see [isWriting]. They are the strokes that
 *    would otherwise chain two lines into one: a bracket spanning four lines, an underline
 *    running the width of the page.
 * 3. **Lines are connected components under vertical overlap and a shared baseline**, not
 *    clusters of centre-y. A centre-based rule puts `l` and `o` on different lines because their
 *    centres genuinely differ by a third of an x-height; overlap normalised by the *shorter* box
 *    does not care. See [sameLine].
 * 4. **Two strokes join only if they are horizontally near, within a gutter's width of each
 *    other.** That width is a multiple of the page's typical *line* height, which a first pass
 *    by vertical overlap alone measures ([typicalLineHeight]). See [rows] for why this is
 *    version 2's whole fix. Pieces of one line that no pair of strokes joined, at a descender on a
 *    word boundary, are joined by their median baselines ([mergeNeighbours]).
 * 5. **Rotated writing is detected and skipped** ([rotatedRuns], [isSlanted]), and returned in
 *    [Segmentation.skipped] rather than dropped, so *"nothing was searchable"* and *"this page is
 *    a diagram"* are different answers.
 * 6. **A stroke left on its own joins the line it sits on, above or below** ([attach]): an
 *    i-dot, a subscript, a capital whose foot missed the baseline. It joins one line and never
 *    links two, which is why it is a step of its own and not a rule in [sameLine].
 * 7. **Lines come back top to bottom.** On a page with columns that interleaves them, and that is
 *    harmless: each line is recognised on its own, and search does not care about order. The one
 *    thing order did decide was the recogniser's context, the previous line's text. That is now
 *    [contextLineFor], the line directly above in the same column.
 */
public object LineSegmenter {

    /**
     * Which segmentation drew a page's lines. It is part of the recogniser's id
     * (`MlKitRecogniser.id`), so text read from lines an older version drew is read again once.
     * Bump it whenever a change would draw different lines on some page.
     *
     * - **1** was M7.
     * - **2** read columns and mind maps (docs/LINE-SEGMENTATION-V2.md). Version 1 joined
     *   strokes across a page without any horizontal limit, so a two-column page was read
     *   straight across its gutter, and the columns' lines chained down the page in a staircase.
     *   It then skipped any page it could tell had columns, so a mind map was not read at all.
     *   Version 2 also stopped a dot or a bar linking two lines ([attach]), measured big writing
     *   against itself ([sameLine]), joined a line split at a descender ([mergeNeighbours]) and
     *   stopped calling a stack of fractions a margin note ([rotatedRuns], [isSlanted]).
     */
    public const val VERSION: Int = 2

    /**
     * @param strokes one page's recognisable strokes, in draw order. Highlighter and other
     *   non-ink objects must already be gone — that is `StrokeSampler`'s job, because deciding
     *   what a brush *is* needs `:android:ink`.
     */
    public fun segment(strokes: List<SampledStroke>): Segmentation {
        if (strokes.isEmpty()) return Segmentation.Empty

        val scale = medianHeight(strokes)
        val writing = ArrayList<SampledStroke>(strokes.size)
        val skipped = ArrayList<SkippedRegion>()
        for (stroke in strokes) {
            if (isWriting(stroke.bounds, scale)) writing += stroke else skipped += skip(listOf(stroke), NOT_WRITING)
        }
        if (writing.isEmpty()) return Segmentation(emptyList(), skipped)

        // Re-derived from the writing alone: on a page that is mostly diagram, the first
        // median is the diagram's and every real line would be measured against it.
        val textScale = medianHeight(writing)
        // A first pass by vertical overlap alone, only to learn how tall a line is on this page.
        val lineHeight = typicalLineHeight(rows(writing, textScale, maxGap = Float.MAX_VALUE), textScale)
        val gutter = lineHeight * GUTTER_FACTOR
        val groups = mergeNeighbours(
            rows(writing, textScale, maxGap = gutter).flatMap { row -> splitAtGutters(row) },
            textScale,
            gutter,
        )

        // One skipped region per *run*, not per stroke: a note written up the margin is one
        // thing the user did, and reporting it as eleven skipped letters would be true and
        // useless.
        val turned = rotatedRuns(groups, gutter)
        for (run in turned) skipped += skip(run, SkipReason.ROTATED)
        val turnedIds = turned.flatMapTo(HashSet()) { run -> run.map { it.id } }

        // ⚠️ **SPEC §8.2 step 3 says "strokes left-to-right within" a line. Measured, that is
        // wrong for the recogniser and it has been corrected.** Sorting by x is right for
        // laying words out and it scrambles the *writing* order the model was trained on: a
        // `t` is crossed and an `i` dotted after the word is finished, so x-order moves those
        // marks into the middle of the word — precisely SPEC §8.1's "out-of-order strokes
        // degrade results", applied by us on purpose. On the 20-page sample, keeping draw
        // order lifted word recall from 0.313 to 0.378 for no cost.
        //
        // So the strokes keep the order they arrived in, and only the *lines* are sorted
        // spatially.
        val order = HashMap<ObjectId, Int>(strokes.size)
        for ((index, stroke) in strokes.withIndex()) order[stroke.id] = index

        val kept = ArrayList<List<SampledStroke>>(groups.size)
        val loose = ArrayList<SampledStroke>()
        for (group in groups) {
            when {
                group.any { it.id in turnedIds } -> Unit
                isSlanted(group, textScale) -> skipped += skip(group, SkipReason.ROTATED)
                group.size == 1 -> loose += group.single()
                else -> kept += group
            }
        }
        val lines = attach(loose, kept, textScale, gutter).map { line ->
            lineOf(line.sortedBy { order[it.id] ?: 0 })
        }
        return Segmentation(lines.sortedBy { it.writingArea.centreY }, skipped)
    }

    /**
     * The line whose text is [line]'s recognition context: the nearest of [lines] *above* it that it
     * overlaps horizontally, by at least [CONTEXT_OVERLAP] of the narrower of the two, within
     * [CONTEXT_GAP_LINES] of its own height. Null when there is none.
     *
     * The previous line in page order is the wrong answer as soon as a page has columns: it is
     * usually the line *beside* this one, across the gutter. ML Kit treats the context as the text
     * the writer had just written, so a wrong one is worse than none (SPEC §8.2 step 4).
     */
    public fun contextLineFor(line: TextLine, lines: List<TextLine>): TextLine? {
        val area = line.writingArea
        return lines
            .filter { other ->
                val above = other.writingArea
                above.bottom <= area.centreY &&
                    area.top - above.bottom <= area.height * CONTEXT_GAP_LINES &&
                    horizontalOverlapShare(above, area) >= CONTEXT_OVERLAP
            }
            .maxByOrNull { it.writingArea.bottom }
    }

    // -----------------------------------------------------------------------
    // Scale
    // -----------------------------------------------------------------------

    /**
     * The median stroke height, floored so a page of dots and commas cannot produce a zero
     * scale and divide every threshold by nothing.
     */
    private fun medianHeight(strokes: List<SampledStroke>): Float {
        val heights = strokes.map { it.bounds.height }.sorted()
        val median = heights[heights.size / 2]
        return max(median, MIN_SCALE_PT)
    }

    /**
     * Whether a stroke is plausibly part of a word.
     *
     * Two rejections, and both are about strokes that would *join* things rather than about
     * strokes that are merely odd. A tall stroke overlaps several lines' bands at once and
     * fuses them; a long flat stroke overlaps every word on its line and reaches into the next
     * column. Either one turns a clean page into a single component.
     *
     * ⚠️ **Each test has an absolute bound as well as a relative one**, because the relative one
     * is measured against the page's own median and a page that is *mostly* diagram has a median
     * that is the diagram's. On a rectangle sketched alone on a page, every edge is within a
     * factor of the median of the other edges, so nothing is oversize relative to anything —
     * which is how three strokes forming a box came to be handed to the recogniser as a word.
     * An inch and a half is not a letter whatever else is on the page.
     */
    private fun isWriting(bounds: PageRect, scale: Float): Boolean {
        if (bounds.height > MAX_LETTER_HEIGHT_PT) return false
        if (bounds.height > scale * OVERSIZE_HEIGHT_FACTOR) return false
        val flat = bounds.height < scale * FLAT_HEIGHT_FRACTION || bounds.height < MIN_LETTER_HEIGHT_PT
        val long = bounds.width > scale * RULE_WIDTH_FACTOR || bounds.width > MAX_RULE_WIDTH_PT
        return !(flat && long)
    }

    // -----------------------------------------------------------------------
    // Lines
    // -----------------------------------------------------------------------

    /**
     * ⭐ **Rows: strokes joined by vertical overlap, and only when they are within [maxGap] of each
     * other horizontally.**
     *
     * **Version 1 had no horizontal test here at all, and that was the bug**
     * (docs/LINE-SEGMENTATION-V2.md). Its first draft had refused to join strokes more than a few median
     * *stroke* heights apart.
     * On 20 real pages that split every line into its words (632 lines where there were 472), because
     * a word space is a fraction of the *line* height, about three times a stroke's. So the test was
     * removed, and the horizontal question was left to [splitAtGutters], after the row existed. But by
     * then the damage is done:
     * - A line in one column joins the line beside it in the other.
     * - That line, at a different pitch, joins the *next* line of the first column.
     * - Union-find chains the staircase down the page.
     *
     * On the user's two-column chemistry page that fused three and four lines at a time, across both
     * columns. The row was then several lines tall, so [splitAtGutters]'s threshold, a multiple of the
     * row's own height, was wider than the gutter it should have found.
     *
     * The fix keeps version 1's lesson about the unit and puts the test back where the chaining
     * happens. [maxGap] is a multiple of the page's typical *line* height, which a first pass with no
     * limit at all measures ([typicalLineHeight]). Within a line almost every word space is far
     * narrower, so a line stays connected through its own neighbours. Nothing can bridge an empty
     * gutter, because a link needs two strokes that are themselves within [maxGap].
     *
     * The errors are deliberately on the side of splitting. The widest word spaces on the user's pages
     * are about two line heights, and a line cut there is two pieces each read on its own: a little
     * context lost for the recogniser, nothing for search. A line read across a gutter loses both, and
     * puts its words' boxes in the wrong column.
     *
     * Union-find over a window rather than all pairs: strokes are sorted by `top`, so once a
     * candidate's `top` is more than a line below the current stroke's `bottom` it cannot
     * overlap this one or any later one and the scan stops. A 2 000-stroke lecture page stays
     * near-linear instead of four million comparisons.
     */
    private fun rows(strokes: List<SampledStroke>, scale: Float, maxGap: Float): List<List<SampledStroke>> {
        val sorted = strokes.sortedBy { it.bounds.top }
        val parent = IntArray(sorted.size) { it }

        for (i in sorted.indices) {
            val a = sorted[i].bounds
            for (j in i + 1 until sorted.size) {
                val b = sorted[j].bounds
                if (b.top > a.bottom + scale) break
                if (a.horizontalGapTo(b) <= maxGap && sameLine(a, b, scale)) union(parent, i, j)
            }
        }

        val groups = LinkedHashMap<Int, MutableList<SampledStroke>>()
        for (i in sorted.indices) groups.getOrPut(find(parent, i)) { ArrayList() } += sorted[i]
        return groups.values.toList()
    }

    /**
     * How tall a line of writing is on this page: the median height of [rows] holding more than
     * one stroke.
     *
     * A one-stroke row is a dot, a stray mark or a lone letter. It says nothing about a line's
     * height, and on a sparse page such rows outnumber the lines. A row fused from several lines
     * is taller than a line, which is why this is a median: most rows are single lines, even on the
     * pages that needed version 2.
     */
    private fun typicalLineHeight(rows: List<List<SampledStroke>>, scale: Float): Float {
        val heights = rows
            .filter { it.size > 1 }
            .map { row -> row.maxOf { it.bounds.bottom } - row.minOf { it.bounds.top } }
            .sorted()
        if (heights.isEmpty()) return scale * LONE_LINE_HEIGHT
        return max(heights[heights.size / 2], scale)
    }

    /**
     * ⭐ **Vertical overlap *and* baseline proximity** — SPEC §8.2 asks for both, and the second
     * one is not optional.
     *
     * Overlap alone merges tightly-spaced lines, and the mechanism is worth stating because it
     * is not obvious: overlap is normalised by the **shorter** box (it has to be, or an `o`
     * beside an `l` starts its own line), so an `o` on one line that happens to sit inside the
     * vertical span of an `f` descending from the line above scores a perfect 1.0. One such pair
     * chains two lines, and union-find then chains the third. Measured on 20 real pages, overlap
     * alone found **304 lines where there were 472** — whole paragraphs collapsing into single
     * "lines" three deep.
     *
     * The baseline is the stable feature of handwriting: `o`, `n`, `l` and `h` all finish on it,
     * ascenders rise above it and descenders fall below it by less than the gap to the next
     * line. So two strokes belong to the same line when their **bottoms** agree to within
     * [BASELINE_TOLERANCE] — which is wide enough for a `g` and narrower than a line's pitch.
     *
     * The tolerance is measured in the page's text scale, **or in the smaller stroke's own height
     * when that is larger.** A heading written three times the size of the notes under it wobbles
     * three times as far off its baseline, and measured against the notes' scale its letters fell
     * apart into fragments. The tolerance only ever grows, so small writing is judged exactly as it
     * was in version 1.
     *
     * ⚠️ **Version 1 had a second way in, and it chained lines.** A dot, a `t` bar or an accent
     * shares no baseline with anything, so a small mark also joined any neighbour whose height it
     * sat within. Between two close lines, an i-dot sits in the band of a descender from the line
     * above *and* of an ascender from the line below, so one dot joined both and fused the two
     * lines. On the user's chemistry page that read *"How to work out what"* and the line under it
     * as one line. Marks now join their line afterwards, in [attach], which can join a mark to one
     * line and cannot join two lines to each other.
     */
    private fun sameLine(a: PageRect, b: PageRect, scale: Float): Boolean {
        if (a.verticalOverlap(b) < MIN_VERTICAL_OVERLAP) return false
        val size = max(scale, min(a.height, b.height))
        return abs(a.bottom - b.bottom) <= size * BASELINE_TOLERANCE
    }

    /**
     * ⭐ **Join the pieces of one line that [rows] left apart**: groups of two or more strokes side by
     * side, within [gutter] of each other, on one baseline.
     *
     * [rows] links two strokes, and a word boundary can leave no pair that qualifies. In the user's
     * *"Equations with squareds"*, the `q` of *Eq* hangs too far below the `u` beside it for their
     * bottoms to agree, and the `E` before it is 26 pt from that `u`, past the 21 pt gutter. Version 1
     * read that heading whole only because it had no gutter at all; the first draft of version 2 read
     * *"Eq"* and *"uations with squareds"*, and a search for *equations* found neither. A group's
     * baseline is the **median** of its strokes' bottoms, so one descender cannot move it, and the
     * tolerance is [sameLine]'s.
     *
     * Two groups may overlap horizontally by no more than half the narrower one's width: more than
     * that is one group above the other, not beside it. A lone stroke is not merged here but attached
     * in [attach], as a leaf.
     *
     * Measured over the user's 183 pages, this cut the suspected mid-word splits by more than a third
     * (84 to 54) and added one fused line. A guard against merging writing of different sizes was
     * tried and dropped: it mostly stopped equations being read whole, whose fractions and exponents
     * give the two halves different median heights.
     */
    private fun mergeNeighbours(
        groups: List<List<SampledStroke>>,
        scale: Float,
        gutter: Float,
    ): List<List<SampledStroke>> {
        val multi = groups.filter { it.size > 1 }
        if (multi.size < 2) return groups
        val boxes = multi.map { group -> group.map { it.bounds }.reduce(PageRect::union) }
        val baselines = multi.map { group -> medianOf(group.map { it.bounds.bottom }) }
        val sizes = multi.map { group -> max(medianOf(group.map { it.bounds.height }), MIN_SCALE_PT) }
        val order = multi.indices.sortedBy { boxes[it].top }
        val parent = IntArray(multi.size) { it }

        for ((x, i) in order.withIndex()) {
            val a = boxes[i]
            for (y in x + 1 until order.size) {
                val j = order[y]
                val b = boxes[j]
                if (b.top > a.bottom) break
                val gap = a.horizontalGapTo(b)
                val beside = gap <= gutter && -gap <= min(a.width, b.width) * MERGE_OVERLAP_SHARE
                val level = a.verticalOverlap(b) >= MIN_VERTICAL_OVERLAP &&
                    abs(baselines[i] - baselines[j]) <= max(scale, min(sizes[i], sizes[j])) * BASELINE_TOLERANCE
                if (beside && level) union(parent, i, j)
            }
        }

        val merged = LinkedHashMap<Int, MutableList<SampledStroke>>()
        for (i in multi.indices) merged.getOrPut(find(parent, i)) { ArrayList() } += multi[i]
        return merged.values.toList() + groups.filter { it.size == 1 }
    }

    /**
     * ⭐ **Give each stroke that is still on its own to the line it belongs to**, or leave it a line
     * of its own when there is none.
     *
     * Who is left on their own after [rows]: an i-dot or an accent, which sits above its letter and
     * shares no baseline with anything; a subscript, which sits below; a capital, or a letter of a
     * drifting hand, whose foot missed the baseline by a little more than the tolerance; the bars of
     * an `E` or a `t`. As lines of their own each of them was handed to the recogniser as a word,
     * came back as a stray character in the index, and left a word short of a stroke. That was half
     * the lines version 2 first drew on the user's pages.
     *
     * A stroke joins the line whose box its centre is inside, or the nearest line within
     * [ATTACH_ABOVE] text scales above it or [ATTACH_BELOW] below. The line must be no further away
     * than [gutter] horizontally, and no shorter than the stroke over [ATTACH_TALLER]. Ties go to the
     * nearer line horizontally, then to the line whose middle is nearest the stroke's.
     *
     * ⚠️ **The lines' boxes are measured once, before anything joins them.** A stroke that joins a
     * line therefore cannot widen it to reach the next stroke. Joining is a leaf operation: it adds
     * a stroke to one line and never links two lines, which is why the marks moved here out of
     * [sameLine] (see there).
     */
    private fun attach(
        loose: List<SampledStroke>,
        lines: List<List<SampledStroke>>,
        scale: Float,
        gutter: Float,
    ): List<List<SampledStroke>> {
        val boxes = lines.map { line -> line.map { it.bounds }.reduce(PageRect::union) }
        val joined = lines.mapTo(ArrayList()) { ArrayList(it) }
        val alone = ArrayList<List<SampledStroke>>()
        for (stroke in loose) {
            val host = hostFor(stroke.bounds, boxes, scale, gutter)
            if (host == null) alone += listOf(stroke) else joined[host] += stroke
        }
        return joined + alone
    }

    /** The line [mark] fits best, by [Fit]'s order; on a tie, the first. */
    private fun hostFor(mark: PageRect, boxes: List<PageRect>, scale: Float, gutter: Float): Int? = boxes.indices
        .mapNotNull { index -> fitOf(mark, boxes[index], scale, gutter)?.let { fit -> index to fit } }
        .minWithOrNull(compareBy({ it.second.off }, { it.second.gap }, { it.second.middle }))
        ?.first

    /** How far [mark] is from [box] vertically, then horizontally, then from its middle. */
    private class Fit(val off: Float, val gap: Float, val middle: Float)

    /** Null when [mark] cannot join the line in [box] at all. */
    private fun fitOf(mark: PageRect, box: PageRect, scale: Float, gutter: Float): Fit? {
        val gap = mark.horizontalGapTo(box)
        if (mark.height > box.height * ATTACH_TALLER || gap > gutter) return null
        val off = when {
            mark.centreY < box.top -> (box.top - mark.centreY).takeIf { it <= scale * ATTACH_ABOVE }
            mark.centreY > box.bottom -> (mark.centreY - box.bottom).takeIf { it <= scale * ATTACH_BELOW }
            else -> 0f
        } ?: return null
        return Fit(off, max(gap, 0f), abs(mark.centreY - box.centreY))
    }

    /**
     * **Step two: cut a row where the gap is too wide to be a word space.**
     *
     * Now that the row exists, its box height *is* the line height — ascenders to descenders —
     * and that is the scale a word space is a fraction of. A gap wider than
     * [GUTTER_FACTOR] line-heights is not a space between two words; it is the gutter between
     * two columns, or the margin between a paragraph and a note beside it. Splitting there is
     * what stops a two-column page being read straight across.
     *
     * The running edge is the furthest right anything in the block has reached, not the
     * previous stroke's right edge: a `t` is crossed after its word is finished and its bar
     * begins to the left of where the pen had got to.
     */
    private fun splitAtGutters(row: List<SampledStroke>): List<List<SampledStroke>> {
        if (row.size < 2) return listOf(row)
        val ordered = row.sortedBy { it.bounds.left }
        var box = ordered.first().bounds
        for (stroke in ordered.drop(1)) box = box.union(stroke.bounds)
        val gutter = box.height.coerceAtLeast(MIN_SCALE_PT) * GUTTER_FACTOR

        val blocks = ArrayList<List<SampledStroke>>()
        var current = ArrayList<SampledStroke>().apply { add(ordered.first()) }
        var right = ordered.first().bounds.right
        for (stroke in ordered.drop(1)) {
            if (stroke.bounds.left - right > gutter) {
                blocks += current
                current = ArrayList()
            }
            current += stroke
            right = max(right, stroke.bounds.right)
        }
        blocks += current
        return blocks
    }

    private fun lineOf(ordered: List<SampledStroke>): TextLine {
        var box = ordered.first().bounds
        for (stroke in ordered.drop(1)) box = box.union(stroke.bounds)
        return TextLine(strokes = ordered, writingArea = box)
    }

    // -----------------------------------------------------------------------
    // The one thing SPEC §8.2 still says to detect and skip
    // -----------------------------------------------------------------------

    /**
     * ⭐ Strokes that read as a line **only if the page is turned** — a note written up the margin.
     *
     * The slope test below cannot see these, and the reason is worth stating because it was the
     * first thing tried. Vertically stacked letters have almost no vertical overlap with each
     * other by construction, so rotated writing never forms a group at all: it arrives as N
     * single-stroke components, each of which has no baseline and is therefore never rotated.
     * Left alone, every one of them is handed to the recogniser on its own and comes back as a
     * garbage character — into the search index and into the PDF's text layer.
     *
     * So the question is asked in the orientation that can answer it: transpose the singletons
     * and run the *same* grouping. A run of [MIN_ROTATED_RUN] or more strokes that becomes a
     * line when x and y are swapped is writing this build cannot read (SPEC §8.2), and saying so
     * costs one extra pass over the strokes nothing else wanted.
     *
     * Only singletons are offered, so a page of ordinary writing does no work here and a bullet
     * that sits beside its own line of text is never a candidate.
     *
     * ⚠️ **The run gets the same [gutter] as a line.** Transposed, the gap between two strokes is
     * the vertical gap between them on the page, and letters written up a margin are as close as
     * letters written across it. Version 1 had no limit here, and version 2 first kept that, so on
     * the user's physics page numerators, fraction bars and denominators stacked down a column of
     * equations chained into one "margin note" 440 pt tall, and 263 strokes went unread.
     */
    private fun rotatedRuns(groups: List<List<SampledStroke>>, gutter: Float): List<List<SampledStroke>> {
        val singles = groups.filter { it.size == 1 }.map { it.single() }
        if (singles.size < MIN_ROTATED_RUN) return emptyList()

        val byId = singles.associateBy { it.id }
        val turned = singles.map { it.copy(bounds = transpose(it.bounds)) }
        return rows(turned, medianHeight(turned), maxGap = gutter)
            .filter { it.size >= MIN_ROTATED_RUN }
            // Back to the untransposed strokes, so the reported region is where the writing
            // actually is rather than where it would be if the page were turned.
            .map { run -> run.mapNotNull { byId[it.id] } }
            .filter { run -> run.size >= MIN_ROTATED_RUN && isColumnShaped(run) }
    }

    /**
     * Whether a candidate rotated run is actually **column shaped** — far taller than it is wide.
     *
     * ⚠️ **Without this the transposed test is a false-positive machine, and the synthetic
     * fixture cannot show it.** Measured on 20 real pages the first version flagged **105**
     * regions as rotated; a page of ordinary prose leaves a scattering of strokes that happen
     * not to overlap their neighbours, and once transposed, four such strokes anywhere on the
     * page will chain into a "run" through union-find. The unit test passed because its
     * fixture had nothing else on the page — the *"distrust a fixture more generous than
     * production"* failure, in the one place a synthetic fixture is least like real ink.
     *
     * A note written up the margin is a narrow vertical band. Requiring the run's own box to be
     * [MIN_ROTATED_ASPECT] times taller than wide says exactly that, and says nothing about
     * strokes that merely failed to find a neighbour.
     */
    private fun isColumnShaped(run: List<SampledStroke>): Boolean {
        var box = run.first().bounds
        for (stroke in run.drop(1)) box = box.union(stroke.bounds)
        return box.height > box.width * MIN_ROTATED_ASPECT
    }

    /**
     * Whether a line's baseline slopes too far from horizontal for it to be read as one line.
     *
     * The baseline is the line of the strokes' *bottoms*, not their centres, because a capital at
     * the start of a sentence moves the centre and not the baseline. Its slope is the median bottom
     * of the right half of the letters against the median bottom of the left half, over the
     * distance between the halves' median centres: a dot or a descender moves a median by one place
     * at most.
     *
     * ⚠️ **Version 1 measured the first stroke against the last, and that made letters rotated.** A
     * capital `I` with serifs is three strokes, two bars and a stem, and the first bar's bottom is
     * the letter's height above the stem's: a slope of five. So were a `t` and its bar, an `F`, a
     * fraction and its bar, and every short group with a dot. Below [MIN_SLANT_LETTERS] letters, or
     * [MIN_SLANT_WIDTH] text scales across, a group is too short to have a measurable baseline and is
     * never called slanted. Marks are not counted as letters at all. A line written up the margin is
     * still found, by [rotatedRuns].
     */
    private fun isSlanted(group: List<SampledStroke>, scale: Float): Boolean {
        val letters = group
            .filter { it.bounds.height > scale * MARK_HEIGHT_FRACTION }
            .sortedBy { it.bounds.centreX }
        if (letters.size < MIN_SLANT_LETTERS) return false
        val left = letters.minOf { it.bounds.left }
        val right = letters.maxOf { it.bounds.right }
        if (right - left < scale * MIN_SLANT_WIDTH) return false
        val half = letters.size / 2
        val first = letters.take(half)
        val last = letters.takeLast(half)
        val run = medianOf(last.map { it.bounds.centreX }) - medianOf(first.map { it.bounds.centreX })
        if (run <= 0f) return false
        val rise = medianOf(last.map { it.bounds.bottom }) - medianOf(first.map { it.bounds.bottom })
        return !isNearHorizontal(rise, run, MAX_BASELINE_SLOPE)
    }

    private fun skip(strokes: List<SampledStroke>, reason: SkipReason): SkippedRegion {
        var box = strokes.first().bounds
        for (stroke in strokes.drop(1)) box = box.union(stroke.bounds)
        return SkippedRegion(bounds = box, reason = reason, strokeCount = strokes.size)
    }

    private val NOT_WRITING = SkipReason.NOT_WRITING

    // -----------------------------------------------------------------------

    /** A quarter of a point. Below this a "stroke" is a tap and has no measurable height. */
    private const val MIN_SCALE_PT = 0.25f

    /**
     * Fraction of the shorter box that must lie in the other's vertical band.
     *
     * Half. Lower and a descender reaches the line below; higher and an `o` next to an `l`
     * starts its own line — the two failures are on opposite sides of this number and they
     * are both worse than anything in between.
     */
    private const val MIN_VERTICAL_OVERLAP = 0.5f

    /** Taller than four x-heights is a bracket, a brace or a diagram edge, not a letter. */
    private const val OVERSIZE_HEIGHT_FACTOR = 4f

    /** An inch and a half. Nobody handwrites body text this tall, whatever the page median says. */
    private const val MAX_LETTER_HEIGHT_PT = 108f

    /** Flatter than a fifth of an x-height, and it has no letter shape left. */
    private const val FLAT_HEIGHT_FRACTION = 0.2f

    /** …or flatter than 3 pt outright, which is a ruled line however small the writing is. */
    private const val MIN_LETTER_HEIGHT_PT = 3f

    /** …and longer than six x-heights, so it is a rule or a strikethrough. */
    private const val RULE_WIDTH_FACTOR = 6f

    /** …or longer than two inches outright. No single letter is. */
    private const val MAX_RULE_WIDTH_PT = 144f

    /**
     * Strokes that must line up when transposed before the run is called rotated writing.
     *
     * Four. Three vertically stacked isolated marks are a plausible column of bullets or
     * tally marks; four is a word. The cost of guessing high is a margin note left out of the
     * index, the cost of guessing low is four bullets skipped — and the second is invisible
     * because a bullet has no text to find.
     */
    private const val MIN_ROTATED_RUN = 4

    /**
     * A gap this many **line heights** wide is a gutter, not a word space.
     *
     * One and a half. Measured on real pages, word spaces sit around a third of a line height
     * and column gutters run to several, so the two populations are far apart and the exact
     * value is not delicate — but the *scale* is, and getting it wrong the first time (median
     * stroke height, about a third of this) split every line into its words.
     *
     * [rows], [rotatedRuns] and [attach] measure it in the page's typical line height, and
     * [splitAtGutters] in the row's own.
     */
    private const val GUTTER_FACTOR = 1.5f

    /**
     * A page with no row of two strokes has no line to measure. Its line height is taken as this
     * many median stroke heights, the ratio an ordinary hand shows between the two.
     */
    private const val LONE_LINE_HEIGHT = 3f

    /** The share of the narrower line's width a line above must cover to be its context. */
    private const val CONTEXT_OVERLAP = 0.3f

    /** How far above, in the line's own height, a line may be and still be its context. */
    private const val CONTEXT_GAP_LINES = 2f

    /** A margin note is a narrow vertical band: this many times taller than wide, at least. */
    private const val MIN_ROTATED_ASPECT = 3f

    /**
     * How far two strokes' bottoms may differ and still be one line, in median stroke heights.
     *
     * 0.7. A descender drops well under one x-height below the baseline, and the next line's
     * baseline is a line pitch away — 1.5 to 2.5 x-heights in ordinary handwriting. The value
     * sits in that gap, and both populations are wide enough that it is not delicate.
     */
    private const val BASELINE_TOLERANCE = 0.7f

    /**
     * A stroke this short is a mark rather than a letter — an i-dot, a t-bar, an accent — and
     * [isSlanted] does not measure a baseline from it.
     */
    private const val MARK_HEIGHT_FRACTION = 0.6f

    /** ~11°. Enough for a sloping hand on unruled paper, not enough for writing up a margin. */
    private const val MAX_BASELINE_SLOPE = 0.2f

    /** Fewer letters than this have no baseline worth measuring: see [isSlanted]. */
    private const val MIN_SLANT_LETTERS = 4

    /** …nor does a group narrower than this many text scales. */
    private const val MIN_SLANT_WIDTH = 4f

    /**
     * How far above a line's box a lone stroke's centre may be and still join it, in text scales.
     * One: an i-dot over a word with no ascenders sits about that far above the x-height.
     */
    private const val ATTACH_ABOVE = 1f

    /**
     * …and below it. Half: a subscript or a missed descender hangs below the baseline by less than
     * that, and the next line's ascenders start not much further down.
     */
    private const val ATTACH_BELOW = 0.5f

    /** A stroke this many times taller than a line is not part of it: a bracket beside it. */
    private const val ATTACH_TALLER = 1.3f

    /**
     * How far two groups may overlap horizontally, as a share of the narrower one's width, and still be
     * side by side for [mergeNeighbours]. Further than that, one is above the other.
     */
    private const val MERGE_OVERLAP_SHARE = 0.5f
}

// ---------------------------------------------------------------------------
// Plain helpers, outside the object so its own count stays about the segmenter
// ---------------------------------------------------------------------------

/** How much of the narrower box's width the other box covers horizontally, 0 to 1. */
private fun horizontalOverlapShare(a: PageRect, b: PageRect): Float {
    val narrower = min(a.width, b.width)
    if (narrower <= 0f) return 0f
    return ((min(a.right, b.right) - max(a.left, b.left)) / narrower).coerceIn(0f, 1f)
}

/** The middle value, or the mean of the middle two. [values] must not be empty. */
private fun medianOf(values: List<Float>): Float {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
}

private fun transpose(bounds: PageRect): PageRect =
    PageRect(left = bounds.top, top = bounds.left, right = bounds.bottom, bottom = bounds.right)

private fun find(parent: IntArray, index: Int): Int {
    var root = index
    while (parent[root] != root) {
        parent[root] = parent[parent[root]]
        root = parent[root]
    }
    return root
}

private fun union(parent: IntArray, a: Int, b: Int) {
    val rootA = find(parent, a)
    val rootB = find(parent, b)
    if (rootA != rootB) parent[rootB] = rootA
}
