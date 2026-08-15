package dev.inkstone.core.model

import kotlin.math.PI
import kotlin.math.max

/**
 * ROADMAP M8c — what an **image** gesture writes down, as a pure function of plain data.
 *
 * The sibling of [TextBoxEdit] and [ShapeEdit], and hoisted here for the same reason: an image is
 * inserted, cropped, rotated and flipped by code in `:android:ink` that a JVM test cannot
 * construct, so the arithmetic that decides *what is written* lives where every target can reach
 * it (CLAUDE.md definition-of-done #6).
 *
 * ## Which register each gesture writes, and why none of them is a new one
 *
 * | Gesture | Op | Why |
 * |---|---|---|
 * | insert | `AddObject` + `SetFormat` | the payload names the asset; the format is the crop |
 * | crop | [PageOp.SetFormat] | absolute, and an image's `SetTextContent` is free (SPEC §4.9.2) |
 * | rotate, flip | [PageOp.SetTransform] | absolute already; **a flip is a matrix negation** |
 * | recolour, fade | `SetStyle` | existed since M2 |
 *
 * ⚠️ **A crop is not a [PageOp.SetErasedRegion].** An erased area is a union of eraser capsules
 * and grows monotonically with the gesture; a crop is a single absolute region the user can widen
 * again. They would fight over one register, and *"erase part of a photo"* and *"crop a photo"*
 * are different operations a user can perform on the same image (SPEC §4.9.2).
 *
 * ## ⚠️ Image bytes are never in the op log
 *
 * `ImageObject.asset` names a bundle entry — `Bundle.imageAsset(id)`, `assets/img-<objectId>.webp`
 * — and the bytes go there (SPEC §4.9.4, §5.3). Nothing here carries a picture. The consequence
 * for a caller is that inserting an image is *two* writes that must both land: these ops, and the
 * asset. An `ImageObject` whose asset is missing renders as a blank rectangle — defined,
 * non-crashing and reported by nothing — which is why the asset is written first.
 */
public object ImageEdit {

    /**
     * A newly inserted image, as the ops that make it — in this order, as one gesture.
     *
     * [ShapeEdit.create]'s ordering argument applies unchanged: a register write sorting before its
     * `AddObject` is legal but pointless work for the fold, and one gesture means one undo entry,
     * so *"insert a picture, press undo"* removes the picture and its crop together.
     *
     * ⚠️ [style] is non-null for the reason spelled out on [TextBoxEdit] — `SetStyle` cannot
     * express *"unset"*, so an image shipped without one has no inverse for its first restyle and
     * the press is eaten silently.
     *
     * @param asset the bundle entry name, from `Bundle.imageAsset(id)`. A plain string on purpose:
     *   it **survives re-identification**, so a copied image points at the original's bytes and
     *   asset lifetime is refcount-free (SPEC §4.9.4). Nothing in this app may ever delete one.
     * @return the `AddObject`, then `SetFormat` when [format] is not [ObjectFormat.Default] —
     *   which for a freshly inserted picture usually means an insert-time crop, and otherwise
     *   nothing, because the default presentation is the absence of a record.
     */
    @Suppress("LongParameterList") // The payload and the format register, spelled out.
    public fun create(
        id: ObjectId,
        asset: String,
        widthPt: Float,
        heightPt: Float,
        transform: Matrix3,
        z: FractionalKey,
        style: ObjectStyle,
        format: ObjectFormat,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> {
        val image = ImageObject(
            id = id,
            asset = asset,
            widthPt = max(widthPt, ShapeGeometry.MIN_EXTENT_PT),
            heightPt = max(heightPt, ShapeGeometry.MIN_EXTENT_PT),
            transform = transform,
            style = style,
            z = z,
        )
        val add = PageOp.AddObject(nextOpId(), nextClock(), image)
        if (format.isDefault) return listOf(add)
        return listOf(add, TextBoxEdit.setFormat(id, format, nextOpId, nextClock))
    }

    /**
     * Set or clear an image's crop, **carrying the rest of its format forward**.
     *
     * ⚠️ [current] is not a convenience parameter, it is the correctness of this method. The format
     * is one LWW register resolving *whole* (CLAUDE.md rule 8), so writing a fresh
     * [ObjectFormat] with only a crop in it would silently reset the object's font, size,
     * alignment and — worst — [ObjectFormat.extras], which holds a **later build's** keys and is
     * the one thing this build cannot reconstruct (SPEC §5.8). Read it with
     * `ObjectFormat.decode(page.formatOf(id))` and pass it in.
     *
     * @param crop null clears the crop back to the whole image. Not an absent op: clearing is a
     *   write like any other, and an absolute register has no way to say *"leave this field"* —
     *   which is the point of it being absolute.
     */
    public fun setCrop(
        target: ObjectId,
        current: ObjectFormat,
        crop: ImageCrop?,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): PageOp = TextBoxEdit.setFormat(target, current.copy(crop = crop), nextOpId, nextClock)

    /**
     * ⭐ A crop, as the **two** ops it really takes: the region, and the frame that region is drawn
     * into.
     *
     * ## Why a crop is not one op, and what shipped when it was
     *
     * `SetFormat` alone says *which pixels*; it says nothing about **how big the picture is**. The
     * renderer scales whatever the crop selects to fill the object's whole `widthPt × heightPt` box
     * (`PagePainter` draws `sourceRect → 0,0,widthPt,heightPt`), so a crop written on its own does
     * not crop at all — it **zooms**. Crop a square photo down to a tall sliver and you get that
     * sliver stretched back out to a square, silently, with the aspect ratio `ImageDownscale` went
     * to real trouble to preserve at insert time thrown away one gesture later.
     *
     * What a person means by *crop* is that the **frame shrinks to the part they kept** and the
     * pixels do not move or change size. That is [croppedFrame], and it is an ordinary absolute
     * `SetTransform` (rule 8) — no new op, no new field.
     *
     * ## Why it is non-destructive, and stays so
     *
     * The frame is always recomputed from the **whole-source** frame ([uncroppedFrame]), never
     * composed onto the frame the last crop left. So cropping twice is the same as cropping once to
     * the intersection, and widening a crop back out — including all the way back to
     * `crop = null` — puts the picture back exactly where it was. A crop that could only tighten
     * would be an erase, which is the distinction SPEC §4.9.2 draws and CLAUDE.md rule 1 requires.
     *
     * @param crop null clears the crop and restores the whole picture at its original size.
     * @return `SetFormat` then `SetTransform`, as one gesture so one undo puts both back.
     */
    @Suppress("LongParameterList") // Two registers and the box they are measured against.
    public fun crop(
        target: ObjectId,
        current: ObjectFormat,
        currentTransform: Matrix3,
        widthPt: Float,
        heightPt: Float,
        crop: ImageCrop?,
        nextOpId: () -> String,
        nextClock: () -> LamportClock,
    ): List<PageOp> {
        val whole = uncroppedFrame(currentTransform, widthPt, heightPt, current.crop)
        return listOf(
            setCrop(target, current, crop, nextOpId, nextClock),
            PageOp.SetTransform(nextOpId(), nextClock(), target, croppedFrame(whole, widthPt, heightPt, crop)),
        )
    }

    /**
     * The frame this image would have if it were showing its **whole** source, given that [applied]
     * is the crop currently on it.
     *
     * The inverse of [croppedFrame], and the reason a crop can be widened again: every crop is
     * measured from here rather than from the frame the previous one left behind.
     *
     * Returns [current] unchanged when the crop is absent, whole, or degenerate — and when the
     * frame's own matrix cannot be inverted, which is a picture that has been scaled to nothing and
     * for which there is no sane frame to recover.
     */
    public fun uncroppedFrame(current: Matrix3, widthPt: Float, heightPt: Float, applied: ImageCrop?): Matrix3 {
        val region = regionOf(applied) ?: return current
        return current * insetOf(region, widthPt, heightPt).inverse().let { it ?: return current }
    }

    /** [whole]'s frame narrowed to [crop] — the picture unmoved, the frame shrunk around it. */
    public fun croppedFrame(whole: Matrix3, widthPt: Float, heightPt: Float, crop: ImageCrop?): Matrix3 {
        val region = regionOf(crop) ?: return whole
        return whole * insetOf(region, widthPt, heightPt)
    }

    /**
     * The local-space map from the cropped box onto the region of the whole box it shows.
     *
     * `translate(l·w, t·h) · scale(r−l, b−t)` — so the cropped box's `(0,0)` lands on the region's
     * top-left and its `(w,h)` on the region's bottom-right, which is precisely where those pixels
     * were already being drawn. That is what makes the crop move nothing.
     */
    private fun insetOf(region: ImageCrop.Rect, widthPt: Float, heightPt: Float): Matrix3 =
        Matrix3.translate(region.left * widthPt, region.top * heightPt) *
            Matrix3.scale(region.right - region.left, region.bottom - region.top)

    /**
     * [crop] as the rectangle the frame has to follow, or null when the frame should not move.
     *
     * A [ImageCrop.Polygon]'s **bounding box**, because the frame is a rectangle whatever shape the
     * clip is — a freehand crop shrinks the frame to what it encloses and then clips inside it.
     */
    private fun regionOf(crop: ImageCrop?): ImageCrop.Rect? {
        val rect = when (crop) {
            null -> return null
            is ImageCrop.Rect -> crop
            is ImageCrop.Polygon -> crop.bounds() ?: return null
        }
        if (rect.isWhole) return null
        if (rect.right - rect.left < MIN_EXTENT || rect.bottom - rect.top < MIN_EXTENT) return null
        return rect
    }

    /**
     * ⭐ The image's transform **after** a flip about its own centre — an absolute matrix, never a
     * delta (CLAUDE.md rule 8).
     *
     * A flip is a matrix negation, which is why ROADMAP M8c is right that this needs no new op and
     * no new field: it is an ordinary [PageOp.SetTransform] carrying the result.
     *
     * ## Why the composition is on the right, and what it means to be "about its own centre"
     *
     * The negation is expressed in the object's **local** box — `translate(c) · scale(±1) ·
     * translate(-c)`, with `c` the centre of the `widthPt × heightPt` box — and post-multiplied
     * onto [current]. That is what makes the centre a fixed point *on the page*: the object's own
     * centre maps to the same page point before and after, so a flip does not also move the
     * picture, whatever rotation or scale [current] already carries. Pre-multiplying instead would
     * mirror about the page's origin and fling the image across the paper.
     *
     * Two flips are the identity, exactly, and that is a property worth relying on rather than a
     * coincidence: `S·S = I` for a signed unit scale, and the two translations cancel by addition.
     *
     * @param horizontal true mirrors left-to-right (the common one); false mirrors top-to-bottom.
     */
    public fun flipped(current: Matrix3, widthPt: Float, heightPt: Float, horizontal: Boolean): Matrix3 =
        about(current, widthPt, heightPt, if (horizontal) Matrix3.scale(-1f, 1f) else Matrix3.scale(1f, -1f))

    /**
     * ⭐ The image's transform **after** a rotation about its own centre — absolute, like
     * [flipped], and for the same reasons.
     *
     * Four quarter-turns return the original matrix to within float tolerance, because the local
     * factor composes as `T · R(θ)⁴ · T⁻¹` and `R(θ)⁴ = I` for `θ = 90°`. It is *within tolerance*
     * rather than exact only because `cos` and `sin` of a converted 90° are not exactly 0 and 1;
     * nothing accumulates, since each call starts from [current] rather than from a running delta.
     * That distinction is the whole of rule 8 — a delta-based rotate drifts, and SPEC §4.2's worked
     * example is what it drifts into.
     *
     * @param degrees positive is clockwise in page space's y-down convention, matching
     *   [Matrix3.rotate].
     */
    public fun rotated(current: Matrix3, widthPt: Float, heightPt: Float, degrees: Float): Matrix3 =
        about(current, widthPt, heightPt, Matrix3.rotate(radiansOf(degrees)))

    /**
     * [current], with [local] applied about the centre of a `widthPt × heightPt` local box.
     *
     * The clamp is [TextBoxEdit.create]'s: a zero extent would put the centre at an edge and make
     * "about its own centre" mean something else for a degenerate object, which is precisely the
     * object a user has just resized badly and is about to try to fix.
     */
    private fun about(current: Matrix3, widthPt: Float, heightPt: Float, local: Matrix3): Matrix3 {
        val centreX = max(widthPt, ShapeGeometry.MIN_EXTENT_PT) * HALF
        val centreY = max(heightPt, ShapeGeometry.MIN_EXTENT_PT) * HALF
        return current *
            Matrix3.translate(centreX, centreY) *
            local *
            Matrix3.translate(-centreX, -centreY)
    }

    private fun radiansOf(degrees: Float): Float = (degrees.toDouble() * PI / HALF_TURN_DEGREES).toFloat()

    private const val HALF = 0.5f
    private const val HALF_TURN_DEGREES = 180.0

    /** Below this a crop region is a line; the frame is left alone rather than collapsed. */
    private const val MIN_EXTENT = 0.001f
}

/**
 * A polygon crop's bounding box, or null when it has no area.
 *
 * The frame a crop shrinks to is always a rectangle — see [ImageEdit.crop] — so a freehand crop
 * needs its extent even though its clip is a shape.
 */
public fun ImageCrop.Polygon.bounds(): ImageCrop.Rect? {
    if (pointCount < 1) return null
    var left = points[0]
    var right = points[0]
    var top = points[1]
    var bottom = points[1]
    for (index in 1 until pointCount) {
        val x = points[index * 2]
        val y = points[index * 2 + 1]
        if (x < left) left = x
        if (x > right) right = x
        if (y < top) top = y
        if (y > bottom) bottom = y
    }
    if (right <= left || bottom <= top) return null
    return ImageCrop.Rect(left, top, right, bottom)
}
