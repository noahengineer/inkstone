package dev.inkstone.app.sync

import dev.inkstone.app.sync.matrix.MatrixClock
import dev.inkstone.core.sync.BearerResult
import dev.inkstone.core.sync.ForcedRefresh
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The token source's rules (docs/CLOUD-PROVIDERS.md §5.3) with a scripted [GoogleAuthorizer].
 *
 * ⚠️ A fake authorizer agrees with whatever this app believes Play Services does. Whether a granted
 * request really answers a token silently, and what `clearToken` really changes, is for a device.
 */
class GoogleAccessTokensTest {

    private val clock = MatrixClock()

    private class Scripted(var next: GoogleAuthorization) : GoogleAuthorizer {
        var asked = 0
        val cleared = mutableListOf<String>()

        override suspend fun authorize(accountEmail: String?): GoogleAuthorization {
            asked++
            return next
        }

        override fun fromConsentResult(data: Any?): GoogleAuthorization = next

        override suspend fun clearToken(token: String) {
            cleared += token
        }

        override suspend fun revoke(accountEmail: String?) = Unit
    }

    private fun tokens(
        first: GoogleAuthorization = GoogleAuthorization.Granted("t1"),
    ): Pair<Scripted, GoogleAccessTokens> {
        val authorizer = Scripted(first)
        return authorizer to GoogleAccessTokens(authorizer, "me@gmail.com", clock)
    }

    @Test
    fun `a token is reused until it is nearly spent`() = runTest {
        val (authorizer, source) = tokens()

        assertEquals(BearerResult.Token("t1"), source.bearer())
        assertEquals(BearerResult.Token("t1"), source.bearer())
        assertEquals(1, authorizer.asked, "a good token was asked for again")

        authorizer.next = GoogleAuthorization.Granted("t2")
        clock.advance(GoogleAccessTokens.TOKEN_LIFETIME_MILLIS + 1)

        assertEquals(BearerResult.Token("t2"), source.bearer())
        assertEquals(2, authorizer.asked)
    }

    @Test
    fun `a grant that needs a screen is dead and stays dead without asking again`() = runTest {
        val (authorizer, source) = tokens(GoogleAuthorization.NeedsConsent(Any()))

        assertIs<BearerResult.Dead>(source.bearer())
        authorizer.next = GoogleAuthorization.Granted("late")
        assertIs<BearerResult.Dead>(source.bearer())

        assertEquals(1, authorizer.asked, "a dead grant was asked about again")
    }

    @Test
    fun `not being able to reach google concludes nothing and is asked again`() = runTest {
        val (authorizer, source) = tokens(GoogleAuthorization.Failed("offline", retryable = true))

        assertIs<BearerResult.Unreachable>(source.bearer())
        authorizer.next = GoogleAuthorization.Granted("back")
        assertEquals(BearerResult.Token("back"), source.bearer())
    }

    @Test
    fun `a registration google refuses is dead because asking again cannot fix it`() = runTest {
        val (authorizer, source) = tokens(GoogleAuthorization.Failed("bad package", retryable = false))

        assertEquals(BearerResult.Dead("bad package"), source.bearer())
        assertIs<BearerResult.Dead>(source.bearer())
        assertEquals(1, authorizer.asked)
    }

    @Test
    fun `a refused token is cleared and replaced once and not again inside the cooldown`() = runTest {
        val (authorizer, source) = tokens()
        source.bearer()
        authorizer.next = GoogleAuthorization.Granted("t2")

        assertEquals(ForcedRefresh.Refreshed, source.refreshAfterRefusal())
        assertEquals(
            listOf("t1"),
            authorizer.cleared,
            "the refused token was not cleared, so Play Services would hand it back",
        )
        assertEquals(BearerResult.Token("t2"), source.bearer())

        val asked = authorizer.asked
        assertEquals(ForcedRefresh.CoolingDown, source.refreshAfterRefusal())
        assertEquals(asked, authorizer.asked, "a second forced refresh inside the cooldown asked Google")

        clock.advance(GoogleAccessTokens.FORCED_COOLDOWN_MILLIS + 1)
        authorizer.next = GoogleAuthorization.Granted("t3")
        assertEquals(ForcedRefresh.Refreshed, source.refreshAfterRefusal())
    }

    @Test
    fun `a refresh after the grant is gone is dead and asks nothing`() = runTest {
        val (authorizer, source) = tokens()
        source.bearer()
        authorizer.next = GoogleAuthorization.NeedsConsent(Any())
        assertIs<ForcedRefresh.Dead>(source.refreshAfterRefusal())

        val asked = authorizer.asked
        assertIs<ForcedRefresh.Dead>(source.refreshAfterRefusal())
        assertEquals(asked, authorizer.asked)
    }

    @Test
    fun `a new sign-in clears what said no and starts from the token just granted`() = runTest {
        val (authorizer, source) = tokens(GoogleAuthorization.NeedsConsent(Any()))
        source.bearer()

        source.reset(fresh = "fresh")

        assertEquals(BearerResult.Token("fresh"), source.bearer())
        assertEquals(1, authorizer.asked, "the token just granted should not be asked for again")
    }
}
