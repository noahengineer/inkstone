package dev.inkstone.core.handwriting.neural

import dev.inkstone.core.model.PenTrajectory
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * One of the user's words as the network is trained on it (teacher forcing, Graves §4): [inputs] are
 * a start token and then each point before, [targets] each point — `(dx, dy, lift)`, normalised, three
 * floats per step.
 */
public class TrainingSequence internal constructor(
    internal val text: IntArray,
    internal val inputs: FloatArray,
    internal val targets: FloatArray,
) {
    public val steps: Int get() = targets.size / PEN

    internal companion object {
        const val PEN = 3
    }
}

/**
 * Where P2's and P3's gradients accumulate. [style] is ∂L/∂s; [adapters] is ∂L/∂(each adapter tensor),
 * shaped like the adapters themselves. Either may be absent, and then nothing is spent computing it.
 */
public class Gradients(public val style: FloatArray?, public val adapters: Adapters?) {
    public fun clear() {
        style?.fill(0f)
        adapters?.tensors?.forEach { it.fill(0f) }
    }

    internal val tensors: List<FloatArray> get() = listOfNotNull(style) + adapters?.tensors.orEmpty()
}

/**
 * ⭐ The synthesis network with a **hand-written backward pass** — docs/HANDWRITING-SYNTHESIS.md §8.1's
 * P2 (the style vector) and P3 (adapters), the base weights frozen.
 *
 * It is the same network as [KotlinSynthesisNetwork] — same weights file, same equations, and a test
 * holds its forward pass to that one's — written for training rather than speed: weights kept in
 * PyTorch's `(out, in)` layout so a backward product `Wᵀ·dz` runs along rows, and every intermediate of
 * a word kept for back-propagation through time. The gradient is checked against **PyTorch's autograd**
 * on the tiny model (`TrainableNetworkGradientTest`, `export.py --tiny`) and against finite differences.
 *
 * ## Back-propagation through time, the order at each step
 *
 * Going backwards from the last step, at step t:
 * 1. the loss's gradient at the output, `dy`, goes to every layer's h through the output matrix;
 * 2. layers L−1 … 1, top down: each LSTM's backward step gives `dz`, which sends gradient to its own h at
 *    t−1 (recurrent), to the layer below's h at t, to the window vector `w_t`, and to the style;
 * 3. the window: `w_t`'s gradient — from the layers above at t, and from layer 0 at t+1, which read
 *    `w_t` as its input — goes through φ to α, β and κ, and κ's to κ at t−1 (κ is a running sum);
 *    α, β and κ's increments are read off layer 0's h, which gets that gradient too;
 * 4. layer 0's backward step, whose input gradient goes to `w_{t−1}` (carried to step t−1) and to the
 *    style.
 *
 * With adapters and no style gradient wanted, only step 1's top-layer share and step 2's top layer run:
 * the layers below are frozen and nothing below the adapters needs a gradient.
 *
 * Not thread-safe: one instance holds one word's intermediates at a time.
 */
// The literals are the format, written as `model.py` writes them: PyTorch's gate order (i, f, g, o) in
// blocks of `cells`, and the mixture's `[e | π | μx | μy | σx | σy | ρ]` in blocks of M. And the loops are
// the equations: split further, a step of the backward pass would no longer read against its maths.
@Suppress("LargeClass", "TooManyFunctions", "MagicNumber", "CyclomaticComplexMethod", "NestedBlockDepth")
public class TrainableNetwork(weights: WeightsFile) {
    public val shape: NetworkShape = weights.header.shape
    private val nLayers = shape.layers
    private val h = shape.cells
    private val g = shape.gates
    private val k = shape.window
    private val m = shape.mixtures
    private val a = shape.alphabetSize
    private val s = shape.styleDim
    private val outs = shape.outputs
    private val kappaScale = shape.kappaScale
    private val mean = weights.header.normMean
    private val std = weights.header.normStd
    private val pointsPerChar = weights.header.pointsPerChar
    private val alphabet = weights.header.alphabet
    private val indexOf: Map<Char, Int> = alphabet.withIndex().associate { it.value to it.index }

    /** Inputs per layer: `[x, w, s]` for the first, `[x, h_below, w, s]` above it (Graves §5). */
    private val inSize = IntArray(nLayers) { if (it == 0) PEN + a + s else PEN + h + a + s }
    private val wStart = IntArray(nLayers) { if (it == 0) PEN else PEN + h }
    private val sStart = IntArray(nLayers) { wStart[it] + a }

    private val wIh = Array(nLayers) { weights.tensor("l$it.w_ih").second }
    private val wHh = Array(nLayers) { weights.tensor("l$it.w_hh").second }
    private val bias = Array(nLayers) { weights.tensor("l$it.b").second }
    private val winW = weights.tensor("window.w").second
    private val winB = weights.tensor("window.b").second
    private val outW = weights.tensor("out.w").second
    private val outB = weights.tensor("out.b").second

    /** The model's own style: the mean of its training writers' (§7.1) — what P2 starts from. */
    public val defaultStyle: FloatArray = weights.tensor("style.default").second.copyOf()

    public fun canWrite(text: String): Boolean = text.isNotEmpty() && text.all { it in indexOf }

    /** [ink] (the model's frame: x-heights, y up) as a training word for [text]; null if it cannot be. */
    public fun sequence(text: String, ink: PenTrajectory): TrainingSequence? {
        if (!canWrite(text)) return null
        val visible = text.count { it != ' ' }
        val inputs = ModelInk.inputs(ink, visible, pointsPerChar, mean, std) ?: return null
        val n = inputs.points
        if (n < MIN_STEPS) return null
        val targets = inputs.values.copyOf()
        val x = FloatArray(n * PEN)
        val step = FloatArray(PEN)
        for (t in 0 until n) {
            inputs.inputAt(t, step)
            step.copyInto(x, t * PEN)
        }
        return TrainingSequence(IntArray(text.length) { indexOf.getValue(text[it]) }, x, targets)
    }

    // --- The per-word record, grown on demand -----------------------------------------------------------

    private var capacity = 0
    private lateinit var u: Array<FloatArray> // per layer: T × inSize
    private lateinit var act: Array<FloatArray> // per layer: T × gates (i, f, g, o after their nonlinearity)
    private lateinit var cs: Array<FloatArray> // per layer: T × cells
    private lateinit var hs: Array<FloatArray> // per layer: T × cells
    private lateinit var winP: FloatArray // T × 3K, the window layer's raw output
    private lateinit var kap: FloatArray // T × K
    private lateinit var dy: FloatArray // T × outputs
    private lateinit var aIh: FloatArray // T × rank: ihA · u(top)
    private lateinit var aHh: FloatArray // T × rank: hhA · h(top, t−1)
    private lateinit var aOut: FloatArray // T × rank: outA · [h0 … h(L−1)]

    private fun ensure(steps: Int, rank: Int) {
        if (steps <= capacity && (rank == 0 || aIh.size >= steps * rank)) return
        val n = max(steps, capacity)
        capacity = n
        u = Array(nLayers) { FloatArray(n * inSize[it]) }
        act = Array(nLayers) { FloatArray(n * g) }
        cs = Array(nLayers) { FloatArray(n * h) }
        hs = Array(nLayers) { FloatArray(n * h) }
        winP = FloatArray(n * WINDOW_PARAMS * k)
        kap = FloatArray(n * k)
        dy = FloatArray(n * outs)
        aIh = FloatArray(n * max(rank, 1))
        aHh = FloatArray(n * max(rank, 1))
        aOut = FloatArray(n * max(rank, 1))
    }

    /**
     * Σ over [seq]'s steps of Graves's per-point negative log-likelihood (eq. 26), with [style] and
     * [adapters]; when [grads] is given, adds the gradient of that sum into it.
     */
    public fun loss(seq: TrainingSequence, style: FloatArray, adapters: Adapters?, grads: Gradients?): Float {
        require(style.size == s) { "style has ${style.size}, the model $s" }
        val steps = seq.steps
        val rank = adapters?.rank ?: 0
        ensure(steps, rank)
        var total = 0.0
        for (t in 0 until steps) total += forward(seq, t, style, adapters)
        if (grads != null) backward(seq, adapters, grads)
        return total.toFloat()
    }

    // --- Forward ------------------------------------------------------------------------------------------

    private val zBuf by lazy { FloatArray(g) }
    private val concat by lazy { FloatArray(nLayers * h) }
    private val yBuf by lazy { FloatArray(outs) }

    @Suppress("LongMethod")
    private fun forward(seq: TrainingSequence, t: Int, style: FloatArray, adapters: Adapters?): Double {
        val top = nLayers - 1
        // Layer 0 reads [x_t, w_{t−1}, s].
        for (l in 0 until nLayers) {
            val ui = u[l]
            val base = t * inSize[l]
            for (p in 0 until PEN) ui[base + p] = seq.inputs[t * PEN + p]
            if (l > 0) hs[l - 1].copyInto(ui, base + PEN, t * h, (t + 1) * h)
            // The window: the previous step's for layer 0, this step's for the layers above it.
            val wFrom = if (l == 0) t - 1 else t
            if (wFrom >=
                0
            ) {
                windowVector(seq, wFrom, ui, base + wStart[l])
            } else {
                ui.fill(
                    0f,
                    base + wStart[l],
                    base + wStart[l] + a,
                )
            }
            style.copyInto(ui, base + sStart[l])
            val zl = zBuf
            val w1 = wIh[l]
            val w2 = wHh[l]
            val n = inSize[l]
            for (gi in 0 until g) {
                var acc = bias[l][gi]
                val row = gi * n
                for (c in 0 until n) acc += w1[row + c] * ui[base + c]
                if (t > 0) {
                    val hr = gi * h
                    val hb = (t - 1) * h
                    for (c in 0 until h) acc += w2[hr + c] * hs[l][hb + c]
                }
                zl[gi] = acc
            }
            if (l == top && adapters != null) addAdapters(adapters, t, ui, base, zl)
            val ab = t * g
            val cb = t * h
            for (j in 0 until h) {
                val ig = sigmoid(zl[j])
                val fg = sigmoid(zl[h + j])
                val gg = tanh(zl[2 * h + j])
                val og = sigmoid(zl[3 * h + j])
                act[l][ab + j] = ig
                act[l][ab + h + j] = fg
                act[l][ab + 2 * h + j] = gg
                act[l][ab + 3 * h + j] = og
                val prev = if (t > 0) cs[l][cb - h + j] else 0f
                val c = fg * prev + ig * gg
                cs[l][cb + j] = c
                hs[l][cb + j] = og * tanh(c)
            }
            if (l == 0) window(t)
        }
        for (l in 0 until nLayers) hs[l].copyInto(concat, l * h, t * h, (t + 1) * h)
        val y = yBuf
        for (o in 0 until outs) {
            var acc = outB[o]
            val row = o * nLayers * h
            for (c in 0 until nLayers * h) acc += outW[row + c] * concat[c]
            y[o] = acc
        }
        if (adapters != null) {
            val r = adapters.rank
            val all = nLayers * h
            for (q in 0 until r) {
                var acc = 0f
                for (c in 0 until all) acc += adapters.outA[q * all + c] * concat[c]
                aOut[t * r + q] = acc
            }
            for (o in 0 until outs) {
                var acc = 0f
                for (q in 0 until r) acc += adapters.outB[o * r + q] * aOut[t * r + q]
                y[o] += acc
            }
        }
        return mixtureLoss(y, seq.targets, t * PEN, dy, t * outs)
    }

    /** `ihB·(ihA·u) + hhB·(hhA·h_{t−1})` into the top layer's pre-activation, keeping `A·u` and `A·h`. */
    private fun addAdapters(ad: Adapters, t: Int, ui: FloatArray, base: Int, zl: FloatArray) {
        val r = ad.rank
        val n = inSize[nLayers - 1]
        for (q in 0 until r) {
            var acc = 0f
            for (c in 0 until n) acc += ad.ihA[q * n + c] * ui[base + c]
            aIh[t * r + q] = acc
            var accH = 0f
            if (t > 0) {
                val hb = (t - 1) * h
                for (c in 0 until h) accH += ad.hhA[q * h + c] * hs[nLayers - 1][hb + c]
            }
            aHh[t * r + q] = accH
        }
        for (gi in 0 until g) {
            var acc = 0f
            for (q in 0 until r) acc += ad.ihB[gi * r + q] * aIh[t * r + q] + ad.hhB[gi * r + q] * aHh[t * r + q]
            zl[gi] += acc
        }
    }

    /** The window at step t, read off layer 0's h: α, β and κ, kept for the backward pass. */
    private fun window(t: Int) {
        val p = winP
        val pb = t * WINDOW_PARAMS * k
        val hb = t * h
        for (q in 0 until WINDOW_PARAMS * k) {
            var acc = winB[q]
            val row = q * h
            for (c in 0 until h) acc += winW[row + c] * hs[0][hb + c]
            p[pb + q] = acc
        }
        for (j in 0 until k) {
            val prev = if (t > 0) kap[(t - 1) * k + j] else 0f
            kap[t * k + j] = prev + exp(p[pb + 2 * k + j]) / kappaScale
        }
    }

    /** `w_t = Σ_{u<U} φ_t(u)·onehot(text[u])`, written into [into] from [at]. */
    private fun windowVector(seq: TrainingSequence, t: Int, into: FloatArray, at: Int) {
        into.fill(0f, at, at + a)
        val pb = t * WINDOW_PARAMS * k
        for ((uIdx, letter) in seq.text.withIndex()) {
            var phi = 0f
            for (j in 0 until k) {
                val d = kap[t * k + j] - uIdx
                phi += exp(winP[pb + j]) * exp(-exp(winP[pb + k + j]) * d * d)
            }
            into[at + letter] += phi
        }
    }

    /**
     * Graves eq. 26 for one step, from the raw outputs at [y]; its gradient with respect to those outputs
     * goes into [dOut] at [dAt]. The mixture is `[e | π | μx | μy | σx | σy | ρ]`, as in `model.nll`.
     */
    @Suppress("LongMethod")
    private fun mixtureLoss(y: FloatArray, target: FloatArray, tAt: Int, dOut: FloatArray, dAt: Int): Double {
        val dx = target[tAt]
        val dyv = target[tAt + 1]
        val lift = target[tAt + 2]
        var maxPi = Float.NEGATIVE_INFINITY
        for (j in 0 until m) maxPi = max(maxPi, y[1 + j])
        var sumPi = 0.0
        for (j in 0 until m) sumPi += exp((y[1 + j] - maxPi).toDouble())
        val logZPi = maxPi + ln(sumPi)
        val logTerm = DoubleArray(m)
        val zxs = DoubleArray(m)
        val zys = DoubleArray(m)
        val rhos = DoubleArray(m)
        val oms = DoubleArray(m)
        var maxTerm = Double.NEGATIVE_INFINITY
        for (j in 0 until m) {
            val mx = y[1 + m + j].toDouble()
            val my = y[1 + 2 * m + j].toDouble()
            val sx = exp(y[1 + 3 * m + j].toDouble())
            val sy = exp(y[1 + 4 * m + j].toDouble())
            val rho = tanh(y[1 + 5 * m + j].toDouble()) * RHO_SCALE
            val zx = (dx - mx) / sx
            val zy = (dyv - my) / sy
            val om = 1.0 - rho * rho
            val zz = zx * zx + zy * zy - 2.0 * rho * zx * zy
            val logN = -zz / (2.0 * om) - ln(TWO_PI * sx * sy * sqrt(om))
            val lp = y[1 + j] - logZPi
            logTerm[j] = lp + logN
            maxTerm = max(maxTerm, logTerm[j])
            zxs[j] = zx
            zys[j] = zy
            rhos[j] = rho
            oms[j] = om
        }
        var sumTerm = 0.0
        for (j in 0 until m) sumTerm += exp(logTerm[j] - maxTerm)
        val logMix = maxTerm + ln(sumTerm)
        val e = y[0].toDouble()
        val pen = max(e, 0.0) - e * lift + ln(1.0 + exp(-abs(e)))
        // Gradients: γ is each component's responsibility for the point.
        dOut[dAt] = (sigmoid(y[0]) - lift)
        for (j in 0 until m) {
            val gamma = exp(logTerm[j] - logMix)
            val piJ = exp(y[1 + j] - logZPi)
            dOut[dAt + 1 + j] = (piJ - gamma).toFloat()
            val zx = zxs[j]
            val zy = zys[j]
            val rho = rhos[j]
            val om = oms[j]
            val sx = exp(y[1 + 3 * m + j].toDouble())
            val sy = exp(y[1 + 4 * m + j].toDouble())
            val zz = zx * zx + zy * zy - 2.0 * rho * zx * zy
            val dMx = (zx - rho * zy) / (om * sx)
            val dMy = (zy - rho * zx) / (om * sy)
            val dSx = (zx * zx - rho * zx * zy) / om - 1.0
            val dSy = (zy * zy - rho * zx * zy) / om - 1.0
            val dRho = zx * zy / om - rho * zz / (om * om) + rho / om
            val th = tanh(y[1 + 5 * m + j].toDouble())
            val dRhoHat = dRho * RHO_SCALE * (1.0 - th * th)
            dOut[dAt + 1 + m + j] = (-gamma * dMx).toFloat()
            dOut[dAt + 1 + 2 * m + j] = (-gamma * dMy).toFloat()
            dOut[dAt + 1 + 3 * m + j] = (-gamma * dSx).toFloat()
            dOut[dAt + 1 + 4 * m + j] = (-gamma * dSy).toFloat()
            dOut[dAt + 1 + 5 * m + j] = (-gamma * dRhoHat).toFloat()
        }
        return -logMix + pen
    }

    // --- Backward -----------------------------------------------------------------------------------------

    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    private fun backward(seq: TrainingSequence, adapters: Adapters?, grads: Gradients) {
        val steps = seq.steps
        val top = nLayers - 1
        val styleGrad = grads.style
        val lowest = if (styleGrad != null) 0 else top
        val dh = Array(nLayers) { FloatArray(h) }
        val dhNext = Array(nLayers) { FloatArray(h) }
        val dcNext = Array(nLayers) { FloatArray(h) }
        val dz = FloatArray(g)
        val dw = FloatArray(a)
        val dwCarry = FloatArray(a)
        val dkCarry = FloatArray(k)
        val dp = FloatArray(WINDOW_PARAMS * k)
        val dConcat = FloatArray(nLayers * h)
        val all = nLayers * h
        val r = adapters?.rank ?: 0
        val vOut = FloatArray(max(r, 1))
        val vIh = FloatArray(max(r, 1))
        val vHh = FloatArray(max(r, 1))
        for (t in steps - 1 downTo 0) {
            for (l in lowest..top) dhNext[l].copyInto(dh[l])
            // 1. The output.
            dConcat.fill(0f)
            val db = t * outs
            for (o in 0 until outs) {
                val d = dy[db + o]
                if (d == 0f) continue
                val row = o * all
                for (c in lowest * h until all) dConcat[c] += outW[row + c] * d
            }
            if (adapters != null) {
                val ga = grads.adapters
                for (q in 0 until r) {
                    var acc = 0f
                    for (o in 0 until outs) acc += adapters.outB[o * r + q] * dy[db + o]
                    vOut[q] = acc
                }
                for (c in lowest * h until all) {
                    var acc = 0f
                    for (q in 0 until r) acc += adapters.outA[q * all + c] * vOut[q]
                    dConcat[c] += acc
                }
                if (ga != null) {
                    for (o in 0 until outs) {
                        val d = dy[db + o]
                        for (q in 0 until r) ga.outB[o * r + q] += d * aOut[t * r + q]
                    }
                    for (q in 0 until r) {
                        val vq = vOut[q]
                        if (vq == 0f) continue
                        for (l in 0 until nLayers) {
                            val hb = t * h
                            for (c in 0 until h) ga.outA[q * all + l * h + c] += vq * hs[l][hb + c]
                        }
                    }
                }
            }
            for (l in lowest..top) for (j in 0 until h) dh[l][j] += dConcat[l * h + j]
            // 2. The layers above the first, top down.
            dw.fill(0f)
            for (l in top downTo maxOf(1, lowest)) {
                val ad = if (l == top) adapters else null
                lstmBackward(l, t, dh[l], dcNext[l], dz)
                recurrentBackward(l, t, dz, dhNext[l], ad, vHh, grads.adapters)
                val n = inSize[l]
                val w1 = wIh[l]
                val ub = t * n
                if (ad != null) {
                    for (q in 0 until r) {
                        var acc = 0f
                        for (gi in 0 until g) acc += ad.ihB[gi * r + q] * dz[gi]
                        vIh[q] = acc
                    }
                    grads.adapters?.let { ga ->
                        for (gi in 0 until g) for (q in 0 until r) ga.ihB[gi * r + q] += dz[gi] * aIh[t * r + q]
                        for (q in 0 until r) for (c in 0 until n) ga.ihA[q * n + c] += vIh[q] * u[l][ub + c]
                    }
                }
                if (styleGrad == null) continue
                for (gi in 0 until g) {
                    val d = dz[gi]
                    if (d == 0f) continue
                    val row = gi * n
                    for (c in 0 until h) dh[l - 1][c] += w1[row + PEN + c] * d
                    for (c in 0 until a) dw[c] += w1[row + wStart[l] + c] * d
                    for (c in 0 until s) styleGrad[c] += w1[row + sStart[l] + c] * d
                }
                if (ad != null) {
                    for (q in 0 until r) {
                        val vq = vIh[q]
                        val ar = q * n
                        for (c in 0 until h) dh[l - 1][c] += ad.ihA[ar + PEN + c] * vq
                        for (c in 0 until a) dw[c] += ad.ihA[ar + wStart[l] + c] * vq
                        for (c in 0 until s) styleGrad[c] += ad.ihA[ar + sStart[l] + c] * vq
                    }
                }
            }
            if (styleGrad == null) continue
            // 3. The window: w_t's gradient, from the layers above (now) and layer 0 at t+1 (carried).
            for (c in 0 until a) dw[c] += dwCarry[c]
            windowBackward(seq, t, dw, dkCarry, dp)
            val pb = WINDOW_PARAMS * k
            for (q in 0 until pb) {
                val d = dp[q]
                if (d == 0f) continue
                val row = q * h
                for (c in 0 until h) dh[0][c] += winW[row + c] * d
            }
            // 4. Layer 0, whose input held w_{t−1} and the style.
            lstmBackward(0, t, dh[0], dcNext[0], dz)
            recurrentBackward(0, t, dz, dhNext[0], null, vHh, null)
            dwCarry.fill(0f)
            val n0 = inSize[0]
            val w0 = wIh[0]
            for (gi in 0 until g) {
                val d = dz[gi]
                if (d == 0f) continue
                val row = gi * n0
                for (c in 0 until a) dwCarry[c] += w0[row + wStart[0] + c] * d
                for (c in 0 until s) styleGrad[c] += w0[row + sStart[0] + c] * d
            }
        }
    }

    /**
     * The LSTM's backward step at (l, t): from [dhT] and the cell gradient carried from t+1 ([dc], which
     * is replaced by the one to carry to t−1), the gate pre-activations' gradient into [dz].
     */
    private fun lstmBackward(l: Int, t: Int, dhT: FloatArray, dc: FloatArray, dz: FloatArray) {
        val ab = t * g
        val cb = t * h
        for (j in 0 until h) {
            val ig = act[l][ab + j]
            val fg = act[l][ab + h + j]
            val gg = act[l][ab + 2 * h + j]
            val og = act[l][ab + 3 * h + j]
            val c = cs[l][cb + j]
            val prev = if (t > 0) cs[l][cb - h + j] else 0f
            val tc = tanh(c)
            val dOut = dhT[j] * tc
            val dCell = dc[j] + dhT[j] * og * (1f - tc * tc)
            dz[j] = dCell * gg * ig * (1f - ig)
            dz[h + j] = dCell * prev * fg * (1f - fg)
            dz[2 * h + j] = dCell * ig * (1f - gg * gg)
            dz[3 * h + j] = dOut * og * (1f - og)
            dc[j] = dCell * fg
        }
    }

    /** `W_hhᵀ·dz` (and its adapter, on the top layer) into [dhPrev]: what h at t−1 is owed. */
    @Suppress("LongParameterList")
    private fun recurrentBackward(
        l: Int,
        t: Int,
        dz: FloatArray,
        dhPrev: FloatArray,
        adapters: Adapters?,
        v: FloatArray,
        ga: Adapters?,
    ) {
        dhPrev.fill(0f)
        if (t == 0) return
        val w2 = wHh[l]
        for (gi in 0 until g) {
            val d = dz[gi]
            if (d == 0f) continue
            val row = gi * h
            for (c in 0 until h) dhPrev[c] += w2[row + c] * d
        }
        if (adapters == null) return
        val r = adapters.rank
        for (q in 0 until r) {
            var acc = 0f
            for (gi in 0 until g) acc += adapters.hhB[gi * r + q] * dz[gi]
            v[q] = acc
        }
        for (q in 0 until r) for (c in 0 until h) dhPrev[c] += adapters.hhA[q * h + c] * v[q]
        if (ga != null) {
            val hb = (t - 1) * h
            for (gi in 0 until g) for (q in 0 until r) ga.hhB[gi * r + q] += dz[gi] * aHh[t * r + q]
            for (q in 0 until r) for (c in 0 until h) ga.hhA[q * h + c] += v[q] * hs[l][hb + c]
        }
    }

    /**
     * From `w_t`'s gradient [dw] to the window layer's raw outputs' ([dp]), with κ's gradient carried
     * from t+1 in [dkCarry] — replaced by what κ at t−1 is owed, which is all of it: κ is a running sum.
     */
    private fun windowBackward(seq: TrainingSequence, t: Int, dw: FloatArray, dkCarry: FloatArray, dp: FloatArray) {
        val pb = t * WINDOW_PARAMS * k
        for (j in 0 until k) {
            val alpha = exp(winP[pb + j])
            val beta = exp(winP[pb + k + j])
            val kappa = kap[t * k + j]
            var dAlpha = 0f
            var dBeta = 0f
            var dKappa = 0f
            for ((uIdx, letter) in seq.text.withIndex()) {
                val dPhi = dw[letter]
                if (dPhi == 0f) continue
                val d = kappa - uIdx
                val e = exp(-beta * d * d)
                dAlpha += dPhi * e
                dBeta += dPhi * alpha * e * (-d * d)
                dKappa += dPhi * alpha * e * (-2f * beta * d)
            }
            val dKappaTotal = dKappa + dkCarry[j]
            dp[j] = dAlpha * alpha
            dp[k + j] = dBeta * beta
            dp[2 * k + j] = dKappaTotal * exp(winP[pb + 2 * k + j]) / kappaScale
            dkCarry[j] = dKappaTotal
        }
    }

    internal companion object {
        const val PEN = 3
        const val WINDOW_PARAMS = 3
        const val MIN_STEPS = 4
        const val RHO_SCALE = 0.9999
        const val TWO_PI = 2.0 * kotlin.math.PI

        fun sigmoid(v: Float): Float = 1f / (1f + exp(-v))
    }
}
