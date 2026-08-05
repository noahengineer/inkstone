package dev.inkstone.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Guardrail 1 (SPEC §2.3, CLAUDE.md rule 2).
 *
 * Fails the build if platform-shared code in a `:core:*` module imports
 * `android.*` or `androidx.*`. `androidx.annotation.*` is exempt — it is a
 * pure-annotation artifact with no runtime dependency on the Android platform.
 *
 * ## Why Android source sets are not scanned
 *
 * A KMP module's `androidMain` source set is Android-by-definition: it is where
 * `actual` implementations live, and they legitimately need platform types.
 * `:core:database`'s `androidMain` cannot construct an `AndroidSqliteDriver`
 * without `android.content.Context`. Scanning it would make the roadmap's own
 * M0 scope ("SQLDelight in :core:database") impossible.
 *
 * The rule that actually protects the iPad path is Guardrail 2 — the
 * `iosSimulatorArm64` link — because a JVM-only dependency such as OkHttp or
 * dav4jvm passes an import scan without complaint and only fails at link time.
 * This task is the cheap fast-feedback half of the pair, not the load-bearing
 * half.
 */
@CacheableTask
abstract class CorePlatformFreeCheckTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    init {
        group = "verification"
        description = "Fails if shared :core code imports android.* or androidx.* (SPEC §2.3)."
    }

    @TaskAction
    fun check() {
        val violations = mutableListOf<String>()

        // `sources` holds source *directories*. A FileCollection never expands a
        // directory into its contents — that is what asFileTree is for. Filtering
        // the collection directly would test isFile on directories, match nothing,
        // and pass vacuously no matter what the code contains.
        val kotlinFiles = sources.asFileTree.files.filter { it.isFile && it.name.endsWith(".kt") }

        kotlinFiles.forEach { file ->
            file.readLines().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                val match = IMPORT_REGEX.matchEntire(line) ?: return@forEachIndexed
                val imported = match.groupValues[1]
                if (ALLOWED_PREFIXES.none { imported.startsWith(it) }) {
                    violations += "${file.absolutePath}:${index + 1}: import $imported"
                }
            }
        }

        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()

        // A guardrail that has quietly stopped guarding is worse than none, and
        // "scanned nothing" is indistinguishable from "found nothing" in the
        // report. Every :core:* module has at least one hand-written shared
        // Kotlin file, so zero is always a wiring bug.
        if (kotlinFiles.isEmpty()) {
            reportFile.writeText("FAILED — scanned 0 files.\n")
            throw GradleException(
                ":core guardrail scanned 0 shared Kotlin files, so it proved nothing. " +
                    "Check the source-set wiring in inkstone.kmp-core.gradle.kts.",
            )
        }

        if (violations.isEmpty()) {
            reportFile.writeText("OK — ${kotlinFiles.size} shared Kotlin file(s) scanned, no platform imports.\n")
            logger.info("Core platform-free check passed ({} files).", kotlinFiles.size)
            return
        }

        reportFile.writeText(violations.joinToString(separator = "\n", postfix = "\n"))
        throw GradleException(
            buildString {
                appendLine()
                appendLine(":core guardrail FAILED — platform imports in shared code.")
                appendLine()
                appendLine("SPEC §2.3 / CLAUDE.md rule 2: :core:* must stay platform-free so the")
                appendLine("iOS target keeps linking. If core needs a platform capability, declare")
                appendLine("an interface in :core:model (SPEC §2.2) and implement it in :app.")
                appendLine()
                violations.forEach { appendLine("  $it") }
                appendLine()
                append("androidx.annotation.* is the only permitted exception.")
            },
        )
    }

    private companion object {
        val IMPORT_REGEX = Regex("""^import\s+((?:android|androidx)\.[A-Za-z0-9_.`*]+)\s*(?://.*)?$""")

        // androidx.annotation is annotations only — no Android runtime linkage.
        val ALLOWED_PREFIXES = listOf("androidx.annotation.")
    }
}
