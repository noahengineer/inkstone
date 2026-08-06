plugins {
    id("inkstone.kmp-core")
    alias(libs.plugins.sqldelight)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.driver.android)
        }
        iosMain.dependencies {
            // The reason :core:database is a real KMP module and not an Android
            // one: this driver is what Guardrail 2 proves still links.
            implementation(libs.sqldelight.driver.native)
        }
    }
}

sqldelight {
    databases {
        create("InkstoneDatabase") {
            packageName.set("dev.inkstone.core.database")
            // TODO(M3): turn on verifyMigrations together with the first real
            // schema — the library index. M2 came and went without one: a note's
            // truth is its bundle and journal on disk (SPEC §5.1), not a table.
            // SPEC §5.8 requires versioned, one-way migrations tested against
            // committed fixtures; enabling it now would just fail the build for
            // want of a baseline .db to compare against.
        }
    }
}
