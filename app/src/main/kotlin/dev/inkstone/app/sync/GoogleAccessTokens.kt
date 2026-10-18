package dev.inkstone.app.sync

import dev.inkstone.core.model.Clock
import dev.inkstone.core.sync.AccessTokenSource
import dev.inkstone.core.sync.BearerResult
import dev.inkstone.core.sync.ForcedRefresh
import dev.inkstone.core.sync.SyncAccount
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One Google account's access token, kept fresh from Play Services — the Drive counterpart of
 * `MicrosoftTokenSource`, under the same rules (docs/CLOUD-PROVIDERS.md §5.3), minus the refresh token:
 * Google's Android guidance keeps none on the device, so there is **nothing to rotate and nothing to lose
 * between the response and the write**. What replaces it is that Play Services decides, silently, whether
 * the grant still stands.
 *
 * - **Before sending**, with no cached token (or a spent one): [bearer] asks Play Services. Not a retry —
 *   nothing has been refused.
 * - **A grant that needs a screen is [BearerResult.Dead] and sticky.** Background work cannot show one, and
 *   asking again would be the same question; only a sign-in ([reset]) clears it.
 * - **After a 401**: at most one fresh token, and not again inside [FORCED_COOLDOWN_MILLIS]
 *   ([refreshAfterRefusal]). The refused token is cleared in Play Services first, or it would hand the same
 *   one back.
 * - **Cannot reach Google / Play Services is busy** is [BearerResult.Unreachable] and concludes nothing —
 *   treating unreachable as revoked signs the user out every time they walk into a lift.
 * - **A registration Google refuses** (package/SHA-1) is dead too, with its own message, because asking
 *   again cannot fix it.
 *
 * The cooldown and the dead flag live in memory, unlike Microsoft's persisted cooldown: there is no
 * credential here whose replay could lock anybody out, and a restarted process asking Play Services once
 * is exactly what a launch should do.
 */
class GoogleAccessTokens(
    private val authorizer: GoogleAuthorizer,
    private val accountEmail: String?,
    private val clock: Clock,
) : AccessTokenSource {

    private val lock = Mutex()
    private var cached: String? = null
    private var cachedUntil: Long = 0L
    private var deadReason: String? = null
    private var lastForcedAt: Long = 0L

    override suspend fun bearer(): BearerResult = lock.withLock {
        deadReason?.let { return@withLock BearerResult.Dead(it) }
        val token = cached
        if (token != null && clock.nowEpochMillis() < cachedUntil) return@withLock BearerResult.Token(token)
        askLocked()
    }

    override suspend fun refreshAfterRefusal(): ForcedRefresh = lock.withLock {
        deadReason?.let { return@withLock ForcedRefresh.Dead(it) }
        val now = clock.nowEpochMillis()
        if (lastForcedAt > 0L && now - lastForcedAt < FORCED_COOLDOWN_MILLIS) return@withLock ForcedRefresh.CoolingDown
        lastForcedAt = now
        cached?.let { authorizer.clearToken(it) }
        cached = null
        when (val result = askLocked()) {
            is BearerResult.Token -> ForcedRefresh.Refreshed
            is BearerResult.Dead -> ForcedRefresh.Dead(result.reason)
            is BearerResult.Unreachable -> ForcedRefresh.Unreachable(result.reason)
        }
    }

    /** A new sign-in: forget everything that said no, and start from this token if one is given. */
    suspend fun reset(fresh: String? = null) {
        lock.withLock {
            deadReason = null
            lastForcedAt = 0L
            cached = fresh
            cachedUntil = if (fresh != null) clock.nowEpochMillis() + TOKEN_LIFETIME_MILLIS else 0L
        }
    }

    private suspend fun askLocked(): BearerResult = when (val answer = authorizer.authorize(accountEmail)) {
        is GoogleAuthorization.Granted -> {
            cached = answer.accessToken
            cachedUntil = clock.nowEpochMillis() + TOKEN_LIFETIME_MILLIS
            BearerResult.Token(answer.accessToken)
        }

        is GoogleAuthorization.NeedsConsent -> dead(MISSING_GRANT_REASON)

        is GoogleAuthorization.Failed ->
            if (answer.retryable) BearerResult.Unreachable(answer.reason) else dead(answer.reason)
    }

    private fun dead(reason: String): BearerResult.Dead {
        deadReason = reason
        cached = null
        return BearerResult.Dead(reason)
    }

    companion object {
        /** Google's access tokens last an hour; a little under, so one is never sent as it expires. */
        const val TOKEN_LIFETIME_MILLIS = 50L * 60 * 1000

        /** A second forced refresh straight after one that worked means the credential is the problem. */
        const val FORCED_COOLDOWN_MILLIS = 5L * 60 * 1000

        /** Shown by the Sync screen. Nobody refused anything: Google simply no longer lists this app's permission. */
        const val MISSING_GRANT_REASON: String = "Google no longer lists Inkstone's permission for this account."
    }
}

/**
 * Hands each Google account its [GoogleAccessTokens] for the life of the process, so a token is reused
 * across drain passes and the 401 cooldown is one clock per account rather than one per store.
 *
 * Separate from [GoogleDriveAccounts] on purpose: the store factory needs this, and the accounts class
 * needs the store factory's siblings — one class doing both would be a dependency cycle.
 */
@javax.inject.Singleton
class GoogleTokenRegistry @javax.inject.Inject constructor(
    private val authorizer: GoogleAuthorizer,
    private val clock: Clock,
) {
    private val lock = Mutex()
    private val sources = HashMap<String, GoogleAccessTokens>()

    suspend fun tokensFor(account: SyncAccount): GoogleAccessTokens = lock.withLock {
        sources.getOrPut(account.id) { GoogleAccessTokens(authorizer, account.loginName, clock) }
    }

    /** After a sign-in or a sign-out: a new start, optionally from a token just granted. */
    suspend fun reset(account: SyncAccount, fresh: String? = null) {
        tokensFor(account).reset(fresh)
    }

    suspend fun forget(accountId: String) {
        lock.withLock { sources.remove(accountId) }
    }
}
