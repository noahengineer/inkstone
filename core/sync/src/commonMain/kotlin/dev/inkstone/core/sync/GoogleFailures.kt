package dev.inkstone.core.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json as KJson

/**
 * What Google's non-2xx answers mean to the sync engine (docs/CLOUD-PROVIDERS.md §3.2).
 *
 * Pure, and its own object so [DriveStore] stays about requests. A **403 is not one thing** on Google:
 * the same status carries a rate limit, an exhausted quota and a plain refusal, and the first two must
 * not be read as "you may not do this" (one is wait, one is stop sending). The `reason` in the error body
 * is what tells them apart. A 401 is [DavFailure.Unauthorized] and nothing here resends anything (rule 5).
 */
internal object GoogleFailures {

    fun of(status: Int, retryAfter: String?, body: ByteArray, describe: String): DavFailure {
        val detail = body.decodeToString().take(DETAIL_LIMIT).takeIf { it.isNotBlank() }
        return when (status) {
            HTTP_UNAUTHORIZED -> DavFailure.Unauthorized

            HTTP_FORBIDDEN -> when (reason(body)) {
                in RATE_REASONS -> DavFailure.RateLimited(retryAfter?.toLongOrNull())
                in QUOTA_REASONS -> DavFailure.QuotaExceeded
                else -> DavFailure.Forbidden(detail)
            }

            HTTP_NOT_FOUND -> DavFailure.NotFound(describe)

            HTTP_PRECONDITION_FAILED -> DavFailure.PreconditionFailed(null)

            HTTP_TOO_MANY_REQUESTS -> DavFailure.RateLimited(retryAfter?.toLongOrNull())

            else -> DavFailure.Server(status, detail)
        }
    }

    /** The first `errors[].reason` of Google's `{"error":{"errors":[{"reason":…}]}}`, or null. */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun reason(body: ByteArray): String? = try {
        val error = KJson.parseToJsonElement(body.decodeToString()).jsonObject["error"] as? JsonObject
        val first = (error?.get("errors") as? JsonArray)?.firstOrNull() as? JsonObject
        (first?.get("reason") as? JsonPrimitive)?.contentOrNull
    } catch (ignored: Exception) {
        null
    }

    private val RATE_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded")
    private val QUOTA_REASONS = setOf("storageQuotaExceeded", "quotaExceeded")
    private const val DETAIL_LIMIT = 500
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_PRECONDITION_FAILED = 412
    private const val HTTP_TOO_MANY_REQUESTS = 429
}
