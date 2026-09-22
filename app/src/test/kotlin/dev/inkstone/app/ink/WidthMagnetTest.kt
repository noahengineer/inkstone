package dev.inkstone.app.ink

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ROADMAP M10 — the slider's pull onto the saved widths marked on its track. */
class WidthMagnetTest {

    private val ladder = EditorPrefs.DEFAULT_WIDTH_SLOTS

    @Test
    fun `a value near a saved size lands on it`() {
        assertEquals(3f, WidthMagnet.pull(3.2f, ladder))
        assertEquals(16f, WidthMagnet.pull(15f, ladder))
    }

    @Test
    fun `a value between two saved sizes is left alone`() {
        assertNull(WidthMagnet.pull(6f, ladder))
        assertNull(WidthMagnet.pull(2.5f, ladder))
    }

    @Test
    fun `the ballpoint default stays reachable, which a fixed window would have taken away`() {
        // 2.5 dp sits midway between the 2 and 3 slots. A tolerance wide enough to give 16 dp a
        // usable pull would have swallowed it, and it is the width one of the four shipped brushes
        // starts at -- so the dock could not have set its own default.
        assertNull(WidthMagnet.pull(2.5f, ladder))
    }

    @Test
    fun `the pull scales with the gap so it feels the same at both ends of the ladder`() {
        // 1 dp apart at the bottom, 8 apart at the top. A single window is wrong at one end or the
        // other; this asserts the proportional behaviour rather than either constant.
        assertEquals(2f, WidthMagnet.pull(2f + 0.25f, ladder))
        assertNull(WidthMagnet.pull(2f + 0.45f, ladder))
        assertEquals(16f, WidthMagnet.pull(16f - 2f, ladder))
        assertNull(WidthMagnet.pull(16f - 3f, ladder))
    }

    @Test
    fun `at least a third of every interval is still reachable`() {
        // The property that stops this quietly becoming a quantiser. Sampled finely across the
        // whole slider range, most of it must pass through untouched.
        val samples = (0..2000).map { MIN_WIDTH_DP + it * (EditorPrefs.DEFAULT_MAX_WIDTH_DP - MIN_WIDTH_DP) / 2000f }
        val free = samples.count { WidthMagnet.pull(it, ladder) == null }
        assertTrue(free > samples.size / 2, "only $free of ${samples.size} widths were reachable")
    }

    @Test
    fun `each interval between two saved sizes keeps a free middle`() {
        ladder.zipWithNext { low, high ->
            val middle = (low + high) / 2f
            assertNull(WidthMagnet.pull(middle, ladder), "the midpoint of $low..$high was swallowed")
        }
    }

    @Test
    fun `a lone saved size has a pull without locking the slider`() {
        assertEquals(4f, WidthMagnet.pull(4.4f, listOf(4f)))
        assertNull(WidthMagnet.pull(12f, listOf(4f)))
    }

    @Test
    fun `no saved sizes means no pull rather than a crash`() {
        assertNull(WidthMagnet.pull(3f, emptyList()))
    }

    @Test
    fun `every value it does move lands exactly on a saved size`() {
        val samples = (0..500).map { MIN_WIDTH_DP + it * (EditorPrefs.DEFAULT_MAX_WIDTH_DP - MIN_WIDTH_DP) / 500f }
        samples.forEach { value ->
            val pulled = WidthMagnet.pull(value, ladder) ?: return@forEach
            assertTrue(ladder.any { abs(it - pulled) < 1e-6f }, "$value was pulled to $pulled")
        }
    }
}
