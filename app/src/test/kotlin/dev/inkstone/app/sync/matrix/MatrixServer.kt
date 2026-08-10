package dev.inkstone.app.sync.matrix

import dev.inkstone.core.model.NextcloudCredential
import dev.inkstone.core.sync.NextcloudUrls
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/*
 * The server SPEC §13.2's matrix runs against — either a real Nextcloud, or an in-process
 * stand-in that speaks the same wire protocol.
 *
 * ## Why the stand-in is a real socket and not a Ktor MockEngine
 *
 * `NextcloudTransport`'s constructor is `internal` on purpose (CLAUDE.md — "Ktor's own types must
 * not leave `:core:sync`"), so `:app` cannot hand the client an engine even in a test. That turns
 * out to be the better constraint: the stand-in below listens on loopback, so
 * `NextcloudTransport.create()` is the production one, OkHttp is real, the real `WebDavClient`
 * builds SPEC §7.3's real PROPFIND body, and the real multistatus parser reads the answer. Only
 * the storage behind it is fake.
 *
 * ⚠️ It is hand-rolled over `ServerSocket` rather than `com.sun.net.httpserver`, for a reason
 * worth recording: these are **Android** unit tests, so the compile classpath is `android.jar`
 * plus the JDK's `java.*`, and `com.sun.net.httpserver` is in neither. It does not exist here.
 * (`jdk.httpserver` is not part of the Android platform, and adding a MockWebServer coordinate
 * to the catalogue for a test fixture is a version this project would then have to keep verified
 * — CLAUDE.md rule 3.)
 *
 * ## What it deliberately does NOT prove
 *
 * Everything SPEC §13 says a mock cannot: it emits a weak ETag because it was *told* to rather
 * than because of how it stores files, its fileids churn because a PUT to a vacant path mints
 * one rather than because of how trash-restore works, and it has no brute-force protection at
 * all. Those are exactly the claims the live half of each row exists to make, and the loud skip
 * names them.
 *
 * Block comment, not KDoc — a file-level KDoc attaches to no declaration.
 */

/**
 * What the matrix needs of a server, whichever one it got.
 *
 * @property requestCounts never null since [CountingProxy] — requests are counted *behind* the
 *   client either way, which is the only place they may be counted: doing it in front would mean
 *   `:app` holding a Ktor plugin, and `NextcloudTransport` is opaque up here precisely to prevent
 *   that (CLAUDE.md). Nullable still, because a row that asserts a count must fail rather than
 *   quietly pass if a future server cannot answer — see [TwoDevices.assertRequests].
 */
internal interface MatrixServer {

    val credential: NextcloudCredential

    val isLive: Boolean

    val requestCounts: RequestCounts?

    /** SPEC §7.6 step 4 — what an admin restoring a backup changes. Row 15. */
    suspend fun setDataFingerprint(value: String): Boolean

    /** SPEC §7.3 — make every validator a weak one. Row 16. */
    fun useWeakEtags(on: Boolean): Boolean

    /** SPEC §7.2 — the app password stops working. Row 20. */
    fun refuseAuthorization(on: Boolean): Boolean

    /** SPEC §7.5 — a 507. Row 12's sibling claim about parking rather than retrying. */
    fun refuseWithQuotaExceeded(on: Boolean): Boolean

    fun close()
}

/**
 * Per-method request tallies, for the rows whose claim is a request count.
 *
 * Also counts *which headers were on them*, because two of SPEC §7's claims are about a header that
 * must **not** be sent: `If-Match` for a weak validator (§7.3, row 16) and anything at all after a
 * 401 (§7.2, row 20). "`asIfMatch` returned null" is a claim about a getter; "no PUT carried an
 * `If-Match`" is a claim about the wire, and only the second one is the rule.
 */
internal class RequestCounts {
    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val headers = ConcurrentHashMap<String, AtomicLong>()

    operator fun get(method: String): Int = counts[method.uppercase()]?.get()?.toInt() ?: 0

    /** Every request of every method, for row 20's *"exactly two, then nothing"*. */
    val total: Int get() = counts.values.sumOf { it.get() }.toInt()

    fun withHeader(method: String, header: String): Int = headers[headerKey(method, header)]?.get()?.toInt() ?: 0

    fun record(method: String, headerNames: Collection<String> = emptyList()) {
        counts.computeIfAbsent(method.uppercase()) { AtomicLong() }.incrementAndGet()
        for (name in headerNames) {
            headers.computeIfAbsent(headerKey(method, name)) { AtomicLong() }.incrementAndGet()
        }
    }

    fun reset() {
        counts.clear()
        headers.clear()
    }

    override fun toString(): String = counts.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }

    private fun headerKey(method: String, header: String) = "${method.uppercase()} ${header.lowercase()}"
}

/**
 * A live account, reached through a [CountingProxy] so the live half can assert a request count.
 *
 * The proxy is not decoration. Without it the live rows print *"request counts not observable"* and
 * every claim SPEC §7 makes about **economy** — one PROPFIND for an idle poll, one PUT for five
 * hundred saves, nothing at all after a 401 — goes unmade against the only server that could
 * disprove it. It also supplies the two behaviours `occ` cannot: rewriting the validator weak
 * (SPEC §7.3) and sending a wrong app password (SPEC §7.2).
 */
internal class LiveMatrixServer(private val live: LiveNextcloud) : MatrixServer {

    private val proxy = CountingProxy(live.credential.serverUrl)

    override val credential: NextcloudCredential = live.credential.copy(serverUrl = proxy.origin)

    override val isLive: Boolean get() = true

    override val requestCounts: RequestCounts get() = proxy.counts

    override suspend fun setDataFingerprint(value: String): Boolean = runOcc("maintenance:data-fingerprint")

    override fun useWeakEtags(on: Boolean): Boolean {
        proxy.weakenValidators = on
        return true
    }

    /**
     * SPEC §7.2 — a **real** 401 from a real Nextcloud, with its real brute-force accounting.
     *
     * `occ user:delete-app-password` needs a shell on the instance and there is no key to the Pi, so
     * the honest substitute is to authenticate with a password that is deliberately wrong. The server
     * does the refusing; nothing in the app or in this fixture simulates it.
     *
     * ⚠️ Each call that is followed by a request spends part of the instance's brute-force budget
     * (~10 failures per 30 minutes per IP, on by default). Row 20 spends exactly two.
     */
    override fun refuseAuthorization(on: Boolean): Boolean {
        proxy.forcedAuthorization = if (on) WRONG_AUTHORIZATION else null
        return true
    }

    /** Not reachable: filling a real quota to produce a 507 would mean filling the Pi's disk. */
    override fun refuseWithQuotaExceeded(on: Boolean): Boolean = false

    override fun close() = proxy.close()

    private fun runOcc(command: String): Boolean {
        val occ = live.occ ?: return false
        val process = ProcessBuilder("$occ $command".split(' ').filter { it.isNotBlank() })
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val ok = process.waitFor() == 0
        println("    occ $command -> ${process.exitValue()}: ${output.trim().take(OCC_OUTPUT_LIMIT)}")
        return ok
    }

    private companion object {
        const val OCC_OUTPUT_LIMIT = 200

        /**
         * `Basic` over a password no instance can have issued.
         *
         * The login name is left out on purpose: the header only has to be *wrong*, and a value that
         * cannot be mistaken for a real credential is one nobody will later read as a leaked one.
         */
        val WRONG_AUTHORIZATION: String = "Basic " + Base64.getEncoder()
            .encodeToString("inkstone:row-20-deliberately-wrong-app-password".encodeToByteArray())
    }
}

/**
 * A Nextcloud-shaped WebDAV server in this JVM.
 *
 * Implements only what SPEC §7.3–§7.6 actually sends: PROPFIND at Depth 0 and 1, GET, PUT with
 * `If-Match` / `If-None-Match: *` / `OC-Checksum`, MKCOL, MOVE with `Overwrite: F`, DELETE, and
 * `GET /ocs/v2.php/cloud/user`.
 *
 * Three behaviours are load-bearing and are the reason this is not a flat map:
 *
 * 1. **A directory's ETag is derived from its subtree**, so a change to a file changes every
 *    ancestor's validator. Without that, SPEC §7.4 rule 1's whole prune-by-directory-ETag design
 *    would be untested — the walk would prune correctly by accident.
 * 2. **DELETE is soft**, into a separate map. The file leaves the namespace but its bytes are
 *    still there, which is Nextcloud's behaviour (SPEC §7.7) and the reason row 14 is allowed to
 *    exist at all.
 * 3. **A PUT to a path with no file at it mints a new `oc:id`.** That is row 21's fileid churn,
 *    and it is what makes *"identity is never a path"* load-bearing rather than decorative.
 */
@Suppress("TooManyFunctions", "LargeClass")
// TooManyFunctions: one function per WebDAV method the app sends, plus the multistatus writer,
// plus the four switches the un-producible rows need. Collapsing any of them would put two
// methods' precondition handling in one branch, and the preconditions are the part of this file
// that has to be right.
// LargeClass: an HTTP/1.1 read loop and a WebDAV namespace, and they are one thing — the loop
// exists to feed the namespace and nothing else will ever call either. Splitting them would mean
// a second file whose only member is `readRequest`.
internal class LocalMatrixServer(uid: String = "matrixuser") : MatrixServer {

    private class Stored(var bytes: ByteArray, var etag: String, var ocId: String, var md5: String?)

    private class Request(
        val method: String,
        val rawPath: String,
        private val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        /** HTTP header names are case-insensitive; OkHttp's casing is not ours to predict. */
        fun header(name: String): String? = headers[name.lowercase()]

        /** For [RequestCounts.withHeader] — which headers a request carried, not what was in them. */
        val headerNames: Set<String> get() = headers.keys
    }

    private class Response(
        val status: Int,
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap(),
    )

    private val files = LinkedHashMap<String, Stored>()
    private val directories = LinkedHashSet<String>()

    /** Nextcloud's DELETE is soft (SPEC §7.7). The bytes leave the namespace, not existence. */
    private val trashed = LinkedHashMap<String, Stored>()

    private var etagCounter = 0L
    private var fileIdCounter = 100L
    private var fingerprint: String? = "fingerprint-0"
    private var weakEtags = false
    private var unauthorized = false
    private var quotaExceeded = false

    private val lock = Any()

    override val requestCounts: RequestCounts = RequestCounts()

    private val socket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))

    // A pool, not one connection at a time: the convergence row polls two devices and a
    // single-threaded server would serialise them into a shape no real server has.
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "matrix-dav").apply { isDaemon = true }
    }

    @Volatile
    private var running = true

    override val credential: NextcloudCredential = NextcloudCredential(
        serverUrl = "http://$LOOPBACK:${socket.localPort}",
        loginName = uid,
        // SPEC §0.1 #3 — deliberately different from the login name, so every path this suite
        // builds goes through the uid and a regression to `loginName` fails here rather than on
        // somebody's email-login account.
        uid = "$uid-uid",
        appPassword = APP_PASSWORD,
    )

    override val isLive: Boolean get() = false

    init {
        directories += ""
        thread(isDaemon = true, name = "matrix-dav-accept") { acceptLoop() }
    }

    override suspend fun setDataFingerprint(value: String): Boolean = synchronized(lock) {
        fingerprint = value
        true
    }

    override fun useWeakEtags(on: Boolean): Boolean = synchronized(lock) {
        weakEtags = on
        true
    }

    override fun refuseAuthorization(on: Boolean): Boolean = synchronized(lock) {
        unauthorized = on
        true
    }

    override fun refuseWithQuotaExceeded(on: Boolean): Boolean = synchronized(lock) {
        quotaExceeded = on
        true
    }

    override fun close() {
        running = false
        socket.close()
        workers.shutdownNow()
    }

    // -----------------------------------------------------------------------
    // Direct inspection, for the rows that stand in for the web UI
    // -----------------------------------------------------------------------

    fun paths(): Set<String> = synchronized(lock) { files.keys.toSet() }

    fun bytesAt(path: String): ByteArray? = synchronized(lock) { files[path]?.bytes?.copyOf() }

    fun ocIdAt(path: String): String? = synchronized(lock) { files[path]?.ocId }

    /**
     * Replace a file's bytes **without** going through the app.
     *
     * This is the "somebody edited it in the web UI" seam rows 9, 13 and 16 need, and the one
     * thing a healthy server will never do to order: hand back a file whose embedded bundle has
     * been stripped, or bump an ETag under a device that is holding the old one.
     */
    fun writeDirectly(path: String, bytes: ByteArray): Unit = synchronized(lock) {
        mkdirs(path.substringBeforeLast('/', ""))
        val existing = files[path]
        if (existing == null) {
            files[path] = Stored(bytes.copyOf(), nextEtag(), nextOcId(), md5Hex(bytes))
        } else {
            existing.bytes = bytes.copyOf()
            existing.etag = nextEtag()
            existing.md5 = md5Hex(bytes)
        }
    }

    /** A raw MOVE, standing in for a rename in the web UI. Keeps the `oc:id` **and** the ETag. */
    fun moveDirectly(from: String, to: String): Boolean = synchronized(lock) {
        val stored = files.remove(from) ?: return false
        mkdirs(to.substringBeforeLast('/', ""))
        files[to] = stored
        true
    }

    /** A raw soft DELETE, standing in for deleting the file in the web UI. */
    fun deleteDirectly(path: String): Boolean = synchronized(lock) {
        val stored = files.remove(path) ?: return false
        trashed[path] = stored
        true
    }

    // -----------------------------------------------------------------------
    // HTTP/1.1, by hand
    // -----------------------------------------------------------------------

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    // `accept` throws a SocketException when `close` pulls the socket out from under it, which is
    // the normal way this loop ends. Anything else is a fixture bug and is reported by the
    // request that then fails to connect.
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
    // An exception escaping here closes the connection with no status, which the client reports
    // as a *transport* failure — so a bug in this fixture would look exactly like the flaky
    // network the suite exists to rule out. A 500 carrying the message is readable.
    private fun serve(client: Socket) {
        client.use { connection ->
            val input = BufferedInputStream(connection.getInputStream())
            val request = readRequest(input) ?: return
            requestCounts.record(request.method, request.headerNames)
            val response = try {
                handle(request)
            } catch (failure: Throwable) {
                Response(SERVER_ERROR, failure.toString().encodeToByteArray())
            }
            write(connection, response)
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readAsciiLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = readHeaders(input)
        val body = readBody(input, headers)
        return Request(parts[0].uppercase(), parts[1].substringBefore('?'), headers, body)
    }

    /** Up to the blank line. Names are lower-cased because OkHttp's casing is not ours to predict. */
    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        var line = readAsciiLine(input)
        while (!line.isNullOrEmpty()) {
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
            line = readAsciiLine(input)
        }
        return headers
    }

    /**
     * Content-Length or chunked, because Ktor picks per body type.
     *
     * A `setBody(ByteArray)` gets a length and a `setBody(String)` usually does too, but the
     * choice is the engine's and a fixture that only handled one would fail on a Ktor upgrade
     * with a hang rather than an error.
     */
    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) return readChunked(input)
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        return if (length <= 0) ByteArray(0) else readFully(input, length)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        var size = nextChunkSize(input)
        while (size > 0) {
            out.write(readFully(input, size))
            readAsciiLine(input)
            size = nextChunkSize(input)
        }
        // The terminating zero-length chunk's trailer line.
        readAsciiLine(input)
        return out.toByteArray()
    }

    /** Zero for the terminating chunk and for anything unparseable, which ends the body either way. */
    private fun nextChunkSize(input: InputStream): Int =
        readAsciiLine(input)?.trim()?.substringBefore(';')?.toIntOrNull(HEX_RADIX) ?: 0

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(bytes, read, length - read)
            if (count < 0) break
            read += count
        }
        return if (read == length) bytes else bytes.copyOf(read)
    }

    /** Byte-oriented: the body may be a PDF, so nothing here may decode ahead of the headers. */
    private fun readAsciiLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var byte = input.read()
        while (byte >= 0 && byte != '\n'.code) {
            if (byte != '\r'.code) out.write(byte)
            byte = input.read()
        }
        // End of stream with nothing read is a closed connection, not an empty line.
        if (byte < 0 && out.size() == 0) return null
        return out.toString(Charsets.ISO_8859_1.name())
    }

    private fun write(connection: Socket, response: Response) {
        val out = connection.getOutputStream()
        val head = buildString {
            append("HTTP/1.1 ${response.status} ${reasonFor(response.status)}\r\n")
            for ((name, value) in response.headers) append("$name: $value\r\n")
            append("Content-Length: ${response.body.size}\r\n")
            // One request per connection. Keep-alive would be more realistic and would also make
            // this fixture responsible for pipelining; the client reconnects, which costs a
            // handshake on loopback and nothing else.
            append("Connection: close\r\n\r\n")
        }
        out.write(head.encodeToByteArray())
        if (response.body.isNotEmpty()) out.write(response.body)
        out.flush()
    }

    private fun reasonFor(status: Int): String = when (status) {
        OK -> "OK"
        CREATED -> "Created"
        NO_CONTENT -> "No Content"
        MULTI_STATUS -> "Multi-Status"
        else -> "Status"
    }

    // -----------------------------------------------------------------------
    // WebDAV
    // -----------------------------------------------------------------------

    private fun handle(request: Request): Response {
        if (request.rawPath.endsWith(OCS_USER_SUFFIX)) return ocsUser()
        if (!authorized(request)) return Response(UNAUTHORIZED)
        // ⚠️ Before `pathFromHref`, because a SEARCH is addressed at `/remote.php/dav/` — there is no
        // uid in that URL, so the path parse answers null and this would be a 404 that looks exactly
        // like the deletion the search exists to rule out (SPEC §7.4 rule 2, `NextcloudUrls.davRoot`).
        if (request.method == "SEARCH") return search(request)

        val path = NextcloudUrls.pathFromHref(request.rawPath, credential.uid) ?: return Response(NOT_FOUND)
        return when (request.method) {
            "PROPFIND" -> propfind(request, path)
            "GET" -> get(path)
            "PUT" -> put(request, path)
            "MKCOL" -> mkcol(path)
            "MOVE" -> move(request, path)
            "DELETE" -> delete(path)
            else -> Response(METHOD_NOT_ALLOWED)
        }
    }

    private fun authorized(request: Request): Boolean {
        if (synchronized(lock) { unauthorized }) return false
        val expected = "Basic " + Base64.getEncoder()
            .encodeToString("${credential.loginName}:${credential.appPassword}".encodeToByteArray())
        return request.header("Authorization") == expected
    }

    /**
     * SPEC §7.2's one permitted follow-up to a 401.
     *
     * 401s when [refuseAuthorization] is on, which is what makes row 20's *"one PUT, one
     * confirmation call, nothing after"* an assertion rather than a hope.
     */
    private fun ocsUser(): Response {
        if (synchronized(lock) { unauthorized }) return Response(UNAUTHORIZED)
        val json = """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":{"id":"${credential.uid}"}}}"""
        return Response(OK, json.encodeToByteArray(), mapOf("Content-Type" to "application/json"))
    }

    private fun propfind(request: Request, path: String): Response = synchronized(lock) {
        val depth = request.header("Depth") ?: "0"
        val isDirectory = path in directories
        if (!isDirectory && path !in files) return Response(NOT_FOUND)
        val shown = buildList {
            add(path to isDirectory)
            if (depth == "1" && isDirectory) addAll(childrenOf(path))
        }
        Response(
            MULTI_STATUS,
            multistatus(shown).encodeToByteArray(),
            mapOf("Content-Type" to "application/xml; charset=utf-8"),
        )
    }

    /**
     * ⭐ RFC 5323 `SEARCH` over `oc:fileid` — SPEC §7.4 rule 2 asked without a path (row 11b).
     *
     * The fileid is the leading digits of an `oc:id` ([nextOcId]), so this resolves it back the way
     * the real server's database does: over every file in the namespace, wherever it is, including
     * folders no walk root covers. That last part is the only reason the method is here.
     *
     * A fileid with nothing at it answers **207 with an empty multistatus**, and a *trashed* file
     * answers the same, because [trashed] is out of the namespace. Both are measured behaviours of
     * the real Nextcloud 32.0.13 and both are load-bearing: an empty answer is what row 14's
     * deletion is still allowed to conclude from.
     */
    private fun search(request: Request): Response = synchronized(lock) {
        val wanted = request.body.decodeToString()
            .substringAfter("<d:literal>", "")
            .substringBefore("</d:literal>", "")
            .trim()
        // An unparseable body matches nothing. Without the guard it would match every `oc:id` whose
        // digits trim to empty, which is a fixture that answers a question nobody asked.
        val hits = if (wanted.isEmpty()) emptyList() else files.keys.filter { fileIdOf(it.storedOcId()) == wanted }
        Response(
            MULTI_STATUS,
            multistatus(hits.map { it to false }).encodeToByteArray(),
            mapOf("Content-Type" to "application/xml; charset=utf-8"),
        )
    }

    /** The numeric part of an `oc:id`, unpadded — what `oc:fileid` reports and a SEARCH matches. */
    private fun fileIdOf(ocId: String): String = ocId.takeWhile(Char::isDigit).trimStart('0')

    private fun String.storedOcId(): String = files.getValue(this).ocId

    private fun get(path: String): Response = synchronized(lock) {
        val stored = files[path] ?: return Response(NOT_FOUND)
        Response(
            OK,
            stored.bytes.copyOf(),
            mapOf("OC-ETag" to validator(stored.etag), "OC-FileId" to stored.ocId),
        )
    }

    /**
     * SPEC §7.5 — and the three preconditions in the order the server checks them.
     *
     * `If-None-Match: *` before `If-Match`, because the two are never both sent and a create
     * against an existing file is the failure the conflicted-copy path depends on. The checksum
     * is verified last and is what turns a truncated upload into a 400 rather than a corrupt
     * note; Nextcloud really does verify it, which is why the app really does send it.
     */
    @Suppress("ReturnCount") // Five preconditions, each ending the request for its own reason.
    private fun put(request: Request, path: String): Response = synchronized(lock) {
        if (quotaExceeded) return Response(INSUFFICIENT_STORAGE)
        if (path.substringBeforeLast('/', "") !in directories) return Response(CONFLICT)

        val existing = files[path]
        if (request.header("If-None-Match") == "*" && existing != null) return Response(PRECONDITION_FAILED)
        val ifMatch = request.header("If-Match")
        if (ifMatch != null && ifMatch.trim('"') != existing?.etag) return Response(PRECONDITION_FAILED)
        val claimed = request.header("OC-Checksum")?.substringAfter(':')
        if (claimed != null && !claimed.equals(md5Hex(request.body), ignoreCase = true)) {
            return Response(BAD_REQUEST, "checksum mismatch".encodeToByteArray())
        }

        val etag = nextEtag()
        // ⭐ A new `oc:id` for a path with nothing at it. Row 21: after a trash-restore the path
        // is free and the fileid is different, which is why the binding is repaired from the PUT
        // response rather than assumed to have survived.
        val stored = existing?.also {
            it.bytes = request.body.copyOf()
            it.etag = etag
            it.md5 = md5Hex(request.body)
        } ?: Stored(request.body.copyOf(), etag, nextOcId(), md5Hex(request.body)).also { files[path] = it }

        Response(
            if (existing == null) CREATED else NO_CONTENT,
            ByteArray(0),
            mapOf("OC-ETag" to validator(stored.etag), "OC-FileId" to stored.ocId),
        )
    }

    private fun mkcol(path: String): Response = synchronized(lock) {
        // 405 is what `mkcolRecursive` reads as "already there" (SPEC §7.6).
        if (path in directories || path in files) return Response(METHOD_NOT_ALLOWED)
        if (path.substringBeforeLast('/', "") !in directories) return Response(CONFLICT)
        directories += path
        Response(CREATED)
    }

    /**
     * SPEC §7.5 — MOVE with `Overwrite: F`, and **the ETag does not change.**
     *
     * That last part is SPEC §7.4 rule 2, and it is the whole of row 11: same `oc:id`, same
     * validator, new href means a move, and a move is not a download.
     */
    @Suppress("ReturnCount") // Four ways a MOVE is refused, each its own status.
    private fun move(request: Request, path: String): Response = synchronized(lock) {
        val destination = request.header("Destination") ?: return Response(BAD_REQUEST)
        val to = NextcloudUrls.pathFromHref(destination, credential.uid) ?: return Response(BAD_REQUEST)
        val stored = files[path] ?: return Response(NOT_FOUND)
        if (request.header("Overwrite") != "T" && to in files) return Response(PRECONDITION_FAILED)
        if (to.substringBeforeLast('/', "") !in directories) return Response(CONFLICT)
        files.remove(path)
        files[to] = stored
        Response(
            CREATED,
            ByteArray(0),
            mapOf("OC-ETag" to validator(stored.etag), "OC-FileId" to stored.ocId),
        )
    }

    /** Soft, like Nextcloud's (SPEC §7.7). The bytes go to a trash map, not to nowhere. */
    private fun delete(path: String): Response = synchronized(lock) {
        val stored = files.remove(path)
        if (stored != null) {
            trashed[path] = stored
            return Response(NO_CONTENT)
        }
        if (path in directories) {
            directories.remove(path)
            files.keys.filter { it.startsWith("$path/") }.forEach { trashed[it] = files.getValue(it) }
            files.keys.removeAll { it.startsWith("$path/") }
            directories.removeAll { it.startsWith("$path/") }
            return Response(NO_CONTENT)
        }
        Response(NOT_FOUND)
    }

    // -----------------------------------------------------------------------
    // Namespace
    // -----------------------------------------------------------------------

    private fun childrenOf(path: String): List<Pair<String, Boolean>> {
        val prefix = if (path.isEmpty()) "" else "$path/"
        val depth = if (path.isEmpty()) 1 else path.count { it == '/' } + 2
        fun isChild(candidate: String) = candidate.isNotEmpty() &&
            candidate.startsWith(prefix) &&
            candidate.split('/').size == depth
        return directories.filter(::isChild).map { it to true } + files.keys.filter(::isChild).map { it to false }
    }

    private fun mkdirs(path: String) {
        if (path.isEmpty()) return
        var built = ""
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            built = if (built.isEmpty()) segment else "$built/$segment"
            directories += built
        }
    }

    private fun nextEtag(): String = "etag-${++etagCounter}"

    /**
     * `<padded fileid><instance id>` — SPEC §7.3's `oc:id`, not `oc:fileid`.
     *
     * The suffix is there so a test that accidentally compared `oc:id` against `oc:fileid` fails
     * rather than passing by coincidence.
     */
    private fun nextOcId(): String = "%08d".format(++fileIdCounter) + "ocinstance"

    /**
     * A directory's validator, derived from everything under it.
     *
     * SPEC §7.4 rule 1: *"a change deep in a tree changes the ETag of every directory above
     * it"*. That propagation is the whole basis of the prune-by-directory-ETag walk, so it is
     * computed rather than stored — a stored one would drift the first time a test forgot to bump
     * an ancestor, and the walk would then prune correctly by luck.
     */
    private fun directoryEtag(path: String): String {
        val prefix = if (path.isEmpty()) "" else "$path/"
        val digest = MessageDigest.getInstance("SHA-256")
        for ((childPath, stored) in files) {
            if (childPath.startsWith(prefix)) digest.update("$childPath:${stored.etag}".encodeToByteArray())
        }
        for (directory in directories) {
            if (directory.startsWith(prefix) && directory != path) digest.update("$directory/".encodeToByteArray())
        }
        return "dir-" + digest.digest().take(ETAG_DIGEST_BYTES).joinToString("") { "%02x".format(it) }
    }

    private fun validator(raw: String): String = if (weakEtags) "W/\"$raw\"" else "\"$raw\""

    private fun multistatus(shown: List<Pair<String, Boolean>>): String = buildString {
        append("""<?xml version="1.0"?>""")
        append("""<d:multistatus xmlns:d="DAV:" """)
        append("""xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">""")
        for ((path, isDirectory) in shown) {
            append("<d:response><d:href>")
            append("/remote.php/dav/files/${credential.uid}/${NextcloudUrls.encodePath(path)}")
            if (isDirectory) append("/")
            append("</d:href><d:propstat><d:prop>")
            if (isDirectory) {
                append("<d:getetag>${escaped(validator(directoryEtag(path)))}</d:getetag>")
                append("<d:resourcetype><d:collection/></d:resourcetype>")
                append("<oc:id>dir-${path.ifEmpty { "root" }.replace('/', '-')}</oc:id>")
            } else {
                val stored = files.getValue(path)
                append("<d:getetag>${escaped(validator(stored.etag))}</d:getetag>")
                append("<d:resourcetype/>")
                append("<oc:id>${stored.ocId}</oc:id>")
                // The unpadded numeric half, as the real server reports it — and the property a
                // `SEARCH` filters on (SPEC §7.3: store `oc:id`, filter on `oc:fileid`).
                append("<oc:fileid>${fileIdOf(stored.ocId)}</oc:fileid>")
                append("<d:getcontentlength>${stored.bytes.size}</d:getcontentlength>")
                stored.md5?.let { append("<oc:checksums><oc:checksum>MD5:$it</oc:checksum></oc:checksums>") }
            }
            fingerprint?.let { append("<nc:data-fingerprint>$it</nc:data-fingerprint>") }
            append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
        }
        append("</d:multistatus>")
    }

    private fun escaped(raw: String) = raw.replace("\"", "&quot;")

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val BACKLOG = 32
        const val APP_PASSWORD = "matrix-app-password"
        const val OCS_USER_SUFFIX = "/cloud/user"
        const val ETAG_DIGEST_BYTES = 8
        const val HEX_RADIX = 16

        const val OK = 200
        const val CREATED = 201
        const val NO_CONTENT = 204
        const val MULTI_STATUS = 207
        const val BAD_REQUEST = 400
        const val UNAUTHORIZED = 401
        const val NOT_FOUND = 404
        const val METHOD_NOT_ALLOWED = 405
        const val CONFLICT = 409
        const val PRECONDITION_FAILED = 412
        const val INSUFFICIENT_STORAGE = 507
        const val SERVER_ERROR = 500
    }
}
