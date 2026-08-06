package dev.inkstone.core.sync

import dev.inkstone.core.model.NextcloudCredential
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * SPEC §7.1 — Login Flow v2, with all six rules.
 *
 * ```
 * POST <server>/index.php/login/v2          (anonymous, no body)
 *   → { poll: { token, endpoint }, login: <browser URL> }
 *
 * open `login` in a Custom Tab; the user authenticates and approves
 *
 * POST <poll.endpoint>  body: token=<pollToken>   every 2–5 s
 *   → 404  while pending
 *   → 200  { server, loginName, appPassword }     ONCE, then the row is deleted
 * ```
 *
 * ## The six rules, and where each one lives
 *
 * 1. **Never hardcode the poll URL.** [LoginFlowStart.pollEndpoint] is POSTed to
 *    verbatim. The server builds it with `linkToRouteAbsolute`, so `/index.php` may or
 *    may not be in it, and reconstructing it works against your server and 404s
 *    against somebody else's.
 * 2. **404 is overloaded** — "not ready", "invalid" and "expired" are indistinguishable.
 *    So the client runs [LIFETIME_SECONDS] itself rather than waiting for a signal that
 *    does not exist.
 * 3. **Persist before acknowledging the 200.** The server deletes its row the moment it
 *    answers; a crash between the response and the write is unrecoverable, and the user
 *    is left with an app password in their security settings that nothing holds. This is
 *    why [awaitApproval] takes a `persist` lambda and calls it *before* it returns, and
 *    why it persists with an empty `uid` and fills that in afterwards (see below).
 * 4. **A human-readable `User-Agent`** — [INKSTONE_USER_AGENT], installed on the client
 *    in `createNextcloudHttpClient`. It becomes the app password's display name in the
 *    user's security settings; leaving Ktor's default there is how other clients end up
 *    listed as "okhttp".
 * 5. ⚠️ **An admin can restrict `core.login_flow_v2.allowed_user_agents`** with a PCRE.
 *    No match and the browser 403s while the client polls 404 forever with no signal —
 *    so [LoginFlowResult.TimedOut] names that possibility in as many words.
 * 6. **Fetch the UID** from `/ocs/v2.php/cloud/user` → `ocs.data.id`. That, not
 *    `loginName`, is the WebDAV path segment (SPEC §0.1 #3). [OcsClient.fetchUid].
 *
 * ## Why the credential is persisted twice
 *
 * Rule 3 and rule 6 pull in opposite directions: the app password must be written the
 * instant it arrives, and the uid can only be fetched *using* it, over the network,
 * which can fail. So the write happens twice — once with `uid = ""` (rule 3 satisfied,
 * nothing lost), then again once the uid is known. An account whose stored credential
 * has an empty uid is resumable: [resolveUid] finishes the job on the next launch.
 * Nothing may use such a credential for WebDAV in the meantime, which
 * [NextcloudSession] enforces.
 */
public class LoginFlowV2(transport: NextcloudTransport, private val ocs: OcsClient = OcsClient(transport)) {

    private val http = transport.client

    /** SPEC §7.1 rule 2 — the server-side lifetime of a login-flow token. */
    public val lifetimeSeconds: Long get() = LIFETIME_SECONDS

    /**
     * `POST <server>/index.php/login/v2`.
     *
     * @param serverUrl already run through [NextcloudUrls.normaliseServer].
     */
    @Suppress("TooGenericExceptionCaught")
    // Same reasoning as WebDavClient.callRaw: Ktor's transport exception types differ
    // per platform, so a narrower catch would compile and miss the failure on one of them.
    public suspend fun begin(serverUrl: String): LoginFlowResult<LoginFlowStart> {
        val normalised = NextcloudUrls.normaliseServer(serverUrl)
        if (!NextcloudUrls.isSecure(normalised)) return LoginFlowResult.Insecure(normalised)

        return try {
            val response = http.post(NextcloudUrls.loginFlowStart(normalised)) {
                // Nextcloud answers 404 to a GET here and only accepts POST. No body at all —
                // sending one is what makes some reverse proxies 411 the request.
                header("Accept", "application/json")
            }
            if (response.status.value !in SUCCESS_FLOOR until REDIRECT_FLOOR) {
                return LoginFlowResult.Failed("The server refused to start a login (HTTP ${response.status.value}).")
            }
            val payload = json.decodeFromString<StartPayload>(response.bodyAsText())
            LoginFlowResult.Success(
                LoginFlowStart(
                    serverUrl = normalised,
                    browserUrl = payload.login,
                    // Rule 1 — kept whole, never rebuilt.
                    pollEndpoint = payload.poll.endpoint,
                    pollToken = payload.poll.token,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            LoginFlowResult.Failed(failure.message ?: "Could not reach $normalised.")
        }
    }

    /**
     * Poll until the user approves, the clock runs out, or something breaks.
     *
     * @param persist called with the credential **before this function returns**
     *   (rule 3). It is called twice: once the instant the app password arrives, with
     *   an empty `uid`, and again once the uid has been fetched. A caller that only
     *   honours the second call has reintroduced the unrecoverable window.
     * @param now a monotonic-enough millisecond source for the client-side timeout.
     *   Wall clock is acceptable here and nowhere else in this module: this is a
     *   20-minute human deadline, not an ordering (SPEC §4.1).
     */
    public suspend fun awaitApproval(
        start: LoginFlowStart,
        persist: suspend (NextcloudCredential) -> Unit,
        now: () -> Long,
    ): LoginFlowResult<NextcloudCredential> {
        val deadline = now() + LIFETIME_SECONDS * MILLIS_PER_SECOND
        while (true) {
            when (val attempt = pollOnce(start)) {
                is PollOutcome.Approved -> return finish(attempt.credential, persist)
                is PollOutcome.Pending -> Unit
                is PollOutcome.Broken -> return LoginFlowResult.Failed(attempt.reason)
            }
            if (now() >= deadline) return LoginFlowResult.TimedOut(TIMEOUT_ADVICE)
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    /**
     * One poll. Exposed so a UI can drive its own loop and show progress.
     *
     * Rule 2: a 404 is [PollOutcome.Pending] and nothing more can be read into it.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    // The exception is deliberately discarded rather than reported: a dropped connection
    // mid-flow is not a failure of the login, the token is still valid on the server, and
    // surfacing "Connection reset" to a user who is halfway through approving in a browser
    // would be noise about something that fixed itself. The client-side deadline in
    // [awaitApproval] is what ends the loop, per SPEC §7.1 rule 2.
    public suspend fun pollOnce(start: LoginFlowStart): PollOutcome = try {
        val response = http.submitForm(
            url = start.pollEndpoint,
            formParameters = Parameters.build { append("token", start.pollToken) },
        )
        when (response.status.value) {
            in SUCCESS_FLOOR until REDIRECT_FLOOR -> {
                val payload = json.decodeFromString<PollPayload>(response.bodyAsText())
                PollOutcome.Approved(
                    NextcloudCredential(
                        // The server's own idea of its URL wins: it is what the app password
                        // was issued against, and it may differ from what the user typed
                        // (a trailing /nextcloud, or a canonical host behind a redirect).
                        serverUrl = NextcloudUrls.normaliseServer(payload.server),
                        loginName = payload.loginName,
                        uid = "",
                        appPassword = payload.appPassword,
                    ),
                )
            }

            NOT_FOUND -> PollOutcome.Pending

            else -> PollOutcome.Broken("The server answered HTTP ${response.status.value} while polling.")
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        PollOutcome.Pending
    }

    /** Rule 3 then rule 6, in that order. See the class KDoc. */
    private suspend fun finish(
        raw: NextcloudCredential,
        persist: suspend (NextcloudCredential) -> Unit,
    ): LoginFlowResult<NextcloudCredential> {
        persist(raw)
        return when (val resolved = resolveUid(raw)) {
            null -> LoginFlowResult.NeedsUid(raw)

            else -> {
                persist(resolved)
                LoginFlowResult.Success(resolved)
            }
        }
    }

    /**
     * SPEC §7.1 rule 6 / §0.1 #3 — fill in the WebDAV path segment.
     *
     * Also the repair path for a credential persisted with an empty uid because the
     * process died between rule 3's write and this call.
     *
     * @return the credential with its uid, or null when the server could not be asked.
     */
    public suspend fun resolveUid(credential: NextcloudCredential): NextcloudCredential? {
        val uid = ocs.fetchUid(credential).valueOrNull ?: return null
        return credential.copy(uid = uid)
    }

    /** What one poll said. See rule 2 on why there is no "invalid" here. */
    public sealed interface PollOutcome {
        public data class Approved(val credential: NextcloudCredential) : PollOutcome

        /** 404 — indistinguishable from "invalid" and "expired". Keep waiting. */
        public data object Pending : PollOutcome

        public data class Broken(val reason: String) : PollOutcome
    }

    private companion object {
        /** SPEC §7.1 rule 2 — "server-side lifetime is 1200 s". */
        const val LIFETIME_SECONDS = 1200L
        const val POLL_INTERVAL_MILLIS = 3000L
        const val MILLIS_PER_SECOND = 1000L
        const val SUCCESS_FLOOR = 200
        const val REDIRECT_FLOOR = 300
        const val NOT_FOUND = 404

        /** SPEC §7.1 rule 5 — the failure mode that otherwise looks like nothing at all. */
        const val TIMEOUT_ADVICE =
            "The login was not approved in time. If you did approve it, the server may restrict which " +
                "apps can use this login method (core.login_flow_v2.allowed_user_agents) — ask the " +
                "administrator to allow \"$INKSTONE_USER_AGENT\"."

        val json = Json { ignoreUnknownKeys = true }
    }
}

/** What `POST /index.php/login/v2` returned. [pollEndpoint] is used verbatim (rule 1). */
public data class LoginFlowStart(
    val serverUrl: String,
    /** Open this in a Custom Tab. */
    val browserUrl: String,
    val pollEndpoint: String,
    val pollToken: String,
)

public sealed interface LoginFlowResult<out T> {

    public data class Success<out T>(val value: T) : LoginFlowResult<T>

    /** SPEC §14 — HTTPS only. No "accept anyway", so this is terminal. */
    public data class Insecure(val serverUrl: String) : LoginFlowResult<Nothing>

    /** SPEC §7.1 rule 2 + rule 5. [advice] names the admin-restriction possibility. */
    public data class TimedOut(val advice: String) : LoginFlowResult<Nothing>

    /**
     * The app password arrived and **has been persisted**, but the uid could not be
     * fetched. Recoverable: the account exists and [LoginFlowV2.resolveUid] finishes it.
     */
    public data class NeedsUid(val credential: NextcloudCredential) : LoginFlowResult<Nothing>

    public data class Failed(val reason: String) : LoginFlowResult<Nothing>
}

@Serializable
private data class StartPayload(val poll: PollLocation, val login: String)

@Serializable
private data class PollLocation(val token: String, val endpoint: String)

@Serializable
private data class PollPayload(val server: String, val loginName: String, val appPassword: String)
