plugins {
    `kotlin-dsl`
}

// Gradle 8.14 embeds a Kotlin that targets JVM 1.8 by default for kotlin-dsl.
// Align build-logic with the 17 the modules compile to, so the same JDK works
// everywhere and Gradle stops warning about mismatched jvmTargets.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // A precompiled script plugin can only `id("...")` a plugin whose marker
    // artifact is on this build's compile classpath.
    implementation(libs.gradlePlugin.android)
    implementation(libs.gradlePlugin.kotlin)
    implementation(libs.gradlePlugin.spotless)
    implementation(libs.gradlePlugin.detekt)
}
