package dev.inkstone.app.sync.cloud

import dev.inkstone.app.sync.matrix.MatrixClock
import dev.inkstone.core.sync.MicrosoftOAuthConfig
import dev.inkstone.core.sync.MicrosoftSignIn
import dev.inkstone.core.sync.MicrosoftSignInConfig
import dev.inkstone.core.sync.NextcloudTransport
import dev.inkstone.core.sync.OAuthSecret
import dev.inkstone.core.sync.PendingSignIn
import dev.inkstone.core.sync.PendingSignInStore
import dev.inkstone.core.sync.PkceCrypto
import dev.inkstone.core.sync.SignInResult
import dev.inkstone.core.sync.TokenVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `MicrosoftSignIn` against [GraphStandIn]'s authorize page, token endpoint and `/me` — the production
 * transport, the real form encoding, the real redirect parsing.
 *
 * ⚠️ **What a green run means.** The protocol is carried out as RFC 6749/7636 and Microsoft's
 * documentation describe it, *as the stand-in encodes it*. Whether `login.microsoftonline.com` accepts
 * this particular redirect URI and these parameters is the one thing no test here can say; that is
 * the user's real sign-in.
 *
 * "The user pressing Continue" is [pressContinue]: it fetches the authorize page and follows the one
 * link on it, because a server-side 302 to a custom scheme would pass here and fail on a device.
 */
class MicrosoftSignInTest {

    private val server = GraphStandIn()
    private val clock = MatrixClock()
    private val transport = NextcloudTransport.create()
    private val vault = RecordingVault()
    private val pending = MemoryPending()

    @AfterTest
    fun tearDown() {
        server.close()
        transport.close()
    }

    private inner class RecordingVault : TokenVault {
        val secrets = HashMap<String, OAuthSecret>()

        override suspend fun get(accountId: String): OAuthSecret? = secrets[accountId]

        override suspend fun put(accountId: String, secret: OAuthSecret) {
            server.log += "vault put ${secret.refreshToken}"
            secrets[accountId] = secret
        }

        override suspend fun remove(accountId: String) {
            server.log += "vault remove"
            secrets.remove(accountId)
        }
    }

    private class MemoryPending : PendingSignInStore {
        var slot: PendingSignIn? = null
        var puts = 0

        override suspend fun put(pending: PendingSignIn) {
            puts++
            slot = pending
        }

        override suspend fun take(): PendingSignIn? = slot.also { slot = null }
    }

    private class JvmCrypto(
        private val random: (Int) -> ByteArray = { n -> ByteArray(n).also(SecureRandom()::nextBytes) },
    ) : PkceCrypto {
        override fun randomBytes(count: Int): ByteArray = random(count)

        override fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    private val redirectUri = "msauth://dev.inkstone/abc%3D"

    private fun signIn(crypto: PkceCrypto = JvmCrypto()) = MicrosoftSignIn(
        transport = transport,
        config = MicrosoftSignInConfig(
            oauth = MicrosoftOAuthConfig(clientId = "client-1", tokenEndpoint = server.tokenUrl),
            redirectUri = redirectUri,
            authorizeEndpoint = server.authorizeUrl,
            graphBase = server.baseUrl,
        ),
        vault = vault,
        pending = pending,
        crypto = crypto,
        clock = clock,
    )

    /** The browser: open the sign-in URL, then follow the page's one link — the redirect back to the app. */
    private suspend fun pressContinue(url: String): String {
        val page = withContext(Dispatchers.IO) {
            (URI(url).toURL().openConnection() as HttpURLConnection).run { inputStream.bufferedReader().readText() }
        }
        val href = Regex("""href="([^"]+)"""").find(page)?.groupValues?.get(1)?.replace("&amp;", "&")
        return assertNotNull(href, "the authorize page offered no link: $page")
    }

    private fun param(url: String, name: String): String? = url.substringAfter('?', "").split('&')
        .map { it.substringBefore('=') to it.substringAfter('=', "") }
        .firstOrNull { it.first == name }?.let { URLDecoder.decode(it.second, "UTF-8") }

    // ------------------------------------------------------------------------------------------

    @Test
    fun `the challenge is RFC 7636's own vector`() = runTest {
        // RFC 7636 Appendix B: this verifier's S256 challenge is E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM.
        val octets = intArrayOf(
            116, 24, 223, 180, 151, 153, 224, 37, 79, 250, 96, 125, 216, 173, 187, 186,
            22, 212, 37, 77, 105, 214, 191, 240, 91, 88, 5, 88, 83, 132, 141, 121,
        ).map { it.toByte() }.toByteArray()
        var calls = 0
        val crypto = JvmCrypto { count -> if (calls++ == 0) octets else ByteArray(count) { 7 } }

        val url = signIn(crypto).begin()

        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", param(url, "code_challenge"))
        assertEquals("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", pending.slot?.codeVerifier)
        assertEquals("S256", param(url, "code_challenge_method"))
    }

    @Test
    fun `begin asks for the scopes and the account picker and persists before it returns`() = runTest {
        val url = signIn().begin(expectedAccountId = "od-1")

        val saved = assertNotNull(pending.slot, "begin returned a URL with nothing written down")
        assertEquals(1, pending.puts)
        assertEquals(saved.state, param(url, "state"))
        assertEquals("od-1", saved.expectedAccountId)
        assertEquals("client-1", param(url, "client_id"))
        assertEquals("code", param(url, "response_type"))
        assertEquals(redirectUri, param(url, "redirect_uri"))
        assertEquals("select_account", param(url, "prompt"))
        assertTrue(param(url, "scope").orEmpty().contains("offline_access"), "no offline_access, no refresh token")
        assertTrue(param(url, "scope").orEmpty().contains("User.Read"), "no User.Read, and /me is refused")
        assertTrue(saved.codeVerifier.length >= 43, "RFC 7636's minimum verifier length")
    }

    @Test
    fun `a complete sign-in saves the refresh token before using the access token`() = runTest {
        val flow = signIn()
        val redirect = pressContinue(flow.begin(expectedAccountId = "od-1"))

        val result = flow.complete(redirect, accountId = "provisional")

        val ok = assertIs<SignInResult.Success>(result)
        assertEquals("user-1", ok.identity.userId)
        assertEquals("test.user@example.com", ok.identity.loginName)
        assertEquals("drive-1", ok.identity.driveId)
        assertEquals("od-1", ok.expectedAccountId)
        assertNotNull(vault.secrets["provisional"], "the secret is under the id the caller passed")
        val issued = server.log.indexOfFirst { it.startsWith("token issued") }
        val saved = server.log.indexOfFirst { it.startsWith("vault put") }
        val firstApi = server.log.indexOfFirst { it.startsWith("api ") }
        assertTrue(issued in 0 until saved && saved < firstApi, "order was: ${server.log}")
        assertNull(pending.slot, "the pending record is spent")
    }

    @Test
    fun `a redirect carrying another state is refused before any code is exchanged`() = runTest {
        val flow = signIn()
        val redirect = pressContinue(flow.begin())
        val forged = redirect.replace(Regex("state=[^&]+"), "state=somebody-elses")

        val result = flow.complete(forged, "provisional")

        assertIs<SignInResult.Failed>(result)
        assertEquals(0, server.tokenEndpoint.count, "a forged redirect reached the token endpoint")
        assertTrue(vault.secrets.isEmpty())
    }

    @Test
    fun `a redirect delivered twice only signs in once`() = runTest {
        val flow = signIn()
        val redirect = pressContinue(flow.begin())

        assertIs<SignInResult.Success>(flow.complete(redirect, "provisional"))
        val again = flow.complete(redirect, "second")

        assertIs<SignInResult.Failed>(again)
        assertEquals(1, server.tokenEndpoint.count)
        assertNull(vault.secrets["second"])
    }

    @Test
    fun `saying no is a cancellation and leaves nothing behind`() = runTest {
        server.userDeclines = true
        val flow = signIn()
        val redirect = pressContinue(flow.begin())

        val result = flow.complete(redirect, "provisional")

        assertIs<SignInResult.Cancelled>(result)
        assertEquals(0, server.tokenEndpoint.count)
        assertTrue(vault.secrets.isEmpty())
        assertNull(pending.slot)
    }

    @Test
    fun `a stale sign-in is refused`() = runTest {
        val flow = signIn()
        val redirect = pressContinue(flow.begin())
        clock.now += 16 * 60 * 1000L

        val result = flow.complete(redirect, "provisional")

        assertIs<SignInResult.Failed>(result)
        assertEquals(0, server.tokenEndpoint.count)
    }

    @Test
    fun `a redirect to some other address is not ours`() = runTest {
        val flow = signIn()
        flow.begin()

        val result = flow.complete("https://evil.example/cb?code=x&state=${pending.slot?.state}", "provisional")

        assertIs<SignInResult.Failed>(result)
        assertEquals(0, server.tokenEndpoint.count)
    }

    @Test
    fun `a verifier that does not match its challenge fails and stores nothing`() = runTest {
        // sha256 lies on the way out, so the challenge the server holds is not the verifier's.
        val crooked = object : PkceCrypto {
            override fun randomBytes(count: Int) = ByteArray(count) { 3 }

            override fun sha256(bytes: ByteArray) = ByteArray(32) { 1 }
        }
        val flow = signIn(crooked)
        val redirect = pressContinue(flow.begin())

        val result = flow.complete(redirect, "provisional")

        assertIs<SignInResult.Failed>(result)
        assertTrue(vault.secrets.isEmpty(), "a refused exchange stored a token")
    }

    @Test
    fun `if Microsoft will not say who you are the token is not kept`() = runTest {
        val flow = signIn()
        val redirect = pressContinue(flow.begin())
        server.rejectAccessTokens = true

        val result = flow.complete(redirect, "provisional")

        val failed = assertIs<SignInResult.Failed>(result)
        assertTrue(failed.reason.contains("HTTP 401"), "the failure must say what Microsoft answered: ${failed.reason}")
        assertTrue(vault.secrets.isEmpty(), "an account with a token and no identity is an orphan")
        assertTrue(server.log.any { it.startsWith("vault put") } && server.log.contains("vault remove"))
    }
}
