package dev.inkstone.android.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import dev.inkstone.core.model.FractionalKey
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageAnnotationMode
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeOutline
import dev.inkstone.core.model.StrokeRebuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The source PDFs M4's acceptance criteria are about, built rather than committed.
 *
 * ROADMAP M4 asks for a `/Rotate 90` page and a page whose CropBox is not the
 * MediaBox — *"both are common in scanned lecture PDFs and both break naive
 * implementations"*. Generating them keeps the two facts that matter (the rotation,
 * the offset origin) visible in the test rather than buried in a binary nobody can
 * read in a diff.
 *
 * A committed fixture is still owed for anything the *format* has to survive —
 * SPEC §13.1's per-schema-version bundles are that, and this is not it.
 */
internal object PdfFixtures {

    /** US Letter, no rotation, origin at zero. The easy case, so the others have a control. */
    const val PLAIN_PAGE = 0

    /** `/Rotate 90` on a Letter MediaBox: page space is 792x612 (SPEC §4.4 rule 3). */
    const val ROTATED_PAGE = 1

    /**
     * A CropBox inset from the MediaBox with a **non-zero origin** — what a scan
     * trimmed to its content looks like. Sizing from the MediaBox, or assuming the
     * origin is `[0 0]`, puts every annotation on this page in the wrong place
     * (SPEC §4.4 rule 2).
     */
    const val CROPPED_PAGE = 2

    /** Both at once, which is the combination that actually turns up on lecture scans. */
    const val ROTATED_AND_CROPPED_PAGE = 3

    fun sourcePdf(): ByteArray = PDDocument().use { doc ->
        doc.addPage(doc.page("plain"))
        doc.addPage(doc.page("rotated").also { it.rotation = 90 })
        // x0=36, y0=48, 540x696 — inset from the MediaBox on all four sides.
        doc.addPage(doc.page("cropped").also { it.cropBox = PDRectangle(36f, 48f, 540f, 696f) })
        doc.addPage(
            doc.page("both").also {
                it.cropBox = PDRectangle(36f, 48f, 540f, 696f)
                it.rotation = 90
            },
        )

        val out = ByteArrayOutputStream()
        doc.save(out)
        out.toByteArray()
    }

    /**
     * A one-page PDF whose page carries **its own** `/Resources`, used by content that
     * needs them.
     *
     * `sourcePdf`'s pages reference no named resources at all, which makes it useless for
     * the question of whether publishing *keeps* a source page's resources while adding
     * ours. A name that a source content stream uses and the published file no longer
     * defines is ink drawn on a page whose own graphics have gone.
     */
    fun sourcePdfWithPageResources(): ByteArray = PDDocument().use { doc ->
        val page = PDPage(PDRectangle(612f, 792f))
        page.cosObject.setItem(COSName.RESOURCES, resourcesWithState())
        page.cosObject.setItem(
            COSName.CONTENTS,
            PDStream(doc, ByteArrayInputStream(sourceContent), COSName.FLATE_DECODE).cosObject,
        )
        doc.addPage(page)

        val out = ByteArrayOutputStream()
        doc.save(out)
        out.toByteArray()
    }

    /**
     * Two pages under an **intermediate `/Pages` node** that supplies their `/MediaBox` and
     * `/Resources` by inheritance (PDF 32000-1 §7.7.3.4).
     *
     * This is the shape pdfTeX and Ghostscript produce for a long document, and the one that
     * makes inheritance load-bearing: reordering or adding a page flattens the tree, and a
     * page whose size came from the node that was cut out has no size at all. The MediaBox is
     * deliberately **not** US Letter, because Letter is exactly what PDFBox falls back to
     * when it cannot find one — a Letter fixture would make the bug invisible.
     */
    fun sourcePdfWithInheritedAttributes(): ByteArray = PDDocument().use { doc ->
        val root = doc.documentCatalog.pages.cosObject
        val node = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.getPDFName("Pages"))
            setItem(COSName.PARENT, root)
            setItem(COSName.MEDIA_BOX, box(INHERITED_WIDTH_PT, INHERITED_HEIGHT_PT))
            setItem(COSName.RESOURCES, resourcesWithState())
        }
        val kids = COSArray()
        repeat(2) {
            kids.add(
                COSDictionary().apply {
                    setItem(COSName.TYPE, COSName.PAGE)
                    setItem(COSName.PARENT, node)
                    setItem(
                        COSName.CONTENTS,
                        PDStream(doc, ByteArrayInputStream(sourceContent), COSName.FLATE_DECODE).cosObject,
                    )
                },
            )
        }
        node.setItem(COSName.KIDS, kids)
        node.setInt(COSName.COUNT, kids.size())
        root.setItem(COSName.KIDS, COSArray().apply { add(node) })
        root.setInt(COSName.COUNT, kids.size())

        val out = ByteArrayOutputStream()
        doc.save(out)
        out.toByteArray()
    }

    /** `/ExtGState << /SrcGS << /ca 0.5 /CA 0.5 >> >>` — one name a source stream uses. */
    private fun resourcesWithState(): COSDictionary = COSDictionary().apply {
        setItem(
            COSName.EXT_G_STATE,
            COSDictionary().apply {
                setItem(
                    COSName.getPDFName(SOURCE_STATE_NAME),
                    COSDictionary().apply {
                        setFloat(COSName.CA, 0.5f)
                        setFloat(COSName.getPDFName("ca"), 0.5f)
                    },
                )
            },
        )
    }

    private fun box(width: Float, height: Float): COSArray = COSArray().apply {
        add(COSFloat(0f))
        add(COSFloat(0f))
        add(COSFloat(width))
        add(COSFloat(height))
    }

    /** Content that names [SOURCE_STATE_NAME], so dropping the source's resources is detectable. */
    private val sourceContent: ByteArray
        get() = "q /$SOURCE_STATE_NAME gs 0.9 0.9 0.9 rg 20 20 100 100 re f Q\n".toByteArray(Charsets.US_ASCII)

    /**
     * A page with content on it, so the fixture is a real page rather than an empty
     * rectangle — and so a test can tell whether publishing kept it.
     */
    private fun PDDocument.page(label: String): PDPage = PDPage(PDRectangle(612f, 792f)).also { page ->
        val content = "q 0.9 0.9 0.9 rg 20 20 100 100 re f Q\n% $label\n".toByteArray(Charsets.US_ASCII)
        page.cosObject.setItem(
            COSName.CONTENTS,
            PDStream(this, ByteArrayInputStream(content), COSName.FLATE_DECODE).cosObject,
        )
    }

    /**
     * A stroke that is a square, so a test can say exactly where it should land.
     *
     * The centreline is the same square, which makes the `/InkList` half checkable
     * too — and `/InkList` is what Recovered mode reads back, so this is not a
     * convenience, it is the recovery path's fixture.
     */
    fun square(id: String, left: Float, top: Float, side: Float, colorArgb: Int = OPAQUE_BLACK): StrokeOutline {
        val corners = listOf(
            PagePoint(left, top),
            PagePoint(left + side, top),
            PagePoint(left + side, top + side),
            PagePoint(left, top + side),
        )
        return StrokeOutline(
            objectId = ObjectId(id),
            contours = listOf(corners),
            centreline = corners,
            colorArgb = colorArgb,
            widthPt = 2.5f,
        )
    }

    /** A highlighter outline: translucent, and written after the ink it covers (SPEC §3.4). */
    fun highlight(id: String, left: Float, top: Float, side: Float): StrokeOutline =
        square(id, left, top, side, TRANSLUCENT_YELLOW)

    fun plan(
        sourceIndex: Int,
        outlines: List<StrokeOutline>,
        pageId: String = "page-$sourceIndex",
        mode: PageAnnotationMode = PageAnnotationMode.PER_STROKE,
        words: List<InvisibleWord> = emptyList(),
    ) = PdfPagePlan(
        source = PageSource.Existing(sourceIndex),
        pageId = PageId(pageId),
        sortKey = FractionalKey.Initial,
        mode = mode,
        outlines = outlines,
        words = words,
    )

    const val OPAQUE_BLACK: Int = 0xFF101010.toInt()

    /** A highlighter: alpha 0x66, which is what makes it a highlighter (SPEC §3.4). */
    const val TRANSLUCENT_YELLOW: Int = 0x66FFE066

    /** The `/ExtGState` name the inheritance fixtures' own content streams use. */
    const val SOURCE_STATE_NAME: String = "SrcGS"

    /** A4 in points — anything but US Letter, for the reason `sourcePdfWithInheritedAttributes` gives. */
    const val INHERITED_WIDTH_PT: Float = 595f

    const val INHERITED_HEIGHT_PT: Float = 842f
}

/**
 * Stands in for `:android:ink`'s rebuilder, which cannot run off a device —
 * `androidx.ink` tessellates natively.
 *
 * What is being tested here is not the geometry blob but everything around it: that
 * the `/InkList` came back with the right points in the right space, that `/NM`
 * carried the id, and that the colour and width survived. So the "blob" is just the
 * points, encoded readably.
 */
internal class RecordingRebuilder : StrokeRebuilder {

    val rebuilt = mutableListOf<Triple<List<PagePoint>, Int, Float>>()

    override fun rebuild(points: List<PagePoint>, colorArgb: Int, widthPt: Float): StrokeGeometry? {
        if (points.size < 2) return null
        rebuilt += Triple(points, colorArgb, widthPt)
        return StrokeGeometry(points.joinToString(";") { "${it.x},${it.y}" }.toByteArray(Charsets.US_ASCII))
    }
}
