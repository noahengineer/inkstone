package dev.inkstone.android.pdf

import com.tom_roush.fontbox.ttf.TTFParser
import com.tom_roush.fontbox.ttf.TTFSubsetter
import com.tom_roush.fontbox.ttf.TrueTypeFont
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * ⭐ SPEC §4.9.5 — a **subset-embedded CID font**, written at the COS level.
 *
 * ## Why not `PDType0Font`
 *
 * Because it cannot be used here, which `CidFontSpikeTest` asserts rather than asserts *about*:
 *
 * ```
 * java.io.IOException: Error: Could not find referenced cmap stream Identity-H
 *   at com.tom_roush.pdfbox.pdmodel.font.PDType0Font.readEncoding(PDType0Font.java:286)
 * ```
 *
 * `readEncoding` **parses** `Identity-H` rather than merely naming it, and that CMap lives in the
 * AAR's assets, which are not classpath entries. So the whole font stack needs
 * `PDFBoxResourceLoader.init(Context)` — and taking it would move every one of this module's PDF
 * acceptance tests from fast JVM tests to instrumented ones, for a font whose bytes *we supply*.
 *
 * Writing the dictionary by hand is the same answer M4 reached twice already: PdfBox-Android 2.0.x
 * has no `PDAnnotationInk`, so `/Ink` is written at the COS level, and it does not bundle
 * `xmpbox`, so the marker is a raw `/Metadata` stream. The parts of the library that are pure Java
 * — `TTFParser`, `TTFSubsetter` — are used; the part that wants a `Context` is not.
 *
 * ## What actually makes the text extractable
 *
 * ⚠️ **`/ToUnicode` is not decoration and it is not the same thing as the encoding.** With
 * `Identity-H` the content stream holds *glyph indices*, which mean nothing to a text extractor.
 * `pdftotext`, `pdfgrep`, Acrobat's search and Nextcloud's full-text index all recover characters
 * through `/ToUnicode` and nowhere else. Omit it and the file renders perfectly, prints perfectly,
 * and extracts as nothing — which is SPEC §5.5's entire feature failing while every visual check
 * passes. That is this module's documented failure mode (docs/M4-PDF.md), one layer up.
 *
 * ## The identity chain, stated because three things have to agree
 *
 * `/CIDToGIDMap /Identity` means **the code in the content stream is the glyph index in the
 * subset**. So three numbers must be the same number: the code written by [hex], the index
 * `/W` gives a width for, and the key `/ToUnicode` maps back to a character. They are all the
 * *new* glyph id — never the original face's — and [TTFSubsetter.getGIDMap] hands the renumbering
 * over as **new → old**, which `CidFontSpikeTest` pins because getting it backwards produces a
 * page full of plausible, wrong glyphs.
 */
internal class PdfCidFont private constructor(
    /** The `/Font` dictionary, ready to be merged into the **page's** `/Resources` (§6.4). */
    val dictionary: COSDictionary,
    private val cidByCodePoint: Map<Int, Int>,
    private val widthByCid: Map<Int, Int>,
) {

    /** True when the face can draw [codePoint] — SPEC §4.9.5's gate. */
    fun canRepresent(codePoint: Int): Boolean = cidByCodePoint.containsKey(codePoint)

    /**
     * [text] as hex digits for `<…> Tj`.
     *
     * ⚠️ **A hex string, never a literal.** A CID code is two bytes and those bytes are freely
     * `0x00`, `0x28` and `0x29` — an unbalanced `(` or a premature `)` inside a `(…)` literal
     * terminates the string early and corrupts the rest of the content stream. There is no
     * escaping scheme in this module and there should not be one; hex has no delimiters to escape.
     */
    fun hex(text: String): String {
        val builder = StringBuilder(text.length * HEX_DIGITS_PER_CID)
        forEachCid(text) { cid -> appendHex(builder, cid) }
        return builder.toString()
    }

    /**
     * The advance of [text] at font size 1, in text space.
     *
     * Real metrics, not the ~0.5 em average the invisible layer assumes for Helvetica. A visible
     * text box has to wrap and align against the width a viewer will actually draw, and a guess
     * there is a line that overflows its box on screen and in the file.
     */
    fun widthEm(text: String): Float {
        var total = 0
        forEachCid(text) { cid -> total += widthByCid[cid] ?: 0 }
        return total / GLYPH_SPACE.toFloat()
    }

    private inline fun forEachCid(text: String, action: (Int) -> Unit) {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            // A code point the face cannot draw is SKIPPED, never replaced by .notdef. A .notdef
            // is a visible box in the PDF and nothing in the extraction; a lookalike substitution
            // puts the wrong word into search. The caller is expected to have refused the string
            // already — see `canRepresent` — so reaching here means a bug upstream, and dropping
            // is the least-wrong thing to do with one.
            val cid = cidByCodePoint[codePoint] ?: continue
            action(cid)
        }
    }

    companion object {

        /** `/W` is in 1/1000 em, whatever the face's own units are. */
        private const val GLYPH_SPACE = 1000
        private const val HEX_DIGITS_PER_CID = 4
        private const val HEX_RADIX = 16
        private const val SUBSET_TAG_LENGTH = 6
        private const val FLAG_NONSYMBOLIC = 32
        private const val DEFAULT_STEM_V = 80
        private const val DEFAULT_WIDTH = 1000

        /**
         * Build a font carrying exactly [codePoints] and nothing else.
         *
         * The subset is the point: embedding the whole face would add ~410 KB to **every published
         * PDF that contains a text box**, and those files sync. A page of ordinary prose needs
         * perhaps sixty glyphs.
         *
         * Returns null when the face cannot be parsed at all, which is a broken asset rather than
         * a user's problem — the caller publishes without a text layer rather than not publishing.
         */
        fun subsetFrom(document: PDDocument, faceBytes: ByteArray, codePoints: Set<Int>): PdfCidFont? {
            if (codePoints.isEmpty()) return null
            val face = runCatching { TTFParser().parse(ByteArrayInputStream(faceBytes)) }.getOrNull() ?: return null
            return face.use { build(document, it, codePoints) }
        }

        private fun build(document: PDDocument, face: TrueTypeFont, codePoints: Set<Int>): PdfCidFont? {
            val cmap = face.unicodeCmap ?: return null
            val subsetter = TTFSubsetter(face)
            // Only what the face can actually draw. Handing the subsetter a code point with no
            // glyph would either fail or silently produce .notdef, and both are worse than the
            // caller being told (`canRepresent`) that the character is out of coverage.
            val drawable = codePoints.filter { cmap.getGlyphId(it) > 0 }
            if (drawable.isEmpty()) return null
            for (codePoint in drawable) subsetter.add(codePoint)

            val subsetBytes = ByteArrayOutputStream().also { subsetter.writeToStream(it) }.toByteArray()
            // new → old, pinned by CidFontSpikeTest. Inverted here once so that every use below
            // reads in the direction the PDF needs.
            val newByOld = subsetter.gidMap.entries.associate { (new, old) -> old to new }

            val scale = GLYPH_SPACE.toDouble() / face.unitsPerEm
            val cidByCodePoint = LinkedHashMap<Int, Int>(drawable.size)
            val widthByCid = LinkedHashMap<Int, Int>(drawable.size)
            for (codePoint in drawable) {
                val oldGid = cmap.getGlyphId(codePoint)
                val cid = newByOld[oldGid] ?: continue
                cidByCodePoint[codePoint] = cid
                widthByCid[cid] = (face.getAdvanceWidth(oldGid) * scale).toInt()
            }
            if (cidByCodePoint.isEmpty()) return null

            val tag = subsetTag(cidByCodePoint.keys)
            val baseFont = COSName.getPDFName("$tag+${face.name.orEmpty().ifEmpty { "InkstoneFace" }}")
            val descriptor = fontDescriptor(document, face, baseFont, subsetBytes, scale)
            val descendant = descendantFont(baseFont, descriptor, widthByCid)
            val toUnicode = flate(document, ToUnicodeCMap.build(cidByCodePoint))

            val dictionary = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.FONT)
                setItem(COSName.SUBTYPE, COSName.getPDFName("Type0"))
                setItem(COSName.getPDFName("BaseFont"), baseFont)
                setItem(COSName.getPDFName("Encoding"), COSName.getPDFName("Identity-H"))
                setItem(COSName.getPDFName("DescendantFonts"), COSArray().apply { add(descendant) })
                setItem(COSName.getPDFName("ToUnicode"), toUnicode)
                markUpdated(this)
            }
            return PdfCidFont(dictionary, cidByCodePoint, widthByCid)
        }

        private fun descendantFont(
            baseFont: COSName,
            descriptor: COSDictionary,
            widthByCid: Map<Int, Int>,
        ): COSDictionary = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT)
            setItem(COSName.SUBTYPE, COSName.getPDFName("CIDFontType2"))
            setItem(COSName.getPDFName("BaseFont"), baseFont)
            setItem(COSName.getPDFName("CIDSystemInfo"), cidSystemInfo())
            setItem(COSName.getPDFName("FontDescriptor"), descriptor)
            setItem(COSName.getPDFName("DW"), COSInteger.get(DEFAULT_WIDTH.toLong()))
            setItem(COSName.getPDFName("W"), widthArray(widthByCid))
            // Identity, so the code in the content stream IS the glyph index. The alternative is a
            // stream mapping one to the other, which is a second thing that can disagree.
            setItem(COSName.getPDFName("CIDToGIDMap"), COSName.getPDFName("Identity"))
            markUpdated(this)
        }

        private fun cidSystemInfo(): COSDictionary = COSDictionary().apply {
            setItem(COSName.getPDFName("Registry"), COSString("Adobe"))
            setItem(COSName.getPDFName("Ordering"), COSString("Identity"))
            setItem(COSName.getPDFName("Supplement"), COSInteger.ZERO)
            markUpdated(this)
        }

        /**
         * `/W` as `[ cid [w] cid [w] … ]`.
         *
         * The consecutive-range form would be shorter, and it is not worth it: a subset's glyph
         * ids are dense but their *widths* are not, so the range form would mostly emit
         * single-element ranges anyway, and this form cannot be got subtly wrong.
         */
        private fun widthArray(widthByCid: Map<Int, Int>): COSArray = COSArray().apply {
            for (cid in widthByCid.keys.sorted()) {
                add(COSInteger.get(cid.toLong()))
                add(COSArray().apply { add(COSInteger.get((widthByCid[cid] ?: 0).toLong())) })
            }
            markUpdated(this)
        }

        private fun fontDescriptor(
            document: PDDocument,
            face: TrueTypeFont,
            baseFont: COSName,
            subsetBytes: ByteArray,
            scale: Double,
        ): COSDictionary {
            val header = face.header
            val horizontal = face.horizontalHeader
            val fontFile = flate(document, subsetBytes).apply {
                // Required for a /FontFile2 by PDF 32000-1 §9.9 Table 127. Some readers tolerate
                // its absence; a stricter one refuses the font and falls back to a default face,
                // which is the "why is my PDF suddenly Times" bug.
                setLong(COSName.LENGTH1, subsetBytes.size.toLong())
            }
            return COSDictionary().apply {
                setItem(COSName.TYPE, COSName.getPDFName("FontDescriptor"))
                setItem(COSName.getPDFName("FontName"), baseFont)
                // Nonsymbolic: the face is Latin/Greek/Cyrillic text, not a dingbat set.
                setInt(COSName.FLAGS, FLAG_NONSYMBOLIC)
                setItem(COSName.getPDFName("FontBBox"), bbox(header, scale))
                setInt(COSName.getPDFName("ItalicAngle"), 0)
                setInt(COSName.getPDFName("Ascent"), (horizontal.ascender * scale).toInt())
                setInt(COSName.getPDFName("Descent"), (horizontal.descender * scale).toInt())
                setInt(
                    COSName.getPDFName("CapHeight"),
                    runCatching { (face.oS2Windows.capHeight * scale).toInt() }
                        .getOrNull()
                        ?.takeIf { it > 0 }
                        ?: (horizontal.ascender * scale).toInt(),
                )
                // Required, and no reader is known to use it for a TrueType face with an embedded
                // file. A plausible constant beats an absent required key.
                setInt(COSName.getPDFName("StemV"), DEFAULT_STEM_V)
                setItem(COSName.getPDFName("FontFile2"), fontFile)
                markUpdated(this)
            }
        }

        private fun bbox(header: com.tom_roush.fontbox.ttf.HeaderTable, scale: Double): COSArray = COSArray().apply {
            add(COSInteger.get((header.xMin * scale).toLong()))
            add(COSInteger.get((header.yMin * scale).toLong()))
            add(COSInteger.get((header.xMax * scale).toLong()))
            add(COSInteger.get((header.yMax * scale).toLong()))
            markUpdated(this)
        }

        private fun flate(document: PDDocument, bytes: ByteArray) = com.tom_roush.pdfbox.pdmodel.common.PDStream(
            document,
            ByteArrayInputStream(bytes),
            COSName.FLATE_DECODE,
        ).cosObject.also { markUpdated(it) }

        /**
         * The six-letter subset tag PDF 32000-1 §9.6.4 requires on an embedded subset.
         *
         * Derived from the code points rather than random, so the same text produces the same
         * bytes — this module's output is compared byte-for-byte in `PdfPublishTest`, and a
         * random tag would make a rebuild look like a change and enqueue an upload.
         */
        private fun subsetTag(codePoints: Collection<Int>): String {
            var hash = codePoints.sorted().fold(HASH_SEED) { acc, cp -> acc * HASH_PRIME + cp }
            val builder = StringBuilder(SUBSET_TAG_LENGTH)
            repeat(SUBSET_TAG_LENGTH) {
                builder.append('A' + ((hash % LETTERS).toInt().let { if (it < 0) it + LETTERS else it }))
                hash /= LETTERS
            }
            return builder.toString()
        }

        private const val HASH_SEED = 1469598103934665603L
        private const val HASH_PRIME = 1099511628211L
        private const val LETTERS = 26

        private fun appendHex(builder: StringBuilder, cid: Int) {
            val hex = cid.toString(HEX_RADIX)
            repeat(HEX_DIGITS_PER_CID - hex.length) { builder.append('0') }
            builder.append(hex)
        }
    }
}

/**
 * The `/ToUnicode` CMap — **the thing that makes `pdfgrep` work**, and the thing whose absence is
 * invisible in every visual check.
 *
 * Written as a byte stream rather than through any helper, for the same reason the font dictionary
 * is: `ToUnicodeWriter` is reachable only through the font stack that needs a `Context`.
 */
internal object ToUnicodeCMap {

    private const val CHUNK = 100

    fun build(cidByCodePoint: Map<Int, Int>): ByteArray {
        val entries = cidByCodePoint.entries.sortedBy { it.value }
        val text = StringBuilder()
        text.append(HEADER)
        // PDF 32000-1 §9.10.3: at most 100 entries per `beginbfchar` block. Exceeding it is
        // accepted by lenient readers and rejected by strict ones, which is the worst kind of
        // limit — it works on the machine you tested on.
        for (chunk in entries.chunked(CHUNK)) {
            text.append(chunk.size).append(" beginbfchar\n")
            for ((codePoint, cid) in chunk) {
                text.append('<').append(hex4(cid)).append("> <").append(utf16Hex(codePoint)).append(">\n")
            }
            text.append("endbfchar\n")
        }
        text.append(FOOTER)
        return text.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun hex4(value: Int): String = value.toString(HEX_RADIX).padStart(HEX_WIDTH, '0').uppercase()

    /**
     * The **destination** is UTF-16BE, so anything outside the BMP is a surrogate pair and is two
     * code units here — not one. Writing the scalar value directly would silently produce a
     * different character for every emoji and every rarer CJK ideograph.
     */
    private fun utf16Hex(codePoint: Int): String = buildString {
        for (unit in String(Character.toChars(codePoint)).toCharArray()) append(hex4(unit.code))
    }

    private const val HEX_RADIX = 16
    private const val HEX_WIDTH = 4

    private val HEADER = """
        /CIDInit /ProcSet findresource begin
        12 dict begin
        begincmap
        /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
        /CMapName /Adobe-Identity-UCS def
        /CMapType 2 def
        1 begincodespacerange
        <0000> <FFFF>
        endcodespacerange

    """.trimIndent() + "\n"

    private val FOOTER = """
        endcmap
        CMapName currentdict /CMap defineresource pop
        end
        end
    """.trimIndent() + "\n"
}
