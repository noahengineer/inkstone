plugins {
    id("inkstone.android-library")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:storage"))

    // NOTE: libs.androidx.pdf.viewer.fragment is deliberately NOT declared yet.
    //
    // Rendering is SPEC §6.1 and M4 owns it, but the pin has a constraint SPEC
    // §0.2 does not mention: every androidx.pdf 1.0.0-alpha19 artifact demands
    // a compile SDK with **extension level >= 19**, and the API 36 platform
    // ships extension level 17. So `compileSdk = 36` alone cannot consume it —
    // M4 will need either an ext-19 platform package or compileSdk 37.
    //
    // Declaring it now would fail :android:pdf:checkDebugAarMetadata for a
    // module that has no code in it until M4. The coordinate is still proven to
    // resolve by `./gradlew verifyCatalogCoordinates`. See docs/M0-VERIFICATION.md §5.7.

    // NOTE: libs.pdfbox.android is deliberately NOT declared here. It is the
    // fallback writer if the M4.0 spike fails (SPEC §6.3) and it is dormant
    // upstream since Jan 2023. `./gradlew verifyCatalogCoordinates` proves the
    // pin resolves without committing the module to it.

    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
}
