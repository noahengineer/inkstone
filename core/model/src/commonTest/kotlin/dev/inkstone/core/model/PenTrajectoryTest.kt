package dev.inkstone.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * docs/HANDWRITING-SYNTHESIS.md §4.1 — [PenTrajectory]'s rules are `androidx.ink`'s, and
 * `MutableStrokeInputBatch.add` throws on every one of them. These tests are the reason a generated
 * stroke can be refused in `:core` instead of throwing inside the editor's lock.
 */
class PenTrajectoryTest {

    @Test
    fun `a valid two stroke trajectory is accepted`() {
        val trajectory = assertNotNull(twoStrokes())
        assertEquals(5, trajectory.pointCount)
        assertEquals(2, trajectory.strokeCount)
        assertEquals(0, trajectory.strokeStart(0))
        assertEquals(3, trajectory.strokeStart(1))
        assertEquals(30, trajectory.durationMs)
        assertEquals(PageRect(0f, 0f, 4f, 2f), trajectory.bounds())
    }

    @Test
    fun `each androidx ink rule is refused by name`() {
        val x = floatArrayOf(0f, 1f, 2f)
        val y = floatArrayOf(0f, 1f, 2f)
        val t = intArrayOf(0, 5, 10)
        val ends = intArrayOf(3)
        fun problem(
            x: FloatArray = floatArrayOf(0f, 1f, 2f),
            y: FloatArray = floatArrayOf(0f, 1f, 2f),
            t: IntArray = intArrayOf(0, 5, 10),
            pressure: FloatArray? = null,
            tilt: FloatArray? = null,
            ends: IntArray = intArrayOf(3),
        ) = PenTrajectory.problemWith(x, y, t, pressure, tilt, ends)

        assertNull(problem(x, y, t, ends = ends))
        assertEquals(TrajectoryProblem.EMPTY, problem(FloatArray(0), FloatArray(0), IntArray(0), ends = IntArray(0)))
        assertEquals(TrajectoryProblem.LENGTHS_DIFFER, problem(y = floatArrayOf(0f, 1f)))
        assertEquals(TrajectoryProblem.BAD_STROKE_ENDS, problem(ends = intArrayOf(2)))
        assertEquals(TrajectoryProblem.BAD_STROKE_ENDS, problem(ends = intArrayOf(2, 2, 3)))
        assertEquals(TrajectoryProblem.NOT_FINITE, problem(x = floatArrayOf(0f, Float.NaN, 2f)))
        assertEquals(TrajectoryProblem.TIME_NEGATIVE, problem(t = intArrayOf(-1, 5, 10)))
        assertEquals(TrajectoryProblem.TIME_DECREASES, problem(t = intArrayOf(0, 10, 5)))
        assertEquals(
            TrajectoryProblem.REPEATED_SAMPLE,
            problem(x = floatArrayOf(0f, 0f, 2f), y = floatArrayOf(0f, 0f, 2f), t = intArrayOf(0, 0, 10)),
        )
        assertEquals(TrajectoryProblem.PRESSURE_OUT_OF_RANGE, problem(pressure = floatArrayOf(0f, 1.5f, 1f)))
        assertEquals(TrajectoryProblem.TILT_OUT_OF_RANGE, problem(tilt = floatArrayOf(0f, 2f, 0f)))
    }

    @Test
    fun `the same position at the same time is allowed across a pen lift`() {
        // androidx.ink validates one batch — one stroke — at a time, so a new stroke beginning
        // exactly where and when the last one ended is two inputs that never meet.
        val trajectory = PenTrajectory.of(
            x = floatArrayOf(0f, 1f, 1f),
            y = floatArrayOf(0f, 1f, 1f),
            tMs = intArrayOf(0, 5, 5),
            pressure = null,
            tilt = null,
            strokeEnds = intArrayOf(2, 3),
        )
        assertNotNull(trajectory)
    }

    @Test
    fun `the builder drops a repeated sample instead of refusing the trajectory`() {
        // Quantisation makes these: two genuine samples that round to the same position and
        // millisecond. Dropping the repeat loses nothing anybody could see.
        val builder = PenTrajectory.Builder(4, hasPressure = true, hasTilt = false)
        builder.add(0f, 0f, 0, pressure = 0.5f)
        builder.add(0f, 0f, 0, pressure = 0.6f)
        builder.add(1f, 1f, 5, pressure = 0.7f)
        builder.endStroke()
        val trajectory = assertNotNull(builder.build())

        assertEquals(2, trajectory.pointCount)
        assertEquals(listOf(0.5f, 0.7f), trajectory.pressure?.toList())
    }

    @Test
    fun `a subset of strokes keeps its clock and its order`() {
        val trajectory = assertNotNull(twoStrokes())
        val second = assertNotNull(trajectory.strokes(listOf(1)))

        assertEquals(1, second.strokeCount)
        assertEquals(listOf(20, 30), second.tMs.toList())
        assertNull(trajectory.strokes(emptyList()))
        assertNull(trajectory.strokes(listOf(2)))
    }

    @Test
    fun `a transform moves every sample and nothing else`() {
        val trajectory = assertNotNull(twoStrokes())
        val moved = assertNotNull(trajectory.transformed(Matrix3.translate(10f, 20f)))

        assertEquals(trajectory.x.map { it + 10f }, moved.x.toList())
        assertEquals(trajectory.y.map { it + 20f }, moved.y.toList())
        assertEquals(trajectory.tMs.toList(), moved.tMs.toList())
        assertEquals(trajectory.pressure?.toList(), moved.pressure?.toList())
    }

    private fun twoStrokes(): PenTrajectory? = PenTrajectory.of(
        x = floatArrayOf(0f, 1f, 2f, 3f, 4f),
        y = floatArrayOf(0f, 2f, 0f, 1f, 1f),
        tMs = intArrayOf(0, 5, 10, 20, 30),
        pressure = floatArrayOf(0.1f, 0.5f, 0.2f, 0.4f, 0.3f),
        tilt = null,
        strokeEnds = intArrayOf(3, 5),
    )
}
