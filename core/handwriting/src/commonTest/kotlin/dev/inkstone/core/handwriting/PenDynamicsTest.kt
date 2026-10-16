package dev.inkstone.core.handwriting

import dev.inkstone.core.handwriting.neural.Alignment
import dev.inkstone.core.handwriting.neural.ModelInk
import dev.inkstone.core.model.PenTrajectory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §7.1's per-user pressure, tilt and timing, §7.2's resampling of real ink, and §5.4 v2's Viterbi pass. */
class PenDynamicsTest {

    /** A loop of [n] points, x-heights, time-uniform, pressing lightly at both ends of the stroke. */
    private fun word(n: Int, msPerPoint: Int, shift: Float = 0f): PenTrajectory {
        val b = PenTrajectory.Builder(n, hasPressure = true, hasTilt = true)
        for (i in 0 until n) {
            val a = 2 * PI * i / n
            val ends = minOf(i, n - 1 - i).toFloat() / n
            b.add(
                shift + (0.5 + 0.4 * cos(a)).toFloat() + i * 0.01f,
                (0.5 + 0.4 * sin(a)).toFloat(),
                i * msPerPoint,
                (
                    0.3f +
                        2f * ends
                    ).coerceAtMost(0.9f),
                0.6f,
            )
        }
        return assertNotNull(b.build())
    }

    @Test
    fun theFitLearnsThatThisHandPressesLighterAtTheEndsOfAStroke() {
        val samples = (0 until 6).map { word(80, 8, it * 0.1f) }
        val fitted = PenDynamics.fit(samples, penUpMs = 150)
        val n = 60
        val xs = FloatArray(n) { (0.5 + 0.4 * cos(2 * PI * it / n)).toFloat() + it * 0.01f }
        val ys = FloatArray(n) { (0.5 + 0.4 * sin(2 * PI * it / n)).toFloat() }
        val out = assertNotNull(fitted.apply(xs, ys, intArrayOf(n)))
        val p = assertNotNull(out.pressure)
        assertTrue(
            p[n / 2] > p[0] + 0.1f && p[n / 2] > p[n - 1] + 0.1f,
            "middle ${p[n / 2]} vs ends ${p[0]}, ${p[n - 1]}",
        )
        assertEquals(0.6f, assertNotNull(out.tilt)[0], 1e-6f)
    }

    @Test
    fun timingFollowsTheHandsSpeedAndPenUps() {
        val samples = (0 until 4).map { word(80, 8) }
        val fitted = PenDynamics.fit(samples, penUpMs = 150)
        val xs = floatArrayOf(0f, 0.1f, 0.2f, 0.5f, 0.6f)
        val ys = floatArrayOf(0f, 0f, 0f, 0.5f, 0.5f)
        val out = assertNotNull(fitted.apply(xs, ys, intArrayOf(3, 5)))
        assertEquals(2, out.strokeCount)
        val t = out.tMs
        assertTrue(t[1] > t[0] && t[2] > t[1], "strictly increasing in a stroke")
        assertTrue(t[3] - t[2] >= 150, "the hand's own pen-up between strokes: ${t[3] - t[2]}")
    }

    @Test
    fun anUnmeasuredHandHasNoPressureOrTiltAndStillValidTime() {
        val out =
            assertNotNull(
                PenDynamics.Unmeasured.apply(floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, 1f), intArrayOf(3)),
            )
        assertNull(out.pressure)
        assertNull(out.tilt)
        assertTrue(out.tMs[1] > out.tMs[0], "a repeated position gets its own instant, not a repeated sample")
    }

    @Test
    fun realInkIsResampledToTheModelsDensityAndEveryPointMapsBack() {
        val ink = word(240, 4)
        val inputs = assertNotNull(ModelInk.inputs(ink, 3, 14f, floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)))
        assertEquals(42, inputs.points)
        val rows = List(inputs.points) { floatArrayOf(it.toFloat()) }
        val spread = inputs.spread(rows, ink)
        assertEquals(ink.pointCount, spread.size)
        val mapped = spread.map { it[0].toInt() }
        assertEquals(0, mapped.first())
        assertEquals(inputs.points - 1, mapped.last())
        assertTrue(mapped.zipWithNext().all { (a, b) -> b >= a }, "in order")
    }

    @Test
    fun theViterbiPassIsMonotonicAndCoversEveryLetter() {
        // Attention that clearly moves a → b → c over nine points, with one noisy point that looks ahead to c,\n        // which a monotonic path cannot reach yet and so leaves on a.
        val phi = listOf(
            floatArrayOf(.9f, .05f, .05f), floatArrayOf(.8f, .1f, .1f), floatArrayOf(.15f, .05f, .8f),
            floatArrayOf(.2f, .7f, .1f), floatArrayOf(.1f, .8f, .1f), floatArrayOf(.1f, .7f, .2f),
            floatArrayOf(.1f, .2f, .7f), floatArrayOf(.05f, .05f, .9f), floatArrayOf(.05f, .05f, .9f),
        )
        val letters = Alignment("abc", phi).letters()
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 2, 2, 2), letters.toList())
    }
}
