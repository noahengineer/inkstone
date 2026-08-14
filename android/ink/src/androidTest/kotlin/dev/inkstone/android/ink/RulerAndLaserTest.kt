package dev.inkstone.android.ink

import android.graphics.Matrix
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.inkstone.core.model.Camera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ⭐ ROADMAP M8c — the straight edge and the laser pointer.
 *
 * ## What is actually asserted, and what is not
 *
 * CLAUDE.md asks that a test say which seam it reaches, so that a green tick does not imply more
 * than it establishes.
 *
 * **Asserted here**: the ruler's projection arithmetic; that the matrix handed to `startStroke`
 * produces the *same* points as that arithmetic, which is the claim the whole design rests on; the
 * angle a numeric entry field would read and write; which part of the edge a finger grabs and what
 * that does to it; and the laser's sampling, fade and bounded buffer.
 *
 * **Not asserted here**: that `InProgressStrokesView` accepts a singular
 * `motionEventToWorldTransform`. That is a native library's behaviour on a code path this suite
 * cannot reach without an `Activity`, and it is the one thing about the ruler that could still be
 * wrong — see [RulerController]'s KDoc for why it is expected to work and what would make it stop.
 * The emulator walk is what closes it.
 *
 * **Not assertable at all**: that the laser writes no op. There is nothing to assert *against* —
 * [LaserController] is constructed with a callback and nothing else, so it holds no [StrokeStore],
 * no [PageOpRecorder] and no route to either. That is the guarantee, and it is structural rather
 * than behavioural on purpose; a test could only ever confirm that a thing with no phone did not
 * make a call.
 *
 * Geometry here is arithmetic and `MotionEvent`s, with no `androidx.ink` stroke anywhere, so this
 * file is independent of the ten M1 failures on x86_64.
 */
@RunWith(AndroidJUnit4::class)
class RulerAndLaserTest {

    private var changes = 0

    private fun ruler(): RulerController = RulerController(
        displayDensity = 1f,
        viewportCentre = { PagePointF(CENTRE_X, CENTRE_Y) },
        onChanged = { changes++ },
    ).also { it.enabled = true }

    private fun event(action: Int, x: Float, y: Float, atMs: Long = downTime): MotionEvent =
        MotionEvent.obtain(downTime, atMs, action, x, y, 0)

    private val downTime = SystemClock.uptimeMillis()

    // -----------------------------------------------------------------------
    // The ruler
    // -----------------------------------------------------------------------

    /** Switching it on for the first time puts it where the user is looking, not at the origin. */
    @Test
    fun enablingTheRulerPlacesItAtTheCentreOfTheViewport() {
        val ruler = ruler()

        assertEquals(CENTRE_X, ruler.centreXPt, TOLERANCE)
        assertEquals(CENTRE_Y, ruler.centreYPt, TOLERANCE)
    }

    /**
     * The projection is perpendicular onto the edge's line, in page space.
     *
     * Three angles, because a horizontal edge is the one case where a wrong sign or a swapped
     * component still produces the right answer.
     */
    @Test
    fun aPointProjectsPerpendicularlyOntoTheEdge() {
        val ruler = ruler()
        val onto = PagePointF()

        ruler.project(CENTRE_X - OFFSET, CENTRE_Y + OFFSET, onto)
        assertEquals("a horizontal edge did not keep x", CENTRE_X - OFFSET, onto.x, TOLERANCE)
        assertEquals("a horizontal edge did not flatten y", CENTRE_Y, onto.y, TOLERANCE)

        ruler.angleDegrees = QUARTER_TURN
        ruler.project(CENTRE_X + OFFSET, CENTRE_Y - OFFSET, onto)
        assertEquals("a vertical edge did not flatten x", CENTRE_X, onto.x, TOLERANCE)
        assertEquals("a vertical edge did not keep y", CENTRE_Y - OFFSET, onto.y, TOLERANCE)

        // On a 45° edge, a point offset equally in +x and -y is exactly perpendicular to it, so it
        // collapses onto the centre. Nothing else about the arithmetic produces that.
        ruler.angleDegrees = EIGHTH_TURN
        ruler.project(CENTRE_X + OFFSET, CENTRE_Y - OFFSET, onto)
        assertEquals(CENTRE_X, onto.x, TOLERANCE)
        assertEquals(CENTRE_Y, onto.y, TOLERANCE)
    }

    /**
     * ⭐ **The matrix and the arithmetic agree**, which is the claim the whole snap rests on.
     *
     * The snap is applied by composing a projection onto `startStroke`'s
     * `motionEventToWorldTransform`, so ink maps every sample — historical, live and predicted —
     * through it in native code at no per-sample cost. [RulerController.project] is the same
     * projection expressed for anything on this side of the boundary. If the two ever disagree, the
     * ink lands somewhere the app's own idea of the edge says it should not, and nothing would
     * report it.
     *
     * The camera matrix here is the identity, so view space *is* page space and what is left is the
     * projection alone.
     */
    @Test
    fun theStrokeTransformProjectsExactlyWhereTheArithmeticSays() {
        val ruler = ruler()
        ruler.angleDegrees = EIGHTH_TURN
        val expected = PagePointF()
        ruler.project(CENTRE_X + OFFSET, CENTRE_Y + HALF_OFFSET, expected)

        val mapped = floatArrayOf(CENTRE_X + OFFSET, CENTRE_Y + HALF_OFFSET)
        ruler.strokeTransform(Matrix()).mapPoints(mapped)

        assertEquals(expected.x, mapped[0], TOLERANCE)
        assertEquals(expected.y, mapped[1], TOLERANCE)
    }

    /**
     * The guide is a **line**, not the drawn segment.
     *
     * Clamping to the edge's length would pile every sample past the end onto one point — a blob
     * where the user drew off the end — and a real straight edge does not do that either.
     */
    @Test
    fun drawingPastTheEndOfTheEdgeStaysOnItsLine() {
        val ruler = ruler()
        val onto = PagePointF()

        ruler.project(CENTRE_X + ruler.lengthPt, CENTRE_Y + OFFSET, onto)

        assertEquals(CENTRE_X + ruler.lengthPt, onto.x, TOLERANCE)
        assertEquals(CENTRE_Y, onto.y, TOLERANCE)
    }

    /** The angle a numeric field would show is normalised, so -30 and 330 are one edge. */
    @Test
    fun theAngleIsNormalisedForNumericEntry() {
        val ruler = ruler()

        ruler.angleDegrees = -THIRTY
        assertEquals(FULL_TURN - THIRTY, ruler.angleDegrees, TOLERANCE)

        ruler.angleDegrees = FULL_TURN + THIRTY
        assertEquals(THIRTY, ruler.angleDegrees, TOLERANCE)
    }

    /**
     * A finger on the middle of the edge moves it; a finger anywhere else is not the ruler's.
     *
     * The rejection is the half that matters: [RulerController.begin] returning false is what lets
     * the pointer fall through to [CameraGestureTracker], so a ruler that claimed everything would
     * make the page unpannable while it was switched on.
     */
    @Test
    fun aFingerOnTheMiddleOfTheEdgeDragsIt() {
        val ruler = ruler()

        val missed = ruler.begin(event(MotionEvent.ACTION_DOWN, CENTRE_X, CENTRE_Y + FAR), 0, 0, Camera.Identity)
        assertFalse("the ruler claimed a pointer nowhere near it", missed)

        val grabbed = ruler.begin(event(MotionEvent.ACTION_DOWN, CENTRE_X, CENTRE_Y + NEAR), 0, 0, Camera.Identity)
        assertTrue("the ruler did not claim a pointer on its edge", grabbed)
        ruler.extend(event(MotionEvent.ACTION_MOVE, CENTRE_X + DRAG, CENTRE_Y + NEAR + DRAG), Camera.Identity)
        ruler.end()

        assertEquals(CENTRE_X + DRAG, ruler.centreXPt, TOLERANCE)
        assertEquals(CENTRE_Y + DRAG, ruler.centreYPt, TOLERANCE)
        assertEquals("the angle changed during a move", 0f, ruler.angleDegrees, TOLERANCE)
    }

    /**
     * A finger near an **end** turns it about its centre, and the centre does not move.
     *
     * Rotating about the grabbed end instead would walk the edge across the page as it turned,
     * which is what a protractor does not do.
     */
    @Test
    fun aFingerNearAnEndRotatesTheEdgeAboutItsCentre() {
        val ruler = ruler()
        val end = CENTRE_X + ruler.lengthPt / 2f - 1f

        assertTrue(ruler.begin(event(MotionEvent.ACTION_DOWN, end, CENTRE_Y), 0, 0, Camera.Identity))
        ruler.extend(event(MotionEvent.ACTION_MOVE, CENTRE_X, CENTRE_Y + FAR), Camera.Identity)
        ruler.end()

        assertEquals(QUARTER_TURN, ruler.angleDegrees, ANGLE_TOLERANCE)
        assertEquals("the centre moved while rotating", CENTRE_X, ruler.centreXPt, TOLERANCE)
        assertEquals("the centre moved while rotating", CENTRE_Y, ruler.centreYPt, TOLERANCE)
    }

    /** Switched off, it claims nothing and snaps nothing. */
    @Test
    fun aDisabledRulerClaimsNoPointer() {
        val ruler = ruler()
        ruler.enabled = false

        assertFalse(ruler.begin(event(MotionEvent.ACTION_DOWN, CENTRE_X, CENTRE_Y), 0, 0, Camera.Identity))
    }

    // -----------------------------------------------------------------------
    // The laser
    // -----------------------------------------------------------------------

    private fun laser() = LaserController { changes++ }

    /** A sweep is sampled, and the samples are the trail. */
    @Test
    fun aSweepIsSampledIntoTheTrail() {
        val laser = laser()

        laser.begin(event(MotionEvent.ACTION_DOWN, TRAIL_X, TRAIL_Y), 0, 0, Camera.Identity)
        laser.extend(event(MotionEvent.ACTION_MOVE, TRAIL_X + STEP, TRAIL_Y), Camera.Identity)
        laser.extend(event(MotionEvent.ACTION_MOVE, TRAIL_X + STEP * 2, TRAIL_Y), Camera.Identity)

        assertEquals(3, laser.count)
        assertEquals(TRAIL_X, laser.xAt(0), TOLERANCE)
        assertEquals(TRAIL_X + STEP * 2, laser.xAt(2), TOLERANCE)
    }

    /**
     * A pointer that has barely moved does not add a sample.
     *
     * At 240 Hz a slow sweep would otherwise be hundreds of coincident points, each costing a
     * `drawLine` per frame for a picture identical to the one two points would give.
     */
    @Test
    fun aSampleCloserThanTheStepIsDropped() {
        val laser = laser()

        laser.begin(event(MotionEvent.ACTION_DOWN, TRAIL_X, TRAIL_Y), 0, 0, Camera.Identity)
        laser.extend(event(MotionEvent.ACTION_MOVE, TRAIL_X + TINY, TRAIL_Y), Camera.Identity)

        assertEquals("a sample under the step threshold was kept", 1, laser.count)
    }

    /**
     * The trail fades to nothing and then stops asking for frames.
     *
     * The false return is what ends the animation: [ToolOverlayView] schedules the next frame only
     * while [LaserController.prune] says something is still visible, so a laser that never expired
     * would repaint forever after the pen was put down.
     */
    @Test
    fun theTrailFadesAndThenStopsAskingForFrames() {
        val laser = laser()
        laser.begin(event(MotionEvent.ACTION_DOWN, TRAIL_X, TRAIL_Y), 0, 0, Camera.Identity)

        assertEquals(1f, laser.brightnessAt(0, downTime), TOLERANCE)
        assertEquals(HALF, laser.brightnessAt(0, downTime + LaserController.LIFETIME_MS / 2), FADE_TOLERANCE)
        assertTrue("the trail expired early", laser.prune(downTime + LaserController.LIFETIME_MS / 2))
        assertFalse("the trail outlived its lifetime", laser.prune(downTime + LaserController.LIFETIME_MS + 1))
        assertTrue("an expired trail still has samples", laser.isEmpty)
    }

    /**
     * A sweep longer than the buffer keeps the newest samples and drops the oldest.
     *
     * The ring is what makes the trail's cost bounded rather than proportional to how long somebody
     * has been waving the pen about, and an off-by-one in it would either read a stale slot or
     * silently stop appending.
     */
    @Test
    fun aVeryLongSweepIsBoundedByTheBuffer() {
        val laser = laser()
        laser.begin(event(MotionEvent.ACTION_DOWN, TRAIL_X, TRAIL_Y), 0, 0, Camera.Identity)
        val samples = LaserController.CAPACITY * 2
        for (index in 1..samples) {
            laser.extend(event(MotionEvent.ACTION_MOVE, TRAIL_X + STEP * index, TRAIL_Y), Camera.Identity)
        }

        assertEquals(LaserController.CAPACITY, laser.count)
        assertEquals(
            "the newest sample is not at the head of the ring",
            TRAIL_X + STEP * samples,
            laser.xAt(laser.count - 1),
            TOLERANCE,
        )
    }

    /** A page turn drops the trail immediately — a mark left over points at whatever is now there. */
    @Test
    fun clearingDropsTheTrailAtOnce() {
        val laser = laser()
        laser.begin(event(MotionEvent.ACTION_DOWN, TRAIL_X, TRAIL_Y), 0, 0, Camera.Identity)

        laser.clear()

        assertTrue(laser.isEmpty)
        assertFalse(laser.isActive)
    }

    private companion object {
        const val CENTRE_X = 300f
        const val CENTRE_Y = 400f
        const val OFFSET = 60f
        const val HALF_OFFSET = 30f
        const val TOLERANCE = 0.01f
        const val ANGLE_TOLERANCE = 0.1f
        const val FADE_TOLERANCE = 0.02f
        const val QUARTER_TURN = 90f
        const val EIGHTH_TURN = 45f
        const val FULL_TURN = 360f
        const val THIRTY = 30f
        const val HALF = 0.5f

        /** Well outside the grab reach of 24 dp at density 1 and camera scale 1. */
        const val FAR = 200f

        /** Inside it. */
        const val NEAR = 8f
        const val DRAG = 25f

        const val TRAIL_X = 120f
        const val TRAIL_Y = 240f
        const val STEP = 10f

        /** Under [LaserController.STEP_PT]. */
        const val TINY = 0.4f
    }
}
