package dev.inkstone.core.handwriting.neural

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * docs/HANDWRITING-SYNTHESIS.md §8.1 — **the hand-written backward pass, gradient-checked against
 * PyTorch.** P2 fits the style vector and P3 the adapters by back-propagation through time, written by
 * hand for this architecture ([TrainableNetwork]); this holds it to autograd on the tiny model, whose
 * goldens `export.py --tiny` computes through `model.sequence_loss` with the weights rounded to f16 as
 * the file holds them.
 *
 * Three independent witnesses, because a gradient that is wrong in a consistent way still trains — just
 * worse, and silently:
 * - **the loss** itself against PyTorch's (the forward pass and Graves's eq. 26);
 * - **every gradient** against autograd's, for P2 (style, no adapters) and P3 (adapters and the style);
 * - **finite differences** of [TrainableNetwork.loss], which needs neither PyTorch nor the backward code.
 *
 * And one more for the inference path: [KotlinSynthesisNetwork] with the same adapters **folded into
 * its weights** gives the same loss — so what P3 trains is what the app writes with.
 */
class TrainableNetworkGradientTest {

    private val weights = assertIs<ModelLoad.Loaded>(
        WeightsFile.read(TinyModelFixture.bytes, LicencePolicy.RELEASE),
    ).weights
    private val net = TrainableNetwork(weights)
    private val alphabet = weights.header.alphabet
    private val seq = TrainingSequence(
        IntArray(TinyModelFixture.TEXT.length) { alphabet.indexOf(TinyModelFixture.TEXT[it]) },
        flat(TinyModelFixture.inputs),
        flat(TinyModelFixture.gradTargets),
    )

    @Test
    fun theLossMatchesPyTorch() {
        val loss = net.loss(seq, net.defaultStyle, null, null)
        close("loss", TinyModelFixture.LOSS_STYLE, loss.toDouble())
        val adapted = net.loss(seq, net.defaultStyle, adapters(), null)
        close("adapted loss", TinyModelFixture.LOSS_ADAPTED, adapted.toDouble())
    }

    @Test
    fun theStyleGradientMatchesAutograd() {
        val grads = Gradients(FloatArray(net.defaultStyle.size), null)
        net.loss(seq, net.defaultStyle, null, grads)
        closeAll("dL/ds", TinyModelFixture.gradStyle, grads.style ?: error("asked for"))
    }

    @Test
    fun theAdapterGradientsMatchAutograd() {
        val ad = adapters()
        val grads = Gradients(FloatArray(net.defaultStyle.size), ad.zeros())
        net.loss(seq, net.defaultStyle, ad, grads)
        closeAll("dL/ds with adapters", TinyModelFixture.adaptedGradStyle, grads.style ?: error("asked for"))
        val names = listOf("ihA", "ihB", "hhA", "hhB", "outA", "outB")
        val got = grads.adapters?.tensors ?: error("asked for")
        for (i in names.indices) closeAll("dL/d${names[i]}", TinyModelFixture.adapterGrads[i], got[i])
    }

    @Test
    fun adapterGradientsAloneMatchTheFullPass() {
        // P3 skips everything below the top layer when no style gradient is wanted: the same numbers.
        val ad = adapters()
        val alone = Gradients(null, ad.zeros())
        net.loss(seq, net.defaultStyle, ad, alone)
        val got = alone.adapters?.tensors ?: error("asked for")
        for (i in got.indices) closeAll("adapter $i alone", TinyModelFixture.adapterGrads[i], got[i])
    }

    @Test
    fun theStyleGradientMatchesFiniteDifferences() {
        val style = net.defaultStyle.copyOf()
        val grads = Gradients(FloatArray(style.size), null)
        net.loss(seq, style, null, grads)
        val analytic = grads.style ?: error("asked for")
        for (j in style.indices) {
            val plus = style.copyOf().also { it[j] += EPS }
            val minus = style.copyOf().also { it[j] -= EPS }
            val numeric = (net.loss(seq, plus, null, null) - net.loss(seq, minus, null, null)) / (2 * EPS)
            val d = abs(numeric - analytic[j])
            assertTrue(
                d <= FD_TOLERANCE * max(1f, abs(numeric)),
                "dL/ds[$j]: analytic ${analytic[j]}, numeric $numeric",
            )
        }
    }

    @Test
    fun theInferenceNetworkWithFoldedAdaptersGivesTheSameLoss() {
        val ad = adapters()
        val inference = KotlinSynthesisNetwork(weights, null, ad)
        val traced = inference.trace(
            TinyModelFixture.TEXT,
            TinyModelFixture.inputs.map(::floats),
            TinyModelFixture.inputs.indices.toSet(),
        )
        val shape = weights.header.shape
        var total = 0.0
        for ((t, step) in traced.withIndex()) total += nll(step.y, TinyModelFixture.gradTargets[t], shape.mixtures)
        close("folded adapters' loss", TinyModelFixture.LOSS_ADAPTED, total)
    }

    private fun adapters(): Adapters {
        val t = TinyModelFixture.adapters.map(::floats)
        return Adapters(TinyModelFixture.ADAPTER_RANK, t[0], t[1], t[2], t[3], t[4], t[5])
    }

    /** Graves eq. 26 from raw outputs, written out plainly — a reference that shares no code with the network. */
    private fun nll(y: FloatArray, target: DoubleArray, m: Int): Double {
        val pis = DoubleArray(m) { y[1 + it].toDouble() }
        val maxPi = pis.max()
        val logZ = maxPi + ln(pis.sumOf { exp(it - maxPi) })
        var mix = 0.0
        for (j in 0 until m) {
            val sx = exp(y[1 + 3 * m + j].toDouble())
            val sy = exp(y[1 + 4 * m + j].toDouble())
            val rho = tanh(y[1 + 5 * m + j].toDouble()) * 0.9999
            val zx = (target[0] - y[1 + m + j]) / sx
            val zy = (target[1] - y[1 + 2 * m + j]) / sy
            val om = 1 - rho * rho
            val n = exp(-(zx * zx + zy * zy - 2 * rho * zx * zy) / (2 * om)) / (2 * kotlin.math.PI * sx * sy * sqrt(om))
            mix += exp(pis[j] - logZ) * n
        }
        val e = y[0].toDouble()
        val pen = max(e, 0.0) - e * target[2] + ln(1 + exp(-abs(e)))
        return -ln(mix) + pen
    }

    private fun close(what: String, want: Double, got: Double) {
        val d = abs(want - got)
        assertTrue(d <= TOLERANCE * max(1.0, abs(want)), "$what: Kotlin $got, PyTorch $want, |d| = $d")
    }

    private fun closeAll(what: String, want: DoubleArray, got: FloatArray) {
        assertTrue(got.size == want.size, "$what: ${got.size} values, want ${want.size}")
        val scale = max(1.0, want.maxOf { abs(it) })
        for (i in want.indices) {
            val d = abs(want[i] - got[i])
            assertTrue(d <= TOLERANCE * scale, "$what[$i]: Kotlin ${got[i]}, PyTorch ${want[i]}, |d| = $d")
        }
    }

    private companion object {
        /** Relative to the largest value compared: float32 against float32, through ~14 steps of BPTT. */
        const val TOLERANCE = 1e-4
        const val EPS = 1e-2f
        const val FD_TOLERANCE = 2e-2f

        fun flat(rows: List<DoubleArray>): FloatArray = FloatArray(rows.size * 3) { rows[it / 3][it % 3].toFloat() }

        fun floats(values: DoubleArray): FloatArray = FloatArray(values.size) { values[it].toFloat() }
    }
}
