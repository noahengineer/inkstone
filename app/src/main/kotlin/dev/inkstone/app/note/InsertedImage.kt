package dev.inkstone.app.note

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * ⭐ ROADMAP M8c — a picked photo, made small enough to live inside a note forever.
 *
 * The Android half of [ImageDownscale]: all of the arithmetic is there, where a JVM test can reach
 * it, and this is the ten lines that hold the decoder. `BitmapFactory` cannot run in a unit test —
 * with `isReturnDefaultValues` it answers null and a zero-size `Options`, so a test written
 * against it passes while asserting nothing.
 *
 * ## ⚠️ The bounds pass is not optional
 *
 * `inJustDecodeBounds` reads the header and allocates no pixels. Decoding a 12-megapixel photo to
 * *measure* it is 48 MB of heap on a device SPEC §10 says to test at 4 GB, and it is the single
 * most likely way "insert a picture" becomes an out-of-memory crash rather than a slow operation.
 * `FolderCoverImage` made the same call for a folder tile; the difference here is that a note's
 * images end up in the bundle, in the published PDF, and on the user's server — so the size is
 * paid on every sync rather than once.
 *
 * ## Why WebP
 *
 * `Bundle.imageAsset` names the entry `.webp`, so the format is part of the file format, and the
 * decision was made where it belongs: one known format at a known size means nothing downstream
 * ever sniffs a file the user chose.
 */
object InsertedImage {

    /**
     * @return the re-encoded bytes and the pixel size they were encoded at, or null when [source]
     *   is not a decodable image — which a content URI can always turn out not to be, and which is
     *   a message to the user rather than a crash.
     */
    fun reEncode(source: ByteArray): Encoded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageDownscale.sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, options) ?: return null
        // `inSampleSize` only halves, so the sampled long edge can still be up to twice the cap —
        // a 4,000 px photo samples by 1 and stays at 4,000, because halving it would throw away
        // detail the cap allows. Without this second step the cap is not a cap.
        val scale = ImageDownscale.scaleAfterSampling(decoded.width, decoded.height)
        val sized = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }

        return try {
            val out = ByteArrayOutputStream()

            @Suppress("DEPRECATION") // WEBP_LOSSY is API 30+; plain WEBP is what's deprecated.
            val ok = sized.compress(Bitmap.CompressFormat.WEBP_LOSSY, ImageDownscale.QUALITY, out)
            if (ok) Encoded(out.toByteArray(), sized.width, sized.height, source.size) else null
        } finally {
            sized.recycle()
        }
    }

    /**
     * What an insert produced.
     *
     * A class rather than a `data class` because of the [webp] array: generated `equals` on a
     * `ByteArray` compares references, which is the wrong answer written by a compiler rather than
     * by a person. `StrokeGeometry` states the same reasoning for the same shape.
     */
    class Encoded(
        val webp: ByteArray,
        val widthPx: Int,
        val heightPx: Int,
        /** What the picker handed over, for the size report a milestone has to be able to make. */
        val sourceBytes: Int,
    )
}
