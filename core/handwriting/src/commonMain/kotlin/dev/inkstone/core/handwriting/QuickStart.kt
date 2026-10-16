package dev.inkstone.core.handwriting

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.IdFactory
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PenStyle
import dev.inkstone.core.model.PenTrajectory
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.search.LineSegmenter
import dev.inkstone.core.search.WordStrokes

/** What one prompt's writing became. */
public sealed interface QuickStartResult {
    public data class Learned(val samples: List<WordSample>) : QuickStartResult

    /** Nothing was written. */
    public data object Empty : QuickStartResult

    /** The writing ran onto more than one line, so its words cannot be told apart reliably. */
    public data object NotOneLine : QuickStartResult

    /** The gaps between strokes did not make the prompt's [expected] words. */
    public data class WordsDidNotSplit(val expected: Int) : QuickStartResult
}

/**
 * ⭐ D14's optional quick start: about three minutes copying prompted sentences that cover the
 * alphabet, for someone with no notes to learn from yet.
 *
 * **No recogniser is involved.** The prompt is the label, so each word becomes a
 * [LabelSource.CONFIRMED] sample the moment it is written. Its words are found by the gaps between
 * strokes ([WordBoxes]) and must number the prompt's; letters are cut only where every letter is its
 * own cluster of strokes. A joined word is kept whole, and the learning pass cuts its runs later,
 * reading each back (`Harvester.recut`) — the one step that needs the model, and it is not this one.
 *
 * Quick-start samples belong to no note ([NOTE_ID]), so `Departures` never takes them for a purged
 * note's, and they are never held out: they exist to teach letters, and a tenth of the alphabet kept
 * back for measurement would defeat them.
 */
public object QuickStart {

    /** The note id every quick-start sample carries. Not a UUID, so it can never be a real note's. */
    public const val NOTE_ID: String = "quick-start"

    /**
     * Eight prompts, English first (Q7). Together: every lower-case letter several times, every
     * capital, every digit, and the punctuation a note most often holds.
     *
     * ⭐ **The sixth and seventh are maths** (§18.10). The first five give every digit once or twice and
     * not one `=`, `+`, `/`, `%` or decimal point, and a profile learned from notes had, on the tablet,
     * one `=` (misframed), one `+`, one `.` (a speck from `fine.`) and no `/` or `%` at all — so a
     * number like `2.5` was assembled with a dot nobody could see, and every `=` fell to the
     * network. Each symbol is its own word, so it is cut as a unit of its own.
     *
     * ⭐ **The eighth is the maths symbols** (docs/HANDWRITING-MATHS.md §2.4, the user's list). No
     * recogniser can read one ([GateCalibration.UNREADABLE]), so the network's are never drawn, and these
     * copies — vouched, since the prompt is the label — are the only way a `±` or a `√` reaches a page.
     * The `√` is also what [MathLayout] carries on over a radicand. Appended last, so the prompts already
     * recorded keep their indices.
     */
    public val PROMPTS: List<String> = listOf(
        "the quick brown fox jumps over the lazy dog",
        "Pack my box with five dozen liquor jugs.",
        "Jackdaws love my big sphinx of quartz!",
        "THE FIVE BOXING WIZARDS JUMP QUICKLY",
        "Room 0123, 4567 or 89? Yes - it's fine.",
        "x = 0.25 + 1.75 - 3/8 = 1.625",
        "(4.9 - 2.6) / 0.5 = 4.6 or 46%",
        "± × ÷ √ π ≤ ≥ ≠ ≈ θ Δ °",
    )

    /** The prompts already recorded among [samples], by index. */
    public fun promptsDone(samples: List<WordSample>): Set<Int> = samples
        .filter { it.noteId.value == NOTE_ID }
        .mapNotNull { it.pageId.value.removePrefix(PAGE_PREFIX).toIntOrNull() }
        .toSet()

    /** Where the quick start opens: the first prompt not yet recorded, or the first when all are. */
    public fun firstToDo(done: Set<Int>): Int = PROMPTS.indices.firstOrNull { it !in done } ?: 0

    /**
     * Turns what was written for [prompt] into samples.
     *
     * @param written everything written for the prompt, one trajectory, **in page points with y
     *   down**, strokes in the order they were drawn.
     * @param promptIndex which prompt, so its samples can be told apart on *My handwriting*.
     */
    @Suppress("LongParameterList", "ReturnCount")
    public fun samples(
        prompt: String,
        promptIndex: Int,
        written: PenTrajectory,
        pen: PenStyle,
        unitLengthCm: Float?,
        ids: IdFactory,
        epochDay: Long,
    ): QuickStartResult {
        val strokes = sampledStrokesOf(written)
        if (strokes.isEmpty()) return QuickStartResult.Empty
        if (!oneLine(strokes)) return QuickStartResult.NotOneLine
        val texts = prompt.split(' ').filter { it.isNotBlank() }
        val words = wordsOf(strokes, texts) ?: return QuickStartResult.WordsDidNotSplit(expected = texts.size)
        val style = LineStyles.measure(strokes) ?: return QuickStartResult.Empty
        val samples = words.mapNotNull { word ->
            val indices = word.strokes.map { stroke -> strokes.indexOf(stroke) }.sorted()
            val trajectory = written.strokes(indices) ?: return@mapNotNull null
            val key = Fingerprint.hex(Fingerprint.of(TrajectoryCodec.encode(trajectory)))
            val clusters = GlyphClusters.of(word.strokes)
            val letters = writtenUnits(word.text)
            val glyphs = if (clusters.size == letters.size) {
                // Clusters index into the word's strokes; the sample's trajectory holds them in the
                // same drawing order, so the indices carry over unchanged.
                clusters.mapIndexed { i, members -> GlyphSpan(letters[i], members) }
            } else {
                emptyList()
            }
            WordSample(
                id = ids.newUuid7(),
                noteId = DocumentId(NOTE_ID),
                pageId = PageId("$PAGE_PREFIX$promptIndex"),
                sourceStrokes = emptyList(),
                sourceKey = key,
                epochDay = epochDay,
                label = word.text,
                labelSource = LabelSource.CONFIRMED,
                alternatives = emptyList(),
                frame = style.frameAt(word.bounds.left),
                pen = pen,
                unitLengthCm = unitLengthCm,
                split = SampleSplit.TRAIN,
                trajectory = trajectory,
                glyphs = glyphs,
            )
        }
        return QuickStartResult.Learned(samples)
    }

    /**
     * The prompt's words, found by the gaps between strokes.
     *
     * ⭐ **The N−1 widest gaps, because the prompt says how many words there are.** It used M7's
     * `WordBoxes`, which calls any gap wider than a third of the median stroke height a word space —
     * right for search, where the count is unknown, and wrong here: a printed hand that leaves air
     * between its letters (the emulator's scripted prompt, the first time this ran through the UI) has
     * letter gaps past that line, and every prompt came back *"the gaps did not make 9 words"*. The
     * widest gaps are the word spaces whatever their size, provided they are clearly wider than every
     * gap left inside a word ([SPACE_OVER_LETTERS]); otherwise the split is refused, never guessed.
     */
    internal fun wordsOf(strokes: List<SampledStroke>, texts: List<String>): List<WordStrokes>? {
        if (texts.isEmpty() || strokes.isEmpty()) return null
        val order = strokes.indices.sortedBy { strokes[it].bounds.left }
        // gaps[k] is the space before order[k + 1]: from the furthest right reached so far, because a
        // `t` is crossed after its word.
        val gaps = FloatArray(order.size - 1)
        var reached = strokes[order[0]].bounds.right
        for (k in 1 until order.size) {
            val stroke = strokes[order[k]].bounds
            gaps[k - 1] = stroke.left - reached
            reached = maxOf(reached, stroke.right)
        }
        val cuts = gaps.indices.sortedByDescending { gaps[it] }.take(texts.size - 1).sorted()
        if (cuts.size != texts.size - 1) return null
        val narrowestSpace = cuts.minOfOrNull { gaps[it] } ?: Float.MAX_VALUE
        val widestInside = gaps.indices.filter { it !in cuts }.maxOfOrNull { gaps[it] } ?: 0f
        if (texts.size > 1 && (narrowestSpace <= 0f || narrowestSpace < widestInside * SPACE_OVER_LETTERS)) return null
        val groups = ArrayList<List<Int>>()
        var start = 0
        for (cut in cuts + (order.size - 1)) {
            groups += order.subList(start, cut + 1).sorted()
            start = cut + 1
        }
        return groups.mapIndexed { i, members ->
            val group = members.map { strokes[it] }
            WordStrokes(texts[i], group.map { it.bounds }.reduce(PageRect::union), group)
        }
    }

    /** How much wider than any gap inside a word the narrowest word space must be. */
    private const val SPACE_OVER_LETTERS = 1.3f

    /**
     * Whether the writing is one line: every line the segmenter finds sits beside the others rather
     * than above or below them. The segmenter alone would call a wide word space a column gutter and
     * report two lines side by side, which for a prompt copied in one go is still one line.
     */
    private fun oneLine(strokes: List<SampledStroke>): Boolean {
        val found = LineSegmenter.segment(strokes).lines.map { it.writingArea }
        // ⭐ The segmenter reads text, and the symbols prompt is all marks: on its fixture it found 18
        // "lines" in one, every bar, dot and degree sign one of its own, most with no height at all
        // (docs/HANDWRITING-MATHS.md §2.4). A line far shorter than the tallest is a mark on a line;
        // what this check exists to catch, a prompt run onto a second line, is lines of full height.
        val tallest = found.maxOfOrNull { it.height } ?: return true
        val lines = found.filter { it.height >= tallest * MARK_FRACTION }
        return lines.all { a ->
            lines.all { b ->
                val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
                overlap >= minOf(a.height, b.height) * SAME_LINE_OVERLAP
            }
        }
    }

    private const val SAME_LINE_OVERLAP = 0.5f

    /** A "line" under this share of the tallest one's height is a mark: a bar, a dot, a degree sign. */
    private const val MARK_FRACTION = 0.4f
    private const val PAGE_PREFIX = "prompt-"
}
