plugins {
    id("inkstone.kmp-core")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))

            // SPEC §0.1 #4 — Ktor, because it is KMP. NOT OkHttp and NOT
            // dav4jvm: both are JVM-only, both would pass the import guardrail,
            // and both would silently kill the iPad path. The PROPFIND XML that
            // dav4jvm would have provided is hand-rolled from SPEC §7.3 in M5.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            // OkHttp appears here and ONLY here — as Ktor's Android engine, in
            // an Android-only source set. iosMain uses Darwin, so the iOS link
            // never sees it.
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            // M5's protocol tests run against Ktor's MockEngine rather than a live
            // server. SPEC §13 requires the *sync* tests — the merge matrix, ETag churn,
            // brute-force lockout — to run against a real Nextcloud in Docker, and M6
            // does that. What is testable here is the part that is pure protocol: the
            // PROPFIND body, the multistatus parse, weak-ETag normalisation, the login
            // flow's six rules, and the 401 policy. A mock is the right tool for those
            // precisely because it can produce a weak ETag on demand.
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
