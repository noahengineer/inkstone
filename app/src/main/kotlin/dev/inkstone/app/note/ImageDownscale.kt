package dev.inkstone.app.note

import dev.inkstone.core.model.ImageCrop
import dev.inkstone.core.model.InkObject
import dev.inkstone.core.model.Matrix3
import dev.inkstone.core.model.PagePoint
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import kotlin.math.max
import kotlin.math.min

/**
 * ⭐ ROADMAP M8c — the sizing arithmetic behind *"insert an image"*, with no `Bitmap` in it.
 *
 * ## Why the numbers are here and not in the decoder
 *
 * `BitmapFactory` cannot run in a JVM unit test — with `isReturnDefaultValues` it answers null and
 * a zero-size `Options`, which is worse than throwing because a test written against it passes.
 * CLAUDE.md's definition-of-done #6 asks for the *decision* to be hoisted out so a test can assert
 * it was made, and every decision an insert makes is here: how much to downsample, how big the
 * picture lands on the page, and what a crop drag means in the source image's coordinates.
 * `InsertedImage` is then the ten lines that hold the decoder.
 *
 * ## Why an insert downscales at all
 *
 * A modern tablet photo is around 4,000 px on its long edge and a note page is 612 pt wide. Stored
 * as picked, one holiday snapshot is several megabytes **inside the note's bundle**, which is
 * inside the published PDF, which is what the outbox uploads — forever, on every save, to the
 * user's own server. `FolderCoverImage` made the same argument for a 180 dp folder tile; the
 * difference here is that a note's images sync, so the cost is paid repeatedly rather than once.
 */
object ImageDownscale {

    /**
     * The cap on the stored image's long edge, in pixels.
     *
     * Chosen against the page rather than against the screen: 2,048 px across a 612 pt page is
     * about 240 dpi, which is past what a 2,560 px-wide tablet can show at 100 % zoom and still
     * has headroom for someone who zooms in to trace a diagram. Doubling it would double every
     * sync of every note that has a photo in it for detail no display in the device's life will
     * resolve.
     */
    const val MAX_LONG_EDGE_PX: Int = 2048

    /**
     * WebP quality. Slightly above the thumbnail cache's, and for `FolderCoverImage`'s reason: a
     * photograph shows compression artefacts sooner than ink does.
     */
    const val QUALITY: Int = 85

    /** How wide an inserted image is, as a fraction of the page — big enough to see, small enough to place. */
    private const val PAGE_FRACTION = 0.5f

    /** A picture smaller than this on a side would be unresizable by hand. Page points. */
    private const val MIN_EXTENT_PT = 24f

    /**
     * `inSampleSize` for a source of [widthPx] × [heightPx].
     *
     * ⚠️ **The decoder is told to skip pixels as it reads; the full-size bitmap is never
     * allocated.** Decoding a 12-megapixel photo to measure it is 48 MB of heap on a device SPEC
     * §10 says to test at 4 GB, and it is the single most likely way inserting a picture becomes
     * an OOM crash rather than a slow operation.
     *
     * Powers of two only, because that is the only thing `BitmapFactory` honours — any other value
     * is rounded down to one, silently. The result decodes to somewhere in
     * `[maxLongEdge, 2 × maxLongEdge)`, and [scaleAfterSampling] finishes the job exactly.
     */
    fun sampleSizeFor(widthPx: Int, heightPx: Int, maxLongEdge: Int = MAX_LONG_EDGE_PX): Int {
        require(maxLongEdge > 0) { "maxLongEdge must be positive, was $maxLongEdge" }
        val longEdge = max(widthPx, heightPx)
        if (longEdge <= 0) return 1
        var sample = 1
        while (longEdge / (sample * 2) >= maxLongEdge) sample *= 2
        return sample
    }

    /**
     * The exact scale to apply *after* sampling, or 1 when the sampled bitmap is already inside the cap.
     *
     * `inSampleSize` alone leaves the long edge anywhere up to twice the cap — a 4,000 px photo
     * samples by 1 and stays at 4,000, because halving it would take it to 2,000 which is *under*
     * the cap and would throw away detail the cap allows. So the cap is only a cap once this
     * second step runs, and leaving it out is how "capped at 2,048" quietly becomes "capped at
     * 4,095".
     */
    fun scaleAfterSampling(sampledWidthPx: Int, sampledHeightPx: Int, maxLongEdge: Int = MAX_LONG_EDGE_PX): Float {
        val longEdge = max(sampledWidthPx, sampledHeightPx)
        if (longEdge <= maxLongEdge || longEdge <= 0) return 1f
        return maxLongEdge.toFloat() / longEdge
    }

    /**
     * Where an inserted image lands: centred on [visible], at [PAGE_FRACTION] of the page's width
     * or whatever the aspect ratio allows, never larger than the visible area.
     *
     * Centred on what the user can *see* rather than on the page, because inserting a picture
     * while zoomed into the corner of a lecture page and having it appear somewhere off-screen is
     * indistinguishable from nothing having happened — CLAUDE.md's definition-of-done #6 in its
     * mildest form.
     *
     * @param visible the page-space rectangle currently on screen.
     */
    fun placeOnPage(sourceWidthPx: Int, sourceHeightPx: Int, page: PageSize, visible: PageRect): PageRect {
        val aspect = if (sourceWidthPx > 0 && sourceHeightPx > 0) {
            sourceHeightPx.toFloat() / sourceWidthPx.toFloat()
        } else {
            1f
        }
        var width = min(page.widthPt * PAGE_FRACTION, visible.width * PAGE_FRACTION)
        var height = width * aspect
        val maxHeight = min(page.heightPt * PAGE_FRACTION, visible.height * PAGE_FRACTION)
        if (height > maxHeight && height > 0f) {
            width *= maxHeight / height
            height = maxHeight
        }
        // ⚠️ **The minimum scales both sides, and clamping them independently was a real bug** —
        // found by the test that asserts a tall picture keeps its aspect ratio. A very tall
        // photograph fitted into a short viewport comes out narrower than [MIN_EXTENT_PT], and
        // `max(width, MIN)` on its own then widened it without touching the height: a 1:8
        // photograph inserted as 1:4, silently, and only for the shapes and zooms that hit the
        // clamp. Distorting a picture at the moment it is inserted is the one thing this function
        // must not do — the user can resize it, and cannot un-stretch it.
        val upscale = max(1f, max(MIN_EXTENT_PT / width, MIN_EXTENT_PT / height))
        width *= upscale
        height *= upscale
        val left = visible.centreX - width / 2f
        val top = visible.centreY - height / 2f
        return PageRect(left, top, left + width, top + height)
    }
}

/**
 * ⭐ ROADMAP M8c — a crop drag, in the source image's own coordinates.
 *
 * SPEC §4.9.2: **a crop is normalised to the source image's own box, never page points.** A crop
 * in page space would have to be rewritten by every resize, rotate and flip — and those are
 * `SetTransform`, a *different* register, so the two would have to be written together and could
 * then disagree. Normalised, the crop is carried by the object's matrix like any other point on
 * it, and a resize writes one op.
 *
 * ## Why the crop UI works on the whole source
 *
 * Entering crop mode shows the picture **uncropped** with the current crop as handles, so the
 * numbers this object produces are already fractions of the source and nothing has to be composed
 * against what was there before. Cropping a crop *relative to what is displayed* is the obvious
 * alternative and it needs a composition step that is wrong in exactly one direction — widening a
 * crop back out becomes unrepresentable, and the user discovers it by not being able to undo their
 * own drag with another drag. SPEC §4.9.2 makes the same point about `SetErasedRegion`: an erased
 * area only grows, a crop is a region the user can widen again, and one register cannot be both.
 */
object CropMath {

    /**
     * A page-space drag, as a fraction-of-source rectangle.
     *
     * The object's inverse transform maps the page point into the object's local box, which runs
     * `0..widthPt × 0..heightPt`; dividing by the extents gives the fraction. Clamped to `0..1`,
     * so a drag that overshoots the picture crops to its edge instead of inventing pixels.
     *
     * @return null when the object is not one with extents, when its matrix is singular (nothing
     *   can be mapped back through one), or when the drag collapsed to a line — a degenerate crop
     *   renders an image with no pixels and no way back through the UI, which is exactly what
     *   `ObjectFormatCodec.decodeRect` refuses to decode.
     */
    fun rectOf(obj: InkObject, from: PagePoint, to: PagePoint): ImageCrop.Rect? {
        val local = localSpace(obj) ?: return null
        val a = local.toFraction(from)
        val b = local.toFraction(to)
        val left = min(a.x, b.x).coerceIn(0f, 1f)
        val right = max(a.x, b.x).coerceIn(0f, 1f)
        val top = min(a.y, b.y).coerceIn(0f, 1f)
        val bottom = max(a.y, b.y).coerceIn(0f, 1f)
        if (right - left < MIN_FRACTION || bottom - top < MIN_FRACTION) return null
        return ImageCrop.Rect(left, top, right, bottom)
    }

    /**
     * A freehand lasso, as a closed polygon of fractions.
     *
     * The same normalisation as [rectOf], point by point. Points are **not** de-duplicated or
     * simplified: the record is written once per gesture and a lasso the user drew slowly is
     * theirs to have kept. They *are* clamped, for the same reason the rectangle is.
     *
     * @return null for fewer than three points — a polygon with no area is a tap, and
     *   `ObjectFormatCodec` refuses to decode one, so writing it would produce a register this
     *   build could not read back.
     */
    fun polygonOf(obj: InkObject, points: List<PagePoint>): ImageCrop.Polygon? {
        if (points.size < MIN_POLYGON_POINTS) return null
        val local = localSpace(obj) ?: return null
        val flat = ArrayList<Float>(points.size * 2)
        for (point in points) {
            val fraction = local.toFraction(point)
            flat += fraction.x.coerceIn(0f, 1f)
            flat += fraction.y.coerceIn(0f, 1f)
        }
        return ImageCrop.Polygon(flat)
    }

    /** The crop as a page-space rectangle, for drawing the handles where the picture actually is. */
    fun handlesOf(obj: InkObject, crop: ImageCrop.Rect, widthPt: Float, heightPt: Float): List<PagePoint> = listOf(
        PagePoint(crop.left * widthPt, crop.top * heightPt),
        PagePoint(crop.right * widthPt, crop.top * heightPt),
        PagePoint(crop.right * widthPt, crop.bottom * heightPt),
        PagePoint(crop.left * widthPt, crop.bottom * heightPt),
    ).map { obj.transform.map(it) }

    private class LocalSpace(val inverse: Matrix3, val widthPt: Float, val heightPt: Float) {
        fun toFraction(page: PagePoint): PagePoint {
            val local = inverse.map(page)
            return PagePoint(local.x / widthPt, local.y / heightPt)
        }
    }

    private fun localSpace(obj: InkObject): LocalSpace? {
        val extents = when (obj) {
            is dev.inkstone.core.model.ImageObject -> obj.widthPt to obj.heightPt
            is dev.inkstone.core.model.TextBoxObject -> obj.widthPt to obj.heightPt
            else -> return null
        }
        if (extents.first <= 0f || extents.second <= 0f) return null
        val inverse = obj.transform.inverse() ?: return null
        return LocalSpace(inverse, extents.first, extents.second)
    }

    /** Below this the crop is a line, which no reader will accept — see [rectOf]. */
    private const val MIN_FRACTION = 0.01f

    private const val MIN_POLYGON_POINTS = 3
}
