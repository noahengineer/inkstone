package dev.inkstone.android.pdf

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.storage.InkstoneMarker
import dev.inkstone.core.storage.sha256Hex
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⭐ SPEC §5.5 — **the invisible searchable text layer, the thing M7 exists to deliver.**
 *
 * ROADMAP M7's hard acceptance criterion is *"`pdfgrep` finds handwritten words in an exported
 * PDF"*. `pdfgrep` is poppler's text extractor plus a regex, so what it needs is that a reader
 * which is **not PdfBox** can pull the words out — and the structural preconditions for that
 * are what this file asserts. `docs/M7-RECOGNITION.md` records the external run: the same
 * bytes through Xpdf's `pdftotext` and through `pypdf`, neither of which shares a line of code
 * with the writer.
 *
 * Every assertion below is a way the layer can be *present, parseable and unextractable*:
 *
 * | Missing | What a reader does |
 * |---|---|
 * | `3 Tr` | the text is drawn **visibly**, over the user's handwriting |
 * | `BT` / `ET` | not a text object; the operators are errors |
 * | the font in the **page's** `/Resources` | `/InkstoneF0` is an undefined name — a lenient reader may skip the show-text operator, a strict one abandons the stream |
 * | `Tm` per word | every word stacks at the origin and the boxes mean nothing |
 *
 * ⚠️ **The `/Resources` row is not hypothetical.** M4 put the font on the content *stream*,
 * which a page content stream does not have (only a form XObject carries its own), so
 * `/InkstoneF0 Tf` was undefined in every file this app published — and `pdfgrep` run against
 * the app's own output still found the words, because Xpdf resolves the glyph widths it needs
 * without the font being defined. The bug was invisible from *both* sides. Fixed in M6, and
 * asserted here so it stays fixed.
 */
class InvisibleTextLayerTest {

    private companion object {
        /** `PdfFixtures.sourcePdf`'s first page is plain US Letter. */
        const val LETTER_HEIGHT_PT = 792f

        /**
         * `PdfBoxWriter.TEXT_FONT_NAME`, repeated rather than referenced — the writer's
         * companion is private, and prising it open so a test can read one string would widen
         * production API for the test's convenience. If the two ever disagree, the assertion
         * that the page defines this name is what fails, which is the right failure.
         */
        const val TEXT_FONT_NAME = "InkstoneF0"
    }

    private val writer = PdfBoxWriter()
    private val bundle = "PK pretend this is note.inkstone".toByteArray(Charsets.US_ASCII)

    @Test
    fun `recognised words are written as invisible text on the page`() {
        val published = publishWithWords()

        val stream = pageContent(published, 0)
        assertTrue("BT" in stream, "no text object was opened")
        assertTrue("3 Tr" in stream, "text rendering mode 3 is what makes the layer invisible")
        assertTrue("ET" in stream)
        assertTrue("(handwriting) Tj" in stream, "the recognised word is not in the content stream")
        assertTrue("(searchable) Tj" in stream)
    }

    @Test
    fun `the font the text layer names is defined on the page and not on the stream`() {
        // The M4 defect, asserted from the reader's side: resolve `/InkstoneF0` the way a
        // viewer does — through the page dictionary — rather than by looking where we put it.
        PDDocument.load(publishWithWords()).use { document ->
            val page = document.getPage(0)
            val fonts = assertNotNull(
                page.cosObject.getDictionaryObject(COSName.RESOURCES) as? COSDictionary,
                "the page has no /Resources at all",
            ).getDictionaryObject(COSName.FONT) as? COSDictionary

            val font = assertNotNull(
                fonts?.getDictionaryObject(COSName.getPDFName(TEXT_FONT_NAME)) as? COSDictionary,
                "/${TEXT_FONT_NAME} is not defined in the page's /Resources /Font",
            )
            assertEquals(COSName.FONT, font.getDictionaryObject(COSName.TYPE))
            assertEquals("Helvetica", font.getNameAsString(COSName.getPDFName("BaseFont")))
        }
    }

    @Test
    fun `each word is positioned by its own text matrix`() {
        val stream = pageContent(publishWithWords(), 0)

        // Two words, two `Tm`s. One would mean the second word is drawn wherever the first
        // one's advance happened to leave the cursor, which is exactly the drift SPEC §5.5
        // says OCR suffers from and this design does not have to.
        assertEquals(2, Regex("""1 0 0 1 [-\d.]+ [-\d.]+ Tm""").findAll(stream).count())
    }

    @Test
    fun `a page with no recognised text gets no text object`() {
        // A note on a device with no language model, and the state of every page before the
        // recogniser has been over it. It must be an absent layer, not an empty one.
        val stream = pageContent(publish(words = emptyList()), 0)

        assertTrue("3 Tr" !in stream, "an unrecognised page must not carry an empty text object")
    }

    @Test
    fun `the invisible text is in PDF user space with y up`() {
        // Page space is y-down (SPEC §4.4) and PDF user space is y-up. A word near the TOP of
        // the page therefore has a LARGE y here. Getting this backwards puts every word on the
        // wrong half of the page, and — because the layer is invisible — looks perfect.
        val stream = pageContent(publishWithWords(), 0)
        val ys = Regex("""1 0 0 1 [-\d.]+ ([-\d.]+) Tm""").findAll(stream).map { it.groupValues[1].toFloat() }.toList()

        assertEquals(2, ys.size)
        assertTrue(
            ys.all { it > LETTER_HEIGHT_PT / 2f },
            "words written near the top of the page must have a large PDF y, got $ys",
        )
    }

    /**
     * ⚠️ The M8c seam. `ContentBuilder.toByteArray` refuses non-ASCII loudly (correctly), and
     * this layer still writes literal strings — so between M8c and the filter in
     * `PdfContent.invisibleText`, ONE accented recognised word threw at publish time and the
     * whole note became unpublishable (`PUBLISH_FAILED`, with nothing on screen to say why).
     * The degrade is per word: the publish succeeds, ASCII words stay extractable, and the
     * non-ASCII word is absent — not transliterated, not truncated into different letters.
     * In-app search still finds it; the word table never comes through this path.
     */
    @Test
    fun `a non-ASCII recognised word is skipped rather than failing the publish`() {
        val published = publish(
            words = listOf(
                InvisibleWord("cafe", leftPt = 72f, topPt = 90f, rightPt = 210f, bottomPt = 114f),
                InvisibleWord("café", leftPt = 220f, topPt = 90f, rightPt = 340f, bottomPt = 114f),
            ),
        )

        val stream = pageContent(published, 0)
        assertTrue("(cafe) Tj" in stream, "the ASCII word must still be written")
        // The old truncation turned U+00E9 into the 0xE9 byte, which ISO-8859-1 reads back as
        // 'é' — so this assertion distinguishes "skipped" from "silently mangled" too.
        assertTrue("é" !in stream, "the non-ASCII word must be absent, not carried as other bytes")
        assertEquals(
            1,
            Regex("""1 0 0 1 [-\d.]+ [-\d.]+ Tm""").findAll(stream).count(),
            "exactly one word should have been placed",
        )
    }

    /**
     * ROADMAP M7's hard criterion, for a human and for the external readers.
     *
     * Silent unless `INKSTONE_PDF_DUMP` names a directory — the same convention
     * `PdfViewerCompatTest` uses for M4's manual viewer gate.
     */
    @Test
    fun `dump a published note for the pdfgrep gate`() {
        val directory = System.getenv("INKSTONE_PDF_DUMP") ?: return
        val out = File(directory, "inkstone-text-layer.pdf")
        out.writeBytes(publishWithWords())
        println("WROTE ${out.absolutePath}")
    }

    // -----------------------------------------------------------------------

    /** Two words near the top of an A4 page, boxed as the strokes that made them would be. */
    private fun publishWithWords(): ByteArray = publish(
        words = listOf(
            InvisibleWord("handwriting", leftPt = 72f, topPt = 90f, rightPt = 210f, bottomPt = 114f),
            InvisibleWord("searchable", leftPt = 220f, topPt = 90f, rightPt = 340f, bottomPt = 114f),
        ),
    )

    private fun publish(words: List<InvisibleWord>): ByteArray {
        val original = PdfFixtures.sourcePdf()
        return writer.publish(
            original = original,
            plan = PublishPlan(
                marker = InkstoneMarker(
                    documentId = DocumentId("019fe000-0000-7000-8000-0000000000t7"),
                    schemaVersion = 1,
                    bundleSha256 = bundle.sha256Hex(),
                    originalLength = original.size.toLong(),
                    originalSha256 = original.sha256Hex(),
                ),
                pages = listOf(
                    PdfFixtures.plan(
                        sourceIndex = 0,
                        outlines = listOf(PdfFixtures.square("s1", left = 72f, top = 90f, side = 24f)),
                        words = words,
                    ),
                ),
                bundle = bundle,
            ),
        )
    }

    /** Every content stream of one page, concatenated — a page may legally have several. */
    private fun pageContent(published: ByteArray, index: Int): String = PDDocument.load(published).use { document ->
        buildString {
            val streams = document.getPage(index).contentStreams
            while (streams.hasNext()) {
                append(streams.next().createInputStream().use { it.readBytes().toString(Charsets.ISO_8859_1) })
                appendLine()
            }
        }
    }
}
