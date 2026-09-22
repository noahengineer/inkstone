package dev.inkstone.android.ink

import android.graphics.Matrix
import android.view.MotionEvent
import dev.inkstone.core.model.Camera
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * ⭐ ROADMAP M8c — the straight edge, as the toolbar sees it.
 *
 * An interface rather than six more members on [InkSurfaceView], for the reason [InkSelection] is
 * one: the surface's own API should be about the surface, and `surface.ruler.angleDegrees = 30f`
 * reads as what it is.
 *
 * [angleDegrees] is settable because ROADMAP asks for **numeric angle entry** — dragging a ruler to
 * exactly 30° is not something a finger can do, and a protractor that only reports its angle is
 * half a tool. It is degrees rather than radians for the same reason: it is the number a person
 * types.
 */
public interface InkRuler {

    /**
     * Whether the straight edge is on the page and snapping strokes to it.
     *
     * ⚠️ Turning it on **places it** the first time, at the centre of what is on screen — a ruler
     * that appeared at the page's origin would be off the glass at any zoom, and a tool whose first
     * appearance is invisible reads as a broken button.
     */
    public var enabled: Boolean

    /** The centre of the edge, in page points. Never null; the ruler always has a position. */
    public val centreXPt: Float
    public val centreYPt: Float

    /** How long the drawn edge is, in page points. The *guide* is the whole line — see [InkRuler]. */
    public val lengthPt: Float

    /**
     * The edge's angle in degrees, clockwise from the page's +x axis, normalised to `[0, 360)`.
     *
     * Clockwise because page space has y growing **downwards** (SPEC §4.4), so the direction a
     * number increases in on screen is the direction it increases in here. A protractor that ran
     * anticlockwise while the ruler turned clockwise would be worse than none.
     */
    public var angleDegrees: Float

    /** Move the edge without turning it. */
    public fun placeAt(xPt: Float, yPt: Float)

    /**
     * How wide the body is **on the glass**, in dp, and which side of the guide it hangs off.
     *
     * On this interface for [lengthPt]'s reason — the chrome is drawn by a different class from the
     * one that owns the geometry, and the grab is decided by a third concern again. One value
     * everybody reads is what keeps the drawn ruler and the grabbable ruler the same rectangle; two
     * copies of it drift the first time anybody adjusts the look, leaving an edge you can see and
     * cannot pick up.
     *
     * The normal is a unit vector in page space pointing at the body. For a horizontal edge it is
     * `(0, 1)`: page space has y growing downwards (SPEC 4.4), so the body hangs below the line and
     * the pen runs along its top, the way a set square sits on paper.
     */
    public val bodyWidthDp: Float

    public val bodyNormalX: Float

    public val bodyNormalY: Float
}

/**
 * ⭐ ROADMAP M8c — a draggable, rotatable straight edge that strokes snap to.
 *
 * ## ⚠️ How the snap happens, and the assumption that made the ruler unusable
 *
 * The snap is an **orthogonal projection onto a line**, which is an affine map. M8c therefore
 * applied it by handing `InProgressStrokesView.startStroke` a `motionEventToWorldTransform` of
 * `projection ∘ viewToPage`, on the reasoning that it costs nothing per sample and keeps ink's own
 * path — and closed with:
 *
 * > *"The composed matrix is **singular** — that is what a projection is — and that is safe here
 * > only because `strokeToWorldTransform` is the identity, so nothing ever inverts it."*
 *
 * **It is inverted, and the ruler has therefore never worked.** `InProgressShapesView` inverts
 * `motionEventToWorldTransform` in `strokeUnitLengthCm` to work out how many centimetres a stroke
 * unit is — which the fountain pen's `INPUT_SPEED_IN_CENTIMETERS_PER_SECOND` behaviour needs — and
 * throws `IllegalArgumentException: motionEventToWorldTransform must be invertible` from inside
 * `startStroke`. So **every** stylus stroke drawn while the edge was out threw at ACTION_DOWN.
 * Not "did not snap": did not draw. That is `RulerReachTest` in `:app`, and it is the one thing
 * `RulerAndLaserTest`'s KDoc already listed as unverified — *"the emulator walk is what closes
 * it"*, and the walk never happened.
 *
 * ### What it does instead
 *
 * The transform ink is given is the plain `viewToPage` again, and the **event** is projected
 * before it is handed over ([eventProjection]) — in view space, so ink's own matrix is untouched
 * and `strokeUnitLengthCm` is measured against a well-conditioned camera. Three properties of the
 * old design survive, and they are why this is a copy of the event rather than hand-built inputs:
 *
 * - **The whole `MotionEvent` still goes to `addToStroke`.** CLAUDE.md is explicit that hand-built
 *   `StrokeInput`s *"compile, draw, and silently drop"* pressure, tilt, orientation, tool type and
 *   every historical point. `MotionEvent.transform` moves the coordinates of every pointer **and
 *   every historical sample** and leaves the rest alone.
 * - **The predicted tail is projected too**, by projecting the predictor's event as well. A ruler
 *   that straightened the real samples and not the predicted ones would show a hook at the nib.
 * - **The cost is bounded and only paid while the edge is out.** One pooled `MotionEvent.obtain`
 *   and one native `transform` per event, recycled immediately — `StrokeInputPool` converts an
 *   event into a `MutableStrokeInputBatch` synchronously on the calling thread, so nothing retains
 *   it (checked against the 1.0.0 bytecode, not assumed).
 *
 * A near-singular matrix — `εI + (1−ε)P` — was the other candidate and is worse than it looks:
 * its condition number is `1/ε`, and `strokeUnitLengthCm` averages the row norms of the inverse,
 * so any ε small enough to straighten a line is large enough to report a stroke unit tens of times
 * too long. The fountain pen would then size itself off a speed it never travelled.
 *
 * ## ⚠️ The body sits BESIDE the guide, never on top of it
 *
 * For two milestones the translucent slab was drawn centred on the line strokes snap to, and
 * `ToolOverlayView` argued the case: chrome that disagreed with the geometry by half the slab's
 * width would land every ruled stroke visibly beside the guide. The conclusion was right and the
 * premise had a third option nobody took — **move the body, keep the line**. As drawn, the ink
 * landed down the middle of a 44 dp band, so the ruler covered the line you had just ruled and
 * whatever you were ruling it against. The user's words: *"otherwise it covers it."*
 *
 * So the guide is unchanged — [project] and [eventProjection] are exactly what they were — and the
 * slab is drawn from the line along [bodyNormalX] / [bodyNormalY], which is the page-space
 * "downhill" side of the edge. That is also what a real straight edge is: a body you hold with one
 * hand and an edge the pen runs along, with nothing of the ruler between the nib and the paper.
 *
 * The grab follows the body ([begin]), because the body is the part a finger aims at.
 *
 * ## The guide is a line; the drawn edge is a segment
 *
 * [project] does **not** clamp to [lengthPt]. Clamping would collapse every sample past the end of
 * the edge onto a single point — a blob where the user drew off the end — and running off a
 * straight edge on paper does not do that either: the pen simply carries on where the edge was
 * pointing. What [lengthPt] decides is where the chrome is drawn and where a finger can grab it.
 *
 * ## What manipulates it
 *
 * A **finger**, and only a finger: the pen is what draws along the edge, so a pen that also moved
 * it would make drawing and adjusting the same gesture. The grab is checked before
 * [CameraGestureTracker] sees the pointer, so putting a finger on the ruler drags the ruler and
 * putting it anywhere else pans the page.
 *
 * ⚠️ **The snap itself is stylus-only** ([appliesTo]), which is the PalmGuard rule the rest of this
 * module already follows: `TOOL_TYPE_STYLUS` or nothing. A palm that got as far as starting a
 * stroke must not have that stroke ruled straight and made to look deliberate.
 *
 * @param viewportCentre where to put the edge the first time it is switched on, in page points, or
 *   null when the surface has not been laid out yet — in which case it stays where it is and the
 *   next enable places it.
 */
internal class RulerController(
    private val displayDensity: Float,
    private val viewportCentre: () -> PagePointF?,
    private val onChanged: () -> Unit,
) : InkRuler {

    /** What a finger that has grabbed the edge is doing to it. */
    private enum class Grip { MOVE, ROTATE }

    override var enabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value && !placed) viewportCentre()?.let { placeAt(it.x, it.y) }
            // A ruler switched off mid-drag must not keep the pointer: the finger's UP would then
            // arrive at a controller that is no longer participating and the camera would never
            // get it back.
            if (!value) cancel()
            onChanged()
        }

    override var centreXPt: Float = 0f
        private set

    override var centreYPt: Float = 0f
        private set

    override val lengthPt: Float get() = LENGTH_PT

    override var angleDegrees: Float
        get() = Math.toDegrees(angleRadians.toDouble()).toFloat().normalisedDegrees()
        set(value) {
            setAngleRadians(Math.toRadians(value.toDouble()).toFloat())
            onChanged()
        }

    override fun placeAt(xPt: Float, yPt: Float) {
        centreXPt = xPt
        centreYPt = yPt
        placed = true
        onChanged()
    }

    /**
     * Whether the ruler has ever been given a position.
     *
     * Not the same as "has a non-zero centre": a user may legitimately drag it to the page's origin,
     * and a ruler that jumped back to the middle of the screen the next time it was switched on
     * would be a tool that forgets where you put it.
     */
    private var placed = false

    private var angleRadians = 0f

    /** The edge's unit direction, cached — [project] reads it and nothing recomputes a cosine. */
    private var dirX = 1f
    private var dirY = 0f

    override val bodyWidthDp: Float get() = SLAB_WIDTH_DP

    /**
     * The unit normal pointing at the body — see [InkRuler.bodyWidthDp].
     *
     * Turning the edge past 180° swaps the body to the other side, exactly as flipping a real ruler
     * over does, because it is derived from the live direction rather than cached.
     *
     * ⚠️ It is the negation of the sign [begin] measures `across` in — see the note there. The
     * two are derived from one another rather than written twice, because a chrome side and a grab
     * side that disagree is a ruler you can see but not pick up.
     */
    override val bodyNormalX: Float get() = -dirY

    override val bodyNormalY: Float get() = dirX

    private fun setAngleRadians(radians: Float) {
        angleRadians = radians
        dirX = cos(radians)
        dirY = sin(radians)
    }

    // --- The snap -----------------------------------------------------------

    /**
     * True when a stroke starting from this pointer should be ruled straight.
     *
     * Stylus only (the PalmGuard rule), and not while a finger is moving the edge — a ruler being
     * adjusted is not a ruler being drawn along, and snapping to a line that is turning under the
     * pen would produce a stroke that matches no edge that ever existed.
     */
    fun appliesTo(event: MotionEvent, pointerIndex: Int): Boolean =
        enabled && !isActive && event.isStylusPointer(pointerIndex)

    /**
     * `(x, y)` projected perpendicularly onto the edge's line, in page space.
     *
     * Two multiplies, an add, and two more — exactly what the class KDoc claims, and allocation-free
     * because [into] is the caller's. Not used by the wet stroke, which goes through
     * [eventProjection]; this is for anything on this side of the boundary that has to agree with
     * where the ink will land.
     */
    fun project(x: Float, y: Float, into: PagePointF) {
        val along = (x - centreXPt) * dirX + (y - centreYPt) * dirY
        into.set(centreXPt + along * dirX, centreYPt + along * dirY)
    }

    /**
     * The projection expressed in **view** coordinates — `pageToView ∘ project ∘ viewToPage` — for
     * `MotionEvent.transform` to apply to a copy of the event.
     *
     * View space in and view space out, because that is what a `MotionEvent` holds. Everything
     * downstream then runs exactly as it does with no ruler: ink is given the camera's own
     * `viewToPage`, which is invertible, and the samples it reads have already been flattened onto
     * the edge.
     *
     * ⚠️ [viewToPage] must be the camera in effect **at stroke start**, not the live one. Ink
     * captures its transform at `startStroke`; projecting later samples through a camera that has
     * since moved would put them on the edge as it is now and then map them with the camera as it
     * was, which is a straight line in the wrong place.
     *
     * Returns a reused instance, like [CameraMatrix] does and for the same reason: this runs per
     * event on the ink path and the alternative is a `Matrix` allocated there (CLAUDE.md rule 7).
     */
    fun eventProjection(viewToPage: Matrix): Matrix {
        buildProjection()
        // The camera is a scale and a translation, so it always inverts; the guard is for the
        // frame before layout, where the scale can still be zero. Answering the identity there
        // means one unsnapped sample, not a crash.
        if (!viewToPage.invert(pageToView)) return IDENTITY
        composed.set(viewToPage)
        // `post`, not `pre`: the event's coordinates go through the camera first and land in page
        // space, are projected onto a line that is defined in page space, and come back.
        composed.postConcat(projection)
        composed.postConcat(pageToView)
        return composed
    }

    private fun buildProjection() {
        // q = c + ((p - c) · u) u, written out. The linear part is the outer product `u ⊗ u`, whose
        // determinant is zero — see the class KDoc on why a singular matrix is safe here.
        projectionValues[Matrix.MSCALE_X] = dirX * dirX
        projectionValues[Matrix.MSKEW_X] = dirX * dirY
        projectionValues[Matrix.MTRANS_X] = centreXPt - (dirX * dirX * centreXPt + dirX * dirY * centreYPt)
        projectionValues[Matrix.MSKEW_Y] = dirX * dirY
        projectionValues[Matrix.MSCALE_Y] = dirY * dirY
        projectionValues[Matrix.MTRANS_Y] = centreYPt - (dirX * dirY * centreXPt + dirY * dirY * centreYPt)
        projectionValues[Matrix.MPERSP_0] = 0f
        projectionValues[Matrix.MPERSP_1] = 0f
        projectionValues[Matrix.MPERSP_2] = 1f
        projection.setValues(projectionValues)
    }

    private val projection = Matrix()
    private val composed = Matrix()
    private val pageToView = Matrix()
    private val projectionValues = FloatArray(MATRIX_VALUES)

    // --- The finger that moves it -------------------------------------------

    var pointerId: Int = MotionEvent.INVALID_POINTER_ID
        private set

    val isActive: Boolean get() = pointerId != MotionEvent.INVALID_POINTER_ID

    private var grip = Grip.MOVE
    private var grabOffsetX = 0f
    private var grabOffsetY = 0f
    private var grabbedFarEnd = false
    private val point = PagePointF()

    /**
     * Take this pointer if it landed on the edge.
     *
     * @return true when the ruler has claimed the pointer, which is the caller's signal not to give
     *   it to the camera. False leaves the event completely untouched.
     */
    fun begin(event: MotionEvent, pointerIndex: Int, pointerId: Int, camera: Camera): Boolean {
        if (!enabled || isActive) return false
        toPageSpace(event.getX(pointerIndex), event.getY(pointerIndex), camera, point)
        val along = (point.x - centreXPt) * dirX + (point.y - centreYPt) * dirY
        // The perpendicular offset is the 2D cross product with the unit direction — signed
        // distance from the line, no square root.
        //
        // ⚠️ **Negative is the body's side.** `(dirY, -dirX)` is the normal this measures against
        // and [bodyNormalX] is its negation, so a point on the slab has `across < 0`. Written once,
        // here, because a grab region on the opposite side from the drawn body is a ruler you can
        // see and cannot pick up — and at 12 % alpha nobody would work out why.
        val across = (point.x - centreXPt) * dirY - (point.y - centreYPt) * dirX
        // A constant reach on the glass, like every other grab in this module: `dp × density ÷
        // camera.scale`. ⚠️ Not `÷ pixelsPerPoint` — that is the conversion for a *width authored in
        // page points*, and confusing the two shrank M8b's endpoint binding by the density factor.
        val reach = GRAB_REACH_DP * displayDensity / camera.scale
        val body = SLAB_WIDTH_DP * displayDensity / camera.scale
        // Asymmetric on purpose: the whole body on one side, a hairline's grace on the drawing
        // side. A finger reaching for the ruler aims at the slab it can see; the paper above the
        // edge is where the *pen* goes, and claiming it would be claiming the page.
        if (across > reach || across < -body || abs(along) > LENGTH_PT / 2f + reach) return false

        this.pointerId = pointerId
        grip = if (abs(along) > LENGTH_PT / 2f * ROTATE_ZONE) Grip.ROTATE else Grip.MOVE
        grabbedFarEnd = along < 0f
        grabOffsetX = centreXPt - point.x
        grabOffsetY = centreYPt - point.y
        return true
    }

    fun extend(event: MotionEvent, camera: Camera) {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        toPageSpace(event.getX(index), event.getY(index), camera, point)
        when (grip) {
            // The offset is kept so the edge does not jump its centre under the finger the instant
            // it is touched — the same reason a drag anywhere else in this app composes against
            // where the gesture started rather than against where it is now.
            Grip.MOVE -> {
                centreXPt = point.x + grabOffsetX
                centreYPt = point.y + grabOffsetY
            }

            // Turn about the centre, following the grabbed end. Grabbing the *other* end and
            // dragging must turn the edge towards the finger, not flip it 180° away, so the half
            // that was grabbed decides the sign.
            Grip.ROTATE -> {
                val towards = atan2(point.y - centreYPt, point.x - centreXPt)
                setAngleRadians(if (grabbedFarEnd) towards + PI_F else towards)
            }
        }
        onChanged()
    }

    fun end() {
        pointerId = MotionEvent.INVALID_POINTER_ID
    }

    /** Drop the gesture without moving anything — an `ACTION_CANCEL`, or the ruler being switched off. */
    fun cancel() {
        pointerId = MotionEvent.INVALID_POINTER_ID
    }

    private fun toPageSpace(viewX: Float, viewY: Float, camera: Camera, into: PagePointF) {
        into.set((viewX - camera.translate.x) / camera.scale, (viewY - camera.translate.y) / camera.scale)
    }

    private fun Float.normalisedDegrees(): Float {
        val wrapped = this % FULL_TURN
        return if (wrapped < 0f) wrapped + FULL_TURN else wrapped
    }

    private companion object {
        val IDENTITY = Matrix()

        const val MATRIX_VALUES = 9
        const val PI_F = Math.PI.toFloat()
        const val FULL_TURN = 360f

        /**
         * The edge's length in page points — about two thirds of a Letter page's width.
         *
         * Long enough to rule a line across a diagram and short enough that both ends are reachable
         * without panning at a working zoom. ⚠️ Not measured on a device (SPEC §4.8.7 records the
         * same caveat for the draw-and-hold constants).
         */
        const val LENGTH_PT = 400f

        /** How far past the edge, on the **pen's** side, a finger may land and still grab it. */
        const val GRAB_REACH_DP = 12f

        /**
         * How wide the body is, on the glass — read by `ToolOverlayView` to draw it and by [begin]
         * to decide what a finger has grabbed.
         *
         * ⚠️ **One constant, not one each.** The chrome and the grab region are the same
         * rectangle described twice, and two copies of it drift the first time anybody adjusts the
         * look — leaving a ruler that is grabbable somewhere it is not drawn, which is
         * `DryBox`'s *"selectable with a lasso but not with a tap"* in a tool made of nothing but
         * chrome.
         */
        const val SLAB_WIDTH_DP = 44f

        /** The outer fraction of each half that rotates rather than moves. */
        const val ROTATE_ZONE = 0.7f
    }
}
