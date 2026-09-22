package dev.inkstone.app.device

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import dev.inkstone.android.ink.InkSurfaceView
import dev.inkstone.app.debug.InkHostActivity
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageCanvas
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.StrokeObject
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A real [InkSurfaceView] in a real window, and a stylus to draw on it with.
 *
 * Shared by every instrumented test that needs *live* ink rather than a fold, because two of the
 * things it encapsulates cost half a session each to find and neither fails loudly:
 *
 * ⚠️ **Every event of one gesture must carry the same `downTime`, and it must be
 * `SystemClock.uptimeMillis()`.** Ink measures a stroke's elapsed time from `downTime`; a wall-clock
 * value, or a fresh one on the ACTION_UP, gives elapsed times `StrokeInput` rejects — and then the
 * stroke simply **never finishes**. No exception, no log, `strokeCount` stays where it was.
 *
 * ⚠️ **Only a stylus draws.** `PalmGuard` looks at the tool type and nothing else (SPEC §3.3), so a
 * finger-shaped event lands on the canvas and marks nothing, silently. `MotionEvent.obtain`'s short
 * overloads cannot set a tool type at all, which is why [stylus] builds one the long way.
 *
 * And one thing it deliberately does **not** offer: a way to synthesise geometry. Ink here is drawn,
 * so what these tests exercise is the real capture path — pressure, tilt, orientation and historical
 * points all come off the `MotionEvent`, and a synthesised batch silently has none of them.
 */
internal class InkHostDriver(private val deviceId: String) {

    private var counter = 0L
    private var gestureDownTime = 0L
    private var gestureEventTime = 0L

    var scenario: ActivityScenario<InkHostActivity>? = null
        private set

    /** Launch the host, bind the page, and wait until the surface has a size worth a camera. */
    fun launch(
        pageSize: PageSize = PageSize.Letter,
        /**
         * SPEC §4.5 — whether the paper grows to hold what is written on it.
         *
         * Set through `setPageMeta`, which is the door the app itself uses (`NoteEditor.reconcile`
         * pushes a folded page's canvas through it), rather than by writing the surface's own
         * field — which is private for exactly that reason.
         */
        infinite: Boolean = false,
    ): ActivityScenario<InkHostActivity> {
        val launched = ActivityScenario.launch(InkHostActivity::class.java)
        scenario = launched
        onSurface { surface ->
            surface.fingerDrawingEnabled = false
            // Prediction needs a live input device to be meaningful and is not what any of these
            // tests are about. Off, so a predicted tail cannot be what a failure is about.
            surface.predictionEnabled = false
            surface.bindPage(
                nextObjectId = { ObjectId("object-${counter++}") },
                nextOpId = { "op-${counter++}" },
                nextClock = { LamportClock(++counter, deviceId) },
            )
            surface.pageSize = pageSize
            if (infinite) {
                surface.setPageMeta(PageCanvas(pageSize, infinite = true), Background.Blank)
            }
        }
        awaitLaidOut()
        return launched
    }

    fun close() {
        scenario?.close()
        scenario = null
    }

    fun <T> onSurface(block: (InkSurfaceView) -> T): T {
        val active = requireNotNull(scenario) { "launch() first" }
        var result: T? = null
        active.onActivity { result = block(it.surface) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    // -----------------------------------------------------------------------
    // Drawing
    // -----------------------------------------------------------------------

    /**
     * Put the pen down and move it, leaving the stroke **in progress**.
     *
     * Asserts the surface consumed the ACTION_DOWN, said out loud because *"the stroke never
     * appeared"* and *"the event never arrived"* are different failures with the same symptom.
     */
    fun beginStroke(x: Float, y: Float) {
        gestureDownTime = SystemClock.uptimeMillis()
        gestureEventTime = gestureDownTime
        val consumed = onSurface { it.dispatchTouchEvent(stylus(MotionEvent.ACTION_DOWN, x, y)) }
        assertTrue(consumed, "the surface did not take the stylus ACTION_DOWN at all")
        for (step in 1..MOVE_STEPS) {
            gestureEventTime += MOVE_MILLIS
            val moved = stylus(MotionEvent.ACTION_MOVE, x + step * MOVE_DX, y + step * MOVE_DY)
            onSurface { it.dispatchTouchEvent(moved) }
        }
    }

    fun endStroke(x: Float, y: Float) {
        gestureEventTime += MOVE_MILLIS
        onSurface { it.dispatchTouchEvent(stylus(MotionEvent.ACTION_UP, x, y)) }
    }

    /**
     * Draw one complete stroke and take its op back out of the recorder.
     *
     * The returned op carries a fresh [ObjectId] and a peer's device id, so it can stand in for
     * another device's work while still being geometry the real capture pipeline produced. Two
     * objects sharing one identity in a grow-only log is its own bug (SPEC §4.2).
     */
    fun harvestOneStroke(peerDeviceId: String): PageOp.AddObject {
        beginStroke(HARVEST_DOWN_X, HARVEST_DOWN_Y)
        endStroke(HARVEST_UP_X, HARVEST_UP_Y)
        awaitStrokeCount(1)

        val drawn = onSurface { it.drainOps() }.flatten().filterIsInstance<PageOp.AddObject>()
        assertEquals(1, drawn.size, "one stylus gesture must record exactly one AddObject")
        val original = drawn.single()
        val stroke = original.obj as StrokeObject
        return original.copy(
            opId = "peer-op",
            clock = LamportClock(++counter, peerDeviceId),
            obj = stroke.copy(id = ObjectId("peer-object")),
        )
    }

    /**
     * Drag one **finger** across the surface, which pans the camera.
     *
     * The realistic way to reach the empty space beside the page: SPEC §4.5 makes panning free,
     * so a note can be moved almost entirely off screen and the paper's edge can be put anywhere.
     * A test that reached the same place by writing to `camera` would be testing the leash rather
     * than the thing a user does.
     *
     * A finger, not a stylus — with `fingerDrawingEnabled` false, that is what `PalmGuard` lets
     * through to the camera (SPEC §3.3).
     */
    fun panBy(fromX: Float, fromY: Float, dx: Float, dy: Float) {
        gestureDownTime = SystemClock.uptimeMillis()
        gestureEventTime = gestureDownTime
        onSurface { it.dispatchTouchEvent(finger(MotionEvent.ACTION_DOWN, fromX, fromY)) }
        for (step in 1..PAN_STEPS) {
            gestureEventTime += MOVE_MILLIS
            val at = finger(MotionEvent.ACTION_MOVE, fromX + dx * step / PAN_STEPS, fromY + dy * step / PAN_STEPS)
            onSurface { it.dispatchTouchEvent(at) }
        }
        gestureEventTime += MOVE_MILLIS
        onSurface { it.dispatchTouchEvent(finger(MotionEvent.ACTION_UP, fromX + dx, fromY + dy)) }
    }

    private fun finger(action: Int, x: Float, y: Float): MotionEvent =
        event(action, x, y, MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN)

    private fun stylus(action: Int, x: Float, y: Float): MotionEvent =
        event(action, x, y, MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS)

    private fun event(action: Int, x: Float, y: Float, tool: Int, source: Int): MotionEvent {
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
                // Ink reads pressure straight off the event, and a brush given none is a different
                // test.
                pressure = PRESSURE
                size = 1f
            },
        )
        return MotionEvent.obtain(
            gestureDownTime,
            gestureEventTime,
            action,
            1,
            properties,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            source,
            0,
        )
    }

    // -----------------------------------------------------------------------
    // Waiting
    // -----------------------------------------------------------------------

    fun awaitLaidOut() {
        await("the surface never got a size, so its camera would be meaningless") {
            onSurface { it.width > 0 && it.height > 0 && it.camera != Camera.Identity }
        }
    }

    /** Finished strokes arrive through a listener, not on the calling thread. */
    fun awaitStrokeCount(expected: Int): Int {
        await("stroke count never reached $expected") { onSurface { it.strokeCount } == expected }
        return onSurface { it.strokeCount }
    }

    fun await(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        assertTrue(condition(), message)
    }

    companion object {
        const val HARVEST_DOWN_X = 200f
        const val HARVEST_DOWN_Y = 200f
        const val HARVEST_UP_X = 340f
        const val HARVEST_UP_Y = 260f
        const val PEN_DOWN_X = 400f
        const val PEN_DOWN_Y = 500f
        const val PEN_UP_X = 540f
        const val PEN_UP_Y = 560f

        const val MOVE_STEPS = 6
        const val PAN_STEPS = 8
        const val MOVE_MILLIS = 8L
        const val MOVE_DX = 20f
        const val MOVE_DY = 10f
        const val PRESSURE = 0.6f

        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 50L
    }
}
