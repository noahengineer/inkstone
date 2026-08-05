import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlinGradle {
        target("*.gradle.kts", "build-logic/*.gradle.kts", "build-logic/src/main/kotlin/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}

val coreModules = listOf(
    ":core:model",
    ":core:storage",
    ":core:database",
    ":core:sync",
    ":core:search",
)

// ---------------------------------------------------------------------------
// Guardrail 1 — :core:* stays platform-free (SPEC §2.3).
// Each module wires its own check into `check`; this is the aggregate you run
// on its own.
// ---------------------------------------------------------------------------
tasks.register("checkCoreIsPlatformFree") {
    group = "verification"
    description = "Fails if any :core:* module imports android.*/androidx.* in shared code."
    dependsOn(coreModules.map { "$it:checkCoreIsPlatformFree" })
}

// ---------------------------------------------------------------------------
// Guardrail 2 — every :core:* module still links for iOS (SPEC §2.3).
//
// This is the guardrail that matters. OkHttp and dav4jvm are JVM-only, so they
// pass the import check above and only fail here. REQUIRES macOS: Kotlin/Native
// cannot compile Apple targets on Windows or Linux, so CI runs this on a macOS
// runner (see .github/workflows/ci.yml).
// ---------------------------------------------------------------------------
tasks.register("linkIosCore") {
    group = "verification"
    description = "Links every :core:* module for iosSimulatorArm64. macOS only."
    dependsOn(coreModules.map { "$it:linkDebugFrameworkIosSimulatorArm64" })
}

// ---------------------------------------------------------------------------
// SPEC §0.2 — "if a coordinate doesn't resolve from a clean Gradle cache,
// report the actual available version and stop." Resolving only what the
// modules happen to use would leave the not-yet-wired pins (PdfBox, ktor-mock,
// the ink artifacts M1 needs) unverified until the milestone that needs them.
//
// Metadata-only (`@pom`, non-transitive) so this works from the root project,
// which has no Android or Kotlin plugin and therefore cannot match AAR variants.
// ---------------------------------------------------------------------------
val catalogProbe: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    description = "Metadata-only probe proving every version-catalog coordinate resolves."
}

val catalogCoordinates: List<String> = run {
    val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
    val libraries = catalog.libraryAliases.mapNotNull { alias ->
        val dependency = catalog.findLibrary(alias).get().get()
        val version = dependency.versionConstraint.requiredVersion
        // Compose artifacts carry no version — the BOM supplies it. Verifying
        // the BOM itself is what proves that line.
        if (version.isEmpty()) null else "${dependency.module.group}:${dependency.module.name}:$version"
    }
    val pluginMarkers = catalog.pluginAliases.map { alias ->
        val plugin = catalog.findPlugin(alias).get().get()
        val id = plugin.pluginId
        "$id:$id.gradle.plugin:${plugin.version.requiredVersion}"
    }
    (libraries + pluginMarkers).distinct().sorted()
}

dependencies {
    catalogCoordinates.forEach { coordinate ->
        catalogProbe("$coordinate@pom")
    }
}

tasks.register("verifyCatalogCoordinates") {
    group = "verification"
    description = "Resolves every version-catalog coordinate from the configured repositories."
    val probe = catalogProbe
    val expected = catalogCoordinates
    doLast {
        val resolved = probe.resolve()
        logger.lifecycle("Verified ${expected.size} catalog coordinate(s); ${resolved.size} metadata file(s) fetched.")
        expected.forEach { logger.info("  ok  $it") }
    }
}
