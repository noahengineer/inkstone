package dev.inkstone.core.storage

import dev.inkstone.core.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC §5.6's three-way open decision, one test per row of its table — plus the
 * fourth row M4 added.
 *
 * The whole point of the marker is that it is the *only* thing separating "our
 * file, mangled" from "a stranger's PDF", and the two need opposite treatment. So
 * each row is asserted rather than reasoned about.
 */
class DocumentIdentityTest {

    private val id = DocumentId("0192f3c4-1111-7abc-8def-0123456789ab")
    private val bundle = "a bundle, near enough".encodeToByteArray()

    private fun marker(sha: String = bundle.sha256Hex(), schema: Int = 1) =
        InkstoneMarker(id, schema, sha, originalLength = 1024L, originalSha256 = "ab".repeat(32))

    // -----------------------------------------------------------------------
    // The table
    // -----------------------------------------------------------------------

    @Test
    fun `marker plus matching bundle is Normal`() {
        val mode = detectOpenMode(marker(), bundle)
        assertIs<PdfOpenMode.Normal>(mode)
        assertEquals(id, mode.marker.documentId)
    }

    @Test
    fun `marker with the bundle stripped is Recovered`() {
        val mode = detectOpenMode(marker(), bundle = null)
        assertIs<PdfOpenMode.Recovered>(mode)
        assertEquals(RecoveryReason.BUNDLE_MISSING, mode.reason)
    }

    /**
     * The case the hash exists for. A third-party editor that keeps the embedded
     * file but rebuilds it leaves something that *is* a bundle and is not ours, and
     * folding a note from it would be silent corruption of a subtler kind than a
     * missing file.
     */
    @Test
    fun `marker with a bundle that does not hash is Recovered`() {
        val mode = detectOpenMode(marker(sha = "00".repeat(32)), bundle)
        assertIs<PdfOpenMode.Recovered>(mode)
        assertEquals(RecoveryReason.BUNDLE_HASH_MISMATCH, mode.reason)
    }

    @Test
    fun `marker with a bundle that hashes but will not open is Recovered`() {
        val mode = detectOpenMode(marker(), bundle, bundleIsReadable = false)
        assertIs<PdfOpenMode.Recovered>(mode)
        assertEquals(RecoveryReason.BUNDLE_UNREADABLE, mode.reason)
    }

    /**
     * ROADMAP M4: *"a PDF annotated in Acrobat with no XMP marker opens as Foreign,
     * not Recovered."* Adopting it would claim a stranger's document under one of
     * our ids, and it would then sync.
     */
    @Test
    fun `no marker is Foreign — even with a perfectly good bundle attached`() {
        assertEquals(PdfOpenMode.Foreign, detectOpenMode(marker = null, bundle = bundle))
    }

    /**
     * Added in M4. SPEC §5.8 forbids re-saving a file from the future, and Recovered
     * mode is the one path that would have: it rewrites the note in *this* build's
     * vocabulary, under the original id, with the clock deliberately bumped past
     * everything — so it would win the next sync and take the newer data with it.
     */
    @Test
    fun `a marker from a newer schema is refused rather than recovered`() {
        val mode = detectOpenMode(marker(schema = 9), bundle = null, understoodVersion = 1)
        assertIs<PdfOpenMode.TooNew>(mode)
        assertEquals(1, mode.understoodVersion)
    }

    // -----------------------------------------------------------------------
    // What a recovery reports
    // -----------------------------------------------------------------------

    @Test
    fun `every recovery declares the losses SPEC 5_6 point 4 requires`() {
        val mode = detectOpenMode(marker(), bundle = null)
        assertIs<PdfOpenMode.Recovered>(mode)
        assertTrue(RecoveryLoss.PRESSURE_AND_WIDTH in mode.losses)
        assertTrue(RecoveryLoss.OP_HISTORY in mode.losses)
        assertTrue(RecoveryLoss.UNDO_STACK in mode.losses)
        // Not claimed unless true: this file's original is intact.
        assertTrue(RecoveryLoss.PRISTINE_ORIGINAL !in mode.losses)
        assertTrue(RecoveryLoss.FLATTENED_PAGE !in mode.losses)
    }

    @Test
    fun `a rewritten file also declares the pristine original gone`() {
        val mode = detectOpenMode(marker(), bundle = null, originalBytesIntact = false, hadFlattenedPage = true)
        assertIs<PdfOpenMode.Recovered>(mode)
        assertTrue(RecoveryLoss.PRISTINE_ORIGINAL in mode.losses)
        assertTrue(RecoveryLoss.FLATTENED_PAGE in mode.losses)
    }

    // -----------------------------------------------------------------------
    // The XMP packet
    // -----------------------------------------------------------------------

    @Test
    fun `the marker survives a trip through XMP`() {
        val original = marker()
        assertEquals(original, InkstoneXmp.parse(InkstoneXmp.packet(original)))
    }

    /**
     * XMP allows a property to be an attribute or a child element and tools convert
     * between the two. A note must not fall into Recovered mode — losing its op
     * history — because some other program preferred a different serialisation.
     */
    @Test
    fun `the element form parses too`() {
        val elementForm = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about="" xmlns:inkstone="${InkstoneMarker.NAMESPACE}">
               <inkstone:documentId>${id.value}</inkstone:documentId>
               <inkstone:schemaVersion>1</inkstone:schemaVersion>
               <inkstone:bundleSha256>${"cd".repeat(32)}</inkstone:bundleSha256>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val parsed = InkstoneXmp.parse(elementForm)
        assertEquals(id, parsed?.documentId)
        assertEquals("cd".repeat(32), parsed?.bundleSha256)
        // Written before the length fields existed: absent, not invented.
        assertEquals(0L, parsed?.originalLength)
    }

    /**
     * The prefix is not the identity — the namespace URI is. Anyone may use the
     * string "inkstone" as an XML prefix; only this app's files carry the URI.
     */
    @Test
    fun `a lookalike prefix in another namespace is not our marker`() {
        val impostor = """
            <rdf:Description xmlns:inkstone="https://example.com/other/"
              inkstone:documentId="${id.value}" inkstone:schemaVersion="1"/>
        """.trimIndent()
        assertNull(InkstoneXmp.parse(impostor))
    }

    @Test
    fun `a packet with no document id is not a marker`() {
        val partial = """<rdf:Description xmlns:inkstone="${InkstoneMarker.NAMESPACE}" inkstone:schemaVersion="1"/>"""
        assertNull(InkstoneXmp.parse(partial))
        assertNull(InkstoneXmp.parse(null as String?))
    }
}
