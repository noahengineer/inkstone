package dev.inkstone.core.sync

import dev.inkstone.core.model.Clock
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json as KJson

/*
 * docs/CLOUD-PROVIDERS.md §5 — OAuth for Microsoft, as plain HTTPS over Ktor.
 *
 * Only the *refresh* half lives here. Getting the first token — the Custom Tab, the PKCE verifier,
 * the redirect activity — is Android's and is built with the sign-in UI, where it can be walked on a
 * device. Everything below is what happens after a refresh token exists, and it is where CLAUDE.md
 * rule 5's analogue has to hold.
 *
 * Block comment, not KDoc, for the usual dangling-toplevel-KDoc reason (see SyncPorts.kt).
 */

/**
 * What survives a process death for one OAuth account. **Not the access token** — it is an hour old
 * at best and cheap to ask for again, and leaving it out of the file leaves it out of every backup.
 *
 * @property forcedRefreshAtEpochMillis when a refresh was last made *because the server said 401*
 *   rather than because the token was nearly spent. Persisted, because a cooldown cleared by every
 *   process restart is not a cooldown — WorkManager restarts the process (SPEC §7.6, `refusedUntil`).
 */
public data class OAuthSecret(val refreshToken: String, val forcedRefreshAtEpochMillis: Long = 0L)

/**
 * Where [OAuthSecret]s live. The Android implementation is Keystore AES-GCM in the same envelope as
 * the Nextcloud credential (SPEC §14, **not** `EncryptedSharedPreferences`); it is written with the
 * sign-in UI because it needs a device to verify.
 *
 * ⚠️ [put] must be durable when it returns: a refresh token **rotates on every use**, so the one this
 * replaces may already be spent (docs §5.1).
 */
public interface TokenVault {
    public suspend fun get(accountId: String): OAuthSecret?

    public suspend fun put(accountId: String, secret: OAuthSecret)

    public suspend fun remove(accountId: String)
}

/** Which app registration to present, and where to present it. */
public data class MicrosoftOAuthConfig(
    val clientId: String,
    val tokenEndpoint: String = DEFAULT_TOKEN_ENDPOINT,
    val scope: String = DEFAULT_SCOPE,
) {
    public companion object {
        /** `common`: personal Microsoft accounts and work/school accounts both sign in. */
        public const val DEFAULT_TOKEN_ENDPOINT: String = "https://login.microsoftonline.com/common/oauth2/v2.0/token"

        /**
         * docs §5.1 — `Files.ReadWrite` (D3), `offline_access` or there is no refresh token, and `User.Read`:
         * without it `GET /me` is refused, so a sign-in that succeeded cannot say *who* signed in (found on the
         * first real sign-in; the stand-in answered `/me` for any token and could not have shown it).
         */
        public const val DEFAULT_SCOPE: String = "User.Read Files.ReadWrite offline_access"
    }
}

/** An access token to send, or the reason there is none. Never an exception. */
public sealed interface BearerResult {
    public data class Token(val value: String) : BearerResult

    /** The refresh token is gone or was refused (`invalid_grant`). Re-sign-in is the only way back. */
    public data class Dead(val reason: String) : BearerResult

    /** The token endpoint could not be reached or misbehaved. **Concludes nothing** about the account. */
    public data class Unreachable(val reason: String) : BearerResult
}

/** The outcome of the one refresh a 401 is allowed to cause. See [MicrosoftTokenSource.refreshAfterRefusal]. */
public sealed interface ForcedRefresh {
    public data object Refreshed : ForcedRefresh

    public data class Dead(val reason: String) : ForcedRefresh

    public data class Unreachable(val reason: String) : ForcedRefresh

    /** A forced refresh already happened recently. A second means the first did not help. */
    public data object CoolingDown : ForcedRefresh
}

/**
 * One account's access token, kept fresh.
 *
 * ## When a refresh is allowed (docs §5.3)
 *
 * A refresh is a request to the **token endpoint**, not to the resource server, so it is not
 * "retrying a credential against the thing that rejected it". It is still bounded:
 *
 * - **Before sending**, when there is no token or it has under five minutes left: [bearer]. Not a
 *   retry — nothing has been refused.
 * - **After a 401** with a token the clock says is still good: at most **one**, and not again inside
 *   [FORCED_COOLDOWN_MILLIS] — [refreshAfterRefusal]. The cooldown is persisted in the [TokenVault].
 * - **`invalid_grant`** is [BearerResult.Dead] at once and **never retried**.
 * - **Cannot reach the endpoint** is [BearerResult.Unreachable]: nothing is concluded, because
 *   treating unreachable as revoked signs the user out every time they walk into a lift
 *   (the same reason `confirmStillAuthorised` answers null).
 *
 * ## The rotation rule
 *
 * Microsoft issues a new refresh token with every new access token. The new one is written to the
 * vault **before** the access token is cached or used — if the process dies between the response and
 * the write, the old token may already be spent, so the order is the whole defence.
 *
 * One instance per account **for the life of the process** ([OneDriveStoreFactory] keeps them), so an
 * hour-long access token is reused across drain passes instead of costing a refresh — and a token
 * rotation — every sixty seconds.
 */
public class MicrosoftTokenSource(
    private val transport: NextcloudTransport,
    private val config: MicrosoftOAuthConfig,
    private val vault: TokenVault,
    private val clock: Clock,
    private val accountId: String,
) {

    private val lock = Mutex()
    private var accessToken: String? = null
    private var accessExpiresAt: Long = 0L

    /**
     * Set once the grant is refused, and **cleared only by a new sign-in** (a new instance — see
     * [OneDriveStoreFactory.forget]). While it is set no request of any kind is made: asking the
     * token endpoint again about a grant it just called dead is a credential retry, which is what
     * `invalid_grant` exists to stop.
     */
    private var deadReason: String? = null

    /** A token to send, refreshing first only if it is missing or nearly spent. */
    public suspend fun bearer(): BearerResult = lock.withLock {
        deadReason?.let { return@withLock BearerResult.Dead(it) }
        val cached = accessToken
        if (cached != null && clock.nowEpochMillis() < accessExpiresAt - REFRESH_MARGIN_MILLIS) {
            return@withLock BearerResult.Token(cached)
        }
        refreshLocked()
    }

    /**
     * The single refresh a 401 may cause. Call it from `confirmStillAuthorised`, never from a retry.
     *
     * Throws the cached token away first: the server just told us it is not good, and handing it out
     * again from [bearer] would be the same-request replay rule 5 forbids.
     */
    public suspend fun refreshAfterRefusal(): ForcedRefresh = lock.withLock {
        deadReason?.let { return@withLock ForcedRefresh.Dead(it) }
        val now = clock.nowEpochMillis()
        val secret = vault.get(accountId) ?: return@withLock ForcedRefresh.Dead(NO_REFRESH_TOKEN)
        if (secret.forcedRefreshAtEpochMillis > 0L &&
            now - secret.forcedRefreshAtEpochMillis < FORCED_COOLDOWN_MILLIS
        ) {
            return@withLock ForcedRefresh.CoolingDown
        }
        accessToken = null
        // Written BEFORE the request: a process killed mid-refresh still counts as the attempt.
        vault.put(accountId, secret.copy(forcedRefreshAtEpochMillis = now))
        when (val result = refreshLocked()) {
            is BearerResult.Token -> ForcedRefresh.Refreshed
            is BearerResult.Dead -> ForcedRefresh.Dead(result.reason)
            is BearerResult.Unreachable -> ForcedRefresh.Unreachable(result.reason)
        }
    }

    /** The cached token, or null. Used by the store after a refresh it just caused. */
    public suspend fun current(): String? = lock.withLock { accessToken }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    // Each return is a distinct answer to "what did the token endpoint say". A transport failure is
    // reported rather than thrown (CLAUDE.md: sealed results across the module boundary), and the
    // engine's exception types differ per platform, so the catch is deliberately broad; cancellation
    // is rethrown so a cancelled worker does not look like an unreachable server.
    private suspend fun refreshLocked(): BearerResult {
        val secret =
            vault.get(accountId) ?: return BearerResult.Dead(NO_REFRESH_TOKEN).also { deadReason = NO_REFRESH_TOKEN }

        val response = try {
            transport.client.post(config.tokenEndpoint) {
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("client_id", config.clientId)
                            append("grant_type", "refresh_token")
                            append("refresh_token", secret.refreshToken)
                            append("scope", config.scope)
                        },
                    ),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return BearerResult.Unreachable(failure.message ?: failure.toString())
        }

        val status = response.status.value
        val body = parseObject(response.bodyAsText())
        if (status == HTTP_OK) {
            val access = body.string("access_token")
            val seconds = body["expires_in"]?.jsonPrimitive?.longOrNull
            if (access == null || seconds == null) return BearerResult.Unreachable(MALFORMED_TOKEN_RESPONSE)
            // ⭐ The rotation rule: the new refresh token is durable before the access token exists
            // anywhere a request could use it. A reply with no new refresh token keeps the old one.
            vault.put(
                accountId,
                secret.copy(refreshToken = body.string("refresh_token") ?: secret.refreshToken),
            )
            accessToken = access
            accessExpiresAt = clock.nowEpochMillis() + seconds * MILLIS_PER_SECOND
            return BearerResult.Token(access)
        }

        val error = body.string("error")
        return if (error in DEAD_GRANT_ERRORS) {
            // Never retried — and never mistaken for a transient: a refused grant stays refused.
            accessToken = null
            deadReason = error ?: "refused"
            BearerResult.Dead(deadReason.orEmpty())
        } else {
            BearerResult.Unreachable("token endpoint answered $status${error?.let { " ($it)" }.orEmpty()}")
        }
    }

    private fun parseObject(text: String): JsonObject = try {
        KJson.parseToJsonElement(text).jsonObject
    } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") ignored: Exception) {
        // An HTML error page from a proxy is not a protocol answer; an empty object reads as one with
        // no `error`, which is "unreachable", not "dead".
        JsonObject(emptyMap())
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val HTTP_OK = 200
        const val MILLIS_PER_SECOND = 1000L
        const val REFRESH_MARGIN_MILLIS = 5L * 60 * 1000
        const val FORCED_COOLDOWN_MILLIS = 15L * 60 * 1000
        const val NO_REFRESH_TOKEN = "no refresh token is stored for this account"
        const val MALFORMED_TOKEN_RESPONSE = "the token endpoint's reply could not be read"

        /** Answers that mean the grant itself is finished, as opposed to "try again later". */
        val DEAD_GRANT_ERRORS = setOf("invalid_grant", "interaction_required", "consent_required", "invalid_client")
    }
}
