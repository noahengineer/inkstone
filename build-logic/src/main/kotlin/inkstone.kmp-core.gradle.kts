import dev.inkstone.gradle.CorePlatformFreeCheckTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/*
 * Convention for every :core:* module (SPEC §2.1).
 *
 * Two guardrails, applied here rather than per-module so they cannot be
 * forgotten:
 *
 *  1. CorePlatformFreeCheckTask — no android.* / androidx.* in shared code.
 *  2. Real iosArm64 + iosSimulatorArm64 targets, each producing a framework
 *     binary. This is the one that matters (SPEC §2.3): OkHttp and dav4jvm are
 *     JVM-only and would sail past the import check while silently killing the
 *     iPad path.
 */
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("inkstone.quality")
}

val moduleFrameworkName: String =
    "Inkstone" +
        project.path
            .removePrefix(":")
            .split(":", "-", "_")
            .joinToString("") { part -> part.replaceFirstChar(Char::uppercaseChar) }

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Both iOS targets from M0 — ROADMAP M11 expects them to have been in CI
    // since the beginning rather than bolted on at the end.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = moduleFrameworkName
            isStatic = true
        }
    }

    sourceSets {
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

android {
    namespace = "dev.inkstone" + project.path.replace(":", ".")
    compileSdk = 36

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val handWrittenSourceRoot = layout.projectDirectory.dir("src").asFile.absolutePath

val sharedKotlinSourceDirs = provider {
    extensions.getByType<KotlinMultiplatformExtension>()
        .sourceSets
        // androidMain is Android-by-definition: it is where `actual`
        // implementations live and they legitimately need platform types.
        // See the KDoc on CorePlatformFreeCheckTask.
        .filterNot { it.name.startsWith("android") }
        .flatMap { it.kotlin.srcDirs }
        // Only hand-written code. Generated dirs (SQLDelight puts its output on
        // commonMain) would otherwise make this task consume another task's
        // output without declaring the dependency, which Gradle rejects — and
        // linting generated code was never the point.
        .filter { it.absolutePath.startsWith(handWrittenSourceRoot) }
}

val corePlatformFreeCheck =
    tasks.register<CorePlatformFreeCheckTask>("checkCoreIsPlatformFree") {
        sources.from(sharedKotlinSourceDirs)
        report.set(layout.buildDirectory.file("reports/inkstone/core-platform-free.txt"))
    }

tasks.named("check") {
    dependsOn(corePlatformFreeCheck)
}
