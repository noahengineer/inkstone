package dev.inkstone.android.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import dev.inkstone.core.model.AnnotationDensity
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.ListStyle
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.PageAnnotationMode
import dev.inkstone.core.model.TextAlign
import dev.inkstone.core.model.TextFont
import dev.inkstone.core.storage.InkstoneMarker
import dev.inkstone.core.storage.sha256Hex
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⭐ ROADMAP M8c acceptance 1 — *"a text box is published as **selectable, searchable PDF text**,
 * not as an image."*
 *
 * Two things have to be true for that and **they fail independently** (docs/M8-TEXT.md §3.5):
 *
 * | | Needs | Failure looks like |
 * |---|---|---|
 * | it renders | the font resolvable through the **page's** `/Resources` | undefined name — blank, or a substituted face |
 * | it extracts | a correct `/ToUnicode` CMap | perfect on screen, nothing in `pdfgrep` |
 *
 * M7 shipped the second without the first for a whole milestone and no test noticed, because
 * `pdftotext` reported the words either way. So the assertions below come from both directions:
 * [PdfTextExtract] reads the file the way an extractor does, and [ContentScan] resolves every name
 * the way a renderer does.
 *
 * ⚠️ **Every text assertion in this module ran on fixture page 0 until M8c**, which is plain US
 * Letter — no rotation, origin at zero — and that is exactly the page on which an identity text
 * matrix is *correct*. `PdfFixtures.sourcePdf` has built a rotated page, a non-zero-origin CropBox
 * page and both-at-once since M4; nothing was asserting against them. That absence is what let
 * `placeWord`'s `1 0 0 1 x y Tm` survive from M4 to M8c.
 */
class PdfTextBoxTest {

    private val bundle = "PK pretend this is note.inkstone".toByteArray(Charsets.US_ASCII)

    /** The production shape: a face is supplied, so text boxes get a subset-embedded CID font. */
    private val writer = PdfBoxWriter(TestFontFaces)

    /** What `:app` looks like before it wires `AssetPdfFontFaces` — the documented degrade. */
    private val bareWriter = PdfBoxWriter()

    // -----------------------------------------------------------------------
    // The embedded CID font
    // -----------------------------------------------------------------------

    @Test
    fun `a text box is shown as a hex string and never as a literal`() {
        val published = publish(writer, PdfFixtures.PLAIN_PAGE, PdfFixtures.textBox("t1", MIXED))

        val content = pageContent(published, PdfFixtures.PLAIN_PAGE)
        assertTrue(Regex("""<[0-9A-Fa-f]+>\s*Tj""") in content, "no hex show-text operator was written")
        assertTrue(
            "(Hello" !in content,
            "the words went into a literal string; a CID byte of 0x28 would then end it early",
        )
    }

    /**
     * ⭐ The acceptance criterion, read back by something that is not the writer.
     *
     * If `/ToUnicode` were missing, reversed, or built from the original face's glyph ids rather
     * than the subset's — the mistake `CidFontSpikeTest` pins the direction of — the file would
     * still render perfectly and this string would come back as replacement characters or as the
     * wrong alphabet.
     */
    @Test
    fun `the extracted text is the text that was typed including characters above U-00FF`() {
        val published = publish(writer, PdfFixtures.PLAIN_PAGE, PdfFixtures.textBox("t1", MIXED))

        PDDocument.load(published).use { document ->
            assertEquals(MIXED, PdfTextExtract.text(document, PdfFixtures.PLAIN_PAGE))
        }
    }

    @Test
    fun `the font is a subset embedded Type0 font with an Identity-H encoding`() {
        val published = publish(writer, PdfFixtures.PLAIN_PAGE, PdfFixtures.textBox("t1", MIXED))

        PDDocument.load(published).use { document ->
            val font = fontOf(document, PdfFixtures.PLAIN_PAGE, TEXT_BOX_FONT)
            assertEquals(COSName.FONT, font.getDictionaryObject(COSName.TYPE))
            assertEquals("Type0", font.getNameAsString(COSName.SUBTYPE))
            assertEquals("Identity-H", font.getNameAsString(COSName.getPDFName("Encoding")))
            assertTrue(
                font.getDictionaryObject(COSName.getPDFName("ToUnicode")) is COSStream,
                "no /ToUnicode: the file would render perfectly and extract as nothing",
            )
            // A subset tag is required on an embedded subset (PDF 32000-1 §9.6.4), and its absence
            // makes a reader treat the face as complete — so a missing glyph becomes a wrong glyph.
            assertTrue(
                Regex("""^[A-Z]{6}\+""") in font.getNameAsString(COSName.getPDFName("BaseFont")).orEmpty(),
                "the /BaseFont carries no six-letter subset tag",
            )
        }
    }

    /**
     * ⚠️ **The font file is a stream, and a stream that was not marked is simply absent** from an
     * incremental update — with no error anywhere (see [PdfBoxWriter]).
     *
     * The dictionary above would survive that, because a plain `COSDictionary` is written inline
     * inside its parent. Only the *streams* are indirect objects, and they are the ones that carry
     * the glyphs and the CMap. So this reads their bytes rather than their presence.
     */
    @Test
    fun `the font file and the CMap survive the incremental update as real streams`() {
        val published = publish(writer, PdfFixtures.PLAIN_PAGE, PdfFixtures.textBox("t1", MIXED))

        PDDocument.load(published).use { document ->
            val font = fontOf(document, PdfFixtures.PLAIN_PAGE, TEXT_BOX_FONT)
            val descendant = assertNotNull(
                (font.getDictionaryObject(COSName.getPDFName("DescendantFonts")) as? COSArray)
                    ?.getObject(0) as? COSDictionary,
                "no /DescendantFonts",
            )
            assertEquals("CIDFontType2", descendant.getNameAsString(COSName.SUBTYPE))
            assertEquals("Identity", descendant.getNameAsString(COSName.getPDFName("CIDToGIDMap")))

            val descriptor = assertNotNull(
                descendant.getDictionaryObject(COSName.getPDFName("FontDescriptor")) as? COSDictionary,
                "no /FontDescriptor",
            )
            val file = assertNotNull(
                descriptor.getDictionaryObject(COSName.getPDFName("FontFile2")) as? COSStream,
                "no /FontFile2 — the glyphs are not in the file",
            )
            val bytes = file.createInputStream().use { it.readBytes() }
            assertTrue(bytes.isNotEmpty(), "the embedded face is empty")
            assertEquals(
                bytes.size.toLong(),
                file.getLong(COSName.LENGTH1),
                "/Length1 must be the unfiltered face length; a strict reader refuses the font without it",
            )

            val cmap = assertNotNull(
                font.getDictionaryObject(COSName.getPDFName("ToUnicode")) as? COSStream,
                "no /ToUnicode stream",
            )
            assertTrue(
                "beginbfchar" in cmap.createInputStream().use { it.readBytes() }.decodeToString(),
                "the /ToUnicode stream carries no character mappings",
            )
        }
    }

    // -----------------------------------------------------------------------
    // Where a viewer looks for the font
    // -----------------------------------------------------------------------

    /**
     * The M4 defect, asked of M8c's font: **a page content stream has no `/Resources` of its own.**
     *
     * Asserted from the reader's side — resolve every name the page's whole content uses, source
     * streams included, through the dictionary a viewer would walk.
     */
    @Test
    fun `every name a page with a text box uses is defined in the resources the page can see`() {
        val published = publish(
            writer,
            PdfFixtures.PLAIN_PAGE,
            PdfFixtures.textBox("t1", MIXED),
            PdfFixtures.textBox("t2", "second", topPt = 300f, format = ObjectFormat(font = MONO_FAMILY)),
        )

        PDDocument.load(published).use { document ->
            val page = document.getPage(PdfFixtures.PLAIN_PAGE)
            val resources = ContentScan.effectiveResources(page)
            val used = ContentScan.namesUsed(page.contents.readBytes().toString(Charsets.ISO_8859_1))
            for ((category, names) in used) {
                val missing = names - ContentScan.namesDefined(resources, category)
                assertTrue(missing.isEmpty(), "the page uses ${category.name} $missing which its /Resources lack")
            }
            // Two families, two subsets: one page can carry more than one font.
            assertTrue(
                ContentScan.namesDefined(resources, COSName.FONT).containsAll(setOf("InkstoneT0", "InkstoneT1")),
                "a page with two font families should define two fonts",
            )
        }
    }

    // -----------------------------------------------------------------------
    // ⚠️ The three fixture pages nothing was asserting against
    // -----------------------------------------------------------------------

    /**
     * ⭐ The same text box on the rotated page, the offset-CropBox page and both at once.
     *
     * Each is a way `1 0 0 1 x y Tm` was wrong: a rotation it did not express, an origin it did
     * not add, and the combination that actually turns up on a lecture scan. The assertions are
     * about *placement*, because extraction would pass on all three even with the M4 matrix.
     */
    @Test
    fun `a text box lands inside the page box on rotated and cropped pages`() {
        for (index in listOf(
            PdfFixtures.ROTATED_PAGE,
            PdfFixtures.CROPPED_PAGE,
            PdfFixtures.ROTATED_AND_CROPPED_PAGE,
        )) {
            val published = publish(writer, index, PdfFixtures.textBox("t1", "Hello", leftPt = 40f, topPt = 60f))

            PDDocument.load(published).use { document ->
                val page = document.getPage(index)
                val box = page.cropBox
                val matrices = textMatrices(page.contents.readBytes().toString(Charsets.ISO_8859_1))
                assertTrue(matrices.isNotEmpty(), "page $index drew no text at all")
                for (matrix in matrices) {
                    val x = matrix[TRANSLATE_X]
                    val y = matrix[TRANSLATE_Y]
                    assertTrue(
                        x >= box.lowerLeftX - SLACK_PT && x <= box.upperRightX + SLACK_PT &&
                            y >= box.lowerLeftY - SLACK_PT && y <= box.upperRightY + SLACK_PT,
                        "page $index places a line at ($x, $y), outside its CropBox $box",
                    )
                }
                assertEquals("Hello", PdfTextExtract.text(document, index), "page $index lost its text")
            }
        }
    }

    /**
     * ⚠️ A rotated page's text matrix must **not** be the identity rotation.
     *
     * This is the assertion that would have failed for four milestones. Without it, "the text is
     * on the page and extracts correctly" is true of a file in which every word lies across the
     * handwriting at ninety degrees.
     */
    @Test
    fun `a rotated page carries a rotated text matrix`() {
        val published = publish(writer, PdfFixtures.ROTATED_PAGE, PdfFixtures.textBox("t1", "Hello"))

        PDDocument.load(published).use { document ->
            val matrix = textMatrices(
                document.getPage(PdfFixtures.ROTATED_PAGE).contents.readBytes().toString(Charsets.ISO_8859_1),
            ).first()
            assertTrue(
                matrix[0] != 1f || matrix[1] != 0f || matrix[2] != 0f || matrix[3] != 1f,
                "the text matrix is the identity rotation on a /Rotate 90 page: ${matrix.toList()}",
            )
        }
    }

    /** A rotated *text box* — the object's own transform, which is a second thing the Tm folds in. */
    @Test
    fun `a rotated text box carries its own rotation into the text matrix`() {
        val turned = PdfFixtures.textBox(
            "t1",
            "Hello",
            transform = Matrix3.translate(120f, 200f) * Matrix3.rotate(QUARTER_RADIANS),
        )
        val published = publish(writer, PdfFixtures.PLAIN_PAGE, turned)

        PDDocument.load(published).use { document ->
            val matrix = textMatrices(
                document.getPage(PdfFixtures.PLAIN_PAGE).contents.readBytes().toString(Charsets.ISO_8859_1),
            ).first()
            assertTrue(matrix[0] != 1f || matrix[1] != 0f, "a 90 degree text box produced an upright matrix")
            assertEquals("Hello", PdfTextExtract.text(document, PdfFixtures.PLAIN_PAGE))
        }
    }

    // -----------------------------------------------------------------------
    // The degrade when no face is supplied
    // -----------------------------------------------------------------------

    /**
     * ⚠️ `PdfFontFaces.None` publishes a text box in a standard-14 base font — **not nothing**.
     *
     * A forgotten injection in `:app` must not produce a valid, parseable PDF with the user's
     * typing missing. What it costs is coverage: the accented word is dropped per character, the
     * same per-word degrade `PdfContent.invisibleText` takes, and never transliterated — a
     * lookalike would put the wrong word into a search index.
     */
    @Test
    fun `with no embeddable face the text falls back to a base font and stays extractable`() {
        val published = publish(bareWriter, PdfFixtures.PLAIN_PAGE, PdfFixtures.textBox("t1", MIXED))

        PDDocument.load(published).use { document ->
            val font = fontOf(document, PdfFixtures.PLAIN_PAGE, TEXT_BOX_FONT)
            assertEquals("Type1", font.getNameAsString(COSName.SUBTYPE))
            assertEquals("Helvetica", font.getNameAsString(COSName.getPDFName("BaseFont")))

            val extracted = PdfTextExtract.text(document, PdfFixtures.PLAIN_PAGE)
            assertTrue("Hello" in extracted, "the ASCII words must survive the degrade")
            assertTrue("é" !in extracted, "an out-of-coverage character must be dropped and not guessed")
            assertTrue("e" !in extracted.substringAfter("caf"), "it must not be transliterated to a lookalike")
        }
    }

    // -----------------------------------------------------------------------
    // Formatting — the SetFormat register, end to end
    // -----------------------------------------------------------------------

    @Test
    fun `a numbered list reaches the published text with its markers`() {
        val listed = PdfFixtures.textBox(
            "t1",
            "first\nsecond",
            format = ObjectFormat(listStyle = ListStyle.NUMBERED),
        )
        val published = publish(bareWriter, PdfFixtures.PLAIN_PAGE, listed)

        PDDocument.load(published).use { document ->
            assertEquals(listOf("1. first", "2. second"), PdfTextExtract.shown(document, PdfFixtures.PLAIN_PAGE))
        }
    }

    @Test
    fun `alignment moves the line and does not change the words`() {
        val text = "short"
        val start = firstLineX(publish(bareWriter, PdfFixtures.PLAIN_PAGE, PdfFixtures.textBox("t1", text)))
        val centred = firstLineX(
            publish(
                bareWriter,
                PdfFixtures.PLAIN_PAGE,
                PdfFixtures.textBox("t1", text, format = ObjectFormat(align = TextAlign.CENTER)),
            ),
        )
        val ended = firstLineX(
            publish(
                bareWriter,
                PdfFixtures.PLAIN_PAGE,
                PdfFixtures.textBox("t1", text, format = ObjectFormat(align = TextAlign.END)),
            ),
        )

        assertTrue(start < centred, "centring did not move the line right: $start then $centred")
        assertTrue(centred < ended, "end alignment did not move the line further right: $centred then $ended")
    }

    /**
     * Wrapping is the writer's, not the register's — SPEC §4.9.2 keeps the words and the
     * presentation in separate registers, so a line break is computed here from the font's own
     * advances (see [TextBoxLayout]).
     */
    @Test
    fun `a paragraph wider than its box wraps onto more than one line`() {
        val long = "one two three four five six seven eight nine ten eleven twelve"
        val published = publish(
            bareWriter,
            PdfFixtures.PLAIN_PAGE,
            PdfFixtures.textBox("t1", long, widthPt = 90f),
        )

        PDDocument.load(published).use { document ->
            val lines = PdfTextExtract.shown(document, PdfFixtures.PLAIN_PAGE)
            assertTrue(lines.size > 1, "a 90pt box held a 62-character line without wrapping")
            assertEquals(long, lines.joinToString(" "), "wrapping changed the words")
        }
    }

    // -----------------------------------------------------------------------
    // ⭐ SPEC §6.4's density rule, kept honest
    // -----------------------------------------------------------------------

    /**
     * ⭐ **Text boxes and images are page content, so they add nothing to `/Annots`.**
     *
     * That is the decision (see [PdfTextBox]), and this is what makes it checkable rather than
     * merely stated: the number SPEC §6.4's threshold was applied to has to *be* the number of
     * annotations the page ends up with. The day a text box becomes a `/FreeText`, a page could
     * carry six hundred annotations while the rule believed it carried three.
     */
    @Test
    fun `objects on a page do not enter the annotation count the density rule uses`() {
        val outlines = List(THREE) { PdfFixtures.square("s-$it", 20f + it * 30f, 40f, 12f) }
        val plan = PdfFixtures.plan(
            PdfFixtures.PLAIN_PAGE,
            outlines,
            objects = listOf(
                PdfFixtures.textBox("t1", "a caption"),
                PdfFixtures.textBox("t2", "another", topPt = 240f),
                PdfFixtures.image("i1", PdfFixtures.FAKE_IMAGE_BYTES),
            ),
        )
        assertEquals(THREE, plan.annotatedObjects)
        assertEquals(PageAnnotationMode.PER_STROKE, PdfPagePlan.modeFor(outlines))

        val original = PdfFixtures.sourcePdf()
        val published = PdfBoxWriter(TestFontFaces, FakeImageDecoder())
            .publish(original, publishPlan(original, plan))
        PDDocument.load(published).use { document ->
            val annots = document.getPage(PdfFixtures.PLAIN_PAGE)
                .cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray
            assertEquals(
                plan.annotatedObjects,
                annots?.size() ?: 0,
                "the density rule counted a different number than the writer produced",
            )
        }
    }

    @Test
    fun `the density threshold still turns on the stroke count alone`() {
        val cap = AnnotationDensity.MAX_PER_STROKE_ANNOTATIONS
        val below = List(cap) { PdfFixtures.square("s-$it", 10f, 20f, 6f) }
        assertEquals(PageAnnotationMode.PER_STROKE, PdfPagePlan.modeFor(below))
        assertEquals(PageAnnotationMode.FLATTENED, PdfPagePlan.modeFor(below + PdfFixtures.square("s-x", 10f, 20f, 6f)))
        // A stroke the eraser took entirely is not written, so it must not count either.
        val erased = List(cap + 1) { PdfFixtures.square("e-$it", 10f, 20f, 6f).copy(contours = emptyList()) }
        assertEquals(PageAnnotationMode.PER_STROKE, PdfPagePlan.modeFor(erased))
    }

    // -----------------------------------------------------------------------
    // The external gate
    // -----------------------------------------------------------------------

    /**
     * ⭐ **Write the file out and say where it is**, every run.
     *
     * *"Verifying a PDF by re-parsing it with the library that wrote it proves almost nothing"*
     * (docs/M4-PDF.md), and [PdfTextExtract] is only most of the way out of that trap — it is
     * still this repository's code. The bytes below are for `pdftotext` (Xpdf, which is poppler's
     * extractor and therefore `pdfgrep`'s) and for PyMuPDF, which **renders**. The two answer
     * different questions and both are needed: M7's words extracted correctly out of a file whose
     * font name was undefined.
     */
    @Test
    fun `dump a published text box for pdftotext and PyMuPDF`() {
        val original = PdfFixtures.sourcePdf()
        val plans = (0 until FIXTURE_PAGES).map { index ->
            PdfFixtures.plan(
                index,
                listOf(PdfFixtures.square("ink-$index", 30f, 40f, 25f)),
                objects = listOf(
                    PdfFixtures.textBox("t1-$index", MIXED),
                    PdfFixtures.textBox(
                        "t2-$index",
                        "bullets\nand more",
                        topPt = 260f,
                        format = ObjectFormat(listStyle = ListStyle.BULLET),
                    ),
                    PdfFixtures.image("i1-$index", PdfFixtures.FAKE_IMAGE_BYTES, topPt = 420f),
                ),
            )
        }
        // ⚠️ **Every fixture page, not just page 0.** The rotated and offset-CropBox pages
        // are the ones an identity text matrix passes every automated check on, so the artefact a
        // human opens has to contain them or the manual gate inherits the same blind spot.
        val published = PdfBoxWriter(TestFontFaces, FakeImageDecoder())
            .publish(original, publishPlan(original, plans))

        val out = System.getenv("INKSTONE_PDF_DUMP")
            ?.let { File(it, "inkstone-text-box.pdf") }
            ?: File.createTempFile("inkstone-text-box", ".pdf")
        out.writeBytes(published)
        println("WROTE ${out.absolutePath}")
    }

    // -----------------------------------------------------------------------

    private fun publish(writer: PdfBoxWriter, pageIndex: Int, vararg objects: PdfPageObject): ByteArray {
        val original = PdfFixtures.sourcePdf()
        val plan = PdfFixtures.plan(pageIndex, emptyList(), objects = objects.toList())
        return writer.publish(original, publishPlan(original, plan))
    }

    /**
     * Every fixture page, so the page tree is not rewritten and the indices stay themselves.
     *
     * ⚠️ [original] is passed in rather than regenerated: `PDDocument.save` mints a fresh
     * document `/ID` per call, so building the fixture twice yields two different byte arrays and
     * the marker's `originalLength` would then describe a file nobody published.
     */
    private fun publishPlan(original: ByteArray, plan: PdfPagePlan): PublishPlan {
        val target = (plan.source as PageSource.Existing).index
        return publishPlan(
            original,
            (0 until FIXTURE_PAGES).map { if (it == target) plan else PdfFixtures.plan(it, emptyList()) },
        )
    }

    private fun publishPlan(original: ByteArray, plans: List<PdfPagePlan>): PublishPlan = PublishPlan(
        marker = InkstoneMarker(
            documentId = DocumentId("0192f3c4-8c8c-7abc-8def-0123456789ab"),
            schemaVersion = 1,
            bundleSha256 = bundle.sha256Hex(),
            originalLength = original.size.toLong(),
            originalSha256 = original.sha256Hex(),
        ),
        pages = plans,
        bundle = bundle,
    )

    private fun pageContent(published: ByteArray, index: Int): String = PDDocument.load(published).use { document ->
        document.getPage(index).contents.readBytes().toString(Charsets.ISO_8859_1)
    }

    private fun fontOf(document: PDDocument, pageIndex: Int, name: String): COSDictionary {
        val resources = assertNotNull(
            ContentScan.effectiveResources(document.getPage(pageIndex)),
            "the page has no /Resources a viewer could reach",
        )
        val fonts = assertNotNull(
            resources.getDictionaryObject(COSName.FONT) as? COSDictionary,
            "the page defines no fonts at all",
        )
        return assertNotNull(
            fonts.getDictionaryObject(COSName.getPDFName(name)) as? COSDictionary,
            "/$name is not defined in the page's /Resources /Font",
        )
    }

    private fun textMatrices(content: String): List<FloatArray> =
        Regex("""([-\d.]+) ([-\d.]+) ([-\d.]+) ([-\d.]+) ([-\d.]+) ([-\d.]+) Tm""")
            .findAll(content)
            .map { match -> FloatArray(MATRIX_VALUES) { match.groupValues[it + 1].toFloat() } }
            .toList()

    private fun firstLineX(published: ByteArray): Float =
        textMatrices(pageContent(published, PdfFixtures.PLAIN_PAGE)).first()[TRANSLATE_X]

    private companion object {
        const val FIXTURE_PAGES = 4
        const val TEXT_BOX_FONT = "InkstoneT0"
        const val MATRIX_VALUES = 6
        const val TRANSLATE_X = 4
        const val TRANSLATE_Y = 5
        const val SLACK_PT = 1f
        const val THREE = 3
        const val QUARTER_RADIANS = 1.5707964f

        val MONO_FAMILY = TextFont.MONO

        /**
         * ASCII, Latin-1, Cyrillic and Greek in one string — every one of them proved present in
         * `TestSubset-Regular.ttf` by `CidFontSpikeTest`, and every one of them a character the
         * pre-M8c writer turned into a *different* byte without saying so.
         */
        const val MIXED = "Hello café жα"
    }
}
