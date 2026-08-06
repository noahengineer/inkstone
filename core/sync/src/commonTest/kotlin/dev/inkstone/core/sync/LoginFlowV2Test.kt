package dev.inkstone.core.sync

import dev.inkstone.core.model.NextcloudCredential
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SPEC §7.1's six rules, one test each where a test can prove one.
 *
 * The test that matters most is [`the WebDAV path segment comes from ocs data id`]: SPEC
 * §0.1 #3 calls it *"the single most likely bug in any Nextcloud client written from
 * memory"*, and it is invisible on an instance where the login name happens to equal the
 * uid. So the fixture logs in with an **email address** — the case ROADMAP M5 asks to be
 * tested against a real instance, reproduced here so a regression fails the build rather
 * than waiting for a manual pass.
 */
class LoginFlowV2Test {

    private val startBody = """
        {
          "poll": {
            "token": "poll-token-abc",
            "endpoint": "https://cloud.example.com/index.php/login/v2/poll"
          },
          "login": "https://cloud.example.com/index.php/login/v2/flow/xyz"
        }
    """.trimIndent()

    private val approvedBody = """
        {
          "server": "https://cloud.example.com",
          "loginName": "noah@example.com",
          "appPassword": "sekret-app-password"
        }
    """.trimIndent()

    @Test
    fun `the poll endpoint is used verbatim and never rebuilt`() = runTest {
        // SPEC §7.1 rule 1. The server builds it with linkToRouteAbsolute, so /index.php
        // may or may not be present — a client that reconstructs the URL works against the
        // server it was written on and 404s against somebody else's.
        val quirky = startBody.replace(
            "https://cloud.example.com/index.php/login/v2/poll",
            "https://cloud.example.com/subdir/login/v2/poll",
        )
        val recorded = RecordedRequests()
        val flow = LoginFlowV2(
            mockTransport(recorded) { request ->
                if (request.url.encodedPath.endsWith(
                        "/login/v2",
                    )
                ) {
                    json(quirky)
                } else {
                    respondError(HttpStatusCode.NotFound)
                }
            },
        )

        val begun = assertIs<LoginFlowResult.Success<LoginFlowStart>>(flow.begin("cloud.example.com"))
        assertEquals("https://cloud.example.com/subdir/login/v2/poll", begun.value.pollEndpoint)

        flow.pollOnce(begun.value)
        assertEquals("/subdir/login/v2/poll", recorded.all.last().url.encodedPath)
    }

    @Test
    fun `a 404 while polling is pending and nothing more`() = runTest {
        // SPEC §7.1 rule 2 — "not ready", "invalid" and "expired" are indistinguishable.
        val flow = LoginFlowV2(mockTransport { respondError(HttpStatusCode.NotFound) })
        val start = LoginFlowStart("https://cloud.example.com", "https://…", "https://…/poll", "t")

        assertIs<LoginFlowV2.PollOutcome.Pending>(flow.pollOnce(start))
    }

    @Test
    fun `the credential is persisted before the call returns`() = runTest {
        // SPEC §7.1 rule 3 — the server deletes its row the instant it answers, so a crash
        // between the 200 and the write is unrecoverable. Persisting is therefore the
        // *first* thing that happens with the app password, before the uid is fetched.
        val persisted = mutableListOf<NextcloudCredential>()
        val flow = LoginFlowV2(
            mockTransport { request ->
                when {
                    request.url.encodedPath.endsWith("/poll") -> json(approvedBody)
                    request.url.encodedPath.contains("cloud/user") -> json("""{"ocs":{"data":{"id":"noah"}}}""")
                    else -> respondError(HttpStatusCode.NotFound)
                }
            },
        )

        val result = flow.awaitApproval(
            start = LoginFlowStart("https://cloud.example.com", "u", "https://cloud.example.com/poll", "t"),
            persist = { persisted += it },
            now = { 0L },
        )

        assertIs<LoginFlowResult.Success<NextcloudCredential>>(result)
        assertEquals(2, persisted.size, "the app password must be written before the uid is fetched")
        assertEquals("", persisted.first().uid, "the first write is the unconditional one (rule 3)")
        assertEquals("sekret-app-password", persisted.first().appPassword)
        assertEquals("noah", persisted.last().uid)
    }

    @Test
    fun `the WebDAV path segment comes from ocs data id and not the login name`() = runTest {
        // SPEC §0.1 #3 / §7.1 rule 6. The login name here is an email address, which is
        // exactly the instance ROADMAP M5 asks to be tested against.
        val flow = LoginFlowV2(
            mockTransport { request ->
                when {
                    request.url.encodedPath.endsWith("/poll") -> json(approvedBody)

                    request.url.encodedPath.contains("cloud/user") ->
                        json("""{"ocs":{"meta":{"status":"ok"},"data":{"id":"noah","display-name":"Noah B"}}}""")

                    else -> respondError(HttpStatusCode.NotFound)
                }
            },
        )

        val result = flow.awaitApproval(
            start = LoginFlowStart("https://cloud.example.com", "u", "https://cloud.example.com/poll", "t"),
            persist = {},
            now = { 0L },
        )

        val credential = assertIs<LoginFlowResult.Success<NextcloudCredential>>(result).value
        assertEquals("noah@example.com", credential.loginName, "loginName authenticates")
        assertEquals("noah", credential.uid, "uid is the path segment")

        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/noah/",
            NextcloudUrls.davBase(credential.serverUrl, credential.uid),
        )
    }

    @Test
    fun `an unfetchable uid still counts as signed in`() = runTest {
        // Rule 3 already saved the app password. Losing the account over a failed second
        // request would make the user redo the browser dance for nothing.
        val flow = LoginFlowV2(
            mockTransport { request ->
                if (request.url.encodedPath.endsWith("/poll")) {
                    json(approvedBody)
                } else {
                    respondError(HttpStatusCode.ServiceUnavailable)
                }
            },
        )

        val result = flow.awaitApproval(
            start = LoginFlowStart("https://cloud.example.com", "u", "https://cloud.example.com/poll", "t"),
            persist = {},
            now = { 0L },
        )

        val needsUid = assertIs<LoginFlowResult.NeedsUid>(result)
        assertEquals("sekret-app-password", needsUid.credential.appPassword)
        assertEquals("", needsUid.credential.uid)
    }

    @Test
    fun `the timeout message names the allowed_user_agents possibility`() = runTest {
        // SPEC §7.1 rule 5 — an admin PCRE that does not match makes the browser 403 while
        // the client polls 404 forever with no signal. If the message does not say so, the
        // user has no way at all to find out.
        val flow = LoginFlowV2(mockTransport { respondError(HttpStatusCode.NotFound) })
        var clock = 0L

        val result = flow.awaitApproval(
            start = LoginFlowStart("https://cloud.example.com", "u", "https://cloud.example.com/poll", "t"),
            persist = {},
            now = {
                clock += 2_000_000L
                clock
            },
        )

        val timedOut = assertIs<LoginFlowResult.TimedOut>(result)
        assertTrue(timedOut.advice.contains("allowed_user_agents"))
        assertTrue(timedOut.advice.contains(INKSTONE_USER_AGENT))
    }

    @Test
    fun `the user agent is readable so the app password has a name in the security settings`() = runTest {
        // SPEC §7.1 rule 4. Left at Ktor's default, the entry reads "okhttp" and the user
        // cannot tell which of their devices to revoke.
        val recorded = RecordedRequests()
        val flow = LoginFlowV2(mockTransport(recorded) { json(startBody) })

        flow.begin("cloud.example.com")

        assertEquals(INKSTONE_USER_AGENT, recorded.all.single().headers["User-Agent"])
        assertTrue(INKSTONE_USER_AGENT.contains("Inkstone"))
    }

    @Test
    fun `http is refused rather than upgraded`() = runTest {
        // SPEC §14 — HTTPS only, and no "accept anyway". Silently upgrading would be
        // guessing about the user's own server; refusing says what happened.
        val flow = LoginFlowV2(mockTransport { json(startBody) })

        val result = flow.begin("http://cloud.example.com")

        assertIs<LoginFlowResult.Insecure>(result)
    }

    @Test
    fun `a bare host is assumed to be https`() = runTest {
        val flow = LoginFlowV2(mockTransport { json(startBody) })

        val result = assertIs<LoginFlowResult.Success<LoginFlowStart>>(flow.begin("  cloud.example.com/  "))

        assertEquals("https://cloud.example.com", result.value.serverUrl)
    }
}
