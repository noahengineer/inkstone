plugins {
    id("inkstone.android-library")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:storage"))

    // Rendering (SPEC §6.1). Whether this can also WRITE standard /Ink
    // annotations is the M4.0 spike — a real fork in the design, not a detail.
    implementation(libs.androidx.pdf.viewer.fragment)

    // NOTE: libs.pdfbox.android is deliberately NOT declared here. It is the
    // fallback writer if the M4.0 spike fails (SPEC §6.3) and it is dormant
    // upstream since Jan 2023. `./gradlew verifyCatalogCoordinates` proves the
    // pin resolves without committing the module to it.

    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
}
