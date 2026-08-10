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

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    // The class `testInstrumentationRunner` names, and it is NOT transitive from
    // androidx.test.ext:junit. Without it every instrumented test in this module dies with
    // ClassNotFoundException before one of them runs, and `check` cannot see it because
    // compiling an instrumented test is not dexing it (M5 found this in two other modules).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}
