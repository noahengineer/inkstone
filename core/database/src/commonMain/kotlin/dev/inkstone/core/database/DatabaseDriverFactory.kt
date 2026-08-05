package dev.inkstone.core.database

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform-supplied SQLite driver.
 *
 * Android needs a `Context` to open a database and iOS does not, so the driver
 * cannot be constructed in shared code. The interface lives here; the two
 * implementations live in `androidMain` and `iosMain` of this module.
 *
 * SPEC §0.2: SQLDelight, not Room — Room 3.0 is not stable, and SQLDelight is
 * KMP-native, which is the whole reason `:core:database` can exist at all.
 */
public fun interface DatabaseDriverFactory {
    public fun create(): SqlDriver
}

public const val DATABASE_FILE_NAME: String = "inkstone.db"

public fun createDatabase(driverFactory: DatabaseDriverFactory): InkstoneDatabase =
    InkstoneDatabase(driverFactory.create())
