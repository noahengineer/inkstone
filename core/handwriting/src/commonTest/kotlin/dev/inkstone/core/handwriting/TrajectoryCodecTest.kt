package dev.inkstone.core.handwriting

import dev.inkstone.core.model.PenTrajectory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/HANDWRITING-SYNTHESIS.md §4.2 — the trajectory's bytes, and what is lost in them. */
class TrajectoryCodecTest {

    @Test
    fun `a trajectory survives within its stated quantisation`() {
        val original = loop(points = 200, withPressure = true, withTilt = true)
        val decoded = assertNotNull(TrajectoryCodec.decode(TrajectoryCodec.encode(original)))

        assertEquals(original.strokeEnds.toList(), decoded.strokeEnds.toList())
        assertEquals(original.tMs.toList(), decoded.tMs.toList())
        for (i in 0 until original.pointCount) {
            assertTrue(abs(original.x[i] - decoded.x[i]) <= HALF_POSITION_STEP, "x[$i]")
            assertTrue(abs(original.y[i] - decoded.y[i]) <= HALF_POSITION_STEP, "y[$i]")
            assertTrue(abs(original.pressure!![i] - decoded.pressure!![i]) <= HALF_CHANNEL_STEP, "pressure[$i]")
            assertTrue(abs(original.tilt!![i] - decoded.tilt!![i]) <= HALF_CHANNEL_STEP * (PI / 2), "tilt[$i]")
        }
    }

    @Test
    fun `encoding is stable once quantised`() {
        val once = TrajectoryCodec.encode(loop(points = 50, withPressure = true, withTilt = false))
        val twice = TrajectoryCodec.encode(assertNotNull(TrajectoryCodec.decode(once)))
        assertTrue(once.contentEquals(twice), "re-encoding a decoded trajectory must not drift")
    }

    @Test
    fun `a missing channel stays missing`() {
        val decoded = assertNotNull(TrajectoryCodec.decode(TrajectoryCodec.encode(loop(20, false, false))))
        assertNull(decoded.pressure)
        assertNull(decoded.tilt)
    }

    @Test
    fun `a smooth pen costs a few bytes a point`() {
        // §4.2 budgets about 6 bytes a point against 16 for raw floats. Not a precise claim, but a
        // codec that stopped delta-coding would blow through it.
        val trajectory = loop(points = 400, withPressure = true, withTilt = false)
        val bytes = TrajectoryCodec.encode(trajectory)
        assertTrue(bytes.size < trajectory.pointCount * MAX_BYTES_PER_POINT, "${bytes.size} bytes")
    }

    @Test
    fun `damage reads as nothing rather than as a wrong trajectory`() {
        val bytes = TrajectoryCodec.encode(loop(points = 30, withPressure = true, withTilt = false))
        assertNull(TrajectoryCodec.decode(bytes.copyOf(bytes.size - 3)), "a truncated tail")
        assertNull(TrajectoryCodec.decode(byteArrayOf(1, 2, 3)), "not ours")
        val newer = bytes.copyOf().also { it[4] = 99 }
        assertNull(TrajectoryCodec.decode(newer), "a later version is refused, not guessed at")
    }

    /** A pen going round a circle twice, as two strokes, at 240 Hz. */
    private fun loop(points: Int, withPressure: Boolean, withTilt: Boolean): PenTrajectory {
        val builder = PenTrajectory.Builder(points, withPressure, withTilt)
        for (i in 0 until points) {
            val angle = i * 2 * PI / (points / 2)
            builder.add(
                x = (100 + 20 * cos(angle)).toFloat(),
                y = (200 + 12 * sin(angle)).toFloat(),
                tMs = i * 4 + if (i >= points / 2) 120 else 0,
                pressure = (0.5 + 0.4 * sin(angle * 3)).toFloat(),
                tilt = (0.6 + 0.2 * cos(angle)).toFloat(),
            )
            if (i == points / 2 - 1) builder.endStroke()
        }
        return assertNotNull(builder.build())
    }

    private companion object {
        const val HALF_POSITION_STEP = 0.5f / TrajectoryCodec.POSITION_SCALE + 1e-4f
        const val HALF_CHANNEL_STEP = 0.5f / TrajectoryCodec.CHANNEL_STEPS + 1e-4f
        const val MAX_BYTES_PER_POINT = 8
    }
}
