package dev.inkstone.android.pdf

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.NextcloudCredential
import dev.inkstone.core.storage.InkstoneMarker
import dev.inkstone.core.storage.PdfOpenMode
import dev.inkstone.core.storage.detectOpenMode
import dev.inkstone.core.storage.sha256Hex
import dev.inkstone.core.sync.DavOutcome
import dev.inkstone.core.sync.NextcloudTransport
import dev.inkstone.core.sync.WebDavClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⭐ **SPEC Appendix B spike #4 — owned by M5.**
 *
 * > *Does a PDF incremental update survive Nextcloud's storage and any server-side
 * > processing intact?*
 * > **If the answer is bad:** fall back to a full rewrite; re-add `assets/source.pdf` to
 * > the bundle and accept the size.
 *
 * The question has two halves that fail in completely different ways.
 *
 * **The transport half** — can a client push these bytes and get them back unchanged? —
 * is `:core:sync`'s `ByteTransparencyTest`, because that is where the transport is, and
 * because a payload's *shape* is irrelevant to it: what breaks a body is being read as a
 * string or transcoded, and that breaks any bytes equally.
 *
 * **This half** is the part that is specifically about the file: that the thing being
 * uploaded really is `original bytes verbatim + one update`, that it still is after a
 * round trip, and that it still opens as ours. Against a **real Nextcloud**, because the
 * question is about the server — its storage, its previewer, an antivirus scanner, a
 * reverse proxy — and no mock can answer that.
 *
 * ```
 * INKSTONE_SPIKE_URL=https://cloud.example.com
 * INKSTONE_SPIKE_LOGIN=noah@example.com     # the login name, which may be an email
 * INKSTONE_SPIKE_UID=noah                   # ocs.data.id — NOT the login name (SPEC §0.1 #3)
 * INKSTONE_SPIKE_APP_PASSWORD=…             # an app password, never a real password
 *
 * ./gradlew :android:pdf:testDebugUnitTest --tests '*PdfRoundTripSpikeTest*'
 * ```
 *
 * Without those, the live test **skips loudly**. A spike that reports success without
 * having run is worse than no spike, so the skip prints what is and is not proven.
 *
 * `[`the published file really is an incremental update`]` always runs, so the fixture
 * this spike depends on cannot rot unnoticed while the server half is unconfigured.
 *
 * ## Why here
 *
 * This is the only module that can build an incremental-update PDF, and `:core:sync` is a
 * **test-only** dependency of it — the main source set has no idea sync exists, so SPEC
 * §2.1's direction is untouched.
 */
class PdfRoundTripSpikeTest {

    private val writer = PdfBoxWriter()
    private val documentId = DocumentId("0192f3c4-5555-7abc-8def-0123456789ab")
    private val bundle = "PK pretend this is note.inkstone".toByteArray(Charsets.US_ASCII)
    private val remotePath = "Inkstone-spike/spike-${documentId.value}.pdf"

    /** The fixture PDF plus one incremental update carrying ink. */
    private fun publishedFixture(): Pair<ByteArray, ByteArray> {
        val original = PdfFixtures.sourcePdf()
        val plans = (0 until PAGE_COUNT).map { index ->
            if (index == 0) {
                PdfFixtures.plan(index, listOf(PdfFixtures.square("spike-a", 72f, 72f, 40f)))
            } else {
                PdfFixtures.plan(index, emptyList())
            }
        }
        val published = writer.publish(
            original,
            PublishPlan(
                marker = InkstoneMarker(
                    documentId = documentId,
                    schemaVersion = 1,
                    bundleSha256 = bundle.sha256Hex(),
                    originalLength = original.size.toLong(),
                    originalSha256 = original.sha256Hex(),
                ),
                pages = plans,
                bundle = bundle,
            ),
        )
        return original to published
    }

    /**
     * Everything the spike claims about a file, in one place — so the local check and the
     * post-download check cannot drift apart.
     */
    private fun assertIntact(original: ByteArray, published: ByteArray, where: String) {
        // ⭐ The property the whole of SPEC §5.2 rests on. Checked as a byte prefix rather
        // than by re-parsing: "the same document" is a weaker claim than "the same bytes",
        // and the design promises the stronger one.
        assertContentEquals(
            original,
            published.copyOfRange(0, original.size),
            "$where: the original bytes are no longer verbatim at the front",
        )
        assertTrue(published.size > original.size, "$where: nothing was appended")

        val marker = assertNotNull(PdfReader.open(published).use { it.marker() }, "$where: the XMP marker is gone")
        assertEquals(documentId, marker.documentId)
        assertEquals(original.size.toLong(), marker.originalLength)
        assertEquals(original.sha256Hex(), marker.originalSha256)

        val mode = detectOpenMode(
            marker = marker,
            bundle = PdfReader.open(published).use { it.bundle() },
            originalBytesIntact = true,
            understoodVersion = 1,
        )
        assertIs<PdfOpenMode.Normal>(mode, "$where: the file no longer opens as ours (SPEC §5.6)")

        PdfReader.open(published).use { reader ->
            assertEquals(PAGE_COUNT, reader.geometries().size, "$where: the page count changed")
            val recovered = reader.recoveredPages(RecordingRebuilder())
            assertTrue(
                recovered.sumOf { it.strokes.size } > 0,
                "$where: the ink annotations did not survive",
            )
        }
    }

    @Test
    fun `the published file really is an incremental update`() {
        val (original, published) = publishedFixture()

        assertIntact(original, published, "local")
    }

    @Test
    fun `SPIKE B4 — the incremental update survives a real Nextcloud round trip`() = runTest {
        val live = liveCredential()
        if (live == null) {
            println(
                """
                |
                |  SPIKE B#4 SKIPPED — no live Nextcloud configured.
                |
                |  PROVEN without it:  the published file is `original + one update`, our
                |                      WebDAV client is byte-transparent (:core:sync's
                |                      ByteTransparencyTest), and the file re-opens as ours.
                |  NOT PROVEN:         that Nextcloud's storage, previewer, antivirus or a
                |                      reverse proxy leaves the bytes alone.
                |
                |  Set INKSTONE_SPIKE_URL / _LOGIN / _UID / _APP_PASSWORD and re-run.
                |
                """.trimMargin(),
            )
            return@runTest
        }

        val (original, published) = publishedFixture()
        assertIntact(original, published, "before upload")

        val dav = WebDavClient(NextcloudTransport.create(), live)
        dav.mkcolRecursive(remotePath.substringBeforeLast('/'))
        // A leftover from a previous run would make If-None-Match: * fail.
        dav.delete(remotePath)

        val receipt = assertIs<DavOutcome.Ok<*>>(
            dav.put(remotePath, published, ifNoneMatchStar = true),
            "the upload itself failed",
        )
        println("SPIKE B#4: uploaded ${published.size} bytes to ${live.serverUrl}/$remotePath — ${receipt.value}")

        try {
            val downloaded = assertIs<DavOutcome.Ok<ByteArray>>(dav.get(remotePath)).value

            assertContentEquals(published, downloaded, "the server changed the bytes")
            assertIntact(original, downloaded, "after download")

            println(
                "SPIKE B#4: PASS — ${published.size} bytes round-tripped byte-identically, " +
                    "the original prefix is intact and the file still opens as ours.",
            )
        } finally {
            // Nextcloud's DELETE is soft (SPEC §7.7), so the file lands in the server's
            // trash rather than being destroyed — including after a failure, where it is
            // still there to look at.
            dav.delete(remotePath)
        }
    }

    private fun liveCredential(): NextcloudCredential? {
        val server = env("INKSTONE_SPIKE_URL") ?: return null
        val login = env("INKSTONE_SPIKE_LOGIN") ?: return null
        // ⚠️ SPEC §0.1 #3 — the WebDAV path segment is `ocs.data.id`, not the login name.
        // Taken as two variables so a run against an instance where they differ (an email
        // login) actually exercises the difference rather than hiding it.
        val uid = env("INKSTONE_SPIKE_UID") ?: return null
        val password = env("INKSTONE_SPIKE_APP_PASSWORD") ?: return null
        return NextcloudCredential(
            serverUrl = server.trimEnd('/'),
            loginName = login,
            uid = uid,
            appPassword = password,
        )
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private companion object {
        const val PAGE_COUNT = 4
    }
}
