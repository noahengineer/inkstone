plugins {
    id("inkstone.kmp-core")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))

            // The op log's *metadata* is JSON — ids, clocks, types and offsets
            // (SPEC §5.3). Stroke geometry is not: it is an opaque blob referenced by
            // offset into a sibling entry, because points as JSON would be megabytes
            // per page against a 2-second open target (SPEC §5.4).
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
