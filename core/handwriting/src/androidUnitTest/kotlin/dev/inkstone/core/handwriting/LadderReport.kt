package dev.inkstone.core.handwriting

import dev.inkstone.core.handwriting.neural.Adapters
import dev.inkstone.core.handwriting.neural.FitPlan
import dev.inkstone.core.handwriting.neural.FitWord
import dev.inkstone.core.handwriting.neural.Fitter
import dev.inkstone.core.handwriting.neural.KotlinSynthesisNetwork
import dev.inkstone.core.handwriting.neural.Primed
import dev.inkstone.core.handwriting.neural.TrainableNetwork
import dev.inkstone.core.handwriting.quality.FidelityProbe
import dev.inkstone.core.handwriting.quality.FidelityResult
import dev.inkstone.core.handwriting.quality.FidelitySet
import dev.inkstone.core.handwriting.quality.WordWriter
import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * docs/HANDWRITING-SYNTHESIS.md H5 — **a report**: §8.1's ladder on a pulled profile. P2 and P3 are
 * fitted here, on this machine, exactly as the personalisation worker fits them on the tablet, and
 * each configuration's F is measured on every word no fit saw: the held-out words and the fit's own
 * validation words.
 *
 * ```
 * INKSTONE_HW_PROFILE=… ./gradlew :core:handwriting:testDebugUnitTest --tests '*LadderReport' --rerun
 * INKSTONE_HW_EPOCHS=n   (default: the plan's)
 * ```
 */
class LadderReport {

    @Test
    @Suppress("CyclomaticComplexMethod", "LongMethod") // A report: one long, linear measurement.
    fun `the ladder on the users profile`() = runTest(timeout = 600.minutes) {
        val loaded = ProfileReports.load() ?: return@runTest
        val trainer = TrainableNetwork(loaded.weights)
        // The fit's validation words, set aside by fingerprint from the training split.
        val valIds = loaded.samples.filter {
            it.split == SampleSplit.TRAIN && Math.floorMod(it.sourceKey.hashCode(), Fitter.VALIDATION_EVERY) == 0
        }.mapTo(HashSet()) { it.id }
        val samples = loaded.samples.map { if (it.id in valIds) it.copy(split = SampleSplit.HELD_OUT) else it }
        val index = GlyphIndex.build(samples, loaded.labels)
        val fit = index.wholeWords().filter { trainer.canWrite(it.text) }
            .mapNotNull { u -> trainer.sequence(u.text, u.trajectory)?.let { FitWord(u.source.sampleId, it) } }
        val validation = samples.filter { it.id in valIds }.mapNotNull { s ->
            val label = loaded.labels.labelOf(s)
            val ink = GlyphIndex.normalisedWord(s, loaded.labels) ?: return@mapNotNull null
            trainer.sequence(label, ink)?.let { FitWord(s.id, it) }
        }
        val words = (fit + validation).associateBy { it.sampleId }
        println("fit on ${fit.size} words (${fit.sumOf { it.sequence.steps }} steps), validation ${validation.size}")

        val set = FidelitySet.of(samples, loaded.labels, index)
        println("evaluated on ${set.words.size} words no fit saw")
        val dynamics = PenDynamics.fit(index.wholeWords().map { it.trajectory }, index.stats.penUpMs)
        val base = NeuralBackend(KotlinSynthesisNetwork(loaded.weights), dynamics)
        var seed = 1L
        val neat = Variation.DEFAULT_NEATNESS
        fun writer(b: NeuralBackend, primed: Primed) =
            WordWriter { labels -> b.write(labels, primed, neat, 1, Random(seed++)).map { it?.trajectory } }
        val generic = FidelityProbe.generic(set, writer(base, Primed.NONE))
        val prime = base.prime(index, SourcePreference(), Random(3))
        report("assembly", FidelityProbe.measure(set, generic, assemblyWriter(index)))
        report("base, unprimed (redraw)", FidelityProbe.measure(set, generic, writer(base, Primed.NONE)))
        report("base, P1 primed", FidelityProbe.measure(set, generic, writer(base, prime)))

        val epochs = System.getenv("INKSTONE_HW_EPOCHS")?.toIntOrNull()
        val fitter = Fitter(trainer, loaded.weights.modelId)
        val p2Plan = epochs?.let { FitPlan.P2.copy(epochs = it) } ?: FitPlan.P2
        val baseLoss = fitter.validationLoss(p2Plan, listOf(trainer.defaultStyle), null, validation)
        println("base: validation NLL/point ${fmt(baseLoss)}")
        var started = System.nanoTime()
        val p2 = done(fitter.run(fitter.start(p2Plan, fit, validation), words, null, { false }) { })
        println("P2 fit: ${seconds(started)} s; validation NLL/point by epoch ${p2.history.map(::fmt)}")
        val moved = fmt(distance(p2.best[0], trainer.defaultStyle))
        println("P2 style moved $moved from the default (|s0|=${fmt(norm(trainer.defaultStyle))})")
        val style = p2.best[0]
        val p2Backend = NeuralBackend(KotlinSynthesisNetwork(loaded.weights, style), dynamics)
        val p2Prime = p2Backend.prime(index, SourcePreference(), Random(3))
        report("P2, unprimed", FidelityProbe.measure(set, generic, writer(p2Backend, Primed.NONE)))
        report("P2, primed", FidelityProbe.measure(set, generic, writer(p2Backend, p2Prime)))

        // Variants, by environment: INKSTONE_HW_P3_RANK, INKSTONE_HW_P3_ON_DEFAULT=1 (P3 without P2).
        val rank = System.getenv("INKSTONE_HW_P3_RANK")?.toIntOrNull() ?: FitPlan.P3.rank
        val p3Style = if (System.getenv("INKSTONE_HW_P3_ON_DEFAULT") == "1") null else style
        val p3Plan = (epochs?.let { FitPlan.P3.copy(epochs = it) } ?: FitPlan.P3).copy(rank = rank)
        started = System.nanoTime()
        val p3 = done(fitter.run(fitter.start(p3Plan, fit, validation), words, p3Style, { false }) { })
        val on = if (p3Style == null) "the model's own style" else "P2's style"
        println("P3 rank $rank fit (on $on): ${seconds(started)} s; validation NLL/point ${p3.history.map(::fmt)}")
        val t = p3.best
        val adapters = Adapters(p3Plan.rank, t[0], t[1], t[2], t[3], t[4], t[5])
        val p3Backend = NeuralBackend(KotlinSynthesisNetwork(loaded.weights, p3Style, adapters), dynamics)
        val p3Prime = p3Backend.prime(index, SourcePreference(), Random(3))
        report("P3 (rank $rank, on $on), unprimed", FidelityProbe.measure(set, generic, writer(p3Backend, Primed.NONE)))
        report("P3 (rank $rank, on $on), primed", FidelityProbe.measure(set, generic, writer(p3Backend, p3Prime)))
        for (neat in listOf(0.25f, 0.75f)) {
            val w = WordWriter { labels ->
                p3Backend.write(labels, Primed.NONE, neat, 1, Random(seed++)).map { it?.trajectory }
            }
            report("P3, unprimed, neatness $neat", FidelityProbe.measure(set, generic, w))
        }
    }

    private fun assemblyWriter(index: GlyphIndex): WordWriter {
        val assembly = GlyphAssembly(index)
        var seed = 100L
        return WordWriter { labels ->
            val rng = Random(seed++)
            labels.map { label ->
                val variation = Variation.forNeatness(Variation.DEFAULT_NEATNESS)
                (assembly.word(label, variation, rng, HashSet()) as? AssembledWord.Written)
                    ?.trajectory
            }
        }
    }

    private fun report(name: String, r: FidelityResult) {
        val scores = r.perWord.mapNotNull { it.f }
        println(
            "$name: F=${fmt(r.median)} mean=${fmt(scores.average().toFloat())} over ${scores.size}; " +
                "wrote ${r.written}/${r.asked}; mean dB=${fmt(r.perWord.map { it.dBackend }.average().toFloat())}",
        )
    }

    private fun done(p: Fitter.Progress) = (p as Fitter.Progress.Done).checkpoint

    private fun seconds(started: Long) = (System.nanoTime() - started) / NANOS

    private fun distance(a: FloatArray, b: FloatArray): Float =
        kotlin.math.sqrt(a.indices.sumOf { ((a[it] - b[it]) * (a[it] - b[it])).toDouble() }).toFloat()

    private fun norm(a: FloatArray): Float = kotlin.math.sqrt(a.sumOf { (it * it).toDouble() }).toFloat()

    private companion object {
        const val NANOS = 1_000_000_000L
    }

    private fun fmt(v: Float?): String = v?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "-"
}
