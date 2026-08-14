package dev.inkstone.android.pdf

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import java.io.ByteArrayInputStream

/**
 * ⭐ An inserted image as a PDF `/XObject /Image` — SPEC §4.9.4's bytes, arriving from the
 * bundle's `assets/` family.
 *
 * ## Two filters, and the choice is not a tuning knob
 *
 * | Source | Filter | Why |
 * |---|---|---|
 * | baseline JPEG | `/DCTDecode`, bytes **untouched** | PDF's DCT filter *is* JPEG. Decoding and re-encoding a photograph as Flate samples multiplies its size by roughly five, in a file that syncs on every save |
 * | anything else | `/FlateDecode`, 8-bit RGB samples | there is no WebP filter in PDF and `/FlateDecode` is PNG's *compression*, not its container |
 *
 * ⚠️ **Only baseline and extended-sequential JPEG is passed through.** `/DCTDecode` is defined
 * against the baseline process; a progressive JPEG parses here, states its size correctly, and
 * renders as nothing in a strict reader. It falls to the decoder instead, which costs bytes and
 * always works — the same trade M4 took everywhere: an honest larger file over a smaller one that
 * is blank in someone else's viewer.
 *
 * ## Every object goes through [markUpdated], including the parent chain
 *
 * An unmarked stream is simply **absent** from an incremental update, with no error (see
 * [PdfBoxWriter]). An absent image XObject leaves a `/Do` naming nothing — a lenient reader draws
 * a blank, a strict one abandons the rest of the page's content stream, which is the ink.
 */
internal object PdfImages {

    /**
     * @return the XObject, or null when this build cannot turn [bytes] into pixels. Null costs one
     *   picture; it never costs the publish (see [PdfImageDecoder]).
     */
    fun xObject(doc: PDDocument, bytes: ByteArray, decoder: PdfImageDecoder): COSStream? {
        if (bytes.isEmpty()) return null
        return passThroughJpeg(doc, bytes) ?: flateRaster(doc, bytes, decoder)
    }

    // -----------------------------------------------------------------------

    private fun passThroughJpeg(doc: PDDocument, bytes: ByteArray): COSStream? {
        val jpeg = JpegHeader.read(bytes) ?: return null
        val colorSpace = when (jpeg.components) {
            GRAY_COMPONENTS -> COSName.DEVICEGRAY

            RGB_COMPONENTS -> COSName.DEVICERGB

            // YCCK/CMYK needs an Adobe APP14 transform and often an inverted /Decode. Getting that
            // wrong produces a photographic negative, which is a striking way to publish a lecture.
            else -> return null
        }
        // A raw output stream, because the bytes are *already* DCT-encoded. `PDStream(doc, in,
        // filter)` would try to encode them again, and PDFBox's DCT filter has no encoder — the
        // path that looks symmetrical with the Flate one below is the one that throws.
        val stream = doc.document.createCOSStream()
        stream.createRawOutputStream().use { it.write(bytes) }
        stream.setItem(COSName.FILTER, COSName.DCT_DECODE)
        return stream.apply {
            describe(jpeg.width, jpeg.height, colorSpace)
            markUpdated(this)
        }
    }

    private fun flateRaster(doc: PDDocument, bytes: ByteArray, decoder: PdfImageDecoder): COSStream? {
        val raster = decoder.decode(bytes) ?: return null
        if (raster.width <= 0 || raster.height <= 0) return null
        val stream = PDStream(doc, ByteArrayInputStream(raster.rgb), COSName.FLATE_DECODE).cosObject
        stream.describe(raster.width, raster.height, COSName.DEVICERGB)
        raster.alpha?.let { stream.setItem(COSName.SMASK, softMask(doc, raster.width, raster.height, it)) }
        markUpdated(stream)
        return stream
    }

    /**
     * The `/SMask` — one grey sample per pixel, which is how a PDF says "transparent here".
     *
     * Not `/Mask`: that is a stencil, one *bit* per pixel, so a photograph cut out with a soft edge
     * would come back with a jagged one. An inserted PNG screenshot with a rounded corner is the
     * ordinary case and it has exactly that soft edge.
     */
    private fun softMask(doc: PDDocument, width: Int, height: Int, alpha: ByteArray): COSStream {
        val stream = PDStream(doc, ByteArrayInputStream(alpha), COSName.FLATE_DECODE).cosObject
        stream.describe(width, height, COSName.DEVICEGRAY)
        markUpdated(stream)
        return stream
    }

    private fun COSStream.describe(width: Int, height: Int, colorSpace: COSName) {
        setItem(COSName.TYPE, COSName.XOBJECT)
        setItem(COSName.SUBTYPE, COSName.IMAGE)
        setInt(COSName.WIDTH, width)
        setInt(COSName.HEIGHT, height)
        setItem(COSName.COLORSPACE, colorSpace)
        setInt(COSName.BITS_PER_COMPONENT, BITS_PER_COMPONENT)
    }

    private const val BITS_PER_COMPONENT = 8
    private const val GRAY_COMPONENTS = 1
    private const val RGB_COMPONENTS = 3
}

/** What a JPEG's start-of-frame marker says about it. */
internal data class JpegFrame(val width: Int, val height: Int, val components: Int)

/**
 * Just enough JPEG to answer *"may these bytes go straight into `/DCTDecode`, and how big are
 * they"*.
 *
 * A PDF image XObject must declare `/Width`, `/Height` and `/ColorSpace` **itself** — the reader is
 * not required to look inside the stream — so passing a JPEG through without parsing its frame
 * header is not an option. Two numbers, from one marker.
 *
 * ⚠️ **Segment lengths include their own two bytes and the entropy-coded data after `SOS` is not
 * a segment.** Walking past `SOS` by segment length lands in compressed data that contains
 * `0xFF 0xC0` often enough to look like a frame header, and the writer would then publish an image
 * declaring a size out of a random byte pair. Every frame marker precedes `SOS` in a conforming
 * file, so the scan stops there.
 */
internal object JpegHeader {

    fun read(bytes: ByteArray): JpegFrame? {
        // One guard, and the short circuit is load-bearing: `u8(0)` on a two-byte array throws.
        if (bytes.size < MIN_LENGTH || bytes.u8(0) != MARKER || bytes.u8(1) != SOI) return null

        var index = 2
        while (index + SEGMENT_HEADER < bytes.size) {
            if (bytes.u8(index) != MARKER) return null
            val marker = bytes.u8(index + 1)
            if (marker in BASELINE_FRAMES) return frameAt(bytes, index)
            val next = skip(bytes, index, marker)
            if (next < 0) return null
            index = next
        }
        return null
    }

    /**
     * Where the segment at [index] ends, or a negative to stop scanning.
     *
     * ⚠️ **Stopping at `SOS` is the point.** Everything after it is entropy-coded data, which
     * contains the byte pair `0xFF 0xC0` often enough to look like a frame header — and the writer
     * would then publish an image whose `/Width` came out of compressed pixels.
     */
    private fun skip(bytes: ByteArray, index: Int, marker: Int): Int = when {
        // Padding: any number of 0xFF bytes may precede a marker.
        marker == MARKER -> index + 1

        marker == SOS || marker == EOI -> -1

        marker in STANDALONE -> index + 2

        else -> bytes.u16(index + 2).let { if (it < 2) -1 else index + 2 + it }
    }

    private fun frameAt(bytes: ByteArray, index: Int): JpegFrame? {
        // length(2) precision(1) height(2) width(2) components(1)
        if (index + FRAME_LENGTH >= bytes.size) return null
        val height = bytes.u16(index + FRAME_HEIGHT)
        val width = bytes.u16(index + FRAME_WIDTH)
        val components = bytes.u8(index + FRAME_COMPONENTS)
        if (width <= 0 || height <= 0) return null
        return JpegFrame(width, height, components)
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and BYTE_MASK

    private fun ByteArray.u16(index: Int): Int = (u8(index) shl BYTE_BITS) or u8(index + 1)

    private const val BYTE_MASK = 0xFF
    private const val BYTE_BITS = 8
    private const val MARKER = 0xFF
    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val MIN_LENGTH = 4
    private const val SEGMENT_HEADER = 3
    private const val FRAME_LENGTH = 9
    private const val FRAME_HEIGHT = 5
    private const val FRAME_WIDTH = 7
    private const val FRAME_COMPONENTS = 9

    /** `SOF0` and `SOF1`. Progressive (`SOF2`) and the arithmetic variants deliberately are not. */
    private val BASELINE_FRAMES = setOf(0xC0, 0xC1)

    /** Markers with no length field: `TEM` and the restart markers, plus `SOI`. */
    private val STANDALONE = setOf(0x01, 0xD0, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8)
}
