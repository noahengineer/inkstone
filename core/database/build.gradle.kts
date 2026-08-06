plugins {
    id("inkstone.kmp-core")
    alias(libs.plugins.sqldelight)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            // M5. The sync *semantics* live in :core:sync so they can be tested against a
            // fake without a driver or a device; this module supplies the rows and the one
            // transaction they need. The edge is within :core:*, so SPEC §2.1's one-way
            // rule is untouched, and it is acyclic: :core:database -> :core:sync -> :core:model.
            api(project(":core:sync"))
            api(libs.sqldelight.runtime)
            api(libs.sqldelight.coroutines)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        androidInstrumentedTest.dependencies {
            // Instrumented, not a JVM unit test, and for the same reason :android:ink's
            // erase tests are: the thing under test is the real engine. The trash cascade is
            // a transaction across two tables and thumbnail invalidation is one UPDATE that
            // must not be splittable — a fake index would be testing the fake.
            //
            // The JVM alternative (`sqldelight-driver-sqlite` + xerial) would run on the
            // desktop but against a *different* SQLite build from the one on the tablet,
            // which is the gap SPEC §13 refuses to accept for sync tests either.
            // `inkstone.kmp-core` puts kotlin("test") on commonTest only.
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.test.junit)
            // The class `testInstrumentationRunner` below names. It is NOT pulled in
            // transitively by androidx.test.ext:junit — without it the test process dies
            // with ClassNotFoundException before a single test runs, and `check`'s
            // compile-only guard cannot see it because compiling is not dexing.
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.core)
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

android {
    // `inkstone.kmp-core` leaves this out because no other :core module has instrumented
    // tests. This one does — see the androidInstrumentedTest note above.
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

sqldelight {
    databases {
        create("InkstoneDatabase") {
            packageName.set("dev.inkstone.core.database")
            // On since M3, which brought the first real schema — the library index.
            //
            // SPEC §5.8 requires versioned, one-way migrations. This is the check that
            // makes that true rather than intended: it applies every .sqm in order to an
            // empty database and asserts the result matches the CREATE statements in the
            // .sq files. A column added to the schema without a migration fails the build
            // here, instead of crashing on the one device that has the old database.
            verifyMigrations.set(true)
            // Where `generateCommonMainInkstoneDatabaseSchema` writes the `<version>.db`
            // snapshots, and where the verify task reads them from. Setting it is also
            // what *registers* the generate task — it does not exist otherwise, which is
            // a surprise worth writing down.
            //
            // The committed `1.db` is the pre-M3 schema. It is a fixture, so it is in git:
            // without it the verifier can only check that the migrations parse, and the
            // whole point is to check they land on the same schema a fresh install gets.
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
        }
    }
}
