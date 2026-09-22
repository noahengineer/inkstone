// Every constant here is a measured ratio with its meaning in the name beside it — `RECTANGLE_FILL`
// says more than a symbol would, and the table of three targets is the algorithm.
@file:Suppress("MagicNumber")

package dev.inkstone.core.model

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max

/**
 * ROADMAP M8b — **draw-and-hold shape snapping**: which shape a freehand gesture was, decided by
 * arithmetic over the points it left.
 *
 * ## This runs on the ink path, and the split is the whole design
 *
 * SPEC §2.4 and CLAUDE.md rule 7: *"no allocation in a loop, no I/O, no serialization, no
 * recognition"* between `MotionEvent` and the front buffer. Draw-and-hold is on that path by
 * definition — the user is still touching the screen — so the work is split in two:
 *
 * - **Per sample**, the caller only records two floats into a buffer it already owns and compares
 *   a distance against a threshold. No allocation, no branch into this file. That is what runs
 *   240 times a second.
 * - **Once, when the hold fires**, [recognise] makes two passes over that buffer. It is O(points)
 *   float arithmetic with **no allocation at all** — which is why the signature is three
 *   primitives and not a `List<PagePoint>`: a list would allocate one object per sample at the
 *   exact moment the user is waiting to see the shape appear.
 *
 * *"Recognition"* in rule 7's sense means a recogniser — ML Kit, a model, a dictionary lookup
 * (SPEC §8). This is a shoelace area and a bounding box.
 *
 * ## Why a ratio and not a corner detector
 *
 * A corner detector needs smoothing, and a smoothing window is a parameter that behaves
 * differently at every stylus sampling rate and every zoom level. The **area a gesture encloses,
 * as a fraction of its bounding box**, separates the three closed kinds with no parameters at all:
 * a rectangle fills its box, an ellipse fills π/4 of it, a triangle half. Those are facts about
 * the shapes, not tuning.
 *
 * ## ⚠️ …but only against a box the shape is square to, and M8b used the axis-aligned one
 *
 * That is the bug this file was rewritten to fix, and it was not a tuning problem. Measured against
 * the **axis-aligned** box the ratio is not a property of the shape at all — a square's is
 * `1 / (1 + k·sin 2θ)`, which sweeps the whole feature range as the user tilts their hand, so a
 * square drawn 7° off-axis scored *ellipse* and one drawn at 45° scored *triangle at confidence
 * 1.000*. A **circle's** axis-aligned fill is `π/4` at every angle, because a circle's box does not
 * change when it turns — which made the ellipse the one kind the old feature could actually
 * measure, and every tilted rectangle fell through the ellipse window on its way down.
 *
 * The fill is now taken against [OrientedBounds.minimumArea], the smallest enclosing rectangle at
 * *any* angle. All four kinds are rotation-invariant against it by construction, so the three
 * targets are finally the facts this KDoc always claimed they were. See [OrientedBox] for the
 * table of what the old feature did to a tilted square.
 *
 * ⚠️ **What is still tuned is [DEFAULT_CONFIDENCE] and [FILL_WINDOW]**, and M7's lesson applies with
 * full force: *"a synthetic fixture is the worst possible witness for a geometric heuristic"* — its
 * rotated-text detector passed its unit test and produced 105 false positives on real pages. A test
 * in this repo can prove that a mathematically perfect ellipse classifies as an ellipse; it cannot
 * prove that a person's ellipse does. The numbers below are a starting point to be **checked by
 * drawing on the tablet**, and the acceptance criterion they exist to serve — *"no fighting it to
 * draw a freehand curve"* — is a device question, not a JVM one.
 *
 * ## What is not recognised
 *
 * [ShapeKind.ARROW] never comes out of here. An arrowhead is a few samples at one end of a
 * gesture, indistinguishable from a hooked pen-lift, and guessing wrong turns a line into an arrow
 * that the user then has to undo. An arrow is a **tool the user picks**, and picking it makes the
 * same gesture that yields a [ShapeKind.LINE] yield an arrow instead.
 */
public object ShapeSnap {

    /**
     * Classify the gesture held in [xs] / [ys] — the first [count] entries, in page space.
     *
     * ⚠️ **This allocates**, and the class KDoc's *"no allocation at all"* was narrowed when the
     * oriented box arrived: [OrientedBounds.minimumArea] builds a convex hull and therefore an index
     * array. It runs **once**, when the hold fires and the user is already waiting for a shape to
     * appear — never per input sample, which is the half of rule 7 that matters. `sample` on the
     * detector is still two float writes and a compare.
     *
     * @return null when nothing matched well enough, which **keeps the freehand ink**. That is the
     *   default and it is the behaviour the acceptance criterion asks for: snapping the user did
     *   not want is far worse than snapping they have to ask for twice.
     */
    public fun recognise(
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        minimumConfidence: Float = DEFAULT_CONFIDENCE,
    ): SnapCandidate? {
        if (count < MIN_POINTS) return null
        val metrics = measure(xs, ys, count)
        if (metrics.pathLength < MIN_PATH_LENGTH_PT) return null

        val open = openCandidate(xs, ys, count, metrics)
        val arc = arcCandidate(xs, ys, count, metrics)
        val closed = closedCandidate(xs, ys, count, metrics)
        val best = listOfNotNull(open, arc, closed).maxByOrNull { it.confidence } ?: return null
        return best.takeIf { it.confidence >= minimumConfidence }
    }

    /** Readability overload for tests and for callers not on the ink path. Allocates. */
    public fun recognise(points: List<PagePoint>, minimumConfidence: Float = DEFAULT_CONFIDENCE): SnapCandidate? {
        val xs = FloatArray(points.size) { points[it].x }
        val ys = FloatArray(points.size) { points[it].y }
        return recognise(xs, ys, points.size, minimumConfidence)
    }

    /**
     * A straight line — the one open kind.
     *
     * Straightness is `endpoint distance / path length`, which is 1 for a perfect line and falls
     * away for anything that wanders. It is scale-free and rotation-free, so it behaves the same
     * for a 2 cm line drawn quickly and a full-page one drawn slowly.
     */
    private fun openCandidate(xs: FloatArray, ys: FloatArray, count: Int, metrics: Metrics): SnapCandidate? {
        if (metrics.pathLength <= 0f) return null
        val straightness = metrics.endGap / metrics.pathLength
        if (straightness < LINE_MIN_STRAIGHTNESS) return null
        // ⚠️ **Ramped from [DEFAULT_CONFIDENCE] to 1, not from 0 to 1** — the fourth time this file
        // has been bitten by a confidence bar sitting downstream of a ramp, after [FILL_WINDOW],
        // [LINE_CERTAIN] itself and [ARC_MAX_RESIDUAL]. Ramping from zero made the *effective*
        // floor `0.93 + 0.55 × (0.96 − 0.93)` = **0.9465**, not the 0.93 the constant names, so a
        // 12 pt line whose last sample sat 0.4 pt off the chord scored 0.50 and was refused by a
        // limit it was well inside. Everything this function accepts now clears the bar by
        // construction, and a straighter line still ranks above a less straight one.
        //
        // The ramp still reaches 1.0 at [LINE_CERTAIN] rather than at a perfectly straight
        // gesture, which is the same repair made once before: a hand-drawn line over a hand's
        // width is never straighter than about 3 % of its own chord.
        val along =
            ((straightness - LINE_MIN_STRAIGHTNESS) / (LINE_CERTAIN - LINE_MIN_STRAIGHTNESS)).coerceIn(0f, 1f)
        val confidence = DEFAULT_CONFIDENCE + along * (1f - DEFAULT_CONFIDENCE)
        return SnapCandidate(
            kind = ShapeKind.LINE,
            box = metrics.box,
            from = PagePoint(xs[0], ys[0]),
            to = PagePoint(xs[count - 1], ys[count - 1]),
            confidence = confidence,
        )
    }

    /**
     * ⭐ **A curve** — an open gesture that is one clean arc, which becomes a [ShapeKind.LINE] with
     * a [ShapeRecord.bend].
     *
     * ## Why this exists, in the user's words
     *
     * > *"when not drawing a straight line it should not not work when trying to create a shape, it
     * > should create a curve out of that"*
     *
     * Draw-and-hold used to refuse every open arc, on the strength of SPEC §4.8.7's *"no fighting it
     * to draw a freehand curve"*. That reading was too broad: the criterion protects a curve drawn
     * and **lifted**, and this path only ever runs when the user has deliberately held still. A hold
     * is a request, so refusing it was not protecting anything — it was the feature declining to
     * work on the one gesture the user had asked it to work on.
     *
     * ## How one arc is told from a squiggle
     *
     * By fitting the quadratic the shape would actually be drawn as, and measuring how far the ink
     * strays from it — not by a corner detector, a sign-change count or a smoothing window, all of
     * which need a parameter per stylus rate. The residual is measured as a **distance to the curve**
     * rather than to `Q(tᵢ)`, because a stroke's arc-length parameterisation is not the Bézier's, and
     * comparing against `Q(tᵢ)` would reject deep arcs for being deep rather than for being wrong.
     *
     * An S-curve, a zigzag and a scribble all fail it by construction: no single quadratic passes
     * near all of their points. So does a near-loop, which is why the sagitta is capped — a
     * quadratic is a parabola arc and cannot express one, and snapping to a shape the format cannot
     * draw is worse than keeping the ink.
     */
    private fun arcCandidate(xs: FloatArray, ys: FloatArray, count: Int, metrics: Metrics): SnapCandidate? {
        // A closed gesture is [closedCandidate]'s, and its chord is meaningless anyway.
        if (metrics.pathLength <= 0f || metrics.endGap / metrics.pathLength <= CLOSED_MAX_GAP) return null
        val chord = metrics.endGap
        if (chord < MIN_PATH_LENGTH_PT) return null

        val midpoint = arcLengthMidpoint(xs, ys, count, metrics.pathLength)
        val ax = xs[0]
        val ay = ys[0]
        val bx = xs[count - 1]
        val by = ys[count - 1]
        // Depth of the bow, relative to the chord. Too shallow is a line and [openCandidate] has it;
        // too deep is beyond what one quadratic can draw.
        val sagitta = perpendicular(ax, ay, bx, by, midpoint) / chord
        if (sagitta < ARC_MIN_SAGITTA || sagitta > ARC_MAX_SAGITTA) return null

        val residual = worstDistanceToQuadratic(xs, ys, count, ax, ay, bx, by, midpoint) / chord
        if (residual > ARC_MAX_RESIDUAL) return null
        return SnapCandidate(
            kind = ShapeKind.LINE,
            box = metrics.box,
            from = PagePoint(ax, ay),
            to = PagePoint(bx, by),
            // ⚠️ **Ramped down to the shared bar, not to zero**, and this file has now been bitten by
            // the alternative three times: a confidence gate downstream of a ramp silently narrows
            // every window the ramp feeds (see [FILL_WINDOW], and the line's own [LINE_CERTAIN]).
            // Ramping `1 → 0` made the *effective* residual limit `ARC_MAX_RESIDUAL × (1 − 0.55)`,
            // so a perfectly good 140° arc — residual 0.042 against a stated limit of 0.06 — scored
            // 0.30 and was refused. Everything this function accepts now also clears
            // [DEFAULT_CONFIDENCE] by construction, and a better fit still ranks above a worse one.
            confidence = (1f - residual / ARC_MAX_RESIDUAL * (1f - DEFAULT_CONFIDENCE)).coerceIn(0f, 1f),
            bend = midpoint,
        )
    }

    /** The sampled point at half the gesture's length — what the curve's `t = 0.5` should land on. */
    private fun arcLengthMidpoint(xs: FloatArray, ys: FloatArray, count: Int, pathLength: Float): PagePoint {
        var travelled = 0f
        val half = pathLength / 2f
        for (index in 1 until count) {
            travelled += hypot(xs[index] - xs[index - 1], ys[index] - ys[index - 1])
            if (travelled >= half) return PagePoint(xs[index], ys[index])
        }
        return PagePoint(xs[count - 1], ys[count - 1])
    }

    /** Distance from [point] to the infinite line through `a → b`. */
    private fun perpendicular(ax: Float, ay: Float, bx: Float, by: Float, point: PagePoint): Float {
        val dx = bx - ax
        val dy = by - ay
        val length = hypot(dx, dy)
        if (length <= 0f) return 0f
        return abs((point.x - ax) * dy - (point.y - ay) * dx) / length
    }

    /**
     * How far the worst sample strays from the quadratic through `a`, [midpoint] and `b`.
     *
     * The curve is flattened once into [ARC_SAMPLES] points and every input sample takes the nearest
     * of them, so the measure is a distance to the *curve* and carries no parameterisation bias. One
     * pass over a fixed-size array per sample; it runs once, when the hold fires.
     */
    @Suppress("LongParameterList") // Two endpoints, the apex and the buffer they are measured against.
    private fun worstDistanceToQuadratic(
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        midpoint: PagePoint,
    ): Float {
        // The control point that puts the curve through the apex — ShapeGeometry's own conversion.
        val cx = 2f * midpoint.x - (ax + bx) / 2f
        val cy = 2f * midpoint.y - (ay + by) / 2f
        val curveX = FloatArray(ARC_SAMPLES)
        val curveY = FloatArray(ARC_SAMPLES)
        for (step in 0 until ARC_SAMPLES) {
            val t = step.toFloat() / (ARC_SAMPLES - 1)
            val u = 1f - t
            curveX[step] = u * u * ax + 2f * u * t * cx + t * t * bx
            curveY[step] = u * u * ay + 2f * u * t * cy + t * t * by
        }
        var worst = 0f
        for (index in 0 until count) {
            var nearest = Float.MAX_VALUE
            for (step in 0 until ARC_SAMPLES) {
                val distance = hypot(xs[index] - curveX[step], ys[index] - curveY[step])
                if (distance < nearest) nearest = distance
            }
            if (nearest > worst) worst = nearest
        }
        return worst
    }

    /**
     * A rectangle, an ellipse or a triangle, by how much of its **minimum-area** bounding box the
     * gesture encloses.
     *
     * The gesture must close first: an open arc encloses an area too, and calling it an ellipse
     * would snap every curve the user drew on purpose.
     *
     * The box is [OrientedBounds.minimumArea] rather than the axis-aligned one, which is what makes
     * the three targets rotation-invariant — see the class KDoc for the table of what the
     * axis-aligned version did to a tilted square. It is also what the shape is *placed* by, so a
     * rectangle drawn at 20° comes out at 20° instead of being flattened onto the axes.
     */
    private fun closedCandidate(xs: FloatArray, ys: FloatArray, count: Int, metrics: Metrics): SnapCandidate? {
        if (metrics.pathLength <= 0f || metrics.endGap / metrics.pathLength > CLOSED_MAX_GAP) return null
        val oriented = OrientedBounds.minimumArea(xs, ys, count)?.takeIf { it.area > 0f } ?: return null

        // ⭐ **Corners decide the family; the fill only decides which cornered kind.**
        //
        // Two independent tests, either of which is strong evidence of corners, because they fail in
        // *different* circumstances and that is the whole reason for having both. Overshooting a
        // corner enlarges the minimum-area box and craters the **fill** while leaving the corners
        // exactly as sharp; rounding the corners does the reverse. A circle satisfies neither — its
        // turning is spread evenly and its fill is π/4, nowhere near [CORNERED_FILL].
        val fill = metrics.area / oriented.area
        val cornered = cornerConcentration(xs, ys, count) >= CORNER_CONCENTRATION || fill >= CORNERED_FILL
        val (kind, confidence) = nearestTarget(fill, cornered) ?: return null
        return SnapCandidate(
            kind = kind,
            box = metrics.box,
            from = PagePoint(metrics.box.left, metrics.box.top),
            to = PagePoint(metrics.box.right, metrics.box.bottom),
            confidence = confidence.coerceIn(0f, 1f),
            oriented = straightened(oriented),
        )
    }

    /**
     * Which target [fill] is nearest **within its family**, and how confidently.
     *
     * ⭐ [cornered] comes from [cornerConcentration] and narrows the choice to two kinds that the
     * fill separates by **half its range** (1.0 against 0.5) — or to one kind, if the gesture has no
     * corners. That is the whole repair: the fill was previously asked to separate a rectangle from
     * an *ellipse*, targets 0.215 apart, using a measurement that a hand's rounding and overshoot
     * move by more than that.
     */
    private fun nearestTarget(fill: Float, cornered: Boolean): Pair<ShapeKind, Float>? {
        var best: ShapeKind? = null
        var bestConfidence = 0f
        for ((kind, target) in FILL_TARGETS) {
            if ((kind == ShapeKind.ELLIPSE) == cornered) continue
            val confidence = 1f - missBy(kind, fill, target) / FILL_WINDOW
            if (confidence > bestConfidence) {
                bestConfidence = confidence
                best = kind
            }
        }
        return best?.let { it to bestConfidence }
    }

    /**
     * ⭐ **How concentrated a closed gesture's turning is** — near 1 when it turns at a few corners,
     * near 0 when it turns everywhere. The feature that tells a square from a circle.
     *
     * ## Why the fill ratio could not do this, twice
     *
     * *"Its far to easy to make a circle"*, then *"still cant make a square, its way to biased to a
     * circle"*. The area a gesture encloses as a fraction of its box is a fact about the *ideal*
     * shapes — a rectangle fills all of its box, an ellipse π/4 — and those targets are only 0.215
     * apart. A hand moves the measurement by more than that: rounding the corners takes area away,
     * bowing an edge outward or overshooting a corner *enlarges the box*, and every one of those
     * pushes a square toward the circle with nothing pushing back (the rectangle's target is the top
     * of the range). Widening the rectangle's window helped and did not fix it, because the feature
     * itself is a poor separator for real gestures. Tuning a constant a third time would have been
     * the third guess.
     *
     * Turning is a different measurement with an enormous margin. Measured over 20 rotations of each
     * fixture with 2–4% noise: **boxes bottom out at 0.456, circles and ellipses top out at 0.398**,
     * including a square whose corners are rounded by a fifth of its side.
     *
     * ## Why this is not the corner detector the class KDoc rejects
     *
     * That rejection is about smoothing windows measured in **samples**, which behave differently at
     * every stylus rate and zoom. This resamples to a fixed count of points **equally spaced along
     * the arc**, so the window is a fraction of the gesture's own length and carries no dependence on
     * how fast it was drawn or how often the digitiser reported. The turn is then the angle between
     * consecutive resampled steps, and the statistic is the share of all turning held by the largest
     * [CORNER_BINS] of them — dimensionless, rotation-invariant and scale-invariant by construction.
     *
     * Runs once, when the hold fires, and allocates two small fixed arrays.
     */
    private fun cornerConcentration(xs: FloatArray, ys: FloatArray, count: Int): Float {
        // ⚠️ **Not `count < CORNER_SAMPLES`.** That guard read *"too few samples"* as *"no corners"*,
        // which is the wrong default and fires on exactly the inputs that are hardest to classify:
        // every `adb shell input stylus motionevent` gesture is about 25 points, so on the emulator
        // every closed shape became an ellipse. Resampling **interpolates**, so fewer input points
        // than output samples is not a problem — a coarse polyline is still a faithful outline.
        if (count < MIN_POINTS) return 0f
        val sampleX = FloatArray(CORNER_SAMPLES)
        val sampleY = FloatArray(CORNER_SAMPLES)
        resampleByArcLength(xs, ys, count, sampleX, sampleY)

        val turns = FloatArray(CORNER_SAMPLES)
        var total = 0f
        for (index in 0 until CORNER_SAMPLES) {
            // ⚠️ Over a **span** of samples rather than adjacent ones, and the span is what makes
            // this survive coarse input. Point-to-point turning on a 12-sided polyline sees twelve
            // "corners" and hands 6/12 of the turning to the top six — a coarse circle then reads as
            // cornered. Measured over 1/24 of the perimeter either side, sampling noise averages out
            // and a real corner still shows its full 90°.
            val previous = (index - CORNER_SPAN + CORNER_SAMPLES) % CORNER_SAMPLES
            val next = (index + CORNER_SPAN) % CORNER_SAMPLES
            val turn = turnAt(
                sampleX[index] - sampleX[previous],
                sampleY[index] - sampleY[previous],
                sampleX[next] - sampleX[index],
                sampleY[next] - sampleY[index],
            )
            turns[index] = turn
            total += turn
        }
        if (total < EPSILON) return 0f

        // The largest CORNER_BINS turns, by selection rather than a sort: the array is small and
        // this keeps the pass allocation-free.
        var top = 0f
        var taken = 0
        var ceiling = Float.MAX_VALUE
        while (taken < CORNER_BINS) {
            var best = -1f
            for (turn in turns) if (turn < ceiling && turn > best) best = turn
            if (best < 0f) break
            // Count every turn tied at this value, so a corner split across two samples is not
            // silently halved — then continue below it.
            for (turn in turns) {
                if (turn == best && taken < CORNER_BINS) {
                    top += turn
                    taken++
                }
            }
            ceiling = best
        }
        return top / total
    }

    /** [count] points resampled to [intoX]'s length, equally spaced along the path. */
    private fun resampleByArcLength(xs: FloatArray, ys: FloatArray, count: Int, intoX: FloatArray, intoY: FloatArray) {
        var length = 0f
        for (index in 1 until count) length += hypot(xs[index] - xs[index - 1], ys[index] - ys[index - 1])
        val step = length / intoX.size
        var walked = 0f
        var at = 0
        for (slot in intoX.indices) {
            val target = step * slot
            while (at < count - 2 && walked + hypot(xs[at + 1] - xs[at], ys[at + 1] - ys[at]) < target) {
                walked += hypot(xs[at + 1] - xs[at], ys[at + 1] - ys[at])
                at++
            }
            val segment = hypot(xs[at + 1] - xs[at], ys[at + 1] - ys[at])
            val fraction = if (segment <= 0f) 0f else ((target - walked) / segment).coerceIn(0f, 1f)
            intoX[slot] = xs[at] + (xs[at + 1] - xs[at]) * fraction
            intoY[slot] = ys[at] + (ys[at + 1] - ys[at]) * fraction
        }
    }

    /** The angle between two consecutive steps, in radians. Zero for either being degenerate. */
    private fun turnAt(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val m1 = hypot(x1, y1)
        val m2 = hypot(x2, y2)
        if (m1 < EPSILON || m2 < EPSILON) return 0f
        val cosine = ((x1 * x2 + y1 * y2) / (m1 * m2)).coerceIn(-1f, 1f)
        return acos(cosine)
    }

    /**
     * ⭐ How far [fill] misses [target] — with a rectangle's **one-sided allowance**.
     *
     * ## Why one kind gets an allowance and the others do not
     *
     * A gesture's fill can never exceed 1.0: the minimum-area box encloses the polygon, so the ratio
     * is bounded above by the rectangle's own target. Every other kind sits in the *interior* of the
     * range and is approached from both sides; the rectangle can only ever be approached from below,
     * so **half of its window is unreachable** and every imperfection — a rounded corner, a bowed
     * edge, an overshoot past a corner that widens the hull — moves it toward the ellipse and
     * nothing moves it back.
     *
     * That asymmetry is structural, and the symptom is exactly what was reported: *"its far to easy
     * to make a circle, whenever i try to make a square it turns into a circle"*. It is also a second
     * dose of the same medicine the KDoc above already describes — M8b nudged the rectangle's target
     * down to 0.95 to meet real gestures, the oriented box made 1.0 honest again, and the *shape* of
     * the window was still wrong.
     *
     * ⚠️ **[RECTANGLE_SLACK] is tuned to a report, not to a measurement**, which is the weakest kind
     * of constant in this file and is flagged as such. What it does is checkable, though: the
     * rectangle/ellipse boundary moves from fill **0.8925 to 0.8525**, and a rectangle can clear
     * [DEFAULT_CONFIDENCE] from **0.812** rather than 0.892. A perfect circle at π/4 is untouched —
     * it still scores 1.000 as an ellipse and loses nothing to the rectangle, because the winner is
     * whichever confidence is *higher*, not whichever clears the bar.
     */
    private fun missBy(kind: ShapeKind, fill: Float, target: Float): Float {
        val distance = abs(fill - target)
        return if (kind == ShapeKind.RECTANGLE) max(0f, distance - RECTANGLE_SLACK) else distance
    }

    /**
     * [box] with a near-axis-aligned angle rounded to exactly zero.
     *
     * Nobody draws a rectangle to within two degrees on purpose, and a shape placed at 1.4° looks
     * like a mistake next to the text around it — the tilt is visible in a way the user did not ask
     * for and cannot easily correct. Below [STRAIGHTEN_RADIANS] the intent is *"straight"*, so it is
     * made straight; above it the tilt is deliberate and is kept exactly.
     */
    private fun straightened(box: OrientedBox): OrientedBox =
        if (abs(box.angleRadians) < STRAIGHTEN_RADIANS) box.copy(angleRadians = 0f) else box

    /**
     * Bounding box, path length, endpoint gap and enclosed area — **one pass, no allocation**
     * except the result.
     *
     * The area is the shoelace formula over the gesture treated as a closed polygon. For an open
     * gesture that is the area of the polygon you get by joining the last point back to the first,
     * which is exactly the quantity [closedCandidate] wants and is meaningless for anything it has
     * already rejected.
     */
    private fun measure(xs: FloatArray, ys: FloatArray, count: Int): Metrics {
        var left = xs[0]
        var right = xs[0]
        var top = ys[0]
        var bottom = ys[0]
        var length = 0f
        var twiceArea = 0f
        for (index in 0 until count) {
            val x = xs[index]
            val y = ys[index]
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
            if (index > 0) length += hypot(x - xs[index - 1], y - ys[index - 1])
            val next = if (index == count - 1) 0 else index + 1
            twiceArea += x * ys[next] - xs[next] * y
        }
        return Metrics(
            box = PageRect(left, top, right, bottom),
            pathLength = length,
            endGap = hypot(xs[count - 1] - xs[0], ys[count - 1] - ys[0]),
            area = abs(twiceArea) / 2f,
        )
    }

    private class Metrics(val box: PageRect, val pathLength: Float, val endGap: Float, val area: Float)

    /**
     * How much of its **minimum-area** box each closed kind fills. Facts about the shapes, and now
     * true at every angle: a rectangle all of it, an ellipse `π/4`, a triangle half.
     *
     * ⚠️ Rectangle is **1.0**, not M8b's 0.95. Against the axis-aligned box no real rectangle ever
     * reached 0.95 so the target had been nudged down to meet them — which handicapped the one kind
     * it was meant to help: a geometrically perfect rectangle scored 0.643 where a perfect ellipse
     * and a perfect triangle both scored 1.000, and every degradation moved it *toward* the ellipse
     * target. Against the oriented box a rectangle really does fill all of it, so the honest number
     * is the one that works.
     *
     * The minimal enclosing rectangle of a triangle is exactly twice its area — a theorem, not a
     * measurement — so 0.5 needs no window of its own either.
     */
    private val FILL_TARGETS = listOf(
        ShapeKind.RECTANGLE to 1f,
        ShapeKind.ELLIPSE to 0.785f,
        ShapeKind.TRIANGLE to 0.5f,
    )

    /**
     * Width parameter of the window around each target, in fill-ratio units.
     *
     * ⚠️ **The accepted half-width is `FILL_WINDOW × (1 − DEFAULT_CONFIDENCE)`, not `FILL_WINDOW`** —
     * the confidence bar narrows it, and reading this constant as the tolerance is how M8b ended up
     * accepting only 36.5 % of the fill range. At 0.24 and a bar of 0.55 the half-width is 0.108,
     * giving rectangle `[0.892, 1.0]`, ellipse `[0.677, 0.893]` and triangle `[0.392, 0.608]`.
     *
     * Rectangle and ellipse now meet rather than leaving a gap, because the boundary between them is
     * real and continuous: a rectangle with corners rounded far enough *is* becoming an oval, and at
     * fill ≈ 0.89 the nearer target should simply win. The genuine no-man's-land is between ellipse
     * and triangle, where nothing a person draws lives. **Tune on the tablet, not here.**
     */
    private const val FILL_WINDOW = 0.24f

    /**
     * How far below 1.0 a gesture may fill its box and still be a rectangle for free. See [missBy].
     *
     * Third of a window, and the reasoning that bounds it is the circle: the allowance may not be so
     * large that a genuine ellipse (π/4 ≈ 0.785) is nearer the rectangle than its own target, which
     * would need it to exceed 0.215. At 0.08 a circle keeps a comfortable margin — rectangle
     * confidence 0.438 against the ellipse's 1.000 — and the boundary between the two sits at 0.8525
     * instead of 0.8925.
     */
    private const val RECTANGLE_SLACK = 0.08f

    /**
     * Above this share of its turning in [CORNER_BINS] samples, a closed gesture **has corners**.
     *
     * Measured over 30 random rotations of each fixture with 2–4% positional noise:
     *
     * | Gesture | concentration (min for boxes, max for round) |
     * |---|---|
     * | square, corners rounded 8% of the side | 0.401 |
     * | rectangle 240×140, rounded 10% | 0.393 |
     * | triangle | 0.520 |
     * | circle, 40 and 24 samples | 0.301 / 0.287 |
     * | ellipse 240×140, dense and coarse | 0.332 / 0.347 |
     *
     * ⚠️ **A square rounded by 15% or more of its side falls below this** (0.299) — at that point it
     * genuinely is a squircle, and no turning-based feature separates it from an oval. That case is
     * caught by [CORNERED_FILL] instead, which is exactly why there are two tests and not one.
     *
     * Still synthetic, and M7's lesson stands. What is different from the fill ratio *alone* is that
     * this has a margin at all.
     */
    private const val CORNER_CONCENTRATION = 0.35f

    /**
     * A gesture filling this much of its minimum-area box **has corners**, whatever its turning says.
     *
     * The second half of the pair. A rounded square keeps a fill near 1.0 while its corners soften;
     * an overshot square keeps its corners while its fill craters. A circle is at π/4 ≈ 0.785 and
     * comes nowhere near this, so the disjunction cannot cost the ellipse anything.
     *
     * Measured over 30 noisy trials each, with `concentration ≥ 0.35 OR fill ≥ 0.87`:
     *
     * | Gesture | classified as cornered |
     * |---|---|
     * | square, corners rounded 6% / 12% / 18% | 30/30, 30/30, 30/30 |
     * | square rounded 25% (a squircle) | 26/30 |
     * | circle, noisy circle, coarse circle, ellipse | **0/30 each** |
     *
     * The circles' worst case was a fill of 0.800 and a concentration of 0.341 — both under their
     * gates with room, which is the margin the fill ratio alone never had.
     */
    private const val CORNERED_FILL = 0.87f

    /** How many equally-spaced samples the outline is reduced to before its turning is measured. */
    private const val CORNER_SAMPLES = 48

    /**
     * How far either side of a sample its turning is measured, in samples — 1/24 of the perimeter.
     *
     * ⚠️ **Not 1.** Adjacent-sample turning counts every vertex of a coarse polyline as a corner: a
     * 12-point circle hands half its turning to the top six samples and reads as *cornered*. Every
     * `adb` gesture and plenty of fast strokes are that coarse.
     */
    private const val CORNER_SPAN = 2

    /** How many of those samples' turns count as "the corners" — four, with one either side spare. */
    private const val CORNER_BINS = 6

    /** Below this a length is zero, and dividing by it would produce a NaN that reaches a path. */
    private const val EPSILON = 1e-6f

    /**
     * A gesture whose ends are further apart than this fraction of its length is not closed.
     *
     * ⚠️ Tightened from M8b's 0.22, which was loose enough to let an **open 300° arc** through at
     * ellipse-confidence 0.84 — so a `C`, a bracket or any large open curve held still was eaten and
     * turned into an oval. That is the second half of *"i can only do ovels"*, and it is a
     * completely separate mechanism from the fill ratio. At 0.12 an arc must reach about 322° before
     * it counts as closed, while a 200 × 120 rectangle may still leave a 77 pt gap at its corner.
     */
    private const val CLOSED_MAX_GAP = 0.12f

    /**
     * ⭐ How deep a bow has to be before it is a curve rather than a line, as a fraction of the
     * chord — **derived from [LINE_MIN_STRAIGHTNESS], not chosen.**
     *
     * ## Why it is derived, and what happened when it was not
     *
     * It was 0.06, which let the arc bid for gestures [openCandidate] was still claiming, and the
     * two were then separated by comparing confidences that are **not on the same scale**. A line's
     * ramp is 0.03 wide (`[0.93, 0.96]`), so its confidence falls off a cliff — 0.83 at a 60° bow,
     * 0.30 at 70° — while an arc's sits nearly flat around 0.78 across that whole range. At 60° the
     * line won by **0.03**, which is noise on a real gesture. The report was immediate: *"straight
     * lines dont create properly"*, because a hand's line is never straight enough to stay on the
     * good side of a cliff that steep.
     *
     * So the candidates are **disjoint by construction** now: the arc only takes what the line has
     * already refused. A gesture at the line's own floor, `sin(x)/x = 0.93` with `x = θ/2`, is a
     * 74.8° arc, whose sagitta is `(1 − cos x) / (2 sin x)` = **0.169**. Anything shallower is the
     * line's; anything deeper is not a line at all.
     *
     * ⚠️ **Move [LINE_MIN_STRAIGHTNESS] and this has to move with it.** `ShapesTest` asserts the
     * property rather than the number — no gesture straight enough to be a line ever comes back as
     * a curve — so the pair cannot drift apart silently.
     *
     * The cost is stated: a shallow bow between about 65° and 78° now snaps to **nothing** and stays
     * ink, as it did before curves existed. That is this file's standing bias — *"snapping the user
     * did not want is far worse than snapping they have to ask for twice"*.
     *
     * Above [ARC_MAX_SAGITTA] a single quadratic cannot draw the arc (it is a parabola; about 195°
     * is the end of it), and snapping to a shape the format cannot express is worse than ink.
     */
    private const val ARC_MIN_SAGITTA = 0.17f
    private const val ARC_MAX_SAGITTA = 0.6f

    /**
     * How far the ink may stray from the curve it would become, as a fraction of the chord.
     *
     * ⭐ **Set from a measurement, not from feel.** Residuals over the gestures that matter:
     *
     * | Gesture | Residual |
     * |---|---|
     * | 30° / 60° / 90° arc | 0.022 / 0.023 / 0.024 |
     * | 120° / 140° arc | 0.031 / 0.042 |
     * | 160° / 180° arc | 0.063 / 0.073 |
     * | 90° corner, rounded r=30 of a 100 arm | 0.059 |
     * | …rounded r=10 | 0.074 |
     * | …sharp | 0.089 |
     * | S-curve | 0.134 |
     * | scribble | 0.485 |
     *
     * ⚠️ **The bar is set by the corner, not by the squiggle.** A scribble is nowhere near, but a
     * *rounded corner* and a *deep arc* genuinely overlap — they are similar shapes, and no
     * threshold separates them cleanly. So this sits below the rounded corner rather than above the
     * deepest arc: a half-circle held still stays ink, which is a feature that did not fire, while a
     * corner turned into a smooth curve is a drawing that changed under the user. This file's stated
     * bias — *"snapping the user did not want is far worse than snapping they have to ask for
     * twice"* — decides it.
     *
     * Still the first number to change if the feature is too shy on a real hand, and M7's lesson
     * stands: the table above is synthetic, and only the tablet can say what a person's arc scores.
     */
    private const val ARC_MAX_RESIDUAL = 0.05f

    /** How finely the candidate curve is flattened for the residual. Accuracy against one pass. */
    private const val ARC_SAMPLES = 24

    /**
     * Chord over path length, below which a gesture is not a line.
     *
     * Public because [ARC_MIN_SAGITTA] is derived from it and `ShapesTest` asserts the two agree —
     * a test that hard-coded 0.93 could not fail when this moved, which is the one thing the
     * assertion exists to do. Same reason [DEFAULT_CONFIDENCE] is public.
     */
    public const val LINE_MIN_STRAIGHTNESS: Float = 0.93f

    /** Where a line's confidence ramp reaches 1.0. See [openCandidate] for why it is not 1.0. */
    private const val LINE_CERTAIN = 0.96f

    /** Two degrees. Below this a tilt is a wobble, not a decision. See [straightened]. */
    private const val STRAIGHTEN_RADIANS = 0.035f

    /**
     * Below this, a gesture is a tap or a dot and there is nothing to recognise.
     *
     * ⚠️ **This and the caller's own travel floor are the pair that decide how small a shape may
     * be** — nothing else here has a size in it, because every other test is a ratio. Keep them
     * together: `ShapeSnapDetector.DEFAULT_MIN_TRAVEL_PT` refuses the *gesture* and this refuses
     * the *classification*, and a gap between them is a size at which the feature half-works.
     */
    private const val MIN_PATH_LENGTH_PT = 9f

    /**
     * Fewest samples worth classifying.
     *
     * ⚠️ **Not a size limit, though it was acting as one.** Sampling is uniform in arc length, so
     * this multiplied by the caller's spacing *is* a length — at M8b's 6 pt spacing it demanded
     * 30 pt of path and refused every short line outright, which is the bug
     * `docs/M8-SMALL-SHAPES.md` is about. Four is what the arithmetic below genuinely needs: a hull
     * for [OrientedBounds.minimumArea], and enough steps that a bow is distinguishable from a
     * straight run. The size floor is [MIN_PATH_LENGTH_PT]'s job and it is stated in points, where
     * it can be reasoned about.
     */
    private const val MIN_POINTS = 4

    /**
     * The bar a candidate must clear to replace the user's ink.
     *
     * Deliberately high. Snapping something that was meant to stay freehand costs an undo and the
     * user's trust in the feature; refusing to snap something that was meant to be a rectangle
     * costs them holding still for another moment.
     */
    public const val DEFAULT_CONFIDENCE: Float = 0.55f
}

/**
 * What a held gesture would become.
 *
 * [from] and [to] carry the endpoints because a [ShapeKind.LINE] is placed by them and not by its
 * box — see [ShapeEdit.placeSegment] for why a line that is exactly horizontal is the case that
 * forces the distinction.
 *
 * @param box the axis-aligned bounds. Kept for callers that want a quick page-space extent; it is
 *   **not** what a closed shape is placed by any more.
 * @param oriented the minimum-area box, present for a closed candidate and null for a line. This is
 *   what [placement] uses, and it is why a rectangle drawn at an angle now stays at that angle.
 */
public data class SnapCandidate(
    val kind: ShapeKind,
    val box: PageRect,
    val from: PagePoint,
    val to: PagePoint,
    val confidence: Float,
    val oriented: OrientedBox? = null,
    /**
     * ⭐ The apex of a recognised **curve**, in page space, or null for every other candidate.
     *
     * Page space here and local space in [ShapeRecord.bend], because the recogniser only ever sees
     * page coordinates — the shape it is proposing does not exist yet, so its own space does not
     * either. [bendLocal] is the conversion, and it takes the placement rather than guessing.
     */
    val bend: PagePoint? = null,
) {

    /**
     * Where this candidate's shape sits, ready for [ShapeEdit.create].
     *
     * ⭐ [grid] rounds the finished placement onto the paper's lattice — see [SnapGrid.snapped] for
     * what that means per kind. It is applied **after** the placement rather than to the gesture's
     * own points, because a shape is placed from its oriented box and its endpoints, not from the
     * samples: snapping the samples first would move the very points the box was measured from and
     * change which shape this was.
     */
    public fun placement(
        bandPt: Float = 1f,
        asKind: ShapeKind = kind,
        grid: SnapGrid = SnapGrid.None,
    ): ShapePlacement {
        val placed = if (asKind == ShapeKind.LINE || asKind == ShapeKind.ARROW) {
            ShapeEdit.placeSegment(from.x, from.y, to.x, to.y, bandPt, asKind)
        } else {
            oriented?.let { ShapeEdit.placeOriented(asKind, it) } ?: ShapeEdit.place(asKind, box)
        }
        return grid.snapped(placed)
    }

    /**
     * [bend] in the space of [placement] — what goes in the record.
     *
     * Null when this is not a curve, and also when the placement is singular, which cannot happen
     * for a segment placed by two distinct endpoints but is not worth an exception if it ever does.
     */
    public fun bendLocal(placement: ShapePlacement): PagePoint? = bend?.let { placement.transform.inverse()?.map(it) }
}
