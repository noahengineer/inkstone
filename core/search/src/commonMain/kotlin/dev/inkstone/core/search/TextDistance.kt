package dev.inkstone.core.search

import kotlin.math.min

/**
 * How far a recognised page is from what was written. Used by M7's accuracy harness, and
 * pure so that the harness's *arithmetic* can be trusted without a device even though its
 * *inputs* need one.
 *
 * Two measures, because they answer different questions and M7 turns on both:
 *
 * - [characterErrorRate] is the standard handwriting-recognition figure and the one to
 *   compare against published numbers.
 * - [wordRecall] is the one this milestone is actually for. Search does not care whether the
 *   punctuation came out right; it cares whether the word you type is in the index. A page
 *   can have a poor CER and still be perfectly findable, and the two numbers moving apart is
 *   information rather than noise.
 */
public object TextDistance {

    /** Levenshtein distance over [reference] and [hypothesis], divided by the reference length. */
    public fun characterErrorRate(reference: String, hypothesis: String): Double {
        if (reference.isEmpty()) return if (hypothesis.isEmpty()) 0.0 else 1.0
        return levenshtein(reference, hypothesis).toDouble() / reference.length
    }

    /** Fraction of [reference]'s words that appear anywhere in [hypothesis], case-folded. */
    public fun wordRecall(reference: String, hypothesis: String): Double {
        val wanted = words(reference)
        if (wanted.isEmpty()) return 1.0
        // A bag, not a set: a page saying "the" nine times and a hypothesis saying it once
        // has not recalled all nine, and scoring it 1.0 would flatter every arm equally but
        // flatter the worse one more.
        val found = words(hypothesis).groupingBy { it }.eachCount().toMutableMap()
        var hits = 0
        for (word in wanted) {
            val left = found[word] ?: 0
            if (left > 0) {
                found[word] = left - 1
                hits++
            }
        }
        return hits.toDouble() / wanted.size
    }

    /**
     * Lowercased, punctuation-stripped tokens — the same normalisation `SearchIndex` uses, so
     * this measures what search will actually be able to find rather than an idealised string.
     */
    public fun words(text: String): List<String> = text.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

    /** Two rows rather than a full matrix: a page of text against a page of text is 4 000² cells. */
    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
}
