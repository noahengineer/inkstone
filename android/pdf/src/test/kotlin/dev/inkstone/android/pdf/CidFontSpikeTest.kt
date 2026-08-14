package dev.inkstone.android.pdf

import com.tom_roush.fontbox.ttf.TTFParser
import com.tom_roush.fontbox.ttf.TTFSubsetter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ROADMAP M8c — **the font spike.** SPEC §4.9.5 books a subset-embedded CID font; this asks the
 * one question that decides whether the whole design is affordable in this module.
 *
 * ## The question
 *
 * `:android:pdf`'s acceptance tests are plain JVM unit tests, which is only possible because
 * `unitTests.isReturnDefaultValues = true` stubs the single Android class the *structural* half of
 * PdfBox-Android touches (`android.util.Log`) — see the module's `build.gradle.kts` and
 * docs/M4-PDF.md §4. `PdfContent` was written by hand specifically to keep the font machinery out,
 * because PdfBox-Android's standard-14 fonts come out of the AAR's **assets** through
 * `PDFBoxResourceLoader`, which needs a `Context` that a JVM test does not have.
 *
 * A CID font is a different proposition: the TTF is supplied as an `InputStream` by the caller, so
 * nothing has to be *found*. But `PDType0Font.load` also builds a descendant font, a
 * `/CIDToGIDMap`, a `/W` array and a `/ToUnicode` CMap — and if any of that reaches for a bundled
 * resource, taking the font stack would silently move every PDF acceptance test to instrumented.
 *
 * **This test is the evidence for that decision, and it must keep running**, because the answer is
 * a property of the dependency and not of our code: a PdfBox-Android upgrade could change it.
 *
 * ## Why the font here is not the font in production
 *
 * Production embeds `LiberationSans-Regular.ttf` out of the pdfbox-android AAR's own assets — no
 * new APK asset at all (SPEC §4.9.5). That file is not on a JVM test's classpath, because AAR
 * assets are not classpath entries, so the committed `TestSubset-Regular.ttf` (28 KB, 109 glyphs)
 * stands in. It is a real subset of the very same face, cut to one character from each block M8c
 * must prove it can carry, so the encoding path under test is the production one.
 *
 * ⚠️ That difference is exactly the kind of seam CLAUDE.md's definition-of-done #6 is about: this
 * test cannot prove the *production* stream opens. Only the device walk does.
 */
class CidFontSpikeTest {

    private fun testFont(): File {
        val url = requireNotNull(javaClass.classLoader?.getResource(TEST_FONT)) {
            "$TEST_FONT missing from android/pdf/src/test/resources"
        }
        return File(url.toURI())
    }

    /**
     * ⭐ **The answer, and it is no.** `PDType0Font.load` reaches for a bundled resource even
     * though the caller supplies the font bytes.
     *
     * ```
     * java.io.IOException: Error: Could not find referenced cmap stream Identity-H
     *   at com.tom_roush.fontbox.cmap.CMapParser.getExternalCMap(CMapParser.java:514)
     *   at com.tom_roush.pdfbox.pdmodel.font.CMapManager.getPredefinedCMap(CmapManager.java:53)
     *   at com.tom_roush.pdfbox.pdmodel.font.PDType0Font.readEncoding(PDType0Font.java:286)
     *   at com.tom_roush.pdfbox.pdmodel.font.PDType0Font.load(PDType0Font.java:98)
     * ```
     *
     * `readEncoding` **parses** `Identity-H` rather than merely naming it, and that CMap lives in
     * the AAR's assets, which are not classpath entries. So the whole font stack needs
     * `PDFBoxResourceLoader.init(Context)` and would have moved every one of this module's PDF
     * acceptance tests to instrumented — for a font we supply the bytes of.
     *
     * This is why M8c writes the CID font **at the COS level** instead, which is the same answer
     * M4 reached for `/Ink` (no `PDAnnotationInk` in 2.0.x) and for the XMP marker (no bundled
     * `xmpbox`). The parts of the library that are pure Java — `TTFParser`, `TTFSubsetter` — are
     * used; the part that wants an Android `Context` is not.
     *
     * Kept as an executing test rather than a comment because the answer belongs to the
     * dependency: a PdfBox-Android upgrade could change it, and then M8c should reconsider.
     */
    @Test
    fun `PDType0Font cannot load without the Android resource loader`() {
        PDDocument().use { document ->
            val failure = assertFailsWith<IOException> {
                testFont().inputStream().use { PDType0Font.load(document, it, true) }
            }
            assertTrue(
                failure.message.orEmpty().contains("Identity-H"),
                "expected the predefined-CMap lookup to be what fails, was: ${failure.message}",
            )
        }
    }

    /**
     * The pure-Java half **does** work, and it is everything the hand-written writer needs.
     *
     * `TTFParser` reads the face and `TTFSubsetter` cuts it down and reports the glyph renumbering
     * — so a subset-embedded CID font is buildable with no `Context` anywhere, and the JVM
     * acceptance tests stay JVM acceptance tests.
     */
    @Test
    fun `TTFParser and TTFSubsetter work with no Context at all`() {
        val face = TTFParser().parse(testFont().inputStream())
        val cmap = requireNotNull(face.unicodeCmap) { "no unicode cmap in the test face" }

        // The gate SPEC §4.9.5 names: a character the face cannot draw is REPORTED, never guessed.
        // Emitting .notdef puts a visible box in the PDF and nothing in the extraction;
        // substituting a lookalike puts the wrong word into search.
        assertTrue(cmap.getGlyphId('e'.code) > 0, "ASCII must be representable")
        assertTrue(cmap.getGlyphId('é'.code) > 0, "Latin-1 must be representable")
        assertTrue(cmap.getGlyphId('ł'.code) > 0, "Latin Extended-A must be representable")
        assertTrue(cmap.getGlyphId('ж'.code) > 0, "Cyrillic must be representable")
        assertTrue(cmap.getGlyphId(0x3B1) > 0, "Greek must be representable")
        assertEquals(0, cmap.getGlyphId(0x4F60), "CJK is out of coverage and must say so")

        val subsetter = TTFSubsetter(face)
        for (codePoint in SAMPLE.codePoints()) subsetter.add(codePoint)
        val bytes = ByteArrayOutputStream().also { subsetter.writeToStream(it) }.toByteArray()

        assertTrue(bytes.isNotEmpty(), "subsetter produced no font")
        assertTrue(
            bytes.size < testFont().length(),
            "a subset of a 28 KB face should be smaller than the face: ${bytes.size}",
        )

        // ⭐ **Direction matters and is not guessable**, and getting it backwards would produce a
        // PDF full of plausible, wrong glyphs — the failure that looks like a font bug and is an
        // indexing bug. With `/CIDToGIDMap /Identity` the code in the content stream IS the glyph
        // index in the *subset*, so the writer needs old-GID → new-GID and must know which way
        // round this map hands it over.
        //
        // It is **new → old**: the keys are indices into the subset, the values into the original.
        val gidMap = subsetter.gidMap
        assertTrue(gidMap.isNotEmpty(), "no glyph renumbering reported")
        assertEquals(0, gidMap[0], "new GID 0 must be .notdef, which is old GID 0")

        val oldGid = cmap.getGlyphId('é'.code)
        val newGid = gidMap.entries.single { it.value == oldGid }.key
        assertTrue(newGid > 0, "a subsetted glyph must get a non-zero new index")
        assertTrue(
            gidMap.keys.max() < face.numberOfGlyphs,
            "the subset must be smaller than the face it came from",
        )
    }

    private companion object {
        const val TEST_FONT = "TestSubset-Regular.ttf"
        const val FEW = 3

        /**
         * One character from each block SPEC §4.9.5 claims coverage of, plus plain ASCII.
         *
         * Every character here is above U+00FF except the ASCII and the `é`, which is the point:
         * `PdfContent`'s `toByteArray` used to truncate each of these into a *different* byte
         * silently; since M8c it refuses them loudly, and the invisible-text path skips such
         * words per word (`PdfContent.invisibleText`). Carrying them is what needs the CID font
         * this spike is about.
         */
        const val SAMPLE = "Hello — café Łódź Ωμέγα Живёт €5"
    }
}
