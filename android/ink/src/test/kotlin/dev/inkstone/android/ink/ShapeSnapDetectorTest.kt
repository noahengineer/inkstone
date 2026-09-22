package dev.inkstone.android.ink

import dev.inkstone.core.model.ShapeKind
import dev.inkstone.core.model.SnapCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * ⭐ Draw-and-hold at the sizes people actually draw at — the JVM half of ROADMAP M8b.
 *
 * ## Why this is a unit test and not an instrumented one
 *
 * [ShapeSnapDetector] is plain arithmetic over two float arrays: it never touches `androidx.ink`,
 * a `MotionEvent` or a `Canvas`. That is deliberate (see its KDoc), and it is what lets the *size*
 * question — the one nobody can ask a tablet a hundred times in a row — be answered by measurement
 * rather than by holding a pen.
 *
 * ⚠️ It cannot answer the other half. Whether a 24 pt line *feels* right, and whether the hold
 * fires when a real digitiser stops reporting between events, are tablet questions (SPEC §4.8.7).
 *
 * ## What the fixtures are, and what they are not
 *
 * Every gesture below is sampled at 240 Hz with ±0.75 pt of independent per-sample jitter, which
 * is several times what an S Pen produces at fit width and is chosen to be pessimistic. M7's
 * lesson stands regardless — *a synthetic fixture is the worst possible witness for a geometric
 * heuristic* — so these assert **floors that used to be far higher**, not that the classifier is
 * right about a person's handwriting.
 */
class ShapeSnapDetectorTest {

    /**
     * Per-sample jitter, in page points, peak to peak.
     *
     * [SHAKY] is deliberately several times an S Pen's and is what the *robustness* tests use — it
     * is the number that stops anyone making the sampling finer (see
     * `ShapeSnapDetector.DEFAULT_SAMPLE_SPACING_PT`). [STEADY] is about what a stylus really
     * produces at fit width, and is what the *size* tests use, because a floor measured under
     * four times the real noise is a floor nobody could reach.
     */
    private var jitter = SHAKY

    /**
     * Feed a gesture, then hold on its last point until the dwell fires.
     *
     * The hold is a tremor on one spot rather than a repeated identical sample, because that is
     * what half a second at 240 Hz actually looks like — and it is the input that used to make
     * holding *longer* give a worse answer.
     *
     * ⚠️ **A second and a half of it, not the 550 ms the dwell asks for.** The dwell window is
     * anchored where the pen last *left* it, which can be a whole radius behind where it settles,
     * so a hand that settles near the edge crosses it once and restarts the clock — costing one
     * more dwell period. A fixture holding for exactly one period therefore fails on the seeds
     * where that happens and reports it as *"the shape was not recognised"*, which is a fixture
     * measuring the wrong thing. A person holds until something happens.
     */
    private fun snap(points: List<Pair<Float, Float>>, holdSamples: Int = 400): SnapCandidate? {
        val detector = ShapeSnapDetector()
        var time = 1_000L
        detector.begin(points[0].first, points[0].second, time)
        for (index in 1 until points.size) {
            time += 4
            detector.sample(points[index].first, points[index].second, time)
        }
        val tremor = Random(11)
        var fired = false
        val last = points.last()
        repeat(holdSamples) {
            time += 4
            val x = last.first + (tremor.nextFloat() - 0.5f) * 0.6f
            val y = last.second + (tremor.nextFloat() - 0.5f) * 0.6f
            if (detector.sample(x, y, time) == ShapeSnapDetector.FIRE_NOW) fired = true
        }
        if (!fired) return null
        return detector.classify()
    }

    private fun line(lengthPt: Float, seed: Int): List<Pair<Float, Float>> {
        val rng = Random(seed)
        val samples = (lengthPt * 4).toInt().coerceAtLeast(20)
        return (0..samples).map { index ->
            val along = index / samples.toFloat()
            Pair(
                100f + lengthPt * along + (rng.nextFloat() - 0.5f) * jitter,
                100f + (rng.nextFloat() - 0.5f) * jitter,
            )
        }
    }

    private fun circle(diameterPt: Float, seed: Int): List<Pair<Float, Float>> {
        val rng = Random(seed)
        val samples = (diameterPt * 6).toInt().coerceAtLeast(40)
        return (0..samples).map { index ->
            val angle = 2 * PI * index / samples
            Pair(
                (100 + diameterPt / 2 * cos(angle) + (rng.nextFloat() - 0.5) * jitter).toFloat(),
                (100 + diameterPt / 2 * sin(angle) + (rng.nextFloat() - 0.5) * jitter).toFloat(),
            )
        }
    }

    private fun square(sidePt: Float, seed: Int): List<Pair<Float, Float>> {
        val rng = Random(seed)
        val perSide = (sidePt * 2).toInt().coerceAtLeast(12)
        val corners = listOf(0f to 0f, sidePt to 0f, sidePt to sidePt, 0f to sidePt, 0f to 0f)
        val out = mutableListOf<Pair<Float, Float>>()
        for (side in 0 until 4) {
            val (ax, ay) = corners[side]
            val (bx, by) = corners[side + 1]
            for (index in 0 until perSide) {
                val along = index / perSide.toFloat()
                out += Pair(
                    100f + ax + (bx - ax) * along + (rng.nextFloat() - 0.5f) * jitter,
                    100f + ay + (by - ay) * along + (rng.nextFloat() - 0.5f) * jitter,
                )
            }
        }
        out += Pair(100f + (rng.nextFloat() - 0.5f) * jitter, 100f + (rng.nextFloat() - 0.5f) * jitter)
        return out
    }

    /**
     * Assert [kind] comes back from at least [atLeast] of twelve seeded gestures.
     *
     * ⚠️ **Not always all twelve, and the exceptions are the point.** At the very bottom of the
     * size range a seed or two is refused, which is the *correct* behaviour — refusing keeps the
     * user's ink, and this file's standing bias is that a snap nobody asked for costs more than one
     * they have to ask for twice. Demanding 12/12 at 12 pt would push someone to widen a confidence
     * bar that protects every other size.
     */
    private fun seedsGiving(
        kind: ShapeKind,
        atLeast: Int = 12,
        noise: Float = jitter,
        gesture: (Int) -> List<Pair<Float, Float>>,
    ) {
        jitter = noise
        val results = try {
            (0 until 12).map { snap(gesture(it)) }
        } finally {
            jitter = SHAKY
        }
        val hits = results.count { it?.kind == kind }
        assertTrue("expected $kind from >= $atLeast of 12 seeds, got ${results.map { it?.kind }}", hits >= atLeast)
    }

    /**
     * ⭐ **A small line is the size the feature used to lose**, and by a long way.
     *
     * The gesture was sampled only when the pen *left the dwell window*, so its points were
     * `DEFAULT_HOLD_RADIUS_PT` apart however small the shape was — and `ShapeSnap.MIN_POINTS` then
     * needed five of those before there was anything to classify at all. Measured over twelve
     * seeds at the old spacing a 24 pt line snapped **0 times out of 12** and a 30 pt line once;
     * the report was *"if you try make a shape like a line or circle or whatever it just does not
     * work"*.
     *
     * A closed shape hid it, which is why it survived a milestone: what gets sampled there is the
     * *perimeter*, so an 18 pt circle already had three times the points a 24 pt line did.
     */
    @Test
    fun `a small line snaps`() {
        seedsGiving(ShapeKind.LINE, atLeast = 10, noise = STEADY) { line(12f, it) }
        seedsGiving(ShapeKind.LINE, noise = STEADY) { line(15f, it) }
    }

    @Test
    fun `a small circle snaps`() {
        seedsGiving(ShapeKind.ELLIPSE, atLeast = 10, noise = STEADY) { circle(12f, it) }
        seedsGiving(ShapeKind.ELLIPSE, noise = STEADY) { circle(15f, it) }
    }

    @Test
    fun `a small square snaps`() {
        seedsGiving(ShapeKind.RECTANGLE, noise = STEADY) { square(12f, it) }
    }

    @Test
    fun `an ordinary line still snaps`() {
        seedsGiving(ShapeKind.LINE) { line(120f, it) }
    }

    /**
     * ⚠️ **The guard on the sampling spacing.** A line is scored by `chord / pathLength`, and a
     * summed path length grows with the digitiser's jitter as the spacing shrinks — at 1.5 pt this
     * fixture scores 0.79 against a bar of 0.93 and a shaky hand stops being able to draw a line at
     * *any* size. Anyone tempted to halve `DEFAULT_SAMPLE_SPACING_PT` to reach smaller shapes will
     * fail here, which is the intent: the size floor is `DEFAULT_MIN_TRAVEL_PT`'s job.
     */
    @Test
    fun `a shaky hand can still draw a line`() {
        seedsGiving(ShapeKind.LINE, noise = SHAKY) { line(60f, it) }
    }

    @Test
    fun `ordinary closed shapes still snap`() {
        seedsGiving(ShapeKind.ELLIPSE) { circle(24f, it) }
        seedsGiving(ShapeKind.RECTANGLE) { square(24f, it) }
    }

    /**
     * Capacity times spacing is the longest path the buffer holds, and halving the spacing without
     * raising the capacity would have silently truncated a large shape — see
     * `ShapeSnapDetector.DEFAULT_CAPACITY`. A 260 pt circle is 817 pt of perimeter.
     */
    @Test
    fun `a large circle survives the buffer`() {
        seedsGiving(ShapeKind.ELLIPSE) { circle(260f, it) }
    }

    /**
     * ⭐ **A snapped line ends where the pen stopped.**
     *
     * `SnapCandidate.from`/`to` are the classified run's first and last points, and that run used
     * to end at the last dwell-window exit — up to a hold radius short of the tip, plus that one
     * sample's own jitter. So the line came out visibly shorter than the one drawn, which is the
     * other half of *"a line that does not have the proper points"*. `classify` appends the tip.
     */
    @Test
    fun `a snapped line keeps its length`() {
        for (lengthPt in listOf(30f, 60f, 120f, 240f)) {
            val candidate = requireNotNull(snap(line(lengthPt, 5))) { "no snap at $lengthPt pt" }
            val snapped = hypot(candidate.to.x - candidate.from.x, candidate.to.y - candidate.from.y)
            assertTrue("a $lengthPt pt line came out $snapped pt", abs(snapped - lengthPt) <= 2f)
        }
    }

    /**
     * ⚠️ **Holding longer must not change the answer**, which is what the dwell's own samples are
     * kept out of the gesture for: they pile onto one spot, inflate `pathLength`, and `ShapeSnap`
     * divides by that twice. A scratch tail `classify` discards is how the finer sampling is had
     * without paying for it.
     */
    @Test
    fun `holding longer does not change the shape`() {
        for (seed in 0 until 8) {
            val brief = snap(line(60f, seed), holdSamples = 400)
            val long = snap(line(60f, seed), holdSamples = 2_400)
            assertEquals("seed $seed", ShapeKind.LINE, brief?.kind)
            assertEquals("seed $seed", brief?.kind, long?.kind)
        }
    }

    /** A pen resting on the page is not a gesture, however long it rests. */
    @Test
    fun `a resting pen never fires`() {
        val rng = Random(3)
        val still = (0..400).map {
            Pair(100f + (rng.nextFloat() - 0.5f) * 2f, 100f + (rng.nextFloat() - 0.5f) * 2f)
        }
        assertNull(snap(still, holdSamples = 900))
    }

    /**
     * ⚠️ **The floor must not depend on how often the digitiser reported.**
     *
     * `travel` used to accrue only when a sample was *appended*, which loses whatever the pen
     * covered after the last point worth recording — an under-estimate of up to one spacing at
     * every dwell-window exit. Every fixture here passed it, because a fixture's samples land on
     * the spacing by construction; **a 12 pt line drawn through the app over `adb` did not snap**,
     * and that walk is the only reason it was found. Path length is measured per sample now.
     *
     * The rates below straddle the sampling spacing deliberately — 0.6 pt is a real stylus, 4 pt is
     * coarser than the spacing — so the un-appended remainder is a different fraction of the path
     * in each. ⚠️ Coarser still is *not* asserted and should not be: a whole 12 pt gesture in five
     * events is a 20 ms flick, `MIN_POINTS` refuses it for want of evidence, and that is the
     * classifier working rather than a floor misbehaving.
     */
    @Test
    fun `the size floor does not depend on the sample rate`() {
        for (stepPt in listOf(0.6f, 1.2f, 2f, 4f)) {
            val steps = (12f / stepPt).toInt()
            val points = (0..steps).map { Pair(100f + stepPt * it, 100f) }
            assertEquals("at $stepPt pt per sample", ShapeKind.LINE, snap(points)?.kind)
        }
    }

    /** A dot is below every floor there is, and must stay ink. */
    @Test
    fun `a dot never fires`() {
        for (seed in 0 until 6) assertNull("seed $seed", snap(line(5f, seed)))
    }

    private companion object {
        const val SHAKY = 1.5f
        const val STEADY = 0.5f
    }
}
