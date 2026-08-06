plugins {
    id("inkstone.android-application")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // M5. `KeystoreCredentialStore` seals a four-field record into an AES-GCM envelope
    // (SPEC §14); hand-rolling the escaping for it would be the one place in this app
    // where a stray quote in a login name silently corrupts a credential.
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // SPEC §2.1 — :app is the only module allowed to depend on everything.
    implementation(project(":core:model"))
    implementation(project(":core:storage"))
    implementation(project(":core:database"))
    implementation(project(":core:sync"))
    implementation(project(":core:search"))
    implementation(project(":android:ink"))
    implementation(project(":android:pdf"))
    implementation(project(":android:ocr"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.work.runtime.ktx)
    // SPEC §7.1 — the login flow opens in a Custom Tab.
    implementation(libs.androidx.browser)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
}
