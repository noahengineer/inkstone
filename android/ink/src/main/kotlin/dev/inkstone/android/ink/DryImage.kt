package dev.inkstone.android.ink

import android.graphics.Path
import android.graphics.Rect
import dev.inkstone.core.model.ImageCrop
import dev.inkstone.core.model.ImageObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ⭐ M8c — an [ImageObject] the dry layer actually **draws**.
 *
 * Replaces `DryPlaceholder` for pictures, the same way [DryTextBox] does for words.
 *
 * ## The bytes are not here and never were
 *
 * SPEC §4.9.4: an image's pixels are a bundle entry, not an op. [asset] is the entry's name, it
 * **survives re-identification** (a duplicate names the original's entry, deliberately — asset
 * lifetime is refcount-free), and the bitmap behind it is fetched through [PageAssets], which
 * `:app` implements. A null answer is drawn as a placeholder rather than as nothing; see
 * [PageAssets] for why a blank rectangle is the failure this whole design is trying to make
 * *visible* rather than merely non-fatal.
 *
 * ## ⭐ What a crop means, on both kinds
 *
 * `ObjectFormat.crop` is normalised to the **source image's own box** (SPEC §4.9.2), never to the
 * page and never to the object's rectangle — so it is carried by the object's matrix like any other
 * point on it and a resize writes one op instead of two that could disagree.
 *
 * Both kinds **reframe**: the cropped region is what fills the object's rectangle. That is what a
 * crop means to a person — the frame stays where they put it and the picture inside it changes —
 * and it is the only reading under which [ImageCrop.Rect] and an [ImageCrop.Polygon] tracing the
 * same rectangle render identically, which is a property worth being able to state. A polygon
 * additionally **clips** to its own outline, so a freehand crop is a rectangular crop with a shaped
 * edge and nothing else.
 *
 * ⚠️ The alternative — a polygon that masks the whole image in place without reframing — was
 * rejected because it makes the two crop kinds disagree about the same shape, and because a
 * freehand crop that does not change the framing is indistinguishable from an eraser. SPEC §4.9.2
 * is explicit that erasing and cropping are different operations on the same image, and they should
 * not look the same either.
 *
 * ## Rotation, flipping and erasing are all somewhere else
 *
 * Rotate and flip are `SetTransform` — absolute, and a flip is a matrix negation (SPEC §4.9.2), so
 * this class never sees one: [PagePainter] concatenates the matrix and everything here is drawn
 * into `[0, widthPt] × [0, heightPt]` regardless. An erasure is `SetErasedRegion` and is punched
 * out by the shared `saveLayer`/`DST_OUT` sandwich, which needs nothing from this class either.
 */
internal class DryImage(image: ImageObject) : DryBox(image, image.widthPt, image.heightPt, content = null) {

    /** The bundle entry this picture's bytes live in — `assets/img-<objectId>.webp` (SPEC §5.3). */
    val asset: String = image.asset

    /**
     * A freehand crop's outline in this object's **own** space, or null when the crop is
     * rectangular, absent or degenerate.
     *
     * Built once per `SetFormat` rather than per frame: it is a `Path`, and building one in
     * `onDraw` is the allocation CLAUDE.md rule 7 is about. It is independent of the bitmap because
     * the polygon is normalised to the source and the source is reframed onto this rectangle — so
     * a picture whose bytes arrive a poll later is clipped correctly the moment they do, with no
     * rebuild.
     */
    var clipPath: Path? = null
        private set

    /**
     * The crop's bounding box as normalised source fractions, or null for "the whole picture".
     *
     * Held rather than derived per draw, for two reasons. It is read in `onDraw` and computing it
     * from an [ImageCrop.Polygon] walks every point (CLAUDE.md rule 7). And it is **one** answer for
     * both crop kinds, which is what makes the reframing identical for a rectangle and for a
     * polygon tracing it — the property the class KDoc claims and [rebuildCrop] is the single
     * writer of.
     */
    private var crop: FloatArray? = null

    init {
        rebuildCrop()
    }

    override fun onPresentationChanged() {
        rebuildCrop()
    }

    /**
     * The part of the source that is drawn, in **bitmap pixels**.
     *
     * Written into [into] rather than returned, because this runs per image per frame and a fresh
     * [Rect] per draw is an allocation on the draw path.
     *
     * ⚠️ **Fractions are clamped and the result is never empty.** A crop is a register a peer
     * running any build can write, `ObjectFormatCodec` only refuses one that is inverted, and a
     * source rectangle of zero width makes `drawBitmap` draw nothing at all — a picture that
     * vanished because somebody's slider went to 1.0001.
     */
    fun sourceRect(bitmapWidth: Int, bitmapHeight: Int, into: Rect) {
        val bounds = crop
        if (bounds == null || bitmapWidth <= 0 || bitmapHeight <= 0) {
            into.set(0, 0, max(bitmapWidth, 0), max(bitmapHeight, 0))
            return
        }
        val left = (bounds[LEFT].coerceIn(0f, 1f) * bitmapWidth).roundToInt().coerceAtMost(bitmapWidth - 1)
        val top = (bounds[TOP].coerceIn(0f, 1f) * bitmapHeight).roundToInt().coerceAtMost(bitmapHeight - 1)
        val right = (bounds[RIGHT].coerceIn(0f, 1f) * bitmapWidth).roundToInt()
        val bottom = (bounds[BOTTOM].coerceIn(0f, 1f) * bitmapHeight).roundToInt()
        into.set(
            left,
            top,
            right.coerceIn(left + 1, bitmapWidth),
            bottom.coerceIn(top + 1, bitmapHeight),
        )
    }

    private fun polygonBounds(crop: ImageCrop.Polygon): FloatArray? {
        if (crop.pointCount < MIN_POLYGON_POINTS) return null
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (index in 0 until crop.pointCount) {
            val x = crop.points[index * 2]
            val y = crop.points[index * 2 + 1]
            left = min(left, x)
            top = min(top, y)
            right = max(right, x)
            bottom = max(bottom, y)
        }
        if (right - left < MIN_EXTENT || bottom - top < MIN_EXTENT) return null
        return floatArrayOf(left, top, right, bottom)
    }

    /**
     * The one writer of [crop] and [clipPath], so the pixels and the shaped edge cannot disagree
     * about which part of the source is being framed.
     *
     * A degenerate polygon — fewer than three points, or no extent — falls back to the whole
     * picture rather than to an empty one: a crop that selects nothing is a picture nobody can get
     * back through the UI, and showing all of it is the recoverable failure.
     */
    private fun rebuildCrop() {
        val polygon = decodedFormat.crop as? ImageCrop.Polygon
        val bounds = when (val current = decodedFormat.crop) {
            null -> null

            is ImageCrop.Rect ->
                if (current.isWhole) null else floatArrayOf(current.left, current.top, current.right, current.bottom)

            is ImageCrop.Polygon -> polygonBounds(current)
        }
        crop = bounds
        if (polygon == null || bounds == null) {
            clipPath = null
            return
        }
        // Normalised source → this object's rectangle, through the crop's own bounding box. The
        // same mapping `sourceRect` performs on the pixels, so the shaped edge lands exactly on the
        // reframed picture rather than a few percent off it.
        val spanX = bounds[RIGHT] - bounds[LEFT]
        val spanY = bounds[BOTTOM] - bounds[TOP]
        val path = Path()
        for (index in 0 until polygon.pointCount) {
            val x = (polygon.points[index * 2] - bounds[LEFT]) / spanX * widthPt
            val y = (polygon.points[index * 2 + 1] - bounds[TOP]) / spanY * heightPt
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        clipPath = path
    }

    /**
     * False, for the reason [DryTextBox] gives: pruning would take the picture off the screen until
     * the page was reloaded, and the erase that did it is undoable everywhere else.
     */
    override fun isErasedAway(region: ErasedRegion): Boolean = false

    private companion object {
        const val LEFT = 0
        const val TOP = 1
        const val RIGHT = 2
        const val BOTTOM = 3
        const val MIN_POLYGON_POINTS = 3

        /** Below this a crop selects a line of pixels, which is a picture nobody can un-crop. */
        const val MIN_EXTENT = 1e-4f
    }
}
