package dev.inkstone.core.sync

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json as KJson

/** Who Google says owns an access token. [permissionId] is the stable key — a mailbox name can change. */
public data class GoogleIdentity(val permissionId: String, val email: String, val displayName: String?)

public sealed interface GoogleIdentityAnswer {
    public data class Found(val identity: GoogleIdentity) : GoogleIdentityAnswer

    /** [why] names the call and what came back, so a refusal is diagnosable from the message alone. */
    public data class Missing(val why: String) : GoogleIdentityAnswer
}

/**
 * `GET drive/v3/about` — the one call that says whose Drive a token opens.
 *
 * Its own class because the sign-in has to learn the identity *before* an account row exists, and a
 * [DriveStore] is built from one. Under the `drive.file` scope `about.get` is expected to work (the docs
 * list that scope for it); **not observed against Google**.
 */
public class GoogleIdentityReader(private val transport: NextcloudTransport, private val apiBase: String) {

    @Suppress("TooGenericExceptionCaught")
    public suspend fun read(accessToken: String): GoogleIdentityAnswer {
        val fields = "user(permissionId,emailAddress,displayName)".encodeURLParameter()
        val response = try {
            transport.client.get("${apiBase.trimEnd('/')}/drive/v3/about?fields=$fields") {
                header("Authorization", "Bearer $accessToken")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return GoogleIdentityAnswer.Missing("about could not be reached: ${failure.message ?: failure}")
        }
        val status = response.status.value
        if (status != HTTP_OK) return GoogleIdentityAnswer.Missing("about answered HTTP $status")
        val text: String = try {
            response.body()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return GoogleIdentityAnswer.Missing("about could not be read: ${failure.message ?: failure}")
        }
        val user = parse(text)?.get("user") as? JsonObject
        val permissionId = user?.text("permissionId")
            ?: return GoogleIdentityAnswer.Missing("about named no user")
        val email = user.text("emailAddress") ?: permissionId
        return GoogleIdentityAnswer.Found(GoogleIdentity(permissionId, email, user.text("displayName")))
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun parse(text: String): JsonObject? = try {
        KJson.parseToJsonElement(text).jsonObject
    } catch (ignored: Exception) {
        null
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val HTTP_OK = 200
    }
}
