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
}

/**
 * ⭐ ROADMAP M8c — a draggable, rotatable straight edge that strokes snap to.
 *
 * ## ⚠️ How the snap actually happens, and why it is not a per-sample callback
 *
 * The snap is an **orthogonal projection onto a line**, which is an affine map — so it is applied
 * by handing `InProgressStrokesView.startStroke` a `motionEventToWorldTransform` of
 * `projection ∘ viewToPage` instead of `viewToPage`. Three things follow, and all three are the
 * reason it is done this way:
 *
 * - **It costs nothing per sample.** The matrix is composed once, at `ACTION_DOWN`. Ink then maps
 *   every sample through it in native code, which it was going to do anyway. There is no allocation
 *   and no arithmetic added to the pen-to-pixel path at all (CLAUDE.md rule 7).
 * - **The whole `MotionEvent` still goes to `addToStroke`.** CLAUDE.md is explicit that hand-built
 *   `StrokeInput`s *"compile, draw, and silently drop"* pressure, tilt, orientation, tool type and
 *   every historical point. Projecting the event ourselves would mean either rebuilding those
 *   inputs or copying and re-transforming the event per move; the matrix keeps ink's own path.
 * - **Historical samples and the predicted tail are projected too**, because they travel through
 *   the same transform. A ruler that snapped the samples the app happened to see and not the ones
 *   ink interpolated between them would produce a line with a wobble at every frame boundary.
 *
 * The composed matrix is **singular** — that is what a projection is — and that is safe here only
 * because `strokeToWorldTransform` is the identity (SPEC §4.4 rule 1: stroke space *is* page
 * space), so nothing ever inverts it. If that ever stops being true this stops working, which is
 * why it is stated here rather than discovered.
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
     * [strokeTransform]; this is for anything on this side of the boundary that has to agree with
     * where the ink will land.
     */
    fun project(x: Float, y: Float, into: PagePointF) {
        val along = (x - centreXPt) * dirX + (y - centreYPt) * dirY
        into.set(centreXPt + along * dirX, centreYPt + along * dirY)
    }

    /**
     * [viewToPage] followed by the projection — what `startStroke` is given instead of the camera's
     * own matrix. See the class KDoc for why this is a matrix and not a per-sample hook.
     *
     * Returns a reused instance, like [CameraMatrix] does and for the same reason: this runs once
     * per stroke and the alternative is a `Matrix` allocated at every `ACTION_DOWN`.
     */
    fun strokeTransform(viewToPage: Matrix): Matrix {
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
        composed.set(viewToPage)
        // `post`, not `pre`: the event's coordinates go through the camera first and land in page
        // space, and only then are they projected onto a line that is defined in page space.
        composed.postConcat(projection)
        return composed
    }

    private val projection = Matrix()
    private val composed = Matrix()
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
        val across = (point.x - centreXPt) * dirY - (point.y - centreYPt) * dirX
        // A constant reach on the glass, like every other grab in this module: `dp × density ÷
        // camera.scale`. ⚠️ Not `÷ pixelsPerPoint` — that is the conversion for a *width authored in
        // page points*, and confusing the two shrank M8b's endpoint binding by the density factor.
        val reach = GRAB_REACH_DP * displayDensity / camera.scale
        if (abs(across) > reach || abs(along) > LENGTH_PT / 2f + reach) return false

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

        /** How far off the edge a finger may land and still grab it. About 8 mm on the glass. */
        const val GRAB_REACH_DP = 24f

        /** The outer fraction of each half that rotates rather than moves. */
        const val ROTATE_ZONE = 0.7f
    }
}
