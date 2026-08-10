package dev.inkstone.app.sync.matrix

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/*
 * ⭐ A counting, pass-through reverse proxy in front of the **live** Nextcloud.
 *
 * ## Why it exists
 *
 * Half of SPEC §7's design is about economy rather than behaviour: one PROPFIND for an idle poll
 * (§7.4 rule 1), one PUT for five hundred offline saves (§7.6), one confirmation call after a 401
 * and then nothing at all (§7.2). Every one of those claims is a **request count**, and until this
 * file existed the live half of the matrix could not make any of them — it printed
 * `(live) request counts not observable` and proved behaviour without economy, which is the half
 * of the design that protects the user's own server.
 *
 * The client may not count for itself. Counting inside `:core:sync` means a Ktor plugin, and `:app`
 * cannot hold one at all — `NextcloudTransport` is opaque outside that module precisely so that
 * nothing up here can make a request (CLAUDE.md, "Ktor's own types must not leave `:core:sync`").
 * A proxy is the only remaining vantage point, and once there is one it also produces the two
 * server behaviours `occ` cannot reach: a **weak** validator (SPEC §7.3, row 16) and a credential
 * the server **refuses** (SPEC §7.2, row 20).
 *
 * ## Why hand-rolled rather than `ktor-server-cio`
 *
 * [LocalMatrixServer] already hand-rolls an HTTP/1.1 codec, so this is the cheaper of the two
 * shapes: no new coordinate to keep verified (CLAUDE.md rule 3), and — the part that matters —
 * no Ktor types on `:app`'s test compile classpath, which is the boundary the paragraph above is
 * about. A pass-through needs no routing, no content negotiation and no serialisation.
 *
 * ## Faithfulness, which is the part that can silently invalidate 27 green rows
 *
 * Every method is forwarded (PROPFIND, MKCOL and MOVE included), every header in both directions,
 * the body in both directions, and the status line verbatim. Three rewrites are unavoidable, and
 * each of them is a bug if it is missed rather than a nicety:
 *
 *  - **`Host`** becomes the upstream authority. Nextcloud checks `trusted_domains`, so a
 *    `Host: 127.0.0.1:<port>` is answered with an untrusted-domain page instead of DAV — every row
 *    would fail, but on a parse error rather than on its own claim.
 *  - **`Destination`** is an absolute URL that `WebDavClient.move` built from `credential.serverUrl`
 *    — which is now this proxy. Forwarded unchanged, Sabre reads a destination on a different
 *    origin as a cross-server move and refuses it, so row 11's MOVE would 502 or escape the proxy.
 *  - **the upstream base path** is prefixed back on, so an instance served at
 *    `https://host/nextcloud` still resolves. The throwaway server has none, so this is a trap
 *    avoided rather than a feature used.
 *
 * Hop-by-hop headers are dropped in both directions and the message re-framed with an explicit
 * `Content-Length`, which also makes it one request per connection — the same shape
 * [LocalMatrixServer] has, and what makes counting exact rather than nearly right.
 *
 * Block comment, not KDoc: a file-level KDoc attaches to no declaration.
 */

/**
 * A loopback origin that forwards to [upstreamServerUrl] and counts what went past.
 *
 * @param upstreamServerUrl the real server, `http(s)://host[:port][/base]`, with no trailing slash
 *   required. Point [dev.inkstone.core.model.NextcloudCredential.serverUrl] at [origin] instead.
 */
internal class CountingProxy(upstreamServerUrl: String) : AutoCloseable {

    private val secure = upstreamServerUrl.startsWith("https://", ignoreCase = true)
    private val withoutScheme = upstreamServerUrl.substringAfter("://").trimEnd('/')

    /** `host` or `host:port` — what goes back into `Host:`. */
    private val authority = withoutScheme.substringBefore('/')

    /** `""` for a server at the domain root, `/nextcloud` for one in a subdirectory. */
    private val basePath = withoutScheme.removePrefix(authority)

    private val host = authority.substringBefore(':')
    private val port = authority.substringAfter(':', "").toIntOrNull() ?: if (secure) HTTPS_PORT else HTTP_PORT

    /** The absolute upstream prefix, for rewriting `Destination` back onto the real server. */
    private val upstreamOrigin = (if (secure) "https://" else "http://") + withoutScheme

    private val socket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))

    /** What a credential's `serverUrl` must be set to. Plain HTTP: this leg never leaves the host. */
    val origin: String = "http://$LOOPBACK:${socket.localPort}"

    val counts: RequestCounts = RequestCounts()

    /**
     * SPEC §7.3, row 16 — rewrite `ETag` and `OC-ETag` on the way back as `W/"…"`.
     *
     * ⚠️ Response **headers** only, and not `<d:getetag>` inside a multistatus body, because that is
     * what the real thing does: nginx weakens the validator it emits for a response it compressed,
     * and it does not rewrite XML it is merely forwarding. So a file's validator arrives weak on a
     * GET or a PUT and strong in a PROPFIND — an inconsistency SPEC §7.3's checksum fallback exists
     * for and `RemoteWalk.unchanged` must refuse. ([LocalMatrixServer.useWeakEtags] weakens the body
     * too, so between them the two halves of row 16 cover both shapes.)
     */
    @Volatile
    var weakenValidators: Boolean = false

    /**
     * SPEC §7.2, row 20 — the `Authorization` header to send **instead of** the real one, or null.
     *
     * Set to a deliberately wrong app password and the 401 that comes back is a real Nextcloud's,
     * with its real brute-force accounting. That is the honest substitute for
     * `occ user:delete-app-password` on an instance with no shell available.
     */
    @Volatile
    var forcedAuthorization: String? = null

    @Volatile
    private var running = true

    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "matrix-proxy").apply { isDaemon = true }
    }

    init {
        thread(isDaemon = true, name = "matrix-proxy-accept") { acceptLoop() }
    }

    override fun close() {
        running = false
        socket.close()
        workers.shutdownNow()
    }

    // -----------------------------------------------------------------------
    // The client leg
    // -----------------------------------------------------------------------

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    // `accept` throws when `close` pulls the socket out from under it, which is how this loop ends
    // normally. Anything else surfaces on the next request, which then fails to connect.
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
    // An exception escaping here would close the connection with no status, which the client
    // reports as a *transport* failure — so a bug in this proxy would look exactly like the flaky
    // network the live suite exists to rule out. A 502 carrying the message is readable.
    private fun serve(client: Socket) {
        client.use { connection ->
            connection.soTimeout = SOCKET_TIMEOUT_MILLIS
            val input = BufferedInputStream(connection.getInputStream())
            val requestLine = Http1.readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val headers = Http1.readHeaders(input)
            val body = Http1.readBody(input, headers)
            counts.record(parts[0], headers.map { it.first })
            val response = try {
                forward(parts[0].uppercase(), parts[1], headers, body)
            } catch (failure: Throwable) {
                Relayed("HTTP/1.1 502 Bad Gateway", emptyList(), failure.toString().encodeToByteArray())
            }
            write(connection, response)
        }
    }

    private fun write(connection: Socket, response: Relayed) {
        val out = connection.getOutputStream()
        val head = buildString {
            append(response.statusLine).append("\r\n")
            for ((name, value) in response.headers) append(name).append(": ").append(value).append("\r\n")
            append("Content-Length: ${response.body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.encodeToByteArray())
        if (response.body.isNotEmpty()) out.write(response.body)
        out.flush()
    }

    // -----------------------------------------------------------------------
    // The upstream leg
    // -----------------------------------------------------------------------

    private fun connectUpstream(): Socket = if (secure) {
        // Untested against an HTTPS instance: the throwaway server is plain HTTP on purpose
        // (`nextcloud-test.local.properties` says why). Here so that pointing the suite at a real
        // one fails on its certificate rather than on this fixture assuming cleartext.
        SSLSocketFactory.getDefault().createSocket(host, port)
    } else {
        Socket(host, port)
    }

    private fun forward(
        method: String,
        target: String,
        headers: List<Pair<String, String>>,
        body: ByteArray,
    ): Relayed = connectUpstream().use { upstream ->
        upstream.soTimeout = SOCKET_TIMEOUT_MILLIS
        val out = upstream.getOutputStream()
        out.write(requestHead(method, target, headers, body.size).encodeToByteArray())
        if (body.isNotEmpty()) out.write(body)
        out.flush()

        val input = BufferedInputStream(upstream.getInputStream())
        val statusLine = Http1.readLine(input) ?: error("upstream closed before sending a status line")
        val upstreamHeaders = Http1.readHeaders(input)
        val received = Http1.readResponseBody(input, upstreamHeaders, statusOf(statusLine))
        Relayed(statusLine, responseHeaders(upstreamHeaders), received)
    }

    private fun requestHead(
        method: String,
        target: String,
        headers: List<Pair<String, String>>,
        bodySize: Int,
    ): String = buildString {
        // The base path goes back on here and nowhere else: the client addressed this proxy's
        // origin, which has no base path of its own.
        append(method).append(' ').append(basePath).append(target).append(" HTTP/1.1\r\n")
        for ((name, value) in headers) {
            if (name.lowercase() in DROPPED_REQUEST_HEADERS) continue
            append(name).append(": ").append(rewrittenRequestHeader(name, value)).append("\r\n")
        }
        append("Host: ").append(authority).append("\r\n")
        append("Content-Length: ").append(bodySize).append("\r\n")
        append("Connection: close\r\n\r\n")
    }

    private fun rewrittenRequestHeader(name: String, value: String): String = when (name.lowercase()) {
        // SPEC §7.2, row 20 — a real 401 from a real server, produced by sending a real wrong
        // password rather than by a switch inside the app.
        "authorization" -> forcedAuthorization ?: value

        // SPEC §7.5's MOVE. An absolute URL naming *this* proxy is a cross-server move to Sabre.
        "destination" -> if (value.startsWith(origin)) upstreamOrigin + value.removePrefix(origin) else value

        else -> value
    }

    private fun responseHeaders(upstreamHeaders: List<Pair<String, String>>): List<Pair<String, String>> =
        upstreamHeaders.mapNotNull { (name, value) ->
            val lowered = name.lowercase()
            when {
                lowered in DROPPED_RESPONSE_HEADERS -> null

                lowered == "etag" || lowered == "oc-etag" -> name to weakened(value)

                // A redirect must not send the client off the proxy, or the next request escapes
                // the counter without anything failing.
                lowered == "location" && value.startsWith(upstreamOrigin) ->
                    name to origin + value.removePrefix(upstreamOrigin)

                else -> name to value
            }
        }

    private fun weakened(validator: String): String = when {
        !weakenValidators || validator.startsWith("W/", ignoreCase = true) -> validator
        validator.startsWith("\"") -> "W/$validator"
        else -> "W/\"$validator\""
    }

    private fun statusOf(statusLine: String): Int = statusLine.split(' ').firstNotNullOfOrNull { it.toIntOrNull() } ?: 0

    private class Relayed(val statusLine: String, val headers: List<Pair<String, String>>, val body: ByteArray)

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val BACKLOG = 32
        const val HTTP_PORT = 80
        const val HTTPS_PORT = 443

        /** Long enough for a real upload over wifi, short enough that a hang is not a 4-minute wait. */
        const val SOCKET_TIMEOUT_MILLIS = 60_000

        /**
         * Framing and connection management are this proxy's, not the client's.
         *
         * `expect` is in the list because the body is already fully buffered by the time anything is
         * forwarded, so relaying a `100-continue` handshake would be work with no observable effect.
         */
        val DROPPED_REQUEST_HEADERS = setOf(
            "host",
            "content-length",
            "transfer-encoding",
            "connection",
            "keep-alive",
            "proxy-connection",
            "expect",
            "te",
            "upgrade",
        )

        val DROPPED_RESPONSE_HEADERS = setOf("content-length", "transfer-encoding", "connection", "keep-alive")
    }
}

/**
 * Just enough HTTP/1.1 to relay one message.
 *
 * Deliberately **not** shared with [LocalMatrixServer]'s codec even though the two overlap: that one
 * lower-cases header names and looks them up, because it is answering; this one preserves their name
 * and order, because it is forwarding, and a proxy that normalised what it passed on would be
 * testing its own idea of the request rather than the app's.
 */
private object Http1 {

    /** Byte-oriented: the body may be a PDF, so nothing here may decode ahead of the headers. */
    fun readLine(input: InputStream): String? {
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

    /** In order, with the original casing, up to the blank line. Duplicates are kept — `Set-Cookie`. */
    fun readHeaders(input: InputStream): List<Pair<String, String>> {
        val headers = ArrayList<Pair<String, String>>()
        var line = readLine(input)
        while (!line.isNullOrEmpty()) {
            headers += line.substringBefore(':').trim() to line.substringAfter(':').trim()
            line = readLine(input)
        }
        return headers
    }

    fun readBody(input: InputStream, headers: List<Pair<String, String>>): ByteArray {
        if (isChunked(headers)) return readChunked(input)
        val length = value(headers, "content-length")?.toIntOrNull() ?: 0
        return if (length <= 0) ByteArray(0) else readFully(input, length)
    }

    /**
     * The response body, which needs the status as well as the headers.
     *
     * 204 and 304 carry none however they are framed — forwarding a body for either would make the
     * client's next read of a reused connection start mid-message.
     */
    fun readResponseBody(input: InputStream, headers: List<Pair<String, String>>, status: Int): ByteArray {
        if (status == NO_CONTENT || status == NOT_MODIFIED || status < OK) return ByteArray(0)
        if (isChunked(headers)) return readChunked(input)
        val length = value(headers, "content-length")?.toIntOrNull()
        // No length and no chunking: read to end-of-stream. Well defined here and nowhere else,
        // because every request goes upstream with `Connection: close`.
        return if (length == null) input.readBytes() else readFully(input, length)
    }

    private fun isChunked(headers: List<Pair<String, String>>): Boolean =
        value(headers, "transfer-encoding")?.contains("chunked", ignoreCase = true) == true

    private fun value(headers: List<Pair<String, String>>, name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        var size = nextChunkSize(input)
        while (size > 0) {
            out.write(readFully(input, size))
            readLine(input)
            size = nextChunkSize(input)
        }
        // The terminating zero-length chunk's trailer line.
        readLine(input)
        return out.toByteArray()
    }

    /** Zero for the terminating chunk and for anything unparseable, which ends the body either way. */
    private fun nextChunkSize(input: InputStream): Int =
        readLine(input)?.trim()?.substringBefore(';')?.toIntOrNull(HEX_RADIX) ?: 0

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

    private const val HEX_RADIX = 16
    private const val OK = 200
    private const val NO_CONTENT = 204
    private const val NOT_MODIFIED = 304
}
