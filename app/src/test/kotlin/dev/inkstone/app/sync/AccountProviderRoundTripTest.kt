package dev.inkstone.app.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.database.sync.SqlAccountStore
import dev.inkstone.core.sync.AccountState
import dev.inkstone.core.sync.ProviderKind
import dev.inkstone.core.sync.SyncAccount
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * docs/CLOUD-PROVIDERS.md §7 — the `provider` column, through the production store on a real
 * (in-memory) SQLite.
 *
 * What this proves and what it does not: it proves the column is written, read back, and *survives
 * the other writes* — `upsertAccount` is `INSERT OR REPLACE`, which resets any column it omits to its
 * DEFAULT, so a OneDrive account would silently become a Nextcloud one on its next write if the
 * statement forgot it. It does **not** prove the migration of an existing file: this database is
 * created at the current schema, so `10.sqm` never runs here. That is
 * `MigrationOnDeviceTest`'s job and it needs a device.
 */
class AccountProviderRoundTripTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = InkstoneDatabase(driver).also { InkstoneDatabase.Schema.create(driver) }
    private val accounts = SqlAccountStore(database, dispatcher)

    private fun account(id: String, provider: ProviderKind, data: String? = null) = SyncAccount(
        id = id,
        serverUrl = "https://graph.microsoft.com/v1.0",
        loginName = "me@example.com",
        uid = "drive-1",
        provider = provider,
        providerData = data,
    )

    @Test
    fun `a onedrive account reads back as onedrive with its data`() = runTest(dispatcher) {
        accounts.upsert(account("od", ProviderKind.ONEDRIVE, """{"driveId":"abc"}"""))

        val read = accounts.find("od")
        assertEquals(ProviderKind.ONEDRIVE, read?.provider)
        assertEquals("""{"driveId":"abc"}""", read?.providerData)
    }

    @Test
    fun `the provider survives every other write to the row`() = runTest(dispatcher) {
        accounts.upsert(account("od", ProviderKind.ONEDRIVE, "data"))

        // setState is the write a 401 makes; upsert is the write a re-login makes.
        accounts.setState("od", AccountState.NEEDS_REAUTH, "refused")
        assertEquals(ProviderKind.ONEDRIVE, accounts.find("od")?.provider, "a state change reset the provider")

        accounts.upsert(accounts.find("od")!!.copy(displayName = "Me"))
        assertEquals(ProviderKind.ONEDRIVE, accounts.find("od")?.provider, "an upsert reset the provider")
        assertEquals("data", accounts.find("od")?.providerData)
    }

    @Test
    fun `an account written without a provider is nextcloud`() = runTest(dispatcher) {
        // A row from before providers existed: the DEFAULT is what `10.sqm` gives every old row.
        driver.execute(
            null,
            "INSERT INTO account (id, serverUrl, loginName, uid, defaultFolder, state) " +
                "VALUES ('old', 'https://cloud.example.com', 'me', 'me', 'Inkstone', 'ACTIVE')",
            0,
        )

        val read = accounts.find("old")
        assertEquals(ProviderKind.NEXTCLOUD, read?.provider)
        assertNull(read?.providerData)
    }

    @Test
    fun `a provider this build does not know is unknown and never nextcloud`() = runTest(dispatcher) {
        driver.execute(
            null,
            "INSERT INTO account (id, serverUrl, loginName, uid, defaultFolder, state, provider) " +
                "VALUES ('future', 'https://x', 'me', 'me', 'Inkstone', 'ACTIVE', 'SOME_FUTURE_CLOUD')",
            0,
        )

        assertEquals(ProviderKind.UNKNOWN, accounts.find("future")?.provider)
    }
}
