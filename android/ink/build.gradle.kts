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

    // Kept behind the M1 spike (SPEC Appendix B #2): if androidx.ink already
    // predicts internally, adding this produces double prediction and visible
    // overshoot on direction changes. Declared now so M1 starts from a
    // resolvable state and the spike is a code change, not a dependency hunt.
    implementation(libs.input.motionprediction)

    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
}
