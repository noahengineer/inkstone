package dev.inkstone.android.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.PageAnnotationMode
import dev.inkstone.core.model.StrokeOutline
import dev.inkstone.core.storage.InkstoneMarker
import dev.inkstone.core.storage.sha256Hex
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ⭐ **Can anything other than PdfBox draw what this module publishes?**
 *
 * M4 verified the annotations were readable by *our own* reader and stopped there.
 * `PdfRoundTripSpikeTest` proves the bytes survive Nextcloud; `PdfPublishTest` proves
 * `PdfReader` finds the ink and that it lands in the right place. None of that is evidence
 * that a *viewer* draws anything — and the two the app is actually looked at in are PDF.js
 * (Nextcloud's own viewer) and PDFium (Chrome), both completely independent
 * implementations. This file is that gap closed: the structural preconditions a third-party
 * renderer needs, asserted rather than dumped.
 *
 * Every assertion here is a way a file can be *valid, parseable, and blank*:
 *
 * | Missing | What a viewer does |
 * |---|---|
 * | `/Type /Annot`, `/Subtype /Ink` | not recognised as an annotation at all |
 * | a normalised `/Rect` | zero-area or inverted box; PDF.js maps the appearance into nothing |
 * | `/AP /N` as a **stream** | a sub-dictionary of states needs `/AS` to pick one, and without it nothing is drawn |
 * | `/BBox` covering the content | the appearance is clipped to its BBox; ink outside it is cut off |
 * | a resource the content names | undefined name: ignored by a lenient reader, stream abandoned by a strict one |
 * | `/F` without Hidden or NoView | drawn nowhere, on purpose |
 *
 * ⚠️ **It found one.** See [PdfResources]: the flattened-page overlay and the
 * invisible text layer both carried their `/Resources` on the *stream*, which a page content
 * stream does not have. Every name they used was undefined in every file this writer had ever
 * published.
 *
 * Set `INKSTONE_PDF_DUMP` to a directory to also write the file out, for the manual gate
 * ROADMAP M4 keeps: Acrobat, Chrome and macOS Preview each drawing it correctly.
 */
class PdfViewerCompatTest {

    private val writer = PdfBoxWriter()
    private val bundle = "PK pretend this is note.inkstone".toByteArray(Charsets.US_ASCII)

    // -----------------------------------------------------------------------
    // What one /Ink annotation has to carry
    // -----------------------------------------------------------------------

    @Test
    fun `every published Ink annotation is one a viewer can recognise`() {
        val checked = forEachAnnotation(publishInkOnEveryPage()) { annotation ->
            val name = annotation.getString(COSName.NM)
            assertEquals("Annot", annotation.getCOSName(COSName.TYPE)?.name, "$name has no /Type /Annot")
            assertEquals("Ink", annotation.getCOSName(COSName.SUBTYPE)?.name, "$name is not /Subtype /Ink")
        }
        assertEquals(EXPECTED_ANNOTATIONS, checked, "the fixture stopped producing annotations")
    }

    /**
     * A `/Rect` has to be normalised — `x1 < x2` and `y1 < y2`.
     *
     * PDF readers are told to normalise it, and PDF.js does; what neither does is invent an
     * area for a box with none. `getTransformMatrix` returns a pure translation when the
     * transformed `/BBox` is degenerate, so a hairline stroke with a zero-height `/Rect`
     * lands as a dot at the page origin instead of where it was drawn. That is what
     * `PdfBoxWriter.boundsOf`'s minimum extent exists for, and this is the assertion that
     * keeps it.
     */
    @Test
    fun `every Rect is normalised and has area`() {
        val hairline = PdfFixtures.square("flat", 100f, 100f, 0f)
        val published = publish(PdfFixtures.sourcePdf(), planWith(0, listOf(hairline)))

        forEachAnnotation(published) { annotation ->
            val rect = floatsOf(annotation, COSName.RECT)
            assertEquals(RECT_VALUES, rect.size, "a /Rect is not four numbers")
            assertTrue(rect[0] < rect[2], "/Rect x1 ${rect[0]} is not left of x2 ${rect[2]}")
            assertTrue(rect[1] < rect[3], "/Rect y1 ${rect[1]} is not below y2 ${rect[3]}")
        }
    }

    /**
     * `/AP /N` must be a stream and not a dictionary of appearance states.
     *
     * Both are legal. A dictionary means *"pick the state named by `/AS`"*, and an annotation
     * with a state dictionary and no `/AS` has no appearance a viewer can choose — so it
     * draws nothing, and `annotation.getDictionaryObject(AP)` is still non-null, which is all
     * the M4 test asserted.
     */
    @Test
    fun `every appearance is a stream and not a dictionary of states`() {
        forEachAnnotation(publishInkOnEveryPage()) { annotation ->
            val normal = appearanceOf(annotation)
            assertTrue(
                normal is COSStream,
                "/AP /N is a ${normal::class.simpleName}; a state dictionary would need /AS to be drawable",
            )
        }
    }

    /**
     * The appearance is clipped to its `/BBox`, so anything the content draws outside it is
     * simply not there — silently, and only in viewers, because `PdfReader` reads `/InkList`
     * and never looks at the appearance at all.
     */
    @Test
    fun `every appearance BBox contains every coordinate the appearance draws`() {
        forEachAnnotation(publishInkOnEveryPage()) { annotation ->
            val stream = appearanceOf(annotation) as COSStream
            val box = floatsOf(stream, COSName.BBOX)
            val drawn = ContentScan.pointsDrawn(contentOf(stream))
            assertTrue(drawn.isNotEmpty(), "the appearance stream draws nothing at all")

            for ((x, y) in drawn) {
                assertTrue(
                    x >= box[0] - TOLERANCE_PT && x <= box[2] + TOLERANCE_PT &&
                        y >= box[1] - TOLERANCE_PT && y <= box[3] + TOLERANCE_PT,
                    "the appearance draws at ($x, $y), outside its /BBox ${box.toList()}",
                )
            }
        }
    }

    /** A form XObject resolves names through its own `/Resources`, and this is where they are. */
    @Test
    fun `every name an appearance stream uses is defined in its own Resources`() {
        forEachAnnotation(publishInkOnEveryPage()) { annotation ->
            val stream = appearanceOf(annotation) as COSStream
            val resources = stream.getDictionaryObject(COSName.RESOURCES) as? COSDictionary
            assertUndefinedNames(ContentScan.namesUsed(contentOf(stream)), resources, "an appearance stream")
        }
    }

    /**
     * `/F` bit 2 is Hidden and bit 6 is NoView: either one means *"do not draw this"*, which is
     * indistinguishable from a bug once a user is looking at a blank page. Bit 1, Invisible,
     * is checked too — PDF.js's own viewability test rejects on it, and it is one typo away
     * from Print.
     */
    @Test
    fun `no annotation is flagged Hidden Invisible or NoView`() {
        forEachAnnotation(publishInkOnEveryPage()) { annotation ->
            val flags = (annotation.getDictionaryObject(COSName.F) as COSNumber).intValue()
            assertEquals(0, flags and FLAG_INVISIBLE, "/F $flags sets Invisible")
            assertEquals(0, flags and FLAG_HIDDEN, "/F $flags sets Hidden")
            assertEquals(0, flags and FLAG_NO_VIEW, "/F $flags sets NoView")
            assertEquals(FLAG_PRINT, flags and FLAG_PRINT, "/F $flags does not set Print")
        }
    }

    // -----------------------------------------------------------------------
    // ⚠️ The defect: page content streams have no resources of their own
    // -----------------------------------------------------------------------

    /**
     * ⚠️ **The bug this file was written to find.**
     *
     * Past 500 strokes a page carries no annotations and its ink is in the page's own content
     * stream instead (SPEC §6.4). That stream has no `/Resources` of its own — it resolves
     * `/GS102 gs` through the *page* — and until M6 the writer put them on the stream, where
     * no viewer looks. A lenient reader like PDF.js drops the operator and composites at the
     * default alpha, which turns a translucent highlighter into an opaque yellow block over
     * the writing it was highlighting; a strict one abandons the rest of the stream, which is
     * the overlay, so a dense page of handwriting is blank.
     */
    @Test
    fun `a flattened page defines every name its overlay uses`() {
        val ink = List(FLATTEN_THRESHOLD + 1) { PdfFixtures.square("s-$it", 10f + (it % 40) * 12f, 20f, 6f) }
        val published = publish(
            PdfFixtures.sourcePdf(),
            planWith(0, ink + PdfFixtures.highlight("hl", 40f, 40f, 60f), PageAnnotationMode.FLATTENED),
        )

        assertPageNamesDefined(published, 0)
    }

    /**
     * The same defect in the invisible text layer (SPEC §5.5), which names one font.
     *
     * Dormant today because M4 ships the path with an empty word list, and M7 is what fills
     * it — so this is the assertion that stops M7 discovering it on a device.
     */
    @Test
    fun `the invisible text layer's font is defined on the page`() {
        val published = publish(
            PdfFixtures.sourcePdf(),
            planWith(
                0,
                listOf(PdfFixtures.square("a", 72f, 72f, 40f)),
                words = listOf(InvisibleWord("hello", 72f, 60f, 140f, 80f)),
            ),
        )

        assertPageNamesDefined(published, 0)
        val resources = PDDocument.load(published).use { ContentScan.effectiveResources(it.getPage(0)) }
        assertTrue(
            "InkstoneF0" in ContentScan.namesDefined(resources, COSName.FONT),
            "the text layer's font is not in the page's /Resources",
        )
    }

    /**
     * Adding our names must not cost the source page its own.
     *
     * The other half of the same fix: merging into the page's `/Resources` is only correct if
     * it *merges*. A publish that replaced the dictionary would take the imported PDF's fonts
     * and images with it, and the page would render as our ink over nothing.
     */
    @Test
    fun `flattening keeps a source page's own resources`() {
        val original = PdfFixtures.sourcePdfWithPageResources()
        // A one-page original takes a one-page plan: the plan *is* the published page list.
        val published = publish(original, listOf(PdfFixtures.plan(0, dense(), mode = PageAnnotationMode.FLATTENED)))

        assertPageNamesDefined(published, 0)
        val defined = PDDocument.load(published).use {
            ContentScan.namesDefined(ContentScan.effectiveResources(it.getPage(0)), COSName.EXT_G_STATE)
        }
        assertTrue(PdfFixtures.SOURCE_STATE_NAME in defined, "the source page's own /ExtGState entry was dropped")
    }

    /**
     * ⚠️ `/Resources` is **inheritable**, so a page with none of its own may still have one.
     *
     * Writing a fresh dictionary onto such a page shadows the `/Pages` node's, which is worse
     * than the bug being fixed: the page keeps our one name and loses all of theirs.
     */
    @Test
    fun `flattening does not shadow resources the page inherits`() {
        val original = PdfFixtures.sourcePdfWithInheritedAttributes()
        val plans = listOf(
            PdfFixtures.plan(0, dense(), mode = PageAnnotationMode.FLATTENED),
            PdfFixtures.plan(1, emptyList()),
        )
        val published = publish(original, plans)

        assertPageNamesDefined(published, 0)
        val defined = PDDocument.load(published).use {
            ContentScan.namesDefined(ContentScan.effectiveResources(it.getPage(0)), COSName.EXT_G_STATE)
        }
        assertTrue(PdfFixtures.SOURCE_STATE_NAME in defined, "the inherited /ExtGState entry was shadowed")
    }

    /**
     * ⚠️ Reordering flattens the page tree, and a page can be inheriting more than resources.
     *
     * `/MediaBox`, `/CropBox` and `/Rotate` are inheritable too, and a long source PDF from
     * pdfTeX or Ghostscript keeps them on an intermediate `/Pages` node. Cutting that node out
     * leaves a page with no size, at which point a viewer falls back to US Letter — while the
     * ink was written against the geometry read *before* the reorder. The page count is right,
     * `PDDocument.load` is happy, and the annotations are on paper of the wrong size.
     *
     * The fixture is A4 precisely because Letter is the fallback.
     */
    @Test
    fun `reordering keeps a page size and resources that were inherited`() {
        val original = PdfFixtures.sourcePdfWithInheritedAttributes()
        // Reversed, so reorderIfNeeded rewrites the tree — the only path that re-parents.
        val published = publish(original, listOf(PdfFixtures.plan(1, dense()), PdfFixtures.plan(0, emptyList())))

        PDDocument.load(published).use { doc ->
            assertEquals("page-1", doc.getPage(0).cosObject.getString(COSName.getPDFName("INKS_PageId")))
            for (index in 0 until doc.numberOfPages) {
                val page = doc.getPage(index)
                assertEquals(PdfFixtures.INHERITED_WIDTH_PT, page.mediaBox.width, "page $index lost its inherited size")
                assertEquals(PdfFixtures.INHERITED_HEIGHT_PT, page.mediaBox.height, "page $index lost its size")
                assertTrue(
                    PdfFixtures.SOURCE_STATE_NAME in
                        ContentScan.namesDefined(ContentScan.effectiveResources(page), COSName.EXT_G_STATE),
                    "page $index lost the resources it inherited",
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // The manual gate's other half
    // -----------------------------------------------------------------------

    /**
     * Write the published file out, for opening in a real viewer.
     *
     * Still here, and still a diagnostic: no automated check replaces ROADMAP M4's *"Acrobat,
     * Chrome and macOS Preview each draw it correctly"*. Silent unless `INKSTONE_PDF_DUMP`
     * names a directory.
     */
    @Test
    fun `dump a published note for the manual viewer gate`() {
        val directory = System.getenv("INKSTONE_PDF_DUMP") ?: return
        val out = File(directory, "inkstone-viewer-check.pdf")
        out.writeBytes(publishInkOnEveryPage())
        println("WROTE ${out.absolutePath}")
    }

    // -----------------------------------------------------------------------

    /**
     * Ink on all four fixture pages — plain, rotated, cropped, and both — with a highlighter
     * over each, because the translucent case is the one whose `/ExtGState` name is not
     * `GS255` and so the only one that catches a resource dictionary built from the wrong
     * outlines.
     */
    private fun publishInkOnEveryPage(): ByteArray {
        val original = PdfFixtures.sourcePdf()
        val plans = (0 until FIXTURE_PAGES).map { index ->
            PdfFixtures.plan(
                index,
                listOf(
                    PdfFixtures.square("ink-$index", 30f, 40f, 25f),
                    PdfFixtures.highlight("hl-$index", 28f, 38f, 29f),
                ),
            )
        }
        return publish(original, plans)
    }

    /** Enough strokes to trip SPEC §6.4's density rule, so FLATTENED is not a fiction. */
    private fun dense() = List(FLATTEN_THRESHOLD + 1) { PdfFixtures.square("d-$it", 10f + (it % 40) * 12f, 20f, 6f) }

    private fun planWith(
        pageIndex: Int,
        outlines: List<StrokeOutline>,
        mode: PageAnnotationMode = PageAnnotationMode.PER_STROKE,
        words: List<InvisibleWord> = emptyList(),
    ) = (0 until FIXTURE_PAGES).map { index ->
        if (index == pageIndex) {
            PdfFixtures.plan(index, outlines, mode = mode, words = words)
        } else {
            PdfFixtures.plan(index, emptyList())
        }
    }

    private fun publish(original: ByteArray, plans: List<PdfPagePlan>): ByteArray = writer.publish(
        original,
        PublishPlan(
            marker = InkstoneMarker(
                documentId = DocumentId("0192f3c4-5555-7abc-8def-0123456789ab"),
                schemaVersion = 1,
                bundleSha256 = bundle.sha256Hex(),
                originalLength = original.size.toLong(),
                originalSha256 = original.sha256Hex(),
            ),
            pages = plans,
            bundle = bundle,
        ),
    )

    /** Every name the page's whole content — the source's streams and ours — has to resolve. */
    private fun assertPageNamesDefined(published: ByteArray, pageIndex: Int) {
        PDDocument.load(published).use { doc ->
            val page: PDPage = doc.getPage(pageIndex)
            val content = page.contents.readBytes().decodeToString()
            assertUndefinedNames(
                ContentScan.namesUsed(content),
                ContentScan.effectiveResources(page),
                "page $pageIndex",
            )
        }
    }

    private fun assertUndefinedNames(used: Map<COSName, Set<String>>, resources: COSDictionary?, where: String) {
        for ((category, names) in used) {
            val defined = ContentScan.namesDefined(resources, category)
            val missing = names - defined
            assertTrue(missing.isEmpty(), "$where uses ${category.name} $missing, which its /Resources do not define")
        }
    }

    /**
     * Run [check] over every annotation on every page, **inside the document's lifetime**.
     *
     * Not a `List<COSDictionary>` returned from a closed `PDDocument`: PdfBox streams read
     * lazily from the source, so an appearance stream fetched after the document closed throws
     * *"COSStream has been closed"*. Collecting the dictionaries and asserting afterwards
     * compiles, looks right, and fails on the one assertion that has to read a stream.
     *
     * @return how many annotations were checked, so a fixture that quietly stopped producing
     *   them cannot make every test in this file vacuous.
     */
    private fun forEachAnnotation(pdf: ByteArray, check: (COSDictionary) -> Unit): Int =
        PDDocument.load(pdf).use { doc ->
            var checked = 0
            for (index in 0 until doc.numberOfPages) {
                val annots = doc.getPage(index).cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
                for (position in 0 until annots.size()) {
                    check(annots.getObject(position) as? COSDictionary ?: continue)
                    checked++
                }
            }
            assertTrue(checked > 0, "no annotations to check — the fixture is not exercising anything")
            checked
        }

    private fun appearanceOf(annotation: COSDictionary) = assertNotNull(
        (annotation.getDictionaryObject(COSName.AP) as? COSDictionary)?.getDictionaryObject(COSName.N),
        "no /AP /N — most viewers draw nothing at all without one",
    )

    private fun contentOf(stream: COSStream): String = stream.createInputStream().use {
        it.readBytes()
    }.decodeToString()

    private fun floatsOf(dictionary: COSDictionary, key: COSName): FloatArray {
        val array = assertNotNull(dictionary.getDictionaryObject(key) as? COSArray, "no ${key.name}")
        return FloatArray(array.size()) { (array.getObject(it) as COSNumber).floatValue() }
    }

    private companion object {
        const val FIXTURE_PAGES = 4

        /** Two strokes on each of the four pages. */
        const val EXPECTED_ANNOTATIONS = 8

        /** SPEC §6.4's threshold, past which a page is flattened. */
        const val FLATTEN_THRESHOLD = 500

        const val RECT_VALUES = 4
        const val TOLERANCE_PT = 0.01f

        // PDF 32000-1 table 165. Bit 1 Invisible, bit 2 Hidden, bit 3 Print, bit 6 NoView.
        const val FLAG_INVISIBLE = 1
        const val FLAG_HIDDEN = 2
        const val FLAG_PRINT = 4
        const val FLAG_NO_VIEW = 32
    }
}
