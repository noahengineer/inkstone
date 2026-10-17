package dev.inkstone.core.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json as KJson

/**
 * What Microsoft Graph's non-2xx answers mean to the sync engine (docs/CLOUD-PROVIDERS.md §3.1).
 *
 * Pure, and its own object so [OneDriveStore] stays about requests. The two that matter most are the
 * conflicts: a **412** is a stale `If-Match`, and a **409 `nameAlreadyExists`** is a create over a file
 * that is there. Both say *someone else got there first*, and both are [DavFailure.PreconditionFailed] —
 * a conflict, never a retry (CLAUDE.md rule 1). A 401 is [DavFailure.Unauthorized] and nothing here
 * resends anything (rule 5).
 */
internal object GraphFailures {

    fun of(status: Int, retryAfter: String?, body: ByteArray, describe: String): DavFailure {
        val detail = body.decodeToString().take(DETAIL_LIMIT).takeIf { it.isNotBlank() }
        return when (status) {
            HTTP_UNAUTHORIZED -> DavFailure.Unauthorized

            HTTP_FORBIDDEN -> DavFailure.Forbidden(detail)

            HTTP_NOT_FOUND -> DavFailure.NotFound(describe)

            HTTP_PRECONDITION_FAILED -> DavFailure.PreconditionFailed(null)

            HTTP_CONFLICT ->
                if (errorCode(body) ==
                    NAME_EXISTS
                ) {
                    DavFailure.PreconditionFailed(null)
                } else {
                    DavFailure.Server(status, detail)
                }

            HTTP_LOCKED -> DavFailure.Locked(null)

            HTTP_TOO_MANY_REQUESTS -> DavFailure.RateLimited(retryAfter?.toLongOrNull())

            HTTP_INSUFFICIENT_STORAGE -> DavFailure.QuotaExceeded

            else -> DavFailure.Server(status, detail)
        }
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun errorCode(body: ByteArray): String? = try {
        (KJson.parseToJsonElement(body.decodeToString()).jsonObject["error"] as? JsonObject)
            ?.get("code")?.let { (it as? JsonPrimitive)?.contentOrNull }
    } catch (ignored: Exception) {
        null
    }

    private const val NAME_EXISTS = "nameAlreadyExists"
    private const val DETAIL_LIMIT = 500
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_CONFLICT = 409
    private const val HTTP_PRECONDITION_FAILED = 412
    private const val HTTP_LOCKED = 423
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_INSUFFICIENT_STORAGE = 507
}
