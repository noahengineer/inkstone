package dev.inkstone.android.pdf

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Document
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageRef
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.RemoteBundleVerdict
import dev.inkstone.core.storage.BundleContents
import dev.inkstone.core.storage.BundleWriter
import dev.inkstone.core.storage.InkstoneMarker
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.storage.PageContents
import dev.inkstone.core.storage.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC §5.6 + §7.6 — the pull side of the three-way open.
 *
 * All four verdicts are reachable from a perfectly healthy Nextcloud, so all four are tested
 * over real published bytes rather than a mock. What the reconciler does with each is
 * `:core:sync`'s business and is tested there; what is tested here is that the verdict is
 * right, that the bundle appears **only** where it was asked for, and that nothing this class
 * touches can end up under `notes/<id>/`.
 */
class PulledPdfExtractorTest {

    private val fs = RecordingFileSystem()
    private val extractor = PulledPdfExtractor(fs, dispatcher = Dispatchers.Unconfined)
    private val writer = PdfBoxWriter()
    private val documentId = DocumentId("0192f3c4-6666-7abc-8def-0123456789ab")

    @Test
    fun `a published note extracts its bundle and reports Normal`() = runTest {
        val bundle = bundleFor(documentId)
        fs.files[PULLED_PDF] = publish(bundle)

        val result = extractor.extract(PULLED_PDF, STAGED_BUNDLE)

        assertEquals(RemoteBundleVerdict.NORMAL, result.verdict)
        assertEquals(documentId, result.documentId)
        assertEquals(1, result.schemaVersion)
        assertTrue(result.bundleWritten)
        assertContentEquals(bundle, fs.files[STAGED_BUNDLE], "the staged bundle is not the one in the file")
    }

    /**
     * ⚠️ Nothing under `notes/<id>/`, and no trash.
     *
     * `PdfGateway` would have installed the bundle into the note directory and, on a
     * Recovered file, moved the local note to trash — from a background worker, for a file
     * that arrived over a network. This is the assertion that keeps the two doors apart.
     */
    @Test
    fun `extraction writes only the staging path`() = runTest {
        fs.files[PULLED_PDF] = publish(bundleFor(documentId))

        extractor.extract(PULLED_PDF, STAGED_BUNDLE)

        assertEquals(listOf(STAGED_BUNDLE), fs.written, "extraction wrote somewhere it was not asked to")
        assertTrue(fs.moved.isEmpty(), "extraction moved a file — trashing is not its job")
        assertTrue(fs.deleted.isEmpty(), "extraction deleted a file")
        assertFalse(
            fs.files.keys.any { it.startsWith(NoteLayout.noteDir(documentId)) },
            "extraction touched the note's own directory",
        )
    }

    /**
     * SPEC §5.6 — a stranger's PDF is not adopted, and carries no schema to report.
     */
    @Test
    fun `a PDF with no marker is Foreign`() = runTest {
        fs.files[PULLED_PDF] = PdfFixtures.sourcePdf()

        val result = extractor.extract(PULLED_PDF, STAGED_BUNDLE)

        assertEquals(RemoteBundleVerdict.FOREIGN, result.verdict)
        assertNull(result.documentId)
        assertEquals(0, result.schemaVersion)
        assertFalse(result.bundleWritten)
        assertTrue(fs.written.isEmpty(), "a foreign file staged a bundle")
    }

    /**
     * SPEC §7.7 layer 3 — the marker is ours and the bundle is gone.
     *
     * **Not recovered here.** Recovery replaces an op log and stamps it past every clock this
     * device knows, so doing it to a pulled file would let a peer's lossy copy win every
     * register against full-fidelity local data. The verdict goes back; the caller makes a
     * conflicted copy.
     */
    @Test
    fun `a file whose bundle was stripped is Recovered and nothing is staged`() = runTest {
        val bundle = bundleFor(documentId)
        // The marker still claims a bundle whose hash it recorded; the entry is not there.
        fs.files[PULLED_PDF] = stripEmbeddedFiles(publish(bundle))

        val result = extractor.extract(PULLED_PDF, STAGED_BUNDLE)

        assertEquals(RemoteBundleVerdict.RECOVERED, result.verdict)
        assertEquals(documentId, result.documentId, "identity survives a strip — that is what the marker is for")
        assertFalse(result.bundleWritten)
        assertTrue(fs.written.isEmpty())
    }

    /**
     * ⚠️ SPEC §5.8 — a bundle from a newer build, and the reason the schema check runs first.
     *
     * `BundleReader.open` does not return a state for a newer schema, it **throws**. Asking
     * whether the bundle is readable before asking whether it is from the future would turn
     * this verdict into an exception out of the middle of a WorkManager job, which the drainer
     * would read as a transport failure and retry with backoff.
     */
    @Test
    fun `a bundle from a newer schema is TooNew and does not throw`() = runTest {
        // The *manifest* says 99 too, so BundleReader.open would throw if it were consulted.
        // That is the whole point of the test: it must not be.
        fs.files[PULLED_PDF] = publish(bundleFor(documentId, FUTURE_SCHEMA), schemaVersion = FUTURE_SCHEMA)

        val result = extractor.extract(PULLED_PDF, STAGED_BUNDLE)

        assertEquals(RemoteBundleVerdict.TOO_NEW, result.verdict)
        assertEquals(FUTURE_SCHEMA, result.schemaVersion, "the attention row needs the version to be actionable")
        assertFalse(result.bundleWritten, "a file this build must not open must not be staged either")
    }

    /**
     * A truncated download is an ordinary outcome of a pull, not an error.
     *
     * The contract forbids throwing: the binding's ETag only advances after a merge, so a file
     * reported Foreign is simply fetched again next pass.
     */
    @Test
    fun `a torn download is Foreign rather than an exception`() = runTest {
        fs.files[PULLED_PDF] = publish(bundleFor(documentId)).copyOfRange(0, TORN_PREFIX_BYTES)

        val result = extractor.extract(PULLED_PDF, STAGED_BUNDLE)

        assertEquals(RemoteBundleVerdict.FOREIGN, result.verdict)
        assertFalse(result.bundleWritten)
    }

    @Test
    fun `a missing file is Foreign rather than an exception`() = runTest {
        val result = extractor.extract(PULLED_PDF, STAGED_BUNDLE)

        assertEquals(RemoteBundleVerdict.FOREIGN, result.verdict)
        assertFalse(result.bundleWritten)
    }

    /**
     * A staging write that fails must report `bundleWritten = false`, not a Normal verdict the
     * caller then merges from a path that holds nothing.
     */
    @Test
    fun `a failed staging write reports that the bundle was not written`() = runTest {
        fs.files[PULLED_PDF] = publish(bundleFor(documentId))
        fs.failWritesTo = STAGED_BUNDLE

        val result = extractor.extract(PULLED_PDF, STAGED_BUNDLE)

        assertEquals(RemoteBundleVerdict.NORMAL, result.verdict)
        assertFalse(result.bundleWritten, "never merge from a path the extractor did not write")
    }

    // -----------------------------------------------------------------------

    /** A real one-page bundle, so `BundleReader.open` is answering about a real archive. */
    private fun bundleFor(id: DocumentId, schemaVersion: Int = 1): ByteArray {
        val pageId = PageId("0192f3c4-7777-7abc-8def-0123456789ab")
        val document = Document(
            id = id,
            schemaVersion = schemaVersion,
            title = "Pulled",
            deviceId = "peer-device",
            createdEpochMillis = 1L,
            modifiedEpochMillis = 2L,
            pages = listOf(PageRef(pageId, FractionalKey.Initial)),
        )
        return BundleWriter.write(
            BundleContents(
                document = document,
                pages = mapOf(pageId to PageContents(PageSize.Letter, Background.Blank, ops = emptyList())),
                highestClockCounter = 7L,
            ),
        )
    }

    /**
     * A published file whose marker claims [schemaVersion].
     *
     * The marker is what a reader consults first (SPEC §5.6), and the only part of a file from
     * a newer build this one is guaranteed to be able to read at all.
     */
    private fun publish(bundle: ByteArray, schemaVersion: Int = 1): ByteArray {
        val original = PdfFixtures.sourcePdf()
        return writer.publish(
            original,
            PublishPlan(
                marker = InkstoneMarker(
                    documentId = documentId,
                    schemaVersion = schemaVersion,
                    bundleSha256 = bundle.sha256Hex(),
                    originalLength = original.size.toLong(),
                    originalSha256 = original.sha256Hex(),
                ),
                pages = (0 until FIXTURE_PAGES).map { PdfFixtures.plan(it, emptyList()) },
                bundle = bundle,
            ),
        )
    }

    /** What a third-party editor does: keep the pages, rebuild everything else. */
    private fun stripEmbeddedFiles(pdf: ByteArray): ByteArray = PDDocument.load(pdf).use { doc ->
        doc.documentCatalog.cosObject.removeItem(COSName.NAMES)
        val out = ByteArrayOutputStream()
        doc.save(out)
        out.toByteArray()
    }

    private companion object {
        const val PULLED_PDF = "download/pulled.pdf"
        const val STAGED_BUNDLE = "download/pulled.bundle"
        const val FIXTURE_PAGES = 4
        const val FUTURE_SCHEMA = 99

        /** Enough of a PDF to look like one and not enough to parse. */
        const val TORN_PREFIX_BYTES = 900
    }
}

/**
 * An in-memory [FileSystem] that records what it was asked to do.
 *
 * Recording rather than simulating, because the claim under test is *"writes nothing under
 * `notes/<id>/` and never trashes anything"* — which is a statement about the calls made, not
 * about the state afterwards. A simulation would let a write to the wrong path pass unnoticed
 * as long as the bytes came back.
 */
internal class RecordingFileSystem : FileSystem {

    val files = mutableMapOf<String, ByteArray>()
    val written = mutableListOf<String>()
    val moved = mutableListOf<Pair<String, String>>()
    val deleted = mutableListOf<String>()

    /** Set to make [writeBytesAtomically] fail, standing in for a full disk. */
    var failWritesTo: String? = null

    override suspend fun exists(path: String): Boolean = path in files

    override suspend fun size(path: String): Long = files[path]?.size?.toLong() ?: -1L

    override suspend fun readBytes(path: String): ByteArray = files[path] ?: throw FileNotFoundException(path)

    override suspend fun readRange(path: String, offset: Long, length: Int): ByteArray {
        val bytes = files[path] ?: return ByteArray(0)
        if (offset >= bytes.size) return ByteArray(0)
        val end = (offset + length).coerceAtMost(bytes.size.toLong()).toInt()
        return bytes.copyOfRange(offset.toInt(), end)
    }

    override suspend fun writeBytesAtomically(path: String, bytes: ByteArray) {
        if (path == failWritesTo) error("no space left on device")
        written += path
        files[path] = bytes
    }

    override suspend fun appendBytes(path: String, bytes: ByteArray) {
        written += path
        files[path] = (files[path] ?: ByteArray(0)) + bytes
    }

    override suspend fun createDirectories(path: String) = Unit

    override suspend fun move(from: String, to: String) {
        moved += from to to
        files.remove(from)?.let { files[to] = it }
    }

    override suspend fun delete(path: String) {
        deleted += path
        files.remove(path)
    }

    override suspend fun list(path: String): List<String> = files.keys
        .filter { it.startsWith("$path/") }
        .map { it.removePrefix("$path/").substringBefore('/') }
        .distinct()
}
