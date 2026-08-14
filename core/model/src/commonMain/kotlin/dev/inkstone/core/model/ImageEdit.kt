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
}
