import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import org.gradle.api.artifacts.VersionCatalogsExtension

/*
 * Formatting (Spotless + ktlint) and static analysis (detekt), applied to every
 * module so the rules are uniform.
 */
plugins {
    id("com.diffplug.spotless")
    id("io.gitlab.arturbosch.detekt")
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val ktlintVersion = versionCatalog.findVersion("ktlint").get().requiredVersion

spotless {
    kotlin {
        target("src/**/*.kt")
        targetExclude("**/build/**")
        ktlint(ktlintVersion)
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(ktlintVersion)
    }
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline.xml")
    // KMP modules have no src/main/kotlin; point detekt at the whole tree.
    source.setFrom(files("src"))
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        sarif.required.set(true)
        xml.required.set(false)
        md.required.set(false)
        txt.required.set(false)
    }
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
    jvmTarget = "17"
}
