package dev.inkstone.app.device

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.inkstone.android.ink.InkSurfaceView
import dev.inkstone.app.debug.InkHostActivity
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.StrokeObject
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * SPEC §13.2 **row 10, the wet half** — a peer's edit arriving mid-stroke.
 *
 * > A draws while B has the page open. […] Instrumented half: a real in-progress stylus stroke
 * > survives.
 *
 * Row 10's JVM half lives in `MergeMatrixFoldTest` and `NoteEditorRemoteOpsTest` and proves what
 * the *editor* does: `record = false`, `loadedPage` untouched, undo stack untouched. It cannot
 * reach this, for two reasons — `activeStrokeId` is private to [InkSurfaceView], and wet ink is
 * front-buffered through a SurfaceView, so a stroke can only genuinely be in progress inside a
 * real window.
 *
 * Two things must hold, and they are the two forbidden outcomes ROADMAP M6 names:
 *
 * 1. the stroke the user is still drawing lands when they lift the pen, and
 * 2. the camera does not move.
 *
 * The second is one call away: `display()` → `surface.loadPage` → `resetCamera(FIT_WIDTH)`, and the
 * camera is not persisted anywhere, so once refit the user's zoom is gone. It was *two* indirections
 * away until `pageSize`'s setter stopped refitting on every write, which is also why
 * `the forbidden path` below matters more than it looks: it is what stops `loadPage`'s refit being
 * removed by accident along with the setter's. It drives exactly that path and asserts the damage,
 * so the first test cannot pass by accident.
 *
 * ## Every stroke here is real ink
 *
 * The peer's op is harvested from a stroke this test actually draws, through `MotionEvent` →
 * `InProgressStrokesView` → `drainOps()`, and then re-stamped with a fresh [ObjectId]. Nothing is
 * synthesised: `SyntheticInk` is avoided deliberately, because M1's fixture does not clamp
 * orientation to `[0, 2π)` and x86_64 rounding puts it outside — the known reason ten of
 * `:android:ink`'s instrumented tests are red on an emulator. A test of M6 must not inherit that.
 *
 * ⚠️ Only a stylus draws. `PalmGuard` accepts `TOOL_TYPE_STYLUS` and `TOOL_TYPE_ERASER` and
 * nothing else (SPEC §3.3), so a finger-shaped event lands on the canvas and marks nothing —
 * silently, with a stroke count that stays at zero.
 */
@RunWith(AndroidJUnit4::class)
class WetInkRemoteMergeTest {

    private var scenario: ActivityScenario<InkHostActivity>? = null
    private var counter = 0L

    /**
     * One gesture's clock.
     *
     * ⚠️ `MotionEvent` times are `SystemClock.uptimeMillis()`, not wall clock, and every event of
     * one gesture must carry the **same** `downTime` — ink measures a stroke's elapsed time from
     * it, and a mismatched one gives negative elapsed times that `StrokeInput` rejects, so the
     * stroke silently never finishes.
     */
    private var gestureDownTime = 0L
    private var gestureEventTime = 0L

    @AfterTest
    fun close() {
        scenario?.close()
    }

    @Test
    fun `an in progress stroke survives a peers op and the camera does not move`() {
        val surface = laidOutSurface()
        val peer = harvestOneStroke(surface)

        beginStroke(surface, PEN_DOWN_X, PEN_DOWN_Y)
        val cameraMidStroke = onSurface(surface) { it.camera }
        assertEquals(
            1,
            onSurface(surface) { it.strokeCount },
            "the harvested stroke is on the page and the new one is still wet",
        )

        onSurface(surface) { it.applyOps(listOf(peer), record = false) }

        assertEquals(2, onSurface(surface) { it.strokeCount }, "the peer's stroke is on the page")
        assertEquals(cameraMidStroke, onSurface(surface) { it.camera }, "the camera must not move under the pen")

        endStroke(surface, PEN_UP_X, PEN_UP_Y)
        assertEquals(
            3,
            awaitStrokeCount(surface, 3),
            "the stroke that was in progress when the peer's op arrived still finishes",
        )
    }

    @Test
    fun `the forbidden path is loadPage which resets the page under the pen and refits the camera`() {
        val surface = laidOutSurface()
        harvestOneStroke(surface)

        beginStroke(surface, PEN_DOWN_X, PEN_DOWN_Y)
        val cameraMidStroke = onSurface(surface) { it.camera }

        // What `display()` does, and what a remote op must never be routed through.
        onSurface(surface) { it.loadPage(FoldedPage.empty(PAGE, size = PageSize.A4)) }

        assertEquals(
            0,
            onSurface(surface) { it.strokeCount },
            "loadPage cancels the unfinished stroke and clears the page — the user's work is eaten",
        )
        assertNotEquals(
            cameraMidStroke,
            onSurface(surface) { it.camera },
            "and it refits the camera, which is unrecoverable because the camera is never persisted",
        )
        // No ACTION_UP here on purpose: finishing a stroke androidx.ink has already cancelled is
        // undefined, and the damage this test is about has already happened.
    }

    // -----------------------------------------------------------------------
    // The surface
    // -----------------------------------------------------------------------

    private fun laidOutSurface(): ActivityScenario<InkHostActivity> {
        val launched = ActivityScenario.launch(InkHostActivity::class.java)
        scenario = launched
        onSurface(launched) { surface ->
            surface.fingerDrawingEnabled = false
            // Prediction is M1's, needs a live input device to be meaningful, and is not what row
            // 10 is about. Off, so a predicted tail cannot be what a failure here is about.
            surface.predictionEnabled = false
            surface.bindPage(
                nextObjectId = { ObjectId("object-${counter++}") },
                nextOpId = { "op-${counter++}" },
                nextClock = { LamportClock(++counter, DEVICE_ID) },
            )
            surface.pageSize = PageSize.Letter
        }
        awaitLaidOut(launched)
        return launched
    }

    /**
     * Draw one complete stroke and take its op back out of the recorder.
     *
     * This is what gives the peer's `AddObject` the geometry the real capture pipeline produced.
     * The returned op carries a fresh [ObjectId] because two objects sharing one identity in a
     * grow-only log is its own bug (SPEC §4.2).
     */
    private fun harvestOneStroke(surface: ActivityScenario<InkHostActivity>): PageOp.AddObject {
        beginStroke(surface, HARVEST_DOWN_X, HARVEST_DOWN_Y)
        endStroke(surface, HARVEST_UP_X, HARVEST_UP_Y)
        awaitStrokeCount(surface, 1)

        val drawn = onSurface(surface) { it.drainOps() }
            .flatten()
            .filterIsInstance<PageOp.AddObject>()
        assertEquals(1, drawn.size, "one stylus gesture must record exactly one AddObject")
        val original = drawn.single()
        val stroke = original.obj as StrokeObject
        return original.copy(
            opId = "peer-op",
            clock = LamportClock(++counter, PEER_DEVICE_ID),
            obj = stroke.copy(id = ObjectId("peer-object")),
        )
    }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    private fun beginStroke(surface: ActivityScenario<InkHostActivity>, x: Float, y: Float) {
        gestureDownTime = SystemClock.uptimeMillis()
        gestureEventTime = gestureDownTime
        val consumed = onSurface(surface) { it.dispatchTouchEvent(stylus(MotionEvent.ACTION_DOWN, x, y)) }
        // Said out loud, because "the stroke never appeared" and "the event never arrived" are
        // different failures with the same symptom, and only one of them is about M6.
        assertTrue(consumed, "the surface did not take the stylus ACTION_DOWN at all")
        for (step in 1..MOVE_STEPS) {
            gestureEventTime += MOVE_MILLIS
            val moved = stylus(MotionEvent.ACTION_MOVE, x + step * MOVE_DX, y + step * MOVE_DY)
            onSurface(surface) { it.dispatchTouchEvent(moved) }
        }
    }

    private fun endStroke(surface: ActivityScenario<InkHostActivity>, x: Float, y: Float) {
        gestureEventTime += MOVE_MILLIS
        onSurface(surface) { it.dispatchTouchEvent(stylus(MotionEvent.ACTION_UP, x, y)) }
    }

    /**
     * A stylus event, built the long way.
     *
     * `MotionEvent.obtain`'s short overloads cannot set a tool type, and a tool type is the only
     * thing `PalmGuard` looks at. Pressure is set to something in range because ink reads it
     * straight off the event, and a brush given no pressure at all is a different test.
     */
    private fun stylus(action: Int, x: Float, y: Float): MotionEvent {
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_STYLUS
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
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
            InputDevice.SOURCE_STYLUS,
            0,
        )
    }

    // -----------------------------------------------------------------------
    // Waiting
    // -----------------------------------------------------------------------

    private fun <T> onSurface(scenario: ActivityScenario<InkHostActivity>, block: (InkSurfaceView) -> T): T {
        var result: T? = null
        scenario.onActivity { result = block(it.surface) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun awaitLaidOut(scenario: ActivityScenario<InkHostActivity>) {
        await("the surface never got a size, so its camera would be meaningless") {
            onSurface(scenario) { it.width > 0 && it.height > 0 && it.camera != Camera.Identity }
        }
    }

    /** Finished strokes arrive through a listener, not on the calling thread. */
    private fun awaitStrokeCount(scenario: ActivityScenario<InkHostActivity>, expected: Int): Int {
        await("stroke count never reached $expected") { onSurface(scenario) { it.strokeCount } == expected }
        return onSurface(scenario) { it.strokeCount }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        assertTrue(condition(), message)
    }

    private companion object {
        const val DEVICE_ID = "wet-ink-device"
        const val PEER_DEVICE_ID = "wet-ink-peer"
        val PAGE = PageId("wet-ink-page")

        const val HARVEST_DOWN_X = 200f
        const val HARVEST_DOWN_Y = 200f
        const val HARVEST_UP_X = 340f
        const val HARVEST_UP_Y = 260f
        const val PEN_DOWN_X = 400f
        const val PEN_DOWN_Y = 500f
        const val PEN_UP_X = 540f
        const val PEN_UP_Y = 560f

        const val MOVE_STEPS = 6
        const val MOVE_MILLIS = 8L
        const val MOVE_DX = 20f
        const val MOVE_DY = 10f
        const val PRESSURE = 0.6f

        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 50L
    }
}
