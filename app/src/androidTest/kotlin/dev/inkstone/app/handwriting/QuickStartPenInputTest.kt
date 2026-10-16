package dev.inkstone.app.handwriting

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The quick start's touch rules (docs/HANDWRITING-SYNTHESIS.md §18.9), through real `MotionEvent`s:
 * the pen writes, fingers never draw, two fingers tapped undo and three redo, and a flipped pen, a
 * held barrel button or *Erase* erases.
 *
 * Instrumented because the rules are about tool types, pointer ids and button states, which a JVM
 * `MotionEvent` does not have — and because `adb shell input` can neither tap with two fingers nor
 * hold a barrel button, so the device walk could not reach them either. [PenInput] is driven
 * directly, with the same [PromptInk] the screen uses; what is *not* covered here is that the screen
 * hands it its events, which the walk on the emulator checked.
 */
class QuickStartPenInputTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ink = PromptInk()
    private val pen = PenInput(context, ink)
    private var downTime = 0L
    private var now = 0L

    private class Pointer(val id: Int, val tool: Int, val x: Float, val y: Float)

    private fun stylus(x: Float, y: Float, id: Int = 0) = Pointer(id, MotionEvent.TOOL_TYPE_STYLUS, x, y)

    private fun finger(id: Int, x: Float, y: Float) = Pointer(id, MotionEvent.TOOL_TYPE_FINGER, x, y)

    private fun send(action: Int, pointers: List<Pointer>, index: Int = 0, buttons: Int = 0) {
        val properties = pointers.map { p ->
            MotionEvent.PointerProperties().apply {
                id = p.id
                toolType = p.tool
            }
        }.toTypedArray()
        val coords = pointers.map { p ->
            MotionEvent.PointerCoords().apply {
                x = p.x
                y = p.y
                pressure = PRESSURE
                size = 1f
            }
        }.toTypedArray()
        val masked = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else {
            action
        }
        val stylusLike = pointers.any { it.tool != MotionEvent.TOOL_TYPE_FINGER }
        val source = if (stylusLike) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
        val event = MotionEvent.obtain(
            downTime, now, masked, pointers.size, properties, coords, 0, buttons, 1f, 1f, 0, 0, source, 0,
        )
        pen.onTouch(event, PX_PER_POINT)
        event.recycle()
        now += STEP_MS
    }

    /** A stroke from ([x0], [y]) to ([x1], [y]) by [tool], with [buttons] held throughout. */
    private fun line(x0: Float, x1: Float, y: Float, tool: Int = MotionEvent.TOOL_TYPE_STYLUS, buttons: Int = 0) {
        downTime = SystemClock.uptimeMillis()
        now = downTime
        val at = { x: Float -> listOf(Pointer(0, tool, x, y)) }
        send(MotionEvent.ACTION_DOWN, at(x0), buttons = buttons)
        for (k in 1 until LINE_STEPS) {
            send(
                MotionEvent.ACTION_MOVE,
                at(x0 + (x1 - x0) * k / LINE_STEPS),
                buttons = buttons,
            )
        }
        send(MotionEvent.ACTION_UP, at(x1), buttons = buttons)
    }

    /** [fingers] fingers down together and up again, without moving. */
    private fun tap(fingers: Int) {
        downTime = SystemClock.uptimeMillis()
        now = downTime
        val all = (0 until fingers).map { finger(it, TAP_X + it * FINGER_GAP, TAP_Y) }
        send(MotionEvent.ACTION_DOWN, all.take(1))
        for (n in 2..fingers) send(MotionEvent.ACTION_POINTER_DOWN, all.take(n), index = n - 1)
        for (n in fingers downTo 2) send(MotionEvent.ACTION_POINTER_UP, all.take(n), index = n - 1)
        send(MotionEvent.ACTION_UP, all.take(1))
    }

    @Test
    fun thePenWritesAStroke() {
        line(10f, 60f, 40f)
        assertEquals(1, ink.visible.size)
        assertEquals(LINE_STEPS + 1, ink.visible.single().size, "every event's sample, DOWN to UP")
    }

    @Test
    fun aFingerNeverDraws() {
        line(10f, 60f, 40f, tool = MotionEvent.TOOL_TYPE_FINGER)
        assertTrue(ink.isEmpty, "a finger is a palm's tool type, so it cannot mark a sample")
    }

    @Test
    fun twoFingersUndoAndThreeFingersRedo() {
        line(10f, 60f, 40f)
        line(10f, 60f, 80f)

        tap(2)
        assertEquals(listOf(40f), ink.visible.map { it.first().y }, "two fingers took the last stroke back")
        tap(3)
        assertEquals(listOf(40f, 80f), ink.visible.map { it.first().y }, "three fingers put it back")
    }

    @Test
    fun theBarrelButtonErasesTheStrokeItCrosses() {
        line(10f, 60f, 40f)
        line(10f, 60f, 80f)

        // Held across the first stroke only, from above it to between the two.
        downTime = SystemClock.uptimeMillis()
        now = downTime
        val barrel = MotionEvent.BUTTON_STYLUS_PRIMARY
        send(MotionEvent.ACTION_DOWN, listOf(stylus(30f, 20f)), buttons = barrel)
        send(MotionEvent.ACTION_MOVE, listOf(stylus(30f, 60f)), buttons = barrel)
        send(MotionEvent.ACTION_UP, listOf(stylus(30f, 60f)), buttons = barrel)

        assertEquals(listOf(80f), ink.visible.map { it.first().y })
        tap(2)
        assertEquals(2, ink.visible.size, "the erase is one undo")
    }

    @Test
    fun theFlippedPenErases() {
        line(10f, 60f, 40f)
        line(30f, 31f, 40f, tool = MotionEvent.TOOL_TYPE_ERASER)
        assertTrue(ink.isEmpty)
    }

    @Test
    fun eraseModeMakesThePenTipErase() {
        line(10f, 60f, 40f)
        pen.eraseMode = true
        line(30f, 31f, 40f)
        assertTrue(ink.isEmpty)
        pen.eraseMode = false
        line(10f, 60f, 90f)
        assertEquals(1, ink.visible.size, "and writes again once it is off")
    }

    @Test
    fun aPalmLandingWhileThePenWritesIsNeitherInkNorATap() {
        line(10f, 60f, 40f)
        downTime = SystemClock.uptimeMillis()
        now = downTime
        val palm = finger(1, 300f, 120f)
        send(MotionEvent.ACTION_DOWN, listOf(stylus(10f, 80f)))
        send(MotionEvent.ACTION_POINTER_DOWN, listOf(stylus(20f, 80f), palm), index = 1)
        send(MotionEvent.ACTION_MOVE, listOf(stylus(40f, 80f), palm))
        send(MotionEvent.ACTION_POINTER_UP, listOf(stylus(50f, 80f), palm), index = 1)
        send(MotionEvent.ACTION_UP, listOf(stylus(60f, 80f)))

        assertEquals(listOf(40f, 80f), ink.visible.map { it.first().y }, "the stroke finished; nothing was undone")
    }

    private companion object {
        const val PX_PER_POINT = 1f
        const val PRESSURE = 0.6f
        const val STEP_MS = 8L
        const val LINE_STEPS = 6
        const val TAP_X = 200f
        const val TAP_Y = 120f
        const val FINGER_GAP = 60f
    }
}
