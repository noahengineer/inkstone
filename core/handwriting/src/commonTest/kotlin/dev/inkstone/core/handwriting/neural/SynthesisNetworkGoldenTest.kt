package dev.inkstone.core.handwriting.neural

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * docs/HANDWRITING-SYNTHESIS.md §7.3 / H4 — Kotlin's network agrees with PyTorch's to 1e-4.
 *
 * [TinyModelFixture] is a real `.ihwm`, written by `export.py` exactly as it writes the shipped model
 * (f16 weights, f32 biases, the header, the trailer), with the PyTorch reference's state recorded on a
 * fixed teacher-forced input. Every value the step produces is compared: each layer's h and c, the
 * window's position κ and vector w, φ over the text, the raw outputs, and the mixture weights and
 * spreads after Graves's bias — the last two through the same functions sampling uses.
 *
 * The shipped model's own golden vectors are checked in `:app` (`ShippedModelTest`), against the asset.
 */
class SynthesisNetworkGoldenTest {

    private val weights = assertIs<ModelLoad.Loaded>(
        WeightsFile.read(TinyModelFixture.bytes, LicencePolicy.RELEASE),
    ).weights
    private val net = KotlinSynthesisNetwork(weights)

    @Test
    fun theFileIsWhatExportWrote() {
        assertEquals(TinyModelFixture.SHA256, weights.sha256)
        assertEquals(TinyModelFixture.SHA256.take(8), net.modelId)
        assertEquals(" abcd", net.alphabet)
    }

    @Test
    fun everyRecordedStepMatchesPyTorch() {
        val text = IntArray(TinyModelFixture.TEXT.length) { net.alphabet.indexOf(TinyModelFixture.TEXT[it]) }
        val batch = net.Batch(listOf(text), null)
        val records = TinyModelFixture.records.associateBy { it.step }
        val pi = FloatArray(weights.header.shape.mixtures)
        var compared = 0
        for ((t, input) in TinyModelFixture.inputs.withIndex()) {
            for (k in 0 until 3) batch.x[k] = input[k].toFloat()
            net.step(batch)
            val r = records[t] ?: continue
            for (l in r.h.indices) {
                close("h[$l]@$t", r.h[l], batch.hs[l])
                close("c[$l]@$t", r.c[l], batch.cs[l])
            }
            close("kappa@$t", r.kappa, batch.kappa)
            close("w@$t", r.w, batch.w)
            close("phi@$t", r.phi, batch.phi.copyOf(r.phi.size))
            close("y@$t", r.y, batch.y)
            net.mixtureWeights(batch, 0, TinyModelFixture.BIAS, pi)
            close("pi@$t", r.piBiased, pi)
            val sigmas = FloatArray(pi.size) { net.sigma(batch, 0, it, 0, TinyModelFixture.BIAS) }
            close("sigmaX@$t", r.sigmaXBiased, sigmas)
            compared++
        }
        assertEquals(TinyModelFixture.records.size, compared, "every recorded step was reached")
    }

    private fun close(what: String, want: DoubleArray, got: FloatArray) {
        assertTrue(got.size >= want.size, "$what: ${got.size} values, want ${want.size}")
        for (i in want.indices) {
            val d = abs(want[i] - got[i])
            assertTrue(d <= TOLERANCE, "$what[$i]: Kotlin ${got[i]}, PyTorch ${want[i]}, |d| = $d")
        }
    }

    private companion object {
        const val TOLERANCE = 1e-4
    }
}
