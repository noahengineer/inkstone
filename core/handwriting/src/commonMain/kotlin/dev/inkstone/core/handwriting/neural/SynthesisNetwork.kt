package dev.inkstone.core.handwriting.neural

import dev.inkstone.core.model.PenTrajectory
import kotlin.random.Random

/**
 * ⭐ The synthesis network, as the rest of `:core:handwriting` sees it (docs/HANDWRITING-SYNTHESIS.md
 * §7.4). One implementation today, [KotlinSynthesisNetwork] — pure Kotlin in `commonMain`, the
 * decision §19.1 records — and this interface is the seam LiteRT would go behind if that decision is
 * ever reversed.
 *
 * Everything here is in the **model's** frame: x-heights, y up, baseline 0, deslanted
 * (`NormalFrame`, §5.2). Turning a word into page ink — the frame, timing, pressure and tilt — is
 * [dev.inkstone.core.handwriting.NeuralBackend]'s job.
 */
public interface SynthesisNetwork {
    /** The first eight hex digits of the weights file's SHA-256 — `hw=…:n:<model>` (D2). */
    public val modelId: String

    public val alphabet: String

    /** How many steps a character takes, as the training corpus was sampled. */
    public val pointsPerChar: Float

    /** Whether every character of [text] is one the model was trained to write. */
    public fun canWrite(text: String): Boolean = text.all { it in alphabet }

    /**
     * The state after the network has *read* [prime] — once per drop, before any word, then copied
     * for each (§7.4). [Primed.NONE] writes unprimed.
     */
    public fun prime(prime: Prime?): Primed

    /**
     * [words], each written after [primed], [batch] at a time. Null for a word the runaway guard
     * stopped: a window that wandered off is a failed word, never a shown one (§7.4).
     *
     * @param bias Graves's sampling bias, `b ∈ [0, 2]` and a little past it on a retry: σ → exp(σ̂ − b),
     *   π ∝ exp(π̂(1 + b)). Neatness maps onto it.
     */
    public fun write(
        words: List<String>,
        primed: Primed,
        bias: Float,
        random: Random,
        batch: Int = DEFAULT_BATCH,
    ): List<NetworkWord?>

    /**
     * Teacher-forced over [trajectory] with [text]: for every point, where the attention window was
     * (φ over the text's characters) — v2 segmentation (§5.4). Null when the text holds a character
     * outside [alphabet] or the trajectory is too short to read.
     */
    public fun align(text: String, trajectory: PenTrajectory): Alignment?

    public companion object {
        /** §7.5: four words a batch is the measured sweet spot on the tablet's big cores. */
        public const val DEFAULT_BATCH: Int = 4

        /** §7.4's runaway guard: at most this many steps per character, then the word has failed. */
        public const val MAX_STEPS_PER_CHAR: Int = 40
    }
}

/**
 * Words the network reads before it writes (§7.4, D15's *"primes with the nearest lines"*): their
 * text, and their ink in the model's frame, laid out left to right as one line.
 */
public class Prime(public val text: String, public val ink: PenTrajectory)

/** The network's state after a prime, and the prime's text, which every word is written after. */
public class Primed internal constructor(internal val state: NetState?, internal val text: String) {
    public val isPrimed: Boolean get() = state != null

    public companion object {
        public val NONE: Primed = Primed(null, "")
    }
}

/**
 * One generated word in the model's frame: points in writing order, the pen lifted after each
 * [strokeEnds] entry, one point per network step.
 *
 * @property steps how many steps it took — the cost, and a sanity number for the notes.
 */
public class NetworkWord(
    public val text: String,
    public val x: FloatArray,
    public val y: FloatArray,
    public val strokeEnds: IntArray,
    public val steps: Int,
)

/**
 * Where the window was for each point of a trajectory (§5.4 v2).
 *
 * @property phi `phi[i][u]`: the attention on character `u` when point `i` was written, normalised to
 *   sum to one over the text.
 */
public class Alignment(public val text: String, public val phi: List<FloatArray>) {
    /**
     * ⭐ Each point's character: the monotonic path through [phi] that maximises the summed log
     * attention, starting on the first character, ending on the last, never going back — a Viterbi
     * pass (§5.4). Points that belong to a delayed stroke (an i-dot written after the word) are where
     * monotonicity is wrong by construction, and the notes measure the accuracy with and without them.
     */
    public fun letters(): IntArray {
        val n = phi.size
        val u = text.length
        if (n == 0 || u == 0) return IntArray(n)
        if (n < u) return IntArray(n) { i -> (i * u / n).coerceAtMost(u - 1) }
        val score = Array(n) { DoubleArray(u) { Double.NEGATIVE_INFINITY } }
        val from = Array(n) { BooleanArray(u) } // true: came from u - 1
        score[0][0] = ln(phi[0][0])
        for (i in 1 until n) {
            for (k in 0 until minOf(u, i + 1)) {
                val stay = score[i - 1][k]
                val step = if (k > 0) score[i - 1][k - 1] else Double.NEGATIVE_INFINITY
                from[i][k] = step > stay
                score[i][k] = maxOf(stay, step) + ln(phi[i][k])
            }
        }
        val out = IntArray(n)
        var k = u - 1
        for (i in n - 1 downTo 0) {
            out[i] = k
            if (i > 0 && from[i][k]) k--
        }
        return out
    }

    private fun ln(p: Float): Double = kotlin.math.ln(p.toDouble().coerceAtLeast(FLOOR))

    private companion object {
        const val FLOOR = 1e-12
    }
}
