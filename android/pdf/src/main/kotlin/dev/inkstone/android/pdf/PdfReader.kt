package dev.inkstone.android.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import dev.inkstone.core.model.AnnotationName
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PdfBox
import dev.inkstone.core.model.PdfPageGeometry
import dev.inkstone.core.model.PdfPoint
import dev.inkstone.core.model.StrokeRebuilder
import dev.inkstone.core.storage.InkstoneMarker
import dev.inkstone.core.storage.InkstoneXmp
import dev.inkstone.core.storage.NoteLayout
import dev.inkstone.core.storage.RecoveredPage
import dev.inkstone.core.storage.RecoveredStroke
import java.io.Closeable

/**
 * Everything M4 reads back out of a PDF: page geometry, the identity marker, the
 * embedded bundle, and — for SPEC §5.6's Recovered mode — the ink itself.
 *
 * One class rather than four because all four want the same parsed document, and
 * parsing a 200-page PDF four times to answer four questions about it is the kind
 * of thing that makes opening a note slow for no reason a user could name.
 *
 * **Not on the main thread.** Opening a large PDF is I/O plus parsing.
 */
internal class PdfReader private constructor(private val document: PDDocument) : Closeable {

    val pageCount: Int get() = document.numberOfPages

    /** SPEC §4.4 rules 2 and 3 — CropBox with `/Rotate` applied, per page. */
    fun geometries(): List<PdfPageGeometry> = document.pages.map(::geometryOf)

    /** SPEC §5.6 — the XMP marker, or null when this PDF carries none of ours. */
    fun marker(): InkstoneMarker? {
        val metadata = document.documentCatalog?.metadata ?: return null
        val bytes = runCatching { metadata.toByteArray() }.getOrNull() ?: return null
        return InkstoneXmp.parse(bytes.decodeToString())
    }

    /**
     * SPEC §5.3 — the embedded `note.inkstone`.
     *
     * @return null when the `/Names /EmbeddedFiles` entry is not there, which is
     *   the observation SPEC §5.6's Recovered branch begins with. Missing is a
     *   state, not an error, and `qpdf` stripping it is an acceptance test.
     */
    fun bundle(): ByteArray? {
        val tree = document.documentCatalog?.names?.embeddedFiles ?: return null
        val specification = runCatching { tree.getValue(NoteLayout.EMBEDDED_BUNDLE_NAME) }.getOrNull()
            ?: runCatching { tree.names?.get(NoteLayout.EMBEDDED_BUNDLE_NAME) }.getOrNull()
            ?: return null
        val file = specification.embeddedFile ?: specification.embeddedFileUnicode ?: return null
        return runCatching { file.toByteArray() }.getOrNull()
    }

    /** True when any page was published with the flattened overlay (SPEC §6.4). */
    fun hasFlattenedPage(): Boolean = document.pages.any {
        it.cosObject.getBoolean(COSName.getPDFName(PdfPageKeys.FLATTENED), false)
    }

    /**
     * Reconstruct every page's ink from its `/Ink` annotations (SPEC §5.6 point 1).
     *
     * ## Why annotations without our `/NM` are still reconstructed
     *
     * An `/Ink` annotation with no `inkstone:` name is either a third party's
     * markup or one of ours whose name did not survive whatever mangled the file.
     * From here the two are indistinguishable, and the choices are to drop it —
     * which silently loses the user's handwriting in the case that matters — or to
     * recover it under a fresh [dev.inkstone.core.model.ObjectId] and say so.
     *
     * Recovery takes the second option and counts them, because a fresh id is a
     * **reportable** problem (it duplicates against a peer that still holds the
     * original — [dev.inkstone.core.storage.RecoveryLoss.UNIDENTIFIED_OBJECTS])
     * whereas dropped ink is an invisible one. SPEC §5.6's *"warn explicitly, and
     * say what was lost"* is what makes this the right way round.
     */
    fun recoveredPages(rebuilder: StrokeRebuilder): List<RecoveredPage> = document.pages.mapIndexed { index, page ->
        val geometry = geometryOf(page)
        RecoveredPage(
            // Written by M4 into every page it publishes (see PdfPageKeys). Null
            // here means the page dictionary was rebuilt by something else, and
            // the note is about to gain a duplicate page on the next merge.
            pageId = page.cosObject.getString(COSName.getPDFName(PdfPageKeys.PAGE_ID))?.let(::PageId),
            sortKey = page.cosObject.getString(COSName.getPDFName(PdfPageKeys.SORT_KEY))?.let(::FractionalKey),
            size = geometry.pageSize,
            // The source page is still the front of this very file, so the page's
            // background is the page it was imported from — index and all.
            background = Background.SourcePdfPage(index),
            strokes = inkAnnotations(page).mapNotNull { annotation -> recover(annotation, geometry, rebuilder) },
        )
    }

    override fun close() {
        document.close()
    }

    // -----------------------------------------------------------------------

    private fun recover(
        annotation: COSDictionary,
        geometry: PdfPageGeometry,
        rebuilder: StrokeRebuilder,
    ): RecoveredStroke? {
        val points = centreline(annotation, geometry)
        if (points.size < MIN_POLYLINE_POINTS) return null
        val blob = rebuilder.rebuild(points, colorOf(annotation), widthOf(annotation)) ?: return null
        return RecoveredStroke(id = AnnotationName.parse(annotation.getString(COSName.NM)), geometry = blob)
    }

    private fun geometryOf(page: PDPage): PdfPageGeometry {
        // PDFBox's getCropBox already walks the inheritance chain and falls back to
        // MediaBox when there is no CropBox — exactly SPEC §4.4 rule 2's "fall back
        // to MediaBox only when CropBox is absent".
        val box = page.cropBox
        return PdfPageGeometry(
            cropBox = PdfBox(box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY),
            rotate = page.rotation,
        )
    }

    private fun inkAnnotations(page: PDPage): List<COSDictionary> {
        val annots = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: return emptyList()
        val result = ArrayList<COSDictionary>()
        for (index in 0 until annots.size()) {
            val dictionary = annots.getObject(index) as? COSDictionary ?: continue
            if (dictionary.getCOSName(COSName.SUBTYPE)?.name == INK_SUBTYPE) result += dictionary
        }
        return result
    }

    /** The annotation's `/InkList`, flattened into one page-space polyline. */
    private fun centreline(annotation: COSDictionary, geometry: PdfPageGeometry): List<PagePoint> {
        val list = annotation.getDictionaryObject(COSName.getPDFName(INK_LIST)) as? COSArray ?: return emptyList()
        val points = ArrayList<PagePoint>()
        for (pathIndex in 0 until list.size()) {
            val path = list.getObject(pathIndex) as? COSArray ?: continue
            var index = 0
            while (index + 1 < path.size()) {
                val x = (path.getObject(index) as? COSNumber)?.floatValue()
                val y = (path.getObject(index + 1) as? COSNumber)?.floatValue()
                if (x != null && y != null) points += geometry.toPage(PdfPoint(x, y))
                index += 2
            }
        }
        return points
    }

    /** `/C` is RGB in 0..1 and `/CA` is the constant opacity. Recombined into one ARGB. */
    private fun colorOf(annotation: COSDictionary): Int {
        val color = annotation.getDictionaryObject(COSName.C) as? COSArray
        val alpha = ((annotation.getDictionaryObject(COSName.CA) as? COSNumber)?.floatValue() ?: 1f).coerceIn(0f, 1f)
        return (channel(alpha) shl ALPHA_SHIFT) or
            (component(color, 0) shl RED_SHIFT) or
            (component(color, 1) shl GREEN_SHIFT) or
            component(color, 2)
    }

    private fun component(color: COSArray?, index: Int): Int {
        val value = (color?.takeIf { it.size() > index }?.getObject(index) as? COSNumber)?.floatValue() ?: 0f
        return channel(value.coerceIn(0f, 1f))
    }

    private fun channel(value: Float): Int = (value * BYTE_MAX).toInt().coerceIn(0, BYTE_MAX)

    /**
     * `/BS /W` — the uniform width the annotation claims.
     *
     * Falls back to a plausible pen width rather than zero: a zero-width stroke
     * rebuilds as an invisible one, which looks to the user exactly like recovery
     * having lost the ink.
     */
    private fun widthOf(annotation: COSDictionary): Float {
        val style = annotation.getDictionaryObject(COSName.BS) as? COSDictionary
        val width = (style?.getDictionaryObject(COSName.getPDFName("W")) as? COSNumber)?.floatValue() ?: 0f
        return if (width > 0f) width else DEFAULT_WIDTH_PT
    }

    companion object {

        fun open(bytes: ByteArray): PdfReader = PdfReader(PDDocument.load(bytes))

        /**
         * SPEC §5.2 — the original PDF back, byte-identically.
         *
         * ROADMAP M4 makes this an acceptance criterion (*"the original PDF bytes
         * are recoverable byte-identically after any number of edit cycles"*), and
         * it is a `copyOfRange` rather than a reconstruction precisely because the
         * incremental update never touched those bytes.
         *
         * @return null when the file no longer carries an original — a note created
         *   from scratch, or one a third party rewrote.
         */
        fun originalBytes(published: ByteArray, marker: InkstoneMarker?): ByteArray? {
            if (marker == null || !marker.hasOriginal) return null
            if (marker.originalLength > published.size) return null
            return published.copyOfRange(0, marker.originalLength.toInt())
        }

        private const val INK_SUBTYPE = "Ink"
        private const val INK_LIST = "InkList"

        // An ARGB int's channel offsets. These are the layout, not tunables.
        private const val ALPHA_SHIFT = 24
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8

        private const val BYTE_MAX = 255
        private const val MIN_POLYLINE_POINTS = 2
        private const val DEFAULT_WIDTH_PT = 2f
    }
}
