import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Convention for `:android:*` modules (SPEC §2.1) — platform implementations.
 *
 * These are allowed to touch the Android SDK; that is their entire job. What
 * they may NOT do is leak third-party types across a module boundary: PdfBox
 * stays behind a `PdfWriter` interface in `:android:pdf`, ML Kit stays inside
 * `:android:ocr`.
 *
 * Dependency direction is one-way: `:app` -> `:android:*` -> `:core:*`.
 * An `:android:*` module never depends on `:app`.
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
