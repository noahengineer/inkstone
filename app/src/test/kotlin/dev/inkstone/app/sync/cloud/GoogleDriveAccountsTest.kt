package dev.inkstone.app.sync.cloud

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.inkstone.app.sync.GoogleAccessTokens
import dev.inkstone.app.sync.GoogleAuthorization
import dev.inkstone.app.sync.GoogleAuthorizer
import dev.inkstone.app.sync.GoogleDriveAccounts
import dev.inkstone.app.sync.GoogleSettings
import dev.inkstone.app.sync.GoogleTokenRegistry
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
import dev.inkstone.core.sync.DriveStoreFactory
import dev.inkstone.core.sync.NextcloudTransport
import dev.inkstone.core.sync.ProviderKind
import dev.inkstone.core.sync.ProviderRoutingFactory
import dev.inkstone.core.sync.SyncEngine
import dev.inkstone.core.sync.SyncStores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GoogleDriveAccounts` over production objects: the real transport, `DriveStore`, SQL stores and
 * [DriveStandIn] behind a socket. The one fake is [GoogleAuthorizer] — Play Services — which has no JVM form.
 *
 * ⚠️ A green run means the app's own wiring does what it says against the stand-in and a scripted
 * Play Services. It does not say Google grants `drive.file` to this package and fingerprint, that `about`
 * answers under that scope, or what the consent screen looks like. That is the user's real sign-in.
 */
class GoogleDriveAccountsTest {

    private val server = DriveStandIn()
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

    private class Scripted : GoogleAuthorizer {
        var next: GoogleAuthorization = GoogleAuthorization.Granted("tok")
        var consentResult: GoogleAuthorization = GoogleAuthorization.Granted("tok")
        val asked = mutableListOf<String?>()
        val revoked = mutableListOf<String?>()

        override suspend fun authorize(accountEmail: String?): GoogleAuthorization {
            asked += accountEmail
            return next
        }

        override fun fromConsentResult(data: Any?): GoogleAuthorization = consentResult

        override suspend fun clearToken(token: String) = Unit

        override suspend fun revoke(accountEmail: String?) {
            revoked += accountEmail
        }
    }

    private val authorizer = Scripted()
    private val registry = GoogleTokenRegistry(authorizer, clock)
    private val settings = GoogleSettings(server.baseUrl)

    private val google = GoogleDriveAccounts(
        accounts = accountStore,
        bindings = bindings,
        sync = engine,
        clock = clock,
        folderCache = cache,
        registry = registry,
        authorizer = authorizer,
        settings = settings,
        transport = transport,
    )

    @AfterTest
    fun tearDown() {
        server.close()
        transport.close()
        driver.close()
    }

    private suspend fun googleAccounts() = accountStore.all().filter { it.provider == ProviderKind.GOOGLE_DRIVE }

    private suspend fun signIn(againAs: String? = null): SignInNotice =
        assertIs<GoogleDriveAccounts.Begin.Done>(google.begin(againAs)).notice

    @Test
    fun `a sign-in where the grant already stands makes one account keyed on the permission id`() = runTest {
        val notice = assertIs<SignInNotice.SignedIn>(signIn())

        val rows = googleAccounts()
        assertEquals(1, rows.size)
        val account = rows.single()
        assertEquals(notice.account.id, account.id)
        assertEquals("perm-1", account.uid)
        assertEquals("test.user@gmail.com", account.loginName)
        assertEquals(AccountState.ACTIVE, account.state)
        assertTrue(account.isUsable)
        assertEquals(settings.apiBase, account.serverUrl)
        assertEquals(false, notice.resumed)
    }

    @Test
    fun `signing in again as the same person finds the account instead of making a second`() = runTest {
        val first = assertIs<SignInNotice.SignedIn>(signIn()).account

        val second = assertIs<SignInNotice.SignedIn>(signIn())

        assertEquals(first.id, second.account.id)
        assertEquals(1, googleAccounts().size)
        assertTrue(second.resumed)
    }

    @Test
    fun `a grant that needs a screen hands back what to show and finishes when the result arrives`() = runTest {
        authorizer.next = GoogleAuthorization.NeedsConsent("pending-intent")

        val begun = assertIs<GoogleDriveAccounts.Begin.Consent>(google.begin())
        assertEquals("pending-intent", begun.request)
        assertTrue(googleAccounts().isEmpty(), "an account appeared before the user had agreed to anything")

        val notice = google.complete(Any())

        assertIs<SignInNotice.SignedIn>(notice)
        assertEquals(1, googleAccounts().size)
    }

    @Test
    fun `dismissing the consent screen changes nothing`() = runTest {
        authorizer.next = GoogleAuthorization.NeedsConsent("pending-intent")
        google.begin()

        assertIs<SignInNotice.Cancelled>(google.complete(null))

        assertTrue(googleAccounts().isEmpty())
    }

    @Test
    fun `a different google account when re-signing-in is refused and nothing changes`() = runTest {
        val id = assertIs<SignInNotice.SignedIn>(signIn()).account.id
        google.signOut(id)
        server.userPermissionId = "perm-2"
        server.userEmail = "someone.else@gmail.com"

        val refused = assertIs<SignInNotice.Failed>(signIn(againAs = id))

        assertTrue(refused.reason.contains("different Google account"), refused.reason)
        assertEquals(1, googleAccounts().size, "a refused identity became an account")
        assertEquals(AccountState.SIGNED_OUT, accountStore.find(id)?.state)
    }

    @Test
    fun `if google will not say who you are nothing is saved and the reason is named`() = runTest {
        server.rejectAccessTokens = true

        val failed = assertIs<SignInNotice.Failed>(signIn())

        assertTrue(failed.reason.contains("HTTP 401"), failed.reason)
        assertTrue(googleAccounts().isEmpty())
    }

    @Test
    fun `a registration google refuses is shown as it is`() = runTest {
        authorizer.next = GoogleAuthorization.Failed("Google rejected this app's registration.", retryable = false)

        val failed = assertIs<SignInNotice.Failed>(signIn())

        assertEquals("Google rejected this app's registration.", failed.reason)
    }

    @Test
    fun `signing out revokes the permission keeps the row and signing in again resumes it`() = runTest {
        val id = assertIs<SignInNotice.SignedIn>(signIn()).account.id

        google.signOut(id)

        assertEquals(listOf<String?>("test.user@gmail.com"), authorizer.revoked)
        assertEquals(AccountState.SIGNED_OUT, accountStore.find(id)?.state)
        val back = assertIs<SignInNotice.SignedIn>(signIn(againAs = id))
        assertEquals(id, back.account.id)
        assertEquals(AccountState.ACTIVE, accountStore.find(id)?.state)
        assertEquals(1, googleAccounts().size)
    }

    @Test
    fun `forgetting removes the row and revokes and touches nothing in drive`() = runTest {
        val id = assertIs<SignInNotice.SignedIn>(signIn()).account.id
        val before = server.api.count

        google.forgetAccount(id)

        assertNull(accountStore.find(id))
        assertEquals(listOf<String?>("test.user@gmail.com"), authorizer.revoked)
        assertEquals(before, server.api.count, "forgetting an account made a request to Drive")
    }

    @Test
    fun `a grant google no longer lists flags the account at launch`() = runTest {
        val id = assertIs<SignInNotice.SignedIn>(signIn()).account.id
        // The sign-in primed a token; a new process has none, so the launch check asks Play Services.
        registry.forget(id)
        authorizer.next = GoogleAuthorization.NeedsConsent(Any())

        google.repairAndRefresh()

        val account = assertNotNull(accountStore.find(id))
        assertEquals(AccountState.NEEDS_REAUTH, account.state)
        assertEquals(GoogleAccessTokens.MISSING_GRANT_REASON, account.lastError)
    }

    @Test
    fun `not reaching google at launch flags nothing`() = runTest {
        val id = assertIs<SignInNotice.SignedIn>(signIn()).account.id
        registry.forget(id)
        authorizer.next = GoogleAuthorization.Failed("offline", retryable = true)

        google.repairAndRefresh()

        assertEquals(AccountState.ACTIVE, accountStore.find(id)?.state)
    }

    @Test
    fun `an account already flagged is not asked about again`() = runTest {
        val id = assertIs<SignInNotice.SignedIn>(signIn()).account.id
        accountStore.setState(id, AccountState.NEEDS_REAUTH, "x")
        val asked = authorizer.asked.size

        google.repairAndRefresh()

        assertEquals(asked, authorizer.asked.size, "a flagged account was asked about at launch")
    }

    @Test
    fun `a drive account is browsed through the shared reads and reaches the drive`() = runTest {
        val id = assertIs<SignInNotice.SignedIn>(signIn()).account.id
        server.seedFile("Inkstone/Physics/Notes.pdf", "pdf".encodeToByteArray())
        val browser = browser()

        val listing = assertIs<NextcloudAccounts.BrowseResult.Listing>(browser.browse(id, "Inkstone"))

        assertEquals(listOf("Physics"), listing.folders.map { it.name })
    }

    /** The provider-agnostic reads, built the way production builds them: a routing factory over Drive's store. */
    private fun browser(): OneDriveAccounts {
        val driveFactory = DriveStoreFactory(transport) { registry.tokensFor(it) }
        val stores = ProviderRoutingFactory(mapOf(ProviderKind.GOOGLE_DRIVE to driveFactory))
        val unused = object : dev.inkstone.core.sync.TokenVault {
            override suspend fun get(accountId: String) = null

            override suspend fun put(accountId: String, secret: dev.inkstone.core.sync.OAuthSecret) = Unit

            override suspend fun remove(accountId: String) = Unit
        }
        val microsoft = dev.inkstone.app.sync.MicrosoftSettings(
            rawClientId = "",
            redirectUri = "msauth://x/y",
            rawStandIn = "",
        )
        return OneDriveAccounts(
            accounts = accountStore,
            vault = unused,
            bindings = bindings,
            sync = engine,
            clock = clock,
            folderCache = cache,
            settings = microsoft,
            factory = dev.inkstone.core.sync.OneDriveStoreFactory(microsoft.oauth, unused, transport, clock),
            stores = stores,
            transport = transport,
            pending = object : dev.inkstone.core.sync.PendingSignInStore {
                override suspend fun put(pending: dev.inkstone.core.sync.PendingSignIn) = Unit

                override suspend fun take(): dev.inkstone.core.sync.PendingSignIn? = null
            },
            crypto = object : dev.inkstone.core.sync.PkceCrypto {
                override fun randomBytes(count: Int) = ByteArray(count)

                override fun sha256(bytes: ByteArray) = ByteArray(32)
            },
        )
    }
}
