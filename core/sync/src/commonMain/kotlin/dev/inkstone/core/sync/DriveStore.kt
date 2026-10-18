package dev.inkstone.core.sync

import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json as KJson

/**
 * Google Drive v3 as a [CloudStore] (docs/CLOUD-PROVIDERS.md §3.2, §4).
 *
 * ## ⚠️ This store cannot honour CLAUDE.md rule 1 the way the others do — and says so
 *
 * Drive has **no precondition on a content update** (an `If-Match` is accepted and ignored). So an
 * overwrite is *check, write pinned, verify* — the exception rule 1 now records, for this provider alone:
 *
 * 1. **Check.** Read `headRevisionId`; if it is not the version the binding last saw, nothing is written
 *    and the answer is [DavFailure.PreconditionFailed] — the 412 the other providers get from the server.
 * 2. **Write pinned.** The update asks Drive to keep the new head revision forever.
 * 3. **Verify.** List the revisions and look at the one *immediately before ours*. If it is the version
 *    we based the write on, nobody got in between. If it is not, a concurrent writer landed inside the
 *    window: their revision is pinned (so Drive will not purge it), downloaded, and written beside the
 *    note as `… (displaced <time>).pdf`. **Nothing is discarded.** The window between check and write is
 *    not closed; it is made *detectable and recoverable*.
 * 4. **Unpin the revision we replaced**, so pinned revisions do not pile up toward Drive's limit of 200.
 *    That is `keepForever=false`, never a delete: Drive then purges it on its own schedule.
 *
 * ## Identity and versions
 *
 * A file is addressed by **id**. Its version tag is `headRevisionId`, deliberately not Drive's
 * `version`: that moves on a rename or a star, and a rename is not a change of content. Names are not
 * unique in a folder on Drive, so a path resolves to the **oldest** match and a create refuses if the name
 * is already there.
 *
 * ## What it never does
 *
 * - **No hard delete.** [trash] sets `trashed`; `files.delete` is not called anywhere.
 * - **No automatic retries**, and a 401 is [DavFailure.Unauthorized]; the one refresh it may cause is in
 *   [confirmStillAuthorised], under the rules of the [AccessTokenSource].
 * - **No scope beyond `drive.file`**, so it sees only files this app created (D3).
 *
 * ⚠️ **Nothing here has been run against Google** (docs §9, M-G1…): the ignored `If-Match`, that
 * `keepRevisionForever` pins the new head, the ordering of `revisions.list`, that a resumable session URI
 * needs no bearer token, and every status a stale feed token answers with. A green test means the store
 * does what this comment says *as the test stand-in encodes it*.
 */
// TooManyFunctions: a store is one method per remote operation plus the few request helpers they share, as
// OneDriveStore is; splitting by count would scatter the one overwrite protocol across files.
@Suppress("TooManyFunctions")
public class DriveStore(
    override val account: SyncAccount,
    private val transport: NextcloudTransport,
    private val tokens: AccessTokenSource,
) : CloudStore {

    override val kind: ProviderKind get() = ProviderKind.GOOGLE_DRIVE

    override val supportsUncheckedReplace: Boolean get() = false

    override val pullShape: PullShape get() = PullShape.FEED

    private val base: String get() = account.serverUrl.trimEnd('/')

    private val files: String get() = "$base/drive/v3/files"

    private val uploads: String get() = "$base/upload/drive/v3/files"

    /** Folder path → id for this store (one pass). Ids never change; a path can. */
    private val folderIds = HashMap<String, String>()

    // -----------------------------------------------------------------------
    // Writes
    // -----------------------------------------------------------------------

    override suspend fun ensureFolder(path: String): DavOutcome<Unit> {
        var built = ""
        var parent = ROOT
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            built = if (built.isEmpty()) segment else "$built/$segment"
            parent = folderIds[built] ?: when (val folder = childFolder(parent, segment, create = true)) {
                is DavOutcome.Ok -> folder.value.also { folderIds[built] = it }
                is DavOutcome.Failed -> return folder
            }
        }
        return DavOutcome.Ok(Unit)
    }

    @Suppress("ReturnCount")
    override suspend fun put(
        target: RemoteTarget,
        bytes: ByteArray,
        precondition: WritePrecondition,
        mtimeEpochSeconds: Long?,
        md5Hex: String?,
        kind: UploadKind,
    ): DavOutcome<UploadReceipt> {
        if (kind != UploadKind.PDF) {
            return DavOutcome.Failed(DavFailure.Forbidden("Google Drive does not store lecture recordings yet"))
        }
        return when (precondition) {
            WritePrecondition.Unchecked -> DavOutcome.Failed(
                DavFailure.Forbidden("Google Drive will not overwrite a file without a version to check"),
            )

            WritePrecondition.CreateOnly -> create(target.path, bytes)

            is WritePrecondition.IfMatch -> {
                if (precondition.etag.weak) {
                    return DavOutcome.Failed(DavFailure.Forbidden("a weak version cannot guard an overwrite"))
                }
                replace(target, bytes, precondition.etag.value)
            }
        }
    }

    @Suppress("ReturnCount") // Each return is a different reason a create cannot go ahead, in order.
    private suspend fun create(path: String, bytes: ByteArray): DavOutcome<UploadReceipt> {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val name = segments.lastOrNull() ?: return DavOutcome.Failed(DavFailure.Forbidden("nowhere to put the file"))
        val parentPath = segments.dropLast(1).joinToString("/")
        val parent = when (val found = folderId(parentPath, create = true)) {
            is DavOutcome.Ok -> found.value
            is DavOutcome.Failed -> return found
        }
        // A create is a create: Drive happily makes a second file of the same name, so the check is ours.
        when (val clash = children(parent, name)) {
            is DavOutcome.Ok -> if (clash.value.isNotEmpty()) {
                return DavOutcome.Failed(DavFailure.PreconditionFailed(clash.value.first().head))
            }

            is DavOutcome.Failed -> return clash
        }
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", PDF)
            put("parents", buildJsonArray { add(JsonPrimitive(parent)) })
        }
        val reply = when (val sent = resumable(HttpMethod.Post, pinnedCreate, metadata, bytes, path)) {
            is DavOutcome.Ok -> sent.value
            is DavOutcome.Failed -> return sent
        }
        val made = Entry.of(reply.json())
            ?: return DavOutcome.Failed(DavFailure.Malformed("Drive's create reply had no id"))
        return DavOutcome.Ok(UploadReceipt(reply.status, ETag.parse(made.head), made.id))
    }

    /** Check, write pinned, verify — see the class comment. */
    @Suppress("ReturnCount")
    private suspend fun replace(
        target: RemoteTarget,
        bytes: ByteArray,
        expectedHead: String,
    ): DavOutcome<UploadReceipt> {
        val before = when (val found = entryFor(target)) {
            is DavOutcome.Ok -> found.value
            is DavOutcome.Failed -> return found
        }
        if (before.trashed) return DavOutcome.Failed(DavFailure.NotFound(target.path))
        // The check. Found without writing anything, so a change is a conflict and not an overwrite.
        if (before.head != expectedHead) return DavOutcome.Failed(DavFailure.PreconditionFailed(before.head))

        val written = when (
            val sent = resumable(
                HttpMethod.Patch,
                "$uploads/${before.id}?uploadType=resumable&keepRevisionForever=true",
                null,
                bytes,
                target.path,
            )
        ) {
            is DavOutcome.Ok -> Entry.of(sent.value.json())
                ?: return DavOutcome.Failed(DavFailure.Malformed("Drive's update reply had no id"))

            is DavOutcome.Failed -> return sent
        }
        val ours = written.head
            ?: return DavOutcome.Failed(DavFailure.Malformed("Drive's update reply had no revision"))
        return when (val secured = verify(before, expectedHead, ours)) {
            is DavOutcome.Ok -> DavOutcome.Ok(UploadReceipt(HTTP_OK, ETag.parse(ours), before.id))
            is DavOutcome.Failed -> secured
        }
    }

    /**
     * Step 3 and 4. See the class comment. Returns Ok when our write is the head *and* anything it
     * displaced is secured; Failed only when a displaced revision could not be pinned, in which case it
     * is still in Drive's history (purgeable after ~30 days) and the conflict path will find it.
     */
    private suspend fun verify(before: Entry, basedOn: String, ours: String): DavOutcome<Unit> {
        val revisions = when (val listed = revisions(before.id)) {
            is DavOutcome.Ok -> listed.value

            // Could not look. The write is done and pinned; refusing to say so would invite a duplicate write.
            is DavOutcome.Failed -> return DavOutcome.Ok(Unit)
        }
        val at = revisions.indexOfFirst { it.id == ours }
        if (at < 0) return DavOutcome.Ok(Unit)
        val basedAt = revisions.indexOfFirst { it.id == basedOn }
        val displaced = when {
            at > 0 && revisions[at - 1].id == basedOn -> emptyList()
            basedAt in 0 until at -> revisions.subList(basedAt + 1, at)
            else -> listOfNotNull(revisions.getOrNull(at - 1))
        }
        for (revision in displaced) {
            when (val saved = keepDisplaced(before, revision)) {
                is DavOutcome.Failed -> return saved
                is DavOutcome.Ok -> Unit
            }
        }
        // Best effort, and never a delete: stops pinned revisions piling up toward Drive's cap.
        if (displaced.isEmpty()) unpin(before.id, basedOn)
        return DavOutcome.Ok(Unit)
    }

    /** Pin a revision someone else wrote, read it, and put it beside the note so it is a file the user can open. */
    private suspend fun keepDisplaced(entry: Entry, revision: Revision): DavOutcome<Unit> {
        val pinned = send(
            HttpMethod.Patch,
            "$files/${entry.id}/revisions/${revision.id}",
            entry.name,
        ) { jsonBody(buildJsonObject { put("keepForever", true) }) }
        if (pinned is DavOutcome.Failed) return pinned
        val mediaUrl = "$files/${entry.id}/revisions/${revision.id}?alt=media"
        val bytes = when (val got = send(HttpMethod.Get, mediaUrl, entry.name) {}) {
            is DavOutcome.Ok -> got.value.bytes
            is DavOutcome.Failed -> return got
        }
        val parent = entry.parents.firstOrNull() ?: ROOT
        val stem = entry.name.removeSuffix(".pdf").removeSuffix(".PDF")
        val name = "$stem (displaced ${revision.modified?.replace(':', '-').orEmpty()}).pdf"
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", PDF)
            put("parents", buildJsonArray { add(JsonPrimitive(parent)) })
        }
        return resumable(HttpMethod.Post, pinnedCreate, metadata, bytes, name).map { }
    }

    private suspend fun unpin(id: String, revisionId: String) {
        send(HttpMethod.Patch, "$files/$id/revisions/$revisionId", revisionId) {
            jsonBody(buildJsonObject { put("keepForever", false) })
        }
    }

    @Suppress("ReturnCount") // As create: each return is a distinct reason a move cannot go ahead.
    override suspend fun move(from: RemoteTarget, toPath: String): DavOutcome<UploadReceipt> {
        val source = when (val found = entryFor(from)) {
            is DavOutcome.Ok -> found.value
            is DavOutcome.Failed -> return found
        }
        val segments = toPath.split('/').filter { it.isNotEmpty() }
        val name = segments.lastOrNull() ?: return DavOutcome.Failed(DavFailure.Forbidden("nowhere to move to"))
        val parent = when (val found = folderId(segments.dropLast(1).joinToString("/"), create = true)) {
            is DavOutcome.Ok -> found.value
            is DavOutcome.Failed -> return found
        }
        // "Never overwrite": Drive allows two files of one name, so the refusal has to be ours.
        when (val clash = children(parent, name)) {
            is DavOutcome.Ok -> if (clash.value.any { it.id != source.id }) {
                return DavOutcome.Failed(DavFailure.PreconditionFailed(null))
            }

            is DavOutcome.Failed -> return clash
        }
        val removeParents = source.parents.joinToString(",").encodeURLParameter()
        val url = "$files/${source.id}?addParents=${parent.encodeURLParameter()}&removeParents=$removeParents" +
            "&fields=${FILE_FIELDS.encodeURLParameter()}"
        val rename = buildJsonObject { put("name", name) }
        val reply = when (val sent = send(HttpMethod.Patch, url, toPath) { jsonBody(rename) }) {
            is DavOutcome.Ok -> sent.value
            is DavOutcome.Failed -> return sent
        }
        val moved = Entry.of(reply.json())
        return DavOutcome.Ok(UploadReceipt(reply.status, ETag.parse(moved?.head), moved?.id ?: source.id))
    }

    override suspend fun trash(target: RemoteTarget): DavOutcome<Unit> {
        val id = when (val found = entryFor(target)) {
            is DavOutcome.Ok -> found.value.id
            is DavOutcome.Failed -> return found
        }
        // `trashed`, never `files.delete` (permanent). CLAUDE.md rule 1.
        return send(HttpMethod.Patch, "$files/$id", target.path) { jsonBody(buildJsonObject { put("trashed", true) }) }
            .map { }
    }

    /** SPEC §7.2's one allowed question after a 401: did the grant survive, and does Drive accept it? */
    override suspend fun confirmStillAuthorised(): Boolean? = when (tokens.refreshAfterRefusal()) {
        is ForcedRefresh.Dead, ForcedRefresh.CoolingDown -> false

        is ForcedRefresh.Unreachable -> null

        ForcedRefresh.Refreshed ->
            when (val probe = send(HttpMethod.Get, aboutUrl(), "about") {}) {
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
        ListingDepth.SELF -> entryAt(path).map { listOf(it.toResource(path)) }

        ListingDepth.CHILDREN -> when (val folder = folderId(path, create = false)) {
            is DavOutcome.Ok -> children(folder.value, null).map { all ->
                all.map { it.toResource(childPath(path, it.name)) }
            }

            is DavOutcome.Failed -> folder
        }
    }

    override suspend fun download(path: String): DavOutcome<DownloadedFile> {
        val entry = when (val found = entryAt(path)) {
            is DavOutcome.Ok -> found.value
            is DavOutcome.Failed -> return found
        }
        return send(HttpMethod.Get, "$files/${entry.id}?alt=media", path) {}
            .map { DownloadedFile(it.bytes, ETag.parse(entry.head), entry.id) }
    }

    override suspend fun locate(fileId: Long): DavOutcome<List<DavResource>> = DavOutcome.Failed(
        DavFailure.Malformed("Google Drive has no file-id search; its pull is the changes feed"),
    )

    /** The changes feed, read to the end. [firstPassScope] is unused: `drive.file` already limits what is visible. */
    override suspend fun changes(since: String?, firstPassScope: List<String>): DavOutcome<ChangeBatch> =
        feedReader.read(since)

    private val feedReader: DriveFeedReader by lazy {
        DriveFeedReader(base) { url, describe -> send(HttpMethod.Get, url, describe) {}.flatten { it.json() } }
    }

    // -----------------------------------------------------------------------
    // Pieces
    // -----------------------------------------------------------------------

    /** The folder at [path] (`""` is My Drive), creating the missing ones only when [create]. */
    private suspend fun folderId(path: String, create: Boolean): DavOutcome<String> {
        val normal = path.split('/').filter { it.isNotEmpty() }.joinToString("/")
        if (normal.isEmpty()) return DavOutcome.Ok(ROOT)
        folderIds[normal]?.let { return DavOutcome.Ok(it) }
        var built = ""
        var parent = ROOT
        for (segment in normal.split('/')) {
            built = if (built.isEmpty()) segment else "$built/$segment"
            parent = folderIds[built] ?: when (val folder = childFolder(parent, segment, create)) {
                is DavOutcome.Ok -> folder.value.also { folderIds[built] = it }
                is DavOutcome.Failed -> return folder
            }
        }
        return DavOutcome.Ok(parent)
    }

    private suspend fun childFolder(parent: String, name: String, create: Boolean): DavOutcome<String> {
        val found = when (val listed = children(parent, name)) {
            is DavOutcome.Ok -> listed.value.filter { it.folder }
            is DavOutcome.Failed -> return listed
        }
        oldest(found)?.let { return DavOutcome.Ok(it.id) }
        if (!create) return DavOutcome.Failed(DavFailure.NotFound(name))
        val body = buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER)
            put("parents", buildJsonArray { add(JsonPrimitive(parent)) })
        }
        return send(HttpMethod.Post, "$files?fields=${"id".encodeURLParameter()}", name) { jsonBody(body) }
            .flatten { it.json().let { json -> (json["id"] as? JsonPrimitive)?.contentOrNull } }
    }

    /** Live (untrashed) children of [parent], optionally only those called [name]. All pages. */
    private suspend fun children(parent: String, name: String?): DavOutcome<List<Entry>> {
        val q = buildString {
            append("'${parent.quoted()}' in parents and trashed=false")
            if (name != null) append(" and name='${name.quoted()}'")
        }
        return list(q)
    }

    private suspend fun list(q: String): DavOutcome<List<Entry>> {
        val out = ArrayList<Entry>()
        var pageToken: String? = null
        var guard = 0
        do {
            val token = pageToken?.let { "&pageToken=${it.encodeURLParameter()}" }.orEmpty()
            val fields = LIST_FIELDS.encodeURLParameter()
            val url = "$files?q=${q.encodeURLParameter()}&fields=$fields&pageSize=$PAGE_SIZE$token"
            val json = when (val sent = send(HttpMethod.Get, url, "files") {}) {
                is DavOutcome.Ok -> sent.value.json()
                is DavOutcome.Failed -> return sent
            }
            for (element in (json["files"] as? JsonArray).orEmpty()) Entry.of(element)?.let(out::add)
            pageToken = (json["nextPageToken"] as? JsonPrimitive)?.contentOrNull
        } while (pageToken != null && guard++ < MAX_PAGES)
        return DavOutcome.Ok(out)
    }

    /** What a path names, folder or file: the oldest match (names are not unique on Drive). */
    private suspend fun entryAt(path: String): DavOutcome<Entry> {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val name = segments.lastOrNull() ?: return entryById(ROOT)
        val parent = when (val found = folderId(segments.dropLast(1).joinToString("/"), create = false)) {
            is DavOutcome.Ok -> found.value
            is DavOutcome.Failed -> return found
        }
        return when (val listed = children(parent, name)) {
            is DavOutcome.Ok ->
                oldest(listed.value)?.let { DavOutcome.Ok(it) } ?: DavOutcome.Failed(DavFailure.NotFound(path))

            is DavOutcome.Failed -> listed
        }
    }

    private suspend fun entryFor(target: RemoteTarget): DavOutcome<Entry> =
        target.id?.let { entryById(it) } ?: entryAt(target.path)

    private suspend fun entryById(id: String): DavOutcome<Entry> =
        send(HttpMethod.Get, "$files/$id?fields=${FILE_FIELDS.encodeURLParameter()}", id) {}
            .flatten { Entry.of(it.json()) }

    private suspend fun revisions(id: String): DavOutcome<List<Revision>> {
        val out = ArrayList<Revision>()
        var pageToken: String? = null
        var guard = 0
        do {
            val token = pageToken?.let { "&pageToken=${it.encodeURLParameter()}" }.orEmpty()
            val fields = "nextPageToken,revisions(id,modifiedTime)".encodeURLParameter()
            val url = "$files/$id/revisions?fields=$fields&pageSize=$REVISION_PAGE$token"
            val json = when (val sent = send(HttpMethod.Get, url, id) {}) {
                is DavOutcome.Ok -> sent.value.json()
                is DavOutcome.Failed -> return sent
            }
            for (element in (json["revisions"] as? JsonArray).orEmpty()) Revision.of(element)?.let(out::add)
            pageToken = (json["nextPageToken"] as? JsonPrimitive)?.contentOrNull
        } while (pageToken != null && guard++ < MAX_PAGES)
        // Drive does not document an order; time is what "immediately before" means.
        return DavOutcome.Ok(out.sortedBy { it.modified.orEmpty() })
    }

    private fun oldest(entries: List<Entry>): Entry? =
        entries.minWithOrNull(compareBy({ it.created.orEmpty() }, { it.id }))

    private fun childPath(parent: String, name: String): String =
        listOf(parent.trim('/'), name).filter { it.isNotEmpty() }.joinToString("/")

    private fun aboutUrl(): String = "$base/drive/v3/about?fields=${"user(permissionId)".encodeURLParameter()}"

    private val pinnedCreate: String get() = "$uploads?uploadType=resumable&keepRevisionForever=true"

    private fun String.quoted(): String = replace("\\", "\\\\").replace("'", "\\'")

    /**
     * Initiate a resumable upload and send the bytes in one request.
     *
     * Resumable for every size: Drive documents multipart as the small-file route and resumable for
     * anything that must not be lost to a dropped connection, and this app's notes are tens of MB.
     * The session URI is on Google's own host or the upload is refused — a reply must not be able to point
     * the bytes elsewhere — and it is sent no bearer token (it is the credential for that one session).
     */
    private suspend fun resumable(
        method: HttpMethod,
        url: String,
        metadata: JsonObject?,
        bytes: ByteArray,
        describe: String,
    ): DavOutcome<Reply> {
        val fields = "&fields=${FILE_FIELDS.encodeURLParameter()}"
        val started = when (
            val sent = send(method, url + fields, describe) {
                header("X-Upload-Content-Type", PDF)
                header("X-Upload-Content-Length", bytes.size.toString())
                jsonBody(metadata ?: JsonObject(emptyMap()))
            }
        ) {
            is DavOutcome.Ok -> sent.value
            is DavOutcome.Failed -> return sent
        }
        val session = started.location ?: return DavOutcome.Failed(DavFailure.Malformed("Drive gave no upload session"))
        if (!session.startsWith(base)) {
            return DavOutcome.Failed(DavFailure.Malformed("Drive's upload session was on another host"))
        }
        return exchange(HttpMethod.Put, session, describe) {
            contentType(ContentType.Application.Pdf)
            setBody(bytes)
        }
    }

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
            is BearerResult.Dead -> return DavOutcome.Failed(DavFailure.Unauthorized)
            is BearerResult.Unreachable -> return DavOutcome.Failed(DavFailure.Transport(bearer.reason))
        }
        return exchange(method, url, describe) {
            header("Authorization", "Bearer $token")
            configure()
        }
    }

    @Suppress("TooGenericExceptionCaught")
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
        val bytes: ByteArray = try {
            response.body()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return DavOutcome.Failed(DavFailure.Transport(failure.message ?: failure.toString()))
        }
        if (status in SUCCESS_FLOOR until REDIRECT_FLOOR) {
            return DavOutcome.Ok(Reply(status, bytes, response.headers["Location"]))
        }
        return DavOutcome.Failed(GoogleFailures.of(status, response.headers["Retry-After"], bytes, describe))
    }

    private class Reply(val status: Int, val bytes: ByteArray, val location: String?) {
        fun json(): JsonElement = try {
            KJson.parseToJsonElement(bytes.decodeToString())
        } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") ignored: Exception) {
            JsonNull
        }
    }

    private operator fun JsonElement.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

    private inline fun <T> DavOutcome<Reply>.flatten(parse: (Reply) -> T?): DavOutcome<T> = when (this) {
        is DavOutcome.Ok -> parse(value)?.let { DavOutcome.Ok(it) }
            ?: DavOutcome.Failed(DavFailure.Malformed("Google Drive's reply could not be read"))

        is DavOutcome.Failed -> this
    }

    private class Revision(val id: String, val modified: String?) {
        companion object {
            fun of(element: JsonElement): Revision? {
                val obj = element as? JsonObject ?: return null
                val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return null
                return Revision(id, (obj["modifiedTime"] as? JsonPrimitive)?.contentOrNull)
            }
        }
    }

    /** What the store needs of a Drive `File`. Everything else Google says is ignored. */
    private class Entry(
        val id: String,
        val name: String,
        val folder: Boolean,
        val parents: List<String>,
        val head: String?,
        val size: Long?,
        val modified: String?,
        val created: String?,
        val trashed: Boolean,
    ) {
        fun toResource(path: String) = DavResource(
            path = path,
            href = id,
            isCollection = folder,
            etag = ETag.parse(head),
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
            fun of(element: JsonElement): Entry? {
                val obj = element as? JsonObject ?: return null
                val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return null
                return Entry(
                    id = id,
                    name = (obj["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    folder = (obj["mimeType"] as? JsonPrimitive)?.contentOrNull == FOLDER,
                    parents = (obj["parents"] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                    head = (obj["headRevisionId"] as? JsonPrimitive)?.contentOrNull,
                    size = (obj["size"] as? JsonPrimitive)?.longOrNull,
                    modified = (obj["modifiedTime"] as? JsonPrimitive)?.contentOrNull,
                    created = (obj["createdTime"] as? JsonPrimitive)?.contentOrNull,
                    trashed = (obj["trashed"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }
        }
    }

    internal companion object {
        const val ROOT = "root"
        const val PDF = "application/pdf"
        const val FOLDER = "application/vnd.google-apps.folder"
        const val FILE_FIELDS = "id,name,mimeType,parents,headRevisionId,size,modifiedTime,createdTime,trashed"
        const val LIST_FIELDS = "nextPageToken,files($FILE_FIELDS)"
        const val PAGE_SIZE = 200
        const val REVISION_PAGE = 200
        const val MAX_PAGES = 500
        const val HTTP_OK = 200
        const val SUCCESS_FLOOR = 200
        const val REDIRECT_FLOOR = 300
    }
}

/**
 * Opens Google Drive accounts. [tokensFor] is `:app`'s: it hands back the account's [AccessTokenSource]
 * (Play Services' `AuthorizationClient`, wrapped), or null when this device holds no grant for it — the
 * same "stop touching this account" answer the other factories give for a missing credential.
 */
public class DriveStoreFactory(
    private val transport: NextcloudTransport,
    private val tokensFor: suspend (SyncAccount) -> AccessTokenSource?,
) : CloudStoreFactory {

    override suspend fun open(account: SyncAccount): CloudStore? {
        if (account.provider != ProviderKind.GOOGLE_DRIVE || account.uid.isEmpty()) return null
        val tokens = tokensFor(account) ?: return null
        return DriveStore(account, transport, tokens)
    }
}
