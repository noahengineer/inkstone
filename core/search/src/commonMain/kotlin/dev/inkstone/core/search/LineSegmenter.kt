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
 * 3. **Lines are connected components under vertical overlap**, not clusters of centre-y. A
 *    centre-based rule puts `l` and `o` on different lines because their centres genuinely
 *    differ by a third of an x-height; overlap normalised by the *shorter* box does not care.
 * 4. **A horizontal gap breaks the connection.** Without it, two columns whose lines happen to
 *    align become one line each, read straight across the gutter — the exact nonsense SPEC §8.2
 *    says to detect rather than emit.
 * 5. **Then the page is checked for columns and each line for rotation**, and anything failing
 *    is returned in [Segmentation.skipped] rather than dropped, so *"nothing was searchable"*
 *    and *"this page is a diagram"* are different answers.
 */
public object LineSegmenter {

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
        val groups = rows(writing, textScale).flatMap { row -> splitAtGutters(row) }

        val columns = columnsOf(groups, textScale)
        if (columns > 1) {
            // Whole-page verdict, not per line: the failure is the reading *order*, and every
            // line participates in it. Two columns of perfectly good lines still concatenate
            // into nonsense, which is what SPEC §8.2 refuses to emit.
            return Segmentation(emptyList(), skipped + skip(writing, SkipReason.MULTI_COLUMN))
        }

        // One skipped region per *run*, not per stroke: a note written up the margin is one
        // thing the user did, and reporting it as eleven skipped letters would be true and
        // useless.
        val turned = rotatedRuns(groups)
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
        // spatially. `isRotated` still asks its question of the x-ordered view, because a
        // baseline is a spatial property.
        val order = HashMap<ObjectId, Int>(strokes.size)
        for ((index, stroke) in strokes.withIndex()) order[stroke.id] = index

        val lines = ArrayList<TextLine>(groups.size)
        for (group in groups) {
            if (group.any { it.id in turnedIds }) continue
            if (isRotated(group.sortedBy { it.bounds.left })) {
                skipped += skip(group, SkipReason.ROTATED)
            } else {
                lines += lineOf(group.sortedBy { order[it.id] ?: 0 })
            }
        }
        return Segmentation(lines.sortedBy { it.writingArea.centreY }, skipped)
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
     * ⭐ **Step one: rows, by vertical overlap alone — no horizontal test whatsoever.**
     *
     * The first version of this refused to join two strokes more than a few median stroke
     * heights apart, meaning to keep two columns separate. Measured against 20 real pages it
     * found **632 lines where there were 472**, and drawing the boxes over the ink showed why
     * immediately: it had split every line into its individual *words*. The threshold was
     * scaled to the median **stroke** height — roughly one letter — while the gap it had to
     * tolerate is a word space, which is a fraction of the **line** height. Those two scales
     * differ by about three times in ordinary handwriting, and the wrong one put the cut-off
     * squarely between "letters in a word" and "words in a line".
     *
     * The line height is not known until the row exists, so the horizontal question cannot be
     * asked here at all. It is asked in [splitAtGutters], where the answer is available.
     *
     * Union-find over a window rather than all pairs: strokes are sorted by `top`, so once a
     * candidate's `top` is more than a line below the current stroke's `bottom` it cannot
     * overlap this one or any later one and the scan stops. A 2 000-stroke lecture page stays
     * near-linear instead of four million comparisons.
     */
    private fun rows(strokes: List<SampledStroke>, scale: Float): List<List<SampledStroke>> {
        val sorted = strokes.sortedBy { it.bounds.top }
        val parent = IntArray(sorted.size) { it }

        for (i in sorted.indices) {
            val a = sorted[i].bounds
            for (j in i + 1 until sorted.size) {
                val b = sorted[j].bounds
                if (b.top > a.bottom + scale) break
                if (sameLine(a, b, scale)) union(parent, i, j)
            }
        }

        val groups = LinkedHashMap<Int, MutableList<SampledStroke>>()
        for (i in sorted.indices) groups.getOrPut(find(parent, i)) { ArrayList() } += sorted[i]
        return groups.values.toList()
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
     * ⚠️ **With one exception, or every `i` loses its dot.** A dot, a `t` bar and an accent are
     * separate strokes that share no baseline with anything; they sit directly *above* the
     * letter they belong to. So a small mark also joins when it is horizontally on top of its
     * neighbour. Without this they become singletons, and a page of singletons is then a page
     * the rotated-text detector starts guessing about.
     */
    private fun sameLine(a: PageRect, b: PageRect, scale: Float): Boolean {
        if (a.verticalOverlap(b) < MIN_VERTICAL_OVERLAP) return false
        if (abs(a.bottom - b.bottom) <= scale * BASELINE_TOLERANCE) return true
        val mark = min(a.height, b.height) <= scale * MARK_HEIGHT_FRACTION
        return mark && a.horizontalGapTo(b) <= scale * MARK_GAP_FACTOR
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

    private fun lineOf(ordered: List<SampledStroke>): TextLine {
        var box = ordered.first().bounds
        for (stroke in ordered.drop(1)) box = box.union(stroke.bounds)
        return TextLine(strokes = ordered, writingArea = box)
    }

    // -----------------------------------------------------------------------
    // The two things SPEC §8.2 says to detect and skip
    // -----------------------------------------------------------------------

    /**
     * How many horizontally disjoint blocks the lines fall into.
     *
     * Measured on the *lines*, not the strokes: the gap between two words is the same shape as
     * the gutter between two columns, and only a gap that persists down the page tells them
     * apart. Requiring [MIN_COLUMN_LINES] lines on each side is what stops a single indented
     * paragraph from reading as a second column.
     */
    private fun columnsOf(groups: List<List<SampledStroke>>, scale: Float): Int {
        if (groups.size < MIN_COLUMN_LINES * 2) return 1
        val spans = groups.map { group ->
            val left = group.minOf { it.bounds.left }
            val right = group.maxOf { it.bounds.right }
            left to right
        }.sortedBy { it.first }

        var blocks = 1
        var blockRight = spans.first().second
        var linesInBlock = 1
        var linesBefore = 0
        for ((left, right) in spans.drop(1)) {
            if (left - blockRight > scale * COLUMN_GUTTER_FACTOR) {
                if (linesInBlock >= MIN_COLUMN_LINES && linesBefore + linesInBlock >= MIN_COLUMN_LINES) blocks++
                linesBefore += linesInBlock
                linesInBlock = 1
                blockRight = right
            } else {
                linesInBlock++
                blockRight = max(blockRight, right)
            }
        }
        return if (blocks > 1 && linesInBlock >= MIN_COLUMN_LINES) blocks else 1
    }

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
     */
    private fun rotatedRuns(groups: List<List<SampledStroke>>): List<List<SampledStroke>> {
        val singles = groups.filter { it.size == 1 }.map { it.single() }
        if (singles.size < MIN_ROTATED_RUN) return emptyList()

        val byId = singles.associateBy { it.id }
        val turned = singles.map { it.copy(bounds = transpose(it.bounds)) }
        return rows(turned, medianHeight(turned))
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

    private fun transpose(bounds: PageRect): PageRect =
        PageRect(left = bounds.top, top = bounds.left, right = bounds.bottom, bottom = bounds.right)

    /**
     * Whether the line's baseline drifts too far from horizontal to be read as one line.
     *
     * The baseline is taken from the *bottoms* of the boxes rather than their centres, because
     * a capital at the start of a sentence moves the centre and not the baseline. A one-stroke
     * line has no slope; [rotatedRuns] is what covers those.
     */
    private fun isRotated(ordered: List<SampledStroke>): Boolean {
        if (ordered.size < 2) return false
        val first = ordered.first().bounds
        val last = ordered.last().bounds
        val run = last.left - first.left
        val rise = last.bottom - first.bottom
        if (abs(run) < MIN_SCALE_PT) return abs(rise) > MIN_SCALE_PT
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
     */
    private const val GUTTER_FACTOR = 1.5f

    /** Fewer lines than this on one side of a gap is an indent, not a column. */
    private const val MIN_COLUMN_LINES = 3

    /** The same question at page scale, where the unit available is the median stroke height. */
    private const val COLUMN_GUTTER_FACTOR = 6f

    /** A margin note is a narrow vertical band: this many times taller than wide, at least. */
    private const val MIN_ROTATED_ASPECT = 3f

    /**
     * How far two strokes' bottoms may differ and still be one line, in median stroke heights.
     *
     * 1.2. A descender drops well under one x-height below the baseline, and the next line's
     * baseline is a line pitch away — 1.5 to 2.5 x-heights in ordinary handwriting. The value
     * sits in that gap, and both populations are wide enough that it is not delicate.
     */
    private const val BASELINE_TOLERANCE = 0.7f

    /** A stroke this short is a mark rather than a letter — an i-dot, a t-bar, an accent. */
    private const val MARK_HEIGHT_FRACTION = 0.6f

    /** …and it belongs to the letter it is sitting on top of, so it must be right above it. */
    private const val MARK_GAP_FACTOR = 0.5f

    /** ~11°. Enough for a sloping hand on unruled paper, not enough for writing up a margin. */
    private const val MAX_BASELINE_SLOPE = 0.2f
}
