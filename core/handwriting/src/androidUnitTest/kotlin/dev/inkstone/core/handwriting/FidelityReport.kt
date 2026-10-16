package dev.inkstone.core.handwriting

import dev.inkstone.core.handwriting.neural.Primed
import dev.inkstone.core.handwriting.quality.FidelityProbe
import dev.inkstone.core.handwriting.quality.FidelityResult
import dev.inkstone.core.handwriting.quality.FidelitySet
import dev.inkstone.core.handwriting.quality.WordWriter
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * docs/HANDWRITING-SYNTHESIS.md H5 — **a report, not a check**: §8.3's fidelity over a pulled profile,
 * for both backends, and the curve over the profile's own history. See [ProfileReports] for the inputs.
 *
 * ```
 * INKSTONE_HW_PROFILE=… ./gradlew :core:handwriting:testDebugUnitTest --tests '*FidelityReport' --rerun
 * ```
 */
class FidelityReport {

    @Test
    fun `fidelity of both backends over the users held-out words`() = runTest(timeout = 60.minutes) {
        val loaded = ProfileReports.load() ?: return@runTest
        val samples = loaded.samples
        println("profile: ${samples.size} samples; " + samples.groupingBy { it.split }.eachCount())
        println("  label sources: " + samples.groupingBy { it.labelSource }.eachCount())
        println("  quick start: ${samples.count { it.noteId.value == QuickStart.NOTE_ID }}")
        val index = GlyphIndex.build(samples, loaded.labels)
        val set = FidelitySet.of(samples, loaded.labels, index)
        println(
            "held-out usable: ${set.words.size} of ${samples.count { it.split == SampleSplit.HELD_OUT }}; " +
                "same-word pairs ${set.wordPairs}; pooled d_self ${fmt(set.pooledSelf)}",
        )
        for (w in set.words) println("  held-out '${w.label}' d_self=${fmt(set.selfOf(w.label))}")

        val net = ProfileReports.network(loaded.weights)
        val dynamics = PenDynamics.fit(index.wholeWords().map { it.trajectory }, index.stats.penUpMs)
        val backend = NeuralBackend(net, dynamics)
        val neatness = Variation.DEFAULT_NEATNESS
        var seed = 1L
        val unprimed =
            WordWriter { labels ->
                backend.write(labels, Primed.NONE, neatness, 1, Random(seed++)).map { it?.trajectory }
            }
        val generic = FidelityProbe.generic(set, unprimed)
        println("generic: ${generic.size} words written")

        val assembly = assemblyWriter(index)
        report("assembly", FidelityProbe.measure(set, generic, assembly))
        report("neural, unprimed (the zero)", FidelityProbe.measure(set, generic, unprimed))
        val primed = backend.prime(index, SourcePreference(), Random(3))
        val primedWriter =
            WordWriter { labels -> backend.write(labels, primed, neatness, 1, Random(seed++)).map { it?.trajectory } }
        report("neural, primed (P1 as H4 ships it)", FidelityProbe.measure(set, generic, primedWriter))
    }

    /**
     * §8.3 asks for 50 held-out words and a pulled profile may have ten, so the decisions in the notes
     * also rest on a **cross-validated** F: every usable word takes a turn as held out ([FOLDS] folds),
     * each fold's backends built only from the other folds. Generic distances are fold-independent.
     */
    @Test
    @Suppress("LongMethod") // A report: one long, linear measurement.
    fun `cross-validated fidelity and the learning curve`() = runTest(timeout = 120.minutes) {
        val loaded = ProfileReports.load() ?: return@runTest
        val all = loaded.samples.map { it.copy(split = SampleSplit.TRAIN) }
        val fullIndex = GlyphIndex.build(all, loaded.labels)
        val net = ProfileReports.network(loaded.weights)
        val backend =
            NeuralBackend(net, PenDynamics.fit(fullIndex.wholeWords().map { it.trajectory }, fullIndex.stats.penUpMs))
        val neatness = Variation.DEFAULT_NEATNESS
        var seed = 1L
        val unprimed =
            WordWriter { labels ->
                backend.write(labels, Primed.NONE, neatness, 1, Random(seed++)).map { it?.trajectory }
            }
        val everything = FidelitySet.of(all.map { it.copy(split = SampleSplit.HELD_OUT) }, loaded.labels, fullIndex)
        val generic = FidelityProbe.generic(everything, unprimed)
        println("cv: ${everything.words.size} usable words, generic written for ${generic.size}")

        for (fraction in listOf(0.25f, 0.5f, 0.75f, 1f)) {
            val asm = ArrayList<Float>()
            val pri = ArrayList<Float>()
            var asmWritten = 0
            var asked = 0
            for (fold in 0 until FOLDS) {
                val split = all.map { s ->
                    val k = foldOf(s)
                    when {
                        k == fold -> s.copy(split = SampleSplit.HELD_OUT)
                        keep(s, fraction) -> s
                        else -> null
                    }
                }.filterNotNull()
                val index = GlyphIndex.build(split, loaded.labels)
                val set = FidelitySet.of(split, loaded.labels, fullIndex)
                val a = FidelityProbe.measure(set, generic, assemblyWriter(index))
                asm += a.perWord.mapNotNull { it.f }
                asmWritten += a.written
                asked += a.asked
                if (fraction == 1f) {
                    val primed = backend.prime(index, SourcePreference(), Random(fold.toLong()))
                    val w =
                        WordWriter { labels ->
                            backend.write(labels, primed, neatness, 1, Random(seed++)).map { it?.trajectory }
                        }
                    pri += FidelityProbe.measure(set, generic, w).perWord.mapNotNull { it.f }
                }
            }
            println(
                "cv fraction=$fraction: assembly F=${fmt(med(asm))} mean=${fmt(asm.average().toFloat())} " +
                    "over ${asm.size} (wrote $asmWritten/$asked)" +
                    if (pri.isNotEmpty()) {
                        "; neural primed F=${fmt(
                            med(pri),
                        )} mean=${fmt(pri.average().toFloat())}"
                    } else {
                        ""
                    },
            )
        }
        val zero = FidelityProbe.measure(everything, generic, unprimed).perWord.mapNotNull { it.f }
        println(
            "cv: neural unprimed (fresh draw against the zero) F=${fmt(
                med(zero),
            )} mean=${fmt(zero.average().toFloat())}",
        )
    }

    private fun foldOf(s: WordSample): Int = Math.floorMod(s.sourceKey.hashCode(), FOLDS)

    /** A stable subsample of the training words, for the learning curve. */
    private fun keep(s: WordSample, fraction: Float): Boolean =
        fraction >= 1f || Math.floorMod(s.id.hashCode() * 31 + 7, 1000) < fraction * 1000

    private fun med(v: List<Float>): Float? = v.takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2] }

    private fun assemblyWriter(index: GlyphIndex): WordWriter {
        val assembly = GlyphAssembly(index)
        var seed = 100L
        return WordWriter { labels ->
            val rng = Random(seed++)
            labels.map { label ->
                val word = assembly.word(label, Variation.forNeatness(Variation.DEFAULT_NEATNESS), rng, HashSet())
                (word as? AssembledWord.Written)?.trajectory
            }
        }
    }

    private fun report(name: String, result: FidelityResult) {
        println(
            "$name: F=${fmt(result.median)} over ${result.perWord.count { it.f != null }} words; " +
                "wrote ${result.written}/${result.asked}",
        )
        for (w in result.perWord) {
            println("  '${w.label}' dG=${fmt(w.dGeneric)} dB=${fmt(w.dBackend)} dS=${fmt(w.dSelf)} F=${fmt(w.f)}")
        }
        File(ProfileReports.out, "fidelity-${name.substringBefore(',').replace(' ', '-')}.csv").writeText(
            "label,dGeneric,dBackend,dSelf,F\n" +
                result.perWord.joinToString("\n") { "${it.label},${it.dGeneric},${it.dBackend},${it.dSelf},${it.f}" },
        )
    }

    private fun fmt(v: Float?): String = v?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "-"

    private companion object {
        const val FOLDS = 5
    }
}
