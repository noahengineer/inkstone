package dev.inkstone.android.pdf

import android.content.res.AssetManager
import dev.inkstone.core.model.TextFont

/**
 * ⭐ Where the bytes of an embeddable font face come from — SPEC §4.9.5's *"no new asset in the
 * APK"*, expressed as the one thing this module cannot fetch for itself.
 *
 * ## Why an interface at all
 *
 * `resources/ttf/LiberationSans-Regular.ttf` is **inside the pdfbox-android AAR's own assets**, and
 * an AAR's assets merge into the APK's `assets/` — they are not classpath entries. Reading one
 * therefore needs an `AssetManager`, which needs a `Context`, which is the single dependency
 * `PdfContent` and `PdfCidFont` were both written by hand to avoid: taking it unconditionally would
 * move **every** PDF acceptance test in this module from a fast JVM test to an instrumented one
 * (`CidFontSpikeTest` is the evidence for that, and it keeps running).
 *
 * So the `Context` stays at the edge. [AssetPdfFontFaces] is the production implementation and
 * lives here, next to the path it has to know, rather than in `:app` where a typo in that string
 * would degrade silently.
 *
 * ## ⚠️ What happens when a face is unavailable, and why it is not "nothing"
 *
 * [None] is the default, and a text box published through it is **not blank**. It falls back to a
 * standard-14 base font — Helvetica, Times-Roman or Courier — which needs no embedding and no
 * asset, and carries printable ASCII. What the embedded face buys is *coverage*: Latin-1
 * Supplement, Latin Extended-A and -B, Cyrillic and most of Greek (SPEC §4.9.5's measured table).
 *
 * That two-tier arrangement is deliberate. The alternative — publish nothing when no face is
 * supplied — makes a forgotten injection in `:app` come out as a **valid, parseable PDF with the
 * user's typing missing**, which is docs/M4-PDF.md's recurring failure exactly. Here a forgotten
 * injection costs an accented character, and the note is still readable.
 */
public fun interface PdfFontFaces {

    /** The TTF bytes to embed for [font], or null to fall back to a standard-14 base font. */
    public fun face(font: TextFont): ByteArray?

    public companion object {

        /** No embeddable face for anything. See the class KDoc on what this degrades to. */
        public val None: PdfFontFaces = PdfFontFaces { null }
    }
}

/**
 * [PdfFontFaces] over the APK's merged assets — which is where pdfbox-android's own faces are.
 *
 * `:app` constructs this with `context.assets` and passes it to `InkstonePdfMaterializer`. Nothing
 * else about the wiring is `:app`'s business; the asset paths below are this module's, because
 * they belong to the dependency this module owns.
 *
 * ⚠️ **The bytes are cached per face for the lifetime of this object.** The Sans face is 410,712
 * bytes and materialization runs on close, before every upload and on idle-while-charging (SPEC
 * §5.1) — re-reading it out of the APK on each publish would be nearly half a megabyte of asset
 * I/O per save, for a file that cannot change while the process lives.
 *
 * ⚠️ **A missing asset is a null, never a throw.** A publish must not fail because a face is not
 * where this build expected it: the fallback in [PdfFontFaces] is designed for exactly this, and a
 * thrown `IOException` here would take the whole note's materialization — and therefore its upload
 * — down with it.
 */
public class AssetPdfFontFaces(private val assets: AssetManager) : PdfFontFaces {

    private val cache = HashMap<TextFont, ByteArray?>()

    override fun face(font: TextFont): ByteArray? = synchronized(cache) {
        cache.getOrPut(font) { read(pathFor(font)) }
    }

    private fun read(path: String): ByteArray? = runCatching { assets.open(path).use { it.readBytes() } }.getOrNull()

    private fun pathFor(font: TextFont): String = when (font) {
        TextFont.SANS -> SANS_PATH
        TextFont.SERIF -> SERIF_PATH
        TextFont.MONO -> MONO_PATH
    }

    private companion object {

        /**
         * Verified present in pdfbox-android 2.0.27.0: 410,712 bytes (SPEC §4.9.5).
         *
         * The other two are the same directory's siblings and are *not* verified to ship — the AAR
         * bundles what its own standard-14 substitution needs, which is a moving target across
         * releases. [read] answering null for them is the designed path, not an accident: a serif
         * text box then comes out as Times-Roman, which is a serif face.
         */
        const val SANS_PATH = "com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
        const val SERIF_PATH = "com/tom_roush/pdfbox/resources/ttf/LiberationSerif-Regular.ttf"
        const val MONO_PATH = "com/tom_roush/pdfbox/resources/ttf/LiberationMono-Regular.ttf"
    }
}
