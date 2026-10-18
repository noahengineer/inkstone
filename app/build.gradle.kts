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

// docs/CLOUD-PROVIDERS.md §5.1. The Entra app registration's client id. **Not a secret** — a public
// client has no secret, which is the whole point of PKCE — so it may live in a Gradle property
// (`~/.gradle/gradle.properties` or `-Pinkstone.msClientId=…`). Blank means "OneDrive is not configured
// in this build", and the Sync screen then says so instead of offering a button that cannot work.
val msClientId = providers.gradleProperty("inkstone.msClientId").orElse("").get().trim()

// DEBUG BUILDS ONLY. A host:port that stands in for login.microsoftonline.com and graph.microsoft.com,
// so the sign-in can be driven on an emulator against `GraphStandIn` (docs/CLOUD-PROVIDERS.md §P4).
// The release block below pins it to the empty string, so the property cannot reach a release APK.
val msStandIn = providers.gradleProperty("inkstone.msStandIn").orElse("").get().trim()

android {
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("String", "MS_CLIENT_ID", "\"$msClientId\"")
    }
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "MS_STAND_IN", "\"$msStandIn\"")
        }
        getByName("release") {
            buildConfigField("String", "MS_STAND_IN", "\"\"")
        }
    }
    testOptions {
        // M6. SPEC §13.2's merge matrix lives in this module's unit tests, because :app is the
        // only source set that sees :core:sync, :core:storage and :android:pdf at once — the
        // three halves one pull passes through. Getting there needs Android's stubs to return
        // defaults rather than throwing: OkHttp's platform detection reads
        // `android.os.Build.VERSION.SDK_INT` the first time a client is built, so without this
        // every request the real `WebDavClient` makes dies in `Platform.get()` before a socket
        // is opened. :android:pdf set the same flag in M4 for PdfBox's one `android.util.Log`
        // reference, and that is almost certainly why its live spike is the only one that has
        // ever run.
        unitTests.isReturnDefaultValues = true
    }
}

// ⚠️ Which test task is running, so the matrix can refuse to drive a *live* server twice.
// `check` runs testDebugUnitTest AND testReleaseUnitTest, and M5's live PDF spike already
// round-trips twice for it. Doubling that across 21 rows is bad enough; two runs racing on the
// same remote paths would manufacture a conflict in the milestone whose subject is conflicts.
// The in-process server has no such problem — it is per-JVM — so only the live half is gated.
tasks.withType<Test>().configureEach {
    systemProperty("inkstone.test.variant", name)
}

dependencies {
    // SPEC §2.1 — :app is the only module allowed to depend on everything.
    implementation(project(":core:model"))
    implementation(project(":core:storage"))
    implementation(project(":core:database"))
    implementation(project(":core:sync"))
    implementation(project(":core:search"))
    implementation(project(":core:calc"))
    implementation(project(":core:handwriting"))
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
    // `LifecycleStartEffect`, for re-seeding the toolbar when an editor comes back to the front.
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.work.runtime.ktx)
    // SPEC §7.1 — the login flow opens in a Custom Tab.
    implementation(libs.androidx.browser)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    // `kotlin.test` over bare JUnit 4, so an assertion in the merge matrix reads the same as
    // the one in `:core:storage`'s `NoteMergeTest` that backs it up. The two are the same
    // argument in two modules and diverging assertion vocabularies made that hard to see.
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    // M6, TEST ONLY. SPEC §13.2's matrix runs the *real* SQL stores, because two of the claims
    // it makes are claims about SQLite: `upsertBinding` carrying `lastSeenRemoteAt` forward
    // across an INSERT OR REPLACE, and `notSeenSince` being the exact complement of
    // `stampBindingSeen`. A fake would hide both — and getting them wrong is a hundred notes in
    // the trash. :core:database refuses this driver for *its* tests on the grounds that testing
    // SQLite against a different SQLite build proves nothing, and that is right; this suite
    // tests sync semantics and needs a database, not a SQLite.
    testImplementation(libs.sqldelight.driver.sqlite)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    // ⚠️ M6. The class `testInstrumentationRunner` names (set by `inkstone.android-application`).
    // It is NOT transitive from androidx.test.ext:junit, and without it every instrumented test
    // in this module dies with ClassNotFoundException before one of them runs. These three lines
    // are the whole reason :core:database and :android:ink each sat with zero *working*
    // instrumented tests for two milestones: `check` compiles an androidTest source set but does
    // not dex or run it, so a broken classpath looks exactly like coverage.
    androidTestImplementation(libs.androidx.test.runner)
    // The assertion vocabulary the rest of the repo uses. `inkstone.android-application` puts
    // kotlin("test") on unitTests only.
    androidTestImplementation(kotlin("test"))
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
