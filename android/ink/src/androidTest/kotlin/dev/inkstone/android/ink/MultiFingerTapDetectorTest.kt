package dev.inkstone.android.ink

import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two-finger-undo detector, under the exact sequences that used to kill a legitimate tap.
 *
 * The regressions here are the mechanics behind *"the 2 fingers to undo feels very
 * inconsistent"*: anchors were keyed by pointer **index**, which compacts when the first
 * finger lifts, so a MOVE arriving between the two lifts compared the surviving finger
 * against the *other* finger's anchor and disqualified the tap — depending on which finger
 * lifted first and on digitiser timing. And the timeout ran from the *first* finger's DOWN,
 * so a deliberate, staggered tap was the one most likely to miss the deadline.
 *
 * Geometry is built relative to the platform's own touch slop, so these pass whatever the
 * device profile says slop is.
 */
@RunWith(AndroidJUnit4::class)
internal class MultiFingerTapDetectorTest {

    private lateinit var taps: MultiFingerTapDetector
    private var slop = 0f
    private val downTime = 100_000L

    /** Two fingers a hand-width apart — far past slop, as real fingers are. */
    private var ax = 0f
    private var ay = 0f
    private var bx = 0f
    private var by = 0f

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        taps = MultiFingerTapDetector(context)
        slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        ax = 100f
        ay = 200f
        bx = ax + slop * 20f
        by = ay
    }

    @Test
    fun twoFingerTapFiresWhenTheFirstLandedFingerLiftsFirst() {
        taps.onPointerDown(down(at = 0, ax, ay))
        taps.onPointerDown(pointerDown(at = 20, index = 1, A_ID to (ax to ay), B_ID to (bx to by)))
        taps.onMove(move(at = 40, A_ID to (ax to ay), B_ID to (bx to by)))
        // Finger A (index 0) lifts first; B compacts to index 0.
        assertEquals(0, taps.onPointerUp(pointerUp(at = 60, index = 0, A_ID to (ax to ay), B_ID to (bx to by))))
        // The move that used to kill the tap: B alone, at B's own position, now at index 0 —
        // the index-keyed anchor compared it against A's and read centimetres of travel.
        taps.onMove(move(at = 70, B_ID to (bx to by)))
        assertEquals(2, taps.onPointerUp(up(at = 90, B_ID, bx, by)))
    }

    @Test
    fun twoFingerTapFiresWhenTheSecondLandedFingerLiftsFirst() {
        taps.onPointerDown(down(at = 0, ax, ay))
        taps.onPointerDown(pointerDown(at = 20, index = 1, A_ID to (ax to ay), B_ID to (bx to by)))
        assertEquals(0, taps.onPointerUp(pointerUp(at = 50, index = 1, A_ID to (ax to ay), B_ID to (bx to by))))
        taps.onMove(move(at = 60, A_ID to (ax to ay)))
        assertEquals(2, taps.onPointerUp(up(at = 80, A_ID, ax, ay)))
    }

    @Test
    fun threeFingersReportThree() {
        val cx = bx + slop * 20f
        taps.onPointerDown(down(at = 0, ax, ay))
        taps.onPointerDown(pointerDown(at = 15, index = 1, A_ID to (ax to ay), B_ID to (bx to by)))
        taps.onPointerDown(
            pointerDown(at = 30, index = 2, A_ID to (ax to ay), B_ID to (bx to by), C_ID to (cx to ay)),
        )
        assertEquals(
            0,
            taps.onPointerUp(pointerUp(at = 60, index = 0, A_ID to (ax to ay), B_ID to (bx to by), C_ID to (cx to ay))),
        )
        assertEquals(0, taps.onPointerUp(pointerUp(at = 70, index = 1, B_ID to (bx to by), C_ID to (cx to ay))))
        assertEquals(3, taps.onPointerUp(up(at = 90, C_ID, cx, ay)))
    }

    @Test
    fun aStaggeredLandingMeasuresTheTimeoutFromTheLastFingerDown() {
        // The second finger lands 350 ms after the first — past the old first-DOWN-anchored
        // deadline on its own — and the lift still happens promptly after the landing.
        taps.onPointerDown(down(at = 0, ax, ay))
        taps.onPointerDown(pointerDown(at = 350, index = 1, A_ID to (ax to ay), B_ID to (bx to by)))
        assertEquals(0, taps.onPointerUp(pointerUp(at = 500, index = 0, A_ID to (ax to ay), B_ID to (bx to by))))
        assertEquals(2, taps.onPointerUp(up(at = 550, B_ID, bx, by)))
    }

    @Test
    fun lingeringFingersDoNotFire() {
        taps.onPointerDown(down(at = 0, ax, ay))
        taps.onPointerDown(pointerDown(at = 20, index = 1, A_ID to (ax to ay), B_ID to (bx to by)))
        assertEquals(0, taps.onPointerUp(pointerUp(at = 700, index = 0, A_ID to (ax to ay), B_ID to (bx to by))))
        assertEquals(0, taps.onPointerUp(up(at = 720, B_ID, bx, by)))
    }

    @Test
    fun aFingerTravellingPastSlopDoesNotFire() {
        val movedX = bx + slop * 2f
        taps.onPointerDown(down(at = 0, ax, ay))
        taps.onPointerDown(pointerDown(at = 20, index = 1, A_ID to (ax to ay), B_ID to (bx to by)))
        taps.onMove(move(at = 40, A_ID to (ax to ay), B_ID to (movedX to by)))
        assertEquals(0, taps.onPointerUp(pointerUp(at = 60, index = 1, A_ID to (ax to ay), B_ID to (movedX to by))))
        assertEquals(0, taps.onPointerUp(up(at = 80, A_ID, ax, ay)))
    }

    @Test
    fun cancelDisarmsTheGesture() {
        taps.onPointerDown(down(at = 0, ax, ay))
        taps.onPointerDown(pointerDown(at = 20, index = 1, A_ID to (ax to ay), B_ID to (bx to by)))
        taps.cancel()
        assertEquals(0, taps.onPointerUp(pointerUp(at = 40, index = 0, A_ID to (ax to ay), B_ID to (bx to by))))
        assertEquals(0, taps.onPointerUp(up(at = 60, B_ID, bx, by)))
    }

    // -----------------------------------------------------------------------
    // Event building — ids are explicit, which is the whole point of the tests
    // -----------------------------------------------------------------------

    private fun down(at: Long, x: Float, y: Float): MotionEvent = build(MotionEvent.ACTION_DOWN, at, A_ID to (x to y))

    private fun up(at: Long, id: Int, x: Float, y: Float): MotionEvent =
        build(MotionEvent.ACTION_UP, at, id to (x to y))

    private fun move(at: Long, vararg points: Pair<Int, Pair<Float, Float>>): MotionEvent =
        build(MotionEvent.ACTION_MOVE, at, *points)

    private fun pointerDown(at: Long, index: Int, vararg points: Pair<Int, Pair<Float, Float>>): MotionEvent = build(
        MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
        at,
        *points,
    )

    private fun pointerUp(at: Long, index: Int, vararg points: Pair<Int, Pair<Float, Float>>): MotionEvent = build(
        MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
        at,
        *points,
    )

    private fun build(action: Int, at: Long, vararg points: Pair<Int, Pair<Float, Float>>): MotionEvent {
        val properties = Array(points.size) { slot ->
            MotionEvent.PointerProperties().apply {
                id = points[slot].first
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(points.size) { slot ->
            MotionEvent.PointerCoords().apply {
                x = points[slot].second.first
                y = points[slot].second.second
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            downTime,
            downTime + at,
            action,
            points.size,
            properties,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    private companion object {
        const val A_ID = 7
        const val B_ID = 3
        const val C_ID = 11
    }
}
