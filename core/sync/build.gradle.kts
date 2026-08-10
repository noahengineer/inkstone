plugins {
    id("inkstone.kmp-core")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))

            // M6. The reconciler decides what to do with a merge, so it has to be able to
            // *name* one: `MergeReport` and `NoteLayout` are :core:storage's. The alternative
            // was a second copy of SPEC §7.7's outcome vocabulary and of `download/<id>.pdf`
            // inside this module — two sources of truth for the one decision this codebase has
            // already spent a milestone getting right.
            //
            // One new edge, inside :core:*, and acyclic:
            //   :core:database -> :core:sync -> :core:storage -> :core:model
            // Both modules are platform-free KMP, so `linkIosCore` is unaffected and
            // `checkCoreIsPlatformFree` sees no new android* imports. Precedent: M5 added
            // :core:database -> :core:sync for the same reason (docs/M5-NEXTCLOUD.md §2).
            // `api` and not `implementation` because `RemoteMergeGate` — which :app
            // implements — returns a `MergeReport`.
            api(project(":core:storage"))

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
