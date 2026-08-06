package dev.inkstone.android.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import dev.inkstone.core.model.AnnotationName
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageAnnotationMode
import dev.inkstone.core.model.PdfPageGeometry
import dev.inkstone.core.model.StrokeOutline
import dev.inkstone.core.storage.InkstoneMarker
import dev.inkstone.core.storage.PdfOpenMode
import dev.inkstone.core.storage.RecoveryLoss
import dev.inkstone.core.storage.RecoveryReason
import dev.inkstone.core.storage.detectOpenMode
import dev.inkstone.core.storage.sha256Hex
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ROADMAP M4's acceptance criteria, over real PDF bytes.
 *
 * These run as **plain JVM unit tests** rather than instrumented ones, which is
 * only possible because PdfBox-Android's structural half touches exactly one
 * Android class (`android.util.Log`) and `unitTests.returnDefaultValues` stubs it.
 * That is worth the sentence: the alternative was a device for every assertion
 * about where an annotation lands, and the assertions that matter here are the ones
 * SPEC §4.4 says are *"invisible until a real PDF is on screen"*.
 *
 * What is still owed to a device: that Acrobat, Chrome and macOS Preview each draw
 * the result correctly. That is a manual gate (ROADMAP M4) and no unit test
 * replaces it.
 */
class PdfPublishTest {

    private val writer = PdfBoxWriter()
    private val documentId = DocumentId("0192f3c4-3333-7abc-8def-0123456789ab")
    private val bundle = "PK pretend this is note.inkstone".toByteArray(Charsets.US_ASCII)

    private fun marker(original: ByteArray) = InkstoneMarker(
        documentId = documentId,
        schemaVersion = 1,
        bundleSha256 = bundle.sha256Hex(),
        originalLength = original.size.toLong(),
        originalSha256 = original.sha256Hex(),
    )

    /**
     * Publish the whole fixture, with ink on one page.
     *
     * All four pages are always planned, because **the plan is the published page
     * list**: a page of the original that the plan does not name is a page the note
     * has tombstoned, and [PdfWriter.publish] correctly leaves it out. Passing one
     * page here would be asking for a one-page document, and getting one.
     */
    private fun publishInk(
        original: ByteArray,
        pageIndex: Int,
        outlines: List<StrokeOutline>,
        mode: PageAnnotationMode = PageAnnotationMode.PER_STROKE,
        pageId: String = "page-$pageIndex",
        words: List<InvisibleWord> = emptyList(),
    ): ByteArray {
        val plans = (0 until PAGE_COUNT).map { index ->
            if (index == pageIndex) {
                PdfFixtures.plan(index, outlines, pageId, mode, words)
            } else {
                PdfFixtures.plan(index, emptyList())
            }
        }
        return writer.publish(original, PublishPlan(marker(original), plans, bundle))
    }

    // -----------------------------------------------------------------------
    // ⭐ Requirement 1 — the incremental update
    // -----------------------------------------------------------------------

    /**
     * SPEC §5.2 — *"preserves the original bytes verbatim at the front of the file."*
     *
     * The prefix check is the whole contract, and it is checked as a prefix rather
     * than by re-parsing: "the same document" is a weaker claim than "the same
     * bytes", and it is the stronger one the design promises.
     */
    @Test
    fun `the original is byte-identically at the front of the published file`() {
        val original = PdfFixtures.sourcePdf()
        val published = publishInk(original, 0, listOf(PdfFixtures.square("a", 72f, 72f, 40f)))

        assertTrue(published.size > original.size, "nothing was appended")
        assertContentEquals(original, published.copyOfRange(0, original.size))
    }

    /**
     * ⭐ ROADMAP M4 — *"the original PDF bytes are recoverable byte-identically
     * after any number of edit cycles."*
     *
     * Each cycle rebases on the original before appending, exactly as
     * [InkstonePdfMaterializer] does. The size assertion is the other half of the
     * claim and the one that would fail silently: appending onto the *previous*
     * published file also keeps the original at the front, and grows the file by an
     * annotation set every time.
     */
    @Test
    fun `five edit cycles leave one update section and the original intact`() {
        val original = PdfFixtures.sourcePdf()
        var published = original
        var firstPublishedSize = -1

        repeat(5) { cycle ->
            val previousMarker = PdfReader.open(published).use { it.marker() }
            val base = PdfReader.originalBytes(published, previousMarker) ?: published
            assertContentEquals(original, base, "cycle $cycle rebased on the wrong bytes")

            published = publishInk(
                base,
                0,
                List(cycle + 1) { PdfFixtures.square("s-$cycle-$it", 72f + it * 10f, 72f, 20f) },
            )
            if (firstPublishedSize < 0) firstPublishedSize = published.size
        }

        assertContentEquals(original, published.copyOfRange(0, original.size))
        // Five strokes rather than one, so it is bigger — but nowhere near five
        // update sections bigger. Anything past 2x the first publish means the
        // rebase stopped happening.
        assertTrue(
            published.size < firstPublishedSize * 2,
            "file grew to ${published.size} from $firstPublishedSize — updates are stacking",
        )
        assertEquals(4, PdfReader.open(published).use { it.pageCount }, "a rebase lost or duplicated pages")
    }

    @Test
    fun `the published file still parses and keeps the source page content`() {
        val original = PdfFixtures.sourcePdf()
        val published = publishInk(original, 0, listOf(PdfFixtures.square("a", 72f, 72f, 40f)))

        PDDocument.load(published).use { doc ->
            assertEquals(PAGE_COUNT, doc.numberOfPages)
            val contents = doc.getPage(0).contents.readBytes().decodeToString()
            assertTrue("20 20 100 100 re" in contents, "the source page's own content was lost")
        }
    }

    // -----------------------------------------------------------------------
    // ⭐ Requirement 2 — coordinate spaces
    // -----------------------------------------------------------------------

    /**
     * ⭐ ROADMAP M4 — *"a source PDF with `/Rotate 90` puts annotations in the right
     * place."*
     *
     * Asserted by reading the written `/InkList` back through the *page's own*
     * geometry and comparing with what went in. That is stronger than checking a
     * number: it is the round trip a Recovered import performs, so if the write and
     * the read disagree about rotation this fails, and if they agree but are both
     * wrong the `/Rect` assertion below catches it.
     */
    @Test
    fun `a rotate 90 page puts annotations where they were drawn`() {
        val original = PdfFixtures.sourcePdf()
        val geometry = writer.readGeometry(original)[PdfFixtures.ROTATED_PAGE]
        // SPEC §4.4 rule 3's worked example, from a real producer this time.
        assertEquals(792f, geometry.pageSize.widthPt)
        assertEquals(612f, geometry.pageSize.heightPt)

        assertAnnotationLandsWhereDrawn(original, PdfFixtures.ROTATED_PAGE, geometry)
    }

    /**
     * ⭐ ROADMAP M4 — *"a source PDF whose CropBox ≠ MediaBox puts annotations in the
     * right place."*
     *
     * The origin is `[36 48]`, so an implementation that assumed `[0 0]` — the other
     * half of the same bug — is off by exactly that much and fails here.
     */
    @Test
    fun `a non-zero-origin CropBox puts annotations where they were drawn`() {
        val original = PdfFixtures.sourcePdf()
        val geometry = writer.readGeometry(original)[PdfFixtures.CROPPED_PAGE]
        assertEquals(540f, geometry.pageSize.widthPt)
        assertEquals(696f, geometry.pageSize.heightPt)
        assertEquals(36f, geometry.cropBox.x0)
        assertEquals(48f, geometry.cropBox.y0)

        assertAnnotationLandsWhereDrawn(original, PdfFixtures.CROPPED_PAGE, geometry)
    }

    /** The combination that actually turns up on scanned lectures. */
    @Test
    fun `a rotated page with an offset CropBox puts annotations where they were drawn`() {
        val original = PdfFixtures.sourcePdf()
        val geometry = writer.readGeometry(original)[PdfFixtures.ROTATED_AND_CROPPED_PAGE]
        assertEquals(696f, geometry.pageSize.widthPt)
        assertEquals(540f, geometry.pageSize.heightPt)

        assertAnnotationLandsWhereDrawn(original, PdfFixtures.ROTATED_AND_CROPPED_PAGE, geometry)
    }

    /**
     * Sizing from the MediaBox is the failure SPEC §4.4 rule 2 names. It would give
     * the cropped page 612x792 instead of 540x696, so this asserts the difference
     * rather than the value — a fixture whose CropBox happened to equal its MediaBox
     * would make every other assertion here vacuous.
     */
    @Test
    fun `page size comes from the CropBox and not the MediaBox`() {
        val geometries = writer.readGeometry(PdfFixtures.sourcePdf())
        val plain = geometries[PdfFixtures.PLAIN_PAGE].pageSize
        val cropped = geometries[PdfFixtures.CROPPED_PAGE].pageSize
        assertTrue(cropped.widthPt < plain.widthPt && cropped.heightPt < plain.heightPt)
    }

    private fun assertAnnotationLandsWhereDrawn(original: ByteArray, pageIndex: Int, geometry: PdfPageGeometry) {
        // Deliberately not centred and not symmetric: a square in the middle of the
        // page survives a wrong rotation, and a square at the origin survives a wrong
        // origin. This one survives neither.
        val drawn = PdfFixtures.square("rot", left = 30f, top = 90f, side = 25f)
        val published = publishInk(original, pageIndex, listOf(drawn))

        val annotation = annotationsOf(published, pageIndex).single()
        val recovered = inkListPoints(annotation).map(geometry::toPage)

        assertEquals(drawn.centreline.size, recovered.size)
        for ((expected, actual) in drawn.centreline.zip(recovered)) {
            assertTrue(
                abs(expected.x - actual.x) < TOLERANCE_PT && abs(expected.y - actual.y) < TOLERANCE_PT,
                "point $expected came back as $actual on page $pageIndex",
            )
        }

        // /Rect is in unrotated user space and must contain the annotation's own
        // geometry — the check that catches a write and a read that are wrong
        // together.
        val rect = (annotation.getDictionaryObject(COSName.RECT) as COSArray).floats()
        for (point in drawn.centreline.map(geometry::toPdfUser)) {
            assertTrue(point.x >= rect[0] - TOLERANCE_PT && point.x <= rect[2] + TOLERANCE_PT, "x outside /Rect")
            assertTrue(point.y >= rect[1] - TOLERANCE_PT && point.y <= rect[3] + TOLERANCE_PT, "y outside /Rect")
        }
    }

    // -----------------------------------------------------------------------
    // SPEC §6.4 — what an annotation carries
    // -----------------------------------------------------------------------

    @Test
    fun `each stroke becomes a standard Ink annotation with both representations`() {
        val original = PdfFixtures.sourcePdf()
        val published = publishInk(original, 0, listOf(PdfFixtures.square("stroke-one", 72f, 72f, 40f)))

        val annotation = annotationsOf(published, 0).single()
        assertEquals("Ink", annotation.getCOSName(COSName.SUBTYPE)?.name)
        // SPEC §5.6 — the ObjectId, which is what stops a recovered import
        // duplicating this stroke against a peer.
        assertEquals(ObjectId("stroke-one"), AnnotationName.parse(annotation.getString(COSName.NM)))
        // SPEC §6.4 — both representations. /AP is what viewers draw; /InkList is
        // what other editors can select and what recovery reads.
        assertNotNull(annotation.getDictionaryObject(COSName.getPDFName("InkList")))
        val appearance = annotation.getDictionaryObject(COSName.AP) as COSDictionary
        assertNotNull(appearance.getDictionaryObject(COSName.N))
        // Bit 3, Print. Ink that is on screen and absent from paper is a bug report.
        assertEquals(4, (annotation.getDictionaryObject(COSName.F) as COSNumber).intValue())
    }

    /**
     * SPEC §4.4 rule 5 warns not to *assume* an identity `/Matrix`. M4 chooses one,
     * because `pageToPdfUser` folds `/Rotate` into the coordinates before they get
     * here — so the appearance, its `/BBox` and the annotation's `/Rect` are all in
     * the same space. This pins the choice: `/BBox` equal to `/Rect` with an
     * identity `/Matrix` is the only combination that is self-consistent.
     */
    @Test
    fun `the appearance stream's BBox matches the annotation's Rect under an identity Matrix`() {
        val original = PdfFixtures.sourcePdf()
        val published = publishInk(original, PdfFixtures.ROTATED_PAGE, listOf(PdfFixtures.square("s", 40f, 40f, 30f)))

        val annotation = annotationsOf(published, PdfFixtures.ROTATED_PAGE).single()
        val form = (annotation.getDictionaryObject(COSName.AP) as COSDictionary)
            .getDictionaryObject(COSName.N) as COSDictionary

        assertContentEquals(
            (annotation.getDictionaryObject(COSName.RECT) as COSArray).floats(),
            (form.getDictionaryObject(COSName.BBOX) as COSArray).floats(),
        )
        assertContentEquals(
            floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f).toList(),
            (form.getDictionaryObject(COSName.MATRIX) as COSArray).floats().toList(),
        )
    }

    @Test
    fun `a translucent stroke carries its alpha`() {
        val original = PdfFixtures.sourcePdf()
        val published =
            publishInk(original, 0, listOf(PdfFixtures.square("hi", 72f, 72f, 40f, PdfFixtures.TRANSLUCENT_YELLOW)))

        val annotation = annotationsOf(published, 0).single()
        val alpha = (annotation.getDictionaryObject(COSName.CA) as COSNumber).floatValue()
        assertTrue(alpha > 0.3f && alpha < 0.5f, "expected the highlighter's ~0.4 alpha, got $alpha")
    }

    // -----------------------------------------------------------------------
    // SPEC §3.4 — the highlighter draws over ink, multiplied
    // -----------------------------------------------------------------------

    /**
     * The half of the M4 highlighter fix that PDF viewers see: annotation order is paint
     * order, so a highlighter written after the ink is a highlighter drawn over it.
     */
    @Test
    fun `a highlighter is painted after the ink it covers`() {
        val original = PdfFixtures.sourcePdf()
        // Handed over in the order the outliner emits them: ink first, highlighters last.
        val published = publishInk(
            original,
            0,
            listOf(PdfFixtures.square("word", 72f, 72f, 40f), PdfFixtures.highlight("hl", 70f, 70f, 44f)),
        )

        val annotations = annotationsOf(published, 0)
        assertEquals(
            listOf("word", "hl"),
            annotations.map { AnnotationName.parse(it.getString(COSName.NM))?.value },
            "annotation order is paint order — the highlight must come second",
        )
    }

    /**
     * **Nothing carries a blend mode**, and the highlighter especially must not.
     *
     * `/BM /Multiply` was written here briefly and removed (SPEC §3.4): over dark ink,
     * multiply is indistinguishable from drawing behind, which is the thing this milestone
     * stopped doing. The rule that outlives the episode is that the published file
     * composites exactly the way the editor does — plain alpha, `/Normal` — so a blend
     * mode reappearing here means the two have drifted apart again.
     */
    @Test
    fun `nothing is published with a blend mode`() {
        val original = PdfFixtures.sourcePdf()
        val published = publishInk(
            original,
            0,
            listOf(PdfFixtures.square("word", 72f, 72f, 40f), PdfFixtures.highlight("hl", 70f, 70f, 44f)),
        )

        for (annotation in annotationsOf(published, 0)) {
            val blend = blendModesOf(annotation)
            assertTrue(blend.isEmpty(), "expected /Normal compositing throughout, got $blend")
        }
    }

    // -----------------------------------------------------------------------
    // ⭐ SPEC §6.4 — the density rule
    // -----------------------------------------------------------------------

    /**
     * ⭐ ROADMAP M4 — *"a 2,000-stroke page uses the flattened path and opens without
     * hanging Preview."*
     *
     * The second half is a manual gate. The first half is this: past 500 strokes the
     * page must carry **no** per-stroke annotations at all, because 2,000 entries in
     * an `/Annots` array is what makes Preview and Chrome's PDFium stop responding.
     */
    @Test
    fun `past five hundred strokes the page is flattened and carries no annotations`() {
        val original = PdfFixtures.sourcePdf()
        val many = List(2_000) { PdfFixtures.square("s-$it", 10f + (it % 50) * 11f, 10f + (it / 50) * 9f, 6f) }
        val published = publishInk(original, 0, many, mode = PageAnnotationMode.FLATTENED)

        assertTrue(annotationsOf(published, 0).isEmpty(), "a flattened page must have no /Annots")
        PdfReader.open(published).use { reader ->
            assertTrue(reader.hasFlattenedPage(), "the page did not record that it was flattened")
        }
        // The ink is in the page's content stream instead, and the source page's own
        // content is still in front of it.
        PDDocument.load(published).use { doc ->
            val contents = doc.getPage(0).contents.readBytes().decodeToString()
            assertTrue("20 20 100 100 re" in contents, "flattening destroyed the source page's content")
            assertTrue(contents.count { it == 'f' } > many.size, "the ink was not flattened into the page")
        }
    }

    @Test
    fun `at five hundred strokes the page still gets one annotation each`() {
        val original = PdfFixtures.sourcePdf()
        val many = List(500) { PdfFixtures.square("s-$it", 10f + (it % 25) * 20f, 10f + (it / 25) * 20f, 6f) }
        val published = publishInk(original, 0, many)
        assertEquals(500, annotationsOf(published, 0).size)
    }

    // -----------------------------------------------------------------------
    // ⭐ Requirement 3 — identity, and the three-way open
    // -----------------------------------------------------------------------

    @Test
    fun `the marker and the bundle both survive publication`() {
        val original = PdfFixtures.sourcePdf()
        val published = publishInk(original, 0, listOf(PdfFixtures.square("a", 72f, 72f, 40f)))

        PdfReader.open(published).use { reader ->
            val found = assertNotNull(reader.marker())
            assertEquals(documentId, found.documentId)
            assertEquals(bundle.sha256Hex(), found.bundleSha256)
            assertEquals(original.size.toLong(), found.originalLength)
            assertContentEquals(bundle, reader.bundle())

            assertIs<PdfOpenMode.Normal>(detectOpenMode(found, reader.bundle()))
        }
        assertContentEquals(original, PdfReader.originalBytes(published, marker(original)))
    }

    /**
     * ⭐ ROADMAP M4 — *"strip the embedded file with `qpdf` → Recovered mode
     * triggers."*
     *
     * `qpdf` is not on the build machine, so the strip is done the way a
     * third-party tool actually does it: load, drop `/Names`, **full rewrite**. That
     * is a harsher test than qpdf's, because it also destroys the pristine original
     * — and the result must say so rather than quietly claiming the bytes are still
     * there.
     */
    @Test
    fun `stripping the embedded bundle triggers Recovered mode and reports what went`() {
        val original = PdfFixtures.sourcePdf()
        val published = publishInk(original, 0, listOf(PdfFixtures.square("a", 72f, 72f, 40f)))
        val stripped = stripEmbeddedFiles(published)

        PdfReader.open(stripped).use { reader ->
            val found = assertNotNull(reader.marker(), "the marker must survive a rewrite — that is its whole job")
            assertNull(reader.bundle())

            val intact = PdfReader.originalBytes(stripped, found)?.sha256Hex() == found.originalSha256
            val mode = detectOpenMode(found, reader.bundle(), originalBytesIntact = intact)

            assertIs<PdfOpenMode.Recovered>(mode)
            assertEquals(RecoveryReason.BUNDLE_MISSING, mode.reason)
            assertTrue(RecoveryLoss.PRESSURE_AND_WIDTH in mode.losses)
            assertTrue(RecoveryLoss.PRISTINE_ORIGINAL in mode.losses, "a rewrite lost the original; say so")
        }
    }

    /**
     * ⭐ ROADMAP M4 — *"a PDF annotated in Acrobat with no XMP marker opens as
     * Foreign, not Recovered."*
     *
     * The fixture is a plain source PDF: no marker, no bundle, and — as it happens —
     * no annotations either, which is the same observation the app makes about a
     * stranger's file.
     */
    @Test
    fun `a PDF that was never ours is Foreign`() {
        PdfReader.open(PdfFixtures.sourcePdf()).use { reader ->
            assertNull(reader.marker())
            assertEquals(PdfOpenMode.Foreign, detectOpenMode(reader.marker(), reader.bundle()))
        }
    }

    /**
     * SPEC §5.6 as amended in M4 — page identity, without which a recovered note
     * gains a duplicate of every page on the next merge (SPEC §4.3).
     */
    @Test
    fun `page identity is written into the page dictionary`() {
        val original = PdfFixtures.sourcePdf()
        val published =
            publishInk(original, 1, listOf(PdfFixtures.square("a", 30f, 30f, 20f)), pageId = "page-uuid-here")

        PDDocument.load(published).use { doc ->
            assertEquals("page-uuid-here", doc.getPage(1).cosObject.getString(COSName.getPDFName("INKS_PageId")))
            assertNotNull(doc.getPage(1).cosObject.getString(COSName.getPDFName("INKS_SortKey")))
        }
    }

    // -----------------------------------------------------------------------
    // Recovery, end to end over real annotations
    // -----------------------------------------------------------------------

    /**
     * The reconstruction path SPEC §5.6 point 1 describes, on a page that is both
     * rotated and cropped — because a recovery that rebuilds ink in the wrong space
     * is worse than one that fails: it produces a note that opens, and is wrong.
     */
    @Test
    fun `recovery reads back the ids, the geometry and the colours it wrote`() {
        val original = PdfFixtures.sourcePdf()
        val drawn = listOf(
            PdfFixtures.square("stroke-one", 30f, 40f, 25f),
            PdfFixtures.square("stroke-two", 90f, 120f, 15f, PdfFixtures.TRANSLUCENT_YELLOW),
        )
        val published = publishInk(original, PdfFixtures.ROTATED_AND_CROPPED_PAGE, drawn)

        val rebuilder = RecordingRebuilder()
        val pages = PdfReader.open(published).use { it.recoveredPages(rebuilder) }
        val page = pages[PdfFixtures.ROTATED_AND_CROPPED_PAGE]

        assertEquals("page-3", page.pageId?.value)
        assertEquals(listOf(ObjectId("stroke-one"), ObjectId("stroke-two")), page.strokes.map { it.id })

        val (points, color, width) = rebuilder.rebuilt.first()
        for ((expected, actual) in drawn[0].centreline.zip(points)) {
            assertTrue(
                abs(expected.x - actual.x) < TOLERANCE_PT && abs(expected.y - actual.y) < TOLERANCE_PT,
                "recovery moved $expected to $actual",
            )
        }
        // Colour survives; a plausible width survives; pressure does not, and that is
        // RecoveryLoss.PRESSURE_AND_WIDTH rather than a bug. Channels are compared
        // within one step because /C is three reals in 0..1 and 0x10/255 does not
        // round-trip exactly through a decimal.
        for (shift in listOf(16, 8, 0)) {
            val expected = (PdfFixtures.OPAQUE_BLACK shr shift) and BYTE_MASK
            val actual = (color shr shift) and BYTE_MASK
            assertTrue(abs(expected - actual) <= 1, "channel at $shift came back $actual, not $expected")
        }
        assertEquals(OPAQUE, (color ushr ALPHA_SHIFT) and BYTE_MASK, "an opaque stroke came back translucent")
        assertTrue(abs(width - 2.5f) < TOLERANCE_PT)
        assertTrue(
            (rebuilder.rebuilt[1].second ushr ALPHA_SHIFT) and BYTE_MASK < OPAQUE,
            "the highlighter came back opaque",
        )
    }

    // -----------------------------------------------------------------------
    // The invisible text layer (SPEC §5.5) — scaffolding in M4
    // -----------------------------------------------------------------------

    @Test
    fun `recognised words are written invisibly into the page content`() {
        val original = PdfFixtures.sourcePdf()
        val published = publishInk(
            original,
            0,
            listOf(PdfFixtures.square("a", 72f, 72f, 40f)),
            words = listOf(InvisibleWord("hello", 72f, 60f, 140f, 80f)),
        )

        PDDocument.load(published).use { doc ->
            val contents = doc.getPage(0).contents.readBytes().decodeToString()
            // `3 Tr` — neither fill nor stroke. The whole point of the sandwich.
            assertTrue("3 Tr" in contents, "the text layer is not invisible")
            assertTrue("(hello) Tj" in contents, "the word was not written")
        }
    }

    @Test
    fun `a page with no recognised words gets no text at all`() {
        val original = PdfFixtures.sourcePdf()
        val published = publishInk(original, 0, listOf(PdfFixtures.square("a", 72f, 72f, 40f)))
        PDDocument.load(published).use { doc ->
            assertTrue("3 Tr" !in doc.getPage(0).contents.readBytes().decodeToString())
        }
    }

    // -----------------------------------------------------------------------

    private fun annotationsOf(pdf: ByteArray, pageIndex: Int): List<COSDictionary> = PDDocument.load(pdf).use { doc ->
        val annots = doc.getPage(pageIndex).cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray
            ?: return emptyList()
        (0 until annots.size()).mapNotNull { annots.getObject(it) as? COSDictionary }
    }

    /**
     * Every `/BM` in an annotation's `/AP /N` resources.
     *
     * Walked with `getDictionaryObject` at each step rather than by casting what `values`
     * returns: once a file has been written and re-parsed, a dictionary's entries are
     * indirect references, so `filterIsInstance<COSDictionary>()` over them silently finds
     * nothing — and an assertion that the blend mode is *absent* then passes for the wrong
     * reason. `getDictionaryObject` resolves the reference.
     */
    private fun blendModesOf(annotation: COSDictionary): List<COSName> {
        val appearance = annotation.getDictionaryObject(COSName.AP) as COSDictionary
        val normal = appearance.getDictionaryObject(COSName.N) as COSDictionary
        val resources = normal.getDictionaryObject(COSName.RESOURCES) as COSDictionary
        val extGState = resources.getDictionaryObject(COSName.EXT_G_STATE) as COSDictionary
        return extGState.keySet().mapNotNull { name ->
            (extGState.getDictionaryObject(name) as? COSDictionary)
                ?.getDictionaryObject(COSName.getPDFName("BM")) as? COSName
        }
    }

    private fun inkListPoints(annotation: COSDictionary): List<dev.inkstone.core.model.PdfPoint> {
        val list = annotation.getDictionaryObject(COSName.getPDFName("InkList")) as COSArray
        val path = list.getObject(0) as COSArray
        return (0 until path.size() step 2).map {
            dev.inkstone.core.model.PdfPoint(
                (path.getObject(it) as COSNumber).floatValue(),
                (path.getObject(it + 1) as COSNumber).floatValue(),
            )
        }
    }

    private fun COSArray.floats(): FloatArray = FloatArray(size()) { (getObject(it) as COSNumber).floatValue() }

    /** What a third-party editor does to a file: keep the pages, rebuild everything else. */
    private fun stripEmbeddedFiles(pdf: ByteArray): ByteArray = PDDocument.load(pdf).use { doc ->
        doc.documentCatalog.cosObject.removeItem(COSName.NAMES)
        val out = ByteArrayOutputStream()
        doc.save(out)
        out.toByteArray()
    }

    private companion object {
        const val PAGE_COUNT = 4
        const val TOLERANCE_PT = 0.01f
        const val ALPHA_SHIFT = 24
        const val BYTE_MASK = 0xFF
        const val OPAQUE = 0xFF
    }
}
