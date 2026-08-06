package dev.inkstone.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SPEC §4.3 — the keys that stop a page vanishing when two devices insert offline. */
class FractionalKeyTest {

    private fun between(a: String?, b: String?) =
        FractionalKey.between(a?.let(::FractionalKey), b?.let(::FractionalKey))

    @Test
    fun `a key between two keys sorts strictly between them`() {
        val mid = between("A", "B")
        assertTrue(FractionalKey("A") < mid)
        assertTrue(mid < FractionalKey("B"))
    }

    @Test
    fun `consecutive digits still have room between them`() {
        // The interesting case: there is no single digit between '1' and '2', so the
        // key has to grow a character rather than fail.
        val mid = between("1", "2")
        assertTrue(FractionalKey("1") < mid && mid < FractionalKey("2"), "got $mid")
        assertTrue(mid.isWellFormed)
    }

    @Test
    fun `repeatedly inserting in the same place grows the key one character at a time`() {
        // The property that makes this usable: keys must not double in length per
        // insert, or reordering a notebook a few hundred times produces kilobyte keys.
        var low = FractionalKey("1")
        val high = FractionalKey("2")
        var longest = 0
        repeat(50) {
            low = FractionalKey.between(low, high)
            longest = maxOf(longest, low.value.length)
            assertTrue(low < high)
        }
        assertTrue(longest <= 52, "keys grew to $longest characters")
    }

    @Test
    fun `an unbounded insert on either side works`() {
        val first = between(null, null)
        val before = between(null, first.value)
        val after = between(first.value, null)

        assertTrue(before < first)
        assertTrue(first < after)
        assertTrue(before.isWellFormed && after.isWellFormed)
    }

    @Test
    fun `every generated key is well formed`() {
        // Well-formed means it can be used as a bound next time. A key ending in the
        // smallest digit cannot, which is why the algorithm must never emit one.
        val random = Random(20260806)
        var low: FractionalKey? = null
        var high: FractionalKey? = null
        repeat(300) {
            val key = FractionalKey.between(low, high)
            assertTrue(key.isWellFormed, "generated a malformed key: $key")
            if (random.nextBoolean()) low = key else high = key
            if (low != null && high != null && low >= high) {
                low = null
                high = null
            }
        }
    }

    @Test
    fun `a sequence is ordered — well formed and short`() {
        for (count in listOf(1, 2, 5, 60, 200)) {
            val keys = FractionalKey.sequence(count)
            assertEquals(count, keys.size)
            assertEquals(keys.sorted(), keys, "sequence($count) was not ordered")
            assertTrue(keys.all { it.isWellFormed }, "sequence($count) produced a malformed key")
            assertTrue(keys.all { it.value.length <= 2 }, "sequence($count) produced a long key")
        }
    }

    @Test
    fun `unusable bounds are refused rather than silently reordering pages`() {
        assertFailsWith<IllegalArgumentException> { between("B", "A") }
        assertFailsWith<IllegalArgumentException> { between("A", "A") }
        // A key ending in the smallest digit has nothing below it, so it cannot be an
        // upper bound. This is why the invariant exists.
        assertFailsWith<IllegalArgumentException> { between(null, "A0") }
        assertNull(FractionalKey.betweenOrNull(null, FractionalKey("A0")))
        assertFalse(FractionalKey("A0").isWellFormed)
        assertFalse(FractionalKey("").isWellFormed)
        assertFalse(FractionalKey("a-b").isWellFormed)
    }
}
