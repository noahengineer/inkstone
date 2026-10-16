package dev.inkstone.core.handwriting.neural

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * docs/HANDWRITING-SYNTHESIS.md §7.5 — phase 4.0, **the speed spike**: how fast one step of a
 * Graves-style synthesis network runs in pure Kotlin, on random weights, before anything is built on
 * the answer (*"spike before depending on the unverified"*).
 *
 * One step is the whole of §7.1's network, not just its LSTMs, so the number is the one generation
 * will pay:
 * - `layers` LSTMs of `cells`, each fed the pen offset, the layer below and the attention window
 *   (the first layer gets the previous step's window, as in Graves §5);
 * - the window — `window` Gaussians over a [TEXT_LENGTH]-character text, one-hot over `alphabet`;
 * - a mixture-density output of `mixtures` bivariate Gaussians plus the pen-up probability, read from
 *   every layer;
 * - a sample drawn from it, which becomes the next step's input.
 *
 * The style vector (§7.1) is constant for a word, so its contribution to every gate is a bias computed
 * once per word; it costs nothing per step and is left out here. That is a property of the design, not
 * a shortcut of the spike.
 *
 * [Kernel.UNROLLED] is the candidate kernel (§7.4): a column-wise multiply-add over the output
 * dimension, four input rows at a time, with every word of a batch reusing each row while it is in
 * cache. [Kernel.SIMPLE] is the one-row loop, kept so the table can say what the unrolling bought.
 *
 * Throwaway by intent. Whatever replaces it is written after the decision is recorded (§12, H4).
 */
// The literals are the network's own shape — four LSTM gates, three window parameters, six numbers
// per mixture component — and read more plainly inline than as eight constants in a throwaway file.
@Suppress("MagicNumber")
public object SpeedSpike {

    /** §7.1's size knobs. The defaults are the design's: ~80 characters, 10 Gaussians, 20 components. */
    public class Shape(
        public val layers: Int,
        public val cells: Int,
        public val alphabet: Int = 80,
        public val window: Int = 10,
        public val mixtures: Int = 20,
    ) {
        /** Weights, counted the way the shipped file would hold them (style excluded). */
        public val parameters: Int
            get() {
                val gates = 4 * cells
                var total = 0
                for (l in 0 until layers) total += (inputWidth(l) + cells + 1) * gates
                total += (cells + 1) * 3 * window
                total += (layers * cells + 1) * outputs
                return total
            }

        internal val outputs: Int get() = 6 * mixtures + 1

        internal fun inputWidth(layer: Int): Int = PEN + alphabet + if (layer == 0) 0 else cells

        override fun toString(): String = "${layers}x$cells"
    }

    public enum class Kernel { SIMPLE, UNROLLED }

    /** Microseconds per step for [batch] words stepping together, best and median of [rounds]. */
    public class StepTime(
        public val shape: Shape,
        public val batch: Int,
        public val kernel: Kernel,
        public val bestMicros: Double,
        public val medianMicros: Double,
    )

    /**
     * One phrase, generated the way §7.4 describes: a prime of [primeSteps] at batch 1, once, then the
     * words in batches of [batch], each batch running until its longest word is done.
     *
     * @property firstWordMillis the prime plus the first batch — the earliest any word is complete.
     */
    public class PhraseTime(
        public val shape: Shape,
        public val batch: Int,
        public val pointsPerChar: Int,
        public val totalMillis: Double,
        public val firstWordMillis: Double,
        public val steps: Int,
    )

    /** Times [steps] steps after [warmup] untimed ones, [rounds] times over. */
    public fun stepTime(
        shape: Shape,
        batch: Int,
        kernel: Kernel = Kernel.UNROLLED,
        steps: Int = 400,
        warmup: Int = 200,
        rounds: Int = 5,
        seed: Long = 1L,
    ): StepTime {
        val net = Net(shape, batch, kernel, Random(seed))
        repeat(warmup) { net.step() }
        val samples = DoubleArray(rounds) {
            val mark = TimeSource.Monotonic.markNow()
            repeat(steps) { net.step() }
            mark.elapsedNow().inWholeNanoseconds / NANOS_PER_MICRO / steps
        }
        samples.sort()
        return StepTime(shape, batch, kernel, samples.first(), samples[rounds / 2])
    }

    /**
     * Times one phrase end to end. [words] are its words' lengths in characters; each word is
     * written as a space and then the word, so it costs `pointsPerChar × (length + 1)` steps.
     */
    public fun phraseTime(
        shape: Shape,
        words: List<Int>,
        batch: Int,
        pointsPerChar: Int,
        primeChars: Int,
        seed: Long = 1L,
    ): PhraseTime {
        val prime = Net(shape, 1, Kernel.UNROLLED, Random(seed))
        val writer = Net(shape, batch, Kernel.UNROLLED, Random(seed))
        repeat(WARM_PHRASE) {
            prime.step()
            writer.step()
        }
        var steps = 0
        val mark = TimeSource.Monotonic.markNow()
        repeat(primeChars * pointsPerChar) {
            prime.step()
            steps++
        }
        var first = -1.0
        for (group in words.chunked(batch)) {
            val longest = group.max() + 1
            repeat(longest * pointsPerChar) {
                writer.step()
                steps++
            }
            if (first < 0) first = mark.elapsedNow().inWholeNanoseconds / NANOS_PER_MILLI
        }
        val total = mark.elapsedNow().inWholeNanoseconds / NANOS_PER_MILLI
        return PhraseTime(shape, batch, pointsPerChar, total, first, steps)
    }

    private const val PEN = 3
    private const val TEXT_LENGTH = 24
    private const val WARM_PHRASE = 200
    private const val NANOS_PER_MICRO = 1_000.0
    private const val NANOS_PER_MILLI = 1_000_000.0
    private const val WEIGHT_SCALE = 0.08f

    /** The network, its state for [batch] words, and the scratch one step needs — all preallocated. */
    private class Net(private val shape: Shape, private val batch: Int, private val kernel: Kernel, rng: Random) {
        private val h = shape.cells
        private val gates = 4 * h
        private val k = shape.window
        private val a = shape.alphabet

        private val wIn = Array(shape.layers) { random(shape.inputWidth(it) * gates, rng) }
        private val wRec = Array(shape.layers) { random(h * gates, rng) }
        private val bias = Array(shape.layers) { random(gates, rng) }
        private val wWin = random(h * 3 * k, rng)
        private val bWin = random(3 * k, rng)
        private val wOut = random(shape.layers * h * shape.outputs, rng)
        private val bOut = random(shape.outputs, rng)
        private val text = IntArray(TEXT_LENGTH) { rng.nextInt(a) }

        private val hs = Array(shape.layers) { FloatArray(batch * h) }
        private val cs = Array(shape.layers) { FloatArray(batch * h) }
        private val pen = FloatArray(batch * PEN)
        private val kappa = FloatArray(batch * k)
        private val win = FloatArray(batch * a)
        private val z = FloatArray(batch * gates)
        private val input = Array(shape.layers) { FloatArray(batch * shape.inputWidth(it)) }
        private val winParams = FloatArray(batch * 3 * k)
        private val concat = FloatArray(batch * shape.layers * h)
        private val out = FloatArray(batch * shape.outputs)
        private val sampler = Random(rng.nextLong())

        fun step() {
            for (l in 0 until shape.layers) {
                gatherInput(l)
                lstm(l)
                if (l == 0) window()
            }
            output()
            sample()
        }

        /** The layer's input row per word: the pen offset, the window, and the layer below. */
        private fun gatherInput(l: Int) {
            val width = shape.inputWidth(l)
            val dst = input[l]
            for (b in 0 until batch) {
                val row = b * width
                pen.copyInto(dst, row, b * PEN, b * PEN + PEN)
                win.copyInto(dst, row + PEN, b * a, b * a + a)
                if (l > 0) hs[l - 1].copyInto(dst, row + PEN + a, b * h, b * h + h)
            }
        }

        private fun lstm(l: Int) {
            for (b in 0 until batch) bias[l].copyInto(z, b * gates)
            matVecAdd(wIn[l], shape.inputWidth(l), gates, input[l], z)
            matVecAdd(wRec[l], h, gates, hs[l], z)
            val hl = hs[l]
            val cl = cs[l]
            for (b in 0 until batch) {
                val zb = b * gates
                val sb = b * h
                for (j in 0 until h) {
                    val i = sigmoid(z[zb + j])
                    val f = sigmoid(z[zb + h + j])
                    val g = tanh(z[zb + 2 * h + j])
                    val o = sigmoid(z[zb + 3 * h + j])
                    val c = f * cl[sb + j] + i * g
                    cl[sb + j] = c
                    hl[sb + j] = o * tanh(c)
                }
            }
        }

        /** Graves eqs. 46–51: K Gaussians over the text, read off the first layer. */
        private fun window() {
            for (b in 0 until batch) bWin.copyInto(winParams, b * 3 * k)
            matVecAdd(wWin, h, 3 * k, hs[0], winParams)
            win.fill(0f)
            for (b in 0 until batch) {
                val p = b * 3 * k
                for (j in 0 until k) kappa[b * k + j] += exp(winParams[p + 2 * k + j])
                for (u in text.indices) {
                    var phi = 0f
                    for (j in 0 until k) {
                        val d = kappa[b * k + j] - u
                        phi += exp(winParams[p + j]) * exp(-exp(winParams[p + k + j]) * d * d)
                    }
                    win[b * a + text[u]] += phi
                }
            }
        }

        /** Every layer feeds the output (Graves §5). */
        private fun output() {
            val all = shape.layers * h
            for (b in 0 until batch) {
                for (l in 0 until shape.layers) hs[l].copyInto(concat, b * all + l * h, b * h, b * h + h)
                bOut.copyInto(out, b * shape.outputs)
            }
            matVecAdd(wOut, all, shape.outputs, concat, out)
        }

        /** Graves eqs. 18–23: pick a component, draw from it, and decide the pen. */
        private fun sample() {
            val m = shape.mixtures
            for (b in 0 until batch) {
                val o = b * shape.outputs
                var total = 0f
                for (j in 0 until m) total += exp(out[o + 1 + j])
                var pick = sampler.nextFloat() * total
                var c = 0
                while (c < m - 1) {
                    pick -= exp(out[o + 1 + c])
                    if (pick <= 0f) break
                    c++
                }
                val sx = exp(out[o + 1 + 3 * m + c])
                val sy = exp(out[o + 1 + 4 * m + c])
                val rho = tanh(out[o + 1 + 5 * m + c])
                val n1 = gaussian()
                val n2 = gaussian()
                pen[b * PEN] = (out[o + 1 + m + c] + sx * n1).coerceIn(-CLAMP, CLAMP)
                pen[b * PEN + 1] = (out[o + 1 + 2 * m + c] + sy * (rho * n1 + sqrt(1 - rho * rho) * n2))
                    .coerceIn(-CLAMP, CLAMP)
                pen[b * PEN + 2] = if (sampler.nextFloat() < sigmoid(out[o])) 1f else 0f
            }
        }

        private fun gaussian(): Float {
            val u = sampler.nextFloat().coerceAtLeast(TINY)
            return (sqrt(-2f * ln(u)) * cos(2f * PI.toFloat() * sampler.nextFloat()))
        }

        /**
         * `y[b] += Wᵀ·x[b]` for every word `b`, with `W` stored row-major by input (`rows × cols`).
         * Each input row of `W` is read once per call and reused by every word while it is in cache.
         */
        private fun matVecAdd(w: FloatArray, rows: Int, cols: Int, x: FloatArray, y: FloatArray) {
            if (kernel == Kernel.SIMPLE) simple(w, rows, cols, x, y) else unrolled(w, rows, cols, x, y)
        }

        private fun simple(w: FloatArray, rows: Int, cols: Int, x: FloatArray, y: FloatArray) {
            for (j in 0 until rows) {
                val r = j * cols
                for (b in 0 until batch) {
                    val xv = x[b * rows + j]
                    val yb = b * cols
                    for (i in 0 until cols) y[yb + i] += w[r + i] * xv
                }
            }
        }

        private fun unrolled(w: FloatArray, rows: Int, cols: Int, x: FloatArray, y: FloatArray) {
            var j = 0
            while (j + UNROLL <= rows) {
                val r0 = j * cols
                val r1 = r0 + cols
                val r2 = r1 + cols
                val r3 = r2 + cols
                for (b in 0 until batch) {
                    val xb = b * rows + j
                    val x0 = x[xb]
                    val x1 = x[xb + 1]
                    val x2 = x[xb + 2]
                    val x3 = x[xb + 3]
                    val yb = b * cols
                    for (i in 0 until cols) {
                        y[yb + i] += w[r0 + i] * x0 + w[r1 + i] * x1 + w[r2 + i] * x2 + w[r3 + i] * x3
                    }
                }
                j += UNROLL
            }
            while (j < rows) {
                val r = j * cols
                for (b in 0 until batch) {
                    val xv = x[b * rows + j]
                    val yb = b * cols
                    for (i in 0 until cols) y[yb + i] += w[r + i] * xv
                }
                j++
            }
        }

        private companion object {
            const val UNROLL = 4
            const val CLAMP = 50f
            const val TINY = 1e-7f

            fun random(n: Int, rng: Random): FloatArray = FloatArray(n) { (rng.nextFloat() * 2f - 1f) * WEIGHT_SCALE }

            fun sigmoid(v: Float): Float = 1f / (1f + exp(-v))
        }
    }
}
