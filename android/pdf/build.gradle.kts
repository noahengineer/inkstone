plugins {
    id("inkstone.android-library")
}

android {
    testOptions {
        // PdfBox-Android is a repackaged pure-Java PDFBox. Across the whole
        // structural half — PDDocument, COSWriter, PDPage, PDAnnotation — it
        // references exactly one Android class: android.util.Log. Stubbing it to
        // return defaults is what lets M4's real acceptance tests (/Rotate 90,
        // non-zero CropBox, byte-identical original, Recovered mode) run as plain
        // JVM unit tests over real PDF bytes instead of instrumented ones.
        //
        // See docs/M4-PDF.md §4. This does NOT extend to rendering: rasterising a
        // page needs android.graphics, so PdfPageRasterizer is instrumented.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:storage"))

    // SPEC §6.3 — the M4.0 spike's answer. androidx.pdf 1.0.0-alpha19 does NOT
    // write standard /Ink: androidx.pdf.ink turns every stroke into a /Stamp
    // annotation holding a filled path object, because the framework type it
    // converts into (android.graphics.pdf.component.PdfAnnotationType) has only
    // UNKNOWN/FREETEXT/HIGHLIGHT/STAMP — there is no ink annotation in the
    // platform API at all. It also cannot set /NM (annotation identity there is
    // the integer index on the page), and its save is a full PDFium
    // re-serialisation via PdfRenderer.write, which forecloses SPEC §5.2's
    // incremental update outright. So: PdfBox-Android behind a PdfWriter
    // interface, and no androidx.pdf dependency anywhere. See docs/M4-PDF.md §1.
    //
    // Dormant upstream since Jan 2023 (SPEC §6.3). It ports PDFBox 2.0.x, which
    // is the line that has saveIncremental — the feature this milestone turns on.
    // Two corrections to SPEC §6.3, both found here and recorded in docs/M4-PDF.md:
    // there is no PDAnnotationInk class in 2.0.x (/Ink is written at the COS
    // level, which works fine), and xmpbox is not bundled (the XMP marker goes in
    // as a raw /Metadata stream, which is what we want anyway).
    implementation(libs.pdfbox.android)

    // NOTE: libs.androidx.pdf.viewer.fragment stays undeclared, and after the M4.0
    // spike that is now a decision rather than a deferral. Every androidx.pdf
    // 1.0.0-alpha19 artifact demands compile SDK extension level >= 19 and the API
    // 36 platform ships 17, so consuming it means either the android-36-ext19
    // platform package or compileSdk 37 (SPEC §0.2 left the choice to M4). Taking
    // a toolchain change to consume a library we would use for one bitmap call is
    // the bad trade: SPEC §6.1's own fallback — framework PdfRenderer (35+) /
    // PdfRendererPreV (30-34) — is in plain android-36, covers minSdk 30, and does
    // not bring a viewer fragment that would fight InkSurfaceView for gestures.
    // compileSdk stays 36 and the AGP 8.13.0 pin is untouched.
    // `verifyCatalogCoordinates` still proves the coordinate resolves.

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    // `kotlin.test` over JUnit 4, so an assertion reads the same here as it does in
    // :core:*'s commonTest — the PDF acceptance tests and the format tests they back
    // up are the same argument in two modules.
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
}
