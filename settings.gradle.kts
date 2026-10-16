@file:Suppress("UnstableApiUsage")

pluginManagement {
    // Convention plugins live in an included build so every module gets the
    // :core guardrail automatically rather than by remembering to add it.
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // `verifyCatalogCoordinates` proves every pin in the catalog resolves,
        // including the [plugins] entries. Plugin *marker* artifacts are not
        // all mirrored to Maven Central — `com.diffplug.spotless.gradle.plugin`
        // is a 404 there and lives only on the portal. Restricted to markers so
        // this does not quietly become a third source for real dependencies.
        gradlePluginPortal {
            content { includeModuleByRegex(".*", ".*\\.gradle\\.plugin") }
        }
    }
}

rootProject.name = "inkstone"

// SPEC §2.1 — dependency direction is one-way: :app -> :android:* -> :core:*
include(":core:model")
include(":core:storage")
include(":core:database")
include(":core:sync")
include(":core:search")
include(":core:calc")
include(":core:handwriting")

include(":android:ink")
include(":android:pdf")
include(":android:ocr")

include(":app")
