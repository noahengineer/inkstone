package dev.inkstone.core.handwriting.neural

import dev.inkstone.core.model.PenTrajectory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/** One sequence's recurrent state: every layer's h and c, the window's position, and its last vector. */
internal class NetState(val h: Array<FloatArray>, val c: Array<FloatArray>, val kappa: FloatArray, val w: FloatArray) {
    /** The input that follows the prime: its last point, so a word continues the line (§7.4). */
    var lastInput: FloatArray = floatArrayOf(0f, 0f, 1f)

    /** Where the prime's pen ended, in the model's frame — a word's points are placed from here. */
    var lastX: Float = 0f
    var lastY: Float = 0f
}

/**
 * ⭐ docs/HANDWRITING-SYNTHESIS.md §7.4 — the synthesis network in pure Kotlin, the decision §19.1
 * records. `tools/handwriting/model.py` is the reference; [SynthesisNetworkGoldenTest] holds this to
 * export.py's golden vectors at 1e-4, and nothing here is "close to" Python — every choice is its:
 *
 * - **layer 1** reads `[x_t, w_{t-1}, s]`, the window is read off it, and **layer l > 1** reads
 *   `[x_t, h^{l-1}_t, w_t, s]`; the output reads every layer;
 * - gates in PyTorch's order `(i, f, g, o)`, with its two biases summed;
 * - α = exp, β = exp, κ_t = κ_{t−1} + exp(k)/kappaScale; φ(u) = Σ_k α_k exp(−β_k (κ_k − u)²) over
 *   `u = 0..U`, and `w_t = Σ_{u<U} φ(u) c_u`;
 * - the mixture `[e | π | μx | μy | σx | σy | ρ]` with Graves's bias.
 *
 * Three things are cheaper than the reference and exact:
 * - **the style vector** is constant per word, so its share of every gate is folded into the layer's
 *   bias once, at construction;
 * - **the window's one-hot input** is applied as one row per character of the text, not a dense
 *   80-row product;
 * - **every weight matrix** is stored transposed (row per input), for [KernelMath.matVecAdd]'s
 *   column-wise multiply-add — the spike's kernel (§19.0).
 *
 * Safe to share between threads: the weights are read-only after construction, and every [prime],
 * [write] and [align] allocates its own batch state. Construction costs one pass over the weights,
 * about 7 MB of floats for 3×256.
 */
public class KotlinSynthesisNetwork(weights: WeightsFile, style: FloatArray? = null, adapters: Adapters? = null) :
    SynthesisNetwork {

    private val shape = weights.header.shape
    private val nLayers = shape.layers
    private val cells = shape.cells
    private val gates = shape.gates
    private val nWindow = shape.window
    private val mixtures = shape.mixtures
    private val letters = shape.alphabetSize
    private val outputs = shape.outputs
    private val kappaScale = shape.kappaScale
    private val mean = weights.header.normMean
    private val std = weights.header.normStd

    override val modelId: String = weights.modelId
    override val alphabet: String = weights.header.alphabet
    override val pointsPerChar: Float = weights.header.pointsPerChar
    private val indexOf: Map<Char, Int> = alphabet.withIndex().associate { it.value to it.index }

    private val denseRows = IntArray(nLayers) { PEN + (if (it == 0) 0 else cells) + cells }
    private val dense: Array<FloatArray>
    private val windowRows: Array<FloatArray>
    private val bias: Array<FloatArray>
    private val winW: FloatArray
    private val winB: FloatArray
    private val outW: FloatArray
    private val outB: FloatArray

    init {
        val s = style ?: weights.tensor("style.default").second
        require(s.size == shape.styleDim) { "style has ${s.size}, the model ${shape.styleDim}" }
        dense = Array(nLayers) { FloatArray(denseRows[it] * gates) }
        windowRows = Array(nLayers) { FloatArray(letters * gates) }
        bias = Array(nLayers) { FloatArray(gates) }
        // P3 (§8.1): the adapters are folded into the weights they adapt, once — so a personalised network
        // costs nothing more per step than the base one.
        val adapted = adapters?.takeIf { it.fits(shape) }
        for (l in 0 until nLayers) {
            val (ihShape, ihBase) = weights.tensor("l$l.w_ih")
            val hhBase = weights.tensor("l$l.w_hh").second
            val b = weights.tensor("l$l.b").second
            val inCols = ihShape[1]
            val top = adapted?.takeIf { l == nLayers - 1 }
            val ih = if (top != null) Adapters.merged(ihBase, gates, inCols, top.ihB, top.ihA, top.rank) else ihBase
            val hh = if (top != null) Adapters.merged(hhBase, gates, cells, top.hhB, top.hhA, top.rank) else hhBase
            val below = if (l == 0) 0 else cells
            val wStart = PEN + below
            val sStart = wStart + letters
            for (g in 0 until gates) {
                val row = g * inCols
                for (r in 0 until PEN + below) dense[l][r * gates + g] = ih[row + r]
                for (r in 0 until cells) dense[l][(PEN + below + r) * gates + g] = hh[g * cells + r]
                for (a in 0 until letters) windowRows[l][a * gates + g] = ih[row + wStart + a]
                var acc = b[g]
                for (j in s.indices) acc += ih[row + sStart + j] * s[j]
                bias[l][g] = acc
            }
        }
        val win = NetworkShape.WINDOW_PARAMS * nWindow
        val ww = weights.tensor("window.w").second
        winW = FloatArray(cells * win) {
            val r = it / win
            val p = it % win
            ww[p * cells + r]
        }
        winB = weights.tensor("window.b").second.copyOf()
        val all = nLayers * cells
        val owBase = weights.tensor("out.w").second
        val ow = if (adapted !=
            null
        ) {
            Adapters.merged(owBase, outputs, all, adapted.outB, adapted.outA, adapted.rank)
        } else {
            owBase
        }
        outW = FloatArray(all * outputs) {
            val r = it / outputs
            val o = it % outputs
            ow[o * all + r]
        }
        outB = weights.tensor("out.b").second.copyOf()
    }

    override fun prime(prime: Prime?): Primed {
        if (prime == null || prime.text.isEmpty() || !canWrite(prime.text)) return Primed.NONE
        val inputs = ModelInk.inputs(prime.ink, prime.text.count { it != ' ' }, pointsPerChar, mean, std)
            ?: return Primed.NONE
        val batch = Batch(listOf(codes(prime.text)), null)
        val n = inputs.points
        // [start, p1 … p(N−1)] as in training; pN is held back as the first word's first input.
        for (t in 0 until n) {
            inputs.inputAt(t, batch.x)
            step(batch)
        }
        catchUp(batch, prime.text.length)
        val state = batch.snapshot(0)
        state.lastInput = inputs.values.copyOfRange((n - 1) * PEN, n * PEN)
        state.lastX = inputs.lastX
        state.lastY = inputs.lastY
        return Primed(state, prime.text)
    }

    /**
     * ⭐ §18.10 — after the prime, the window must stand at the end of the prime's text.
     *
     * Each word is written as *prime text, a space, the word*, so the network carries on from wherever
     * its window stopped reading the prime. On the user's real ink — a hand unlike the synthetic
     * writers it learned from — the window **lags** the ink it is fed, and generation then began by
     * writing out the rest of the prime: `=` came out as *"mass ="*, *"ove ="*, *"ned ="*, `1/2` as
     * *"lass 1/2"*. The gate refuses such a word, so this never reached a page, but it spent the
     * ladder's attempts on it — and it is why priming *lowered* the pass rate in §19.7.
     *
     * So the window is moved forward, all [nWindow] components together, until its α-weighted centre is
     * on the space after the prime ([length]); it is never moved back. The ink and the LSTM state —
     * the style, which is what a prime is for — are untouched.
     */
    private fun catchUp(batch: Batch, length: Int) = catchUp(batch.kappa, batch.winParams, nWindow, length)

    override fun write(
        words: List<String>,
        primed: Primed,
        bias: Float,
        random: Random,
        batch: Int,
    ): List<NetworkWord?> {
        val out = arrayOfNulls<NetworkWord>(words.size)
        val writable = words.indices.filter { words[it].isNotEmpty() && canWrite(words[it]) }
        for (chunk in writable.chunked(batch.coerceAtLeast(1))) {
            val texts = chunk.map { codes(if (primed.text.isEmpty()) words[it] else primed.text + " " + words[it]) }
            val b = Batch(texts, primed.state)
            val result = generate(b, chunk.map { words[it].length + 1 }, bias, random, primed.state)
            chunk.forEachIndexed { k, i -> out[i] = result[k]?.let { word(words[i], it, primed.state) } }
        }
        return out.toList()
    }

    override fun align(text: String, trajectory: PenTrajectory): Alignment? {
        if (text.isEmpty() || !canWrite(text)) return null
        val inputs = ModelInk.inputs(trajectory, text.count { it != ' ' }, pointsPerChar, mean, std) ?: return null
        val batch = Batch(listOf(codes(text)), null)
        val u = text.length
        val phis = ArrayList<FloatArray>(inputs.points)
        for (t in 0 until inputs.points) {
            // The window at step t is where the network looked while writing point t.
            inputs.inputAt(t, batch.x)
            step(batch)
            val row = FloatArray(u)
            var sum = 0f
            for (k in 0 until u) {
                row[k] = batch.phi[k]
                sum += row[k]
            }
            if (sum > 0f) for (k in 0 until u) row[k] /= sum
            phis += row
        }
        return Alignment(text, inputs.spread(phis, trajectory))
    }

    /**
     * Teacher-forced over [inputs] — normalised `(dx, dy, lift)` per step — with [text]: every layer's
     * h and c, the window's κ, w and φ, and the raw outputs, at each step in [at]. Exactly what
     * `export.py`'s golden vectors record, so a parity test outside this module can hold the shipped
     * weights to them (§7.3).
     */
    public fun trace(text: String, inputs: List<FloatArray>, at: Set<Int>): List<StepTrace> {
        val batch = Batch(listOf(codes(text)), null)
        val out = ArrayList<StepTrace>()
        for ((t, input) in inputs.withIndex()) {
            input.copyInto(batch.x, 0, 0, PEN)
            step(batch)
            if (t !in at) continue
            out += StepTrace(
                step = t,
                h = batch.hs.map { it.copyOf() },
                c = batch.cs.map { it.copyOf() },
                kappa = batch.kappa.copyOf(),
                w = batch.w.copyOf(),
                phi = batch.phi.copyOf(),
                y = batch.y.copyOf(),
            )
        }
        return out
    }

    /** One step of [trace]. */
    public class StepTrace(
        public val step: Int,
        public val h: List<FloatArray>,
        public val c: List<FloatArray>,
        public val kappa: FloatArray,
        public val w: FloatArray,
        public val phi: FloatArray,
        public val y: FloatArray,
    )

    private fun codes(text: String): IntArray = IntArray(text.length) { indexOf.getValue(text[it]) }

    // --- One step, for a batch --------------------------------------------------------------------------

    /** Everything a step reads and writes for [texts].size sequences, preallocated. */
    internal inner class Batch(val texts: List<IntArray>, from: NetState?) {
        val n = texts.size
        val hs = Array(nLayers) { FloatArray(n * cells) }
        val cs = Array(nLayers) { FloatArray(n * cells) }
        val kappa = FloatArray(n * nWindow)
        val w = FloatArray(n * letters)
        val x = FloatArray(n * PEN)
        private val uMax = texts.maxOf { it.size }
        val phiStride = uMax + 1
        val phi = FloatArray(n * phiStride)
        val input = Array(nLayers) { FloatArray(n * denseRows[it]) }
        val z = FloatArray(n * gates)
        val winParams = FloatArray(n * NetworkShape.WINDOW_PARAMS * nWindow)
        val concat = FloatArray(n * nLayers * cells)
        val y = FloatArray(n * outputs)
        val pi = FloatArray(mixtures)

        init {
            if (from != null) {
                for (i in 0 until n) {
                    for (l in 0 until nLayers) {
                        from.h[l].copyInto(hs[l], i * cells)
                        from.c[l].copyInto(cs[l], i * cells)
                    }
                    from.kappa.copyInto(kappa, i * nWindow)
                    from.w.copyInto(w, i * letters)
                }
            }
        }

        fun snapshot(i: Int): NetState = NetState(
            Array(nLayers) { hs[it].copyOfRange(i * cells, (i + 1) * cells) },
            Array(nLayers) { cs[it].copyOfRange(i * cells, (i + 1) * cells) },
            kappa.copyOfRange(i * nWindow, (i + 1) * nWindow),
            w.copyOfRange(i * letters, (i + 1) * letters),
        )
    }

    /** One step: every layer, the window after the first, then the output. Fills `y` and `phi`. */
    internal fun step(b: Batch) {
        for (l in 0 until nLayers) {
            gather(b, l)
            for (i in 0 until b.n) bias[l].copyInto(b.z, i * gates)
            KernelMath.matVecAdd(dense[l], denseRows[l], gates, b.input[l], b.z, b.n)
            addWindow(b, l)
            cell(b, l)
            if (l == 0) window(b)
        }
        output(b)
    }

    /** `[x, h^{l-1}, h^l_prev]` per word — the window is added separately, a row per character. */
    private fun gather(b: Batch, l: Int) {
        val width = denseRows[l]
        val dst = b.input[l]
        for (i in 0 until b.n) {
            val row = i * width
            b.x.copyInto(dst, row, i * PEN, i * PEN + PEN)
            var at = row + PEN
            if (l > 0) {
                b.hs[l - 1].copyInto(dst, at, i * cells, (i + 1) * cells)
                at += cells
            }
            b.hs[l].copyInto(dst, at, i * cells, (i + 1) * cells)
        }
    }

    /** The window's contribution: layer 1 reads the last step's, every other layer this step's (Graves §5). */
    private fun addWindow(b: Batch, l: Int) {
        val rows = windowRows[l]
        for (i in 0 until b.n) {
            val zb = i * gates
            for (a in 0 until letters) {
                val v = b.w[i * letters + a]
                if (v == 0f) continue
                val r = a * gates
                for (g in 0 until gates) b.z[zb + g] += rows[r + g] * v
            }
        }
    }

    private fun cell(b: Batch, l: Int) {
        val h = b.hs[l]
        val c = b.cs[l]
        for (i in 0 until b.n) {
            val zb = i * gates
            val sb = i * cells
            for (j in 0 until cells) {
                val gi = sigmoid(b.z[zb + j])
                val gf = sigmoid(b.z[zb + GATE_F * cells + j])
                val gg = tanh(b.z[zb + GATE_G * cells + j])
                val go = sigmoid(b.z[zb + GATE_O * cells + j])
                val next = gf * c[sb + j] + gi * gg
                c[sb + j] = next
                h[sb + j] = go * tanh(next)
            }
        }
    }

    private fun window(b: Batch) {
        val k = nWindow
        val win = NetworkShape.WINDOW_PARAMS * k
        for (i in 0 until b.n) winB.copyInto(b.winParams, i * win)
        KernelMath.matVecAdd(winW, cells, win, b.hs[0], b.winParams, b.n)
        b.w.fill(0f)
        for (i in 0 until b.n) {
            val p = i * win
            val kb = i * k
            for (j in 0 until k) b.kappa[kb + j] += exp(b.winParams[p + 2 * k + j]) / kappaScale
            val text = b.texts[i]
            for (u in 0..text.size) {
                var phi = 0f
                for (j in 0 until k) {
                    val d = b.kappa[kb + j] - u
                    phi += exp(b.winParams[p + j]) * exp(-exp(b.winParams[p + k + j]) * d * d)
                }
                b.phi[i * b.phiStride + u] = phi
                if (u < text.size) b.w[i * letters + text[u]] += phi
            }
        }
    }

    private fun output(b: Batch) {
        val all = nLayers * cells
        for (i in 0 until b.n) {
            for (l in 0 until nLayers) b.hs[l].copyInto(b.concat, i * all + l * cells, i * cells, (i + 1) * cells)
            outB.copyInto(b.y, i * outputs)
        }
        KernelMath.matVecAdd(outW, all, outputs, b.concat, b.y, b.n)
    }

    // --- Sampling ------------------------------------------------------------------------------------

    /**
     * Offsets per word (denormalised later), or null where the runaway guard stopped it.
     *
     * ⭐ **A word ends after its marks, not at Graves's stop.** Graves stops a word at the first pen
     * lift after the window has passed its last letter. But i-dots and t-bars are written *after* the
     * word — the synthetic writers do so, as hands do — so that lift is the one before them, and
     * `cation` came out as `calion`. So the stop opens a short grace ([WordEnd]): strokes that start
     * over the word's own ink and are short (marks) are kept; the first that heads off to the right,
     * runs long, or is still unfinished when the grace runs out is dropped, and the word ends there.
     */
    private fun generate(
        b: Batch,
        lengths: List<Int>,
        bias: Float,
        random: Random,
        from: NetState?,
    ): List<FloatArray?> {
        val start = from?.lastInput ?: floatArrayOf(0f, 0f, 1f)
        for (i in 0 until b.n) start.copyInto(b.x, i * PEN)
        val words = Array(b.n) { WordEnd() }
        val limit = lengths.maxOf { it } * SynthesisNetwork.MAX_STEPS_PER_CHAR + graceSteps
        var steps = 0
        while (steps < limit && !words.all { it.done }) {
            step(b)
            steps++
            for (i in 0 until b.n) {
                draw(b, i, bias, random)
                advance(b, i, words[i], steps >= lengths[i] * SynthesisNetwork.MAX_STEPS_PER_CHAR)
            }
        }
        return words.map { it.result() }
    }

    /** Word [i] takes its new point; Graves's stop opens its grace, or the guard ends it. */
    private fun advance(b: Batch, i: Int, w: WordEnd, guardReached: Boolean) {
        if (w.done) return
        w.take(b.x[i * PEN], b.x[i * PEN + 1], b.x[i * PEN + 2] > HALF)
        if (w.inGrace) return
        if (w.lifted && passedEnd(b, i, b.texts[i].size)) {
            w.startGrace(graceSteps)
        } else if (guardReached) {
            w.done = true
        }
    }

    private val graceSteps = (pointsPerChar * GRACE_CHARS).toInt()

    /**
     * One word's points as they are drawn, and the grace after Graves's stop in which its marks are
     * written. Tracks the pen in the model's frame (x-heights) to tell a mark over the word from the
     * start of something else.
     */
    private inner class WordEnd {
        private val recorded = ArrayList<Float>()
        private var committed = 0
        private var px = 0f
        private var py = 0f
        private var minX = Float.POSITIVE_INFINITY
        private var maxX = Float.NEGATIVE_INFINITY
        private var strokeStartX = 0f
        private var strokeLength = 0f
        private var newStroke = true
        private var graceLeft = 0
        var lifted = false
            private set
        var inGrace = false
            private set
        var done = false
        private var finished = false

        fun take(dx: Float, dy: Float, lift: Boolean) {
            val sx = dx * std[0] + mean[0]
            val sy = dy * std[1] + mean[1]
            px += sx
            py += sy
            recorded += dx
            recorded += dy
            recorded += if (lift) 1f else 0f
            if (newStroke) {
                strokeStartX = px
                strokeLength = 0f
            } else {
                strokeLength += sqrt(sx * sx + sy * sy)
            }
            if (!inGrace) {
                minX = minOf(minX, px)
                maxX = maxOf(maxX, px)
                if (lift) committed = recorded.size
            } else {
                graceStep(lift)
            }
            lifted = lift
            newStroke = lift
        }

        fun startGrace(steps: Int) {
            inGrace = true
            finished = true
            committed = recorded.size
            graceLeft = steps
        }

        /** A mark starts over the word and stays short; anything else ends the word before it. */
        private fun graceStep(lift: Boolean) {
            graceLeft--
            val offWord = strokeStartX > maxX + MARK_REACH || strokeStartX < minX - MARK_REACH
            when {
                offWord || strokeLength > MARK_LENGTH -> done = true
                lift -> committed = recorded.size
                graceLeft <= 0 -> done = true
            }
            if (lift && graceLeft <= 0) done = true
        }

        fun result(): FloatArray? = if (finished) FloatArray(committed) { recorded[it] } else null
    }

    /** Graves: the word is done when φ at one past the last character outweighs every character. */
    private fun passedEnd(b: Batch, i: Int, u: Int): Boolean {
        val base = i * b.phiStride
        val end = b.phi[base + u]
        for (k in 0 until u) if (b.phi[base + k] >= end) return false
        return true
    }

    /** Graves eq. 61: word [i]'s mixture weights with bias, into [out] — `softmax(π̂ (1 + b))`. */
    internal fun mixtureWeights(b: Batch, i: Int, bias: Float, out: FloatArray) {
        val o = i * outputs + 1
        var max = Float.NEGATIVE_INFINITY
        for (j in 0 until mixtures) max = maxOf(max, b.y[o + j] * (1f + bias))
        var total = 0f
        for (j in 0 until mixtures) {
            out[j] = exp(b.y[o + j] * (1f + bias) - max)
            total += out[j]
        }
        for (j in 0 until mixtures) out[j] /= total
    }

    /** Graves eq. 62: component [c]'s spread with bias, `exp(σ̂ − b)`; [axis] 0 is x, 1 is y. */
    internal fun sigma(b: Batch, i: Int, c: Int, axis: Int, bias: Float): Float =
        exp(b.y[i * outputs + 1 + (SIGMA_X + axis) * mixtures + c] - bias)

    /** Graves eqs. 18–23 with the bias of eqs. 61–62; the sample replaces word [i]'s input. */
    private fun draw(b: Batch, i: Int, bias: Float, random: Random) {
        val o = i * outputs
        val m = mixtures
        mixtureWeights(b, i, bias, b.pi)
        var pick = random.nextFloat()
        var c = 0
        while (c < m - 1) {
            pick -= b.pi[c]
            if (pick <= 0f) break
            c++
        }
        val sx = sigma(b, i, c, 0, bias)
        val sy = sigma(b, i, c, 1, bias)
        val rho = tanh(b.y[o + 1 + RHO * m + c])
        val n1 = gaussian(random)
        val n2 = gaussian(random)
        b.x[i * PEN] = b.y[o + 1 + MU_X * m + c] + sx * n1
        b.x[i * PEN + 1] = b.y[o + 1 + MU_Y * m + c] + sy * (rho * n1 + sqrt((1f - rho * rho).coerceAtLeast(0f)) * n2)
        b.x[i * PEN + 2] = if (random.nextFloat() < sigmoid(b.y[o])) 1f else 0f
    }

    /** Recorded normalised offsets → points in the model's frame, from where the prime left the pen. */
    private fun word(text: String, offsets: FloatArray, from: NetState?): NetworkWord {
        val n = offsets.size / PEN
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val ends = ArrayList<Int>()
        // On the prime's own line, so its baseline is the word's; from the frame's origin unprimed.
        var px = from?.lastX ?: 0f
        var py = from?.lastY ?: 0f
        for (k in 0 until n) {
            px += offsets[k * PEN] * std[0] + mean[0]
            py += offsets[k * PEN + 1] * std[1] + mean[1]
            xs[k] = px
            ys[k] = py
            if (offsets[k * PEN + 2] > HALF) ends += k + 1
        }
        if (ends.lastOrNull() != n) ends += n
        return NetworkWord(text, xs, ys, ends.toIntArray(), n)
    }

    internal companion object {
        /**
         * [catchUp]'s arithmetic, over the first sequence of a batch: the window's [k] components sit at
         * [kappa], weighted by `exp` of the first [k] entries of [winParams] (α̂). Moves every component
         * forward by the same amount until the weighted centre is at [length]; never back.
         */
        internal fun catchUp(kappa: FloatArray, winParams: FloatArray, k: Int, length: Int) {
            var weight = 0f
            var centre = 0f
            for (j in 0 until k) {
                val alpha = exp(winParams[j])
                weight += alpha
                centre += alpha * kappa[j]
            }
            if (weight <= 0f) return
            val lag = length - centre / weight
            if (lag > 0f) for (j in 0 until k) kappa[j] += lag
        }

        private const val PEN = 3
        private const val HALF = 0.5f

        /** After Graves's stop, how long marks may take: two characters' worth of steps. */
        const val GRACE_CHARS = 2f

        /** A mark starts within this many x-heights of the word's ink... */
        const val MARK_REACH = 0.3f

        /** ...and is at most this long: a t-bar or an x's second stroke, never a letter. */
        const val MARK_LENGTH = 1.2f

        /** The output's layout, `[e | π | μx | μy | σx | σy | ρ]`: each block's index, counted in M. */
        const val MU_X = 1
        const val MU_Y = 2
        const val SIGMA_X = 3
        const val RHO = 5

        /** PyTorch's gate order `(i, f, g, o)`, each a block of `cells`. */
        const val GATE_F = 1
        const val GATE_G = 2
        const val GATE_O = 3

        const val MINUS_TWO = -2f
        val TWO_PI = (2 * PI).toFloat()
        const val TINY = 1e-7f

        fun sigmoid(v: Float): Float = 1f / (1f + exp(-v))

        fun gaussian(random: Random): Float {
            val u = random.nextFloat().coerceAtLeast(TINY)
            return sqrt(MINUS_TWO * ln(u)) * cos(TWO_PI * random.nextFloat())
        }
    }
}
