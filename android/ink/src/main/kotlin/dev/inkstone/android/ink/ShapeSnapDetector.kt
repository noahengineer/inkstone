package dev.inkstone.android.ink

import dev.inkstone.core.model.ShapeSnap
import dev.inkstone.core.model.SnapCandidate

/**
 * ⭐ ROADMAP M8b — **draw-and-hold**: the half that runs while the user is still touching the glass.
 *
 * ## The split, and why it is the whole design
 *
 * SPEC §4.8.7 and CLAUDE.md rule 7: nothing heavy runs between `MotionEvent` and the front buffer.
 * Draw-and-hold is on that path by definition, so the work is divided:
 *
 * - **[sample] runs per input event.** It writes two floats into arrays this object already owns
 *   and compares one distance against a threshold. **No allocation, ever** — the arrays are
 *   allocated once, at construction, and the sample count only ever resets to zero. It does not
 *   touch the in-progress stroke, `androidx.ink`, or anything that can block.
 * - **[ShapeSnap.recognise] runs once**, when [sample] answers true, and makes two allocation-free
 *   passes over those same arrays. That is why its signature is `FloatArray, FloatArray, Int` and
 *   not a list — a list would allocate an object per sample at exactly the moment the user is
 *   waiting to see the shape appear.
 *
 * ## What "held" means
 *
 * The pen has stayed within [holdRadiusPt] of where it was for [holdMillis], **after** travelling
 * far enough to be a gesture rather than a dot. Both are needed: without the travel test, resting
 * the pen before starting to write would snap; without the radius test, the natural tremor of a
 * held hand would never qualify.
 *
 * The dwell timer is measured from `MotionEvent.eventTime`, not a wall clock — it is the same
 * `uptimeMillis` base ink measures a stroke against, and it is monotonic.
 *
 * ## Capacity, and what happens when it runs out
 *
 * Samples beyond [capacity] are **dropped, not wrapped**. A ring buffer would silently rotate the
 * gesture's start out from under the classifier, which measures a closed shape by the area between
 * its first and last points — so a slow, dense circle would classify as whatever its most recent
 * arc happened to look like. Dropping keeps the shape the user drew and merely stops refining it;
 * a gesture long enough to overflow is far past the point where more samples change the answer.
 */
internal class ShapeSnapDetector(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val holdMillis: Long = DEFAULT_HOLD_MILLIS,
    private val holdRadiusPt: Float = DEFAULT_HOLD_RADIUS_PT,
    private val minTravelPt: Float = DEFAULT_MIN_TRAVEL_PT,
) {

    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private var count = 0

    /** Where the pen settled, and when — the dwell window's origin. */
    private var anchorX = 0f
    private var anchorY = 0f
    private var anchorAt = 0L

    private var travel = 0f

    /** Set once a gesture has snapped, so one hold cannot fire twice. */
    private var spent = false

    val isArmed: Boolean get() = count > 0 && !spent

    fun begin(pageX: Float, pageY: Float, eventTimeMillis: Long) {
        count = 0
        travel = 0f
        spent = false
        anchorX = pageX
        anchorY = pageY
        anchorAt = eventTimeMillis
        append(pageX, pageY)
    }

    fun cancel() {
        count = 0
        spent = true
    }

    /**
     * One input sample, in **page space**.
     *
     * ⚠️ **Returns a delay rather than a boolean, and that is not a refinement — it is the
     * difference between the feature working and not.** Android delivers `ACTION_MOVE` when the
     * digitiser reports a change, so a pen held genuinely still can stop producing events
     * altogether: the dwell would then never be *sampled*, and holding perfectly still — exactly
     * what the user was asked to do — would be the one way to never trigger it. A real hand
     * tremors enough that this usually fires from samples alone, which is precisely what makes the
     * bug intermittent and hardware-dependent.
     *
     * So the caller schedules [FIRE_NOW] work on a timer when this answers a positive delay, and
     * cancels it when a later sample answers [NOT_HOLDING]. The per-sample path is unchanged: two
     * float writes and a compare.
     *
     * @return [FIRE_NOW] when the hold is complete, [NOT_HOLDING] when this is movement rather
     *   than a hold, or the milliseconds still to wait. Answers [FIRE_NOW] at most once.
     */
    fun sample(pageX: Float, pageY: Float, eventTimeMillis: Long): Long {
        if (spent) return NOT_HOLDING
        val dx = pageX - anchorX
        val dy = pageY - anchorY
        val movedSquared = dx * dx + dy * dy

        if (movedSquared > holdRadiusPt * holdRadiusPt) {
            // Moved out of the dwell window: this is drawing, not holding. The window restarts
            // here, and the distance counts towards "was this a gesture at all".
            append(pageX, pageY)
            travel += sqrtOf(movedSquared)
            anchorX = pageX
            anchorY = pageY
            anchorAt = eventTimeMillis
            return NOT_HOLDING
        }
        // ⚠️ Deliberately NOT appended. A sample inside the dwell window is the *hold*, not the
        // drawing, and recording it corrupts the very gesture the hold exists to classify: half a
        // second of tremor at 240 Hz piles ~130 points onto one spot, which inflates `pathLength`
        // without moving the endpoints. `ShapeSnap` divides by that length twice — a line's
        // straightness fell below the bar, and a closed shape's end-gap ratio fell *under*
        // `CLOSED_MAX_GAP`, so holding still made an open arc look closed. Both got worse the
        // longer the user held, which is the opposite of what holding longer should mean.
        //
        // Dropping them also makes sampling uniform in arc length rather than in time, so a shape
        // drawn slowly and one drawn quickly present the same evidence.
        if (travel < minTravelPt) return NOT_HOLDING
        val remaining = holdMillis - (eventTimeMillis - anchorAt)
        if (remaining > 0) return remaining
        spent = true
        return FIRE_NOW
    }

    /**
     * The timer's own check, for when the samples stopped arriving.
     *
     * Takes the clock rather than an event time because there is no event — [android.os.SystemClock]
     * `uptimeMillis` is the same base `MotionEvent.eventTime` uses, which is why the subtraction
     * against [anchorAt] is meaningful at all.
     */
    fun holdElapsed(nowMillis: Long): Boolean {
        if (spent || count == 0) return false
        if (travel < minTravelPt) return false
        if (nowMillis - anchorAt < holdMillis) return false
        spent = true
        return true
    }

    /** What the gesture would become, or null to leave the ink alone. Call only after [sample] fires. */
    fun classify(): SnapCandidate? = ShapeSnap.recognise(xs, ys, count)

    private fun append(x: Float, y: Float) {
        // Dropped rather than wrapped — see the class KDoc.
        if (count >= capacity) return
        xs[count] = x
        ys[count] = y
        count++
    }

    /** `kotlin.math.sqrt` on a Float, spelled out so the per-sample path has no boxing surprises. */
    private fun sqrtOf(value: Float): Float = kotlin.math.sqrt(value)

    companion object {
        /** [sample] — the hold is complete. */
        const val FIRE_NOW: Long = 0L

        /** [sample] — this is movement, not a hold. Any timer the caller armed should be cancelled. */
        const val NOT_HOLDING: Long = -1L

        /**
         * Enough for a large, slowly drawn shape at 240 Hz. Two `FloatArray`s of this size are
         * 4 KB, allocated once per surface.
         */
        private const val DEFAULT_CAPACITY = 512

        /**
         * Long enough not to fire mid-stroke while someone thinks, short enough not to feel like a
         * wait. ⚠️ **Not measured on a device** — SPEC §4.8.7 is explicit that this and the
         * classifier's confidence bar are settled by drawing on the tablet, not in a fixture.
         */
        private const val DEFAULT_HOLD_MILLIS = 550L

        /** Page points. A held hand tremors; a drawing hand does not stay inside this. */
        private const val DEFAULT_HOLD_RADIUS_PT = 6f

        /** Below this the gesture is a dot or a tick, and there is nothing to snap. */
        private const val DEFAULT_MIN_TRAVEL_PT = 24f
    }
}
