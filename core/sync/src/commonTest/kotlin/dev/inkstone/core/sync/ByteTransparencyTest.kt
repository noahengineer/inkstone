package dev.inkstone.core.sync

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.UserAgent
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * The transport half of **SPEC Appendix B spike #4** — *"does a PDF incremental update
 * survive Nextcloud's storage and any server-side processing intact?"*
 *
 * ## Why the payload here is not a PDF
 *
 * A file whose front is the original bytes verbatim and whose tail is one incremental
 * update is fragile in exactly one way: anything that re-serialises, transcodes or
 * truncates it destroys `published[0, originalLength) == original`. And the most likely
 * place for that is not the server — it is a client that reads a body as a `String`,
 * gzips it without saying so, or loses the tail of a stream.
 *
 * None of those failures care what the bytes *mean*. They care about nulls, high bytes,
 * CR/LF pairs and length — so the fixture is built out of exactly those, plus the
 * `%PDF`/`%%EOF`/`startxref` landmarks that make it recognisably the same *shape* as a
 * published note. That makes this test cheap, deterministic, and free of any dependency
 * on `:android:pdf`, which `:core:sync` may not have.
 *
 * The half that needs a real PDF and a real server is `:android:pdf`'s
 * `PdfRoundTripSpikeTest`.
 */
class ByteTransparencyTest {

    /**
     * `original bytes + one incremental update`, in the shape SPEC §5.2 produces.
     *
     * Every byte value 0..255 appears in both halves, including `0x00` (which truncates a
     * C string), `0x0D 0x0A` (which a text-mode transfer rewrites) and `0x1A` (which
     * historically ended a file on some platforms).
     */
    private fun incrementalUpdateShapedBytes(updateSize: Int = 4096): Pair<ByteArray, ByteArray> {
        val original = buildList {
            addAll("%PDF-1.7\n".encodeToByteArray().toList())
            for (i in 0 until 8192) add((i % 256).toByte())
            addAll("\r\nstartxref\n1234\n%%EOF\n".encodeToByteArray().toList())
        }.toByteArray()

        val update = buildList {
            addAll("\n% incremental update\n".encodeToByteArray().toList())
            for (i in 0 until updateSize) add(((i * 7 + 13) % 256).toByte())
            addAll("\r\nstartxref\n${original.size}\n%%EOF\n".encodeToByteArray().toList())
        }.toByteArray()

        return original to (original + update)
    }

    @Test
    fun `a body round-trips through PUT and GET byte for byte`() = runTest {
        val (original, published) = incrementalUpdateShapedBytes()
        val store = HashMap<String, ByteArray>()
        val dav = WebDavClient(byteExactStore(store), testCredential)

        assertIs<DavOutcome.Ok<UploadReceipt>>(dav.put("Inkstone/note.pdf", published, ifNoneMatchStar = true))
        val downloaded = assertIs<DavOutcome.Ok<ByteArray>>(dav.get("Inkstone/note.pdf")).value

        assertContentEquals(published, downloaded, "the bytes changed in transit")
        assertContentEquals(
            original,
            downloaded.copyOfRange(0, original.size),
            "the original prefix — the whole of SPEC §5.2 — did not survive",
        )
    }

    @Test
    fun `a body large enough to stream is not truncated or transcoded`() = runTest {
        // A small body survives a "read it as a String" bug by luck of encoding. A large
        // one, spanning many buffers, does not — which is the point of the size.
        val (original, published) = incrementalUpdateShapedBytes(updateSize = 3 * 1024 * 1024)
        val store = HashMap<String, ByteArray>()
        val dav = WebDavClient(byteExactStore(store), testCredential)

        dav.put("Inkstone/big.pdf", published)
        val downloaded = assertIs<DavOutcome.Ok<ByteArray>>(dav.get("Inkstone/big.pdf")).value

        assertContentEquals(published, downloaded)
        assertContentEquals(original, downloaded.copyOfRange(0, original.size))
    }

    @Test
    fun `what is uploaded is what the caller passed and not a re-encoding of it`() = runTest {
        // The assertion is on the *request* rather than on a round trip, so a store that
        // happened to be forgiving could not hide an outbound transcoding.
        val (_, published) = incrementalUpdateShapedBytes()
        var sent: ByteArray? = null
        val transport = NextcloudTransport(
            io.ktor.client.HttpClient(MockEngine) {
                install(UserAgent) { agent = INKSTONE_USER_AGENT }
                expectSuccess = false
                engine {
                    addHandler { request ->
                        sent = request.body.readAllBytes()
                        respond("", HttpStatusCode.Created)
                    }
                }
            },
        )

        WebDavClient(transport, testCredential).put("Inkstone/note.pdf", published)

        assertContentEquals(published, assertNotNull(sent))
    }

    // -----------------------------------------------------------------------

    /**
     * A WebDAV store that stores bytes and returns them, and does nothing else.
     *
     * Deliberately dumb: its job is to hold the *transport* still — Ktor's engine, the
     * content type, the body conversion — so a failure above is a failure in our client
     * rather than in a clever fake.
     */
    private fun byteExactStore(store: MutableMap<String, ByteArray>): NextcloudTransport = NextcloudTransport(
        io.ktor.client.HttpClient(MockEngine) {
            install(UserAgent) { agent = INKSTONE_USER_AGENT }
            expectSuccess = false
            engine {
                addHandler { request ->
                    val key = request.url.encodedPath
                    when (request.method.value) {
                        "PUT" -> {
                            store[key] = request.body.readAllBytes()
                            respond("", HttpStatusCode.Created, headersOf("OC-ETag", "\"e-${store[key]?.size}\""))
                        }

                        "GET" -> store[key]
                            ?.let { respond(ByteReadChannel(it), HttpStatusCode.OK) }
                            ?: respondError(HttpStatusCode.NotFound)

                        else -> respondError(HttpStatusCode.MethodNotAllowed)
                    }
                }
            }
        },
    )
}

private fun OutgoingContent.readAllBytes(): ByteArray = when (this) {
    is OutgoingContent.ByteArrayContent -> bytes()
    else -> error("the client sent a ${this::class.simpleName} body; a PDF must go out as raw bytes")
}
