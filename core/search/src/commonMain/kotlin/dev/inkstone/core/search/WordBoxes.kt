package dev.inkstone.core.search

import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.RecognisedLine
import dev.inkstone.core.model.RecognisedWord
import dev.inkstone.core.model.SampledStroke
import dev.inkstone.core.model.TextLine

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
            words.mapIndexed { index, word -> RecognisedWord(word, clusters[index]) }
        } else {
            apportion(words, box)
        }
        return RecognisedLine(text = text.trim(), bounds = box, words = placed)
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
     */
    private fun clustersOf(strokes: List<SampledStroke>): List<PageRect> {
        val ordered = strokes.sortedBy { it.bounds.left }
        val height = ordered.maxOf { it.bounds.height }.coerceAtLeast(MIN_HEIGHT_PT)
        val gap = height * WORD_GAP_FACTOR

        val clusters = ArrayList<PageRect>()
        var current = ordered.first().bounds
        for (stroke in ordered.drop(1)) {
            current = if (stroke.bounds.left - current.right > gap) {
                clusters += current
                stroke.bounds
            } else {
                current.union(stroke.bounds)
            }
        }
        clusters += current
        return clusters
    }

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

    /** A quarter of a point, so a line of full stops cannot make every gap a word break. */
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
