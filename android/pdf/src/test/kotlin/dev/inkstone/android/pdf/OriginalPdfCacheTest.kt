package dev.inkstone.android.pdf

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.storage.InkstoneMarker
import dev.inkstone.core.storage.sha256Hex
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The paper under an imported PDF's ink must be the original, never the published file.
 *
 * The bug this guards: the editor rendered `document.pdf`, whose update section is the ink as
 * of the last close, so every close baked the note's ink into the paper (`OriginalPdfCache`'s
 * KDoc). The files here are written by the real [PdfBoxWriter], not built by hand, so the
 * marker scan meets exactly the bytes production publishes.
 */
class OriginalPdfCacheTest {

    private val dir: File = Files.createTempDirectory("original-pdf").toFile()
    private val cache = OriginalPdfCache(File(dir, "cache"))
    private val writer = PdfBoxWriter()
    private val noteId = DocumentId("0192f3c4-3333-7abc-8def-0123456789ab")
    private val bundle = "PK pretend this is note.inkstone".toByteArray(Charsets.US_ASCII)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun publish(original: ByteArray, id: DocumentId = noteId, ink: Boolean = true): ByteArray {
        val marker = InkstoneMarker(id, 1, bundle.sha256Hex(), original.size.toLong(), original.sha256Hex())
        val square = listOf(PdfFixtures.square("a", 72f, 72f, 40f))
        val plans = (0 until PAGE_COUNT).map { index ->
            PdfFixtures.plan(index, if (ink && index == 0) square else emptyList())
        }
        return writer.publish(original, PublishPlan(marker, plans, bundle))
    }

    private fun file(bytes: ByteArray): File = File(dir, "document.pdf").apply { writeBytes(bytes) }

    @Test
    fun `a published import is drawn from its original bytes and nothing after them`() {
        val original = PdfFixtures.sourcePdf()
        val published = file(publish(original))

        val paper = cache.paperFor(noteId, published)

        assertNotEquals(published, paper)
        assertContentEquals(original, paper.readBytes())
    }

    @Test
    fun `the marker found is the current one`() {
        val original = PdfFixtures.sourcePdf()
        val marker = OriginalPdfCache.findMarker(file(publish(original)))

        assertEquals(noteId, marker?.documentId)
        assertEquals(original.size.toLong(), marker?.originalLength)
    }

    @Test
    fun `a second close rebases on the same original and the cached copy is reused`() {
        val original = PdfFixtures.sourcePdf()
        val first = cache.paperFor(noteId, file(publish(original)))
        val modified = first.lastModified()

        val second = cache.paperFor(noteId, file(publish(original, ink = false)))

        assertEquals(first, second)
        assertEquals(modified, second.lastModified())
        assertContentEquals(original, second.readBytes())
    }

    @Test
    fun `a file with no marker is its own paper`() {
        val original = file(PdfFixtures.sourcePdf())

        assertEquals(original, cache.paperFor(noteId, original))
    }

    @Test
    fun `another document's marker is not ours to cut back to`() {
        val stranger = DocumentId("0192f3c4-4444-7abc-8def-0123456789ab")
        val published = file(publish(PdfFixtures.sourcePdf(), id = stranger))

        assertEquals(published, cache.paperFor(noteId, published))
    }

    @Test
    fun `nothing half-written is left in the cache`() {
        cache.paperFor(noteId, file(publish(PdfFixtures.sourcePdf())))

        val left = File(dir, "cache/source-pdf").listFiles().orEmpty().map { it.name }
        assertEquals(1, left.size)
        assertTrue(left.single().endsWith(".pdf"))
    }

    private companion object {
        const val PAGE_COUNT = 4
    }
}
