package dev.inkstone.android.pdf

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import dev.inkstone.core.model.ListStyle
import dev.inkstone.core.model.PdfPageGeometry
import dev.inkstone.core.model.TextFont

/**
 * ⭐ M8c — a page's text boxes and images, turned into content **and the resources that content
 * names**.
 *
 * ## Why this is a type of its own
 *
 * Every name a page content stream uses has to be defined in the *page's* `/Resources` (SPEC
 * §4.9.5, §6.4, and the M4 defect [PdfResources] exists to prevent). So writing a text box is
 * never one thing: it is a stream that says `/InkstoneT0 Tf` **and** a font dictionary that
 * defines `/InkstoneT0`, and the two are produced at the same moment or they disagree. Returning
 * them together as [PageObjects] makes "emitted content that names an undefined resource"
 * unrepresentable rather than merely tested for.
 *
 * ## The font is subset **per page, per family**
 *
 * SPEC §4.9.5's subset is the point: the whole Liberation Sans face is 410,712 bytes, and a
 * published note syncs. One subset per family per page covers every text box on it with the glyphs
 * they actually use — perhaps sixty for a page of prose. Per *box* would embed the same 'e' as
 * many times as there are boxes; per *document* would need one pass over every page before
 * writing any of them, which the incremental writer is not shaped for.
 */
internal class PdfPageObjects(private val faces: PdfFontFaces, private val decoder: PdfImageDecoder) {

    /**
     * @return the content pieces in **z order** (SPEC §4.3), and the three resource categories
     *   they name. Empty content and empty dictionaries when the page carries no objects.
     */
    fun write(doc: PDDocument, plan: PdfPagePlan, geometry: PdfPageGeometry): PageObjects {
        if (plan.objects.isEmpty()) return PageObjects.Empty

        val fonts = COSDictionary()
        val xObjects = COSDictionary()
        val states = COSDictionary()
        val byFamily = fontsFor(doc, plan, fonts)
        // Keyed on the byte array *instance*, which is what deduplicates: the materializer reads
        // the bundle's assets once, so two ImageObjects naming one asset share one array and
        // therefore one XObject. An unrelated caller handing over two equal-but-distinct arrays
        // gets two copies, which is correct and merely larger.
        val written = HashMap<ByteArray, String>()

        val content = mutableListOf<ByteArray>()
        for (obj in plan.objects) {
            when (obj) {
                is PdfTextBox -> content += textContent(obj, geometry, byFamily, states)
                is PdfImage -> content += imageContent(doc, obj, geometry, xObjects, states, written)
            }
        }
        return PageObjects(content.filter { it.isNotEmpty() }, fonts, xObjects, states)
    }

    // -----------------------------------------------------------------------

    private fun textContent(
        box: PdfTextBox,
        geometry: PdfPageGeometry,
        byFamily: Map<TextFont, PdfPageFont>,
        states: COSDictionary,
    ): ByteArray {
        val font = byFamily[box.format.font] ?: return ByteArray(0)
        return PdfObjectContent.textBox(box, geometry, font, stateFor(box.alpha, states))
    }

    private fun imageContent(
        doc: PDDocument,
        image: PdfImage,
        geometry: PdfPageGeometry,
        xObjects: COSDictionary,
        states: COSDictionary,
        written: MutableMap<ByteArray, String>,
    ): ByteArray {
        val name = written.getOrPut(image.bytes) {
            val stream: COSStream = PdfImages.xObject(doc, image.bytes, decoder) ?: return ByteArray(0)
            val fresh = "$IMAGE_PREFIX${xObjects.keySet().size}"
            xObjects.setItem(COSName.getPDFName(fresh), stream)
            markUpdated(xObjects)
            fresh
        }
        return PdfObjectContent.image(image, geometry, name, stateFor(image.alpha, states))
    }

    /**
     * The `/ExtGState` name for [alpha], defining it on the way past — or null when it is opaque.
     *
     * Null rather than a `GS255` entry, because an opaque object needs no graphics state at all
     * and every `gs` operator is one more thing a viewer parses on every draw. The states are
     * shared by value ([PdfContent.stateName]), so a page of translucent highlighting and
     * translucent pictures at the same alpha defines one entry.
     */
    private fun stateFor(alpha: Int, states: COSDictionary): String? {
        if (alpha >= OPAQUE) return null
        val name = PdfContent.stateName(alpha)
        if (states.getDictionaryObject(name) == null) {
            val value = alpha / OPAQUE.toFloat()
            states.setItem(
                COSName.getPDFName(name),
                COSDictionary().apply {
                    setFloat(COSName.CA, value)
                    setFloat(COSName.CA_NS, value)
                    markUpdated(this)
                },
            )
            markUpdated(states)
        }
        return name
    }

    /**
     * One [PdfPageFont] per [TextFont] the page uses, with its dictionary written into [fonts].
     *
     * ⚠️ **The subset is built from the code points that will actually be drawn, list markers
     * included.** Leaving the bullet out would make `TextBoxLayout` ask `canRepresent('•')`, get
     * false, and silently fall back to a hyphen in a file whose font could have carried it — a
     * degrade caused by the writer rather than by the face.
     */
    private fun fontsFor(doc: PDDocument, plan: PdfPagePlan, fonts: COSDictionary): Map<TextFont, PdfPageFont> {
        val used = LinkedHashMap<TextFont, MutableSet<Int>>()
        for (box in plan.objects.filterIsInstance<PdfTextBox>()) {
            val points = used.getOrPut(box.format.font) { linkedSetOf() }
            box.text.codePoints().forEach { if (it != '\n'.code) points += it }
            if (box.format.listStyle != ListStyle.NONE) points += MARKER_CODE_POINTS
        }
        return used.entries.withIndex().associate { (index, entry) ->
            entry.key to font(doc, entry.key, entry.value, "$FONT_PREFIX$index", fonts)
        }
    }

    private fun font(
        doc: PDDocument,
        family: TextFont,
        codePoints: Set<Int>,
        name: String,
        fonts: COSDictionary,
    ): PdfPageFont {
        val embedded = faces.face(family)?.let { PdfCidFont.subsetFrom(doc, it, codePoints) }
        if (embedded != null) {
            fonts.setItem(COSName.getPDFName(name), embedded.dictionary)
            markUpdated(fonts)
            return PdfPageFont.Embedded(name, embedded)
        }
        // The documented degrade, not a failure: printable ASCII in a base font, no embedding.
        // See PdfFontFaces on why publishing nothing would be the worse answer.
        val standard = PdfPageFont.standardFor(name, family)
        fonts.setItem(COSName.getPDFName(name), baseFontDictionary(standard.baseFont))
        markUpdated(fonts)
        return standard
    }

    private companion object {

        /** Distinct from `InkstoneF0`, the invisible layer's font — one page can define both. */
        const val FONT_PREFIX = "InkstoneT"
        const val IMAGE_PREFIX = "InkstoneIm"
        const val OPAQUE = 0xFF

        /**
         * Everything a list marker can need: the bullet, its ASCII stand-in, the digits, a full
         * stop and the space that follows.
         */
        val MARKER_CODE_POINTS: Set<Int> =
            (('0'..'9').map { it.code } + listOf('•'.code, '-'.code, '.'.code, ' '.code)).toSet()
    }
}

/**
 * What one page's objects produced: content to append, and the resources it names.
 *
 * The dictionaries are merged into the **page's** `/Resources` by the caller through
 * [PdfResources.addToPage], never onto the streams — see [PdfResources] for what that cost in M4.
 */
internal class PageObjects(
    val content: List<ByteArray>,
    val fonts: COSDictionary,
    val xObjects: COSDictionary,
    val states: COSDictionary,
) {
    companion object {
        val Empty: PageObjects = PageObjects(emptyList(), COSDictionary(), COSDictionary(), COSDictionary())
    }
}

/** A standard-14 base font, named and not embedded. WinAnsi because the text is ASCII either way. */
internal fun baseFontDictionary(baseFont: String): COSDictionary = COSDictionary().apply {
    setItem(COSName.TYPE, COSName.FONT)
    setItem(COSName.SUBTYPE, COSName.getPDFName("Type1"))
    setItem(COSName.getPDFName("BaseFont"), COSName.getPDFName(baseFont))
    setItem(COSName.getPDFName("Encoding"), COSName.getPDFName("WinAnsiEncoding"))
    markUpdated(this)
}
