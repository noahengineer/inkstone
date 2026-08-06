package dev.inkstone.android.ink

import android.os.Build
import android.view.MotionEvent

/**
 * Palm rejection and tool classification (SPEC §3.3).
 *
 * The core of it is one observation: **a palm is delivered as a finger**, and it
 * lands while the pen is still hovering. The S Pen reports hover events from
 * about a centimetre out, so "has the stylus been near the glass recently?" is
 * the strongest palm signal a public API offers — and it costs one timestamp.
 *
 * Combined with `InkSurfaceView.fingerDrawingEnabled` defaulting to off (a
 * surface fingers cannot mark cannot be marked by a palm) and with
 * `FLAG_CANCELED` handling, this is what makes ROADMAP M1's "zero stray marks
 * in 20 consecutive attempts" reachable without a vendor SDK.
 */
internal class PalmGuard {

    private var lastStylusEventUptimeMs = 0L

    fun noteStylusEvent(event: MotionEvent) {
        lastStylusEventUptimeMs = event.eventTime
    }

    /**
     * @return false while the stylus is on or near the glass, and for a short
     *   grace window after it leaves — a pen lifted to cross a `t` must not let
     *   the palm resting underneath start panning the page.
     */
    fun allowsFinger(event: MotionEvent): Boolean = event.eventTime - lastStylusEventUptimeMs > GRACE_MS

    private companion object {
        const val GRACE_MS = 300L
    }
}

/** A stylus, whether tip-down or flipped to the eraser end. */
internal fun MotionEvent.isStylusPointer(pointerIndex: Int): Boolean = when (getToolType(pointerIndex)) {
    MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> true
    else -> false
}

/**
 * SPEC §3.3 — palm rejection also arrives as `FLAG_CANCELED` on a pointer's UP,
 * from API 33. A cancelled pointer must not finish its stroke; finishing it is
 * exactly the stray mark the flag exists to prevent.
 */
internal fun MotionEvent.isCancelledPointer(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (flags and MotionEvent.FLAG_CANCELED) != 0
