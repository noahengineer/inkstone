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
import dev.inkstone.core.model.ImageCrop
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.ObjectStyle
import dev.inkstone.core.model.PageAnnotationMode
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.StrokeGeometry
import dev.inkstone.core.model.StrokeOutline
import dev.inkstone.core.model.StrokeRebuilder
import dev.inkstone.core.model.TextFont
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

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

    @Suppress("LongParameterList")
    fun plan(
        sourceIndex: Int,
        outlines: List<StrokeOutline>,
        pageId: String = "page-$sourceIndex",
        mode: PageAnnotationMode = PageAnnotationMode.PER_STROKE,
        words: List<InvisibleWord> = emptyList(),
        objects: List<PdfPageObject> = emptyList(),
        // A fixed sheet at the page-space origin, which is what every fixture here is: the
        // published box is then the source page's own and the writer changes nothing about it.
        // `PdfCanvasTest` is where a grown one is passed instead.
        canvas: PageCanvas = PageCanvas.fixed(PageSize.Letter),
    ) = PdfPagePlan(
        source = PageSource.Existing(sourceIndex),
        pageId = PageId(pageId),
        sortKey = FractionalKey.Initial,
        canvas = canvas,
        mode = mode,
        outlines = outlines,
        words = words,
        objects = objects,
    )

    // -----------------------------------------------------------------------
    // M8c — text boxes and images
    // -----------------------------------------------------------------------

    /**
     * A text box at ([leftPt], [topPt]) in page space, sized [widthPt] × [heightPt].
     *
     * Placed through the object's **transform**, not by baking the position into the payload,
     * because that is where a text box's position actually lives (`SetTransform`, absolute, SPEC
     * §4.2) and because a fixture that used the identity everywhere could not tell a correct
     * matrix from a dropped one — CLAUDE.md's M8a lesson about two spaces with the same numbers.
     */
    @Suppress("LongParameterList")
    fun textBox(
        id: String,
        text: String,
        leftPt: Float = 72f,
        topPt: Float = 100f,
        widthPt: Float = 300f,
        heightPt: Float = 120f,
        format: ObjectFormat = ObjectFormat.Default,
        transform: Matrix3 = Matrix3.translate(leftPt, topPt),
        style: ObjectStyle = ObjectStyle(colorArgb = OPAQUE_BLACK, widthPt = 0f),
    ) = PdfTextBox(
        objectId = ObjectId(id),
        text = text,
        widthPt = widthPt,
        heightPt = heightPt,
        transform = transform,
        format = format,
        style = style,
    )

    @Suppress("LongParameterList")
    fun image(
        id: String,
        bytes: ByteArray,
        leftPt: Float = 72f,
        topPt: Float = 300f,
        widthPt: Float = 200f,
        heightPt: Float = 150f,
        crop: ImageCrop? = null,
        opacity: Float = 1f,
        transform: Matrix3 = Matrix3.translate(leftPt, topPt),
    ) = PdfImage(
        objectId = ObjectId(id),
        bytes = bytes,
        widthPt = widthPt,
        heightPt = heightPt,
        transform = transform,
        crop = crop,
        opacity = opacity,
    )

    /** Bytes that only [FakeImageDecoder] understands — a stand-in for a WebP asset. */
    val FAKE_IMAGE_BYTES: ByteArray = "INKSTONE-FAKE-IMAGE".toByteArray(Charsets.US_ASCII)

    /**
     * A JPEG **header**, structurally valid as far as [JpegHeader] reads and no further.
     *
     * ⚠️ **This cannot prove a viewer decodes the image**, and it is not asked to. What the
     * `/DCTDecode` path has to get right is the dictionary — `/Width`, `/Height`, `/ColorSpace`
     * — and that the bytes reach the file *unaltered*, because a PDF reader takes those numbers
     * from the dictionary and not from the stream. Building a genuinely decodable JPEG would need
     * an encoder; the Flate path is where a real, renderable picture is produced, and that is what
     * the dumped file carries.
     */
    fun headerOnlyJpeg(widthPx: Int, heightPx: Int, components: Int = 3): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        // APP0, length 4, so the scan has a segment to step over before the frame.
        out.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x00, 0x04, 0x00, 0x00))
        // SOF0: length 8 + 3*components, precision 8, height, width, component count.
        out.write(byteArrayOf(0xFF.toByte(), 0xC0.toByte()))
        out.write(byteArrayOf(0x00, (8 + 3 * components).toByte(), 0x08))
        out.write(byteArrayOf((heightPx shr 8).toByte(), heightPx.toByte()))
        out.write(byteArrayOf((widthPx shr 8).toByte(), widthPx.toByte()))
        out.write(byteArrayOf(components.toByte()))
        repeat(components) { out.write(byteArrayOf((it + 1).toByte(), 0x11, 0x00)) }
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02))
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

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
 * [PdfFontFaces] over the committed `TestSubset-Regular.ttf`.
 *
 * ⚠️ **Not the production face**, and `CidFontSpikeTest` says why: pdfbox-android's
 * `LiberationSans-Regular.ttf` lives in the AAR's *assets*, which are not on a JVM test's
 * classpath. This is a real 28 KB subset of that very face, cut to one character from each block
 * SPEC §4.9.5 claims coverage of, so the encoding path under test is the production one — but it
 * is a **narrower** face than production, not a wider one, which is the direction CLAUDE.md's
 * "distrust a fixture more generous than production" asks for.
 *
 * It answers for all three families, so the multi-family subsetting path is exercised. Production
 * may well answer null for serif and mono, and [PdfFontFaces.None] is what covers that.
 */
internal object TestFontFaces : PdfFontFaces {

    private val bytes: ByteArray by lazy {
        val url = requireNotNull(TestFontFaces::class.java.classLoader?.getResource(FACE)) {
            "$FACE missing from android/pdf/src/test/resources"
        }
        File(url.toURI()).readBytes()
    }

    override fun face(font: TextFont): ByteArray? = bytes

    private const val FACE = "TestSubset-Regular.ttf"
}

/**
 * A [PdfImageDecoder] that answers for one fixture byte string and nothing else.
 *
 * ⚠️ Deliberately **not** a decoder that accepts anything. `AndroidImageDecoder` answers null for
 * bytes it cannot decode, and a fake that always succeeded would hide every place the writer fails
 * to handle that — which is the ordinary case for a peer's asset this build has no codec for.
 *
 * The picture is **asymmetric on both axes**: red across the top row, blue across the bottom, and
 * a bright first column. A symmetric fixture cannot tell a correctly oriented image from one
 * flipped by [PdfPlacement.unitImageToObject] getting the unit square's `v` direction backwards,
 * which is the one mistake in this area that produces a *plausible* picture.
 */
internal class FakeImageDecoder(
    private val widthPx: Int = 8,
    private val heightPx: Int = 6,
    private val withAlpha: Boolean = false,
) : PdfImageDecoder {

    var decoded: Int = 0
        private set

    override fun decode(bytes: ByteArray): RasterImage? {
        if (!bytes.contentEquals(PdfFixtures.FAKE_IMAGE_BYTES)) return null
        decoded++
        val rgb = ByteArray(widthPx * heightPx * 3)
        for (row in 0 until heightPx) {
            for (column in 0 until widthPx) {
                val at = (row * widthPx + column) * 3
                val top = row < heightPx / 2
                rgb[at] = if (top) LIT else 0
                rgb[at + 1] = if (column == 0) LIT else 0
                rgb[at + 2] = if (top) 0 else LIT
            }
        }
        return RasterImage(widthPx, heightPx, rgb, if (withAlpha) ByteArray(widthPx * heightPx) { LIT } else null)
    }

    private companion object {
        const val LIT: Byte = 0xFF.toByte()
    }
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
