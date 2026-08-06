package dev.inkstone.android.pdf

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
