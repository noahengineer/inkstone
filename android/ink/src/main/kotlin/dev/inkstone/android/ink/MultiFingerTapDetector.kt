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
 * platform's touch slop, or the fingers lingering past [TAP_TIMEOUT_MS]. A
 * pinch or a drag fails both immediately, and a real tap moves the camera by
 * at most a pixel or two before the fingers lift, which is imperceptible.
 *
 * ## Anchors are keyed by pointer **id**, never by index
 *
 * Fingers do not lift in the same frame, and when one lifts the survivors'
 * *indices* compact down. The first version anchored `startX[index]`, so a
 * MOVE arriving in the 10–80 ms between the two lifts compared the surviving
 * finger against the *other* finger's anchor — centimetres apart, far past
 * slop — and killed the tap. Whether that happened depended on which finger
 * lifted first and on digitiser timing: the classic feels-random failure, and
 * the largest single cause of "two-finger undo is inconsistent". Ids are
 * stable for a pointer's whole life ([MotionEvent.getPointerId]); indices are
 * not, which is the same reason [CameraGestureTracker] tracks ids.
 *
 * ## The timeout runs from the **last** finger to land
 *
 * Landing two or three fingers together staggers by tens of milliseconds, and
 * a person who has found the gesture flaky taps *slower*, not faster. Charging
 * the stagger against the deadline made the deliberate tap the one most likely
 * to fail. What actually separates a tap from a resting hand is how long the
 * fingers stay down once they have all arrived, so that is what is measured.
 *
 * Deliberately not a `GestureDetector`: that has no notion of "how many fingers
 * were involved", which is the entire signal here.
 *
 * Public so that every surface the pen writes on undoes the same way — the note
 * editor and the handwriting quick start — rather than a second detector
 * relearning the id-not-index and last-finger-timeout lessons above.
 */
class MultiFingerTapDetector(context: Context) {

    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var candidate = false
    private var lastDownMs = 0L
    private var peakPointerCount = 0
    private val ids = IntArray(MAX_TRACKED) { MotionEvent.INVALID_POINTER_ID }
    private val startX = FloatArray(MAX_TRACKED)
    private val startY = FloatArray(MAX_TRACKED)

    /** Call on every finger DOWN, including the second and third. */
    fun onPointerDown(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            candidate = true
            peakPointerCount = 0
            for (slot in ids.indices) ids[slot] = MotionEvent.INVALID_POINTER_ID
        }
        if (!candidate) return

        lastDownMs = event.eventTime
        peakPointerCount = maxOf(peakPointerCount, event.pointerCount)
        // Re-anchor every pointer, not just the new one. A finger that was
        // resting while others arrived has not "moved" for tap purposes.
        for (index in 0 until minOf(event.pointerCount, MAX_TRACKED)) {
            val id = event.getPointerId(index)
            val slot = slotOf(id)
            if (slot < 0) continue
            ids[slot] = id
            startX[slot] = event.getX(index)
            startY[slot] = event.getY(index)
        }
    }

    /** The slot already tracking [id], or a free one, or -1 when all are taken. */
    private fun slotOf(id: Int): Int {
        var free = -1
        for (slot in ids.indices) {
            if (ids[slot] == id) return slot
            if (free < 0 && ids[slot] == MotionEvent.INVALID_POINTER_ID) free = slot
        }
        return free
    }

    fun onMove(event: MotionEvent) {
        if (!candidate) return
        for (slot in ids.indices) {
            if (!stillWithinSlop(event, slot)) {
                candidate = false
                return
            }
        }
    }

    /** Whether the pointer anchored in [slot] has stayed put — vacuously true for an empty slot. */
    private fun stillWithinSlop(event: MotionEvent, slot: Int): Boolean {
        val id = ids[slot]
        if (id == MotionEvent.INVALID_POINTER_ID) return true
        // A pointer that already lifted is absent from this event; its anchor
        // constrains nothing any more.
        val index = event.findPointerIndex(id)
        if (index < 0) return true
        val dx = event.getX(index) - startX[slot]
        val dy = event.getY(index) - startY[slot]
        return dx * dx + dy * dy <= touchSlopPx * touchSlopPx
    }

    /**
     * Call on every finger UP.
     *
     * @return the number of fingers involved once the **last** one lifts and the
     *   gesture still qualifies, or 0. The caller decides what 2 and 3 mean.
     */
    fun onPointerUp(event: MotionEvent): Int {
        if (!candidate) return 0
        if (event.eventTime - lastDownMs > TAP_TIMEOUT_MS) {
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

    companion object {
        /** Two fingers tapped together: undo. */
        const val UNDO_FINGERS = 2

        /** Three fingers tapped together: redo. */
        const val REDO_FINGERS = 3

        /**
         * From the last finger's DOWN to the last finger's UP. Generous on
         * purpose: a tap that has to be quick is a tap that fails, and the slop
         * test is what rejects pans — this only has to reject a resting hand.
         */
        private const val TAP_TIMEOUT_MS = 400L

        private const val MAX_TRACKED = 3
    }
}
