package dev.inkstone.core.handwriting

import dev.inkstone.core.handwriting.neural.Prime
import dev.inkstone.core.handwriting.neural.Primed
import dev.inkstone.core.handwriting.neural.SynthesisNetwork
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.PenTrajectory
import kotlin.random.Random

/**
 * ⭐ The neural backend — docs/HANDWRITING-SYNTHESIS.md §7, as H4 uses it: it writes **what assembly
 * cannot** (a letter the user has never written, a word assembly could not make legible), and every
 * word it writes is read back by the same gate as assembly's (§8.2). Assembly stays primary;
 * deciding when the model is better is phase 5.
 *
 * - **It primes once per drop** ([prime]): a few of the user's own whole words, this page's first,
 *   then this note's, then recent ones (D15) — read once, the state copied for every word (§7.4).
 * - **Neatness is Graves's bias** ([biasFor]): `b = 2 × neatness`, one step higher per retry.
 * - **A word comes back in assembly's frame** ([Word.trajectory]): x-heights, y up, its ink starting
 *   at x = 0 on baseline 0 — so the gate, layout and the page's frame treat both backends' words
 *   identically, and what the gate read is what is drawn.
 * - **Pressure, tilt and timing** come from the user's own hand ([PenDynamics]).
 */
public class NeuralBackend(private val network: SynthesisNetwork, private val dynamics: PenDynamics) {

    /** `hw=…:n:<model>` — the model's identity on every stroke it writes (D2). */
    public val modelId: String get() = network.modelId

    public fun canWrite(text: String): Boolean = text.isNotEmpty() && network.canWrite(text)

    /** A generated word, ready for the gate. [steps] is the network's cost, for the logs and the notes. */
    public class Word(
        public val text: String,
        public val trajectory: PenTrajectory,
        public val width: Float,
        public val steps: Int,
    )

    /**
     * The state to write a drop's words after: the network having read up to [PRIME_WORDS] of the
     * user's own words, chosen by [preference]. [Primed.NONE] when the profile has none the model can
     * read. Computed once per drop — ahead of the typing, by the panel (§19.1).
     */
    public fun prime(index: GlyphIndex, preference: SourcePreference, rng: Random): Primed {
        val words = primeCandidates(index)
            .groupBy { preference.weightOf(it.source) }
            // Not `toSortedMap`: it is JVM-only, and the iOS klib compile is what said so (guardrail 2).
            .entries
            .sortedByDescending { it.key }
            .flatMap { it.value.shuffled(rng) }
            .distinctBy { it.text }
            .take(PRIME_WORDS)
        return prime(words, index)
    }

    /**
     * The words a prime may be made of. Lower-case words only: this hand writes capitals and digits at
     * x-height (`CaCO3`, `NO2`, §18.7), proportions the synthetic corpus never shows, and a prime is a
     * style to continue.
     */
    public fun primeCandidates(index: GlyphIndex): List<GlyphUnit> = index.wholeWords()
        .filter { canWrite(it.text) && it.text.length >= MIN_PRIME_LETTERS && it.text.all { c -> c.isLowerCase() } }

    /** P1's fixed prime set (§8.1): exactly [words], in order, one line. */
    public fun prime(words: List<GlyphUnit>, index: GlyphIndex): Primed {
        if (words.isEmpty()) return Primed.NONE
        val gap = index.stats.wordGapsOrDefault().sorted().let { it[it.size / 2] }
        var x = 0f
        val placed = words.mapNotNull { unit ->
            unit.trajectory.transformed(Matrix3.translate(x, 0f))?.also { x += unit.width + gap }
        }
        val ink = concatenate(placed) ?: return Primed.NONE
        return network.prime(Prime(words.joinToString(" ") { it.text }, ink))
    }

    /** [words] after [primed], at [neatness]'s bias raised for [attempt]; null where one failed. */
    public fun write(words: List<String>, primed: Primed, neatness: Float, attempt: Int, rng: Random): List<Word?> {
        val written = network.write(words, primed, biasFor(neatness, attempt), rng)
        return written.map { w ->
            w ?: return@map null
            val ink = dynamics.apply(w.x, w.y, w.strokeEnds) ?: return@map null
            val box = ink.bounds()
            val shifted = ink.transformed(Matrix3.translate(-box.left, 0f)) ?: return@map null
            Word(w.text, shifted, box.right - box.left, w.steps)
        }
    }

    public companion object {
        /** §8.2: neural, up to three samples, raising b one step each time. */
        public const val ATTEMPTS: Int = 3
        public const val BIAS_STEP: Float = 0.5f
        private const val MAX_NEAT_BIAS = 2f
        public const val PRIME_WORDS: Int = 3
        private const val MIN_PRIME_LETTERS = 3

        /** §7.4: neatness `0..1` onto Graves's `b ∈ [0, 2]`, one [BIAS_STEP] higher per retry. */
        public fun biasFor(neatness: Float, attempt: Int): Float =
            neatness.coerceIn(0f, 1f) * MAX_NEAT_BIAS + (attempt - 1).coerceAtLeast(0) * BIAS_STEP

        /** Several trajectories as one, on one clock, in order. */
        internal fun concatenate(parts: List<PenTrajectory>): PenTrajectory? {
            if (parts.isEmpty()) return null
            val total = parts.sumOf { it.pointCount }
            val builder = PenTrajectory.Builder(total, parts.all { it.pressure != null }, parts.all { it.tilt != null })
            var clock = 0
            for (part in parts) {
                val base = clock - part.tMs[0]
                for (s in 0 until part.strokeCount) {
                    for (i in part.strokeStart(s) until part.strokeEnd(s)) {
                        builder.add(
                            part.x[i],
                            part.y[i],
                            part.tMs[i] + base,
                            part.pressure?.get(i) ?: 0f,
                            part.tilt?.get(i) ?: 0f,
                        )
                    }
                    builder.endStroke()
                }
                clock = part.tMs.last() + base + PRIME_GAP_MS
            }
            return builder.build()
        }

        private const val PRIME_GAP_MS = 250
    }
}
