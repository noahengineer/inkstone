plugins {
    id("inkstone.android-library")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:search"))

    // ML Kit stays inside this module (SPEC §2.1). It is also a single point of
    // dependency with no viable alternative — ML Kit GenAI does not do
    // handwriting, and image OCR throws away the temporal signal that makes
    // recognition work — so M7 must keep the app fully usable when the language
    // model is absent, degrading search to titles only.
    implementation(libs.mlkit.digital.ink)

    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
}
