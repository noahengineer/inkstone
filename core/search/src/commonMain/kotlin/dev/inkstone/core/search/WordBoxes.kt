package dev.inkstone.core.search

import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.RecognisedLine
import dev.inkstone.core.model.RecognisedWord
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.model.TextLine

/**
 * One recognised word and the strokes that made it — [WordBoxes.strokesOfWords].
 *
 * @property strokes in the order they were drawn, not left to right: a `t` crossed after the word
 *   was finished is last, as it was on the page.
 */
public data class WordStrokes(val text: String, val bounds: PageRect, val strokes: List<SampledStroke>)

/**
 * ⭐ SPEC §5.5 — put each recognised word back on the ink that produced it.
 *
 * **The recogniser does not do this.** ML Kit's `RecognitionCandidate` carries a `String` and a
 * score and nothing else; there are no glyph boxes in the API, verified against the 19.0.0
 * artifact. So a naive implementation has recognised text and no idea where any of it is, and
 * the invisible text layer either does not get written or gets written across the whole line.
 *
 * Inkstone can do better than any OCR pipeline here and the reason is structural: the strokes
 * are still in hand. Splitting a line into words by the gaps between strokes is the same signal
 * a reader uses, it is exact rather than estimated from a rasterised glyph, and when it agrees
 * with the recogniser about how many words there are — which is the ordinary case — every word
 * gets the true box of the ink that made it.
 *
 * When it disagrees, the fallback apportions the line's box by character count rather than
 * dropping the word or claiming the whole line. That is honest: a search hit lands on the right
 * line and within a word or two along it, instead of nowhere.
 */
public object WordBoxes {

    /**
     * @param text what the recogniser returned for [line], e.g. `"And he had a feeling"`.
     * @return one [RecognisedWord] per whitespace-separated token, in reading order. Empty when
     *   the recogniser returned nothing — a line it could not read has no words to place.
     */
    public fun assign(line: TextLine, text: String): RecognisedLine {
        val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val box = line.writingArea
        if (words.isEmpty() || line.isEmpty) return RecognisedLine(text.trim(), box, emptyList())

        val clusters = clustersOf(line.strokes)
        val placed = if (clusters.size == words.size) {
            words.mapIndexed { index, word -> RecognisedWord(word, clusters[index].bounds) }
        } else {
            apportion(words, box)
        }
        return RecognisedLine(text = text.trim(), bounds = box, words = placed)
    }

    /**
     * ⭐ Each word **and the strokes that made it** — docs/HANDWRITING-SYNTHESIS.md §5.3.
     *
     * [assign] keeps only boxes, which is all search and the PDF's text layer need. Learning
     * someone's handwriting needs the strokes themselves, and the grouping that produced the boxes
     * is exactly that information — computed here and, until now, thrown away.
     *
     * ⚠️ **Null whenever the gaps and the recogniser disagree about how many words there are.**
     * [assign] then falls back to slicing the line's box by character count, which is honest about
     * *where* a word is and says nothing about *which strokes* it is — so there is no fallback here.
     * A word learned from the wrong strokes is worse than a word not learned.
     *
     * @return one entry per whitespace-separated token, in reading order, each with its strokes in
     *   the order they were **drawn** — the order [line] holds them in, which is what makes a
     *   trajectory's time run forwards.
     */
    public fun strokesOfWords(line: TextLine, text: String): List<WordStrokes>? {
        val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty() || line.isEmpty) return null
        val clusters = clustersOf(line.strokes)
        if (clusters.size != words.size) return null
        return words.mapIndexed { index, word ->
            val members = clusters[index].ids
            WordStrokes(
                text = word,
                bounds = clusters[index].bounds,
                strokes = line.strokes.filter { it.id in members },
            )
        }
    }

    /**
     * Groups of strokes separated by a gap wide enough to be a word space.
     *
     * The threshold is relative to the line's own height, so it follows the writer's hand
     * rather than a number in points. Strokes are taken in reading order and a cluster is
     * closed when the next stroke starts more than [WORD_GAP_FACTOR] line-heights past the
     * furthest right anything in the cluster reached — the *furthest right*, not the previous
     * stroke's right, because a `t` is crossed after the word is finished and its bar starts
     * to the left of where the pen had got to.
     *
     * ⚠️ **The line's height is the MEDIAN stroke height, not the tallest.** It was the tallest
     * until this file gained tests, and one bracket, integral sign or ascender-plus-descender
     * raised the word-gap threshold for the whole line by a third — merging words that were
     * properly spaced, and only on the lines that happen to contain a tall letter. `LineSegmenter`
     * already refuses the same mistake one level up (*"scale is taken from the writing and not
     * from a diagram sharing the page"*), and the two files measuring a line differently was the
     * kind of disagreement that stays invisible until someone writes an `f`.
     */
    private fun clustersOf(strokes: List<SampledStroke>): List<Cluster> {
        val ordered = strokes.sortedBy { it.bounds.left }
        val heights = ordered.map { it.bounds.height }.sorted()
        val height = heights[heights.size / 2].coerceAtLeast(MIN_HEIGHT_PT)
        val gap = height * WORD_GAP_FACTOR

        val clusters = ArrayList<Cluster>()
        var current = Cluster(ordered.first().bounds, hashSetOf(ordered.first().id))
        for (stroke in ordered.drop(1)) {
            current = if (stroke.bounds.left - current.bounds.right > gap) {
                clusters += current
                Cluster(stroke.bounds, hashSetOf(stroke.id))
            } else {
                current.ids += stroke.id
                Cluster(current.bounds.union(stroke.bounds), current.ids)
            }
        }
        clusters += current
        return clusters
    }

    /** One word's worth of strokes: their box, and which they were. */
    private class Cluster(val bounds: PageRect, val ids: HashSet<ObjectId>)

    /**
     * Share the line's box out by character count, spaces included.
     *
     * Reached when the recogniser and the gaps disagree — it read two words as one, or a
     * comma became its own token. The words are still in order and still on the right line,
     * which is what a search hit and a text selection actually need.
     */
    private fun apportion(words: List<String>, box: PageRect): List<RecognisedWord> {
        val total = words.sumOf { it.length } + (words.size - 1)
        if (total <= 0) return emptyList()
        var cursor = box.left
        return words.map { word ->
            val width = box.width * word.length / total
            val placed = RecognisedWord(word, PageRect(cursor, box.top, cursor + width, box.bottom))
            cursor += width + box.width / total
            placed
        }
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * A floor on the measured line height, so the threshold cannot become zero.
     *
     * ⚠️ **It does not do what it used to claim.** The KDoc here said it stopped *"a line of full
     * stops making every gap a word break"*, and it does no such thing: a full stop is around a
     * point tall, well above this floor, so the threshold is a third of a point and every gap
     * between stops is still a break. The floor engages only for strokes with no measurable
     * height at all, where its job is to keep `height * WORD_GAP_FACTOR` off zero.
     *
     * The full-stop case is handled, but by [assign]'s fallback rather than here: the cluster
     * count will not match the recogniser's word count, so the line is apportioned. Which is the
     * right outcome — it simply is not this constant's doing.
     */
    private const val MIN_HEIGHT_PT = 0.25f

    /**
     * A gap of this many line-heights is a word space.
     *
     * A third. Inter-letter gaps in joined-up writing are near zero and in printed hands run
     * to perhaps a tenth; word spaces are conventionally about an en, which against a line
     * height including ascenders and descenders lands near a third. The consequence of being
     * wrong is bounded either way — two words share a box, or one word gets two — because the
     * count check falls back to [apportion] rather than to nonsense.
     */
    private const val WORD_GAP_FACTOR = 0.33f
}
