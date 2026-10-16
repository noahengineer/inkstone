package dev.inkstone.core.handwriting

import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.SampledStroke
import kotlin.math.max
import kotlin.math.min

/**
 * A word's strokes grouped into letter-sized clusters — v1 glyph segmentation
 * (docs/HANDWRITING-SYNTHESIS.md §5.4).
 *
 * Strokes that overlap side by side belong to one letter: the two strokes of an `x`, a `t` and its
 * crossbar, an `i` and its dot (a small mark joins whatever it sits over or beside). A joined-up pair
 * is one stroke and one cluster, and splitting a stroke is v2's job (the model's attention, phase 4).
 * Since phase 3, a word with fewer clusters than letters is shared out by [partition] instead of
 * being left whole: a cluster may hold a run of joined letters, `th` as one piece of ink. That is
 * what lets assembly copy a join the user made, rather than invent one.
 *
 * The harvester then reads every cluster on its own and keeps only those that read as their letter,
 * so a wrong cut is refused rather than learned.
 */
public object GlyphClusters {

    /** @return clusters left to right, each a list of indices into [strokes] in drawing order. */
    public fun of(strokes: List<SampledStroke>): List<List<Int>> {
        if (strokes.isEmpty()) return emptyList()
        val scale = median(strokes.map { it.bounds.height }).coerceAtLeast(MIN_SCALE_PT)
        val clusters = ArrayList<Cluster>()
        for (index in strokes.indices.sortedBy { strokes[it].bounds.left }) {
            val box = strokes[index].bounds
            val small = box.height < scale * SMALL_HEIGHT && box.width < scale * SMALL_WIDTH
            val host = clusters
                .filter { sideBySide(it.box, box) || (small && it.near(box, scale)) }
                .maxByOrNull { overlap(it.box, box) }
            if (host == null) {
                clusters += Cluster(box, mutableListOf(index))
            } else {
                host.box = host.box.union(box)
                host.members += index
            }
        }
        return clusters.sortedBy { it.box.left }.map { it.members.sorted() }
    }

    /**
     * How many letters each cluster holds, when a word has fewer clusters than letters — which is
     * what a joined-up pair looks like: one stroke, two letters (§6.1, phase 3).
     *
     * The clusters' widths are shared out in proportion to how wide each letter usually is
     * ([expectedWidth]), as a dynamic program over where each cluster's letters end. It is a guess
     * about *how many*, never about *which shape*: the harvester reads every cluster back and keeps
     * only those that read as the letters given to them, so a wrong share is refused, not learned.
     *
     * @return letters per cluster, left to right, summing to [letters]' size; null when there are
     *   no clusters, more clusters than letters, or a cluster would need more than
     *   [MAX_LETTERS_PER_CLUSTER].
     */
    @Suppress("ReturnCount")
    public fun partition(widths: List<Float>, letters: List<String>): List<Int>? {
        val clusters = widths.size
        val count = letters.size
        if (clusters == 0 || count < clusters || count > clusters * MAX_LETTERS_PER_CLUSTER) return null
        if (clusters == count) return List(clusters) { 1 }
        val totalWidth = widths.sum()
        if (totalWidth <= 0f) return null
        val prefix = FloatArray(count + 1)
        for (i in 0 until count) prefix[i + 1] = prefix[i] + expectedWidth(letters[i])
        val from = cheapestStarts(widths.map { it / totalWidth }, prefix) ?: return null
        val shares = IntArray(clusters)
        var end = count
        for (k in clusters downTo 1) {
            val start = from[k][end]
            shares[k - 1] = end - start
            end = start
        }
        return shares.toList()
    }

    /**
     * [partition]'s dynamic program. `from[k][end]` is where cluster `k`'s letters start when its
     * last letter is `end - 1`, chosen to minimise the squared difference between each cluster's
     * share of the width ([actual]) and its letters' share of the expected width ([prefix] is the
     * running sum). Null when no split keeps every cluster within [MAX_LETTERS_PER_CLUSTER].
     */
    @Suppress("NestedBlockDepth")
    private fun cheapestStarts(actual: List<Float>, prefix: FloatArray): Array<IntArray>? {
        val clusters = actual.size
        val count = prefix.size - 1
        val totalExpected = prefix[count]
        val cost = Array(clusters + 1) { FloatArray(count + 1) { Float.MAX_VALUE } }
        val from = Array(clusters + 1) { IntArray(count + 1) }
        cost[0][0] = 0f
        for (k in 1..clusters) {
            for (end in k..count - (clusters - k)) {
                for (start in maxOf(k - 1, end - MAX_LETTERS_PER_CLUSTER) until end) {
                    if (cost[k - 1][start] == Float.MAX_VALUE) continue
                    val miss = actual[k - 1] - (prefix[end] - prefix[start]) / totalExpected
                    val total = cost[k - 1][start] + miss * miss
                    if (total < cost[k][end]) {
                        cost[k][end] = total
                        from[k][end] = start
                    }
                }
            }
        }
        return if (cost[clusters][count] == Float.MAX_VALUE) null else from
    }

    /**
     * How wide a letter usually is, relative to an `n`. Rough on purpose: it only has to rank a
     * three-letter cluster wider than a one-letter one, and the read-back decides the rest.
     */
    internal fun expectedWidth(letter: String): Float {
        val c = letter.firstOrNull() ?: return 1f
        return when {
            c in NARROW -> NARROW_WIDTH
            c in WIDE -> WIDE_WIDTH
            c.isUpperCase() -> CAPITAL_WIDTH
            c.isDigit() -> DIGIT_WIDTH
            !c.isLetterOrDigit() -> MARK_WIDTH
            else -> 1f
        }
    }

    /** More than this many letters in one stroke is a word, not a join, and is left whole. */
    public const val MAX_LETTERS_PER_CLUSTER: Int = 4

    private const val NARROW = "ijlrtf1I"
    private const val WIDE = "mwMW"
    private const val NARROW_WIDTH = 0.5f
    private const val WIDE_WIDTH = 1.5f
    private const val CAPITAL_WIDTH = 1.2f
    private const val DIGIT_WIDTH = 0.9f
    private const val MARK_WIDTH = 0.4f

    private class Cluster(var box: PageRect, val members: MutableList<Int>) {
        fun near(other: PageRect, scale: Float): Boolean {
            val reach = scale * SMALL_REACH
            return other.centreX >= box.left - reach && other.centreX <= box.right + reach
        }
    }

    private fun overlap(a: PageRect, b: PageRect): Float = min(a.right, b.right) - max(a.left, b.left)

    /** Most of the narrower box lies within the other's horizontal span. */
    private fun sideBySide(a: PageRect, b: PageRect): Boolean =
        overlap(a, b) >= min(a.width, b.width).coerceAtLeast(MIN_WIDTH_PT) * OVERLAP_SHARE

    private const val MIN_SCALE_PT = 0.5f
    private const val MIN_WIDTH_PT = 0.25f
    private const val OVERLAP_SHARE = 0.5f
    private const val SMALL_HEIGHT = 0.35f
    private const val SMALL_WIDTH = 0.6f
    private const val SMALL_REACH = 0.25f
}

/**
 * [text] as the units a person writes one at a time — characters, with a surrogate pair kept
 * together. Whitespace is not something anyone writes, so it is dropped.
 */
internal fun writtenUnits(text: String): List<String> {
    val units = ArrayList<String>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val end = if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) i + 2 else i + 1
        if (!c.isWhitespace()) units += text.substring(i, end)
        i = end
    }
    return units
}
