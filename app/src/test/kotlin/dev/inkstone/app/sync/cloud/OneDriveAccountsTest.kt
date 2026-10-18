package dev.inkstone.app.sync.cloud

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.inkstone.app.note.InMemoryFileSystem
import dev.inkstone.app.platform.KeystorePendingSignInStore
import dev.inkstone.app.platform.KeystoreTokenVault
import dev.inkstone.app.platform.SealedFiles
import dev.inkstone.app.sync.MicrosoftSettings
import dev.inkstone.app.sync.NextcloudAccounts
import dev.inkstone.app.sync.OneDriveAccounts
import dev.inkstone.app.sync.SignInNotice
import dev.inkstone.app.sync.matrix.CountingScheduler
import dev.inkstone.app.sync.matrix.MatrixClock
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.database.sync.SqlAccountStore
import dev.inkstone.core.database.sync.SqlBindingStore
import dev.inkstone.core.database.sync.SqlBrowsedFolderStore
import dev.inkstone.core.database.sync.SqlOutboxStore
import dev.inkstone.core.database.sync.SqlRecordingUploads
import dev.inkstone.core.database.sync.SqlSyncNotes
import dev.inkstone.core.sync.AccountState
import dev.inkstone.core.sync.NextcloudTransport
import dev.inkstone.core.sync.OneDriveStoreFactory
import dev.inkstone.core.sync.PkceCrypto
import dev.inkstone.core.sync.ProviderKind
import dev.inkstone.core.sync.SyncAccount
import dev.inkstone.core.sync.SyncEngine
import dev.inkstone.core.sync.SyncStores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `OneDriveAccounts` — the glue between the sign-in protocol, the Keystore-sealed token store, the
 * account table and the OneDrive store — over **production objects only**: the real `MicrosoftSignIn`,
 * `KeystoreTokenVault` and `KeystorePendingSignInStore` (with a plain AES key where the Keystore would
 * be), real SQLite stores, the real transport, and [GraphStandIn] behind a socket.
 *
 * This is the layer the other tests skip, and the one that is most likely to be correct in every part
 * and inert as a whole (CLAUDE.md, definition of done #6): `MicrosoftSignInTest` never creates an
 * account, `OneDriveStoreTest` never signs in.
 *
 * ⚠️ A green run means the app's own wiring does what it says against the stand-in. It does not say
 * Microsoft accepts the redirect URI or the scopes; that is the user's real sign-in.
 */
class OneDriveAccountsTest {

    private val server = GraphStandIn()
    private val clock = MatrixClock()
    private val transport = NextcloudTransport.create()
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = InkstoneDatabase(driver).also { InkstoneDatabase.Schema.create(driver) }
    private val dispatcher = Dispatchers.Unconfined

    private val accountStore = SqlAccountStore(database, dispatcher)
    private val bindings = SqlBindingStore(database, dispatcher)
    private val cache = SqlBrowsedFolderStore(database, dispatcher)
    private val engine = SyncEngine(
        SyncStores(
            accountStore,
            bindings,
            SqlOutboxStore(database, dispatcher),
            SqlSyncNotes(database, dispatcher),
            SqlRecordingUploads(database, dispatcher),
        ),
        clock,
        CountingScheduler(),
    )

    private val files = InMemoryFileSystem()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val vault = KeystoreTokenVault(SealedFiles(files) { key })
    private val pending = KeystorePendingSignInStore(SealedFiles(files) { key })

    private val settings = MicrosoftSettings(
        rawClientId = "client-1",
        redirectUri = "msauth://dev.inkstone/abc%3D",
        rawStandIn = server.baseUrl.removeSuffix("/v1.0"),
    )

    private val oneDrive = OneDriveAccounts(
        accounts = accountStore,
        vault = vault,
        bindings = bindings,
        sync = engine,
        clock = clock,
        folderCache = cache,
        settings = settings,
        factory = OneDriveStoreFactory(settings.oauth, vault, transport, clock),
        transport = transport,
        pending = pending,
        crypto = object : PkceCrypto {
            private val random = SecureRandom()

            override fun randomBytes(count: Int) = ByteArray(count).also(random::nextBytes)

            override fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        },
    )

    @AfterTest
    fun tearDown() {
        server.close()
        transport.close()
        driver.close()
    }

    /** The whole user journey: open the page, press Continue, and let the redirect reach the app. */
    private suspend fun signIn(againAs: String? = null): SignInNotice {
        val url = assertNotNull(oneDrive.begin(againAs), "begin returned no URL for a configured build")
        val page = withContext(Dispatchers.IO) {
            (URI(url).toURL().openConnection() as HttpURLConnection).run { inputStream.bufferedReader().readText() }
        }
        val redirect = assertNotNull(Regex("""href="([^"]+)"""").find(page)).groupValues[1].replace("&amp;", "&")
        oneDrive.onRedirect(redirect)
        return awaitNotice()
    }

    private suspend fun awaitNotice(): SignInNotice = withContext(Dispatchers.Default) {
        withTimeout(NOTICE_TIMEOUT_MILLIS) { oneDrive.notice.filterNotNull().first() }
    }.also { oneDrive.noticeShown() }

    private suspend fun onedriveAccounts() = accountStore.all().filter { it.provider == ProviderKind.ONEDRIVE }

    // ------------------------------------------------------------------------------------------

    @Test
    fun `a first sign-in makes one account and keeps its token under that account`() = runTest {
        val notice = assertIs<SignInNotice.SignedIn>(signIn())

        val rows = onedriveAccounts()
        assertEquals(1, rows.size, "one sign-in made ${rows.size} accounts")
        val account = rows.single()
        assertEquals(notice.account.id, account.id)
        assertEquals(AccountState.ACTIVE, account.state)
        assertEquals("drive-1", account.uid)
        assertEquals("test.user@example.com", account.loginName)
        assertTrue(account.isUsable, "the account must be usable the moment it exists")
        assertNotNull(vault.get(account.id), "the refresh token is not under the account's id")
        assertEquals(false, notice.resumed)
    }

    @Test
    fun `signing in a second time as the same person finds the account instead of making another`() = runTest {
        val first = assertIs<SignInNotice.SignedIn>(signIn()).account

        val second = assertIs<SignInNotice.SignedIn>(signIn())

        assertEquals(first.id, second.account.id)
        assertEquals(1, onedriveAccounts().size)
        assertTrue(second.resumed)
    }

    @Test
    fun `signing out keeps the row and forgets the token and signing in again resumes it`() = runTest {
        val id = assertIs<SignInNotice.SignedIn>(signIn()).account.id

        oneDrive.signOut(id)

        assertEquals(AccountState.SIGNED_OUT, accountStore.find(id)?.state)
        assertNull(vault.get(id), "sign-out left the refresh token on the device")

        val back = assertIs<SignInNotice.SignedIn>(signIn(againAs = id))
        assertEquals(id, back.account.id)
        assertEquals(AccountState.ACTIVE, accountStore.find(id)?.state)
        assertNotNull(vault.get(id))
        assertEquals(1, onedriveAccounts().size)
    }

    @Test
    fun `signing in as somebody else when re-signing-in to an account is refused and changes nothing`() = runTest {
        val id = assertIs<SignInNotice.SignedIn>(signIn()).account.id
        oneDrive.signOut(id)
        server.signedInUserId = "user-2"
        server.signedInMail = "someone.else@example.com"
        server.signedInDriveId = "drive-2"

        val refused = assertIs<SignInNotice.Failed>(signIn(againAs = id))

        assertTrue(refused.reason.contains("different Microsoft account"), refused.reason)
        assertEquals(1, onedriveAccounts().size, "a refused identity must not become an account")
        assertEquals(AccountState.SIGNED_OUT, accountStore.find(id)?.state)
        assertNull(vault.get(id))
        assertTrue(files.exists("oauth/pending-sign-in.bin").not())
    }

    @Test
    fun `saying no on the sign-in page changes nothing`() = runTest {
        server.userDeclines = true

        assertIs<SignInNotice.Cancelled>(signIn())

        assertTrue(onedriveAccounts().isEmpty())
    }

    @Test
    fun `a redirect delivered twice does not replace the sign-in with a failure`() = runTest {
        val url = assertNotNull(oneDrive.begin())
        val page = withContext(Dispatchers.IO) {
            (URI(url).toURL().openConnection() as HttpURLConnection).run { inputStream.bufferedReader().readText() }
        }
        val redirect = assertNotNull(Regex("""href="([^"]+)"""").find(page)).groupValues[1].replace("&amp;", "&")

        oneDrive.onRedirect(redirect)
        assertIs<SignInNotice.SignedIn>(awaitNotice())
        oneDrive.onRedirect(redirect)
        // Give the duplicate time to run; then there must be no notice to show and still one account.
        withContext(Dispatchers.Default) { kotlinx.coroutines.delay(SETTLE_MILLIS) }

        assertNull(oneDrive.notice.value, "the duplicate delivery produced a notice")
        assertEquals(1, onedriveAccounts().size)
    }

    @Test
    fun `a token this device cannot open flags the account without asking Microsoft anything`() = runTest {
        val id = assertIs<SignInNotice.SignedIn>(signIn()).account.id
        vault.remove(id) // what an invalidated Keystore key looks like to the vault: no secret
        val apiBefore = server.api.count
        val tokenBefore = server.tokenEndpoint.count

        oneDrive.repairAndRefresh()

        val account = assertNotNull(accountStore.find(id))
        assertEquals(AccountState.NEEDS_REAUTH, account.state)
        assertEquals(OneDriveAccounts.MISSING_TOKEN_REASON, account.lastError)
        assertEquals(apiBefore, server.api.count, "flagging a missing token made an API request")
        assertEquals(tokenBefore, server.tokenEndpoint.count, "flagging a missing token asked the token endpoint")
    }

    @Test
    fun `a nextcloud account is never flagged by the onedrive repair`() = runTest {
        accountStore.upsert(
            SyncAccount(id = "nc-1", serverUrl = "https://cloud.example.com", loginName = "me", uid = "me"),
        )

        oneDrive.repairAndRefresh()

        assertEquals(AccountState.ACTIVE, accountStore.find("nc-1")?.state)
    }

    @Test
    fun `browsing lists folders and pdfs from the drive and remembers the folders`() = runTest {
        val id = assertIs<SignInNotice.SignedIn>(signIn()).account.id
        server.seedFile("Inkstone/Physics/Notes.pdf", "pdf".encodeToByteArray())
        server.seedFile("Inkstone/Chemistry/Lab.pdf", "pdf".encodeToByteArray())

        val listing = assertIs<NextcloudAccounts.BrowseResult.Listing>(oneDrive.browse(id, "Inkstone"))

        assertEquals(listOf("Chemistry", "Physics"), listing.folders.map { it.name })
        assertNull(listing.remembered)
        val again = cache.childrenOf(id, "Inkstone")
        assertEquals(listOf("Chemistry", "Physics"), again.folders.map { it.name })
    }

    @Test
    fun `a refused token during browsing flags the account once and stops asking`() = runTest {
        val id = assertIs<SignInNotice.SignedIn>(signIn()).account.id
        server.rejectAccessTokens = true

        val first = oneDrive.browse(id, "")

        assertIs<NextcloudAccounts.BrowseResult.Failed>(first)
        assertEquals(AccountState.NEEDS_REAUTH, accountStore.find(id)?.state)
        val tokenCalls = server.tokenEndpoint.count
        val apiCalls = server.api.count

        val second = oneDrive.browse(id, "")

        assertIs<NextcloudAccounts.BrowseResult.Unavailable>(second)
        assertEquals(tokenCalls, server.tokenEndpoint.count, "a halted account asked the token endpoint again")
        assertEquals(apiCalls, server.api.count, "a halted account made another API request")
    }

    @Test
    fun `after a dead grant signing in again works - the latch is released`() = runTest {
        val id = assertIs<SignInNotice.SignedIn>(signIn()).account.id
        clock.advance(TWO_HOURS) // the cached access token is now spent, so the next call must refresh
        server.grantDead = true

        assertIs<NextcloudAccounts.BrowseResult.Failed>(oneDrive.browse(id, ""))
        assertEquals(AccountState.NEEDS_REAUTH, accountStore.find(id)?.state)

        server.grantDead = false
        val back = assertIs<SignInNotice.SignedIn>(signIn(againAs = id))

        assertEquals(id, back.account.id)
        assertEquals(AccountState.ACTIVE, accountStore.find(id)?.state)
        assertIs<NextcloudAccounts.BrowseResult.Listing>(
            oneDrive.browse(id, ""),
            "the dead-grant latch survived a new sign-in, so the account still refuses",
        )
    }

    @Test
    fun `an unconfigured build offers no sign-in`() = runTest {
        val bare = OneDriveAccounts(
            accounts = accountStore,
            vault = vault,
            bindings = bindings,
            sync = engine,
            clock = clock,
            folderCache = cache,
            settings = MicrosoftSettings(rawClientId = " ", redirectUri = "msauth://dev.inkstone/x", rawStandIn = ""),
            factory = OneDriveStoreFactory(settings.oauth, vault, transport, clock),
            transport = transport,
            pending = pending,
            crypto = object : PkceCrypto {
                override fun randomBytes(count: Int) = ByteArray(count)

                override fun sha256(bytes: ByteArray) = ByteArray(32)
            },
        )

        assertNull(bare.begin())
        assertEquals(false, bare.configured)
    }

    private companion object {
        const val NOTICE_TIMEOUT_MILLIS = 10_000L
        const val SETTLE_MILLIS = 400L
        const val TWO_HOURS = 2L * 60 * 60 * 1000
    }
}
