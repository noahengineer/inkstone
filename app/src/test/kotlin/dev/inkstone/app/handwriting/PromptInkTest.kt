package dev.inkstone.app.handwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quick start's undo, redo and eraser (docs/HANDWRITING-SYNTHESIS.md §18.9). `PromptInk` holds
 * the rules, so they are tested here. The touch routing in front of it — which pointer is a pen, a
 * finger tap or an eraser — is `PenInput`'s, and is tested with real `MotionEvent`s by the
 * instrumented `QuickStartPenInputTest`.
 */
class PromptInkTest {

    /** A horizontal stroke at height [y], from x [from] to [to], starting at [t0] ms. */
    private fun stroke(y: Float, from: Float = 0f, to: Float = 20f, t0: Long = 0L): List<Sample> =
        (0..10).map { k -> Sample(from + (to - from) * k / 10f, y, t0 + k * 10L, 0.5f, 0.2f) }

    @Test
    fun undoTakesBackTheLastStrokeAndRedoPutsItBack() {
        val ink = PromptInk()
        ink.add(stroke(10f))
        ink.add(stroke(30f, t0 = 200))

        assertTrue(ink.undo())
        assertEquals(1, ink.visible.size)
        assertEquals(10f, ink.visible.single().first().y)
        assertTrue(ink.redo())
        assertEquals(2, ink.visible.size)
        assertFalse("nothing left to redo", ink.redo())
    }

    @Test
    fun aNewStrokeAfterAnUndoForgetsTheRedo() {
        val ink = PromptInk()
        ink.add(stroke(10f))
        ink.undo()
        ink.add(stroke(30f, t0 = 200))

        assertFalse(ink.canRedo)
        assertEquals(listOf(30f), ink.visible.map { it.first().y })
    }

    @Test
    fun theEraserTakesTheWholeStrokeItTouchesAndOnlyThat() {
        val ink = PromptInk()
        ink.add(stroke(10f))
        ink.add(stroke(40f, t0 = 200))

        // Crossing the first stroke at its middle, well clear of the second.
        assertTrue(ink.eraseAlong(10f, 0f, 10f, 20f, radius = 4f))
        ink.endErase()

        assertEquals(listOf(40f), ink.visible.map { it.first().y })
        assertEquals("the untouched stroke is whole: every sample, none cut", 11, ink.visible.single().size)
    }

    @Test
    fun aQuickSwipeCannotJumpOverAThinStroke() {
        val ink = PromptInk()
        ink.add(stroke(10f))
        // One move from far above to far below: no event lands near the stroke, but the path crosses it.
        ink.eraseAlong(10f, -100f, 10f, 100f, radius = 2f)
        ink.endErase()
        assertTrue(ink.isEmpty)
    }

    @Test
    fun oneEraserGestureIsOneUndo() {
        val ink = PromptInk()
        ink.add(stroke(10f))
        ink.add(stroke(30f, t0 = 200))
        ink.add(stroke(50f, t0 = 400))

        ink.eraseAlong(10f, 0f, 10f, 35f, radius = 2f)
        ink.eraseAlong(10f, 35f, 10f, 45f, radius = 2f)
        ink.endErase()
        assertEquals(1, ink.visible.size)

        ink.undo()
        assertEquals(
            "both strokes back, in the order they were drawn",
            listOf(10f, 30f, 50f),
            ink.visible.map {
                it.first().y
            },
        )
    }

    @Test
    fun anEraserThatTouchesNothingLeavesNothingToUndo() {
        val ink = PromptInk()
        ink.add(stroke(10f))
        assertFalse(ink.eraseAlong(10f, 80f, 15f, 90f, radius = 2f))
        ink.endErase()
        ink.undo()
        assertTrue("the one undo took the stroke, not an empty erase", ink.isEmpty)
    }

    @Test
    fun clearIsOneUndoAway() {
        val ink = PromptInk()
        ink.add(stroke(10f))
        ink.add(stroke(30f, t0 = 200))
        ink.clear()
        assertTrue(ink.isEmpty)
        assertNull(ink.trajectory())

        ink.undo()
        assertEquals(2, ink.visible.size)
    }

    @Test
    fun theTrajectoryIsTheShownStrokesOnAClockFromZero() {
        val ink = PromptInk()
        ink.add(stroke(10f, t0 = 1_000))
        ink.add(stroke(30f, t0 = 1_500))
        ink.add(stroke(50f, t0 = 2_000))
        ink.eraseAlong(10f, 25f, 10f, 35f, radius = 2f)
        ink.endErase()

        val written = ink.trajectory() ?: error("two strokes are shown")
        assertEquals(2, written.strokeCount)
        assertEquals("starts at zero, whenever the prompt was first touched", 0, written.tMs[0])
        assertEquals("the hidden stroke's time stays a pen-up", 1_000, written.tMs[written.strokeStart(1)])
        assertEquals(50f, written.y[written.strokeStart(1)])
    }

    @Test
    fun resetForgetsTheStrokesAndTheirHistory() {
        val ink = PromptInk()
        ink.add(stroke(10f))
        ink.reset()
        assertTrue(ink.isEmpty)
        assertFalse(ink.canUndo)
        assertFalse(ink.canRedo)
    }
}
