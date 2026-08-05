import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Convention for :app — Compose UI, DI, WorkManager, and the platform
 * implementations of the interfaces :core:model declares (SPEC §2.2).
 *
 * Block comment, not KDoc: a .gradle.kts script has no declaration for KDoc to
 * attach to, and ktlint's standard:kdoc rule rejects it.
 */
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("inkstone.quality")
}

android {
    namespace = "dev.inkstone.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.inkstone"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-M0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // TODO(M10): turn on minify + shrinkResources together with the
            // signing config, and verify R8 against the reflection in
            // kotlinx-serialization (ROADMAP M10). Off for now so that `build`
            // — which assembles release — cannot fail on rules that no code
            // exercises yet.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
