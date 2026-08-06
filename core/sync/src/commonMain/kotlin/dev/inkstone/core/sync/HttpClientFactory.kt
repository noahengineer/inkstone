package dev.inkstone.core.sync

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.UserAgent

/**
 * SPEC §7.1 rule 4 — this string becomes the app password's display name in the
 * user's Nextcloud security settings. Leaving Ktor's default there is how other
 * clients end up listed as "okhttp", which is unreadable and unrevokable-by-name.
 *
 * ⚠️ SPEC §7.1 rule 5: an admin can restrict `core.login_flow_v2.allowed_user_agents`
 * with a PCRE. No match and the browser 403s while the client polls 404 forever
 * with no signal — so M5's timeout message must name this possibility.
 */
public const val INKSTONE_USER_AGENT: String = "Inkstone/1.0 (Android)"

/**
 * The engine is the only platform-specific part of the HTTP stack: OkHttp on
 * Android, Darwin on iOS. Everything above it is shared.
 */
internal expect fun platformHttpClient(configure: HttpClientConfig<*>.() -> Unit = {}): HttpClient

/**
 * The app's one HTTP connection to Nextcloud — **and the reason `:app` never sees a
 * Ktor type.**
 *
 * CLAUDE.md: *"Third-party types don't leak across module boundaries. `dav4jvm` stays
 * inside `:core:sync`."* The same applies to Ktor, and it applies harder here than
 * anywhere else in the project: `:app` is Android-only, so nothing about it would
 * complain if it started holding an `HttpClient` and, three milestones later, making a
 * request outside the outbox (CLAUDE.md rule 4). This wrapper is opaque from outside the
 * module, so there is nothing in `:app` to make a request *with*.
 *
 * Added in M5 after Hilt asked `:app` to name the return type of a provider and the
 * honest answer turned out to be "a type `:app` should not be able to name".
 */
public class NextcloudTransport internal constructor(internal val client: HttpClient) {

    /** Shut the connection pool down. Called when the last account is signed out. */
    public fun close() {
        client.close()
    }

    public companion object {
        /**
         * Deliberately no retry plugin. SPEC §7.2: a 401 halts the worker and flags
         * `NEEDS_REAUTH`. Auto-retrying credentials against Nextcloud's brute-force
         * protection is the documented way real users IP-block their own server, and a
         * transport-level retry policy is exactly how that happens without anyone deciding
         * it should.
         */
        public fun create(): NextcloudTransport = NextcloudTransport(
            platformHttpClient {
                install(UserAgent) { agent = INKSTONE_USER_AGENT }
                // Every non-2xx comes back as a response to be classified (see DavOutcome),
                // never as an exception. SPEC §7.5's table is a policy decision, not an
                // error condition.
                expectSuccess = false
            },
        )
    }
}

/**
 * M0's factory, kept because `tools/verify-guardrails.sh` and the iOS link both reference
 * the Ktor stack through it.
 */
public fun createNextcloudHttpClient(): NextcloudTransport = NextcloudTransport.create()
