package dev.inkstone.app.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.inkstone.core.storage.ThumbnailCache
import java.io.ByteArrayOutputStream

/**
 * Turns a picked photo into a folder cover.
 *
 * ## Why the bytes are not just copied
 *
 * A photo picked from the gallery is routinely 12 megapixels and several megabytes, in
 * whatever format the camera wrote. Storing it as-is would mean:
 *
 * - **memory**: 48 MB decoded, for a picture drawn 180 dp wide — enough to matter on the
 *   4 GB device SPEC §10 says to test on;
 * - **disk**: megabytes per folder, in the app's private storage, forever;
 * - **format**: something downstream would have to sniff a file the user chose.
 *
 * So it is decoded once, downscaled to the width a cover is actually drawn at, and
 * re-encoded as WebP — the same format and roughly the same size as a note thumbnail.
 *
 * The decode is **downsampled**, not resized afterwards: `inSampleSize` makes the decoder
 * skip pixels as it reads, so the full-size bitmap is never allocated at all.
 */
object FolderCoverImage {

    /**
     * @return WebP bytes at most [ThumbnailCache.COVER_WIDTH_PX] wide, or null when the
     *   bytes are not a decodable image — which a content URI can always turn out not to be.
     */
    fun reEncode(source: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= ThumbnailCache.COVER_WIDTH_PX) sample *= 2

        val decoded = BitmapFactory.decodeByteArray(
            source,
            0,
            source.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        return try {
            val out = ByteArrayOutputStream()
            @Suppress("DEPRECATION") // WEBP_LOSSY is API 30+; plain WEBP is what's deprecated.
            if (decoded.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, out)) out.toByteArray() else null
        } finally {
            decoded.recycle()
        }
    }

    /** Slightly above the thumbnail quality: a photo shows compression artefacts sooner than ink. */
    private const val QUALITY = 88
}
