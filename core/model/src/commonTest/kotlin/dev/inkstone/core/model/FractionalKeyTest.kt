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
    fun `stacking a thousand objects on top keeps the key short`() {
        // Every stroke drawn goes above everything on the page, so this is the call that decides
        // how long keys get. Bisecting towards the end of the alphabet grew one character every
        // six strokes: the physics note reached 852 characters at 5,159 objects, and this loop
        // used to end at ~170.
        var top = FractionalKey.Initial
        repeat(1_000) {
            val next = FractionalKey.between(top, null)
            assertTrue(top < next, "$next is not above $top")
            assertTrue(next.isWellFormed, "generated a malformed key: $next")
            top = next
        }
        assertTrue(top.value.length <= 20, "keys grew to ${top.value.length} characters")
    }

    @Test
    fun `a key above an all-z key is one character longer and no more`() {
        // Nothing shorter can sort above it, so this is the floor - and it is what a note whose
        // keys are already long gets from now on, rather than growing by ten more per sixty strokes.
        val long = "z".repeat(852)
        val next = between(long, null)
        assertEquals(long + "1", next.value)
        assertTrue(FractionalKey(long) < next)
        assertEquals(long + "2", between(next.value, null).value)
    }

    @Test
    fun `a key above any key is strictly above it and well formed`() {
        val random = Random(20261010)
        val digits = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        repeat(2_000) {
            val length = 1 + random.nextInt(12)
            val body = buildString { repeat(length - 1) { append(digits[random.nextInt(digits.length)]) } }
            // Mostly 'z'-heavy keys, because that is where stepping has to look past a prefix.
            val zs = "z".repeat(random.nextInt(4))
            val key = FractionalKey(zs + body + digits[1 + random.nextInt(digits.length - 1)])
            val next = FractionalKey.between(key, null)
            assertTrue(key < next, "$next is not above $key")
            assertTrue(next.isWellFormed, "generated a malformed key: $next")
            assertTrue(next.value.length <= key.value.length + 1, "$key stepped to the longer $next")
        }
    }

    @Test
    fun `well formedness is exactly the digit alphabet`() {
        // The characters either side of each range, which a range test gets wrong by one.
        for (outside in listOf('/', ':', '@', '[', '`', '{', ' ', '-', 'é')) {
            assertFalse(FractionalKey("A${outside}B").isWellFormed, "accepted '$outside'")
        }
        assertTrue(FractionalKey("09AZaz").isWellFormed)
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
