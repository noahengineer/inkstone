package dev.inkstone.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.inkstone.app.handwriting.HandwritingWriter
import dev.inkstone.core.handwriting.ClassCalibration
import dev.inkstone.core.handwriting.GateCalibration
import dev.inkstone.core.handwriting.GateCalibrator
import dev.inkstone.core.handwriting.GateRule
import dev.inkstone.core.handwriting.GlyphIndex
import dev.inkstone.core.handwriting.HandwritingSynthesizer
import dev.inkstone.core.handwriting.LabelBook
import dev.inkstone.core.handwriting.NeuralBackend
import dev.inkstone.core.handwriting.PenDynamics
import dev.inkstone.core.handwriting.Refusal
import dev.inkstone.core.handwriting.ReliabilityGate
import dev.inkstone.core.handwriting.TokenClass
import dev.inkstone.core.handwriting.WordOutcome
import dev.inkstone.core.handwriting.WordSample
import dev.inkstone.core.handwriting.WordSampleCodec
import dev.inkstone.core.handwriting.WriteRequest
import dev.inkstone.core.handwriting.WriteStyle
import dev.inkstone.core.handwriting.neural.KotlinSynthesisNetwork
import dev.inkstone.core.model.GeneratedInk
import dev.inkstone.core.model.InkReader
import dev.inkstone.core.model.InkReading
import dev.inkstone.core.model.InkToRead
import org.json.JSONObject
import java.io.File

/**
 * **Debug builds only.** docs/HANDWRITING-MATHS.md §5.4 — maths written through the **real** gate:
 * each expression of a plan, with the *Maths* switch on, through the production synthesizer and ML Kit.
 *
 * ```
 * adb shell am broadcast -n dev.inkstone/.app.debug.MathsGateReceiver --es plan maths-plan.json
 *   [--es profile tab-profile] [--es rules WORD=PUNCTUATION,NUMBER=CASE_ALIKE,FORMULA=CASE_ALIKE]
 *   [--ei seeds 3] [--ef xheight 6.43] [--ez calibrate true]
 * adb logcat -s MathsGate
 * ```
 *
 * **The plan** is `files/debug/<plan>`, written by `MathsLayoutReport` on the JVM: each expression
 * with the runs the synthesizer reads back, in order. Every reading is recorded on its way through the
 * gate, and named by its pre-context — run *i* is read after the runs before it, joined by spaces — so
 * each run's first reading can be scored under three rules on the same ink: the gate's (a token with a
 * digit judged on its first reading that holds one, docs/HANDWRITING-SYNTHESIS.md §21.4.2), the top
 * reading alone (§21.4.1), and any of the five (before either).
 *
 * **The profile** is this device's own, with its calibration, unless `profile` names a pulled one whose
 * `.hws` shards are in `files/debug/<profile>/words/`, read with no corrections (as the JVM sheet reads it)
 * and written with the shipped model fitted to its dynamics. `rules` then gives it the calibrated rule
 * per class; without them it is uncalibrated. With `calibrate`, a pulled profile's own words are also
 * read as `GateCalibrator` reads them, and each class's pass rate is logged under all three rules.
 *
 * The expressions and what ML Kit read them as are the experimenter's, so they are logged; nothing
 * from the profile itself is (§9) — the calibration logs counts, never a word. Nothing here touches a note.
 */
class MathsGateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val args = listOf(
            intent.getStringExtra(EXTRA_PLAN) ?: DEFAULT_PLAN,
            intent.getStringExtra(EXTRA_PROFILE).orEmpty(),
            intent.getStringExtra(EXTRA_RULES).orEmpty(),
            intent.getIntExtra(EXTRA_SEEDS, DEFAULT_SEEDS).toString(),
            intent.getFloatExtra(EXTRA_X_HEIGHT, X_HEIGHT_PT).toString(),
            intent.getBooleanExtra(EXTRA_CALIBRATE, false).toString(),
        ).joinToString("|")
        DebugTaskWorker.enqueue(context, DebugTaskWorker.TASK_MATHS_GATE, args)
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Deps {
        fun writer(): HandwritingWriter

        fun reader(): InkReader
    }

    /** Every ink [delegate] was asked to read, with what it read, in order. */
    private class Recording(private val delegate: InkReader) : InkReader {
        val reads = ArrayList<Pair<String?, List<String>>>()

        override suspend fun read(inks: List<InkToRead>, candidates: Int): InkReading {
            val reading = delegate.read(inks, candidates)
            if (reading is InkReading.Read) {
                inks.forEachIndexed { i, ink ->
                    reads += ink.preContext to reading.results.getOrNull(i).orEmpty().map { it.text.trim() }
                }
            }
            return reading
        }
    }

    /** One reading scored three ways: the gate, the top reading alone, any of five. */
    private class Score(val gate: Boolean, val top: Boolean, val among: Boolean)

    private class Tally {
        var reads = 0
        var gate = 0
        var top = 0
        var among = 0

        fun add(score: Score) {
            reads++
            if (score.gate) gate++
            if (score.top) top++
            if (score.among) among++
        }

        override fun toString() = "gate $gate/$reads, top reading alone $top/$reads, any of five $among/$reads"
    }

    internal companion object {
        private const val TAG = "MathsGate"
        private const val EXTRA_PLAN = "plan"
        private const val EXTRA_PROFILE = "profile"
        private const val EXTRA_RULES = "rules"
        private const val EXTRA_SEEDS = "seeds"
        private const val EXTRA_X_HEIGHT = "xheight"
        private const val EXTRA_CALIBRATE = "calibrate"
        private const val DEFAULT_PLAN = "maths-plan.json"
        private const val DEFAULT_SEEDS = 1
        private const val DIR = "debug"
        private const val ARG_RULES = 2
        private const val ARG_SEEDS = 3
        private const val ARG_X_HEIGHT = 4
        private const val ARG_CALIBRATE = 5

        /** The JVM sheet's style (`MathsLayoutReport`): 9 pt unless told, its wrap and pitch. */
        private const val X_HEIGHT_PT = 9f
        private const val WRAP_PT = 900f
        private const val PITCH_PT = 60f
        private const val SEED_BASE = 100L
        private const val SEED_STRIDE = 1000L

        /** `HandwritingSynthesizer`'s own pre-context: the runs before, joined by spaces, at most 20 characters. */
        private const val PRE_CONTEXT = 20

        @Suppress("LongMethod", "CyclomaticComplexMethod")
        suspend fun run(context: Context, argument: String?) {
            val parts = argument.orEmpty().split('|')
            val dir = File(context.applicationContext.filesDir, DIR)
            val plan = planOf(File(dir, parts.getOrNull(0).orEmpty().ifBlank { DEFAULT_PLAN })) ?: run {
                Log.i(TAG, "no plan in files/$DIR: write one with MathsLayoutReport and push it")
                return
            }
            val seeds = parts.getOrNull(ARG_SEEDS)?.toIntOrNull() ?: DEFAULT_SEEDS
            val xHeight = parts.getOrNull(ARG_X_HEIGHT)?.toFloatOrNull() ?: X_HEIGHT_PT
            val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
            val writer = deps.writer()
            val own = writer.profile()
            val pulled = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            val index: GlyphIndex
            val neural: NeuralBackend?
            var calibration: GateCalibration
            var samples = emptyList<WordSample>()
            if (pulled == null) {
                index = own.index
                neural = own.neural
                calibration = own.calibration
            } else {
                samples = File(File(dir, pulled), "words").listFiles { f -> f.name.endsWith(".hws") }.orEmpty()
                    .sortedBy { it.name }
                    .flatMap { WordSampleCodec.decode(it.readBytes()).orEmpty() }
                index = GlyphIndex.build(samples, LabelBook())
                val dynamics = PenDynamics.fit(index.wholeWords().map { it.trajectory }, index.stats.penUpMs)
                neural = writer.weights()?.let { NeuralBackend(KotlinSynthesisNetwork(it), dynamics) }
                calibration = GateCalibration.UNCALIBRATED
            }
            parts.getOrNull(ARG_RULES)?.takeIf { it.isNotBlank() }?.let { calibration = rulesOf(it) }
            Log.i(
                TAG,
                "profile=${pulled ?: "own"} units=${index.unitCount} model=${neural?.modelId} " +
                    "rules=${calibration.classes.values.joinToString { "${it.tokenClass}=${it.rule}" }} " +
                    "expressions=${plan.size} seeds=$seeds xheight=$xHeight",
            )
            if (parts.getOrNull(ARG_CALIBRATE) == "true" && samples.isNotEmpty()) {
                calibrate(samples, deps.reader())
            }
            val recording = Recording(deps.reader())
            val synthesizer = HandwritingSynthesizer(ReliabilityGate(recording, calibration), neural)
            val style = WriteStyle(
                xHeightPt = xHeight,
                slantRad = index.stats.slantRad,
                angleRad = 0f,
                wrapWidthPt = WRAP_PT,
                linePitchPt = PITCH_PT,
            )
            val digitRuns = Tally()
            val otherRuns = Tally()
            var complete = 0
            var firstTime = 0
            var topFirstTime = 0
            var oldFirstTime = 0
            var total = 0
            for ((k, entry) in plan.withIndex()) {
                val (text, pieces) = entry
                for (s in 0 until seeds) {
                    total++
                    recording.reads.clear()
                    val seed = SEED_BASE + k + s * SEED_STRIDE
                    val phrase = synthesizer.write(WriteRequest(text, style, seed, maths = true), index)
                    val firstReads = score("'$text'#$s", pieces, recording.reads, calibration, digitRuns, otherRuns)
                    val words = phrase.words
                    val written = words.filterIsInstance<WordOutcome.Written>()
                    val done = phrase.complete
                    val attempts = written.maxOfOrNull { it.attempts } ?: 0
                    if (done) complete++
                    if (done && attempts == 1) firstTime++
                    val readRuns = firstReads.values
                    val wouldBe = readRuns.isNotEmpty() && words.none { it.isMissing() }
                    if (wouldBe && readRuns.all { it.top }) topFirstTime++
                    if (wouldBe && readRuns.all { it.among }) oldFirstTime++
                    val outcome = words.joinToString(" ", transform = ::describe)
                    Log.i(TAG, "'$text'#$s complete=$done attempts=$attempts $outcome")
                }
            }
            Log.i(
                TAG,
                "total: complete $complete/$total, first time $firstTime/$total (top reading alone would have " +
                    "been $topFirstTime/$total, any of five $oldFirstTime/$total)",
            )
            Log.i(TAG, "first readings, runs with a digit: $digitRuns")
            Log.i(TAG, "first readings, other runs: $otherRuns")
        }

        /**
         * A pulled profile's own words, read exactly as `GateCalibrator` reads them, and each class's control
         * scored under the three rules with the rule calibration chooses for it. Counts only.
         */
        private suspend fun calibrate(samples: List<WordSample>, reader: InkReader) {
            val readings = GateCalibrator.read(samples, LabelBook(), reader) ?: run {
                Log.i(TAG, "calibrate: the recogniser could not read")
                return
            }
            val calibration = GateCalibrator.calibrate(readings, 0L)
            for (c in calibration.classes.values.sortedBy { it.tokenClass }) {
                val group = readings.filter { TokenClass.of(it.label) == c.tokenClass }
                val control = if (c.biased) group else group.filter { it.vouched }
                val tally = Tally()
                for (r in control) tally.add(scoreOf(c.rule, r.label, r.readings))
                Log.i(
                    TAG,
                    "calibrate ${c.tokenClass}: rule=${c.rule} judgeable=${c.judgeable} words=${c.samples} " +
                        "control=${control.size}${if (c.biased) " (biased)" else " (vouched)"} $tally",
                )
            }
            Log.i(TAG, "calibrate: ${readings.size} words read")
        }

        private fun scoreOf(rule: GateRule, label: String, readings: List<String>): Score {
            val gate = rule.passes(label, readings)
            val top = if (GateRule.digitBearing(label)) rule.passesAmong(label, readings.take(1)) else gate
            return Score(gate, top, rule.passesAmong(label, readings))
        }

        /** Each run's first reading, scored three ways on the same ink. Every reading is logged. */
        @Suppress("LongParameterList")
        private fun score(
            what: String,
            pieces: List<String>,
            reads: List<Pair<String?, List<String>>>,
            calibration: GateCalibration,
            digitRuns: Tally,
            otherRuns: Tally,
        ): Map<Int, Score> {
            val firstReads = HashMap<Int, Score>()
            for ((call, read) in reads.withIndex()) {
                val (pre, readings) = read
                val run = pieces.indices.firstOrNull { contextBefore(pieces, it) == pre }
                if (run == null) {
                    Log.w(TAG, "  $what read $call: no run has pre-context '$pre'")
                    continue
                }
                val label = pieces[run]
                val rule = calibration.ruleFor(label)
                val score = scoreOf(rule, label, readings)
                if (run !in firstReads) {
                    firstReads[run] = score
                    (if (GateRule.digitBearing(label)) digitRuns else otherRuns).add(score)
                }
                Log.i(
                    TAG,
                    "  $what read $call '$label' $rule gate=${score.gate} top=${score.top} among=${score.among} " +
                        "read=$readings",
                )
            }
            return firstReads
        }

        /** One word's outcome: its tries and how many strokes the model wrote, or why it was refused. */
        private fun describe(w: WordOutcome): String = when (w) {
            is WordOutcome.Written -> {
                val tags = w.strokeTags ?: List(w.ink.strokeCount) { w.backend to w.model }
                val net = tags.count { it.first == GeneratedInk.NEURAL }
                "${w.text}[${w.attempts}, net $net/${tags.size}${if (w.readBack) "" else ", unread"}]"
            }

            is WordOutcome.Refused -> "✕${w.text}: ${w.reason}"
        }

        private fun WordOutcome.isMissing() = this is WordOutcome.Refused && reason is Refusal.MissingLetters

        private fun contextBefore(pieces: List<String>, i: Int): String? =
            pieces.take(i).joinToString(" ").takeLast(PRE_CONTEXT).ifEmpty { null }

        private fun planOf(file: File): List<Pair<String, List<String>>>? {
            if (!file.exists()) return null
            val list = JSONObject(file.readText()).getJSONArray("expressions")
            return List(list.length()) { i ->
                val e = list.getJSONObject(i)
                val pieces = e.getJSONArray("pieces")
                e.getString("text") to List(pieces.length()) { pieces.getString(it) }
            }
        }

        /** `WORD=PUNCTUATION,NUMBER=CASE_ALIKE`: a calibration holding just those rules, every class judgeable. */
        private fun rulesOf(spec: String): GateCalibration {
            val classes = spec.split(',').mapNotNull { pair ->
                val (name, rule) = pair.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                val tokenClass = TokenClass.entries.firstOrNull { it.name == name.trim() } ?: return@mapNotNull null
                val gateRule = GateRule.entries.firstOrNull { it.name == rule.trim() } ?: return@mapNotNull null
                tokenClass to ClassCalibration(
                    tokenClass, 0, 0, true, emptyMap(), emptyMap(), emptyMap(), gateRule, judgeable = true,
                )
            }.toMap()
            return GateCalibration(0L, classes)
        }
    }
}
