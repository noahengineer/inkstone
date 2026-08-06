package dev.inkstone.core.sync

import dev.inkstone.core.model.NextcloudCredential
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * The OCS half of the Nextcloud API — the three endpoints that are not WebDAV.
 *
 * Every request carries `OCS-APIRequest: true` (without it Nextcloud answers with an
 * HTML login page, not JSON) and `Accept: application/json` (the default is XML).
 * Both are easy to leave off and both fail in a way that looks like a parse bug.
 */
public class OcsClient(transport: NextcloudTransport) {

    private val http = transport.client

    /**
     * SPEC §0.1 #3 / §7.1 rule 6 — **`ocs.data.id`, the WebDAV path segment.**
     *
     * The single most likely bug in any Nextcloud client written from memory. Login
     * Flow v2 returns a `loginName` that may be an email address; on an instance where
     * it is, `/remote.php/dav/files/<loginName>/` 404s and on an instance where the
     * login name happens to equal the uid it works, which is why it survives testing.
     *
     * This is also SPEC §7.2's confirmation call: it is the *one* request permitted
     * after a 401, to distinguish "that path 401s" from "these credentials are dead".
     */
    public suspend fun fetchUid(credential: NextcloudCredential): DavOutcome<String> =
        ocsGet(credential, NextcloudUrls.ocsUser(credential.serverUrl)).flatMap { data ->
            val id = data["id"]?.textOrNull()
            if (id.isNullOrEmpty()) {
                DavOutcome.Failed(DavFailure.Malformed("ocs.data.id was missing from /cloud/user"))
            } else {
                DavOutcome.Ok(id)
            }
        }

    /** The account's display name, for the settings screen. Never load-bearing. */
    public suspend fun fetchDisplayName(credential: NextcloudCredential): DavOutcome<String?> =
        ocsGet(credential, NextcloudUrls.ocsUser(credential.serverUrl)).map { data ->
            data["display-name"]?.textOrNull() ?: data["displayname"]?.textOrNull()
        }

    /**
     * SPEC §7.9 — capabilities, fetched once at login and again on a server version
     * change, then cached.
     *
     * Parsed leniently on purpose: the shape of this document varies across Nextcloud
     * versions and installed apps, and a strict deserialiser would turn "this server has
     * an app I have never heard of" into "you cannot log in".
     */
    public suspend fun fetchCapabilities(credential: NextcloudCredential): DavOutcome<NextcloudCapabilities> =
        ocsGet(credential, NextcloudUrls.ocsCapabilities(credential.serverUrl)).map {
            NextcloudCapabilities.parse(it)
        }

    /**
     * SPEC §7.1 — sign-out. `DELETE /ocs/v2.php/core/apppassword`.
     *
     * > If it returns non-200, **remove the local account anyway** (Nextcloud's own
     * > guidance).
     *
     * So this returns an outcome the caller is expected to ignore for the purpose of
     * deciding whether to forget the account — an unreachable server must not be able
     * to trap a user in a session they asked to end.
     */
    @Suppress("TooGenericExceptionCaught")
    public suspend fun revokeAppPassword(credential: NextcloudCredential): DavOutcome<Unit> = try {
        val response = http.delete(NextcloudUrls.ocsAppPassword(credential.serverUrl)) {
            ocsHeaders(credential)
        }
        if (response.status.value in SUCCESS_FLOOR until REDIRECT_FLOOR) {
            DavOutcome.Ok(Unit)
        } else {
            DavOutcome.Failed(DavFailure.Server(response.status.value, null))
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        DavOutcome.Failed(DavFailure.Transport(failure.message ?: failure.toString()))
    }

    // -----------------------------------------------------------------------

    @Suppress("TooGenericExceptionCaught")
    // As in WebDavClient: Ktor's transport exception types differ between the OkHttp
    // and Darwin engines, so a narrower catch misses the failure on one platform.
    private suspend fun ocsGet(credential: NextcloudCredential, url: String): DavOutcome<JsonObject> {
        val response: HttpResponse = try {
            http.get(url) { ocsHeaders(credential) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return DavOutcome.Failed(DavFailure.Transport(failure.message ?: failure.toString()))
        }

        val status = response.status.value
        if (status == UNAUTHORIZED) return DavOutcome.Failed(DavFailure.Unauthorized)
        if (status !in SUCCESS_FLOOR until REDIRECT_FLOOR) {
            return DavOutcome.Failed(DavFailure.Server(status, null))
        }

        return try {
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val data = root["ocs"]?.jsonObject?.get("data")?.jsonObject
                ?: return DavOutcome.Failed(DavFailure.Malformed("no ocs.data in the reply"))
            DavOutcome.Ok(data)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // Almost always the HTML login page you get without `OCS-APIRequest: true`.
            DavOutcome.Failed(DavFailure.Malformed(failure.message ?: "unparseable OCS reply"))
        }
    }

    private fun HttpRequestBuilder.ocsHeaders(credential: NextcloudCredential) {
        header("Authorization", BasicAuth.header(credential.loginName, credential.appPassword))
        // Without this Nextcloud answers with an HTML login page rather than JSON.
        header("OCS-APIRequest", "true")
        header("Accept", "application/json")
    }

    private companion object {
        const val SUCCESS_FLOOR = 200
        const val REDIRECT_FLOOR = 300
        const val UNAUTHORIZED = 401
        val json = Json { ignoreUnknownKeys = true }
    }
}

internal inline fun <T, R> DavOutcome<T>.flatMap(transform: (T) -> DavOutcome<R>): DavOutcome<R> = when (this) {
    is DavOutcome.Ok -> transform(value)
    is DavOutcome.Failed -> this
}

/**
 * A non-empty string for a JSON string, null for JSON `null` and for `""`.
 *
 * `contentOrNull` alone would give `""` for an empty string, and an empty uid is the
 * one value this module must never treat as usable (SPEC §0.1 #3).
 */
internal fun JsonElement.textOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
