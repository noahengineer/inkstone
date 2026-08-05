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
 * M0 wires the client so that Guardrail 2 has something real to link. The
 * Nextcloud protocol itself — Login Flow v2, PROPFIND, the outbox, the
 * reconciler — is M5 and M6.
 */
public fun createNextcloudHttpClient(): HttpClient = platformHttpClient {
    install(UserAgent) {
        agent = INKSTONE_USER_AGENT
    }
    // Deliberately no retry plugin. SPEC §7.2: a 401 halts the worker and flags
    // NEEDS_REAUTH. Auto-retrying credentials against Nextcloud's brute-force
    // protection is the documented way real users IP-block their own server.
    expectSuccess = false
}
