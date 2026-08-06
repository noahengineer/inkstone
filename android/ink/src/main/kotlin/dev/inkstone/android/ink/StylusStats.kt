package dev.inkstone.android.ink

import android.view.MotionEvent

/**
 * What the digitiser actually reports — pressure, tilt, and which tool.
 *
 * ## Why this exists
 *
 * "The pen doesn't react to how hard you press" has two completely different causes and
 * they need opposite fixes:
 *
 * 1. **The stylus reports pressure and the brush response is too weak.** Tune
 *    `InkstoneBrushFamilies`.
 * 2. **The stylus reports a constant.** No amount of tuning helps, and the right answer is
 *    to drive width from speed and tilt instead — which the fountain family already has
 *    wired as `isFallbackFor = PRESSURE`, but which only engages if ink is told pressure is
 *    *absent* rather than being handed the same number every sample.
 *
 * Nothing in the app could tell those apart, so this does. CLAUDE.md's standing rule is
 * that the number to trust is whatever the device says.
 *
 * ## Rule 7
 *
 * [record] runs per `MotionEvent` on the ink path. It is four float compares and four plain
 * field writes: no allocation, no locks, no I/O. All writes are on the UI thread and the
 * only reader is the HUD poll, so `@Volatile` on the fields is enough — the atomics
 * [LatencyStats] needs are for ink's internal threads, which do not touch this.
 *
 * Historical samples are deliberately **not** walked. This is a HUD, one value per event is
 * plenty, and walking history here would put a loop on the ink path to answer a question
 * that a single sample answers.
 */
public class StylusStats {

    @Volatile private var samples: Long = 0

    @Volatile private var last: Float = 0f

    @Volatile private var lowest: Float = Float.MAX_VALUE

    @Volatile private var highest: Float = -Float.MAX_VALUE

    @Volatile private var lastTilt: Float = 0f

    @Volatile private var toolType: Int = MotionEvent.TOOL_TYPE_UNKNOWN

    internal fun record(event: MotionEvent, pointerIndex: Int) {
        val pressure = event.getPressure(pointerIndex)
        samples++
        last = pressure
        if (pressure < lowest) lowest = pressure
        if (pressure > highest) highest = pressure
        lastTilt = event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex)
        toolType = event.getToolType(pointerIndex)
    }

    public fun reset() {
        samples = 0
        last = 0f
        lowest = Float.MAX_VALUE
        highest = -Float.MAX_VALUE
        lastTilt = 0f
        toolType = MotionEvent.TOOL_TYPE_UNKNOWN
    }

    public fun snapshot(): Snapshot {
        val count = samples
        return Snapshot(
            sampleCount = count,
            lastPressure = last,
            minPressure = if (count == 0L) 0f else lowest,
            maxPressure = if (count == 0L) 0f else highest,
            lastTiltRadians = lastTilt,
            toolType = toolType,
        )
    }

    /**
     * @property varies **the answer to the question.** A stylus that reports real pressure
     *   spans a wide band within a few strokes; one that does not reports the same number
     *   every sample and this stays false however hard you press.
     */
    public data class Snapshot(
        val sampleCount: Long,
        val lastPressure: Float,
        val minPressure: Float,
        val maxPressure: Float,
        val lastTiltRadians: Float,
        val toolType: Int,
    ) {
        public val varies: Boolean get() = sampleCount > 0 && (maxPressure - minPressure) > MEANINGFUL_SPREAD

        public val toolName: String get() = when (toolType) {
            MotionEvent.TOOL_TYPE_STYLUS -> "stylus"
            MotionEvent.TOOL_TYPE_ERASER -> "eraser"
            MotionEvent.TOOL_TYPE_FINGER -> "finger"
            MotionEvent.TOOL_TYPE_MOUSE -> "mouse"
            else -> "unknown"
        }

        public val reportsTilt: Boolean get() = lastTiltRadians != 0f

        private companion object {
            /**
             * Below this, the spread is digitiser noise rather than the user pressing
             * harder. A pen with real pressure clears it within one written word.
             */
            const val MEANINGFUL_SPREAD = 0.02f
        }
    }
}
