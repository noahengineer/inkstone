package dev.inkstone.app.sync.cloud

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A hand-rolled stand-in for the slice of Google Drive v3 that `DriveStore` uses, over `ServerSocket`
 * (the reason [GraphStandIn] gives: the production transport and parsing stay in the loop; only the
 * storage behind the socket is fake).
 *
 * ## Every behaviour here is a claim about Google's *documentation*, not about Google
 *
 * - A content update **ignores `If-Match`** (the community report in docs §3.2) — there is no 412 anywhere
 *   in this class, which is the point: the store must protect the file itself.
 * - `keepRevisionForever=true` pins the new head revision; an unpinned superseded revision is *purgeable*
 *   (kept in this fake until [purgeUnpinned]); `revisions/{id}?alt=media` works **only on a pinned
 *   revision**, as the revisions guide says.
 * - A resumable upload is a session: `POST/PATCH …/upload/drive/v3/files…?uploadType=resumable` answers a
 *   `Location`, and a `PUT` to it commits. The session URI takes no bearer token.
 * - **`fields` is honoured**, and with none the answer is Google's thin default (`kind,id,name,mimeType`).
 *   A fixture that returned everything would hide a store that forgot to ask for `headRevisionId` — the
 *   fixture would be more generous than production, which is this repo's oldest lesson.
 * - Names are **not unique** in a folder; `files.list` filters by `'<id>' in parents`, `name='…'`,
 *   `mimeType='…'` and `trashed=false`; a trashed file is hidden from listing but still fetchable by id.
 * - `changes` lists the latest state of each file changed since a token, with `trashed` on the file.
 *
 * [beforeCommit] fires inside the upload, after the store's own check and before the bytes land: what a
 * concurrent writer in the window looks like.
 */
// A fixture that mirrors the Drive docs route by route: a router and a query parser are the shape this rule
// judges worst, and splitting them would hide which documented behaviour each line stands for.
@Suppress("CyclomaticComplexMethod", "ComplexCondition", "TooManyFunctions")
internal class DriveStandIn(port: Int = 0, publicHost: String = "127.0.0.1") {

    class Rev(val id: String, val modified: String, val bytes: ByteArray, var keepForever: Boolean)

    class Node(
        val id: String,
        var name: String,
        var parents: MutableList<String>,
        val folder: Boolean,
        val created: String,
    ) {
        var trashed = false
        val revisions = ArrayList<Rev>()
        val head: Rev? get() = revisions.lastOrNull()
    }

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    private class Response(
        val status: Int,
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap(),
    )

    private class Session(val parent: String?, val id: String?, val name: String?, val keep: Boolean)

    private val lock = Any()
    private val nodes = LinkedHashMap<String, Node>()
    private val sessions = HashMap<String, Session>()
    private var counter = 0
    private var revisionCounter = 0
    private var clockTick = 0
    private var seq = 0L
    private val changedAt = LinkedHashMap<String, Long>()
    private var sessionCounter = 0

    /** Every request as `METHOD kind`, in order. */
    val log: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
    val api = GraphStandIn.Counter()
    val contentCommits = GraphStandIn.Counter()
    val ifMatchSeen: MutableList<String?> = java.util.Collections.synchronizedList(ArrayList())
    val sessionAuthorization: MutableList<String?> = java.util.Collections.synchronizedList(ArrayList())

    @Volatile var rejectAccessTokens: Boolean = false

    @Volatile var expireChangeTokens: Boolean = false

    /** Who `about` says owns the token. Two people on one tablet is how identity checks are tested. */
    @Volatile var userPermissionId: String = "perm-1"

    @Volatile var userEmail: String = "test.user@gmail.com"

    /** Never return more than this many items in a page, whatever `pageSize` asks — to make paging happen. */
    @Volatile var pageCap: Int? = null

    @Volatile var beforeCommit: (() -> Unit)? = null

    private val socket = ServerSocket(port, BACKLOG, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "drive-standin").apply { isDaemon = true } }

    @Volatile private var running = true

    private val origin = "http://$publicHost:${socket.localPort}"
    val baseUrl: String = origin
    val rootId: String = "root-folder-id"

    init {
        nodes[rootId] = Node(rootId, "My Drive", mutableListOf(), folder = true, created = tick())
        thread(isDaemon = true, name = "drive-standin-accept") { acceptLoop() }
    }

    fun close() {
        running = false
        runCatching { socket.close() }
        workers.shutdownNow()
    }

    // -- the test's view ---------------------------------------------------------------------------

    fun idAt(path: String): String? = synchronized(lock) { find(path)?.id }

    fun bytesAt(path: String): ByteArray? = synchronized(lock) { find(path)?.head?.bytes?.copyOf() }

    fun headRevisionAt(path: String): String? = synchronized(lock) { find(path)?.head?.id }

    fun revisionsAt(path: String): List<Rev> = synchronized(lock) { find(path)?.revisions?.toList().orEmpty() }

    fun isTrashed(path: String): Boolean = synchronized(lock) { find(path, includeTrashed = true)?.trashed == true }

    fun namesIn(folderPath: String): List<String> = synchronized(lock) {
        val folder = if (folderPath.isEmpty()) nodes.getValue(rootId) else find(folderPath) ?: return emptyList()
        nodes.values.filter { folder.id in it.parents && !it.trashed }.map { it.name }
    }

    /** Somebody else's write, as another device would make it: a new head revision, unpinned. */
    fun writeBehindTheirBack(path: String, bytes: ByteArray): String = synchronized(lock) {
        val node = checkNotNull(find(path)) { "no $path" }
        addRevision(node, bytes, keep = false).id
    }

    /** Drive eventually purges unpinned superseded revisions. This is that, on demand. */
    fun purgeUnpinned(path: String) = synchronized(lock) {
        val node = find(path) ?: return@synchronized
        node.revisions.removeAll { it !== node.head && !it.keepForever }
    }

    fun seedFile(path: String, bytes: ByteArray): String = synchronized(lock) {
        val segments = path.split('/').filter { it.isNotEmpty() }
        var parent = rootId
        for (segment in segments.dropLast(1)) {
            val existing = children(parent).firstOrNull { it.name == segment && it.folder }
            parent = existing?.id ?: newNode(parent, segment, true).id
        }
        val node = newNode(parent, segments.last(), false)
        addRevision(node, bytes, keep = true)
        node.id
    }

    fun trashBehindTheirBack(path: String) = synchronized(lock) {
        val node = checkNotNull(find(path))
        node.trashed = true
        changedAt[node.id] = ++seq
    }

    // -- model -------------------------------------------------------------------------------------

    private fun tick(): String {
        clockTick++
        return "2026-01-01T00:%02d:%02d.%03dZ".format(clockTick / 60_000, clockTick / 1000 % 60, clockTick % 1000)
    }

    private fun newNode(parent: String, name: String, folder: Boolean): Node {
        val node = Node("f${++counter}", name, mutableListOf(parent), folder, tick())
        nodes[node.id] = node
        changedAt[node.id] = ++seq
        return node
    }

    private fun addRevision(node: Node, bytes: ByteArray, keep: Boolean): Rev {
        val rev = Rev("r${++revisionCounter}", tick(), bytes, keep)
        node.revisions += rev
        changedAt[node.id] = ++seq
        return rev
    }

    private fun resolveAlias(id: String) = if (id == "root") rootId else id

    private fun children(parent: String): List<Node> = nodes.values.filter { parent in it.parents }

    private fun find(path: String, includeTrashed: Boolean = false): Node? {
        var current = nodes.getValue(rootId)
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            current = children(current.id)
                .filter { it.name == segment && (includeTrashed || !it.trashed) }
                .minByOrNull { it.created } ?: return null
        }
        return current
    }

    private fun md5(bytes: ByteArray) =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The resource the way Google would show it before `fields` is applied. */
    private fun view(node: Node): Map<String, Any?> = buildMap {
        put("kind", "drive#file")
        put("id", node.id)
        put("name", node.name)
        put("mimeType", if (node.folder) FOLDER else PDF)
        put("parents", node.parents.toList())
        put("trashed", node.trashed)
        put("createdTime", node.created)
        node.head?.let {
            put("headRevisionId", it.id)
            put("md5Checksum", md5(it.bytes))
            put("size", it.bytes.size.toString()) // Google sends int64 as a string
            put("modifiedTime", it.modified)
        }
    }

    // -- the Drive surface -------------------------------------------------------------------------

    private fun handle(request: Request): Response = synchronized(lock) {
        val isSession = request.path.startsWith("/upload-session/")
        if (!isSession) api.hit()
        log += "${request.method} ${request.path.take(LOG_PATH)}"
        if (isSession) {
            sessionAuthorization += request.header("authorization")
            return commit(request)
        }
        val bearer = request.header("authorization")?.removePrefix("Bearer ")
        if (rejectAccessTokens || bearer.isNullOrEmpty()) return json(401, error("authError", "Invalid Credentials"))
        request.header("if-match").let { ifMatchSeen += it }
        val path = request.path
        return when {
            path == "/drive/v3/about" -> json(200, project(mapOf("user" to who()), request))
            path == "/drive/v3/changes/startPageToken" -> json(200, "{\"startPageToken\":\"S$seq\"}")
            path == "/drive/v3/changes" -> changes(request)
            path == "/drive/v3/files" && request.method == "GET" -> list(request)
            path == "/drive/v3/files" && request.method == "POST" -> createMetadata(request)
            path.startsWith("/upload/drive/v3/files") -> startUpload(request)
            path.startsWith("/drive/v3/files/") -> fileRoute(request, path.removePrefix("/drive/v3/files/"))
            else -> json(404, error("notFound", "no route $path"))
        }
    }

    private fun fileRoute(request: Request, rest: String): Response {
        val parts = rest.split('/')
        val id = resolveAlias(URLDecoder.decode(parts[0], "UTF-8"))
        val node = nodes[id] ?: return json(404, error("notFound", "File not found: $id"))
        return when {
            parts.size == 1 && request.method == "GET" ->
                if (request.query["alt"] == "media") {
                    Response(200, node.head?.bytes ?: ByteArray(0), mapOf("Content-Type" to PDF))
                } else {
                    json(200, project(view(node), request))
                }

            parts.size == 1 && request.method == "PATCH" -> patch(request, node)

            parts.size == 2 && parts[1] == "revisions" -> listRevisions(request, node)

            parts.size == 3 && parts[1] == "revisions" -> revision(request, node, parts[2])

            else -> json(404, error("notFound", "no route"))
        }
    }

    private fun list(request: Request): Response {
        val q = request.query["q"].orEmpty()
        val parentPattern = Regex("""'((?:[^'\\]|\\.)*)' in parents""")
        val parent = parentPattern.find(q)?.groupValues?.get(1)?.let { resolveAlias(unescape(it)) }
        val name = Regex("""name='((?:[^'\\]|\\.)*)'""").find(q)?.groupValues?.get(1)?.let(::unescape)
        val mime = Regex("""mimeType='([^']*)'""").find(q)?.groupValues?.get(1)
        val liveOnly = q.contains("trashed=false")
        val matching = nodes.values.filter {
            it.id != rootId &&
                (parent == null || parent in it.parents) &&
                (name == null || it.name == name) &&
                (mime == null || (if (it.folder) FOLDER else PDF) == mime) &&
                (!liveOnly || !it.trashed)
        }
        val size = minOf(request.query["pageSize"]?.toIntOrNull() ?: DEFAULT_PAGE, pageCap ?: Int.MAX_VALUE)
        val offset = request.query["pageToken"]?.removePrefix("L")?.toIntOrNull() ?: 0
        val page = matching.drop(offset).take(size)
        val body = buildMap<String, Any?> {
            put("kind", "drive#fileList")
            put("files", page.map { view(it) })
            if (offset + page.size < matching.size) put("nextPageToken", "L${offset + page.size}")
        }
        return json(200, project(body, request, default = "kind,incompleteSearch,files(kind,id,name,mimeType)"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun createMetadata(request: Request): Response {
        val body = parseObject(request.body)
        val parent = ((body["parents"] as? List<String>)?.firstOrNull() ?: "root").let(::resolveAlias)
        val node = newNode(parent, body["name"] as? String ?: "Untitled", (body["mimeType"] as? String) == FOLDER)
        return json(200, project(view(node), request))
    }

    private fun patch(request: Request, node: Node): Response {
        val body = parseObject(request.body)
        (body["name"] as? String)?.let { node.name = it }
        (body["trashed"] as? Boolean)?.let { node.trashed = it }
        request.query["addParents"]?.takeIf { it.isNotEmpty() }?.let { node.parents.add(resolveAlias(it)) }
        request.query["removeParents"]?.takeIf { it.isNotEmpty() }?.split(',')
            ?.forEach { node.parents.remove(resolveAlias(it)) }
        changedAt[node.id] = ++seq
        return json(200, project(view(node), request))
    }

    private fun listRevisions(request: Request, node: Node): Response {
        val body = mapOf(
            "kind" to "drive#revisionList",
            "revisions" to node.revisions.map {
                mapOf(
                    "kind" to "drive#revision",
                    "id" to it.id,
                    "modifiedTime" to it.modified,
                    "keepForever" to it.keepForever,
                )
            },
        )
        return json(200, project(body, request, default = "kind,revisions(kind,id,mimeType)"))
    }

    private fun revision(request: Request, node: Node, id: String): Response {
        val rev = node.revisions.firstOrNull { it.id == id }
            ?: return json(404, error("notFound", "Revision not found: $id"))
        if (request.method == "PATCH") {
            (parseObject(request.body)["keepForever"] as? Boolean)?.let { rev.keepForever = it }
            return json(200, project(mapOf("id" to rev.id, "keepForever" to rev.keepForever), request))
        }
        if (request.query["alt"] == "media") {
            // The revisions guide: only a revision marked keepForever can be downloaded.
            if (!rev.keepForever && rev !== node.head) {
                return json(403, error("downloadRestricted", "Revision is not pinned"))
            }
            return Response(200, rev.bytes, mapOf("Content-Type" to PDF))
        }
        val shown = mapOf("id" to rev.id, "modifiedTime" to rev.modified, "keepForever" to rev.keepForever)
        return json(200, project(shown, request))
    }

    private fun startUpload(request: Request): Response {
        val idPart = request.path.removePrefix("/upload/drive/v3/files").trim('/')
        val keep = request.query["keepRevisionForever"] == "true"
        val session = if (request.method == "POST") {
            val meta = parseObject(request.body)

            @Suppress("UNCHECKED_CAST")
            Session(
                parent = ((meta["parents"] as? List<String>)?.firstOrNull() ?: "root").let(::resolveAlias),
                id = null,
                name = meta["name"] as? String,
                keep = keep,
            )
        } else {
            val id = resolveAlias(idPart)
            if (nodes[id] == null) return json(404, error("notFound", "File not found: $id"))
            Session(null, id, null, keep)
        }
        val key = "s${++sessionCounter}"
        sessions[key] = session
        sessionFields[key] = request
        return Response(200, headers = mapOf("Location" to "$origin/upload-session/$key"))
    }

    private val sessionFields = HashMap<String, Request>()

    private fun commit(request: Request): Response {
        val key = request.path.removePrefix("/upload-session/")
        val session = sessions.remove(key) ?: return json(404, error("notFound", "no such session"))
        val started = sessionFields.remove(key)
        beforeCommit?.also { beforeCommit = null }?.invoke()
        contentCommits.hit()
        val node = if (session.id == null) {
            newNode(session.parent ?: rootId, session.name ?: "Untitled", false)
        } else {
            nodes.getValue(session.id)
        }
        addRevision(node, request.body, session.keep)
        return json(200, project(view(node), started ?: request))
    }

    private fun changes(request: Request): Response {
        if (expireChangeTokens) return json(400, error("invalid", "Invalid Value"))
        val token = request.query["pageToken"].orEmpty()
        val (from, offset, snapshot) = when {
            token.startsWith("S") -> Triple(token.drop(1).toLong(), 0, seq)

            token.startsWith("P") ->
                token.drop(1).split(':').let { Triple(it[0].toLong(), it[1].toInt(), it[2].toLong()) }

            else -> return json(400, error("invalid", "Invalid Value"))
        }
        val changed = changedAt.filter { it.value in (from + 1)..snapshot }.keys.toList()
        val size = minOf(request.query["pageSize"]?.toIntOrNull() ?: DEFAULT_PAGE, pageCap ?: Int.MAX_VALUE)
        val page = changed.drop(offset).take(size)
        val body = buildMap<String, Any?> {
            put("kind", "drive#changeList")
            put(
                "changes",
                page.map { id ->
                    mapOf(
                        "kind" to "drive#change",
                        "fileId" to id,
                        "removed" to false,
                        "file" to view(nodes.getValue(id)),
                    )
                },
            )
            if (offset + page.size < changed.size) {
                put("nextPageToken", "P$from:${offset + page.size}:$snapshot")
            } else {
                put("newStartPageToken", "S$snapshot")
            }
        }
        return json(200, project(body, request, default = "kind,nextPageToken,newStartPageToken"))
    }

    // -- fields projection and a tiny JSON codec ---------------------------------------------------

    private class FieldTree(val children: Map<String, FieldTree?>)

    private fun parseFields(spec: String): FieldTree {
        var i = 0

        fun parse(): FieldTree {
            val out = LinkedHashMap<String, FieldTree?>()
            while (i < spec.length) {
                val start = i
                while (i < spec.length && spec[i] != ',' && spec[i] != '(' && spec[i] != ')') i++
                val name = spec.substring(start, i)
                if (i < spec.length && spec[i] == '(') {
                    i++
                    out[name] = parse()
                } else if (name.isNotEmpty()) {
                    out[name] = null
                }
                if (i < spec.length && spec[i] == ')') {
                    i++
                    return FieldTree(out)
                }
                if (i < spec.length && spec[i] == ',') i++
            }
            return FieldTree(out)
        }
        return parse()
    }

    private fun project(value: Map<String, Any?>, request: Request, default: String = "kind,id,name,mimeType"): String =
        write(projectValue(value, parseFields(request.query["fields"] ?: default)))

    private fun projectValue(value: Any?, tree: FieldTree?): Any? = when {
        tree == null -> value

        value is Map<*, *> -> value.entries.filter { it.key in tree.children }
            .associate { it.key as String to projectValue(it.value, tree.children[it.key]) }

        value is List<*> -> value.map { projectValue(it, tree) }

        else -> value
    }

    private fun write(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { write(it.key.toString()) + ":" + write(it.value) }
        is List<*> -> value.joinToString(",", "[", "]") { write(it) }
        else -> write(value.toString())
    }

    private fun parseObject(body: ByteArray): Map<String, Any?> {
        if (body.isEmpty()) return emptyMap()
        val element = kotlinx.serialization.json.Json.parseToJsonElement(body.decodeToString())
        return (convert(element) as? Map<*, *>)?.entries?.associate { it.key as String to it.value }.orEmpty()
    }

    private fun convert(element: kotlinx.serialization.json.JsonElement): Any? = when (element) {
        is kotlinx.serialization.json.JsonObject -> element.entries.associate { it.key to convert(it.value) }

        is kotlinx.serialization.json.JsonArray -> element.map { convert(it) }

        is kotlinx.serialization.json.JsonPrimitive ->
            if (element.isString) {
                element.content
            } else {
                element.content.toBooleanStrictOrNull() ?: element.content.toLongOrNull()
            }

        else -> null
    }

    private fun unescape(text: String) = text.replace("\\'", "'").replace("\\\\", "\\")

    private fun error(reason: String, message: String) = "{\"error\":{\"code\":0,\"message\":\"$message\"," +
        "\"errors\":[{\"reason\":\"$reason\",\"message\":\"$message\"}]}}"

    private fun who() = mapOf(
        "permissionId" to userPermissionId,
        "emailAddress" to userEmail,
        "displayName" to "Test User",
    )

    private fun json(status: Int, body: String) =
        Response(status, body.encodeToByteArray(), mapOf("Content-Type" to "application/json"))

    // -- plumbing ----------------------------------------------------------------------------------

    private fun acceptLoop() {
        while (running) {
            val client = try {
                socket.accept()
            } catch (ignored: Throwable) {
                return
            }
            workers.execute { serve(client) }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun serve(client: Socket) {
        client.use { connection ->
            val input = BufferedInputStream(connection.getInputStream())
            val request = readRequest(input) ?: return
            val response = try {
                handle(request)
            } catch (failure: Throwable) {
                Response(500, failure.toString().encodeToByteArray())
            }
            val out = connection.getOutputStream()
            val head = buildString {
                append("HTTP/1.1 ${response.status} ${if (response.status < 300) "OK" else "Status"}\r\n")
                for ((name, value) in response.headers) append("$name: $value\r\n")
                if (response.headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                    append("Content-Type: application/json\r\n")
                }
                append("Content-Length: ${response.body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.encodeToByteArray())
            if (response.body.isNotEmpty()) out.write(response.body)
            out.flush()
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        var header = readLine(input)
        while (!header.isNullOrEmpty()) {
            headers[header.substringBefore(':').trim().lowercase()] = header.substringAfter(':').trim()
            header = readLine(input)
        }
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
            else -> readFully(input, headers["content-length"]?.toIntOrNull() ?: 0)
        }
        val target = parts[1]
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        return Request(parts[0].uppercase(), target.substringBefore('?'), query, headers, body)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        var size = readLine(input)?.trim()?.substringBefore(';')?.toIntOrNull(HEX) ?: 0
        while (size > 0) {
            out.write(readFully(input, size))
            readLine(input)
            size = readLine(input)?.trim()?.substringBefore(';')?.toIntOrNull(HEX) ?: 0
        }
        readLine(input)
        return out.toByteArray()
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(bytes, read, length - read)
            if (count < 0) break
            read += count
        }
        return bytes.copyOf(read)
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var b = input.read()
        while (b >= 0 && b != '\n'.code) {
            if (b != '\r'.code) out.write(b)
            b = input.read()
        }
        if (b < 0 && out.size() == 0) return null
        return out.toString(Charsets.ISO_8859_1.name())
    }

    private companion object {
        const val PDF = "application/pdf"
        const val FOLDER = "application/vnd.google-apps.folder"
        const val BACKLOG = 50
        const val LOG_PATH = 80
        const val DEFAULT_PAGE = 100
        const val HEX = 16
    }
}
