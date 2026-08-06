package dev.inkstone.core.sync

import dev.inkstone.core.model.NextcloudCredential
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlin.coroutines.cancellation.CancellationException

/**
 * The WebDAV half of the Nextcloud client (SPEC §7.3–§7.5).
 *
 * One instance per account. Everything it needs to address a file — the server, the
 * **uid** (not the login name, SPEC §0.1 #3) and the app password — comes from a
 * [NextcloudCredential], and every method returns a [DavOutcome] rather than throwing.
 *
 * ## Three rules encoded here rather than remembered
 *
 * 1. **`Depth` is always explicit.** Sabre's default when the header is omitted is
 *    `infinity`, which on a large tree is a self-inflicted denial of service against
 *    the user's own server (SPEC §7.3). [Depth] has no "unset".
 * 2. **A 401 comes straight back, and nothing is re-sent.** SPEC §7.2.
 * 3. **`OC-ETag` and `OC-FileId` are read off the PUT/MOVE/COPY response.** SPEC §7.4:
 *    *"no follow-up PROPFIND"*. [UploadReceipt] carries them.
 */
public class WebDavClient(transport: NextcloudTransport, private val credential: NextcloudCredential) {

    private val http = transport.client

    private val authorization = BasicAuth.header(credential.loginName, credential.appPassword)

    /** SPEC §7.3 — always sent, never defaulted. */
    public enum class Depth(internal val header: String) {
        Zero("0"),
        One("1"),
    }

    private fun url(remotePath: String) = NextcloudUrls.davUrl(credential.serverUrl, credential.uid, remotePath)

    // -----------------------------------------------------------------------
    // Read
    // -----------------------------------------------------------------------

    /**
     * PROPFIND with the SPEC §7.3 body.
     *
     * @param depth [Depth.Zero] for "this resource only" — which is the whole poll in
     *   the common case, because directory ETags propagate (SPEC §7.4 rule 1).
     */
    public suspend fun propfind(remotePath: String, depth: Depth): DavOutcome<List<DavResource>> =
        call(HttpMethod("PROPFIND"), remotePath) {
            header("Depth", depth.header)
            contentType(ContentType.Application.Xml)
            setBody(WebDavXml.PROPFIND_BODY)
        }.flatMapText { body -> parseMultistatus(body) }

    /** SPEC §7.9 — free space, with the sentinel negatives handled by [DavResource]. */
    public suspend fun quota(remotePath: String = ""): DavOutcome<DavResource?> =
        call(HttpMethod("PROPFIND"), remotePath) {
            header("Depth", Depth.Zero.header)
            contentType(ContentType.Application.Xml)
            setBody(WebDavXml.QUOTA_BODY)
        }.flatMapText { body -> parseMultistatus(body).map { it.firstOrNull() } }

    /** Download a file whole. Used by the M5 round-trip spike and by M6's reconciler. */
    public suspend fun get(remotePath: String): DavOutcome<ByteArray> =
        callRaw(HttpMethod.Get, remotePath) {}.map { it.bytes }

    public suspend fun exists(remotePath: String): DavOutcome<Boolean> =
        when (val outcome = propfind(remotePath, Depth.Zero)) {
            is DavOutcome.Ok -> DavOutcome.Ok(outcome.value.isNotEmpty())

            is DavOutcome.Failed ->
                if (outcome.error is DavFailure.NotFound) DavOutcome.Ok(false) else outcome
        }

    // -----------------------------------------------------------------------
    // Write
    // -----------------------------------------------------------------------

    /**
     * SPEC §7.5 — PUT with `If-Match`, `X-OC-Mtime` and `OC-Checksum`.
     *
     * @param ifMatch the ETag last seen, or null to write unconditionally. **Pass the
     *   ETag whenever there is one**: CLAUDE.md rule 1 forbids overwriting a remote file
     *   without a precondition, and a 412 means someone else changed it — a conflict,
     *   not a retry.
     *
     *   A *weak* ETag yields `null` from [ETag.asIfMatch] on purpose (SPEC §7.3): sending
     *   one back produces a permanent 412 loop, so the caller falls back to comparing
     *   `OC-Checksum` instead.
     * @param ifNoneMatchStar SPEC §7.5 — `If-None-Match: *` for a create that must not
     *   clobber an existing file.
     * @param md5Hex the body's MD5, for `OC-Checksum`. Nextcloud verifies it, which turns
     *   a truncated upload into a 400 instead of a corrupt note.
     */
    @Suppress("LongParameterList") // Each one is a distinct header SPEC §7.5 names.
    public suspend fun put(
        remotePath: String,
        bytes: ByteArray,
        ifMatch: ETag? = null,
        ifNoneMatchStar: Boolean = false,
        mtimeEpochSeconds: Long? = null,
        md5Hex: String? = null,
    ): DavOutcome<UploadReceipt> = callRaw(HttpMethod.Put, remotePath) {
        ifMatch?.asIfMatch?.let { header("If-Match", it) }
        if (ifNoneMatchStar) header("If-None-Match", "*")
        mtimeEpochSeconds?.let { header("X-OC-Mtime", it.toString()) }
        md5Hex?.let { header("OC-Checksum", "md5:$it") }
        contentType(ContentType.Application.Pdf)
        setBody(bytes)
    }.map { it.receipt() }

    /** SPEC §7.6 — `MKCOL`, which drains before any op targeting a path inside it. */
    public suspend fun mkcol(remotePath: String): DavOutcome<Unit> = callRaw(HttpMethod("MKCOL"), remotePath) {}.map { }

    /**
     * `MKCOL` for every missing level of [remotePath], root-first.
     *
     * A 405 on an intermediate level means it already exists, which is success — a
     * PROPFIND per level to find that out would be three round trips to learn what one
     * status code already said.
     */
    public suspend fun mkcolRecursive(remotePath: String): DavOutcome<Unit> {
        val segments = remotePath.split('/').filter { it.isNotEmpty() }
        var built = ""
        for (segment in segments) {
            built = if (built.isEmpty()) segment else "$built/$segment"
            when (val outcome = mkcol(built)) {
                is DavOutcome.Ok -> Unit

                is DavOutcome.Failed -> {
                    val error = outcome.error
                    val alreadyThere = error is DavFailure.Server && error.status == METHOD_NOT_ALLOWED
                    if (!alreadyThere) return outcome
                }
            }
        }
        return DavOutcome.Ok(Unit)
    }

    /**
     * SPEC §7.5 — `MOVE` with **`Overwrite: F`**.
     *
     * `F` is not a default worth relying on: RFC 4918's default is `T`, and a move that
     * silently overwrote the destination would destroy a note nobody asked it to touch
     * (CLAUDE.md rule 1). A 412 here means something is already at the destination.
     */
    public suspend fun move(fromPath: String, toPath: String): DavOutcome<UploadReceipt> =
        callRaw(HttpMethod("MOVE"), fromPath) {
            header("Destination", url(toPath))
            header("Overwrite", "F")
        }.map { it.receipt() }

    /**
     * `DELETE` — soft on Nextcloud, which is why it is allowed at all.
     *
     * SPEC §7.7: *"Nextcloud `DELETE` is soft"* — the file lands in the server's trash
     * with its own retention. That is the remote half of CLAUDE.md rule 1; the local
     * half is [dev.inkstone.core.storage.NoteTrash].
     */
    public suspend fun delete(remotePath: String): DavOutcome<Unit> = callRaw(HttpMethod.Delete, remotePath) {}.map { }

    // -----------------------------------------------------------------------
    // Plumbing
    // -----------------------------------------------------------------------

    private suspend fun call(
        method: HttpMethod,
        remotePath: String,
        configure: HttpRequestBuilder.() -> Unit,
    ): DavOutcome<RawResponse> = callRaw(method, remotePath, configure)

    @Suppress("TooGenericExceptionCaught")
    // A network call fails in as many ways as there are engines. Ktor's exception types
    // differ per platform (kotlinx.io.IOException on Native, java.io.IOException on the
    // JVM engine), so catching a narrower type here would compile and then miss the real
    // failure on one of the two targets this module has to work on. Cancellation is
    // rethrown so a cancelled sync worker does not look like a transport error.
    private suspend fun callRaw(
        method: HttpMethod,
        remotePath: String,
        configure: HttpRequestBuilder.() -> Unit,
    ): DavOutcome<RawResponse> {
        val response = try {
            http.request(url(remotePath)) {
                this.method = method
                header("Authorization", authorization)
                configure()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return DavOutcome.Failed(DavFailure.Transport(failure.message ?: failure.toString()))
        }
        return interpret(response, remotePath)
    }

    private suspend fun interpret(response: HttpResponse, remotePath: String): DavOutcome<RawResponse> {
        val status = response.status.value
        if (status in SUCCESS_FLOOR until REDIRECT_FLOOR) {
            return DavOutcome.Ok(
                RawResponse(
                    status = status,
                    bytes = response.body<ByteArray>(),
                    etag = ETag.parse(response.headers["OC-ETag"] ?: response.headers["ETag"]),
                    ocId = response.headers["OC-FileId"],
                ),
            )
        }
        return DavOutcome.Failed(failureFor(response, status, remotePath))
    }

    private suspend fun failureFor(response: HttpResponse, status: Int, remotePath: String): DavFailure =
        when (status) {
            // SPEC §7.2 / CLAUDE.md rule 5. Nothing is re-sent after this.
            UNAUTHORIZED -> DavFailure.Unauthorized

            FORBIDDEN -> DavFailure.Forbidden(response.bodyAsTextOrNull()?.take(DETAIL_LIMIT))

            NOT_FOUND -> DavFailure.NotFound(remotePath)

            PRECONDITION_FAILED -> DavFailure.PreconditionFailed(response.headers["ETag"])

            LOCKED -> DavFailure.Locked(response.headers["X-Lock-Owner"])

            TOO_MANY_REQUESTS -> DavFailure.RateLimited(response.headers["Retry-After"]?.toLongOrNull())

            INSUFFICIENT_STORAGE -> DavFailure.QuotaExceeded

            else -> DavFailure.Server(status, response.bodyAsTextOrNull()?.take(DETAIL_LIMIT))
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    // Reading an error body is best-effort diagnostics. If it fails there is nothing
    // useful left to report about it, and the status code — which is the actual finding —
    // has already been captured by the caller.
    private suspend fun HttpResponse.bodyAsTextOrNull(): String? = try {
        bodyAsText().takeIf { it.isNotBlank() }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (ignored: Throwable) {
        null
    }

    private fun parseMultistatus(body: String): DavOutcome<List<DavResource>> = try {
        DavOutcome.Ok(WebDavXml.parseMultistatus(body, credential.uid))
    } catch (malformed: XmlParseException) {
        DavOutcome.Failed(DavFailure.Malformed(malformed.message ?: "unreadable multistatus"))
    }

    private inline fun <R> DavOutcome<RawResponse>.flatMapText(transform: (String) -> DavOutcome<R>): DavOutcome<R> =
        when (this) {
            is DavOutcome.Ok -> transform(value.bytes.decodeToString())
            is DavOutcome.Failed -> this
        }

    private class RawResponse(val status: Int, val bytes: ByteArray, val etag: ETag?, val ocId: String?) {
        fun receipt() = UploadReceipt(status, etag, ocId)
    }

    private companion object {
        const val SUCCESS_FLOOR = 200
        const val REDIRECT_FLOOR = 300
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val METHOD_NOT_ALLOWED = 405
        const val PRECONDITION_FAILED = 412
        const val LOCKED = 423
        const val TOO_MANY_REQUESTS = 429
        const val INSUFFICIENT_STORAGE = 507
        const val DETAIL_LIMIT = 500
    }
}

/**
 * What a PUT or MOVE told us, without a follow-up PROPFIND.
 *
 * SPEC §7.4: *"`OC-FileId` and `OC-Etag` come back as **response headers** on
 * PUT/MOVE/COPY"*. Persisting them straight from here is what keeps an upload to one
 * round trip.
 *
 * Both are nullable because a reverse proxy can strip them. A missing ETag is not an
 * error — it means the next upload has no `If-Match` to send and has to re-read the
 * binding first, which is slower and still correct.
 */
public data class UploadReceipt(val status: Int, val etag: ETag?, val ocId: String?)
