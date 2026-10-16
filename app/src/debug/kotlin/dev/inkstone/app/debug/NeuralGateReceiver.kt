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
import dev.inkstone.core.handwriting.NeuralBackend
import dev.inkstone.core.handwriting.NormalFrame
import dev.inkstone.core.handwriting.PenDynamics
import dev.inkstone.core.handwriting.ReliabilityGate
import dev.inkstone.core.handwriting.SourcePreference
import dev.inkstone.core.handwriting.neural.KotlinSynthesisNetwork
import dev.inkstone.core.handwriting.neural.LicencePolicy
import dev.inkstone.core.handwriting.neural.ModelLoad
import dev.inkstone.core.handwriting.neural.Primed
import dev.inkstone.core.handwriting.neural.WeightsFile
import dev.inkstone.core.model.InkReader
import java.io.File
import kotlin.random.Random

/**
 * **Debug builds only.** docs/HANDWRITING-SYNTHESIS.md H4 — how often the neural backend's words pass
 * the real gate (ML Kit) on this device's profile, unprimed and primed with the user's own words.
 *
 * ```
 * adb shell am broadcast -n dev.inkstone/.app.debug.NeuralGateReceiver --es words "bob jab quiz" --ei n 5
 *   --ef neatness 0.5 [--es model e003.ihwm]
 * adb logcat -s NeuralGate
 * ```
 *
 * The words are the experimenter's, not the user's, so they are logged; the user's own words never are
 * (§9). Each word is written [n] times per condition at the first rung's bias, one gate call per sample
 * — first-pass rates, which is what §8.4's switching rule will one day be about.
 *
 * `model` names a candidate `.ihwm` in `files/debug/` (pushed with `run-as … cp`), so each training
 * epoch can be measured without rebuilding the APK; without it, the shipped asset is measured.
 *
 * Nothing here touches a note.
 */
class NeuralGateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val args = listOf(
            intent.getStringExtra(EXTRA_WORDS) ?: DEFAULT_WORDS,
            intent.getIntExtra(EXTRA_N, DEFAULT_N).toString(),
            intent.getFloatExtra(EXTRA_NEATNESS, DEFAULT_NEATNESS).toString(),
            intent.getStringExtra(EXTRA_MODEL).orEmpty(),
        ).joinToString("|")
        DebugTaskWorker.enqueue(context, DebugTaskWorker.TASK_NEURAL_GATE, args)
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Deps {
        fun writer(): HandwritingWriter

        fun reader(): InkReader
    }

    internal companion object {
        private const val TAG = "NeuralGate"
        private const val EXTRA_WORDS = "words"
        private const val EXTRA_N = "n"
        private const val EXTRA_NEATNESS = "neatness"
        private const val EXTRA_MODEL = "model"
        private const val DEFAULT_WORDS = "bob jab cab quiz buzz kelvin zebra"
        private const val DEFAULT_N = 5
        private const val DEFAULT_NEATNESS = 0.5f
        private const val X_HEIGHT_PT = 5.5f
        private const val DIR = "debug"
        private const val ARG_MODEL = 3
        private const val NANOS_PER_MS = 1_000_000
        private const val SEED_STRIDE = 31L

        suspend fun run(context: Context, argument: String?) {
            val parts = argument.orEmpty().split('|')
            val words = parts.getOrNull(0)?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
            val n = parts.getOrNull(1)?.toIntOrNull() ?: DEFAULT_N
            val neatness = parts.getOrNull(2)?.toFloatOrNull() ?: DEFAULT_NEATNESS
            val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
            val profile = deps.writer().profile()
            val override = parts.getOrNull(ARG_MODEL)?.takeIf { it.isNotBlank() }
                ?.let { File(File(context.applicationContext.filesDir, DIR), it) }
            val backend = if (override != null) {
                // A candidate checkpoint pushed for this measurement only; the app itself reads the asset.
                val bytes = override.takeIf { it.exists() }?.readBytes()
                val load = WeightsFile.read(bytes, LicencePolicy.DEVELOPMENT)
                (load as? ModelLoad.Loaded)?.let {
                    val words = profile.index.wholeWords().map { u -> u.trajectory }
                    val dynamics = PenDynamics.fit(words, profile.index.stats.penUpMs)
                    NeuralBackend(KotlinSynthesisNetwork(it.weights), dynamics)
                }
            } else {
                profile.neural
            }
            if (backend == null) {
                Log.i(TAG, "no neural backend: no model, or it was refused (${override?.name ?: "asset"})")
                return
            }
            val gate = ReliabilityGate(deps.reader())
            val frame = NormalFrame(0f, 0f, 0f, 0f, X_HEIGHT_PT)
            val started = System.nanoTime()
            val primed = backend.prime(profile.index, SourcePreference(), Random(1))
            val primeMs = (System.nanoTime() - started) / NANOS_PER_MS
            Log.i(TAG, "model ${backend.modelId} primed=${primed.isPrimed} ms=$primeMs")
            for ((label, prime) in listOf("unprimed" to Primed.NONE, "primed" to primed)) {
                var passed = 0
                var finished = 0
                var total = 0
                for (word in words) {
                    var wordPassed = 0
                    repeat(n) { k ->
                        total++
                        val t0 = System.nanoTime()
                        val seed = k * SEED_STRIDE + word.hashCode()
                        val written = backend.write(listOf(word), prime, neatness, 1, Random(seed))
                            .single() ?: return@repeat
                        val genMs = (System.nanoTime() - t0) / NANOS_PER_MS
                        finished++
                        val ink = frame.denormalise(written.trajectory) ?: return@repeat
                        val verdict = gate.check(listOf(ReliabilityGate.Item(word, ink, X_HEIGHT_PT, null)))
                        val ok = (verdict as? ReliabilityGate.Verdict.Read)?.passed?.single() == true
                        val read = (verdict as? ReliabilityGate.Verdict.Read)?.readings?.single()?.take(2)
                        if (ok) {
                            passed++
                            wordPassed++
                        }
                        Log.i(TAG, "$label '$word' #$k steps=${written.steps} genMs=$genMs pass=$ok read=$read")
                    }
                    Log.i(TAG, "$label '$word': $wordPassed/$n passed")
                }
                Log.i(TAG, "$label total: $passed/$total passed, $finished/$total finished, neatness=$neatness")
            }
        }
    }
}
