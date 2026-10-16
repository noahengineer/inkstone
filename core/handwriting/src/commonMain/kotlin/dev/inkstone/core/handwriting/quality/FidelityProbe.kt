package dev.inkstone.core.handwriting.quality

import dev.inkstone.core.handwriting.GlyphIndex
import dev.inkstone.core.handwriting.LabelBook
import dev.inkstone.core.handwriting.SampleSplit
import dev.inkstone.core.handwriting.UnitKind
import dev.inkstone.core.handwriting.WordSample
import dev.inkstone.core.model.PenTrajectory

/** One of the user's held-out words (§5.5), as fidelity reads it: its label and its ink, normalised. */
public class HeldOutWord(public val sampleId: String, public val label: String, public val ink: PenTrajectory)

/**
 * ⭐ What §8.3 measures against: the user's **held-out** words, and how far the user is from
 * themselves.
 *
 * Held-out words are never primed with, trained on, or assembled from (§5.5), so a backend's distance
 * to one is a distance to writing it has never seen. Only words [GlyphIndex.normalisedWord] would use
 * are kept — a word whose frame is wrong would charge every backend for the normaliser's mistake.
 *
 * **d_self** — the user's own variability — is the median distance between two of the user's samples
 * of the same word, over **all** samples, held-out or not: it is a fact about the hand, not a thing
 * learned from. Where the profile has no second sample of a word, it is pooled from the letters the
 * word is made of ([selfOf]), as §8.3 says.
 */
public class FidelitySet private constructor(
    public val words: List<HeldOutWord>,
    private val byWord: Map<String, Float>,
    private val byLetter: Map<String, Float>,
    /** The median over every same-word pair in the profile: the last fallback. */
    public val pooledSelf: Float?,
    /** How many same-word pairs there were — the notes report it, since d_self rests on it. */
    public val wordPairs: Int,
) {
    /**
     * d_self for [label]: its own pairs where there are any; else the median of its letters' pooled
     * self-distances, when every letter has one; else [pooledSelf].
     */
    public fun selfOf(label: String): Float? {
        byWord[label]?.let { return it }
        val letters = label.map { it.toString() }
        val perLetter = letters.mapNotNull { byLetter[it] }
        if (perLetter.isNotEmpty() && perLetter.size == letters.size) return median(perLetter)
        return pooledSelf
    }

    public companion object {
        /** Pairs per word or letter, at most: the self-distance is a median, and more pairs cost DTWs. */
        private const val MAX_PAIRS = 12

        public fun of(samples: List<WordSample>, labels: LabelBook, index: GlyphIndex): FidelitySet {
            val live = samples.filter { it.sourceKey !in labels.rejected && it.noteId.value !in labels.excludedNotes }
            val normal = live.mapNotNull { s -> GlyphIndex.normalisedWord(s, labels)?.let { s to it } }
            val words = normal.filter { it.first.split == SampleSplit.HELD_OUT }
                .map { (s, ink) -> HeldOutWord(s.id, labels.labelOf(s), ink) }
            val byWord = HashMap<String, Float>()
            val allPairs = ArrayList<Float>()
            for ((label, group) in normal.groupBy { labels.labelOf(it.first) }) {
                val d = pairDistances(group.map { it.second })
                if (d.isNotEmpty()) {
                    byWord[label] = median(d)
                    allPairs += d
                }
            }
            val byLetter = HashMap<String, Float>()
            for (letter in index.alphabet) {
                val inks = index.variants(letter).filter { it.kind == UnitKind.LETTER }.map { it.trajectory }
                val d = pairDistances(inks)
                if (d.isNotEmpty()) byLetter[letter] = median(d)
            }
            return FidelitySet(
                words,
                byWord,
                byLetter,
                allPairs.takeIf {
                    it.isNotEmpty()
                }?.let(::median),
                allPairs.size,
            )
        }

        private fun pairDistances(inks: List<PenTrajectory>): List<Float> {
            val out = ArrayList<Float>()
            loop@ for (i in inks.indices) {
                for (j in i + 1 until inks.size) {
                    Fidelity.distance(inks[i], inks[j])?.let { out += it }
                    if (out.size >= MAX_PAIRS) break@loop
                }
            }
            return out
        }
    }
}

/**
 * Writes words for fidelity to measure, in the normalised frame with their ink starting at x = 0: one
 * trajectory per label asked for, null where the backend could not write it. A batch, because the
 * network writes four words for little more than the cost of one (§19.0).
 */
public fun interface WordWriter {
    public suspend fun write(labels: List<String>): List<PenTrajectory?>
}

/** F for one held-out word. [f] is null when §8.3's ratio cannot be said ([Fidelity.score]). */
public class WordFidelity(
    public val sampleId: String,
    public val label: String,
    public val dGeneric: Float,
    public val dBackend: Float,
    public val dSelf: Float,
    public val f: Float?,
)

/**
 * A backend's fidelity over a [FidelitySet].
 *
 * @property median §8.3's F_B: the median of [perWord]'s scores — null with none.
 * @property written how many held-out words the backend could write at all. A word it cannot write has
 *   no F; it is counted here, so a backend that writes three easy words is not mistaken for a good one.
 */
public class FidelityResult(
    public val perWord: List<WordFidelity>,
    public val median: Float?,
    public val written: Int,
    public val asked: Int,
) {
    public val coverage: Float get() = if (asked == 0) 0f else written.toFloat() / asked
}

/**
 * ⭐ §8.3's measurement: per held-out word, [samples] writings from each side, median distance to the
 * user's own; F per word; the median over words.
 */
public object FidelityProbe {
    /** §8.3: at least three samples per word. */
    public const val SAMPLES: Int = 3

    /**
     * d_generic per held-out word (keyed by sample id): the median distance from [samples] unprimed,
     * unpersonalised network writings (P0 only) to the user's word. The zero of every F. Computed once
     * per base model and held-out set.
     */
    public suspend fun generic(set: FidelitySet, writer: WordWriter, samples: Int = SAMPLES): Map<String, Float> =
        distances(set, writer, samples)

    public suspend fun measure(
        set: FidelitySet,
        generic: Map<String, Float>,
        writer: WordWriter,
        samples: Int = SAMPLES,
    ): FidelityResult {
        val d = distances(set, writer, samples)
        val perWord = set.words.mapNotNull { word ->
            val dB = d[word.sampleId] ?: return@mapNotNull null
            val dG = generic[word.sampleId] ?: return@mapNotNull null
            val dS = set.selfOf(word.label) ?: return@mapNotNull null
            WordFidelity(word.sampleId, word.label, dG, dB, dS, Fidelity.score(dG, dB, dS))
        }
        val scores = perWord.mapNotNull { it.f }
        return FidelityResult(perWord, scores.takeIf { it.isNotEmpty() }?.let(::median), d.size, set.words.size)
    }

    /** Per held-out word: the median distance of [samples] writings to the user's own. */
    private suspend fun distances(set: FidelitySet, writer: WordWriter, samples: Int): Map<String, Float> {
        val labels = set.words.map { it.label }
        val runs = List(samples) { writer.write(labels) }
        val out = HashMap<String, Float>()
        set.words.forEachIndexed { i, word ->
            val ds = runs.mapNotNull { run -> run.getOrNull(i)?.let { Fidelity.distance(it, word.ink) } }
            if (ds.isNotEmpty()) out[word.sampleId] = median(ds)
        }
        return out
    }
}

internal fun median(values: List<Float>): Float {
    val sorted = values.sorted()
    val n = sorted.size
    return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
}
