plugins {
    id("inkstone.kmp-core")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))

            // docs/HANDWRITING-SYNTHESIS.md §5.3 — the harvester reads lines and words exactly the
            // way M7's recogniser did, because the recognised text it labels with came from them.
            // A second line segmenter here would disagree with that one about where a word is.
            implementation(project(":core:search"))

            // §4.4 — the weights file's identity and self-check are SHA-256, and `:core:storage`'s
            // `sha256Hex` is the one hand-written digest in common Kotlin (CLAUDE.md: none in stdlib).
            // A second copy here would be a second implementation to keep right.
            implementation(project(":core:storage"))

            // The profile's two small JSON files (§4.2). Hand-built `JsonObject`s, as `OpCodec`
            // does, rather than the serialization plugin: unknown keys have to survive a rewrite
            // (SPEC §5.8), and a generated serializer drops them.
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
