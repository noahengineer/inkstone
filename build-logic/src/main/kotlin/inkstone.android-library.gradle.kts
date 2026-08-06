import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Convention for :android:* modules (SPEC §2.1) — platform implementations.
 *
 * These are allowed to touch the Android SDK; that is their entire job. What
 * they may NOT do is leak third-party types across a module boundary: PdfBox
 * stays behind a PdfWriter interface in :android:pdf, ML Kit stays inside
 * :android:ocr.
 *
 * Dependency direction is one-way: :app -> :android:* -> :core:*.
 * An :android:* module never depends on :app.
 */
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("inkstone.quality")
}

android {
    namespace = "dev.inkstone" + project.path.replace(":", ".")
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/*
 * Instrumented tests must at least COMPILE as part of `check`.
 *
 * They cannot be *run* without a device, so `build` has never executed them — and that is
 * fine and expected. What is not fine is what happened in M3: a schema change altered a
 * `LibraryIndex` signature, `./gradlew build` stayed green, and the instrumented test that
 * called it had been broken for two commits. A test that does not compile is worse than no
 * test, because it looks like coverage.
 *
 * Compiling is cheap and catches exactly that class of drift. Running still needs
 * `connectedDebugAndroidTest` and a tablet.
 */
tasks.named("check") {
    // A live TaskCollection, so it resolves when the execution graph is built rather than
    // during configuration — the KMP and non-KMP variants of this task are named differently
    // and neither exists yet at the point this runs.
    dependsOn(tasks.matching { it.name.startsWith("compileDebugAndroidTestKotlin") })
}
