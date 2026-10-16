package dev.inkstone.core.handwriting.neural

import dev.inkstone.core.handwriting.PenDynamics
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * docs/HANDWRITING-SYNTHESIS.md §8.1 — the fit: it learns, it checkpoints, and a fit that is stopped —
 * by its time running out or by a kill — ends **bit for bit** where an unbroken one does. The process
 * kill itself, in another process, is `CrashSafetyTest`'s; this proves the arithmetic it relies on.
 *
 * The network is [TinyModelFixture]'s, and the "user's words" are words that network wrote itself: the
 * point is the fit's bookkeeping, not what it learns.
 */
class FitterTest {

    private val weights = assertIs<ModelLoad.Loaded>(
        WeightsFile.read(TinyModelFixture.bytes, LicencePolicy.RELEASE),
    ).weights
    private val trainer = TrainableNetwork(weights)
    private val words: List<FitWord> = run {
        val net = KotlinSynthesisNetwork(weights)
        val texts = listOf("abc", "bad", "cab", "dab", "add", "dad", "cad", "bac")
        val written = net.write(texts, Primed.NONE, 0.5f, Random(9))
        texts.indices.mapNotNull { i ->
            val w = written[i] ?: return@mapNotNull null
            val ink = PenDynamics.Unmeasured.apply(w.x, w.y, w.strokeEnds) ?: return@mapNotNull null
            trainer.sequence(texts[i], ink)?.let { FitWord("w$i", it) }
        }
    }
    private val byId = words.associateBy { it.sampleId }

    private fun plan(rung: Rung) = if (rung == Rung.STYLE) {
        FitPlan(Rung.STYLE, epochs = 3, batch = 3, learningRate = 0.05f, prior = 0.01f, checkpointEvery = 2)
    } else {
        FitPlan(
            Rung.ADAPTERS,
            epochs = 3,
            batch = 3,
            learningRate = 0.01f,
            prior = 0.001f,
            rank = 2,
            checkpointEvery = 2,
        )
    }

    @Test
    fun thereAreWordsToFit() {
        assertTrue(words.size >= 6, "the tiny network wrote ${words.size} of 8 words")
    }

    @Test
    fun aStyleFitLowersTheLossOnItsWords() = runTest {
        val fitter = Fitter(trainer, weights.modelId)
        val start = fitter.start(plan(Rung.STYLE).copy(epochs = 6), words, words)
        val before = fitter.validationLoss(start.plan, start.params, null, words)
        val done = assertIs<Fitter.Progress.Done>(fitter.run(start, byId, null, { false }) { }).checkpoint
        assertTrue(done.bestValidation < before, "validation $before -> ${done.bestValidation}")
    }

    @Test
    fun anAdapterFitLowersTheLossOnItsWords() = runTest {
        val fitter = Fitter(trainer, weights.modelId)
        val start = fitter.start(plan(Rung.ADAPTERS).copy(epochs = 6), words, words)
        val before = fitter.validationLoss(start.plan, start.params, null, words)
        val done = assertIs<Fitter.Progress.Done>(fitter.run(start, byId, null, { false }) { }).checkpoint
        assertTrue(done.bestValidation < before, "validation $before -> ${done.bestValidation}")
    }

    @Test
    fun aFitStoppedForTimeEndsWhereAnUnbrokenOneDoes() = runTest {
        for (rung in Rung.entries) {
            val fitter = Fitter(trainer, weights.modelId)
            val unbroken = assertIs<Fitter.Progress.Done>(
                fitter.run(fitter.start(plan(rung), words, words.take(2)), byId, null, { false }) { },
            ).checkpoint

            var calls = 0
            val paused = assertIs<Fitter.Progress.Paused>(
                fitter.run(fitter.start(plan(rung), words, words.take(2)), byId, null, { ++calls > 3 }) { },
            ).checkpoint
            assertEquals(3, paused.iteration, "$rung: stopped where it said")
            // Across a process: the checkpoint as bytes, read back.
            val reread = assertNotNull(FitCheckpoint.decode(FitCheckpoint.encode(paused)))
            val resumed = assertIs<Fitter.Progress.Done>(fitter.run(reread, byId, null, { false }) { }).checkpoint
            same("$rung", unbroken, resumed)
        }
    }

    /**
     * The crash bound: killed between checkpoints, a fit resumes from the last one written — losing at
     * most [FitPlan.checkpointEvery] iterations — and still ends exactly where an unbroken one does.
     */
    @Test
    fun aFitKilledBetweenCheckpointsLosesAtMostOneIntervalAndEndsTheSame() = runTest {
        val fitter = Fitter(trainer, weights.modelId)
        val p = plan(Rung.STYLE)
        val unbroken = assertIs<Fitter.Progress.Done>(
            fitter.run(fitter.start(p, words, words.take(2)), byId, null, {
                false
            }) { },
        ).checkpoint
        val saved = ArrayList<ByteArray>()
        val killAt = 7
        var calls = 0
        fitter.run(fitter.start(p, words, words.take(2)), byId, null, { ++calls > killAt }) {
            saved +=
                FitCheckpoint.encode(it)
        }
        // The kill: everything after the last checkpoint *before* iteration 7 is lost — including the
        // checkpoint a stopped run writes on its way out, which a killed one never gets to.
        val onDisk = saved.map { assertNotNull(FitCheckpoint.decode(it)) }.last { it.iteration < killAt }
        assertTrue(killAt - onDisk.iteration <= p.checkpointEvery, "lost ${killAt - onDisk.iteration} iterations")
        val resumed = assertIs<Fitter.Progress.Done>(fitter.run(onDisk, byId, null, { false }) { }).checkpoint
        same("killed", unbroken, resumed)
    }

    @Test
    fun freshAdaptersAreTheBaseModelExactly() {
        val text = TinyModelFixture.TEXT
        val inputs = TinyModelFixture.inputs.map { row -> FloatArray(row.size) { row[it].toFloat() } }
        val steps = inputs.indices.toSet()
        val base = KotlinSynthesisNetwork(weights).trace(text, inputs, steps)
        val fresh = Adapters.fresh(weights.header.shape, 3, Random(1))
        val adapted = KotlinSynthesisNetwork(weights, null, fresh).trace(text, inputs, steps)
        for (t in base.indices) assertContentEquals(base[t].y, adapted[t].y, "step $t")
    }

    /** §8.1's shrinkage: a blend of zero is the base model exactly, and one is the fit itself. */
    @Test
    fun scaledAdaptersBlendFromTheBaseModelToTheFit() {
        val text = TinyModelFixture.TEXT
        val inputs = TinyModelFixture.inputs.map { row -> FloatArray(row.size) { row[it].toFloat() } }
        val steps = inputs.indices.toSet()
        val fit = Adapters.fresh(weights.header.shape, 2, Random(5))
        fit.ihB.fill(0.1f)
        fun outputs(a: Adapters?) = KotlinSynthesisNetwork(weights, null, a).trace(text, inputs, steps).map { it.y }
        val base = outputs(null)
        val none = outputs(Adapters.scaled(fit, 0f))
        val full = outputs(Adapters.scaled(fit, 1f))
        val fitted = outputs(fit)
        for (t in base.indices) {
            assertContentEquals(base[t], none[t], "α = 0 is the base model at step $t")
            assertContentEquals(fitted[t], full[t], "α = 1 is the fit at step $t")
        }
        assertTrue(base.indices.any { !base[it].contentEquals(fitted[it]) }, "and the fit is not the base model")
    }

    @Test
    fun adaptersSurviveTheirBytes() {
        val a = Adapters.fresh(weights.header.shape, 2, Random(3))
        a.ihB[5] = 0.25f
        val b = assertNotNull(Adapters.decode(Adapters.encode(a)))
        assertEquals(a.rank, b.rank)
        for (i in a.tensors.indices) assertContentEquals(a.tensors[i], b.tensors[i])
        assertEquals(null, Adapters.decode(ByteArray(3)))
    }

    private fun same(what: String, a: FitCheckpoint, b: FitCheckpoint) {
        assertEquals(a.iteration, b.iteration, what)
        assertEquals(a.adamStep, b.adamStep, what)
        assertEquals(a.history, b.history, "$what: the validation curve")
        assertEquals(a.bestValidation, b.bestValidation, what)
        for (i in a.best.indices) assertContentEquals(a.best[i], b.best[i], "$what: best[$i], bit for bit")
        for (i in a.params.indices) assertContentEquals(a.params[i], b.params[i], "$what: params[$i]")
    }
}
