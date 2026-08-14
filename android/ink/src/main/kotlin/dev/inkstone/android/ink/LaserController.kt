package dev.inkstone.android.ink

import android.os.SystemClock
import android.view.MotionEvent
import dev.inkstone.core.model.Camera
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ⭐ ROADMAP M8c — the laser pointer: a bright dot with a trail that fades away.
 *
 * ## ⚠️ It writes nothing, and that is enforced by what it cannot reach
 *
 * A laser mark is not an object. It is not in the op log, not in the journal, not in the published
 * PDF, not in a thumbnail, and it does not survive a page turn. The way that is guaranteed here is
 * **structural rather than remembered**: this class is not given a [StrokeStore], a
 * [PageOpRecorder] or anything that could reach either, so there is no call it could make. Compare
 * [EraserController] and [SelectionController], both of which hold a store precisely because they
 * do write.
 *
 * That matters more than it looks. Every other gesture in this module ends in an op, so the
 * *default* shape of a controller here is one that writes; a laser implemented by copying one of
 * them and remembering not to call `endEdit` would work and would be one refactor away from
 * journaling a light beam.
 *
 * ## Page space, not screen space
 *
 * The trail is stored in page points and drawn through the camera, so pointing at a word and then
 * panning keeps the mark on the word. A screen-space trail would slide across the page whenever
 * anything moved, which is exactly what a pointer must not do while somebody is talking about what
 * it is pointing at.
 *
 * ## The buffer
 *
 * A fixed ring of [CAPACITY] samples, written by index — the dry layer's rule about allocation
 * applies here too, since this runs on the same `MotionEvent` path at the same rate. A sample is
 * only appended once the pointer has travelled [STEP_PT], which is what keeps a fast sweep to tens
 * of points rather than to the few hundred a 240 Hz digitiser would produce; the trail is a
 * *shape*, and sampling it more finely than a page point changes nothing anyone can see while
 * costing a `drawLine` each.
 *
 * ## PalmGuard
 *
 * `TOOL_TYPE_STYLUS` only, checked by the caller before [begin] — a palm resting on the glass
 * during a lecture must not paint a red streak across the slide.
 */
internal class LaserController(private val onChanged: () -> Unit) {

    private val xs = FloatArray(CAPACITY)
    private val ys = FloatArray(CAPACITY)
    private val stamps = LongArray(CAPACITY)

    /** Where the next sample goes. The oldest live sample is at `head - count`. */
    private var head = 0

    /** How many samples are live. Dropping the oldest is `count--`, which is why it is kept this way. */
    var count = 0
        private set

    var pointerId: Int = MotionEvent.INVALID_POINTER_ID
        private set

    val isActive: Boolean get() = pointerId != MotionEvent.INVALID_POINTER_ID

    val isEmpty: Boolean get() = count == 0

    /** The trail's page-space extent, so the overlay can invalidate a rectangle and not the screen. */
    var minX: Float = 0f
        private set
    var minY: Float = 0f
        private set
    var maxX: Float = 0f
        private set
    var maxY: Float = 0f
        private set

    fun xAt(index: Int): Float = xs[slot(index)]

    fun yAt(index: Int): Float = ys[slot(index)]

    /**
     * How bright the sample at [index] should be right now, from 1 at the moment it was made to 0
     * at [LIFETIME_MS].
     *
     * Linear rather than eased: the trail is on screen for under a second and the difference is not
     * something anyone can see, so the curve would be decoration with a constant to tune.
     */
    fun brightnessAt(index: Int, nowMs: Long): Float =
        (1f - (nowMs - stamps[slot(index)]).toFloat() / LIFETIME_MS).coerceIn(0f, 1f)

    private fun slot(index: Int): Int = (head - count + index + CAPACITY) % CAPACITY

    /**
     * Start a new sweep.
     *
     * ⚠️ **The previous trail is dropped.** Keeping it would draw a segment from wherever the last
     * flick ended to wherever this one starts — a line across the page that the user never made and
     * that points at nothing. The cost is that a rapid tap-tap-tap shows dots rather than a fading
     * chain, which is what a real laser does anyway.
     */
    fun begin(event: MotionEvent, pointerIndex: Int, pointerId: Int, camera: Camera) {
        if (isActive) return
        this.pointerId = pointerId
        count = 0
        head = 0
        toPageSpace(event.getX(pointerIndex), event.getY(pointerIndex), camera, point)
        append(point.x, point.y, event.eventTime, force = true)
        onChanged()
    }

    /**
     * Every sample this event carries, historical ones included.
     *
     * The same reason the eraser walks them: at 30 cm/s the per-frame samples alone are a dotted
     * line, and a pointer that stutters when moved quickly is a pointer people stop trusting.
     */
    fun extend(event: MotionEvent, camera: Camera) {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        for (historical in 0 until event.historySize) {
            toPageSpace(event.getHistoricalX(index, historical), event.getHistoricalY(index, historical), camera, point)
            append(point.x, point.y, event.getHistoricalEventTime(historical), force = false)
        }
        toPageSpace(event.getX(index), event.getY(index), camera, point)
        append(point.x, point.y, event.eventTime, force = false)
        onChanged()
    }

    /**
     * The pointer left the glass. The trail stays and finishes fading, which is the whole point of
     * a laser — lifting the pen is not an instruction to erase what was just indicated.
     */
    fun end() {
        pointerId = MotionEvent.INVALID_POINTER_ID
    }

    /**
     * Drop the trail immediately — a page switch, or the surface being detached.
     *
     * Unlike [end], which lets it finish fading. A mark left over from the previous page would be
     * pointing at whatever now happens to be underneath it.
     */
    fun clear() {
        pointerId = MotionEvent.INVALID_POINTER_ID
        count = 0
        head = 0
    }

    /**
     * Drop samples older than [LIFETIME_MS].
     *
     * @return true while anything is still visible, which is the overlay's signal to schedule
     *   another frame. False is what stops the animation, so a laser that is not on screen costs
     *   nothing at all.
     */
    fun prune(nowMs: Long): Boolean {
        while (count > 0 && nowMs - stamps[slot(0)] > LIFETIME_MS) count--
        if (count == 0) return false
        recomputeBounds()
        return true
    }

    private fun append(x: Float, y: Float, eventTimeMs: Long, force: Boolean) {
        if (!force && count > 0) {
            val lastIndex = slot(count - 1)
            if (abs(x - xs[lastIndex]) < STEP_PT && abs(y - ys[lastIndex]) < STEP_PT) return
        }
        xs[head] = x
        ys[head] = y
        // ⚠️ `MotionEvent`'s clock is `SystemClock.uptimeMillis`, and so is the one the overlay
        // fades against. Mixing it with `elapsedRealtime` — which M12 uses for a recording's
        // timeline, and which keeps running through deep sleep — would make a trail arrive already
        // expired after the screen had been off.
        stamps[head] = eventTimeMs
        head = (head + 1) % CAPACITY
        count = min(count + 1, CAPACITY)
        recomputeBounds()
    }

    private fun recomputeBounds() {
        if (count == 0) return
        var lowX = Float.MAX_VALUE
        var lowY = Float.MAX_VALUE
        var highX = -Float.MAX_VALUE
        var highY = -Float.MAX_VALUE
        for (index in 0 until count) {
            val at = slot(index)
            lowX = min(lowX, xs[at])
            lowY = min(lowY, ys[at])
            highX = max(highX, xs[at])
            highY = max(highY, ys[at])
        }
        minX = lowX
        minY = lowY
        maxX = highX
        maxY = highY
    }

    private val point = PagePointF()

    private fun toPageSpace(viewX: Float, viewY: Float, camera: Camera, into: PagePointF) {
        into.set((viewX - camera.translate.x) / camera.scale, (viewY - camera.translate.y) / camera.scale)
    }

    internal companion object {
        /**
         * How long a mark lives, on `SystemClock.uptimeMillis`.
         *
         * Long enough to see a circle drawn round a word, short enough that the trail never becomes
         * a drawing — which is the line between a pointer and an annotation tool, and the reason
         * this feature needs no undo.
         */
        const val LIFETIME_MS = 900L

        /**
         * A page point between samples. See the class KDoc: the trail is a shape and a finer
         * sampling of it is a `drawLine` nobody can see.
         */
        const val STEP_PT = 1.5f

        /** A whole [LIFETIME_MS] of a fast sweep at [STEP_PT], with room to spare. */
        const val CAPACITY = 192

        /** `SystemClock.uptimeMillis`, named so the overlay cannot reach for the wrong clock. */
        fun now(): Long = SystemClock.uptimeMillis()
    }
}
