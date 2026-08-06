package dev.inkstone.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** UUIDv7 — the shape, and the monotonicity a burst of strokes depends on. */
class IdFactoryTest {

    private class FixedClock(var millis: Long) : Clock {
        override fun nowEpochMillis(): Long = millis
    }

    @Test
    fun `an id is a v7 uuid with the right variant`() {
        val id = IdFactory(FixedClock(1_770_000_000_000), Random(1)).newUuid7()

        assertEquals(36, id.length)
        assertEquals(listOf(8, 4, 4, 4, 12), id.split('-').map { it.length })
        assertEquals('7', id[14], "version nibble")
        assertTrue(id[19] in "89ab", "variant nibble was ${id[19]}")
        assertTrue(id.all { it == '-' || it in "0123456789abcdef" })
    }

    @Test
    fun `ids minted in the same millisecond still sort in creation order`() {
        // The reason `rand_a` is a counter and not random: strokes arrive in bursts
        // faster than the clock ticks, and ids that sort arbitrarily inside a
        // millisecond make the bundle's entry order meaningless between runs.
        val factory = IdFactory(FixedClock(1_770_000_000_000), Random(7))
        val ids = List(BURST) { factory.newUuid7() }

        assertEquals(ids.sorted(), ids, "ids minted in one millisecond did not sort in order")
        assertEquals(ids.toSet().size, ids.size, "ids collided")
    }

    @Test
    fun `a later millisecond sorts after an earlier one`() {
        val clock = FixedClock(1_770_000_000_000)
        val factory = IdFactory(clock, Random(7))
        val early = factory.newUuid7()
        clock.millis += 1
        val late = factory.newUuid7()

        assertTrue(early < late)
    }

    @Test
    fun `an unset device clock does not crash the edit path`() {
        // A wrong-but-ordered id is recoverable; an exception where a stroke is being
        // recorded is not.
        val id = IdFactory(FixedClock(-1), Random(3)).newUuid7()
        assertEquals("00000000-0000-7000", id.take(18))
    }

    private companion object {
        const val BURST = 500
    }
}
