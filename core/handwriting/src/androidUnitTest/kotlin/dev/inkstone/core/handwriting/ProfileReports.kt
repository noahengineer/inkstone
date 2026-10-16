package dev.inkstone.core.handwriting

import dev.inkstone.core.handwriting.neural.KotlinSynthesisNetwork
import dev.inkstone.core.handwriting.neural.LicencePolicy
import dev.inkstone.core.handwriting.neural.ModelLoad
import dev.inkstone.core.handwriting.neural.WeightsFile
import java.io.File

/**
 * What the H5 reports read: a **pulled** profile and a model, both from outside the repo.
 *
 * ```
 * INKSTONE_HW_PROFILE=<dir holding words/<n>.hws and labels.json>   — a profile copied off a device
 * INKSTONE_HW_MODEL=<an .ihwm>     — default: app/src/main/assets/handwriting/base-v1.ihwm
 * INKSTONE_HW_OUT=<dir>            — where sheets and CSVs go
 * ```
 *
 * The profile is the user's own handwriting, so it never enters the repo, and a report without one
 * prints `SKIPPED` and asserts nothing: these are measuring instruments, not checks.
 */
internal object ProfileReports {
    val out: File = System.getenv("INKSTONE_HW_OUT")?.let(::File)
        ?: File(System.getProperty("java.io.tmpdir"), "inkstone-hw")

    class Loaded(val samples: List<WordSample>, val labels: LabelBook, val weights: WeightsFile)

    fun load(): Loaded? {
        val dir = System.getenv("INKSTONE_HW_PROFILE")?.let(::File)
        val model = System.getenv("INKSTONE_HW_MODEL")?.let(::File)
            ?: File("../../app/src/main/assets/handwriting/base-v1.ihwm")
        if (dir == null || !dir.isDirectory || !model.exists()) {
            println("SKIPPED — set INKSTONE_HW_PROFILE to a pulled profile directory.")
            return null
        }
        val samples = File(dir, "words").listFiles { f -> f.name.endsWith(".hws") }.orEmpty()
            .sortedBy { it.name }
            .flatMap { WordSampleCodec.decode(it.readBytes()).orEmpty() }
        val load = WeightsFile.read(model.readBytes(), LicencePolicy.DEVELOPMENT) as? ModelLoad.Loaded ?: return null
        out.mkdirs()
        return Loaded(samples, LabelBook(), load.weights)
    }

    fun network(weights: WeightsFile, style: FloatArray? = null): KotlinSynthesisNetwork =
        KotlinSynthesisNetwork(weights, style)
}
