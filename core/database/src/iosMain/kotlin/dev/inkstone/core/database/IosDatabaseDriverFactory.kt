package dev.inkstone.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

/**
 * Exercised by Guardrail 2. If a JVM-only database dependency ever lands in
 * this module's `commonMain`, this is the file that stops compiling.
 */
public class IosDatabaseDriverFactory : DatabaseDriverFactory {
    override fun create(): SqlDriver =
        NativeSqliteDriver(
            schema = InkstoneDatabase.Schema,
            name = DATABASE_FILE_NAME,
        )
}
