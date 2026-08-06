package dev.inkstone.core.sync

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC §7.3–§7.5 — the PROPFIND body, the multistatus parse, and the headers that matter.
 *
 * The multistatus fixtures use a **different namespace prefix** from Nextcloud's usual
 * `d:`/`oc:`/`nc:` in one case, because a prefix is the document's choice and a parser
 * that string-matches `"d:getetag"` passes every test written against one server and
 * fails against a proxy that rewrites the document.
 */
class WebDavTest {

    private val multistatus = """
        <?xml version="1.0"?>
        <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
          <d:response>
            <d:href>/remote.php/dav/files/noah/Inkstone/</d:href>
            <d:propstat>
              <d:prop>
                <d:getetag>&quot;6512aa11bb&quot;</d:getetag>
                <d:resourcetype><d:collection/></d:resourcetype>
                <oc:id>00000031ocxyz</oc:id>
                <oc:fileid>31</oc:fileid>
                <oc:permissions>RGDNVCK</oc:permissions>
                <oc:size>91234</oc:size>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
            <d:propstat>
              <d:prop><nc:lock/><nc:lock-owner-displayname/></d:prop>
              <d:status>HTTP/1.1 404 Not Found</d:status>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/noah/Inkstone/Lecture%20one.pdf</d:href>
            <d:propstat>
              <d:prop>
                <d:getetag>W/&quot;weakvalue&quot;</d:getetag>
                <d:getcontentlength>2048</d:getcontentlength>
                <d:resourcetype/>
                <oc:id>00000042ocxyz</oc:id>
                <oc:permissions>RDNVW</oc:permissions>
                <oc:checksums><oc:checksum>MD5:0123abcd</oc:checksum></oc:checksums>
                <nc:data-fingerprint>fp-1</nc:data-fingerprint>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    private fun client(recorded: RecordedRequests = RecordedRequests(), body: String, status: HttpStatusCode) =
        WebDavClient(mockTransport(recorded) { respond(body, status) }, testCredential)

    // -----------------------------------------------------------------------
    // The body SPEC §7.3 gives verbatim
    // -----------------------------------------------------------------------

    @Test
    fun `the propfind body asks for everything SPEC section 7_3 lists`() {
        // Kept whole rather than trimmed to "what M5 uses": asking for them all costs one
        // request either way, and M6's reconciler needs oc:id, the checksums and the
        // data-fingerprint without a second round trip.
        val required = listOf(
            "d:getetag", "d:getlastmodified", "d:getcontentlength", "d:resourcetype",
            "oc:id", "oc:fileid", "oc:permissions", "oc:size", "oc:checksums",
            "nc:data-fingerprint", "nc:lock", "nc:lock-owner-displayname",
        )
        for (property in required) {
            assertTrue("<$property/>" in WebDavXml.PROPFIND_BODY, "missing <$property/>")
        }
        assertTrue("xmlns:d=\"DAV:\"" in WebDavXml.PROPFIND_BODY)
        assertTrue("xmlns:oc=\"http://owncloud.org/ns\"" in WebDavXml.PROPFIND_BODY)
        assertTrue("xmlns:nc=\"http://nextcloud.org/ns\"" in WebDavXml.PROPFIND_BODY)
    }

    @Test
    fun `depth is always sent explicitly`() = runTest {
        // ⚠️ SPEC §7.3 — Sabre's default when Depth is omitted is `infinity`, which on a
        // large tree is a denial of service against the user's own server.
        val recorded = RecordedRequests()
        val dav = client(recorded, multistatus, HttpStatusCode.MultiStatus)

        dav.propfind("Inkstone", WebDavClient.Depth.One)
        dav.propfind("Inkstone", WebDavClient.Depth.Zero)

        assertEquals(listOf("1", "0"), recorded.of("PROPFIND").map { it.headers["Depth"] })
        assertTrue(recorded.of("PROPFIND").all { it.headers["Depth"] != null })
    }

    // -----------------------------------------------------------------------
    // Parsing
    // -----------------------------------------------------------------------

    @Test
    fun `a multistatus parses into resources keyed on oc id`() = runTest {
        val dav = client(body = multistatus, status = HttpStatusCode.MultiStatus)

        val resources = assertIs<DavOutcome.Ok<List<DavResource>>>(
            dav.propfind("Inkstone", WebDavClient.Depth.One),
        ).value

        assertEquals(2, resources.size)
        val folder = resources.first()
        assertEquals("Inkstone", folder.path)
        assertTrue(folder.isCollection)
        // SPEC §7.3: "use oc:id everywhere", not oc:fileid.
        assertEquals("00000031ocxyz", folder.ocId)
        assertEquals("31", folder.fileId)
        assertEquals(91234L, folder.recursiveSize)
        assertTrue(folder.mayCreateFolder())

        val file = resources.last()
        // The href was percent-encoded; the path is not.
        assertEquals("Inkstone/Lecture one.pdf", file.path)
        assertEquals("Lecture one.pdf", file.name)
        assertFalse(file.isCollection)
        assertEquals(2048L, file.contentLength)
        assertEquals("0123abcd", file.checksum("md5"))
        assertEquals("fp-1", file.dataFingerprint)
    }

    @Test
    fun `properties in a non-2xx propstat are not treated as present`() = runTest {
        // A server reports a property it does not have as a 404 propstat beside the 200
        // one. Reading both is how oc:id ends up being the empty string on an instance
        // that happens not to expose a field.
        val dav = client(body = multistatus, status = HttpStatusCode.MultiStatus)

        val folder = assertIs<DavOutcome.Ok<List<DavResource>>>(
            dav.propfind("Inkstone", WebDavClient.Depth.One),
        ).value.first()

        assertNull(folder.lockOwner)
        assertFalse(folder.locked)
    }

    @Test
    fun `namespaces are resolved rather than prefixes matched`() = runTest {
        // Prefixes and their declarations renamed together — which is what a proxy that
        // reserialises the document does, and what a parser that string-matches "d:getetag"
        // cannot survive.
        val renamed = multistatus
            .replace("xmlns:d=", "xmlns:DAVNS=")
            .replace("xmlns:oc=", "xmlns:OCNS=")
            .replace("<d:", "<DAVNS:").replace("</d:", "</DAVNS:")
            .replace("<oc:", "<OCNS:").replace("</oc:", "</OCNS:")
        val dav = client(body = renamed, status = HttpStatusCode.MultiStatus)

        val resources = assertIs<DavOutcome.Ok<List<DavResource>>>(
            dav.propfind("Inkstone", WebDavClient.Depth.One),
        ).value

        assertEquals(2, resources.size)
        assertEquals("00000031ocxyz", resources.first().ocId)
    }

    @Test
    fun `a body that is not a multistatus is a Malformed failure rather than a crash`() = runTest {
        val dav = client(body = "<html><body>Log in</body></html>", status = HttpStatusCode.MultiStatus)

        val failure = assertIs<DavOutcome.Failed>(dav.propfind("Inkstone", WebDavClient.Depth.Zero))
        assertIs<DavFailure.Malformed>(failure.error)
    }

    // -----------------------------------------------------------------------
    // ETags — SPEC §7.3's permanent-412 trap
    // -----------------------------------------------------------------------

    @Test
    fun `a weak ETag is recognised and refuses to be an If-Match`() {
        val weak = assertNotNull(ETag.parse("""W/"abc123""""))
        assertTrue(weak.weak)
        assertEquals("abc123", weak.value)
        assertNull(weak.asIfMatch, "returning a weak validator in If-Match is a permanent 412 loop")

        val strong = assertNotNull(ETag.parse(""""abc123""""))
        assertFalse(strong.weak)
        assertEquals("\"abc123\"", strong.asIfMatch, "stored unquoted, re-quoted for If-Match")
    }

    @Test
    fun `a weak ETag survives the parse from a propfind`() = runTest {
        val dav = client(body = multistatus, status = HttpStatusCode.MultiStatus)

        val file = assertIs<DavOutcome.Ok<List<DavResource>>>(
            dav.propfind("Inkstone", WebDavClient.Depth.One),
        ).value.last()

        assertEquals(ETag("weakvalue", weak = true), file.etag)
    }

    @Test
    fun `a blank ETag is null rather than an empty one`() {
        assertNull(ETag.parse(null))
        assertNull(ETag.parse(""))
        assertNull(ETag.parse("   "))
        assertNull(ETag.parse("\"\""))
    }

    // -----------------------------------------------------------------------
    // SPEC §7.5's response table
    // -----------------------------------------------------------------------

    @Test
    fun `every row of the upload response table maps to its own failure`() = runTest {
        val expectations = listOf(
            HttpStatusCode.Unauthorized to DavFailure.Unauthorized::class,
            HttpStatusCode.PreconditionFailed to DavFailure.PreconditionFailed::class,
            HttpStatusCode.InsufficientStorage to DavFailure.QuotaExceeded::class,
            HttpStatusCode.Locked to DavFailure.Locked::class,
            HttpStatusCode.TooManyRequests to DavFailure.RateLimited::class,
            HttpStatusCode.NotFound to DavFailure.NotFound::class,
            HttpStatusCode.Forbidden to DavFailure.Forbidden::class,
        )

        for ((status, expected) in expectations) {
            val dav = client(body = "", status = status)
            val failure = assertIs<DavOutcome.Failed>(dav.put("a.pdf", byteArrayOf(1))).error
            assertEquals(expected, failure::class, "HTTP ${status.value} mapped to $failure")
        }
    }

    @Test
    fun `a 401 is never retryable and neither is a 412`() {
        // CLAUDE.md rule 5 and SPEC §7.5. Both look like "try again later" and neither is.
        assertFalse(DavFailure.Unauthorized.isRetryable)
        assertFalse(DavFailure.PreconditionFailed(null).isRetryable)
        assertFalse(DavFailure.QuotaExceeded.isRetryable)
        assertTrue(DavFailure.Transport("offline").isRetryable)
        assertTrue(DavFailure.RateLimited(30).isRetryable)
        assertTrue(DavFailure.Server(503, null).isRetryable)
        assertFalse(DavFailure.Server(400, null).isRetryable)
    }

    @Test
    fun `an upload reads its ETag and file id off the response with no follow-up propfind`() = runTest {
        // SPEC §7.4: "OC-FileId and OC-Etag come back as response headers on PUT/MOVE/COPY
        // — no follow-up PROPFIND."
        val recorded = RecordedRequests()
        val dav = WebDavClient(
            mockTransport(recorded) {
                respond(
                    "",
                    HttpStatusCode.Created,
                    io.ktor.http.headersOf(
                        "OC-ETag" to listOf("\"newetag\""),
                        "OC-FileId" to listOf("00000042ocxyz"),
                    ),
                )
            },
            testCredential,
        )

        val receipt = assertIs<DavOutcome.Ok<UploadReceipt>>(dav.put("Inkstone/a.pdf", byteArrayOf(1, 2))).value

        assertEquals(ETag("newetag", weak = false), receipt.etag)
        assertEquals("00000042ocxyz", receipt.ocId)
        assertEquals(0, recorded.count("PROPFIND"))
    }

    @Test
    fun `a create sends If-None-Match star and a move sends Overwrite F`() = runTest {
        val recorded = RecordedRequests()
        val dav = WebDavClient(mockTransport(recorded) { respond("", HttpStatusCode.Created) }, testCredential)

        dav.put("Inkstone/a.pdf", byteArrayOf(1), ifNoneMatchStar = true, mtimeEpochSeconds = 1700000000L)
        dav.move("Inkstone/a.pdf", "Inkstone/b.pdf")

        assertEquals("*", recorded.of("PUT").single().headers["If-None-Match"])
        assertEquals("1700000000", recorded.of("PUT").single().headers["X-OC-Mtime"])
        assertEquals("F", recorded.of("MOVE").single().headers["Overwrite"])
    }

    @Test
    fun `a recursive mkcol treats 405 as already there`() = runTest {
        val recorded = RecordedRequests()
        val dav = WebDavClient(
            mockTransport(recorded) { respond("", HttpStatusCode.MethodNotAllowed) },
            testCredential,
        )

        assertIs<DavOutcome.Ok<Unit>>(dav.mkcolRecursive("Inkstone/Physics/Term 2"))
        assertEquals(3, recorded.count("MKCOL"), "one MKCOL per level, root first")
    }

    // -----------------------------------------------------------------------
    // Quota — SPEC §7.9's sentinel negatives
    // -----------------------------------------------------------------------

    @Test
    fun `quota sentinels are not rendered as negative free space`() {
        // SPEC §7.9: -1 uncomputed, -2 unknown, -3 unlimited. Printing the raw value gives
        // the user "-3 MB left".
        fun resource(quota: Long?) = DavResource(
            path = "", href = "", isCollection = true, etag = null, lastModified = null,
            contentLength = null, ocId = null, fileId = null, permissions = null,
            recursiveSize = null, checksums = emptyList(), dataFingerprint = null,
            lockOwner = null, locked = false, quotaAvailableBytes = quota,
        )

        assertNull(resource(-1L).availableBytesOrNull())
        assertNull(resource(-2L).availableBytesOrNull())
        assertNull(resource(-3L).availableBytesOrNull())
        assertTrue(resource(-3L).quotaIsUnlimited)
        assertEquals(1024L, resource(1024L).availableBytesOrNull())
    }
}
