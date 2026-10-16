package dev.inkstone.app.handwriting

import android.os.Build
import android.util.Log
import dev.inkstone.core.handwriting.neural.SpeedSpike
import java.io.File

/**
 * docs/HANDWRITING-SYNTHESIS.md §7.5 — runs phase 4.0's speed spike in **this** build, so the
 * number is the release build's and not a debuggable one's, and writes it to logcat under [TAG].
 *
 * Reached from the library's *Measure* menu, beside *Fill library*, for the same reason that one is
 * there: it is a measurement, behind a menu so it is not mistaken for a feature. It takes a few
 * minutes and must be run off the UI thread.
 */
object ModelSpeedSpike {

    const val TAG: String = "InkstoneH4"

    private val shapes = listOf(SpeedSpike.Shape(3, 256), SpeedSpike.Shape(2, 384), SpeedSpike.Shape(3, 400))
    private val batches = listOf(1, 4, 8)

    /** *"Electric charge is conserved in circuits"*: 40 characters, six words. */
    private val phrase = listOf(8, 6, 2, 9, 2, 8)
    private const val PRIME_CHARS = 16
    private const val BASELINE_BATCH = 4
    private val pointsPerChar = listOf(16, 25)

    fun run() {
        log("start ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} cores=${Runtime.getRuntime().availableProcessors()}")
        for (shape in shapes) {
            log("shape $shape params=${shape.parameters}")
            for (batch in batches) {
                val t = SpeedSpike.stepTime(shape, batch)
                log(
                    "step $shape batch=$batch kernel=${t.kernel} best=%.1fus median=%.1fus perWord=%.1fus cpu=%s"
                        .format(t.bestMicros, t.medianMicros, t.medianMicros / batch, cpu()),
                )
            }
        }
        val baseline = SpeedSpike.stepTime(shapes.first(), BASELINE_BATCH, SpeedSpike.Kernel.SIMPLE)
        log(
            "step ${baseline.shape} batch=$BASELINE_BATCH kernel=SIMPLE best=%.1fus median=%.1fus"
                .format(baseline.bestMicros, baseline.medianMicros),
        )
        for (shape in shapes) {
            for (ppc in pointsPerChar) {
                val p = SpeedSpike.phraseTime(shape, phrase, batch = 4, pointsPerChar = ppc, primeChars = PRIME_CHARS)
                log(
                    "phrase $shape batch=4 ppc=$ppc steps=${p.steps} total=%.0fms firstWord=%.0fms cpu=%s"
                        .format(p.totalMillis, p.firstWordMillis, cpu()),
                )
            }
        }
        log("done")
    }

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    /** The core this thread last ran on — field 39 of `/proc/thread-self/stat`, read after `comm`. */
    private fun cpu(): String = runCatching {
        val stat = File("/proc/thread-self/stat").readText()
        stat.substringAfterLast(')').trim().split(' ')[CPU_FIELD_AFTER_COMM]
    }.getOrDefault("?")

    /** `processor` is field 39; after `pid (comm)` the split starts at field 3. */
    private const val CPU_FIELD_AFTER_COMM = 36
}
