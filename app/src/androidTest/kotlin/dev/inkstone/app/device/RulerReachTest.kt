package dev.inkstone.app.device

import android.content.res.Resources
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import dev.inkstone.android.ink.InkSurfaceView
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⭐ The straight edge, in a real window — *"the ruler does not work at all"*.
 *
 * ## Why this is here and not in `:android:ink`'s own suite
 *
 * `RulerAndLaserTest` asserts the ruler's arithmetic and its grab against a bare
 * `RulerController`, which is the right seam for the geometry and the wrong one for both defects
 * this file exists for. Neither of them lives in the controller:
 *
 * - **The grab was behind `PalmGuard`.** The controller never sees a pointer the surface has
 *   already dropped, so a test that calls `begin` directly cannot fail on it — and that is exactly
 *   how the ruler shipped un-grabbable in the only posture it is ever used in.
 * - **A singular `motionEventToWorldTransform` is `androidx.ink`'s to accept or reject.**
 *   `RulerAndLaserTest`'s own KDoc lists that as the one thing about the ruler that could still be
 *   wrong and says *"the emulator walk is what closes it"*. A walk is not a check that runs again
 *   next milestone; this is.
 *
 * Both need a laid-out [InkSurfaceView] inside a window, which is what [InkHostDriver] is for.
 *
 * ⚠️ Test names here may contain no apostrophes and no commas: **D8 rejects both** in a dex method
 * name, and the JVM compiler accepts them, so it does not surface until the test APK is built.
 */
class RulerReachTest {

    private val driver = InkHostDriver(DEVICE_ID)

    @Before
    fun launch() {
        driver.launch()
    }

    @After
    fun close() {
        driver.close()
    }

    /**
     * ⭐ **A ruled stroke still lands**, which is the one part of the snap only a real window can
     * answer.
     *
     * The snap is applied by composing an orthogonal projection onto the transform `startStroke` is
     * given, and a projection is **singular** by construction. If `InProgressStrokesView` refused
     * one, every stroke drawn while the edge was out would simply never finish — no exception, no
     * log, `strokeCount` where it was — which is precisely the report that prompted this file.
     */
    @Test
    fun `a stroke drawn while the edge is out still finishes`() {
        driver.onSurface { it.ruler.enabled = true }

        driver.beginStroke(InkHostDriver.PEN_DOWN_X, InkHostDriver.PEN_DOWN_Y)
        driver.endStroke(InkHostDriver.PEN_UP_X, InkHostDriver.PEN_UP_Y)

        assertEquals(1, driver.awaitStrokeCount(1), "a ruled stroke never finished")
    }

    /**
     * ⭐ **The finger grabs the edge while the pen is hovering**, which is the fix.
     *
     * The S Pen reports hover from about a centimetre out and every hover event refreshes
     * `PalmGuard`, so with the pen in hand — the *only* posture a straight edge is used in — the
     * guard used to swallow the finger before the ruler saw it. The edge could then be moved
     * exactly when nobody wanted to move it, which reads as a tool that does nothing.
     *
     * The hover goes through `onGenericMotionEvent`, which is the path a hovering stylus really
     * takes and the only thing that stamps the guard.
     */
    @Test
    fun `a finger moves the edge while the pen is hovering over the page`() {
        driver.onSurface { surface ->
            surface.ruler.enabled = true
            val before = surface.ruler.centreYPt
            val inset = bodyInsetPt(surface)

            hoverPen(surface, surface.ruler.centreXPt, surface.ruler.centreYPt)
            dragPage(surface, surface.ruler.centreXPt, before + inset, DRAG_PT)

            assertEquals(
                before + DRAG_PT,
                surface.ruler.centreYPt,
                TOLERANCE_PT,
                "the pen being near the page blocked the grab",
            )
        }
    }

    /**
     * The rejection is the half that matters: a finger that is not on the ruler has to reach the
     * camera, or the page is unpannable for as long as the edge is out.
     */
    @Test
    fun `a finger away from the edge does not move it`() {
        driver.onSurface { surface ->
            surface.ruler.enabled = true
            val before = surface.ruler.centreYPt

            dragPage(surface, surface.ruler.centreXPt, before + MISS_PT, DRAG_PT)

            assertEquals(before, surface.ruler.centreYPt, TOLERANCE_PT)
        }
    }

    /**
     * ⭐ **The body hangs off the edge, and the grab is on the same side.**
     *
     * A point inside the body is the ruler; the same distance on the pen's side is paper. Together
     * with `RulerAndLaserTest`, this is what stops the drawn body and the grabbable body from
     * drifting apart — at 12 % alpha, a ruler you can see and cannot pick up is not something
     * anybody would ever diagnose from the screen.
     */
    @Test
    fun `the body is grabbable and the paper above the edge is not`() {
        driver.onSurface { surface ->
            surface.ruler.enabled = true
            val inset = bodyInsetPt(surface)
            val centreX = surface.ruler.centreXPt
            val start = surface.ruler.centreYPt

            dragPage(surface, centreX, start + inset, DRAG_PT)
            assertEquals(start + DRAG_PT, surface.ruler.centreYPt, TOLERANCE_PT, "the body did not grab")

            val moved = surface.ruler.centreYPt
            dragPage(surface, centreX, moved - inset, DRAG_PT)
            assertTrue(
                abs(surface.ruler.centreYPt - moved) < TOLERANCE_PT,
                "the ruler claimed the paper its edge is drawn against",
            )
        }
    }

    /**
     * Halfway into the body, in **page** points.
     *
     * Derived from `InkRuler.bodyWidthDp` and the live camera rather than written as a number: the
     * grab region is dp on the glass divided by the camera's scale, so a hard-coded page distance
     * would be inside the body on one density and outside it on another — a test that passes or
     * fails on which emulator ran it.
     */
    private fun bodyInsetPt(surface: InkSurfaceView): Float =
        surface.ruler.bodyWidthDp * Resources.getSystem().displayMetrics.density / surface.camera.scale / 2f

    /** A finger, down-move-up, from a point in **page** space, dragged [byPt] straight down. */
    private fun dragPage(surface: InkSurfaceView, atXPt: Float, atYPt: Float, byPt: Float) {
        val camera = surface.camera
        val viewX = atXPt * camera.scale + camera.translate.x
        val viewY = atYPt * camera.scale + camera.translate.y
        val toY = (atYPt + byPt) * camera.scale + camera.translate.y
        // ⚠️ One `downTime` for the whole gesture, like `InkHostDriver.beginStroke`: a fresh one on
        // each event is a fresh gesture, and the surface would never see a move belonging to the
        // pointer it took.
        val downTime = SystemClock.uptimeMillis()
        surface.dispatchTouchEvent(finger(downTime, downTime, MotionEvent.ACTION_DOWN, viewX, viewY))
        surface.dispatchTouchEvent(finger(downTime, downTime + STEP_MS, MotionEvent.ACTION_MOVE, viewX, toY))
        surface.dispatchTouchEvent(finger(downTime, downTime + STEP_MS * 2, MotionEvent.ACTION_UP, viewX, toY))
    }

    /** A pen held above the glass — no touch, and the only thing that stamps `PalmGuard`. */
    private fun hoverPen(surface: InkSurfaceView, atXPt: Float, atYPt: Float) {
        val camera = surface.camera
        val now = SystemClock.uptimeMillis()
        surface.onGenericMotionEvent(
            event(
                downTime = now,
                at = now,
                action = MotionEvent.ACTION_HOVER_MOVE,
                x = atXPt * camera.scale + camera.translate.x,
                y = atYPt * camera.scale + camera.translate.y,
                tool = MotionEvent.TOOL_TYPE_STYLUS,
                source = SOURCE_HOVER,
            ),
        )
    }

    private fun finger(downTime: Long, at: Long, action: Int, x: Float, y: Float): MotionEvent =
        event(downTime, at, action, x, y, MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN)

    @Suppress("LongParameterList") // A MotionEvent is a long parameter list; see InkHostDriver.
    private fun event(
        downTime: Long,
        at: Long,
        action: Int,
        x: Float,
        y: Float,
        tool: Int,
        source: Int,
    ): MotionEvent {
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = tool
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            },
        )
        return MotionEvent.obtain(downTime, at, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
    }

    private companion object {
        const val DEVICE_ID = "ruler-device"

        /** Page points, far enough out that neither side of the grab region reaches it. */
        const val MISS_PT = 260f

        const val DRAG_PT = 40f
        const val TOLERANCE_PT = 1.5f
        const val STEP_MS = 8L

        /** A hovering stylus arrives on the pointer source, not the touchscreen. */
        const val SOURCE_HOVER = InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_CLASS_POINTER
    }
}
