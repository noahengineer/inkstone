package dev.inkstone.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sqrt

/**
 * ⭐ **Make it a perfect one** — what a drawn shape becomes when the selection bar's
 * `Circle` / `Square` / `Equal` / `45°` chip is pressed, in the user's words:
 *
 * > *"it should have buttons for makeing it a perfect circle for example or perfect triangle"*
 *
 * Pure arithmetic in `:core:model` for [SnapGrid]'s and [AlignmentGuides]' reason: the decision is
 * plain data, and what `:android:ink` does with the answer is write one absolute op.
 *
 * ## ⛔ It writes the **outline**, not a scale — and the first version wrote a scale
 *
 * > *"if you make it a perfect square for example the line sizes get messed up"*
 *
 * Exactly right, and it was this file's own doing. Making a 2:1 oval into a circle by scaling was
 * one absolute `SetTransform` carrying `scale(0.75, 1.5)` — and `PagePainter` **concatenates an
 * object's matrix onto the canvas** and then strokes with a paint of one width (see its
 * `paintObject`), deliberately, so that a move is a matrix and never a rewrite of the geometry. A
 * non-uniform matrix therefore stretches the *pen* as well as the shape: the circle came out twice
 * as thick at the top as at the sides. The shape was right and the ink was wrong.
 *
 * So a closed shape is regularised by writing its **vertices** ([ShapeRecord.points]) and leaving
 * the transform alone. The vertex list is already the outline whenever it is present
 * (`ShapeGeometry.outline`), the payload extents are untouched, and a matrix nobody changed cannot
 * distort a stroke it was not distorting a moment ago.
 *
 * ⚠️ It also means this button **cannot** make an already-squashed shape's ink even again: a shape
 * resized by an edge handle carries an anisotropic matrix from that resize, and its stroke is
 * uneven before this is pressed and equally uneven afterwards. That is the resize's property, not
 * this chip's, and fixing it is a change to how every shape in every note is painted.
 *
 * ## What "regular" means per kind
 *
 * | kind | regular | how the size is chosen |
 * |---|---|---|
 * | [ShapeKind.RECTANGLE] | a square | the mean of the two side lengths |
 * | [ShapeKind.ELLIPSE] | a circle | the mean of the two diameters |
 * | [ShapeKind.TRIANGLE] | equilateral | the mean of the base each side implies |
 * | [ShapeKind.LINE], [ShapeKind.ARROW] | the nearest 45° | length and midpoint unchanged |
 *
 * ⚠️ **The mean, and about the centre**, so the shape stays where it is and stays about as big as
 * it was. Taking the larger extent would grow every shape a user regularises — which reads as the
 * button moving things.
 *
 * ⚠️ **A triangle's "regular" is a fact about [ShapeGeometry]'s outline, not about its box.** The
 * local triangle is apex-at-top-centre with the base along the bottom edge, so equilateral means
 * `height = base × √3/2`; a *square* box gives a triangle noticeably taller than an equilateral one.
 *
 * ⚠️ **A line is an angle, not a box**, and it is the one kind still regularised by its transform.
 * Its box is a rotated band whose height is the pen's width (`ShapeEdit.placeSegment`), so the
 * useful regularisation is the nearest eighth-turn about its own midpoint — a **rotation**, which
 * is rigid and so stretches nothing. Writing its ends as vertices instead would leave the band
 * pointing the old way, and the band is what a tap and a lasso measure against.
 */
public object ShapeRegular {

    /**
     * What making this shape regular changes, or **null when there is nothing to do**.
     *
     * ⚠️ Null for "already regular" is not tidiness. `Undo.inverseOf` gives an op an inverse
     * whatever it carries, so writing a value a shape already has produces a perfectly undoable
     * entry that changes nothing — and the next undo press would be eaten by it. It is also what
     * the chip's visibility is built on, through [isRegular], so the two cannot disagree.
     *
     * @param points the shape's live [ShapeRecord.points], or null if it has never been
     *   point-edited. Passed in rather than derived so that a shape whose corners have been dragged
     *   is measured as it is drawn — which is also what makes pressing this twice a no-op.
     * @param transform the object's live absolute matrix. Read, never written, for a closed shape.
     */
    public fun regularise(
        kind: ShapeKind,
        widthPt: Float,
        heightPt: Float,
        points: List<PagePoint>?,
        transform: Matrix3,
    ): ShapeRegularEdit? {
        if (widthPt < ShapeGeometry.MIN_EXTENT_PT || heightPt < ShapeGeometry.MIN_EXTENT_PT) return null
        if (ShapeGeometry.bends(kind)) {
            return straightened(widthPt, heightPt, transform)?.let { ShapeRegularEdit(transform = it) }
        }
        return regularOutline(kind, widthPt, heightPt, points, transform)?.let { ShapeRegularEdit(points = it) }
    }

    /**
     * True when [regularise] would leave this shape alone — what the chip's presence is built on.
     *
     * Deliberately the same arithmetic rather than a second rule about it: a chip that is offered
     * and does nothing, and a chip that is hidden for a shape it would have fixed, are the two ways
     * this can be wrong and they are both a second opinion about one question.
     */
    public fun isRegular(
        kind: ShapeKind,
        widthPt: Float,
        heightPt: Float,
        points: List<PagePoint>?,
        transform: Matrix3,
    ): Boolean = regularise(kind, widthPt, heightPt, points, transform) == null

    /**
     * The regular outline for a closed shape, in the shape's **own** space, or null when the one it
     * has is already regular.
     *
     * The work is done in **page** space and mapped back, because "regular" is a claim about what
     * the user can see: a shape carrying an anisotropic matrix needs an oval vertex list to draw a
     * circle, and one carrying a rotation needs its square turned to match.
     *
     * ⚠️ The box's two axes are **orthonormalised** before the target is built. A shape that was
     * resized by an edge handle and then rotated has a skewed matrix, and a "square" laid out along
     * two axes that are not at right angles is a rhombus — regular by the arithmetic and visibly
     * not by eye.
     */
    private fun regularOutline(
        kind: ShapeKind,
        widthPt: Float,
        heightPt: Float,
        points: List<PagePoint>?,
        transform: Matrix3,
    ): List<PagePoint>? {
        val inverse = transform.inverse() ?: return null
        val origin = transform.map(PagePoint(0f, 0f))
        val across = transform.map(PagePoint(widthPt, 0f))
        val down = transform.map(PagePoint(0f, heightPt))
        val sideX = hypot(across.x - origin.x, across.y - origin.y)
        val sideY = hypot(down.x - origin.x, down.y - origin.y)
        if (sideX < ShapeGeometry.MIN_EXTENT_PT || sideY < ShapeGeometry.MIN_EXTENT_PT) return null

        // The box's own axes, made orthonormal — see the KDoc on why the second one is corrected.
        val ex = PagePoint((across.x - origin.x) / sideX, (across.y - origin.y) / sideX)
        val rawY = PagePoint((down.x - origin.x) / sideY, (down.y - origin.y) / sideY)
        val skew = ex.x * rawY.x + ex.y * rawY.y
        val ey = normalise(PagePoint(rawY.x - ex.x * skew, rawY.y - ex.y * skew)) ?: return null

        val ratio = if (kind == ShapeKind.TRIANGLE) EQUILATERAL_RATIO else 1f
        // The base each side implies, averaged — see the class KDoc on why the mean.
        val base = (sideX + sideY / ratio) / 2f
        val halfX = base / 2f
        val halfY = base * ratio / 2f

        // The centre of the box on the page, which is where the regular shape is built around.
        val centre = transform.map(ShapeGeometry.centre(widthPt, heightPt))
        fun at(x: Float, y: Float) = inverse.map(
            PagePoint(
                centre.x + ex.x * x * halfX + ey.x * y * halfY,
                centre.y + ex.y * x * halfX + ey.y * y * halfY,
            ),
        )

        // The same vertex order [ShapePoints.of] seeds, so a regularised shape and a freshly drawn
        // one are the same list in the same order and nothing downstream can tell them apart.
        val regular = when (kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> return null
            ShapeKind.RECTANGLE -> listOf(at(-1f, -1f), at(1f, -1f), at(1f, 1f), at(-1f, 1f))
            ShapeKind.ELLIPSE -> listOf(at(0f, -1f), at(1f, 0f), at(0f, 1f), at(-1f, 0f))
            ShapeKind.TRIANGLE -> listOf(at(0f, -1f), at(1f, 1f), at(-1f, 1f))
        }
        val current = points?.takeIf { it.size == regular.size }
            ?: ShapePoints.of(kind, widthPt, heightPt).map { it.local }
        return regular.takeIf { it.movedFrom(current, transform) }
    }

    /**
     * A segment turned about its own midpoint onto the nearest eighth of a turn.
     *
     * ⚠️ Pre-multiplied — the rotation is in **page** space, about a page-space point. Composing it
     * on the other side would turn the segment about a point in its own box, which is a different
     * point on the page for every rotation the shape already has. A rotation is rigid, so unlike a
     * scale it cannot change the ink's width.
     */
    private fun straightened(widthPt: Float, heightPt: Float, transform: Matrix3): Matrix3? {
        val middle = heightPt / 2f
        val a = transform.map(PagePoint(0f, middle))
        val b = transform.map(PagePoint(widthPt, middle))
        val dx = b.x - a.x
        val dy = b.y - a.y
        if (hypot(dx, dy) < ShapeGeometry.MIN_EXTENT_PT) return null
        val angle = atan2(dy, dx)
        val snapped = round(angle / EIGHTH_TURN) * EIGHTH_TURN
        val turn = snapped - angle
        if (abs(turn) < ANGLE_TOLERANCE) return null
        val midX = (a.x + b.x) / 2f
        val midY = (a.y + b.y) / 2f
        return Matrix3.translate(midX, midY) * Matrix3.rotate(turn) * Matrix3.translate(-midX, -midY) * transform
    }

    /**
     * Whether these vertices differ from [current] by anything the eye could catch.
     *
     * ⚠️ Measured in **page** space, through [transform]. Local units are whatever the shape's
     * matrix makes of them — a tenth of a local unit is a tenth of a point on a fresh shape and a
     * hundredth on one scaled ten times — so a local tolerance would make the chip stick around
     * forever on some shapes and vanish too eagerly on others.
     */
    private fun List<PagePoint>.movedFrom(current: List<PagePoint>, transform: Matrix3): Boolean {
        if (size != current.size) return true
        for (index in indices) {
            val a = transform.map(this[index])
            val b = transform.map(current[index])
            if (abs(a.x - b.x) > TOLERANCE_PT || abs(a.y - b.y) > TOLERANCE_PT) return true
        }
        return false
    }

    private fun normalise(v: PagePoint): PagePoint? {
        val length = hypot(v.x, v.y)
        return if (length < EPSILON) null else PagePoint(v.x / length, v.y / length)
    }

    /** An equilateral triangle is `√3/2` as tall as it is wide — see the class KDoc. */
    private val EQUILATERAL_RATIO: Float = (sqrt(3.0) / 2.0).toFloat()

    /** 45°, in radians. The turn a segment is snapped onto a multiple of. */
    private val EIGHTH_TURN: Float = (PI / 4.0).toFloat()

    /** A twentieth of a point on the page: under a pixel at any zoom a person draws at. */
    private const val TOLERANCE_PT = 0.05f

    /** About a twentieth of a degree. Below it the segment is already on the eighth-turn. */
    private const val ANGLE_TOLERANCE = 1e-3f

    private const val EPSILON = 1e-6f
}

/**
 * What making one shape regular writes: a placement, or an outline — never both.
 *
 * Two fields rather than a sealed pair because the caller writes both the same way (one absolute op
 * each, in one gesture) and because it makes the *"a segment moves its box, a closed shape moves its
 * vertices"* split a fact about the data instead of a rule the call site has to remember.
 */
public data class ShapeRegularEdit(val transform: Matrix3? = null, val points: List<PagePoint>? = null)
