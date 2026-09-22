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
 *   and compares two distances against thresholds — one for *"is this worth a point"*, one for
 *   *"is the pen holding"*. **No allocation, ever** — the arrays are allocated once, at
 *   construction, and the sample count only ever resets to zero. It does not touch the in-progress
 *   stroke, `androidx.ink`, or anything that can block.
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
    private val sampleSpacingPt: Float = DEFAULT_SAMPLE_SPACING_PT,
) {

    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private var count = 0

    /** Where the pen settled, and when — the dwell window's origin. */
    private var anchorX = 0f
    private var anchorY = 0f
    private var anchorAt = 0L

    /** The pen's latest position, whether or not it was worth a sample. See [classify]. */
    private var lastX = 0f
    private var lastY = 0f

    /** Where the last **appended** sample went — [sampleSpacingPt] is measured from here. */
    private var appendedX = 0f
    private var appendedY = 0f

    /** Path length of the drawing, and of the scratch tail that has not become drawing yet. */
    private var travel = 0f
    private var pendingTravel = 0f

    /** Set once a gesture has snapped, so one hold cannot fire twice. */
    private var spent = false

    /**
     * ⭐ How far the pen has come since the anchor — the part of the gesture [travel] cannot see.
     *
     * [travel] only grows when the pen *leaves* the dwell window, so it is a multiple of the hold
     * radius and undercounts the last leg by up to one: a 15 pt line exits at 6 and at 12 and then
     * finishes 3 pt later, so it was measured as **12**. Against a floor of 16 that refused every
     * line under about 21 pt — and the refusal was the dwell never firing at all, so it looked
     * like the hold was not being detected rather than like a size limit.
     *
     * Adding the straight-line distance from the anchor is exact for the missing leg and is
     * **tremor-proof by construction**: it is bounded by the hold radius, and a pen resting where
     * it went down never moves the anchor, so a rest contributes its own wobble and nothing else.
     */
    private fun reach(): Float = sqrtOf((lastX - anchorX) * (lastX - anchorX) + (lastY - anchorY) * (lastY - anchorY))

    val isArmed: Boolean get() = count > 0 && !spent

    fun begin(pageX: Float, pageY: Float, eventTimeMillis: Long) {
        count = 0
        travel = 0f
        pendingTravel = 0f
        spent = false
        anchorX = pageX
        anchorY = pageY
        anchorAt = eventTimeMillis
        lastX = pageX
        lastY = pageY
        appendedX = pageX
        appendedY = pageY
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

        // ⭐ **The gesture is sampled at its own resolution, not at the dwell radius**, and that
        // distinction is what makes a small shape work at all. Both jobs used to be done by the
        // one 6 pt test below: a sample was recorded only when it left the dwell window, so the
        // classifier saw a polyline whose vertices were 6 pt apart however small the shape was. A
        // 30 pt line arrived as five points carrying the hand's full wobble at each of them, and
        // [ShapeSnap]'s `MIN_POINTS` then refused it outright — so the floor for a line was about
        // 36 pt while a circle, whose *perimeter* is what gets sampled, worked down to 18 pt
        // across. The instrument was the feature's limit, which is this repo's oldest lesson about
        // geometric thresholds.
        //
        // ⚠️ **Path length is measured per *sample*, not per appended point.** Adding it inside the
        // branch below reads naturally and undercounts by up to one spacing at every dwell-window
        // exit, because whatever the pen travelled after the last point it was worth recording is
        // simply lost. That is a systematic under-estimate of [travel] — the one value here with a
        // threshold on it — and it showed up as a 12 pt line refusing to snap on a device while
        // passing every fixture, since a fixture's samples happen to land on the spacing.
        val moveX = pageX - lastX
        val moveY = pageY - lastY
        pendingTravel += sqrtOf(moveX * moveX + moveY * moveY)
        lastX = pageX
        lastY = pageY

        val stepX = pageX - appendedX
        val stepY = pageY - appendedY
        if (stepX * stepX + stepY * stepY >= sampleSpacingPt * sampleSpacingPt &&
            pendingTravel < DWELL_PATH_LIMIT * holdRadiusPt
        ) {
            append(pageX, pageY)
            appendedX = pageX
            appendedY = pageY
        }

        val dx = pageX - anchorX
        val dy = pageY - anchorY
        val movedSquared = dx * dx + dy * dy

        if (movedSquared > holdRadiusPt * holdRadiusPt) {
            // Moved out of the dwell window: what the pen covered since the anchor was drawing, so
            // it counts towards "was this a gesture at all". The window restarts here.
            travel += pendingTravel
            pendingTravel = 0f
            anchorX = pageX
            anchorY = pageY
            anchorAt = eventTimeMillis
            return NOT_HOLDING
        }
        // ⚠️ **The hold contributes no samples, and [sampleSpacingPt] rather than a rule is what
        // guarantees it.** A sample is only recorded once the pen has moved a whole spacing from
        // the last one recorded, and a held hand's tremor never covers 3 pt in one step — so half a
        // second of it appends nothing at all, and [DWELL_PATH_LIMIT] stops the appending outright
        // for a pen that mills about rather than rests.
        //
        // That matters because the alternative is worse than it looks. Excluding everything since
        // the last window *exit* also excludes up to a full 6 pt of genuine drawing at the end of
        // the gesture — negligible for a big shape and **half of a 12 pt one**, which left three
        // points where `MIN_POINTS` wants four. The earlier version of this file did exactly that,
        // and it is what a 12 pt line refusing to snap on a device turned out to be.
        //
        // The problem the exclusion existed for is real and is simply solved a layer up: tremor at
        // 240 Hz used to pile ~130 points onto one spot, inflating `pathLength`, which `ShapeSnap`
        // divides by twice — a line's straightness fell below the bar and a closed shape's end-gap
        // ratio fell *under* `CLOSED_MAX_GAP`, so holding still made an open arc look closed. At
        // one sample per 3 pt of movement none of those points exist to begin with.
        if (travel + reach() < minTravelPt) return NOT_HOLDING
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
        if (travel + reach() < minTravelPt) return false
        if (nowMillis - anchorAt < holdMillis) return false
        spent = true
        return true
    }

    /**
     * What the gesture would become, or null to leave the ink alone. Call only after [sample] fires.
     *
     * ⭐ **The pen's final resting position is the gesture's last point**, and leaving it out is why
     * a snapped line used to stop short of where it was drawn to. A sample is only recorded once the
     * pen has covered a whole [sampleSpacingPt], so the last one is by construction up to that far
     * behind the tip — and a line is placed by exactly its two endpoints
     * (`SnapCandidate.from`/`to`). So the tip is appended here, once: it is where the user stopped,
     * and for a closed gesture it is also the honest end of the loop `CLOSED_MAX_GAP` measures.
     */
    fun classify(): SnapCandidate? = ShapeSnap.recognise(xs, ys, withTip())

    /**
     * [count] plus the pen's tip, when the tip is not already the last sample.
     *
     * Writes into the slot after the last sample and does not advance [count], so calling this
     * twice is the same as calling it once.
     */
    private fun withTip(): Int {
        if (count <= 0 || count >= capacity) return count
        val dx = lastX - xs[count - 1]
        val dy = lastY - ys[count - 1]
        if (dx * dx + dy * dy < TIP_EPSILON_PT * TIP_EPSILON_PT) return count
        xs[count] = lastX
        ys[count] = lastY
        return count + 1
    }

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
         * 8 KB, allocated once per surface.
         *
         * ⚠️ **Raised with [DEFAULT_SAMPLE_SPACING_PT], and it had to be.** Capacity times spacing
         * is the longest path this can hold: at 512 the old 6 pt spacing covered 3,072 pt, and
         * halving the spacing without this would have halved that to less than a page diagonal —
         * so a large circle would be *dropped* mid-gesture and classified from the arc that fitted.
         * The product is what matters and it is unchanged.
         */
        private const val DEFAULT_CAPACITY = 1024

        /**
         * Long enough not to fire mid-stroke while someone thinks, short enough not to feel like a
         * wait. ⚠️ **Not measured on a device** — SPEC §4.8.7 is explicit that this and the
         * classifier's confidence bar are settled by drawing on the tablet, not in a fixture.
         */
        private const val DEFAULT_HOLD_MILLIS = 550L

        /** Page points. A held hand tremors; a drawing hand does not stay inside this. */
        private const val DEFAULT_HOLD_RADIUS_PT = 6f

        /**
         * Below this the gesture is a dot or a tick, and there is nothing to snap.
         *
         * ⚠️ **A path length, not a count of dwell-window exits.** It used to be accumulated 6 pt
         * at a time — a coarse under-estimate of the real path, which at 24 needed four exits to
         * reach, and which together with M8b's `MIN_POINTS` of 6 at 6 pt spacing put a line's floor
         * at about 36 pt. With [reach] added it is honest to within a hand's wobble, and that is
         * what lets it come down this far: at 16 the thing refusing a 15 pt line was the **dwell**,
         * seven times in twelve, and the classifier was never asked — a 12 pt line's straightness
         * measures 0.93–1.00 against a bar of 0.93, so nothing about the *shape* was ever the
         * problem.
         *
         * ⚠️ **This is the knob, and it is the one with a real cost.** Draw-and-hold cannot tell a
         * deliberate 12 pt dash from the downstroke of a letter somebody paused in the middle of;
         * all that separates them is that the second is rarer than half a second of stillness
         * suggests. Everything else here is geometry, so if small marks start snapping when they
         * should not, raise **this** and `ShapeSnap.MIN_PATH_LENGTH_PT` beside it — not the
         * sampling spacing, which decides how well a gesture is measured rather than how small one
         * may be.
         */
        private const val DEFAULT_MIN_TRAVEL_PT = 9f

        /**
         * ⭐ How far the pen moves before the gesture is worth another point.
         *
         * ⚠️ It is deliberately **not** [DEFAULT_HOLD_RADIUS_PT]. A hold radius is a fact about a
         * hand at rest and cannot go much below 6 pt; a sampling spacing is a fact about the shape
         * being drawn. Tying them together made the second follow the first, which is what set the
         * smallest shape the feature could recognise.
         *
         * ⚠️ **And it is not as fine as it could be, deliberately** — `openCandidate` scores a line
         * by `chord / pathLength`, and a summed path length grows with the digitiser's own jitter
         * as the spacing shrinks. Measured over a 100 pt line, worst of twelve seeds, this is the
         * whole trade:
         *
         * | per-sample jitter | 1.5 pt spacing | 3 pt | 6 pt |
         * |---|---|---|---|
         * | ±0.25 pt (an S Pen at fit width) | 0.99 | 0.99 | 1.00 |
         * | ±0.5 pt | 0.94 | 0.99 | 1.00 |
         * | ±0.75 pt | 0.79 | 0.96 | 0.99 |
         * | ±1.0 pt (zoomed well out) | 0.44 | 0.92 | 0.98 |
         *
         * `LINE_MIN_STRAIGHTNESS` is 0.93, so at 1.5 pt a shaky hand's line stops being a line —
         * which is the *same* class of bug in the opposite direction, the sampling rate deciding
         * the answer. 3 pt keeps a factor of two of headroom over any jitter a stylus plausibly
         * has. The smallest shape the feature will take is [DEFAULT_MIN_TRAVEL_PT]'s to decide;
         * this only decides how well the gesture is measured once it is taken.
         */
        private const val DEFAULT_SAMPLE_SPACING_PT = 3f

        /**
         * How much path may pile up inside the dwell window, in window radii, before sampling stops.
         *
         * Crossing a circle of radius `r` costs at most `2r` of path; four radii is milling about
         * rather than crossing, and the only thing left that can produce it is a hold. This is the
         * backstop under [sampleSpacingPt]'s own exclusion of a held hand — see [sample].
         */
        private const val DWELL_PATH_LIMIT = 4f

        /** Below this the tip is already the last recorded sample. See [withTip]. */
        private const val TIP_EPSILON_PT = 0.05f
    }
}
