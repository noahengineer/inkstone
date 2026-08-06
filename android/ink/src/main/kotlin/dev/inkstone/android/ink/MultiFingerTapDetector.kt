package dev.inkstone.android.ink

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration

/**
 * Two fingers tapped = undo. Three = redo.
 *
 * The gesture every tablet note app has, and the one that costs nothing to
 * reach: no button to find, no hand to move off the page.
 *
 * ## Why it does not fight the pan/zoom gesture
 *
 * A tap and a pan begin identically — the difference is only what happens next.
 * So this watches the same pointers the camera does and disqualifies itself the
 * moment the gesture stops looking like a tap: any pointer travelling past the
 * platform's touch slop, or the whole thing lasting longer than [TAP_TIMEOUT_MS].
 * A pinch or a drag fails both immediately, and a real tap moves the camera by
 * at most a pixel or two before the fingers lift, which is imperceptible.
 *
 * Deliberately not a `GestureDetector`: that has no notion of "how many fingers
 * were involved", which is the entire signal here.
 */
internal class MultiFingerTapDetector(context: Context) {

    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var candidate = false
    private var downTimeMs = 0L
    private var peakPointerCount = 0
    private val startX = FloatArray(MAX_TRACKED)
    private val startY = FloatArray(MAX_TRACKED)

    /** Call on every finger DOWN, including the second and third. */
    fun onPointerDown(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            candidate = true
            downTimeMs = event.eventTime
            peakPointerCount = 0
        }
        if (!candidate) return

        peakPointerCount = maxOf(peakPointerCount, event.pointerCount)
        // Re-anchor every pointer, not just the new one. A finger that was
        // resting while others arrived has not "moved" for tap purposes.
        for (index in 0 until minOf(event.pointerCount, MAX_TRACKED)) {
            startX[index] = event.getX(index)
            startY[index] = event.getY(index)
        }
    }

    fun onMove(event: MotionEvent) {
        if (!candidate) return
        for (index in 0 until minOf(event.pointerCount, MAX_TRACKED)) {
            val dx = event.getX(index) - startX[index]
            val dy = event.getY(index) - startY[index]
            if (dx * dx + dy * dy > touchSlopPx * touchSlopPx) {
                candidate = false
                return
            }
        }
    }

    /**
     * Call on every finger UP.
     *
     * @return the number of fingers involved once the **last** one lifts and the
     *   gesture still qualifies, or 0. The caller decides what 2 and 3 mean.
     */
    fun onPointerUp(event: MotionEvent): Int {
        if (!candidate) return 0
        if (event.eventTime - downTimeMs > TAP_TIMEOUT_MS) {
            candidate = false
            return 0
        }
        // ACTION_UP is the last pointer; ACTION_POINTER_UP means others remain.
        if (event.actionMasked != MotionEvent.ACTION_UP) return 0

        val fingers = peakPointerCount
        candidate = false
        return fingers
    }

    fun cancel() {
        candidate = false
    }

    private companion object {
        /**
         * Generous next to the platform's 100 ms tap timeout: landing two or
         * three fingers together is less precise than one, and a tap that has to
         * be quick is a tap that fails.
         */
        const val TAP_TIMEOUT_MS = 300L

        const val MAX_TRACKED = 3
    }
}
