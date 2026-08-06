plugins {
    id("inkstone.android-library")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:storage"))

    // SPEC §0.1 #1 / §3.2 — androidx.ink STABLE 1.0.0, View-based, wrapped in
    // AndroidView. Not the 1.1.0-alpha Compose modules: an alpha in the most
    // important code path in the app is a bad trade, and Google's own Compose
    // setup snippet pins a combination that does not resolve.
    implementation(libs.ink.authoring)
    implementation(libs.ink.brush)
    implementation(libs.ink.geometry)
    implementation(libs.ink.rendering)
    implementation(libs.ink.strokes)
    implementation(libs.ink.storage)
    implementation(libs.ink.nativeloader)

    implementation(libs.graphics.core)

    // SPEC Appendix B #2 — spike RESOLVED in M1: androidx.ink 1.0.0 does NOT
    // predict internally. `ink-authoring-android`'s POM declares no dependency
    // on this artifact, the ink stack contains no predictor of its own, and
    // `addToStroke`/`enqueueInputs` take predicted input as an explicit
    // caller-supplied parameter. So this is KEPT, and it is the only predictor
    // in the pipeline — there is no double prediction to remove.
    // See docs/M1-INK.md.
    implementation(libs.input.motionprediction)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit)

    // Erase and render-order semantics are instrumented, not unit-tested:
    // androidx.ink's geometry is native, so a Stroke cannot be tessellated —
    // and therefore cannot be hit-tested — off a device.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
}
