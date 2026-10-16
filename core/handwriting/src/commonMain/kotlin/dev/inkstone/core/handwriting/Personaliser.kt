package dev.inkstone.core.handwriting

import dev.inkstone.core.handwriting.neural.Adapters
import dev.inkstone.core.handwriting.neural.FitCheckpoint
import dev.inkstone.core.handwriting.neural.FitPlan
import dev.inkstone.core.handwriting.neural.FitWord
import dev.inkstone.core.handwriting.neural.Fitter
import dev.inkstone.core.handwriting.neural.KotlinSynthesisNetwork
import dev.inkstone.core.handwriting.neural.Primed
import dev.inkstone.core.handwriting.neural.Rung
import dev.inkstone.core.handwriting.neural.TrainableNetwork
import dev.inkstone.core.handwriting.neural.WeightsFile
import dev.inkstone.core.handwriting.quality.BackendPolicy
import dev.inkstone.core.handwriting.quality.CharFails
import dev.inkstone.core.handwriting.quality.FidelityProbe
import dev.inkstone.core.handwriting.quality.FidelityResult
import dev.inkstone.core.handwriting.quality.FidelitySet
import dev.inkstone.core.handwriting.quality.PersonalState
import dev.inkstone.core.handwriting.quality.PolicyState
import dev.inkstone.core.handwriting.quality.PrimeMode
import dev.inkstone.core.handwriting.quality.QualityMeasure
import dev.inkstone.core.handwriting.quality.Stage
import dev.inkstone.core.handwriting.quality.WordWriter
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.InkReader
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A personalisation artefact on disk: `"IHWP" | version | base model | rung | payload`. Naming the base
 * model in the file as well as in `profile.json` means an artefact can never be applied to weights it
 * was not fitted to (§4.4), whatever state the JSON is in.
 */
public object Artefact {
    private val MAGIC = "IHWP".encodeToByteArray()
    private const val VERSION = 1

    public fun style(baseModel: String, style: FloatArray): ByteArray = wrap(baseModel, Rung.STYLE) { sink ->
        sink.varint(style.size.toLong())
        style.forEach(sink::float)
    }

    public fun adapters(baseModel: String, adapters: Adapters): ByteArray =
        wrap(baseModel, Rung.ADAPTERS) { it.bytes(Adapters.encode(adapters)) }

    /** The style vector in [bytes], if it was fitted to [baseModel]. */
    public fun readStyle(bytes: ByteArray?, baseModel: String): FloatArray? {
        val r = open(bytes, baseModel, Rung.STYLE) ?: return null
        val n = r.varint()?.toInt()?.takeIf { it in 1..MAX_STYLE } ?: return null
        return FloatArray(n) { r.float() ?: return null }
    }

    public fun readAdapters(bytes: ByteArray?, baseModel: String): Adapters? {
        val r = open(bytes, baseModel, Rung.ADAPTERS) ?: return null
        return Adapters.decode(r.take(r.remaining) ?: return null)
    }

    private fun wrap(baseModel: String, rung: Rung, body: (ByteSink) -> Unit): ByteArray {
        val sink = ByteSink()
        sink.bytes(MAGIC)
        sink.byte(VERSION)
        sink.string(baseModel)
        sink.byte(if (rung == Rung.STYLE) STYLE_CODE else ADAPTERS_CODE)
        body(sink)
        return sink.toByteArray()
    }

    @Suppress("ReturnCount")
    private fun open(bytes: ByteArray?, baseModel: String, rung: Rung): ByteCursor? {
        val r = ByteCursor(bytes ?: return null)
        if (r.take(MAGIC.size)?.contentEquals(MAGIC) != true) return null
        if ((r.byte() ?: return null) > VERSION) return null
        if (r.string() != baseModel) return null
        val code = r.byte() ?: return null
        if (code != (if (rung == Rung.STYLE) STYLE_CODE else ADAPTERS_CODE)) return null
        return r
    }

    private const val STYLE_CODE = 2
    private const val ADAPTERS_CODE = 3
    private const val MAX_STYLE = 4096
}

/**
 * ⭐ docs/HANDWRITING-SYNTHESIS.md §8 — the personalisation pass the worker runs while the tablet charges
 * and is idle. Platform-free: `:app` supplies the file system, the recogniser and the weights, and a
 * way to change `profile.json` under the learner's lock.
 *
 * ## One pass
 *
 * 1. **The base model**: artefacts fitted to other weights go to the trash, and the ladder starts over.
 * 2. **The gate's calibration** (§8.2), whenever the profile has more words than it was measured on.
 * 3. **One round of the ladder** (§8.1), resumable at every step ([PersonalState.stage]):
 *    P2 (fit, then decide), P3 (fit on P2's style or the model's own, then decide), P1 (choose a prime),
 *    then a measurement (§8.3, §8.4). A round starts when there is none yet, or when the profile has
 *    grown by a quarter since the last.
 * 4. **Acceptance** ([accepts]): an artefact replaces what is in force only if fidelity on every word no
 *    fit saw rises by at least [DELTA] and the network's first-pass gate rate does not fall by more than
 *    [GATE_SLACK]. Otherwise it goes to the trash, and the notes say it did not help.
 * 5. **The measurement**: F of both backends on the held-out words, the network's first-pass rate over
 *    [BackendPolicy.MIN_GATED] gated words with per-character failures, and §8.4's decision. One more
 *    point on the curve.
 *
 * Every fit checkpoints ([Fitter]); a pass that runs out of time stops at a checkpoint and the next one
 * carries on, and a kill loses at most [FitPlan.checkpointEvery] iterations.
 */
@Suppress("LongParameterList", "TooManyFunctions")
public class Personaliser(
    private val fs: FileSystem,
    private val key: ProfileKey,
    private val weights: WeightsFile,
    private val reader: InkReader,
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
    private val plans: Plans = Plans(),
) {
    /** The rung plans and the sizes of a pass; tests shrink them. */
    public data class Plans(
        val p2: FitPlan = FitPlan.P2,
        val p3: FitPlan = FitPlan.P3,
        val gated: Int = BackendPolicy.MIN_GATED,
        val acceptGated: Int = ACCEPT_GATED,
        val primeCandidates: Int = PRIME_SETS,
    )

    /** What a pass did. */
    public sealed interface Outcome {
        /** Nothing to do: no round is owed. */
        public data object Idle : Outcome

        /** Out of time at [stage]; the next pass carries on there. */
        public data class Paused(val stage: Stage) : Outcome

        /** A round finished, with [measure]. */
        public data class Measured(val measure: QualityMeasure) : Outcome

        /** Too little writing to fit or measure anything yet. */
        public data class TooLittle(val words: Int) : Outcome

        /** The recogniser could not read: nothing can be calibrated or gated. */
        public data object Unavailable : Outcome
    }

    private val modelId = weights.modelId

    /**
     * One pass. [edit] applies a change to `profile.json` under the learner's lock and returns the state
     * as written — the only way this touches the file the learner also writes.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    public suspend fun pass(
        samples: List<WordSample>,
        labels: LabelBook,
        state: ProfileState,
        outOfTime: () -> Boolean,
        edit: suspend ((ProfileState) -> ProfileState) -> ProfileState,
    ): Outcome {
        var st = state
        if (st.personal.baseModel != modelId) {
            trashArtefacts("base model ${st.personal.baseModel} -> $modelId")
            // The switch was decided on the old model's numbers: back to the user's letters until measured again.
            st = edit { it.copy(personal = PersonalState(baseModel = modelId), policy = PolicyState.INITIAL) }
        }
        // The same words GateCalibrator.read reads, or the comparison below never settles.
        val usable = samples.count {
            it.sourceKey !in labels.rejected &&
                it.noteId.value !in labels.excludedNotes &&
                GlyphIndex.normalisedWord(it, labels) != null
        }
        val calibratedOn = st.gate?.wordsRead ?: -1
        val outdated = (st.gate?.ruleVersion ?: 0) < GateCalibration.RULE_VERSION
        if (st.gate == null || calibratedOn < usable || outdated) {
            val readings = GateCalibrator.read(samples, labels, reader) ?: return Outcome.Unavailable
            val calibration = GateCalibrator.calibrate(readings, now())
            // Both rates for the chosen rule: for a class with digits, the gap is what §21.4 costs this hand.
            val classes = calibration.classes.values.joinToString { c ->
                fun pc(rates: Map<GateRule, Float>) = ((rates[c.rule] ?: 0f) * PERCENT).roundToInt()
                val control = if (c.biased) "${c.samples}, biased" else "${c.vouched} vouched"
                "${c.tokenClass}=${c.rule}/${c.judgeable} ${pc(c.positive)}% " +
                    "(${pc(c.positiveAmongCandidates)}% among candidates) of $control"
            }
            log("calibrated: $classes over ${calibration.wordsRead} words")
            st = edit { it.copy(gate = calibration) }
        }
        val train = samples.count { it.split == SampleSplit.TRAIN }
        val work = Work(samples, labels, st)
        if (work.fit.size < MIN_FIT_WORDS) return Outcome.TooLittle(work.fit.size)
        if (st.personal.stage == Stage.DONE) {
            val fitted = st.personal.fittedOn
            val due = fitted == 0 || train >= fitted + maxOf(MIN_GROWTH, fitted / GROWTH_DIVISOR)
            if (!due) return Outcome.Idle
            st = edit { it.copy(personal = it.personal.copy(stage = Stage.P2)) }
        }
        while (true) {
            if (outOfTime()) return Outcome.Paused(st.personal.stage)
            when (st.personal.stage) {
                Stage.P2, Stage.P3 -> {
                    val rung = if (st.personal.stage == Stage.P2) Rung.STYLE else Rung.ADAPTERS
                    val done = fit(rung, work, st, outOfTime) ?: return Outcome.Paused(st.personal.stage)
                    // A fit that finished as its time ran out is decided at the start of the next pass,
                    // with a whole budget: its checkpoint stays on disk until the decision is recorded.
                    if (outOfTime()) return Outcome.Paused(st.personal.stage)
                    val accepted = decide(rung, done, work, st)
                    st = edit { s ->
                        val p = s.personal
                        val next = if (rung == Rung.STYLE) Stage.P3 else Stage.P1
                        s.copy(
                            personal = if (rung == Rung.STYLE) {
                                p.copy(style = accepted || p.style, stage = next)
                            } else {
                                p.copy(adapters = accepted || p.adapters, stage = next)
                            },
                        )
                    }
                    // Only now: killed before this, the next pass finds the finished fit and decides again.
                    toTrash(HandwritingLayout.checkpoint(key), "checkpoint.bin")
                }

                Stage.P1 -> {
                    val (mode, words) = choosePrime(work, st)
                    st = edit {
                        val p = it.personal
                        it.copy(
                            personal = p.copy(
                                prime = mode,
                                primeWords = words,
                                primeDecided = true,
                                stage = Stage.MEASURE,
                            ),
                        )
                    }
                }

                Stage.MEASURE -> {
                    val measure = measure(samples, labels, st, train)
                    st = edit {
                        it.copy(
                            quality = it.quality + measure,
                            policy = BackendPolicy.decide(it.policy, measure),
                            personal = it.personal.copy(
                                stage = Stage.DONE,
                                fittedOn = train,
                                lastRunEpochMillis = now(),
                            ),
                        )
                    }
                    log(
                        "measured: F assembly=${measure.fAssembly} neural=${measure.fNeural} " +
                            "firstPass=${measure.neuralFirstPass} over ${measure.gatedWords} " +
                            "primary=${st.policy.primary}",
                    )
                    return Outcome.Measured(measure)
                }

                Stage.DONE -> return Outcome.Idle
            }
        }
    }

    // --- The evaluation set and the fit's words ---------------------------------------------------------

    /**
     * Everything a round reads, built once per pass. A share of the training words ([Fitter.VALIDATION_EVERY])
     * is set aside: the fits stop early on them, and acceptance is measured on them **and** the held-out
     * words — every word no fit saw, because ten held-out words cannot tell two configurations apart.
     */
    private inner class Work(private val samples: List<WordSample>, val labels: LabelBook, st: ProfileState) {
        val trainer = TrainableNetwork(weights)
        private val valIds = samples.filter {
            it.split == SampleSplit.TRAIN &&
                Fingerprint.of(it.sourceKey.encodeToByteArray()).mod(Fitter.VALIDATION_EVERY.toLong()) == 0L
        }.mapTo(HashSet()) { it.id }
        val evalSamples = samples.map { if (it.id in valIds) it.copy(split = SampleSplit.HELD_OUT) else it }
        val index = GlyphIndex.build(evalSamples, labels)
        val fit: List<FitWord> = index.wholeWords().filter { trainer.canWrite(it.text) }
            .mapNotNull { u -> trainer.sequence(u.text, u.trajectory)?.let { FitWord(u.source.sampleId, it) } }
        val validation: List<FitWord> = evalSamples.filter { it.id in valIds }.mapNotNull { s ->
            val ink = GlyphIndex.normalisedWord(s, labels) ?: return@mapNotNull null
            trainer.sequence(labels.labelOf(s), ink)?.let { FitWord(s.id, it) }
        }
        val words: Map<String, FitWord> = (fit + validation).associateBy { it.sampleId }
        val set: FidelitySet = FidelitySet.of(evalSamples, labels, index)
        val dynamics: PenDynamics = PenDynamics.fit(index.wholeWords().map { it.trajectory }, index.stats.penUpMs)
        private var genericCache: Map<String, Float>? = null
        private var seed = 1L

        suspend fun generic(): Map<String, Float> = genericCache ?: FidelityProbe.generic(
            set,
            writer(backend(null, null, dynamics), Primed.NONE),
        ).also { genericCache = it }

        fun writer(b: NeuralBackend, primed: Primed): WordWriter = WordWriter { ls ->
            b.write(ls, primed, Variation.DEFAULT_NEATNESS, 1, Random(seed++)).map { it?.trajectory }
        }

        /** The gate as the profile now calibrates it. */
        val gate = ReliabilityGate(reader, st.gate ?: GateCalibration.UNCALIBRATED)

        val vocabulary: List<String> = vocabularyOf(samples, labels)
    }

    /** The profile's own words, for first-pass gate rates. */
    private fun vocabularyOf(samples: List<WordSample>, labels: LabelBook): List<String> =
        samples.map { labels.labelOf(it) }.distinct()

    private fun backend(style: FloatArray?, adapters: Adapters?, dynamics: PenDynamics): NeuralBackend =
        NeuralBackend(KotlinSynthesisNetwork(weights, style, adapters), dynamics)

    // --- P2 and P3 --------------------------------------------------------------------------------------

    /** Carries the rung's fit on, from its checkpoint when there is one. Null when out of time. */
    private suspend fun fit(rung: Rung, work: Work, st: ProfileState, outOfTime: () -> Boolean): FitCheckpoint? {
        val fitter = Fitter(work.trainer, modelId)
        val path = HandwritingLayout.checkpoint(key)
        val saved = loadCheckpoint(fs, key)
            ?.takeIf { c ->
                c.rung == rung && c.baseModel == modelId &&
                    (c.words + c.validation).all { it in work.words }
            }
        if (saved == null && fs.exists(path)) toTrash(path, "checkpoint.bin")
        val plan = if (rung == Rung.STYLE) plans.p2 else plans.p3
        val from = saved ?: fitter.start(plan, work.fit, work.validation)
        if (saved != null) log("resuming $rung at iteration ${saved.iteration}")
        val style = acceptedStyle(st)
        val progress = fitter.run(from, work.words, style, outOfTime) { c -> saveCheckpoint(fs, key, c) }
        return when (progress) {
            is Fitter.Progress.Paused -> null
            is Fitter.Progress.Done -> progress.checkpoint
        }
    }

    /**
     * §8.1's acceptance for a finished fit: the candidate against what is in force, on every word no fit
     * saw. Writes the artefact when it wins; trashes it when it does not.
     */
    private suspend fun decide(rung: Rung, done: FitCheckpoint, work: Work, st: ProfileState): Boolean {
        val style = acceptedStyle(st)
        val adapters = acceptedAdapters(st)
        val baseline = evaluate(work, backend(style, adapters, work.dynamics), Primed.NONE)
        val fittedStyle = done.best[0]
        val fittedAdapters = if (rung == Rung.ADAPTERS) Adapters.of(done.rank, done.best) else null
        val from = style ?: work.trainer.defaultStyle

        // ⭐ Shrinkage (§20): the fit at full strength, then blended back towards what is in force. On the
        // tablet the full adapters raised F by 0.28 and halved the first-pass rate: the strongest blend
        // that keeps the user's words legible is the one worth keeping.
        fun at(alpha: Float): Pair<NeuralBackend, ByteArray> = if (fittedAdapters != null) {
            Adapters.scaled(fittedAdapters, alpha).let {
                backend(style, it, work.dynamics) to
                    Artefact.adapters(modelId, it)
            }
        } else {
            FloatArray(from.size) { from[it] + alpha * (fittedStyle[it] - from[it]) }
                .let { backend(it, adapters, work.dynamics) to Artefact.style(modelId, it) }
        }
        val chosen = SHRINK.asSequence().map { alpha -> alpha to at(alpha) }.firstOrNull { (alpha, candidate) ->
            judge(baseline, evaluate(work, candidate.first, Primed.NONE), "${rung.name} α=$alpha")
        }?.second?.second
        val accepted = chosen != null
        val bytes = chosen ?: when (rung) {
            Rung.STYLE -> Artefact.style(modelId, fittedStyle)
            Rung.ADAPTERS -> Artefact.adapters(modelId, Adapters.of(done.rank, done.best))
        }
        val path = if (rung == Rung.STYLE) HandwritingLayout.styleVector(key) else HandwritingLayout.adapters(key)
        fs.createDirectories(HandwritingLayout.styleDir(key))
        if (accepted) {
            if (fs.exists(path)) toTrash(path, path.substringAfterLast('/'))
            fs.writeBytesAtomically(path, bytes)
        } else {
            fs.createDirectories(HandwritingLayout.trashDir(key))
            val name = "${now()}-rejected-${rung.name.lowercase()}.bin"
            fs.writeBytesAtomically("${HandwritingLayout.trashDir(key)}/$name", bytes)
        }
        return accepted
    }

    /**
     * Whether [candidate] beats [current]: mean F over every word no fit saw rises by at least [DELTA],
     * and the first-pass gate rate over [Plans.acceptGated] words does not fall by more than [GATE_SLACK].
     * The mean, not §8.3's median: while the network is far from the user, most of its words score 0, the
     * median sits at 0 for every configuration, and it could not tell a real improvement from none.
     */
    @Suppress("LongParameterList")
    private suspend fun accepts(
        work: Work,
        current: NeuralBackend,
        currentPrime: Primed,
        candidate: NeuralBackend,
        candidatePrime: Primed,
        what: String,
    ): Boolean = judge(evaluate(work, current, currentPrime), evaluate(work, candidate, candidatePrime), what)

    /** A configuration's fidelity on every word no fit saw, and its first-pass gate rate. */
    private class Evaluation(val fidelity: FidelityResult, val firstPass: Float)

    private suspend fun evaluate(work: Work, b: NeuralBackend, primed: Primed): Evaluation = Evaluation(
        FidelityProbe.measure(work.set, work.generic(), work.writer(b, primed)),
        firstPass(work.gate, work.vocabulary, b, primed, plans.acceptGated).rate,
    )

    /** §8.1's rule, [candidate] against [current]; logged either way. */
    private fun judge(current: Evaluation, candidate: Evaluation, what: String): Boolean {
        val gain = pairedGain(current.fidelity, candidate.fidelity)
        val legible = candidate.firstPass >= current.firstPass - GATE_SLACK
        val ok = gain.mean >= DELTA && gain.mean >= gain.standardError && legible
        log(
            "$what: mean F ${gain.before} -> ${gain.after} " +
                "(gain ${gain.mean} ± ${gain.standardError} over ${gain.words}), " +
                "first pass ${current.firstPass} -> ${candidate.firstPass}: ${if (ok) "accepted" else "rejected"}",
        )
        return ok
    }

    /** Two configurations' F on the same words, paired. */
    public class Gain(
        public val before: Float,
        public val after: Float,
        public val mean: Float,
        public val standardError: Float,
        public val words: Int,
    )

    /**
     * ⭐ The paired gain: per word, the candidate's F less the current's, over the words both scored. A
     * rise must beat [DELTA] **and** its own standard error — with forty-odd words, a mean F moves by
     * about 0.04 between two draws of the *same* configuration (§20), and a bare threshold would accept noise.
     */
    public fun pairedGain(current: FidelityResult, candidate: FidelityResult): Gain {
        val before = current.perWord.associate { it.sampleId to (it.f ?: 0f) }
        val pairs = candidate.perWord.mapNotNull { w -> before[w.sampleId]?.let { b -> b to (w.f ?: 0f) } }
        if (pairs.isEmpty()) return Gain(0f, 0f, 0f, Float.POSITIVE_INFINITY, 0)
        val diffs = pairs.map { it.second - it.first }
        val mean = diffs.average().toFloat()
        val sd = sqrt(diffs.sumOf { ((it - mean) * (it - mean)).toDouble() } / maxOf(1, diffs.size - 1)).toFloat()
        val se = sd / sqrt(diffs.size.toFloat())
        val meanBefore = pairs.map { it.first }.average().toFloat()
        return Gain(meanBefore, pairs.map { it.second }.average().toFloat(), mean, se, diffs.size)
    }

    private fun meanF(scores: List<Float>): Float = if (scores.isEmpty()) 0f else scores.average().toFloat()

    /** A first-pass gate measurement: the rate over [gated] words, and failures per character. */
    private class FirstPass(val rate: Float, val gated: Int, val fails: Map<Char, CharFails>)

    /**
     * The network's first-pass gate rate over [n] of the profile's own words — only those it may write:
     * a token the gate cannot judge never goes to the network (§8.2), so measuring it would mean nothing.
     */
    private suspend fun firstPass(
        gate: ReliabilityGate,
        vocabulary: List<String>,
        b: NeuralBackend,
        primed: Primed,
        n: Int,
    ): FirstPass {
        val pool = vocabulary.filter { b.canWrite(it) && gate.calibration.judgeable(it) }
        if (pool.isEmpty()) return FirstPass(0f, 0, emptyMap())
        val labels = List(n) { pool[it % pool.size] }
        val frame = NormalFrame(0f, 0f, 0f, 0f, GATE_X_HEIGHT_PT)
        var passed = 0
        var gated = 0
        val fails = HashMap<Char, IntArray>()
        val rng = Random(GATE_SEED)
        for (chunk in labels.chunked(GATE_BATCH)) {
            val written = b.write(chunk, primed, Variation.DEFAULT_NEATNESS, 1, rng)
            val items = chunk.indices.mapNotNull { i ->
                val ink = written[i]?.let { w -> frame.denormalise(w.trajectory) } ?: return@mapNotNull null
                ReliabilityGate.Item(chunk[i], ink, GATE_X_HEIGHT_PT, null)
            }
            val verdict = gate.check(items) as? ReliabilityGate.Verdict.Read ?: continue
            items.forEachIndexed { i, item ->
                gated++
                val ok = verdict.passed[i]
                if (ok) passed++
                for (c in item.label.toSet()) {
                    val f = fails.getOrPut(c) { IntArray(2) }
                    if (!ok) f[0]++
                    f[1]++
                }
            }
            // A word the runaway guard stopped is a first-pass failure too.
            val stopped = chunk.size - items.size
            gated += stopped
        }
        val rate = if (gated == 0) 0f else passed.toFloat() / gated
        return FirstPass(rate, gated, fails.mapValues { (_, f) -> CharFails(f[0], f[1]) })
    }

    // --- P1 -------------------------------------------------------------------------------------------

    /**
     * P1 — which of the user's words, if any, the network reads first. The candidates: no prime, the
     * drop's own (D15's page-first prime, as H4 shipped), and [Plans.primeCandidates] fixed sets drawn
     * from the training words. Each is scored by mean F over every word no fit saw; a prime is in force
     * only if it beats *no prime* by [DELTA] and keeps the gate rate.
     */
    private suspend fun choosePrime(work: Work, st: ProfileState): Pair<PrimeMode, List<String>> {
        val b = backend(acceptedStyle(st), acceptedAdapters(st), work.dynamics)
        val generic = work.generic()
        suspend fun score(primed: Primed): Float =
            meanF(FidelityProbe.measure(work.set, generic, work.writer(b, primed)).perWord.mapNotNull { it.f })
        val none = score(Primed.NONE)
        val page = b.prime(work.index, SourcePreference(), Random(PRIME_SEED))
        val pageScore = score(page)
        val pool = b.primeCandidates(work.index)
        val rng = Random(PRIME_SEED)
        var best: Triple<PrimeMode, List<GlyphUnit>, Float> = Triple(PrimeMode.PAGE, emptyList(), pageScore)
        repeat(plans.primeCandidates) {
            val set = pool.shuffled(rng).distinctBy { it.text }.take(NeuralBackend.PRIME_WORDS)
            if (set.isEmpty()) return@repeat
            val s = score(b.prime(set, work.index))
            if (s > best.third) best = Triple(PrimeMode.FIXED, set, s)
        }
        log("P1: none=$none page=$pageScore best=${best.first}:${best.third}")
        if (best.third < none + DELTA) return PrimeMode.NONE to emptyList()
        val primed = if (best.first == PrimeMode.FIXED) b.prime(best.second, work.index) else page
        val keepsGate = accepts(work, b, Primed.NONE, b, primed, "P1 ${best.first}")
        return if (keepsGate) best.first to best.second.map { it.source.sampleId } else PrimeMode.NONE to emptyList()
    }

    // --- The measurement --------------------------------------------------------------------------------

    /**
     * §8.3 and §8.4's evidence: F of both backends on the **held-out** words as the app writes them
     * (assembly from every training word, the network with every accepted rung), and the network's
     * first-pass gate rate over [Plans.gated] words with per-character failures.
     */
    private suspend fun measure(
        samples: List<WordSample>,
        labels: LabelBook,
        st: ProfileState,
        train: Int,
    ): QualityMeasure {
        val index = GlyphIndex.build(samples, labels)
        val set = FidelitySet.of(samples, labels, index)
        val dynamics = PenDynamics.fit(index.wholeWords().map { it.trajectory }, index.stats.penUpMs)
        val base = backend(null, null, dynamics)
        val personal = backend(acceptedStyle(st), acceptedAdapters(st), dynamics)
        val primed = primeFor(personal, index, st)
        var seed = SEED_MEASURE
        fun w(b: NeuralBackend, p: Primed) =
            WordWriter { ls -> b.write(ls, p, Variation.DEFAULT_NEATNESS, 1, Random(seed++)).map { it?.trajectory } }
        val generic = FidelityProbe.generic(set, w(base, Primed.NONE))
        val assembly = GlyphAssembly(index)
        val rng = Random(seed)
        val asm = FidelityProbe.measure(
            set,
            generic,
            WordWriter { ls ->
                val variation = Variation.forNeatness(Variation.DEFAULT_NEATNESS)
                ls.map { (assembly.word(it, variation, rng, HashSet()) as? AssembledWord.Written)?.trajectory }
            },
        )
        val net = FidelityProbe.measure(set, generic, w(personal, primed))
        val gate = ReliabilityGate(reader, st.gate ?: GateCalibration.UNCALIBRATED)
        val first = firstPass(gate, vocabularyOf(samples, labels), personal, primed, plans.gated)
        return QualityMeasure(
            epochMillis = now(),
            words = train,
            heldOut = set.words.size,
            fAssembly = asm.median,
            fNeural = net.median,
            assemblyCoverage = asm.coverage,
            neuralFirstPass = first.rate,
            gatedWords = first.gated,
            charFails = first.fails,
            rungs = st.personal.rungs,
        )
    }

    /** The prime P1 settled on, for [b] over [index]. */
    public fun primeFor(b: NeuralBackend, index: GlyphIndex, st: ProfileState): Primed = when (st.personal.prime) {
        PrimeMode.NONE -> Primed.NONE

        PrimeMode.PAGE -> b.prime(index, SourcePreference(), Random(PRIME_SEED))

        PrimeMode.FIXED -> {
            val byId = index.wholeWords().associateBy { it.source.sampleId }
            b.prime(st.personal.primeWords.mapNotNull { byId[it] }, index)
        }
    }

    // --- Artefacts ----------------------------------------------------------------------------------------

    public suspend fun acceptedStyle(st: ProfileState): FloatArray? =
        if (st.personal.style) Artefact.readStyle(readOrNull(HandwritingLayout.styleVector(key)), modelId) else null

    public suspend fun acceptedAdapters(st: ProfileState): Adapters? =
        if (st.personal.adapters) Artefact.readAdapters(readOrNull(HandwritingLayout.adapters(key)), modelId) else null

    private suspend fun trashArtefacts(why: String) {
        var moved = 0
        val paths =
            listOf(
                HandwritingLayout.styleVector(key),
                HandwritingLayout.adapters(key),
                HandwritingLayout.checkpoint(key),
            )
        for (path in paths) {
            if (fs.exists(path)) {
                toTrash(path, path.substringAfterLast('/'))
                moved++
            }
        }
        if (moved > 0) log("artefacts to the trash ($why): $moved")
    }

    private suspend fun toTrash(path: String, name: String) {
        if (!fs.exists(path)) return
        fs.createDirectories(HandwritingLayout.trashDir(key))
        fs.move(path, "${HandwritingLayout.trashDir(key)}/${now()}-$name")
    }

    private suspend fun readOrNull(path: String): ByteArray? =
        if (fs.exists(path)) runCatching { fs.readBytes(path) }.getOrNull() else null

    public companion object {
        /**
         * Writes a fit's checkpoint, atomically (rule 10): a kill mid-write leaves the previous one whole.
         * The one way a checkpoint is written — the crash-safety scenario uses it too.
         */
        public suspend fun saveCheckpoint(fs: FileSystem, key: ProfileKey, checkpoint: FitCheckpoint) {
            fs.createDirectories(HandwritingLayout.styleDir(key))
            fs.writeBytesAtomically(HandwritingLayout.checkpoint(key), FitCheckpoint.encode(checkpoint))
        }

        /** The checkpoint on disk, or null when there is none or it cannot be read — then a fit starts over. */
        public suspend fun loadCheckpoint(fs: FileSystem, key: ProfileKey): FitCheckpoint? {
            val path = HandwritingLayout.checkpoint(key)
            if (!fs.exists(path)) return null
            return runCatching { fs.readBytes(path) }.getOrNull()?.let(FitCheckpoint::decode)
        }

        /** §8.1's δ: the least rise in mean F an artefact must show to replace what is in force. */
        public const val DELTA: Float = 0.02f

        /**
         * How far the first-pass gate rate may fall and still count as *"not falling"*: the sampling error
         * of a rate near 0.9 over 120 words is about 0.03, and a stricter test rejects on noise.
         */
        public const val GATE_SLACK: Float = 0.03f

        /**
         * The blends a finished fit is tried at, strongest first: the fit itself, then half and a quarter of
         * the way from what is in force towards it. The first that §8.1's rule accepts is kept.
         */
        public val SHRINK: List<Float> = listOf(1f, 0.5f, 0.25f)
        public const val MIN_FIT_WORDS: Int = 20
        private const val MIN_GROWTH = 10
        private const val GROWTH_DIVISOR = 4
        private const val ACCEPT_GATED = 120
        private const val PRIME_SETS = 4
        private const val PRIME_SEED = 3L
        private const val GATE_SEED = 17L
        private const val SEED_MEASURE = 1_000L
        private const val GATE_BATCH = 24
        private const val GATE_X_HEIGHT_PT = 5.5f
        private const val PERCENT = 100f
    }
}
