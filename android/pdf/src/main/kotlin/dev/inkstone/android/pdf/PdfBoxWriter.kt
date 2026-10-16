package dev.inkstone.android.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentCatalog
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import dev.inkstone.core.model.AnnotationName
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageAnnotationMode
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PdfBox
import dev.inkstone.core.model.PdfPageGeometry
import dev.inkstone.core.model.StrokeOutline
import dev.inkstone.core.storage.InkstoneMarker
import dev.inkstone.core.storage.InkstoneXmp
import dev.inkstone.core.storage.NoteLayout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * [PdfWriter] on PdfBox-Android — the M4.0 spike's answer (SPEC §6.3, docs/M4-PDF.md §1).
 *
 * ## ⭐ The incremental update (SPEC §5.2)
 *
 * `saveIncremental` copies the loaded bytes to the output **verbatim** and appends
 * a new body, xref and trailer whose `/Prev` chains back to the old one. That is
 * the whole of requirement #1: the original PDF is byte-identically at the front
 * of the published file, so *"give me my original back"* costs a `copyOfRange`,
 * and the embedded bundle carries no second copy of a 20 MB lecture PDF.
 *
 * **The landmine, stated plainly because it fails silently:** in incremental mode
 * PDFBox writes only objects whose `COSUpdateInfo.isNeedToBeUpdated()` is true —
 * *including objects this code just created*. An unmarked annotation is simply
 * absent from the output, with no error anywhere. So [markUpdated] is called on
 * every dictionary and stream built here, and on the parent chain (page → catalog)
 * that has to point at them. `PdfPublishTest` is what proves it, by re-parsing the
 * output rather than by trusting this paragraph.
 *
 * ## Threading
 *
 * Materialization only — on close, before upload, or idle-while-charging. Never on
 * the ink path (CLAUDE.md rule 7). [PdfDocumentMaterializer] moves it off the main
 * thread; nothing here does its own dispatching.
 */
internal class PdfBoxWriter(faces: PdfFontFaces = PdfFontFaces.None, decoder: PdfImageDecoder = PdfImageDecoder.None) :
    PdfWriter {

    /**
     * M8c's text boxes and images, and the resources they name — see [PdfPageObjects].
     *
     * ⚠️ **The defaults are the *narrow* ones on purpose.** A writer built with no arguments
     * publishes text boxes in a standard-14 base font and skips any image that is not already a
     * JPEG, which is what a JVM test can reach. Production passes both in through
     * [InkstonePdfMaterializer], so a test can never be more generous than the app — the
     * direction CLAUDE.md warns about is the other one, and this makes it structurally unavailable.
     */
    private val pageObjects = PdfPageObjects(faces, decoder)

    override fun createBase(pages: List<BasePage>): ByteArray {
        require(pages.isNotEmpty()) { "a base PDF needs at least one page" }
        PDDocument().use { doc ->
            for (spec in pages) {
                val page = PDPage(PDRectangle(spec.canvas.size.widthPt, spec.canvas.size.heightPt))
                doc.addPage(page)
                val template = PdfTemplateContent.forPage(spec.background, spec.canvas.size)
                if (template.isNotEmpty()) {
                    val stream = PDStream(doc, ByteArrayInputStream(template), COSName.FLATE_DECODE)
                    page.cosObject.setItem(COSName.CONTENTS, stream)
                }
            }
            val out = ByteArrayOutputStream(ESTIMATED_BASE_BYTES)
            doc.save(out)
            return out.toByteArray()
        }
    }

    override fun publish(original: ByteArray, plan: PublishPlan): ByteArray {
        PDDocument.load(original).use { doc ->
            val catalog = doc.documentCatalog
            val originalPageCount = doc.numberOfPages

            val pages = plan.pages.map { pagePlan -> resolvePage(doc, pagePlan, originalPageCount) }
            for ((page, pagePlan) in pages.zip(plan.pages)) writePage(doc, page, pagePlan)
            reorderIfNeeded(catalog, pages, plan.pages, originalPageCount)

            writeMarker(doc, catalog, plan.marker)
            embedBundle(doc, catalog, plan.bundle)
            markUpdated(catalog.cosObject)

            val out = ByteArrayOutputStream(original.size + ESTIMATED_UPDATE_BYTES)
            doc.saveIncremental(out)
            return out.toByteArray()
        }
    }

    private fun resolvePage(doc: PDDocument, plan: PdfPagePlan, originalPageCount: Int): PDPage =
        when (val source = plan.source) {
            is PageSource.Existing -> {
                // A plan pointing past the end of the original is a bug in the caller,
                // and a silent skip would publish a note missing a page.
                require(source.index in 0 until originalPageCount) {
                    "page plan ${source.index} is outside the $originalPageCount-page original"
                }
                doc.getPage(source.index)
            }

            // ⭐ The box is the **paper**, which on an infinite page is the canvas grown to hold
            // the ink (SPEC §4.5) — and it starts at the PDF origin whatever the canvas's own
            // page-space origin is. The two are reconciled by [PdfPageGeometry.origin], which
            // `writePage` puts on the geometry every conversion goes through.
            is PageSource.Generated -> PDPage(box(source.canvas.size)).also { page ->
                val template = PdfTemplateContent.forPage(source.background, source.canvas.size)
                if (template.isNotEmpty()) {
                    val stream = PDStream(doc, ByteArrayInputStream(template), COSName.FLATE_DECODE).cosObject
                    markUpdated(stream)
                    page.cosObject.setItem(COSName.CONTENTS, stream)
                }
                doc.addPage(page)
            }
        }

    /**
     * Rewrite the page tree — but only when the published order is not already the
     * original's.
     *
     * The common case by far is an imported PDF whose pages nobody has touched, and
     * there the base's own tree is already right. Rebuilding it anyway would drag
     * every page object into the update section to fix its `/Parent`, which for a
     * 200-page lecture is a few hundred objects written on every save to say nothing
     * had changed.
     */
    private fun reorderIfNeeded(
        catalog: PDDocumentCatalog,
        pages: List<PDPage>,
        plans: List<PdfPagePlan>,
        originalPageCount: Int,
    ) {
        val unchanged = plans.size == originalPageCount &&
            plans.withIndex().all { (index, plan) -> (plan.source as? PageSource.Existing)?.index == index }
        if (unchanged) return

        val root = catalog.pages.cosObject
        val kids = COSArray()
        for (page in pages) {
            kids.add(page.cosObject)
            // Before the /Parent changes, not after: what the page inherits is read through
            // the chain this line is about to shorten.
            PdfResources.inheritDown(page.cosObject)
            // A flattened tree means every page's parent is the root. Pages that were
            // in a nested node have to be told, or a reader following /Parent walks
            // into a node that no longer lists them.
            page.cosObject.setItem(COSName.PARENT, root)
            markUpdated(page.cosObject)
        }
        markUpdated(kids)
        root.setItem(COSName.KIDS, kids)
        root.setInt(COSName.COUNT, pages.size)
        markUpdated(root)
    }

    // SPEC §4.4 rule 2 — CropBox, not MediaBox. PDFBox's getCropBox already walks the
    // inheritance chain and falls back to MediaBox when there is no CropBox, which is
    // exactly the rule's "fall back to MediaBox only when CropBox is absent".
    override fun readGeometry(pdf: ByteArray): List<PdfPageGeometry> =
        PDDocument.load(pdf).use { doc -> doc.pages.map(::geometryOf) }

    // -----------------------------------------------------------------------
    // One page
    // -----------------------------------------------------------------------

    /**
     * ⭐ **One appended content stream per page, not one per feature** (M8c).
     *
     * M4 called [appendContent] once for the flattened overlay and again for the invisible text
     * layer, and each call re-wraps everything already there in a fresh `q`/`Q`. Two features
     * therefore nested two levels deep; M8c's text boxes and images would have made it four. The
     * pieces are each balanced on their own, so concatenating them and appending once is the same
     * output with one level of nesting — and it is the only arrangement in which the page's
     * objects come out in the order this method wrote them.
     */
    private fun writePage(doc: PDDocument, page: PDPage, plan: PdfPagePlan) {
        val dict = page.cosObject
        // Read from the page rather than taken from the caller. SPEC §4.4's whole
        // failure mode is a CropBox and a rotation that came from somewhere other
        // than the page being written on; there is no way to pass the wrong one here.
        val anchor = geometryOf(page)
        // ⭐ SPEC §4.5 — an infinite page's paper may reach past the box the page was imported or
        // created with, so the box grows to meet it.
        //
        // ⚠️ **Only an infinite page's**, and the guard is not a shortcut. A fixed page publishes
        // into the box it already had, exactly as it did before this existed, so nothing about an
        // ordinary note's output changes — and an imported page whose stored extent has drifted
        // from its source page's box (a replaced PDF, SPEC §6.2) is not silently resized by a
        // number that came from the note rather than from the page.
        //
        // ⚠️ **Computed from the anchor and applied afterwards, in that order.** The enlarged box
        // has a different top-left corner, and the top-left corner is what page space is measured
        // from — so a geometry read back off the enlarged page would place every annotation on it
        // by however far the box grew. [PdfPageGeometry.origin] is what cancels that, and it only
        // cancels the growth that `boxFor` was asked for.
        //
        // ⚠️ **Only a page that already had content gets its box enlarged**, and getting that wrong
        // is not a crash but a page of the wrong size. A *generated* page was created a few lines
        // ago at exactly the canvas's extent ([resolvePage]) and has nothing on it to preserve, so
        // enlarging it as well adds the growth twice: a canvas 1008 pt wide came out as a 1404 pt
        // page with 396 pt of blank paper down one side. Caught by reading the published
        // `/MediaBox` back with something that is not PDFBox, which is the only kind of check that
        // could have caught it — every assertion made with the writing library agreed with itself.
        val geometry = when {
            plan.source is PageSource.Existing && plan.canvas.infinite -> {
                val grown = anchor.boxFor(plan.canvas)
                if (grown != anchor.cropBox) setBox(dict, grown)
                PdfPageGeometry(grown, anchor.rotate, origin = plan.canvas.origin)
            }

            // A generated page *is* the paper, so its box needs no adjusting — only the mapping
            // from page space into it, which is what the origin carries.
            else -> PdfPageGeometry(anchor.cropBox, anchor.rotate, origin = plan.canvas.origin)
        }

        // Page identity (SPEC §5.6 as amended in M4 — see PdfPageKeys).
        dict.setString(COSName.getPDFName(PdfPageKeys.PAGE_ID), plan.pageId.value)
        dict.setString(COSName.getPDFName(PdfPageKeys.SORT_KEY), plan.sortKey.value)

        val drawable = plan.outlines.filterNot { it.isEmpty }
        val content = mutableListOf<ByteArray>()
        val states = COSDictionary()
        val fonts = COSDictionary()

        when (plan.mode) {
            PageAnnotationMode.PER_STROKE -> {
                dict.removeItem(COSName.getPDFName(PdfPageKeys.FLATTENED))
                appendAnnotations(doc, dict, drawable, geometry, plan.generated)
            }

            PageAnnotationMode.FLATTENED -> {
                dict.setBoolean(COSName.getPDFName(PdfPageKeys.FLATTENED), true)
                if (drawable.isNotEmpty()) {
                    mergeInto(states, extGStates(drawable))
                    content += PdfContent.flattenedOverlay(drawable, geometry)
                }
            }
        }

        // ⭐ SPEC §4.9 — text boxes and images, as page content and never as annotations. That is
        // what keeps `PdfPagePlan.annotatedObjects` honest: SPEC §6.4's threshold is a cap on
        // `/Annots` length, and nothing below adds to it.
        val objects = pageObjects.write(doc, plan, geometry)
        content += objects.content
        mergeInto(states, objects.states)
        mergeInto(fonts, objects.fonts)
        PdfResources.addToPage(dict, COSName.XOBJECT, objects.xObjects)

        val text = PdfContent.invisibleText(plan.words, geometry, TEXT_FONT_NAME)
        if (text.isNotEmpty()) {
            fonts.setItem(COSName.getPDFName(TEXT_FONT_NAME), baseFontDictionary(INVISIBLE_BASE_FONT))
            content += text
        }

        // ⚠️ The **page's** resources, not the stream's — see PdfResources. Both calls are no-ops
        // for an empty category, so a page with no objects writes no dictionaries.
        PdfResources.addToPage(dict, COSName.EXT_G_STATE, states)
        PdfResources.addToPage(dict, COSName.FONT, fonts)
        if (content.isNotEmpty()) appendContent(doc, page, flateStream(doc, join(content)))

        markUpdated(dict)
        markParentChain(dict)
    }

    /** Copy [source]'s entries into [target]. One category, built from several contributors. */
    private fun mergeInto(target: COSDictionary, source: COSDictionary) {
        if (source.keySet().isEmpty()) return
        for (name in source.keySet()) target.setItem(name, source.getItem(name))
        markUpdated(target)
    }

    private fun join(parts: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(parts.sumOf { it.size })
        for (part in parts) out.write(part)
        return out.toByteArray()
    }

    /**
     * Mark every `/Pages` node between this page and the root.
     *
     * PDFBox's incremental writer walks **from the trailer**, and stops at the first
     * object that is not marked. A marked page hanging off an unmarked `/Pages` node
     * is therefore never reached and never written — the update section comes out
     * containing the catalog, the marker and the bundle, and no ink at all.
     *
     * It fails exactly this quietly: the file is valid, opens everywhere, has the
     * right page count, and is missing every annotation. Found by `PdfPublishTest`
     * asserting on the output rather than on the intent.
     */
    private fun markParentChain(pageDict: COSDictionary) {
        var node: COSDictionary? = pageDict.getDictionaryObject(COSName.PARENT) as? COSDictionary
        // A malformed file could in principle have a /Parent cycle; a page tree is
        // never deep, so a bound is cheaper than a visited set.
        var depth = 0
        while (node != null && depth < MAX_PAGE_TREE_DEPTH) {
            markUpdated(node)
            node = node.getDictionaryObject(COSName.PARENT) as? COSDictionary
            depth++
        }
    }

    /** A page rectangle at the PDF origin. */
    private fun box(size: PageSize) = PDRectangle(size.widthPt, size.heightPt)

    /**
     * Write a page's box, both boxes, and mark it.
     *
     * ⚠️ **`/MediaBox` as well as `/CropBox`.** A CropBox larger than the MediaBox is clipped back
     * to it by every reader — the PDF specification says a CropBox is an *intersection* with the
     * media — so growing only the one a viewer displays (SPEC §4.4 rule 2) would leave a grown
     * page looking exactly like an ungrown one, with the extra ink cut off at the old edge and
     * nothing in the file to say why.
     */
    private fun setBox(dict: COSDictionary, box: PdfBox) {
        val array = COSArray().apply {
            add(COSFloat(box.x0))
            add(COSFloat(box.y0))
            add(COSFloat(box.x1))
            add(COSFloat(box.y1))
        }
        markUpdated(array)
        dict.setItem(COSName.CROP_BOX, array)
        dict.setItem(COSName.MEDIA_BOX, array)
        markUpdated(dict)
    }

    private fun geometryOf(page: PDPage): PdfPageGeometry {
        val box = page.cropBox
        return PdfPageGeometry(
            cropBox = PdfBox(box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY),
            rotate = page.rotation,
        )
    }

    /**
     * One `/Ink` annotation per stroke, appended to whatever `/Annots` the page
     * already had.
     *
     * Appended, never replaced: SPEC §6.2 keeps a source PDF's existing
     * annotations — *"rendered, preserved on export, not editable in v1"* — and
     * because every publish rebases on the pristine original, the array being
     * appended to is always the original's own.
     */
    private fun appendAnnotations(
        doc: PDDocument,
        pageDict: COSDictionary,
        outlines: List<StrokeOutline>,
        geometry: PdfPageGeometry,
        generated: Set<ObjectId>,
    ) {
        if (outlines.isEmpty()) return
        val annots = (pageDict.getDictionaryObject(COSName.ANNOTS) as? COSArray) ?: COSArray().also {
            pageDict.setItem(COSName.ANNOTS, it)
        }
        for (outline in outlines) {
            val annotation = inkAnnotation(doc, outline, geometry)
            if (outline.objectId in generated) annotation.setBoolean(COSName.getPDFName(PdfPageKeys.GENERATED), true)
            annots.add(annotation)
        }
        markUpdated(annots)
    }

    /**
     * A standard `/Ink` annotation carrying **both** representations (SPEC §6.4).
     *
     * `/AP /N` is the filled outline — full pressure variation, and what viewers
     * draw. `/InkList` is the uniform-width centreline, for interop, so another
     * editor can select and move the stroke rather than seeing an opaque blob.
     * `/InkList` is also what Recovered mode reads back (see [RecoveredStroke]);
     * the "redundant" half turns out to be the only one that reconstructs as ink.
     */
    private fun inkAnnotation(doc: PDDocument, outline: StrokeOutline, geometry: PdfPageGeometry): COSDictionary {
        val rect = boundsOf(outline, geometry)
        val stateName = PdfContent.stateNameFor(outline)

        val annotation = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.ANNOT)
            setItem(COSName.SUBTYPE, COSName.getPDFName(INK_SUBTYPE))
            // SPEC §5.6 — the ObjectId, so a recovered import merges onto the peer's
            // copy instead of duplicating it.
            setString(COSName.NM, AnnotationName.of(outline.objectId))
            setItem(COSName.RECT, rect.toCosArray())
            setItem(COSName.getPDFName(INK_LIST), inkList(outline, geometry))
            setItem(COSName.C, colorArray(outline.colorArgb))
            setItem(
                COSName.BS,
                COSDictionary().apply {
                    setItem(COSName.getPDFName("S"), COSName.getPDFName("S"))
                    setFloat(COSName.getPDFName("W"), outline.widthPt)
                    markUpdated(this)
                },
            )
            setFloat(COSName.CA, PdfContent.alphaValue(outline.colorArgb))
            // Bit 3, "Print". Without it the ink is on screen and absent from paper,
            // which is a bug report waiting to happen.
            setItem(COSName.F, COSInteger.get(ANNOTATION_FLAG_PRINT.toLong()))
            setItem(
                COSName.AP,
                COSDictionary().apply {
                    setItem(COSName.N, appearance(doc, outline, geometry, rect, stateName))
                    markUpdated(this)
                },
            )
        }
        markUpdated(annotation)
        return annotation
    }

    /**
     * The `/AP /N` form XObject.
     *
     * ## `/Matrix` is identity, and that is a choice rather than an assumption
     *
     * SPEC §4.4 rule 5 warns not to assume identity, and rule 4 says the rotation
     * *"is then folded into the annotation's `/Matrix`"*. M4 folds it one step
     * earlier instead: [PdfPageGeometry.toPdfUser] already maps page space through
     * `/Rotate` into unrotated PDF user space, so by the time a coordinate reaches
     * this method there is no rotation left to express. The content, the `/BBox`
     * and the annotation's `/Rect` are then all in the same space, and the
     * viewer's BBox→Rect fit is the identity it would compute anyway.
     *
     * This is simpler *and* safer than a rotated `/Matrix`: a non-identity matrix
     * has to agree with a `/BBox` computed in form space, and the two disagreeing
     * is exactly the "annotations are 3mm off and rotated" failure §4.4 exists to
     * prevent. SPEC §4.4 has been amended to record the choice.
     */
    private fun appearance(
        doc: PDDocument,
        outline: StrokeOutline,
        geometry: PdfPageGeometry,
        rect: PdfBox,
        stateName: String,
    ): COSStream {
        val content = PdfContent.strokeAppearance(outline, geometry, stateName)
        val stream = PDStream(doc, ByteArrayInputStream(content), COSName.FLATE_DECODE).cosObject
        stream.setItem(COSName.TYPE, COSName.XOBJECT)
        stream.setItem(COSName.SUBTYPE, COSName.FORM)
        stream.setItem(COSName.BBOX, rect.toCosArray())
        stream.setItem(COSName.MATRIX, identityMatrix())
        // A form XObject is the one kind of stream that legitimately carries its own
        // `/Resources`, and this is the only place in this writer that may set them.
        stream.setItem(COSName.RESOURCES, PdfResources.form(COSName.EXT_G_STATE, extGStates(listOf(outline))))
        markUpdated(stream)
        return stream
    }

    private fun flateStream(doc: PDDocument, content: ByteArray): COSStream {
        val stream = PDStream(doc, ByteArrayInputStream(content), COSName.FLATE_DECODE).cosObject
        markUpdated(stream)
        return stream
    }

    /**
     * Append [stream] to the page's content, leaving the original stream objects
     * untouched.
     *
     * The `q`/`Q` guards are not decoration. A source PDF's content stream is not
     * required to leave the graphics state as it found it, and appending onto an
     * unbalanced one puts our fills under whatever clip or transform was left
     * behind — which shows up as ink in the wrong place on exactly the scanned
     * documents this app is for.
     */
    private fun appendContent(doc: PDDocument, page: PDPage, stream: COSStream) {
        val contents = COSArray()
        contents.add(rawStream(doc, "q\n".toByteArray(Charsets.US_ASCII)))
        when (val existing = page.cosObject.getDictionaryObject(COSName.CONTENTS)) {
            is COSArray -> for (index in 0 until existing.size()) contents.add(existing.get(index))
            null -> Unit
            else -> contents.add(existing)
        }
        contents.add(rawStream(doc, "Q\n".toByteArray(Charsets.US_ASCII)))
        contents.add(stream)
        page.cosObject.setItem(COSName.CONTENTS, contents)
        markUpdated(contents)
    }

    private fun rawStream(doc: PDDocument, bytes: ByteArray): COSStream {
        val stream = PDStream(doc, ByteArrayInputStream(bytes)).cosObject
        markUpdated(stream)
        return stream
    }

    // -----------------------------------------------------------------------
    // Catalog-level: the marker and the bundle
    // -----------------------------------------------------------------------

    /**
     * SPEC §5.6 — the XMP identity marker.
     *
     * Written **uncompressed**, deliberately. Adobe's XMP specification asks for
     * an unfiltered `/Metadata` stream so that a tool can find the packet by
     * scanning the file rather than by parsing the object graph — and the tools
     * most likely to strip the embedded bundle are exactly the ones that will not
     * be walking our name tree. An uncompressed packet is a few hundred bytes.
     */
    private fun writeMarker(doc: PDDocument, catalog: PDDocumentCatalog, marker: InkstoneMarker) {
        val metadata = PDMetadata(doc)
        metadata.createOutputStream().use { it.write(InkstoneXmp.packet(marker).toByteArray(Charsets.UTF_8)) }
        catalog.metadata = metadata
        markUpdated(metadata.cosObject)
    }

    /** SPEC §5.3 — the lossless bundle, via `/Names /EmbeddedFiles`. */
    private fun embedBundle(doc: PDDocument, catalog: PDDocumentCatalog, bundle: ByteArray) {
        val embedded = PDEmbeddedFile(doc, ByteArrayInputStream(bundle), COSName.FLATE_DECODE).apply {
            subtype = BUNDLE_MIME
            size = bundle.size
        }
        markUpdated(embedded.cosObject)

        val specification = PDComplexFileSpecification().apply {
            file = NoteLayout.EMBEDDED_BUNDLE_NAME
            fileUnicode = NoteLayout.EMBEDDED_BUNDLE_NAME
            embeddedFile = embedded
            embeddedFileUnicode = embedded
            // PDFBox's no-arg constructor leaves /Type off, and a file specification
            // in an /EmbeddedFiles name tree is required to carry it. Readers that
            // check — Acrobat's attachment pane is one — otherwise show nothing,
            // which looks exactly like a bundle that was never written.
            cosObject.setItem(COSName.TYPE, COSName.getPDFName("Filespec"))
        }
        markUpdated(specification.cosObject)

        val tree = PDEmbeddedFilesNameTreeNode().apply {
            names = mapOf(NoteLayout.EMBEDDED_BUNDLE_NAME to specification)
        }
        markUpdated(tree.cosObject)

        val names = PDDocumentNameDictionary(catalog).apply { embeddedFiles = tree }
        markUpdated(names.cosObject)
        catalog.names = names
    }

    // -----------------------------------------------------------------------
    // COS helpers
    // -----------------------------------------------------------------------

    private fun boundsOf(outline: StrokeOutline, geometry: PdfPageGeometry): PdfBox {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (contour in outline.contours) {
            for (point in contour) {
                val user = geometry.toPdfUser(point)
                if (user.x < minX) minX = user.x
                if (user.y < minY) minY = user.y
                if (user.x > maxX) maxX = user.x
                if (user.y > maxY) maxY = user.y
            }
        }
        // A hairline stroke can be zero-extent on one axis, and a zero-area /Rect is
        // an annotation some viewers decline to draw at all.
        if (maxX - minX < MIN_EXTENT_PT) maxX = minX + MIN_EXTENT_PT
        if (maxY - minY < MIN_EXTENT_PT) maxY = minY + MIN_EXTENT_PT
        return PdfBox(minX - RECT_PADDING_PT, minY - RECT_PADDING_PT, maxX + RECT_PADDING_PT, maxY + RECT_PADDING_PT)
    }

    private fun inkList(outline: StrokeOutline, geometry: PdfPageGeometry): COSArray {
        val path = COSArray()
        for (point in outline.centreline) {
            val user = geometry.toPdfUser(point)
            path.add(COSFloat(user.x))
            path.add(COSFloat(user.y))
        }
        val list = COSArray()
        list.add(path)
        return list
    }

    /** `/C` — DeviceRGB in 0..1, alpha carried separately in `/CA`. */
    private fun colorArray(argb: Int): COSArray = COSArray().apply {
        for (shift in RGB_SHIFTS) add(COSFloat(((argb shr shift) and BYTE_MASK) / BYTE_MASK.toFloat()))
    }

    private fun identityMatrix(): COSArray = COSArray().apply {
        for (value in IDENTITY) add(COSFloat(value))
    }

    /**
     * The `/ExtGState` entries for the compositing the strokes on a page use.
     *
     * Plain alpha, `/Normal` blending — the same compositing [PagePainter] does on screen.
     *
     * A `/Multiply` blend was written here briefly after M4 and removed along with the
     * editor's: over dark ink it is indistinguishable from drawing behind, which is the
     * thing SPEC §3.4 stopped doing. What keeps the published file honest is that it
     * matches the editor, not that it is physically faithful.
     */
    private fun extGStates(outlines: List<StrokeOutline>): COSDictionary {
        val extGState = COSDictionary()
        for (outline in outlines.distinctBy { PdfContent.stateNameFor(it) }) {
            val alpha = PdfContent.alphaValue(outline.colorArgb)
            extGState.setItem(
                COSName.getPDFName(PdfContent.stateNameFor(outline)),
                COSDictionary().apply {
                    setFloat(COSName.CA, alpha)
                    setFloat(COSName.getPDFName("ca"), alpha)
                    markUpdated(this)
                },
            )
        }
        markUpdated(extGState)
        return extGState
    }

    private fun PdfBox.toCosArray(): COSArray = COSArray().apply {
        add(COSFloat(x0))
        add(COSFloat(y0))
        add(COSFloat(x1))
        add(COSFloat(y1))
    }

    private companion object {
        /** `[a b c d e f]` — the PDF form of the identity transform. See [appearance] on why. */
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)

        /** An ARGB int's red, green and blue offsets, in the order `/C` wants them. */
        val RGB_SHIFTS = intArrayOf(16, 8, 0)

        const val INK_SUBTYPE = "Ink"
        const val INK_LIST = "InkList"
        const val TEXT_FONT_NAME = "InkstoneF0"

        /**
         * The invisible layer's font (SPEC §5.5), named and not embedded.
         *
         * A base font rather than a CID subset, deliberately: the layer is `3 Tr`, so nothing is
         * ever *drawn* with it and its metrics are irrelevant. Embedding a face for text nobody
         * sees would add a subset to every published page that has been over the recogniser.
         * Recognised words outside printable ASCII are skipped per word — `PdfContent.invisibleText`
         * has the reasoning, and carrying them is what M8c's [PdfPageObjects] path is for.
         */
        const val INVISIBLE_BASE_FONT = "Helvetica"
        const val BUNDLE_MIME = "application/vnd.inkstone.note"
        const val ANNOTATION_FLAG_PRINT = 4
        const val BYTE_MASK = 0xFF
        const val RECT_PADDING_PT = 1f
        const val MIN_EXTENT_PT = 0.5f
        const val ESTIMATED_BASE_BYTES = 8 * 1024
        const val ESTIMATED_UPDATE_BYTES = 64 * 1024
        const val MAX_PAGE_TREE_DEPTH = 64
    }
}
