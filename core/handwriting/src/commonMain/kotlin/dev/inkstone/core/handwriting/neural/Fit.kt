package dev.inkstone.core.handwriting.neural

import dev.inkstone.core.handwriting.ByteCursor
import dev.inkstone.core.handwriting.ByteSink
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** Which rung of §8.1's ladder a fit is for. */
// The codes are written to disk and fixed for ever, as `LabelSource`'s are.
@Suppress("MagicNumber")
public enum class Rung(internal val code: Int) {
    /** P2: the style vector, the whole network frozen. */
    STYLE(2),

    /** P3: adapters on the top layer and the output, everything else frozen. */
    ADAPTERS(3),
    ;

    internal companion object {
        fun of(code: Int): Rung? = entries.firstOrNull { it.code == code }
    }
}

/**
 * How a fit runs. Every number is a starting point, and docs/HANDWRITING-SYNTHESIS.md §20 reports what
 * they gave on the user's profile.
 *
 * @property prior an L2 pull back towards where the fit started (the model's own style, or adapters of
 *   zero), per point: the user's words are a few hundred, and a style vector or adapter left free to
 *   chase them lands where the network has never been and writes nonsense there.
 * @property checkpointEvery iterations between checkpoints — the most work a kill can lose.
 */
public data class FitPlan(
    val rung: Rung,
    val epochs: Int,
    val batch: Int,
    val learningRate: Float,
    val prior: Float,
    val rank: Int = 0,
    val checkpointEvery: Int = DEFAULT_CHECKPOINT_EVERY,
    val seed: Long = 1L,
    val clip: Float = DEFAULT_CLIP,
) {
    public companion object {
        public const val DEFAULT_CHECKPOINT_EVERY: Int = 8
        private const val DEFAULT_CLIP = 5f

        public val P2: FitPlan = FitPlan(Rung.STYLE, epochs = 12, batch = 8, learningRate = 0.02f, prior = 0.01f)
        public val P3: FitPlan =
            FitPlan(Rung.ADAPTERS, epochs = 12, batch = 8, learningRate = 0.003f, prior = 0.001f, rank = 4)
    }
}

/** One word to fit on: its sample's id (so a resume can find it again), its text and its ink. */
public class FitWord(public val sampleId: String, public val sequence: TrainingSequence)

/**
 * ⭐ Everything a fit needs to carry on exactly where it stopped — written atomically every
 * [FitPlan.checkpointEvery] iterations, so a kill loses at most that many, and a resumed fit ends **bit
 * for bit** where an unbroken one would: the batch order is a function of (seed, epoch), and the
 * optimiser's whole state is here.
 *
 * @property words the sample ids being fitted, in order — fixed for the fit's life. Words learned after
 *   it started wait for the next one rather than changing this one under its own feet.
 * @property baseModel the weights' identity: a checkpoint for another base model is thrown away.
 */
@Suppress("LongParameterList") // A record of the optimiser's whole state: every field is needed to resume.
public class FitCheckpoint(
    public val rung: Rung,
    public val baseModel: String,
    public val plan: FitPlan,
    public val words: List<String>,
    public val validation: List<String>,
    public val iteration: Int,
    public val adamStep: Int,
    public val params: List<FloatArray>,
    public val firstMoment: List<FloatArray>,
    public val secondMoment: List<FloatArray>,
    public val best: List<FloatArray>,
    public val bestValidation: Float,
    /** Mean validation loss per point after each epoch so far — the fit's own curve. */
    public val history: List<Float>,
    public val rank: Int,
) {
    public companion object {
        private val MAGIC = "IHWC".encodeToByteArray()
        private const val VERSION = 1

        public fun encode(c: FitCheckpoint): ByteArray {
            val sink = ByteSink()
            sink.bytes(MAGIC)
            sink.byte(VERSION)
            sink.byte(c.rung.code)
            sink.string(c.baseModel)
            sink.varint(c.plan.epochs.toLong())
            sink.varint(c.plan.batch.toLong())
            sink.float(c.plan.learningRate)
            sink.float(c.plan.prior)
            sink.varint(c.plan.rank.toLong())
            sink.varint(c.plan.checkpointEvery.toLong())
            sink.zigzag(c.plan.seed)
            sink.float(c.plan.clip)
            strings(sink, c.words)
            strings(sink, c.validation)
            sink.varint(c.iteration.toLong())
            sink.varint(c.adamStep.toLong())
            for (group in listOf(c.params, c.firstMoment, c.secondMoment, c.best)) {
                sink.varint(group.size.toLong())
                for (t in group) {
                    sink.varint(t.size.toLong())
                    t.forEach(sink::float)
                }
            }
            sink.float(c.bestValidation)
            sink.varint(c.history.size.toLong())
            c.history.forEach(sink::float)
            sink.varint(c.rank.toLong())
            return sink.toByteArray()
        }

        /** Null for anything this build did not write, or a file cut short — then the fit starts over. */
        @Suppress("ReturnCount", "CyclomaticComplexMethod")
        public fun decode(bytes: ByteArray): FitCheckpoint? {
            val r = ByteCursor(bytes)
            if (r.take(MAGIC.size)?.contentEquals(MAGIC) != true) return null
            if ((r.byte() ?: return null) > VERSION) return null
            val rung = Rung.of(r.byte() ?: return null) ?: return null
            val base = r.string() ?: return null
            val plan = FitPlan(
                rung = rung,
                epochs = r.varint()?.toInt() ?: return null,
                batch = r.varint()?.toInt() ?: return null,
                learningRate = r.float() ?: return null,
                prior = r.float() ?: return null,
                rank = r.varint()?.toInt() ?: return null,
                checkpointEvery = r.varint()?.toInt() ?: return null,
                seed = r.zigzag() ?: return null,
                clip = r.float() ?: return null,
            )
            val words = strings(r) ?: return null
            val validation = strings(r) ?: return null
            val iteration = r.varint()?.toInt() ?: return null
            val step = r.varint()?.toInt() ?: return null
            val groups = ArrayList<List<FloatArray>>()
            repeat(GROUPS) {
                val n = r.varint()?.toInt()?.takeIf { it in 0..MAX_TENSORS } ?: return null
                val group = ArrayList<FloatArray>()
                repeat(n) {
                    val size = r.varint()?.toInt()?.takeIf { it >= 0 && it * FLOAT_BYTES <= r.remaining } ?: return null
                    group += FloatArray(size) { r.float() ?: return null }
                }
                groups += group
            }
            val bestValidation = r.float() ?: return null
            val historySize = r.varint()?.toInt()?.takeIf { it >= 0 && it * FLOAT_BYTES <= r.remaining } ?: return null
            val history = List(historySize) { r.float() ?: return null }
            val rank = r.varint()?.toInt() ?: return null
            val g = groups.iterator()
            return FitCheckpoint(
                rung, base, plan, words, validation, iteration, step,
                g.next(), g.next(), g.next(), g.next(), bestValidation, history, rank,
            )
        }

        private fun strings(sink: ByteSink, values: List<String>) {
            sink.varint(values.size.toLong())
            values.forEach(sink::string)
        }

        private fun strings(r: ByteCursor): List<String>? {
            val n = r.varint()?.toInt()?.takeIf { it >= 0 && it <= r.remaining } ?: return null
            val out = ArrayList<String>(n)
            repeat(n) { out += r.string() ?: return null }
            return out
        }

        private const val GROUPS = 4
        private const val MAX_TENSORS = 16
        private const val FLOAT_BYTES = 4
    }
}

/**
 * ⭐ docs/HANDWRITING-SYNTHESIS.md §8.1's P2 and P3 — a fit of the style vector or of the adapters to the
 * user's own words, the base model frozen, by Adam over the hand-written gradient ([TrainableNetwork]).
 *
 * - **Resumable** ([FitCheckpoint]): [run] works in slices, returns when its time is up or when it is
 *   done, and checkpoints every [FitPlan.checkpointEvery] iterations and before it returns. A process
 *   kill between two checkpoints loses at most that many iterations; the resumed fit then repeats them
 *   and ends bit for bit where an unbroken one would.
 * - **Early-stopped on words it does not fit**: a share of the words is set aside, the validation loss is
 *   measured after every epoch, and the result is the best epoch's parameters, not the last's.
 * - **Pulled towards where it started** ([FitPlan.prior]).
 *
 * The result is a *candidate*: it replaces nothing until fidelity and the gate say it is better (§8.1's
 * acceptance, `Personaliser`).
 */
public class Fitter(private val net: TrainableNetwork, private val baseModel: String) {

    /** What a call to [run] ended with. */
    public sealed interface Progress {
        /** Out of time; [checkpoint] is on disk (via `save`) and the next call carries on from it. */
        public data class Paused(val checkpoint: FitCheckpoint) : Progress

        /** The fit is over; [checkpoint]'s `best` is the result. */
        public data class Done(val checkpoint: FitCheckpoint) : Progress
    }

    /** A fresh fit of [plan] over [words], [validation] set aside. Nothing is written until [run] saves. */
    public fun start(plan: FitPlan, words: List<FitWord>, validation: List<FitWord>): FitCheckpoint {
        val init = initial(plan)
        return FitCheckpoint(
            rung = plan.rung,
            baseModel = baseModel,
            plan = plan,
            words = words.map { it.sampleId },
            validation = validation.map { it.sampleId },
            iteration = 0,
            adamStep = 0,
            params = init.map { it.copyOf() },
            firstMoment = init.map { FloatArray(it.size) },
            secondMoment = init.map { FloatArray(it.size) },
            best = init.map { it.copyOf() },
            bestValidation = Float.POSITIVE_INFINITY,
            history = emptyList(),
            rank = plan.rank,
        )
    }

    /** Where the fit starts, and what [FitPlan.prior] pulls back towards. */
    private fun initial(plan: FitPlan): List<FloatArray> = when (plan.rung) {
        Rung.STYLE -> listOf(net.defaultStyle.copyOf())
        Rung.ADAPTERS -> Adapters.fresh(net.shape, plan.rank, Random(plan.seed)).tensors.map { it.copyOf() }
    }

    /**
     * Carries [from] on until it is done or [outOfTime] says to stop, calling [save] every
     * [FitPlan.checkpointEvery] iterations and once more before returning. [words] and [validation] are
     * looked up by the checkpoint's sample ids; a fit whose words have gone is the caller's to restart.
     *
     * @param style P3 is fitted on top of the style P2 settled on (or the model's own).
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    public suspend fun run(
        from: FitCheckpoint,
        words: Map<String, FitWord>,
        style: FloatArray?,
        outOfTime: () -> Boolean,
        save: suspend (FitCheckpoint) -> Unit,
    ): Progress {
        val plan = from.plan
        val fit = from.words.mapNotNull { words[it] }
        val held = from.validation.mapNotNull { words[it] }
        require(fit.size == from.words.size && held.size == from.validation.size) { "a fitted word has gone" }
        val start = initial(plan)
        val params = from.params.map { it.copyOf() }
        val m = from.firstMoment.map { it.copyOf() }
        val v = from.secondMoment.map { it.copyOf() }
        var best = from.best.map { it.copyOf() }
        var bestValidation = from.bestValidation
        val history = from.history.toMutableList()
        var step = from.adamStep
        val perEpoch = maxOf(1, ceil(fit.size / plan.batch.toFloat()).toInt())
        val total = plan.epochs * perEpoch
        val grads = gradientsFor(plan, params)
        var iteration = from.iteration

        fun snapshot() = FitCheckpoint(
            plan.rung, baseModel, plan, from.words, from.validation, iteration, step,
            params.map { it.copyOf() }, m.map { it.copyOf() }, v.map { it.copyOf() }, best.map { it.copyOf() },
            bestValidation, history.toList(), plan.rank,
        )

        while (iteration < total) {
            if (outOfTime()) {
                val paused = snapshot()
                save(paused)
                return Progress.Paused(paused)
            }
            val epoch = iteration / perEpoch
            val order = fit.indices.shuffled(Random(plan.seed * EPOCH_STRIDE + epoch))
            val batch = order.drop((iteration % perEpoch) * plan.batch).take(plan.batch)
            grads.clear()
            var points = 0
            for (i in batch) {
                net.loss(fit[i].sequence, styleOf(plan, params, style), adaptersOf(plan, params), grads)
                points += fit[i].sequence.steps
            }
            val tensors = grads.tensors
            val scale = 1f / maxOf(1, points)
            for (t in tensors.indices) {
                val g = tensors[t]
                for (j in g.indices) g[j] = g[j] * scale + 2f * plan.prior * (params[t][j] - start[t][j])
            }
            clip(tensors, plan.clip)
            step++
            adam(params, tensors, m, v, step, plan.learningRate)
            iteration++
            if (iteration % perEpoch == 0) {
                val loss = validationLoss(plan, params, style, held)
                history += loss
                // With nothing set aside there is nothing to stop early on: the last epoch is the result.
                if (held.isEmpty() || loss < bestValidation) {
                    bestValidation = loss
                    best = params.map { it.copyOf() }
                }
            }
            if (iteration % plan.checkpointEvery == 0 || iteration == total) save(snapshot())
        }
        return Progress.Done(snapshot())
    }

    /** Mean NLL per point over [held] — what early stopping compares. */
    public fun validationLoss(plan: FitPlan, params: List<FloatArray>, style: FloatArray?, held: List<FitWord>): Float {
        var loss = 0.0
        var points = 0
        for (w in held) {
            loss += net.loss(w.sequence, styleOf(plan, params, style), adaptersOf(plan, params), null)
            points += w.sequence.steps
        }
        return if (points == 0) 0f else (loss / points).toFloat()
    }

    private fun styleOf(plan: FitPlan, params: List<FloatArray>, style: FloatArray?): FloatArray = when (plan.rung) {
        Rung.STYLE -> params[0]
        Rung.ADAPTERS -> style ?: net.defaultStyle
    }

    private fun adaptersOf(plan: FitPlan, params: List<FloatArray>): Adapters? = when (plan.rung) {
        Rung.STYLE -> null
        Rung.ADAPTERS -> adaptersOf(plan.rank, params)
    }

    private fun gradientsFor(plan: FitPlan, params: List<FloatArray>): Gradients = when (plan.rung) {
        Rung.STYLE -> Gradients(FloatArray(params[0].size), null)
        Rung.ADAPTERS -> Gradients(null, adaptersOf(plan.rank, params.map { FloatArray(it.size) }))
    }

    private fun adaptersOf(rank: Int, t: List<FloatArray>): Adapters = Adapters.of(rank, t)

    private fun clip(tensors: List<FloatArray>, limit: Float) {
        var sq = 0.0
        for (t in tensors) for (x in t) sq += x.toDouble() * x
        val norm = sqrt(sq).toFloat()
        if (norm <= limit || norm == 0f) return
        val f = limit / norm
        for (t in tensors) for (j in t.indices) t[j] *= f
    }

    @Suppress("LongParameterList")
    private fun adam(
        params: List<FloatArray>,
        grads: List<FloatArray>,
        m: List<FloatArray>,
        v: List<FloatArray>,
        step: Int,
        lr: Float,
    ) {
        val c1 = 1.0 - BETA1.pow(step)
        val c2 = 1.0 - BETA2.pow(step)
        for (t in params.indices) {
            val p = params[t]
            val g = grads[t]
            for (j in p.indices) {
                m[t][j] = (BETA1 * m[t][j] + (1 - BETA1) * g[j]).toFloat()
                v[t][j] = (BETA2 * v[t][j] + (1 - BETA2) * g[j] * g[j]).toFloat()
                val mh = m[t][j] / c1
                val vh = v[t][j] / c2
                p[j] -= (lr * mh / (sqrt(vh) + ADAM_EPS)).toFloat()
            }
        }
    }

    public companion object {
        private const val BETA1 = 0.9
        private const val BETA2 = 0.999
        private const val ADAM_EPS = 1e-8
        private const val EPOCH_STRIDE = 1_000_003L

        /** The share of a fit's words set aside to stop it early — by a fingerprint, so it is stable. */
        public const val VALIDATION_EVERY: Int = 6
    }
}
