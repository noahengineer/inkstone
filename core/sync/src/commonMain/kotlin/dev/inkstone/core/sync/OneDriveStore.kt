package dev.inkstone.core.sync

import dev.inkstone.core.model.Clock
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json as KJson

/**
 * OneDrive over Microsoft Graph, as a [CloudStore] (docs/CLOUD-PROVIDERS.md §3.1, §4).
 *
 * ## What makes this safe to write to
 *
 * - **Every overwrite is an upload session with `If-Match`.** `createUploadSession` is the one Graph
 *   write the docs say takes `If-Match` and answers 412 on a mismatch; the simple `PUT …/content`
 *   documents no precondition, so it is **never used**, whatever the size. A 412 maps to
 *   [DavFailure.PreconditionFailed] — a conflict, never a retry (CLAUDE.md rule 1).
 * - **Updates are addressed by item id.** A precondition on a *path* answers 412 for a file that is
 *   merely missing, which would read as a conflict. With no id on hand the store resolves the path and
 *   **compares the version itself** before the session is created, and the session still carries
 *   `If-Match`, so the server has the last word.
 * - **A create is `conflictBehavior: fail`** — `If-None-Match: *`'s analogue.
 * - **There is no hard delete.** [trash] is Graph's `DELETE`, which the docs say goes to the recycle bin.
 * - **No automatic retries**, of anything. A 401 comes back as [DavFailure.Unauthorized]; the single
 *   token refresh it may cause happens in [confirmStillAuthorised] under the rules in
 *   [MicrosoftTokenSource].
 * - **The upload URL is pre-authorised and is sent no bearer token** (Graph answers 401 if one is added).
 *
 * ## What this store is NOT yet
 *
 * It has no change feed. [pullShape] is [PullShape.FEED] and nothing implements `delta`, so the
 * reconciler refuses to poll a OneDrive account — an upload-only account is a known, loud limit
 * (docs §12), whereas pulling through a folder listing would look healthy and miss every remote
 * change, because Graph's folder ETags are not documented to propagate the way Nextcloud's do.
 *
 * ⚠️ Unverified against a live Graph (docs §9, M-M1…M-M10): the 412, the 409 on a duplicate create, that
 * a half-finished session is invisible, and that an item carries a `@microsoft.graph.downloadUrl`.
 *
 * @param fragmentBytes must be a multiple of 320 KiB (327,680) — Graph fails the *commit* of a
 *   file uploaded in any other size. A parameter so a test can use a small one.
 */
public class OneDriveStore(
    override val account: SyncAccount,
    private val transport: NextcloudTransport,
    private val tokens: MicrosoftTokenSource,
    private val fragmentBytes: Int = FRAGMENT_BYTES,
) : CloudStore {

    init {
        require(fragmentBytes > 0 && fragmentBytes % FRAGMENT_UNIT == 0) {
            "an upload fragment must be a multiple of 320 KiB, was $fragmentBytes"
        }
    }

    override val kind: ProviderKind get() = ProviderKind.ONEDRIVE

    override val supportsUncheckedReplace: Boolean get() = false

    override val pullShape: PullShape get() = PullShape.FEED

    private val base: String get() = account.serverUrl.trimEnd('/')

    private val drive: String get() = "$base/drives/${account.uid}"

    /** Folder path → item id, for the life of this store (one pass). A folder id never changes. */
    private val folderIds = HashMap<String, String>()

    // -----------------------------------------------------------------------
    // Writes
    // -----------------------------------------------------------------------

    override suspend fun ensureFolder(path: String): DavOutcome<Unit> {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return DavOutcome.Ok(Unit)
        // The common case is one request: the folder is already there.
        return when (val found = lookUpFolder(segments.joinToString("/"))) {
            is DavOutcome.Ok -> if (found.value) DavOutcome.Ok(Unit) else createMissing(segments)
            is DavOutcome.Failed -> found
        }
    }

    /** Ok(true): it is a folder. Ok(false): nothing is there. Failed: a file is in the way, or it could not be read. */
    private suspend fun lookUpFolder(full: String): DavOutcome<Boolean> = when (val existing = resolve(full)) {
        is DavOutcome.Ok ->
            if (existing.value.isFolder) {
                folderIds[full] = existing.value.id
                DavOutcome.Ok(true)
            } else {
                DavOutcome.Failed(DavFailure.Forbidden("a file named ${full.substringAfterLast('/')} is in the way"))
            }

        is DavOutcome.Failed ->
            if (existing.error is DavFailure.NotFound) DavOutcome.Ok(false) else existing
    }

    private suspend fun createMissing(segments: List<String>): DavOutcome<Unit> {
        var built = ""
        var parentId: String? = null
        for (segment in segments) {
            built = if (built.isEmpty()) segment else "$built/$segment"
            val known = folderIds[built]
            if (known != null) {
                parentId = known
                continue
            }
            val created = createFolder(parentId, segment, built)
            if (created is DavOutcome.Failed) return created
            parentId = (created as DavOutcome.Ok).value
            folderIds[built] = parentId
        }
        return DavOutcome.Ok(Unit)
    }

    override suspend fun put(
        target: RemoteTarget,
        bytes: ByteArray,
        precondition: WritePrecondition,
        mtimeEpochSeconds: Long?,
        md5Hex: String?,
        kind: UploadKind,
    ): DavOutcome<UploadReceipt> {
        // Neither the mtime nor the MD5 has a place here: Graph computes its own hashes, and setting
        // `fileSystemInfo` is a metadata write that moves the eTag for nothing.
        val session = when (precondition) {
            WritePrecondition.Unchecked -> return DavOutcome.Failed(
                DavFailure.Forbidden("OneDrive will not overwrite a file without a version to check"),
            )

            WritePrecondition.CreateOnly -> openSession(
                url = "${itemUrl(target.path)}/createUploadSession",
                behavior = "fail",
                ifMatch = null,
                describe = target.path,
            )

            is WritePrecondition.IfMatch -> {
                val ifMatch = precondition.etag.asIfMatch
                    ?: return DavOutcome.Failed(DavFailure.Forbidden("a weak ETag cannot guard an overwrite"))
                val id = when (val found = idFor(target)) {
                    is DavOutcome.Ok -> found.value
                    is DavOutcome.Failed -> return found
                }
                openSession(
                    url = "$drive/items/${id.id}/createUploadSession",
                    behavior = "replace",
                    ifMatch = ifMatch,
                    describe = target.path,
                    // Resolved by path rather than given: the version on the server is compared here
                    // too, so a change is a conflict before a session exists. The server still checks.
                    resolvedEtag = id.etag?.takeIf { target.id == null },
                    expected = precondition.etag,
                )
            }
        }
        val uploadUrl = when (session) {
            is DavOutcome.Ok -> session.value
            is DavOutcome.Failed -> return session
        }
        return uploadFragments(uploadUrl, bytes, target)
    }

    override suspend fun move(from: RemoteTarget, toPath: String): DavOutcome<UploadReceipt> {
        val source = when (val found = idFor(from)) {
            is DavOutcome.Ok -> found.value
            is DavOutcome.Failed -> return found
        }
        val segments = toPath.split('/').filter { it.isNotEmpty() }
        val name = segments.lastOrNull() ?: return DavOutcome.Failed(DavFailure.Forbidden("nowhere to move to"))
        val parentPath = segments.dropLast(1).joinToString("/")
        val parentId = when (val parent = folderId(parentPath)) {
            is DavOutcome.Ok -> parent.value
            is DavOutcome.Failed -> return parent
        }
        // No If-Match and no conflictBehavior: a move changes no content, and Graph's default for a
        // name that is already taken is to refuse (409), which is the "never overwrite" rule.
        val body = buildJsonObject {
            put("name", name)
            put("parentReference", buildJsonObject { put("id", parentId) })
        }
        val reply = when (val sent = send(HttpMethod.Patch, "$drive/items/${source.id}", toPath) { jsonBody(body) }) {
            is DavOutcome.Ok -> sent.value
            is DavOutcome.Failed -> return sent
        }
        val item = Item.of(reply.json())
        return DavOutcome.Ok(UploadReceipt(reply.status, item?.etag, item?.id ?: source.id))
    }

    override suspend fun trash(target: RemoteTarget): DavOutcome<Unit> {
        val id = when (val found = idFor(target)) {
            is DavOutcome.Ok -> found.value.id
            is DavOutcome.Failed -> return found
        }
        // The docs: DELETE "moves the items to the recycle bin instead of permanently deleting".
        return send(HttpMethod.Delete, "$drive/items/$id", target.path) {}.map { }
    }

    /**
     * SPEC §7.2's one allowed question after a 401, for an OAuth account (docs §5.3).
     *
     * Two things are being asked and only the first costs the token endpoint a request: *did the
     * grant survive* (one refresh, never repeated inside the cooldown), and *does the service
     * accept the fresh token* (one `GET /me/drive`). A second 401 straight after a refresh that
     * worked is not a path problem; it is the credential, so it answers false.
     */
    override suspend fun confirmStillAuthorised(): Boolean? = when (tokens.refreshAfterRefusal()) {
        is ForcedRefresh.Dead, ForcedRefresh.CoolingDown -> false

        is ForcedRefresh.Unreachable -> null

        ForcedRefresh.Refreshed -> when (val probe = send(HttpMethod.Get, "$base/me/drive", "/me/drive") {}) {
            is DavOutcome.Ok -> true

            is DavOutcome.Failed -> when (probe.error) {
                DavFailure.Unauthorized -> false
                else -> null
            }
        }
    }

    // -----------------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------------

    override suspend fun listing(path: String, depth: ListingDepth): DavOutcome<List<DavResource>> = when (depth) {
        ListingDepth.SELF -> resolve(path).map { listOf(it.toResource(path)) }
        ListingDepth.CHILDREN -> children(path)
    }

    override suspend fun download(path: String): DavOutcome<DownloadedFile> {
        val item = when (val found = resolve(path)) {
            is DavOutcome.Ok -> found.value
            is DavOutcome.Failed -> return found
        }
        val url = item.downloadUrl
            ?: return DavOutcome.Failed(DavFailure.Malformed("OneDrive gave no download URL for $path"))
        // Pre-authorised, like the upload URL: no bearer token, and following it ourselves keeps one
        // from being copied onto a redirect to another host.
        return sendAnonymous(HttpMethod.Get, url, path) {}.map { DownloadedFile(it.bytes, item.etag, item.id) }
    }

    override suspend fun locate(fileId: Long): DavOutcome<List<DavResource>> = DavOutcome.Failed(
        DavFailure.Malformed("OneDrive has no file-id search; its pull is a delta feed, which is not built yet"),
    )

    // -----------------------------------------------------------------------
    // Pieces
    // -----------------------------------------------------------------------

    private suspend fun children(path: String): DavOutcome<List<DavResource>> {
        val out = ArrayList<DavResource>()
        val folder = if (path.isBlank()) "$drive/root" else itemUrl(path)
        var url: String? = "$folder/children?\$top=$PAGE_SIZE"
        var guard = 0
        while (url != null && guard++ < MAX_PAGES) {
            val reply = when (val sent = send(HttpMethod.Get, url, path) {}) {
                is DavOutcome.Ok -> sent.value
                is DavOutcome.Failed -> return sent
            }
            val json = reply.json()
            for (element in (json["value"] as? JsonArray).orEmpty()) {
                val item = Item.of(element) ?: continue
                out += item.toResource(listOf(path.trim('/'), item.name).filter { it.isNotEmpty() }.joinToString("/"))
            }
            url = (json["@odata.nextLink"] as? JsonPrimitive)?.contentOrNull
        }
        return DavOutcome.Ok(out)
    }

    private suspend fun createFolder(parentId: String?, name: String, path: String): DavOutcome<String> {
        val url = if (parentId == null) "$drive/root/children" else "$drive/items/$parentId/children"
        val body = buildJsonObject {
            put("name", name)
            put("folder", buildJsonObject { })
            put("@microsoft.graph.conflictBehavior", "fail")
        }
        return when (val sent = send(HttpMethod.Post, url, path) { jsonBody(body) }) {
            is DavOutcome.Ok -> Item.of(sent.value.json())?.id
                ?.let { DavOutcome.Ok(it) }
                ?: DavOutcome.Failed(DavFailure.Malformed("OneDrive's reply to a folder create had no id"))

            is DavOutcome.Failed ->
                // Somebody (or a racing pass) made it first: that is the state we wanted.
                if (sent.error is DavFailure.PreconditionFailed) {
                    resolve(path).map { it.id }
                } else {
                    sent
                }
        }
    }

    private suspend fun folderId(path: String): DavOutcome<String> {
        val normal = path.split('/').filter { it.isNotEmpty() }.joinToString("/")
        if (normal.isEmpty()) {
            return resolveRoot().map { it.id }
        }
        folderIds[normal]?.let { return DavOutcome.Ok(it) }
        val ensured = ensureFolder(normal)
        if (ensured is DavOutcome.Failed) return ensured
        return folderIds[normal]?.let { DavOutcome.Ok(it) } ?: resolve(normal).map { it.id }
    }

    private suspend fun resolveRoot(): DavOutcome<Item> =
        send(HttpMethod.Get, "$drive/root", "/") {}.flatten { Item.of(it.json()) }

    private suspend fun resolve(path: String): DavOutcome<Item> =
        send(HttpMethod.Get, if (path.isBlank()) "$drive/root" else itemUrl(path), path) {}
            .flatten { Item.of(it.json()) }

    /** The target's item id and current version: given, or looked up by path. */
    private suspend fun idFor(target: RemoteTarget): DavOutcome<Item> {
        val id = target.id
        if (id != null) return DavOutcome.Ok(Item(id = id, etag = null, isFolder = false, name = "", size = null))
        return resolve(target.path)
    }

    @Suppress("LongParameterList") // One request's worth of preconditions; splitting them hides which apply.
    private suspend fun openSession(
        url: String,
        behavior: String,
        ifMatch: String?,
        describe: String,
        resolvedEtag: ETag? = null,
        expected: ETag? = null,
    ): DavOutcome<String> {
        if (resolvedEtag != null && expected != null && resolvedEtag.value != expected.value) {
            // The file changed since we last saw it — found without writing anything.
            return DavOutcome.Failed(DavFailure.PreconditionFailed(resolvedEtag.toString()))
        }
        val body = buildJsonObject {
            put("item", buildJsonObject { put("@microsoft.graph.conflictBehavior", behavior) })
        }
        val reply = when (
            val sent = send(HttpMethod.Post, url, describe) {
                ifMatch?.let { header("If-Match", it) }
                jsonBody(body)
            }
        ) {
            is DavOutcome.Ok -> sent.value
            is DavOutcome.Failed -> return sent
        }
        val uploadUrl = (reply.json()["uploadUrl"] as? JsonPrimitive)?.contentOrNull
            ?: return DavOutcome.Failed(DavFailure.Malformed("OneDrive's session reply had no uploadUrl"))
        return DavOutcome.Ok(uploadUrl)
    }

    private suspend fun uploadFragments(
        uploadUrl: String,
        bytes: ByteArray,
        target: RemoteTarget,
    ): DavOutcome<UploadReceipt> {
        val total = bytes.size
        var start = 0
        var last: Reply? = null
        while (start < total) {
            val end = minOf(start + fragmentBytes, total)
            val slice = bytes.copyOfRange(start, end)
            val range = "bytes $start-${end - 1}/$total"
            val sent = sendAnonymous(HttpMethod.Put, uploadUrl, target.path) {
                header("Content-Range", range)
                setBody(slice)
            }
            last = when (sent) {
                is DavOutcome.Ok -> sent.value
                is DavOutcome.Failed -> return sent
            }
            start = end
        }
        val final = last ?: return DavOutcome.Failed(DavFailure.Malformed("nothing was uploaded"))
        if (final.status !in FINAL_STATUSES) {
            return DavOutcome.Failed(
                DavFailure.Malformed("the last fragment was answered ${final.status}, not a commit"),
            )
        }
        var item = Item.of(final.json())
        if (item?.etag == null && item != null) {
            // The commit reply carried no version. Without one the *next* write has nothing to check
            // against, so fetch it rather than record "unknown".
            item = (resolveById(item.id) as? DavOutcome.Ok)?.value ?: item
        }
        return DavOutcome.Ok(UploadReceipt(final.status, item?.etag, item?.id))
    }

    private suspend fun resolveById(id: String): DavOutcome<Item> =
        send(HttpMethod.Get, "$drive/items/$id", id) {}.flatten { Item.of(it.json()) }

    private fun itemUrl(path: String): String = "$drive/root:/${NextcloudUrls.encodePath(path)}:"

    private fun HttpRequestBuilder.jsonBody(body: JsonObject) {
        contentType(ContentType.Application.Json)
        setBody(body.toString())
    }

    // -----------------------------------------------------------------------
    // The one place a request is sent
    // -----------------------------------------------------------------------

    private suspend fun send(
        method: HttpMethod,
        url: String,
        describe: String,
        configure: HttpRequestBuilder.() -> Unit,
    ): DavOutcome<Reply> {
        val token = when (val bearer = tokens.bearer()) {
            is BearerResult.Token -> bearer.value

            // No request is made: the grant is finished, and the drainer's next step is the one
            // confirmation, which answers false without touching the token endpoint again.
            is BearerResult.Dead -> return DavOutcome.Failed(DavFailure.Unauthorized)

            is BearerResult.Unreachable -> return DavOutcome.Failed(DavFailure.Transport(bearer.reason))
        }
        return exchange(method, url, describe) {
            header("Authorization", "Bearer $token")
            configure()
        }
    }

    private suspend fun sendAnonymous(
        method: HttpMethod,
        url: String,
        describe: String,
        configure: HttpRequestBuilder.() -> Unit,
    ): DavOutcome<Reply> = exchange(method, url, describe, configure)

    @Suppress("TooGenericExceptionCaught")
    // A network call fails in as many ways as there are engines; see WebDavClient.callUrl.
    private suspend fun exchange(
        method: HttpMethod,
        url: String,
        describe: String,
        configure: HttpRequestBuilder.() -> Unit,
    ): DavOutcome<Reply> {
        val response = try {
            transport.client.request(url) {
                this.method = method
                configure()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return DavOutcome.Failed(DavFailure.Transport(failure.message ?: failure.toString()))
        }
        val status = response.status.value
        val bytes = try {
            response.readBytes()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return DavOutcome.Failed(DavFailure.Transport(failure.message ?: failure.toString()))
        }
        if (status in SUCCESS_FLOOR until REDIRECT_FLOOR) {
            return DavOutcome.Ok(Reply(status, bytes))
        }
        return DavOutcome.Failed(failureFor(response, status, bytes, describe))
    }

    private suspend fun HttpResponse.readBytes(): ByteArray = body()

    private fun failureFor(response: HttpResponse, status: Int, body: ByteArray, describe: String): DavFailure {
        val code = errorCode(body)
        val detail = body.decodeToString().take(DETAIL_LIMIT).takeIf { it.isNotBlank() }
        return when (status) {
            HTTP_UNAUTHORIZED -> DavFailure.Unauthorized

            HTTP_FORBIDDEN -> DavFailure.Forbidden(detail)

            HTTP_NOT_FOUND -> DavFailure.NotFound(describe)

            // 409 nameAlreadyExists is a create over something that exists; 412 is a stale If-Match.
            // Both are "someone else got there first", and both are conflicts rather than retries.
            HTTP_PRECONDITION_FAILED -> DavFailure.PreconditionFailed(null)

            HTTP_CONFLICT ->
                if (code ==
                    "nameAlreadyExists"
                ) {
                    DavFailure.PreconditionFailed(null)
                } else {
                    DavFailure.Server(status, detail)
                }

            HTTP_LOCKED -> DavFailure.Locked(null)

            HTTP_TOO_MANY_REQUESTS -> DavFailure.RateLimited(response.headers["Retry-After"]?.toLongOrNull())

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

    private class Reply(val status: Int, val bytes: ByteArray) {
        fun json(): JsonElement = try {
            KJson.parseToJsonElement(bytes.decodeToString())
        } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") ignored: Exception) {
            JsonNull
        }
    }

    private operator fun JsonElement.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

    private inline fun <T> DavOutcome<Reply>.flatten(parse: (Reply) -> T?): DavOutcome<T> = when (this) {
        is DavOutcome.Ok -> parse(value)?.let { DavOutcome.Ok(it) }
            ?: DavOutcome.Failed(DavFailure.Malformed("OneDrive's reply could not be read"))

        is DavOutcome.Failed -> this
    }

    /** What the store needs of a driveItem. Everything else Graph says is ignored. */
    private class Item(
        val id: String,
        val etag: ETag?,
        val isFolder: Boolean,
        val name: String,
        val size: Long?,
        val modified: String? = null,
        val downloadUrl: String? = null,
    ) {
        fun toResource(path: String) = DavResource(
            path = path,
            href = id,
            isCollection = isFolder,
            etag = etag,
            lastModified = modified,
            contentLength = size,
            ocId = id,
            fileId = id,
            permissions = null,
            recursiveSize = null,
            checksums = emptyList(),
            dataFingerprint = null,
            lockOwner = null,
            locked = false,
            quotaAvailableBytes = null,
        )

        companion object {
            fun of(element: JsonElement): Item? {
                val obj = element as? JsonObject ?: return null
                val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return null
                return Item(
                    id = id,
                    etag = ETag.parse((obj["eTag"] as? JsonPrimitive)?.contentOrNull),
                    isFolder = obj["folder"] is JsonObject,
                    name = (obj["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    size = (obj["size"] as? JsonPrimitive)?.longOrNull,
                    modified = (obj["lastModifiedDateTime"] as? JsonPrimitive)?.contentOrNull,
                    downloadUrl = (obj["@microsoft.graph.downloadUrl"] as? JsonPrimitive)?.contentOrNull,
                )
            }
        }
    }

    internal companion object {
        /** 32 × 320 KiB = 10 MiB: inside Graph's recommended 5–10 MiB and its 60 MiB request limit. */
        const val FRAGMENT_UNIT = 327_680
        const val FRAGMENT_BYTES = 32 * FRAGMENT_UNIT
        const val PAGE_SIZE = 200
        const val MAX_PAGES = 500
        const val DETAIL_LIMIT = 500
        const val SUCCESS_FLOOR = 200
        const val REDIRECT_FLOOR = 300
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val HTTP_PRECONDITION_FAILED = 412
        const val HTTP_LOCKED = 423
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_INSUFFICIENT_STORAGE = 507
        val FINAL_STATUSES = setOf(200, 201)
    }
}

/**
 * Opens OneDrive accounts. Keeps **one [MicrosoftTokenSource] per account for the life of the
 * process**, so an access token is reused across drain passes and a refresh token is not rotated every
 * time a store is opened.
 *
 * Declines (null) an account that is not OneDrive, has no resolved drive id yet (`uid` empty, the
 * `NEEDS_UID` gate), or has no refresh token — the same "stop touching this account" answer the
 * Nextcloud factory gives for a missing credential.
 */
public class OneDriveStoreFactory(
    private val config: MicrosoftOAuthConfig,
    private val vault: TokenVault,
    private val transport: NextcloudTransport,
    private val clock: Clock,
) : CloudStoreFactory {

    private val lock = Mutex()
    private val sources = HashMap<String, MicrosoftTokenSource>()

    override suspend fun open(account: SyncAccount): CloudStore? {
        if (account.provider != ProviderKind.ONEDRIVE || account.uid.isEmpty()) return null
        if (vault.get(account.id) == null) return null
        val source = lock.withLock {
            sources.getOrPut(account.id) { MicrosoftTokenSource(transport, config, vault, clock, account.id) }
        }
        return OneDriveStore(account, transport, source)
    }

    /** Forget an account's cached token state — after a sign-in or a sign-out. */
    public suspend fun forget(accountId: String) {
        lock.withLock { sources.remove(accountId) }
    }
}
