package dev.inkstone.android.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StrokeStarts]: each stroke keeps the start it had, however late ink reports it finished.
 *
 * ⚠️ The keys here are plain objects standing in for `InProgressStrokeId`, which is what
 * `InkSurfaceView` passes. That the surface puts at the down event and finds in `onStrokesFinished`
 * is what the emulator walk checks, through the ledger (docs/HANDWRITING-SYNTHESIS.md §16).
 */
class StrokeStartsTest {

    @Test
    fun `a stroke reported after the next one began keeps its own start`() {
        val starts = StrokeStarts<String>()
        val first = Any()
        val second = Any()
        // The order on the glass: first down, first up, second down. Then ink reports both.
        starts.put(first, atElapsed = 1_000L, isRuled = true, kind = "pen")
        starts.put(second, atElapsed = 1_400L, isRuled = false, kind = "highlighter")

        val a = starts.find(first)
        val b = starts.find(second)
        assertEquals(1_000L, starts.startedAt(a))
        assertTrue(starts.ruled(a))
        assertEquals("pen", starts.kind(a))
        assertEquals(1_400L, starts.startedAt(b))
        assertFalse(starts.ruled(b))
        assertEquals("highlighter", starts.kind(b))
    }

    @Test
    fun `a cleared stroke cannot be found again`() {
        val starts = StrokeStarts<String>()
        val id = Any()
        starts.put(id, atElapsed = 5L, isRuled = false, kind = "pen")

        starts.clear(starts.find(id))

        assertEquals(-1, starts.find(id))
    }

    @Test
    fun `a stroke never put is not found`() {
        assertEquals(-1, StrokeStarts<String>().find(Any()))
    }

    @Test
    fun `the oldest slot is reused and a cancelled stroke costs only its own entry`() {
        val starts = StrokeStarts<String>(capacity = 2)
        val cancelled = Any()
        val kept = Any()
        val newest = Any()
        starts.put(cancelled, atElapsed = 1L, isRuled = false, kind = "pen")
        starts.put(kept, atElapsed = 2L, isRuled = false, kind = "pen")
        starts.put(newest, atElapsed = 3L, isRuled = false, kind = "pen")

        assertEquals(-1, starts.find(cancelled))
        assertEquals(2L, starts.startedAt(starts.find(kept)))
        assertEquals(3L, starts.startedAt(starts.find(newest)))
    }
}
