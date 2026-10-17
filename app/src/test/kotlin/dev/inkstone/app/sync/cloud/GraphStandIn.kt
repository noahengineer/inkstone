package dev.inkstone.app.sync.cloud

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A hand-rolled stand-in for Microsoft Graph's OneDrive surface **and** its token endpoint, over
 * `ServerSocket` — for the reason `LocalMatrixServer` gives: the production transport (real OkHttp),
 * the real request building and the real parsing all stay in the loop, and only the storage behind
 * the socket is fake.
 *
 * ## What it is, and what it is not
 *
 * It implements what `docs/CLOUD-PROVIDERS.md` §3–§5 *read* in Microsoft's documentation, so every
 * behaviour here is a claim about **the docs, not about Graph**:
 *
 * - `createUploadSession` honours `If-Match` (412 `resourceModified`) and `conflictBehavior: fail`
 *   (409 `nameAlreadyExists`); a precondition on a missing item is also a 412, which is what a
 *   Microsoft engineer reports in `onedrive-api-docs#1716`;
 * - the upload URL is pre-authorised and **401s if a bearer token is sent to it**;
 * - an unfinished session changes nothing visible;
 * - `DELETE` goes to a recycle bin, never away;
 * - a move onto a name that is taken is a 409;
 * - refresh tokens **rotate**, and an old one is refused (`invalid_grant`), so a client that uses the
 *   wrong one fails here rather than working by luck.
 *
 * `tools/cloud/graph_spike.py` is how those stop being claims. **This file agreeing with the store is
 * evidence the store matches this file, and nothing more** — CLAUDE.md's oldest lesson.
 */
internal class GraphStandIn {

    class Node(
        val id: String,
        var name: String,
        var parentId: String?,
        val isFolder: Boolean,
        var bytes: ByteArray = ByteArray(0),
        var version: Int = 1,
    ) {
        val etag: String get() = "\"{$id-v},$version\""
    }

    private class Session(val parentId: String?, val name: String, val itemId: String?, val total: Long = -1L) {
        val received = ByteArrayOutputStream()
        var expectedTotal: Long = total
    }

    private class Request(
        val method: String,
        val path: String,
        val query: String,
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

    private val lock = Any()
    private val nodes = LinkedHashMap<String, Node>()
    private val recycleBin = ArrayList<Node>()
    private val sessions = HashMap<String, Session>()
    private var idCounter = 1000
    private var sessionCounter = 0
    private var tokenCounter = 1

    private val rootId = "root-id"

    /** Every request, in order, as `METHOD kind` plus which headers mattered — for ordering assertions. */
    val log: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    /** What a test may assert on without parsing [log]. */
    val upload = Counter()
    val api = Counter()
    val tokenEndpoint = Counter()
    val ranges: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
    val sessionIfMatch: MutableList<String?> = java.util.Collections.synchronizedList(ArrayList())
    val sessionAuthorization: MutableList<String?> = java.util.Collections.synchronizedList(ArrayList())
    val uploadAuthorization: MutableList<String?> = java.util.Collections.synchronizedList(ArrayList())

    class Counter {
        private val n = java.util.concurrent.atomic.AtomicInteger()
        val count: Int get() = n.get()

        fun hit() {
            n.incrementAndGet()
        }
    }

    // -- switches ---------------------------------------------------------------------------------
    @Volatile var rejectAccessTokens: Boolean = false

    @Volatile var grantDead: Boolean = false

    @Volatile var tokenEndpointUnreachable: Boolean = false

    @Volatile var throttleApiWith: Long? = null

    @Volatile var accessLifetimeSeconds: Long = 3600

    private var currentRefresh = "rt-1"
    private val validAccess = HashSet<String>()

    private val socket = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "graph-standin").apply { isDaemon = true } }

    @Volatile private var running = true

    private val origin = "http://127.0.0.1:${socket.localPort}"
    val baseUrl: String = "$origin/v1.0"
    val tokenUrl: String = "$origin/oauth/token"
    val driveId: String = "drive-1"
    val initialRefreshToken: String = "rt-1"

    init {
        nodes[rootId] = Node(rootId, "root", null, isFolder = true)
        thread(isDaemon = true, name = "graph-standin-accept") { acceptLoop() }
    }

    fun close() {
        running = false
        runCatching { socket.close() }
        workers.shutdownNow()
    }

    // -- what a test reads or does "as somebody else" ---------------------------------------------

    fun bytesAt(path: String): ByteArray? = synchronized(lock) { find(path)?.takeIf { !it.isFolder }?.bytes?.copyOf() }

    fun nodeAt(path: String): Node? = synchronized(lock) { find(path) }

    fun idAt(path: String): String? = synchronized(lock) { find(path)?.id }

    fun etagAt(path: String): String? = synchronized(lock) { find(path)?.etag }

    fun recycled(): List<String> = synchronized(lock) { recycleBin.map { it.name } }

    /** Another device writing the file: new bytes, new version, no precondition. */
    fun editBehindTheirBack(path: String, bytes: ByteArray) = synchronized(lock) {
        val node = checkNotNull(find(path)) { "no $path" }
        node.bytes = bytes
        node.version++
    }

    /** A metadata-only change (a rename elsewhere, an indexer): the eTag moves, the bytes do not. */
    fun touchMetadata(path: String) = synchronized(lock) {
        checkNotNull(find(path)).version++
    }

    fun seedFile(path: String, bytes: ByteArray): String = synchronized(lock) {
        val segments = path.split('/').filter { it.isNotEmpty() }
        var parent = rootId
        for (segment in segments.dropLast(1)) {
            parent = child(parent, segment)?.id ?: newNode(parent, segment, true).id
        }
        newNode(parent, segments.last(), false, bytes).id
    }

    // -- plumbing ------------------------------------------------------------------------------

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
            if (request.path == "/oauth/token" && tokenEndpointUnreachable) {
                // Hang up with no status: the client reports a transport failure.
                return
            }
            val response = try {
                handle(request)
            } catch (failure: Throwable) {
                Response(500, failure.toString().encodeToByteArray())
            }
            val out = connection.getOutputStream()
            val head = buildString {
                append("HTTP/1.1 ${response.status} ${if (response.status < 300) "OK" else "Status"}\r\n")
                for ((name, value) in response.headers) append("$name: $value\r\n")
                append("Content-Type: application/json\r\n")
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
        return Request(parts[0].uppercase(), target.substringBefore('?'), target.substringAfter('?', ""), headers, body)
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

    // -- routing -------------------------------------------------------------------------------

    private fun handle(request: Request): Response {
        val path = request.path
        return when {
            path == "/oauth/token" && request.method == "POST" -> token(request)
            path.startsWith("/upload/") -> uploadFragment(request)
            path.startsWith("/download/") -> download(request)
            path.startsWith("/v1.0/") -> graph(request, path.removePrefix("/v1.0"))
            else -> json(404, error("notFound"))
        }
    }

    private fun graph(request: Request, path: String): Response = synchronized(lock) {
        api.hit()
        log +=
            "api ${request.method} ${path.take(
                LOG_PATH,
            )} auth=${request.header("authorization").orEmpty().removePrefix("Bearer ")}"
        throttleApiWith?.let { return json(429, error("activityLimitReached"), mapOf("Retry-After" to it.toString())) }
        val bearer = request.header("authorization")?.removePrefix("Bearer ")
        if (rejectAccessTokens || bearer == null || bearer !in validAccess) {
            return json(401, error("InvalidAuthenticationToken"))
        }
        val decoded = decode(path)
        return when {
            decoded == "/me/drive" -> json(200, """{"id":"$driveId","driveType":"personal"}""")
            decoded.startsWith("/drives/$driveId") -> drive(request, decoded.removePrefix("/drives/$driveId"))
            else -> json(404, error("itemNotFound"))
        }
    }

    @Suppress("ReturnCount") // A router: one early return per route shape reads as the table it is.
    private fun drive(request: Request, rest: String): Response {
        // `/root:/a/b:` (+ `/children` | `/createUploadSession`), `/root`, `/root/children`, `/items/{id}...`
        if (rest.startsWith("/root:/")) {
            val inner = rest.removePrefix("/root:/")
            val close = inner.lastIndexOf(':')
            val itemPath = inner.substring(0, close)
            val suffix = inner.substring(close + 1)
            return byPath(request, itemPath, suffix)
        }
        if (rest == "/root") return json(200, itemJson(nodes.getValue(rootId)))
        if (rest == "/root/children") return childrenOrCreate(request, nodes.getValue(rootId))
        if (rest.startsWith("/items/")) {
            val tail = rest.removePrefix("/items/")
            val id = tail.substringBefore('/')
            val suffix = tail.substringAfter('/', "")
            val node = nodes[id]?.takeIf { it.parentId != null || it.id == rootId }
                ?: return json(404, error("itemNotFound"))
            return byId(request, node, suffix)
        }
        return json(404, error("itemNotFound"))
    }

    private fun byPath(request: Request, itemPath: String, suffix: String): Response {
        val node = find(itemPath)
        return when {
            suffix == "/createUploadSession" && request.method == "POST" -> createSession(request, itemPath, node)
            node == null -> json(404, error("itemNotFound"))
            suffix == "/children" && request.method == "GET" -> childrenOrCreate(request, node)
            suffix.isEmpty() && request.method == "GET" -> json(200, itemJson(node))
            else -> json(404, error("itemNotFound"))
        }
    }

    private fun byId(request: Request, node: Node, suffix: String): Response = when {
        suffix == "createUploadSession" && request.method == "POST" -> createSessionForItem(request, node)
        suffix == "children" -> childrenOrCreate(request, node)
        suffix.isEmpty() && request.method == "GET" -> json(200, itemJson(node))
        suffix.isEmpty() && request.method == "PATCH" -> patch(request, node)
        suffix.isEmpty() && request.method == "DELETE" -> delete(node)
        else -> json(404, error("itemNotFound"))
    }

    private fun childrenOrCreate(request: Request, folder: Node): Response {
        if (request.method == "GET") {
            val items = nodes.values.filter { it.parentId == folder.id }.joinToString(",") { itemJson(it) }
            return json(200, """{"value":[$items]}""")
        }
        val body = String(request.body)
        val name =
            Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                ?: return json(400, error("invalidRequest"))
        if (child(folder.id, name) != null) return json(409, error("nameAlreadyExists"))
        return json(201, itemJson(newNode(folder.id, name, true)))
    }

    private fun createSession(request: Request, itemPath: String, existing: Node?): Response {
        sessionIfMatch += request.header("if-match")
        sessionAuthorization += request.header("authorization")
        val behavior =
            Regex("conflictBehavior\"\\s*:\\s*\"(\\w+)\"").find(String(request.body))?.groupValues?.get(1) ?: "fail"
        val segments = itemPath.split('/').filter { it.isNotEmpty() }
        val parent = find(segments.dropLast(1).joinToString("/")) ?: return json(404, error("itemNotFound"))
        val precondition = request.header("if-match")
        if (precondition != null && existing == null) return json(412, error("resourceModified"))
        if (existing != null && behavior == "fail") return json(409, error("nameAlreadyExists"))
        if (existing != null && precondition != null &&
            precondition != existing.etag
        ) {
            return json(412, error("resourceModified"))
        }
        return openSession(Session(parent.id, segments.last(), existing?.id))
    }

    private fun createSessionForItem(request: Request, node: Node): Response {
        sessionIfMatch += request.header("if-match")
        sessionAuthorization += request.header("authorization")
        val precondition = request.header("if-match")
        if (precondition != null && precondition != node.etag) return json(412, error("resourceModified"))
        return openSession(Session(node.parentId, node.name, node.id))
    }

    private fun openSession(session: Session): Response {
        val token = "s${++sessionCounter}"
        sessions[token] = session
        return json(200, """{"uploadUrl":"$origin/upload/$token","expirationDateTime":"2099-01-01T00:00:00Z"}""")
    }

    private fun uploadFragment(request: Request): Response = synchronized(lock) {
        upload.hit()
        uploadAuthorization += request.header("authorization")
        // A bearer token sent to a pre-authorised URL is refused — the documented trap.
        if (request.header("authorization") != null) return json(401, error("InvalidAuthenticationToken"))
        val token = request.path.removePrefix("/upload/")
        val session = sessions[token] ?: return json(404, error("itemNotFound"))
        val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").find(request.header("content-range").orEmpty())
            ?: return json(400, error("invalidRange"))
        val (start, end, total) = range.destructured
        ranges += "$start-$end"
        if (start.toLong() != session.received.size().toLong()) return json(416, error("invalidRange"))
        session.received.write(request.body)
        session.expectedTotal = total.toLong()
        if (session.received.size() < session.expectedTotal) {
            return json(202, """{"nextExpectedRanges":["${session.received.size()}-"]}""")
        }
        sessions.remove(token)
        val bytes = session.received.toByteArray()
        val existing = session.itemId?.let { nodes[it] }
        return if (existing != null) {
            existing.bytes = bytes
            existing.version++
            json(200, itemJson(existing))
        } else {
            if (child(session.parentId, session.name) != null) return json(409, error("nameAlreadyExists"))
            json(201, itemJson(newNode(session.parentId, session.name, false, bytes)))
        }
    }

    private fun download(request: Request): Response = synchronized(lock) {
        api.hit()
        log += "download auth=${request.header("authorization")}"
        if (request.header("authorization") != null) return json(401, error("InvalidAuthenticationToken"))
        val id = request.path.removePrefix("/download/").substringBefore('/')
        val node = nodes[id] ?: return json(404, error("itemNotFound"))
        Response(200, node.bytes)
    }

    private fun patch(request: Request, node: Node): Response {
        val body = String(request.body)
        val newName = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: node.name
        val newParent =
            Regex("\"parentReference\"\\s*:\\s*\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                ?: node.parentId
        val clash = child(newParent, newName)
        if (clash != null && clash.id != node.id) return json(409, error("nameAlreadyExists"))
        node.name = newName
        node.parentId = newParent
        node.version++
        return json(200, itemJson(node))
    }

    private fun delete(node: Node): Response {
        // Soft: out of the namespace, into the bin. Nothing here can destroy bytes.
        nodes.remove(node.id)
        recycleBin += node
        return Response(204)
    }

    private fun token(request: Request): Response = synchronized(lock) {
        tokenEndpoint.hit()
        val form = String(request.body).split('&').filter { it.contains('=') }
            .associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
        log += "token refresh=${form["refresh_token"]}"
        if (grantDead || form["grant_type"] != "refresh_token" || form["refresh_token"] != currentRefresh) {
            return json(400, """{"error":"invalid_grant","error_description":"refused"}""")
        }
        val n = ++tokenCounter
        val access = "at-$n"
        val refresh = "rt-$n"
        currentRefresh = refresh
        validAccess += access
        log += "token issued $access $refresh"
        json(
            200,
            """{"access_token":"$access","refresh_token":"$refresh",""" +
                """"expires_in":$accessLifetimeSeconds,"token_type":"Bearer"}""",
        )
    }

    // -- tree --------------------------------------------------------------------------------------

    private fun child(parentId: String?, name: String): Node? =
        nodes.values.firstOrNull { it.parentId == parentId && it.name == name }

    private fun find(path: String): Node? {
        var node: Node? = nodes[rootId]
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            node = child(node?.id, segment) ?: return null
        }
        return node
    }

    private fun newNode(parentId: String?, name: String, folder: Boolean, bytes: ByteArray = ByteArray(0)): Node {
        val id = "item-${idCounter++}"
        return Node(id, name, parentId, folder, bytes).also { nodes[id] = it }
    }

    private fun itemJson(node: Node): String = buildString {
        append("""{"id":"${node.id}","name":"${node.name}","eTag":"${node.etag.replace("\"", "\\\"")}",""")
        append(""""size":${node.bytes.size},"lastModifiedDateTime":"2026-10-17T00:00:00Z",""")
        val kindFields = if (node.isFolder) {
            """"folder":{}"""
        } else {
            """"file":{},"@microsoft.graph.downloadUrl":"$origin/download/${node.id}/${node.version}""""
        }
        append(kindFields)
        append("}")
    }

    private fun decode(path: String): String = URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")

    private fun error(code: String) = """{"error":{"code":"$code","message":"stand-in"}}"""

    private fun json(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
        Response(status, body.encodeToByteArray(), headers)

    private companion object {
        const val BACKLOG = 50
        const val HEX = 16
        const val LOG_PATH = 60
    }
}
