package dev.inkstone.android.ink

import android.view.MotionEvent
import dev.inkstone.core.model.Camera
import dev.inkstone.core.model.Offset
import dev.inkstone.core.model.ZoomLimits
import dev.inkstone.core.model.panned
import dev.inkstone.core.model.zoomedAround
import kotlin.math.hypot

/**
 * Pan and pinch-zoom, up to two pointers (SPEC §4.5).
 *
 * Separate from [InkSurfaceView] because it is the one part of input handling
 * with state of its own that has nothing to do with ink — and because the
 * caller's rule for it is a single sentence: **fingers only, never while a
 * stylus stroke is live.** Enforcing that at the one call site is easier to
 * verify than enforcing it inside a method that also draws.
 *
 * `ScaleGestureDetector` is not used. It cannot be told to ignore a specific
 * pointer, which is precisely what palm rejection needs, and combining its
 * scale output with a separately tracked pan reintroduces the focal-point drift
 * that [zoomedAround] exists to avoid.
 */
internal class CameraGestureTracker(private val limits: ZoomLimits) {

    private val pointerIds = IntArray(MAX_POINTERS) { MotionEvent.INVALID_POINTER_ID }
    private var pointerCount = 0
    private var lastCentroidX = 0f
    private var lastCentroidY = 0f
    private var lastSpan = 0f

    val isActive: Boolean get() = pointerCount > 0

    fun addPointer(event: MotionEvent, pointerId: Int) {
        if (pointerCount >= MAX_POINTERS) return
        pointerIds[pointerCount++] = pointerId
        reanchor(event)
    }

    fun removePointer(event: MotionEvent, pointerId: Int) {
        var slot = 0
        while (slot < pointerCount) {
            if (pointerIds[slot] == pointerId) {
                pointerIds[slot] = pointerIds[pointerCount - 1]
                pointerCount--
            } else {
                slot++
            }
        }
        if (pointerCount > 0) reanchor(event)
    }

    fun abandon() {
        pointerCount = 0
        lastSpan = 0f
    }

    /** @return the camera after this move, or [camera] unchanged if nothing applied. */
    fun onMove(event: MotionEvent, camera: Camera): Camera {
        if (pointerCount == 0) return camera

        var sumX = 0f
        var sumY = 0f
        var found = 0
        for (slot in 0 until pointerCount) {
            val index = event.findPointerIndex(pointerIds[slot])
            if (index < 0) continue
            sumX += event.getX(index)
            sumY += event.getY(index)
            found++
        }
        if (found == 0) return camera

        val centroidX = sumX / found
        val centroidY = sumY / found
        var next = camera.panned(Offset(centroidX - lastCentroidX, centroidY - lastCentroidY))

        if (found == MAX_POINTERS) {
            val span = span(event)
            // Below the threshold the ratio is dominated by touch noise, and a
            // pinch that starts with the fingers almost touching would zoom by
            // a factor of ten from a millimetre of movement.
            if (span > MIN_PINCH_SPAN_PX && lastSpan > MIN_PINCH_SPAN_PX) {
                next = next.zoomedAround(Offset(centroidX, centroidY), span / lastSpan, limits)
            }
            lastSpan = span
        }

        lastCentroidX = centroidX
        lastCentroidY = centroidY
        return next
    }

    private fun reanchor(event: MotionEvent) {
        var sumX = 0f
        var sumY = 0f
        var found = 0
        for (slot in 0 until pointerCount) {
            val index = event.findPointerIndex(pointerIds[slot])
            if (index < 0) continue
            sumX += event.getX(index)
            sumY += event.getY(index)
            found++
        }
        if (found == 0) return
        lastCentroidX = sumX / found
        lastCentroidY = sumY / found
        lastSpan = span(event)
    }

    private fun span(event: MotionEvent): Float {
        if (pointerCount < MAX_POINTERS) return 0f
        val a = event.findPointerIndex(pointerIds[0])
        val b = event.findPointerIndex(pointerIds[1])
        return if (a < 0 || b < 0) 0f else hypot(event.getX(a) - event.getX(b), event.getY(a) - event.getY(b))
    }

    private companion object {
        const val MAX_POINTERS = 2
        const val MIN_PINCH_SPAN_PX = 24f
    }
}
