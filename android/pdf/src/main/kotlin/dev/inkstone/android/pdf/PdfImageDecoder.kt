package dev.inkstone.android.pdf

import android.graphics.BitmapFactory

/**
 * Pixels, in the one arrangement a PDF image XObject can carry directly.
 *
 * @property rgb `width * height * 3` bytes, row-major from the **top** row, 8 bits per component.
 *   That is PDF image space, which is also what `Bitmap.getPixels` hands over, so no row flip
 *   happens anywhere — the flip is in the placement matrix instead
 *   ([PdfPlacement.unitImageToObject]).
 * @property alpha `width * height` bytes, or null when the image is fully opaque. Becomes an
 *   `/SMask`; a null here is one fewer stream in every published file, which matters because a
 *   photograph's soft mask compresses far worse than the photograph.
 */
public class RasterImage(
    public val width: Int,
    public val height: Int,
    public val rgb: ByteArray,
    public val alpha: ByteArray?,
)

/**
 * ⭐ How encoded image bytes become pixels — the second thing this module cannot do for itself.
 *
 * SPEC §5.3 names an inserted image `assets/img-<objectId>.webp`, and **PDF has no WebP filter**.
 * Neither does it have a PNG filter: `/FlateDecode` carries PNG's *compression* and not its
 * format. So anything that is not already a JPEG has to be decoded to samples, and decoding needs
 * a platform codec — `android.graphics.BitmapFactory` here, which is why this is an interface with
 * an Android implementation rather than a function.
 *
 * ⚠️ **A JPEG never reaches an implementation of this.** `PdfImages` passes it through with
 * `/DCTDecode` untouched, because decoding and re-`/FlateDecode`-ing a photograph turns a 2 MB
 * asset into ~10 MB of barely-compressible samples — in a file that syncs, on every save. The
 * decoder exists for WebP and PNG.
 *
 * ⚠️ **Null is a designed answer, not an error.** A codec this build does not have, or an image
 * too large to decode, must cost the picture and not the publish: a thrown decoder would take the
 * note's materialization and therefore its upload with it (M12's `readBytes` lesson, one module
 * over). The image is simply absent from the PDF; the bytes are still in the bundle, which is the
 * source of truth (SPEC §5.3).
 */
public fun interface PdfImageDecoder {

    /** @return the pixels, or null when this build cannot decode [bytes]. */
    public fun decode(bytes: ByteArray): RasterImage?

    public companion object {

        /** Decodes nothing. JPEGs still publish; everything else is absent. */
        public val None: PdfImageDecoder = PdfImageDecoder { null }
    }
}

/**
 * [PdfImageDecoder] on `android.graphics` — everything the platform can decode, which is every
 * format this app can have inserted.
 *
 * No `Context`, so this can be the *default* on `InkstonePdfMaterializer`'s public constructor and
 * images need no wiring in `:app` at all. In a JVM unit test `BitmapFactory` is stubbed to return
 * defaults (`unitTests.isReturnDefaultValues`, see the module's `build.gradle.kts`), so
 * [decode] answers null there and the tests inject a fake instead — which is the honest shape of
 * it: this class cannot be tested off a device and does not pretend to be.
 */
public class AndroidImageDecoder : PdfImageDecoder {

    override fun decode(bytes: ByteArray): RasterImage? {
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return null
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            bitmap.recycle()
            return null
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val hasAlpha = bitmap.hasAlpha()
        bitmap.recycle()
        return raster(width, height, pixels, hasAlpha)
    }

    private fun raster(width: Int, height: Int, pixels: IntArray, hasAlpha: Boolean): RasterImage {
        val rgb = ByteArray(pixels.size * RGB_COMPONENTS)
        val alpha = if (hasAlpha) ByteArray(pixels.size) else null
        for (index in pixels.indices) {
            val pixel = pixels[index]
            rgb[index * RGB_COMPONENTS] = (pixel shr RED_SHIFT).toByte()
            rgb[index * RGB_COMPONENTS + 1] = (pixel shr GREEN_SHIFT).toByte()
            rgb[index * RGB_COMPONENTS + 2] = pixel.toByte()
            alpha?.set(index, (pixel shr ALPHA_SHIFT).toByte())
        }
        return RasterImage(width, height, rgb, alpha)
    }

    private companion object {
        const val RGB_COMPONENTS = 3
        const val ALPHA_SHIFT = 24
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
    }
}
