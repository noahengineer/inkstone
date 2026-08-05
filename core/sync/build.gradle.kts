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
            implementation(libs.ktor.client.mock)
        }
    }
}
