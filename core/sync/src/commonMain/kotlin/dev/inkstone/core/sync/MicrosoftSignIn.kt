package dev.inkstone.core.sync

import dev.inkstone.core.model.Clock
import io.ktor.client.call.body
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json as KJson

/*
 * docs/CLOUD-PROVIDERS.md §5.1 — signing in to Microsoft: authorization code + PKCE, in the user's own
 * browser. This is the half of OAuth that needs a person; [MicrosoftTokenSource] is what happens after.
 *
 * What lives in `:app` and not here: opening the Custom Tab, the Activity that receives the redirect, and
 * the Keystore-sealed files behind [TokenVault] and [PendingSignInStore]. This file is pure protocol, so
 * the parts that can be got wrong quietly — the PKCE challenge, the state check, *when the refresh token
 * is saved* — are testable without a device.
 *
 * Block comment, not KDoc, for the usual dangling-toplevel-KDoc reason (see SyncPorts.kt).
 */

/** The two things PKCE needs from a platform. Core has neither a secure random nor a SHA-256 it may export. */
public interface PkceCrypto {
    /** **Cryptographically secure** random bytes. `kotlin.random.Random` is not, and a PKCE verifier must be. */
    public fun randomBytes(count: Int): ByteArray

    public fun sha256(bytes: ByteArray): ByteArray
}

/**
 * A sign-in that has been started and not finished.
 *
 * It exists because the browser can outlive this process: Android may kill the app while the user is on
 * Microsoft's page, and the redirect then arrives at a process that remembers nothing. So the verifier
 * and the `state` are written **before** the URL is returned (the same persist-before-open rule as Login
 * Flow v2, SPEC §7.1 rule 3).
 *
 * @property expectedAccountId set when the user is signing in *again* to a known account, so that the
 *   caller can refuse a different Microsoft identity (docs §5.3) even after a process death.
 */
public data class PendingSignIn(
    val state: String,
    val codeVerifier: String,
    val startedAtEpochMillis: Long,
    val expectedAccountId: String? = null,
)

/** One slot: one sign-in at a time. Sealed at rest in `:app`. */
public interface PendingSignInStore {
    public suspend fun put(pending: PendingSignIn)

    /** Read **and delete**. A redirect can be delivered twice; an authorization code is good for one use. */
    public suspend fun take(): PendingSignIn?
}

public data class MicrosoftSignInConfig(
    val oauth: MicrosoftOAuthConfig,
    /** `msauth://<package>/<URL-encoded signature hash>` — what the Entra *Android* platform generates. */
    val redirectUri: String,
    val authorizeEndpoint: String = DEFAULT_AUTHORIZE_ENDPOINT,
    val graphBase: String = DEFAULT_GRAPH_BASE,
) {
    public companion object {
        public const val DEFAULT_AUTHORIZE_ENDPOINT: String =
            "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
        public const val DEFAULT_GRAPH_BASE: String = "https://graph.microsoft.com/v1.0"
    }
}

/** Who signed in, as Graph describes them. [userId] is the stable key — a mailbox name can change. */
public data class SignedInIdentity(
    val userId: String,
    val loginName: String,
    val displayName: String?,
    val driveId: String,
)

public sealed interface SignInResult {
    /** The refresh token is **already saved** under the account id the caller passed. */
    public data class Success(val identity: SignedInIdentity, val expectedAccountId: String?) : SignInResult

    /** The user said no. Not an error, and nothing is left behind. */
    public data class Cancelled(val reason: String) : SignInResult

    public data class Failed(val reason: String) : SignInResult
}

/**
 * Authorization-code + PKCE against the Microsoft identity platform (no client secret: public clients
 * must not use one, docs §5.1).
 *
 * ## Four rules, each a real bug if skipped
 *
 * 1. **Persist before opening.** [begin] writes the verifier and `state` first.
 * 2. **The pending record is single-use.** [complete] *takes* it, so a redirect delivered twice, or a
 *    forged one, finds nothing.
 * 3. **`state` must match.** A redirect carrying somebody else's `state` is refused before the code is
 *    looked at.
 * 4. **The refresh token is saved before anything uses the access token** — including this class's own
 *    two profile calls. Microsoft rotates it on every use, so what came back is the only copy.
 *
 * And nothing here retries: an authorization code is single-use, so a failed exchange is a failed
 * sign-in and the user presses the button again (CLAUDE.md rule 5's principle).
 */
public class MicrosoftSignIn(
    private val transport: NextcloudTransport,
    private val config: MicrosoftSignInConfig,
    private val vault: TokenVault,
    private val pending: PendingSignInStore,
    private val crypto: PkceCrypto,
    private val clock: Clock,
) {

    /** The URL for the Custom Tab. */
    public suspend fun begin(expectedAccountId: String? = null): String {
        val verifier = base64Url(crypto.randomBytes(VERIFIER_BYTES))
        val state = base64Url(crypto.randomBytes(STATE_BYTES))
        val challenge = base64Url(crypto.sha256(verifier.encodeToByteArray()))
        // Rule 1. If the process dies on the next line, the redirect still finds its verifier.
        pending.put(PendingSignIn(state, verifier, clock.nowEpochMillis(), expectedAccountId))

        val url = URLBuilder(config.authorizeEndpoint)
        url.parameters.apply {
            append("client_id", config.oauth.clientId)
            append("response_type", "code")
            append("redirect_uri", config.redirectUri)
            append("response_mode", "query")
            append("scope", config.oauth.scope)
            append("state", state)
            append("code_challenge", challenge)
            append("code_challenge_method", "S256")
            // The account picker, not a silent reuse: this tablet may hold more than one Microsoft login.
            append("prompt", "select_account")
        }
        return url.buildString()
    }

    /**
     * Finish a sign-in from the URL the browser was redirected to.
     *
     * @param accountId where the refresh token is saved. The caller's *provisional* id: the identity is
     *   only known afterwards, and the caller then either keeps it or moves the secret onto the account
     *   it turns out to be (the same two-step as Nextcloud's `adopt`).
     */
    public suspend fun complete(redirect: String, accountId: String): SignInResult {
        val saved = pending.take()
            ?: return SignInResult.Failed("No sign-in was in progress, or this one was already used.")
        return when (val checked = check(redirect, saved)) {
            is Checked.Refused -> checked.result
            is Checked.Code -> exchange(checked.code, saved, accountId)
        }
    }

    private sealed interface Checked {
        data class Code(val code: String) : Checked

        data class Refused(val result: SignInResult) : Checked
    }

    @Suppress("ReturnCount")
    // Each return is a different reason the redirect cannot be believed, and the order is the point.
    private fun check(redirect: String, saved: PendingSignIn): Checked {
        if (clock.nowEpochMillis() - saved.startedAtEpochMillis > PENDING_LIFETIME_MILLIS) {
            return Checked.Refused(SignInResult.Failed("That sign-in took too long. Start it again."))
        }
        if (!redirect.startsWith(config.redirectUri)) {
            return Checked.Refused(SignInResult.Failed("That was not the redirect Inkstone asked for."))
        }
        val params = parametersOf(redirect)
            ?: return Checked.Refused(SignInResult.Failed("The sign-in redirect could not be read."))
        // Rule 3, before the code is so much as looked at.
        if (params["state"] != saved.state) {
            return Checked.Refused(SignInResult.Failed("The sign-in redirect did not match the one that was started."))
        }
        params["error"]?.let { error ->
            val detail = params["error_description"] ?: error
            return Checked.Refused(
                if (error == "access_denied") SignInResult.Cancelled(detail) else SignInResult.Failed(detail),
            )
        }
        val code = params["code"] ?: return Checked.Refused(SignInResult.Failed("Microsoft sent no sign-in code."))
        return Checked.Code(code)
    }

    private fun parametersOf(redirect: String): Parameters? = try {
        Url(redirect).parameters
    } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") unreadable: Exception) {
        null
    }

    @Suppress("ReturnCount")
    private suspend fun exchange(code: String, saved: PendingSignIn, accountId: String): SignInResult {
        val tokens = when (val answer = tokenRequest(code, saved.codeVerifier)) {
            is TokenAnswer.Refused -> return SignInResult.Failed(answer.reason)
            is TokenAnswer.Issued -> answer
        }
        // Rule 4: durable before anything — including the two calls just below — uses the access token.
        vault.put(accountId, OAuthSecret(tokens.refreshToken))

        val identity = when (val found = identityOf(tokens.accessToken)) {
            is IdentityAnswer.Found -> found.identity

            is IdentityAnswer.Missing -> {
                // Nothing is left behind: a half-made account with a token and no identity is the orphan
                // the Nextcloud flow's `cleanUp` exists for.
                vault.remove(accountId)
                return SignInResult.Failed(
                    "Signed in, but Microsoft would not say who you are (${found.why}). Try again.",
                )
            }
        }
        return SignInResult.Success(identity, saved.expectedAccountId)
    }

    private sealed interface TokenAnswer {
        data class Issued(val accessToken: String, val refreshToken: String) : TokenAnswer

        data class Refused(val reason: String) : TokenAnswer
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    // The engine's exception types differ per platform; a failed exchange is a failed sign-in either way.
    // Cancellation is rethrown so a cancelled screen does not look like a refusal.
    private suspend fun tokenRequest(code: String, verifier: String): TokenAnswer {
        val response: HttpResponse = try {
            transport.client.post(config.oauth.tokenEndpoint) {
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("client_id", config.oauth.clientId)
                            append("grant_type", "authorization_code")
                            append("code", code)
                            append("redirect_uri", config.redirectUri)
                            append("code_verifier", verifier)
                            append("scope", config.oauth.scope)
                        },
                    ),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return TokenAnswer.Refused("Could not reach Microsoft: ${failure.message ?: failure}")
        }
        val body = parse(response.bodyAsText())
        if (response.status.value != HTTP_OK) {
            val why = body.text("error_description") ?: body.text("error") ?: "HTTP ${response.status.value}"
            return TokenAnswer.Refused("Microsoft refused the sign-in: ${why.lineSequence().first()}")
        }
        val access = body.text("access_token")
        val refresh = body.text("refresh_token")
        if (access == null) return TokenAnswer.Refused("Microsoft's reply had no access token.")
        // Without `offline_access` there is no refresh token, and a session that ends in an hour is not a sync.
        if (refresh == null) {
            return TokenAnswer.Refused("Microsoft issued no refresh token, so Inkstone could not stay signed in.")
        }
        return TokenAnswer.Issued(access, refresh)
    }

    private sealed interface IdentityAnswer {
        data class Found(val identity: SignedInIdentity) : IdentityAnswer

        /** [why] names the call and what came back, so a refusal is diagnosable from the message alone. */
        data class Missing(val why: String) : IdentityAnswer
    }

    private suspend fun identityOf(accessToken: String): IdentityAnswer {
        val me = getJson("${config.graphBase}/me?\$select=id,displayName,mail,userPrincipalName", accessToken, "/me")
        val meBody = me.body ?: return IdentityAnswer.Missing(me.why)
        val drive = getJson("${config.graphBase}/me/drive?\$select=id", accessToken, "/me/drive")
        val driveBody = drive.body ?: return IdentityAnswer.Missing(drive.why)
        val userId = meBody.text("id") ?: return IdentityAnswer.Missing("/me had no id")
        val driveId = driveBody.text("id") ?: return IdentityAnswer.Missing("/me/drive had no id")
        val name = meBody.text("mail") ?: meBody.text("userPrincipalName") ?: meBody.text("displayName") ?: userId
        return IdentityAnswer.Found(SignedInIdentity(userId, name, meBody.text("displayName"), driveId))
    }

    private class Fetched(val body: JsonObject?, val why: String)

    @Suppress("TooGenericExceptionCaught")
    private suspend fun getJson(url: String, accessToken: String, label: String): Fetched = try {
        val response = transport.client.get(url) { header("Authorization", "Bearer $accessToken") }
        val parsed = if (response.status.value ==
            HTTP_OK
        ) {
            parse(response.body<String>()).takeIf { it.isNotEmpty() }
        } else {
            null
        }
        Fetched(parsed, "$label answered HTTP ${response.status.value}")
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Fetched(null, "$label could not be reached: ${failure.message ?: failure}")
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun parse(text: String): JsonObject = try {
        KJson.parseToJsonElement(text).jsonObject
    } catch (ignored: Exception) {
        JsonObject(emptyMap())
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun base64Url(bytes: ByteArray): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)

    private companion object {
        const val HTTP_OK = 200

        /** 32 random bytes encode to 43 characters — RFC 7636's minimum verifier length. */
        const val VERIFIER_BYTES = 32
        const val STATE_BYTES = 16

        /** Long enough for a slow second factor, short enough that a stale record cannot be replayed next week. */
        const val PENDING_LIFETIME_MILLIS = 15L * 60 * 1000
    }
}
