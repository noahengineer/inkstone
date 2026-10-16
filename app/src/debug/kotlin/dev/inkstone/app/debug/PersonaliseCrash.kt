package dev.inkstone.app.debug

import android.content.Context
import dev.inkstone.app.handwriting.AssetModelSource
import dev.inkstone.core.handwriting.PenDynamics
import dev.inkstone.core.handwriting.ProfileKey
import dev.inkstone.core.handwriting.neural.FitPlan
import dev.inkstone.core.handwriting.neural.FitWord
import dev.inkstone.core.handwriting.neural.KotlinSynthesisNetwork
import dev.inkstone.core.handwriting.neural.LicencePolicy
import dev.inkstone.core.handwriting.neural.ModelLoad
import dev.inkstone.core.handwriting.neural.Primed
import dev.inkstone.core.handwriting.neural.Rung
import dev.inkstone.core.handwriting.neural.TrainableNetwork
import dev.inkstone.core.handwriting.neural.WeightsFile
import kotlin.random.Random

/**
 * docs/HANDWRITING-SYNTHESIS.md §8.1 and CLAUDE.md's crash-safety requirement — the personalisation fit
 * the crash-point process runs and the test resumes. Shared, in `src/debug`, for [CrashPoint]'s reason:
 * both processes must build **exactly** the same fit, and only this source set is seen by both.
 *
 * The shipped model and four words it writes itself, at a fixed seed — the user's profile is never
 * touched — fitted by the production [dev.inkstone.core.handwriting.neural.Fitter] with the production
 * checkpoint writer (`Personaliser.saveCheckpoint`).
 */
object PersonaliseCrash {
    /** A profile of its own, inside the scenario's scratch directory. */
    val KEY: ProfileKey = ProfileKey("crashpoint")

    /** Eight iterations (4 epochs × 2 batches), a checkpoint every second. */
    val PLAN: FitPlan =
        FitPlan(Rung.STYLE, epochs = 4, batch = 2, learningRate = 0.02f, prior = 0.01f, checkpointEvery = 2)

    /**
     * The victim dies when asked for the 6th time whether it is out of time — as iteration 6 is about
     * to start, five done, the last checkpoint written after iteration 4.
     */
    const val KILL_AT_CALL: Int = 6

    private val TEXTS = listOf("the", "cat", "sat", "on")

    suspend fun weights(context: Context): WeightsFile {
        val bytes = AssetModelSource(context.assets).bytes()
        return (WeightsFile.read(bytes, LicencePolicy.DEVELOPMENT) as ModelLoad.Loaded).weights
    }

    /** Words the model wrote itself, unprimed, at a fixed seed: the same in every process. */
    fun words(weights: WeightsFile, trainer: TrainableNetwork): List<FitWord> {
        val written = KotlinSynthesisNetwork(weights).write(TEXTS, Primed.NONE, NEAT_BIAS, Random(SEED))
        return TEXTS.indices.mapNotNull { i ->
            val w = written[i] ?: return@mapNotNull null
            val ink = PenDynamics.Unmeasured.apply(w.x, w.y, w.strokeEnds) ?: return@mapNotNull null
            trainer.sequence(TEXTS[i], ink)?.let { FitWord("crash-$i", it) }
        }
    }

    private const val NEAT_BIAS = 2f
    private const val SEED = 9
}
