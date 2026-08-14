package dev.inkstone.android.pdf

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument

/**
 * ⭐ **A text extractor that is not the writer** — the check docs/M4-PDF.md says this module keeps
 * failing to make.
 *
 * M4's PDFs were valid, parseable and blank because they were only ever re-parsed by PdfBox, the
 * library that wrote them. The same trap is open here twice over, and worse, because the two
 * halves of a text feature **fail independently** (docs/M8-TEXT.md §3.5):
 *
 * | Half | What it needs | What its absence looks like |
 * |---|---|---|
 * | rendering | the font resolvable in the **page's** `/Resources` | an undefined name; a blank page or a default face |
 * | extraction | a correct `/ToUnicode` CMap | perfect on screen, nothing in `pdfgrep` |
 *
 * So this walks the file the way a *text extractor* does and nowhere near the way the writer does:
 * find the show-text operators, read the current font's `/ToUnicode`, and map the codes back to
 * characters. It never asks [PdfCidFont] anything. If the CMap is missing, wrong, or reversed —
 * the M8c mistake `CidFontSpikeTest` pins the direction of — the string that comes back is not the
 * string that went in.
 *
 * ⚠️ It is still **not poppler**. `pdftotext` and PyMuPDF are the external gate, and the dump
 * tests exist to feed them. What this buys is that the gate is not the only thing standing between
 * a regression and a device.
 */
internal object PdfTextExtract {

    /**
     * Everything shown by a `Tj` on [pageIndex], in stream order, one entry per operator.
     *
     * Called **inside** the document's lifetime: PdfBox streams read lazily from the source, so a
     * `/ToUnicode` fetched after the document closed throws *"COSStream has been closed"*.
     */
    fun shown(document: PDDocument, pageIndex: Int): List<String> {
        val page = document.getPage(pageIndex)
        val fonts = ContentScan.effectiveResources(page)?.getDictionaryObject(COSName.FONT) as? COSDictionary
        val content = page.contents.readBytes().toString(Charsets.ISO_8859_1)

        val out = mutableListOf<String>()
        var cmap: Map<Int, String> = emptyMap()
        for (match in SHOW.findAll(content)) {
            val (selected, hex, literal) = match.destructured
            when {
                selected.isNotEmpty() -> cmap = toUnicodeOf(fonts, selected)
                hex.isNotEmpty() -> out += decodeHex(hex, cmap)
                else -> out += unescape(literal)
            }
        }
        return out
    }

    /** The page's whole shown text, one line per `Tj`. Convenience for an assertion on a paragraph. */
    fun text(document: PDDocument, pageIndex: Int): String = shown(document, pageIndex).joinToString("\n")

    // -----------------------------------------------------------------------

    /**
     * `Tf`, `<…> Tj` and `(…) Tj`, in one pass so the current font is always the one in force.
     *
     * A separate regex per operator would return them in two independent orders, and a page with
     * a Cyrillic box and an ASCII box would then decode one of them with the other's CMap — which
     * produces plausible words, in the wrong alphabet, in a test that is checking alphabets.
     */
    private val SHOW = Regex(
        """/(\w+)\s+[-\d.]+\s+Tf|<([0-9A-Fa-f]*)>\s*Tj|\(((?:\\.|[^()\\])*)\)\s*Tj""",
    )

    /** `<XXXX> <YYYY…>` pairs, read only from the `bfchar` region so the codespace range is not one. */
    private fun toUnicodeOf(fonts: COSDictionary?, name: String): Map<Int, String> {
        val font = fonts?.getDictionaryObject(COSName.getPDFName(name)) as? COSDictionary ?: return emptyMap()
        val stream = font.getDictionaryObject(COSName.getPDFName("ToUnicode")) as? COSStream ?: return emptyMap()
        val text = stream.createInputStream().use { it.readBytes() }.decodeToString()
        val body = text.substringAfter("beginbfchar", "")
        return PAIR.findAll(body).associate { match ->
            val code = match.groupValues[1].toInt(RADIX)
            code to match.groupValues[2].chunked(UTF16_DIGITS).map { it.toInt(RADIX).toChar() }.joinToString("")
        }
    }

    private val PAIR = Regex("""<([0-9A-Fa-f]{4})>\s*<([0-9A-Fa-f]+)>""")

    /**
     * Two-byte codes through the CMap.
     *
     * A code with no `/ToUnicode` entry is reported as `�`, not skipped: *"the glyph is there
     * and the file cannot say what it is"* is precisely the failure this file exists to detect, and
     * dropping it would make it read as text the writer never emitted.
     */
    private fun decodeHex(hex: String, cmap: Map<Int, String>): String =
        hex.chunked(CID_DIGITS).joinToString("") { cmap[it.toInt(RADIX)] ?: "�" }

    private fun unescape(literal: String): String = literal
        .replace("\\(", "(")
        .replace("\\)", ")")
        .replace("\\\\", "\\")

    private const val RADIX = 16
    private const val CID_DIGITS = 4
    private const val UTF16_DIGITS = 4
}
