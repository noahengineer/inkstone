package dev.inkstone.core.sync

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.ExtractedRemote
import dev.inkstone.core.model.RemoteBundleVerdict
import dev.inkstone.core.model.WorkScheduler
import dev.inkstone.core.storage.MergeReport
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf

/*
 * A scriptable Nextcloud for the reconciler's own logic.
 *
 * SPEC §13 requires the *merge matrix* to run against a real Nextcloud in Docker, and M6 does that
 * in `:app`'s test source set, where a real `NoteStore` and a real materializer are on the
 * classpath. What a mock is genuinely the better tool for is everything here: it can emit a **weak**
 * ETag, present one `oc:id` at a new path, return a 401 exactly once, or move
 * `nc:data-fingerprint` — none of which a healthy server will do to order, and every one of which
 * is a branch where being wrong is silent.
 *
 * Block comment, not KDoc: a file-level KDoc attaches to no declaration and ktlint rejects it.
 */

/**
 * One resource as a server would describe it.
 *
 * @param fileId ⚠️ `oc:fileid`, stated **explicitly** rather than derived from [ocId] — which is
 *   what a `SEARCH` matches on (SPEC §7.4 rule 2). Deriving it here would mean the fixture and
 *   [RemoteBinding.remoteFileId] shared one reading of `oc:id`'s format, so a test could pass with
 *   both of them wrong. Null means "this server publishes no fileid for it", which is a real case:
 *   the property is a 404 propstat inside a `SEARCH` select on some backends.
 */
internal class Remote(
    val path: String,
    val isCollection: Boolean,
    val etag: String?,
    val ocId: String?,
    val weak: Boolean = false,
    val md5: String? = null,
    val fingerprint: String? = null,
    val fileId: Long? = null,
)

/**
 * A tree, plus the two ways a server can refuse.
 *
 * Deliberately not a `WebDavClient` stand-in: the real client, the real PROPFIND body and the real
 * multistatus parser are all in the loop, so a test that passes here has exercised SPEC §7.3's
 * parsing as well as the walk. Only the socket is fake.
 */
internal class FakeServer {
    private val resources = LinkedHashMap<String, Remote>()
    private val children = LinkedHashMap<String, MutableList<String>>()
    private val bytes = LinkedHashMap<String, ByteArray>()
    private val statusFor = LinkedHashMap<String, HttpStatusCode>()
    private val onGet = LinkedHashMap<String, String>()

    /** How many `GET /ocs/v2.php/cloud/user` calls were made. SPEC §7.2 allows exactly one. */
    var uidProbes: Int = 0
        private set

    private var uidStatus: HttpStatusCode = HttpStatusCode.OK

    private var searchStatus: HttpStatusCode? = null

    /** Make SPEC §7.2's confirmation call agree that the app password is dead. */
    fun refusingUid(): FakeServer {
        uidStatus = HttpStatusCode.Unauthorized
        return this
    }

    /**
     * An instance with no search backend — SPEC §7.4 rule 2's fallback has to survive one.
     *
     * 501 by default because that is what a Sabre server with the plugin absent answers; 405 and a
     * proxy that blocks unknown methods land in the same branch, and all three must mean *"cannot
     * tell"* rather than *"deleted"*.
     */
    fun refusingSearch(status: HttpStatusCode = HttpStatusCode.NotImplemented): FakeServer {
        searchStatus = status
        return this
    }

    fun directory(
        path: String,
        etag: String,
        ocId: String,
        weak: Boolean = false,
        fingerprint: String? = null,
    ): FakeServer {
        put(Remote(path, isCollection = true, etag = etag, ocId = ocId, weak = weak, fingerprint = fingerprint))
        return this
    }

    /**
     * @param getEtag what the **GET** reports, when it differs from what the PROPFIND advertised.
     *   That happens whenever somebody saves between the two calls, and it is the reason
     *   [WebDavClient.download] exists rather than `get()`.
     */
    @Suppress("LongParameterList") // One row of a PROPFIND, and every column is asserted on somewhere.
    fun file(
        path: String,
        etag: String,
        ocId: String?,
        content: ByteArray = "pdf-$path".encodeToByteArray(),
        weak: Boolean = false,
        md5: String? = null,
        getEtag: String? = null,
        fileId: Long? = null,
    ): FakeServer {
        put(Remote(path, isCollection = false, etag = etag, ocId = ocId, weak = weak, md5 = md5, fileId = fileId))
        bytes[path] = content
        getEtag?.let { onGet[path] = it }
        return this
    }

    /**
     * Move a file the way the Nextcloud web UI does: new href, **same `oc:id`, same ETag**.
     *
     * SPEC §7.4 rule 2, and the reason it is one method rather than a `file()` call at the new path —
     * a fixture that re-declared the file would be free to give it a fresh validator, and then a test
     * about move detection would be testing a change instead.
     */
    fun moveFile(from: String, to: String): FakeServer {
        val stored = resources.getValue(from)
        resources.remove(from)
        children[from.substringBeforeLast('/', "")]?.remove(from)
        bytes.remove(from)?.let { bytes[to] = it }
        put(
            Remote(
                path = to,
                isCollection = false,
                etag = stored.etag,
                ocId = stored.ocId,
                weak = stored.weak,
                md5 = stored.md5,
                fileId = stored.fileId,
            ),
        )
        return this
    }

    /** Make one path answer with [status] — a 401, a 503, whatever the branch under test needs. */
    fun refusing(path: String, status: HttpStatusCode): FakeServer {
        statusFor[path] = status
        return this
    }

    private fun put(resource: Remote) {
        resources[resource.path] = resource
        if (resource.path.isEmpty()) return
        val parent = resource.path.substringBeforeLast('/', "")
        children.getOrPut(parent) { mutableListOf() }.let { if (resource.path !in it) it += resource.path }
    }

    fun handle(request: HttpRequestData): MockRequestHandleScope.() -> HttpResponseData = {
        val encoded = request.url.encodedPath
        when {
            encoded.endsWith("/cloud/user") -> {
                uidProbes++
                if (uidStatus == HttpStatusCode.OK) {
                    json("""{"ocs":{"data":{"id":"noah","display-name":"Noah"}}}""")
                } else {
                    respondError(uidStatus)
                }
            }

            request.method.value == "PROPFIND" -> propfind(pathOf(encoded), request.headers["Depth"])

            // ⚠️ Not `pathOf`: a SEARCH is addressed at `/remote.php/dav/`, which has no uid in it,
            // so `pathFromHref` answers null. See [NextcloudUrls.davRoot].
            request.method.value == "SEARCH" -> search(request)

            request.method.value == "GET" -> get(pathOf(encoded))

            else -> respondError(HttpStatusCode.MethodNotAllowed)
        }
    }

    private fun pathOf(encodedPath: String): String = NextcloudUrls.pathFromHref(encodedPath, testCredential.uid) ?: ""

    private fun MockRequestHandleScope.propfind(path: String, depth: String?): HttpResponseData {
        statusFor[path]?.let { return respondError(it) }
        val self = resources[path] ?: return respondError(HttpStatusCode.NotFound)
        val shown = if (depth ==
            "0"
        ) {
            listOf(self)
        } else {
            listOf(self) + children[path].orEmpty().mapNotNull(resources::get)
        }
        return xml(multistatus(shown))
    }

    /**
     * RFC 5323 `SEARCH` over `oc:fileid` — SPEC §7.4 rule 2 asked without a path.
     *
     * A fileid with nothing at it answers **207 with an empty multistatus**, exactly as the real
     * Nextcloud 32.0.13 does (measured), because that is the only thing that distinguishes *"the
     * file is genuinely gone"* from *"this server cannot answer"*.
     */
    private fun MockRequestHandleScope.search(request: HttpRequestData): HttpResponseData {
        searchStatus?.let { return respondError(it) }
        val wanted = fileIdIn(bodyOf(request))
        val hits = if (wanted == null) emptyList() else resources.values.filter { it.fileId == wanted }
        return xml(multistatus(hits))
    }

    private fun bodyOf(request: HttpRequestData): String =
        (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()

    private fun fileIdIn(body: String): Long? = body
        .substringAfter("<d:literal>", "")
        .substringBefore("</d:literal>", "")
        .trim()
        .toLongOrNull()

    private fun MockRequestHandleScope.get(path: String): HttpResponseData {
        statusFor[path]?.let { return respondError(it) }
        val content = bytes[path] ?: return respondError(HttpStatusCode.NotFound)
        val resource = resources.getValue(path)
        val etag = onGet[path]?.let { "\"$it\"" } ?: quoted(resource)
        return respond(
            content,
            HttpStatusCode.OK,
            headersOf(
                "OC-ETag" to listOf(etag),
                "OC-FileId" to listOfNotNull(resource.ocId),
            ),
        )
    }

    private fun quoted(resource: Remote): String {
        val value = "\"${resource.etag}\""
        return if (resource.weak) "W/$value" else value
    }

    private fun multistatus(shown: List<Remote>): String = buildString {
        append("""<?xml version="1.0"?>""")
        append(
            """<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">""",
        )
        for (resource in shown) {
            append("<d:response><d:href>")
            append("/remote.php/dav/files/${testCredential.uid}/${NextcloudUrls.encodePath(resource.path)}")
            if (resource.isCollection) append("/")
            append("</d:href><d:propstat><d:prop>")
            resource.etag?.let { append("<d:getetag>${escaped(quoted(resource))}</d:getetag>") }
            append(
                if (resource.isCollection) "<d:resourcetype><d:collection/></d:resourcetype>" else "<d:resourcetype/>",
            )
            resource.ocId?.let { append("<oc:id>$it</oc:id>") }
            resource.fileId?.let { append("<oc:fileid>$it</oc:fileid>") }
            resource.md5?.let { append("<oc:checksums><oc:checksum>MD5:$it</oc:checksum></oc:checksums>") }
            resource.fingerprint?.let { append("<nc:data-fingerprint>$it</nc:data-fingerprint>") }
            append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
        }
        append("</d:multistatus>")
    }

    private fun escaped(raw: String) = raw.replace("\"", "&quot;")
}

/**
 * Everything a [Reconciler] needs, wired to fakes, with the real `WebDavClient` in the middle.
 *
 * `verdict` and `report` are the two scripted decisions — what came out of the PDF, and what the
 * merge concluded. Both belong to other modules (`:android:pdf` and `:core:storage`), and both are
 * exercised for real elsewhere; what is under test here is what the reconciler *does* with them.
 */
@Suppress("LongParameterList")
// One scripted decision per collaborator the reconciler cannot own: what came out of the PDF, what
// the merge concluded, what revision it reached, whether a peer's note installs, and whether the
// note could be trashed at all. Grouping them would mean inventing a holder called "the things a
// reconcile talks to", which is what the harness is.
internal class ReconcileHarness(
    val server: FakeServer,
    account: SyncAccount = testAccount,
    verdict: (String) -> ExtractedRemote = { normal(null) },
    report: (DocumentId) -> MergeReport = { FakeMergeGate.defaultMerged() },
    mergedRev: Long = 2L,
    installs: (RemoteInstall) -> DocumentId? = { it.noteId },
    trashes: (DocumentId) -> Boolean = { true },
    /** Parks a pass mid-merge. See [FakeMergeGate.onMerge]. */
    onMerge: suspend (DocumentId) -> Unit = {},
) {
    val recorded = RecordedRequests()
    val clock = FakeClock()
    val fs = FakeFileSystem()
    val notes = FakeSyncNotes()
    val outbox = FakeOutboxStore()
    val accounts = FakeAccountStore(account)
    val bindings = FakeBindingStore()
    val credentials = FakeCredentialStore(account.id to testCredential)
    val directories = FakeDirectoryCache()
    val cursors = FakeSyncCursorStore()
    val downloads = FakePendingDownloadStore()
    val attention = FakeAttentionLog()
    val folders = FakeFolderBindingStore()
    val peers = FakePeerClockStore()
    val foreign = FakeForeignRemoteCache()
    val gate = FakeMergeGate(
        report = report,
        localRev = mergedRev,
        installs = installs,
        trashes = trashes,
        onMerge = onMerge,
    )
    val extractor = FakeExtractor(fs = fs, verdictFor = verdict)
    val scheduler = CountingScheduler()

    private val stores = SyncStores(accounts, bindings, outbox, notes)
    private val pull = ReconcileStores(directories, cursors, downloads, attention, folders, peers, foreign)
    val engine: SyncEngine = SyncEngine(stores, clock, scheduler)

    val reconciler: Reconciler = Reconciler(
        stores = stores,
        pull = pull,
        credentials = credentials,
        fs = fs,
        clock = clock,
        transport = mockTransport(recorded) { request -> server.handle(request)() },
        extractor = extractor,
        gate = gate,
        engine = engine,
    )

    suspend fun bind(
        noteId: DocumentId,
        path: String,
        ocId: String?,
        etag: ETag?,
        localRev: Long = 1L,
        syncedRev: Long = 1L,
        checksum: String? = null,
    ) {
        notes.setRev(noteId, localRev)
        bindings.upsert(
            RemoteBinding(
                noteId = noteId,
                accountId = testAccount.id,
                remotePath = path,
                remoteOcId = ocId,
                lastSyncedEtag = etag,
                lastSyncedRev = syncedRev,
                // Non-zero so `everUploaded` is true without depending on the ETag surviving a
                // proxy — the reason the column exists at all (SPEC §7.5).
                lastUploadAtEpochMillis = 1L,
                lastChecksum = checksum,
            ),
        )
    }

    /** Pretend a previous pass already walked [path] and cached its ETag, so this one can prune. */
    suspend fun cache(path: String, etag: String, ocId: String? = null, weak: Boolean = false) {
        directories.upsert(RemoteDirectory(testAccount.id, path, ETag(etag, weak), ocId, seenAtEpochMillis = 1L))
    }

    fun requests(method: String): Int = recorded.count(method)

    fun paths(method: String): List<String> = recorded.of(method).map { it.url.encodedPath }
}

internal class CountingScheduler : WorkScheduler {
    var drains: Int = 0
        private set

    var reconciles: Int = 0
        private set

    override fun enqueueOutboxDrain() {
        drains++
    }

    override fun enqueueMaterialization(noteId: DocumentId) = Unit

    override fun enqueueReconcile() {
        reconciles++
    }

    override fun enqueueReconcile(noteId: DocumentId) {
        reconciles++
    }
}

internal fun normal(documentId: DocumentId?): ExtractedRemote =
    ExtractedRemote(RemoteBundleVerdict.NORMAL, documentId, schemaVersion = 1, bundleWritten = documentId != null)

internal fun foreign(): ExtractedRemote =
    ExtractedRemote(RemoteBundleVerdict.FOREIGN, null, schemaVersion = 0, bundleWritten = false)

internal fun tooNew(documentId: DocumentId?, schemaVersion: Int = 99): ExtractedRemote =
    ExtractedRemote(RemoteBundleVerdict.TOO_NEW, documentId, schemaVersion, bundleWritten = false)
