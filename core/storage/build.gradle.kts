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

            // `suspend` is a language feature and needs no dependency, which is why this module
            // went eight milestones without one. `withContext` is not: `NoteStore.saveUndo` takes
            // an injected `saveContext` so the editor can keep a 62 ms undo write off the UI
            // thread. This module still owns **no dispatcher of its own** — the default is
            // `EmptyCoroutineContext` and the caller decides — so the rule that `:core` does not
            // choose threads is intact.
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
