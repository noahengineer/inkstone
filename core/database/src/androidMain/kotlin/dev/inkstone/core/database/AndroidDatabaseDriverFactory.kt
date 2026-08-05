package dev.inkstone.core.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * `androidMain` is Android-by-definition — this is exactly the source set the
 * platform-free guardrail deliberately does not scan (see
 * `CorePlatformFreeCheckTask`). The rule that protects the iPad path is the
 * `iosSimulatorArm64` link, and this file has no effect on it.
 */
public class AndroidDatabaseDriverFactory(
    private val context: Context,
) : DatabaseDriverFactory {
    override fun create(): SqlDriver =
        AndroidSqliteDriver(
            schema = InkstoneDatabase.Schema,
            context = context,
            name = DATABASE_FILE_NAME,
        )
}
