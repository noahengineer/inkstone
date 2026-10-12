package dev.inkstone.core.calc.num

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DecMath's exact Long helpers: what fractions, surds, GCD, LCM and FACT stand on. */
class IntegerHelpersTest {
    private val gcds = listOf(
        Triple(12L, 18L, 6L),
        Triple(-12L, 18L, 6L),
        Triple(12L, -18L, 6L),
        Triple(0L, 5L, 5L),
        Triple(0L, 0L, 0L),
        Triple(17L, 5L, 1L),
        Triple(9_999_999_999L, 3_333_333_333L, 3_333_333_333L),
        Triple(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
    )

    private val lcms = listOf(
        Triple(4L, 6L, 12L),
        Triple(-4L, 6L, 12L),
        Triple(0L, 5L, 0L),
        Triple(21L, 6L, 42L),
        Triple(9_999_999_999L, 3_333_333_333L, 9_999_999_999L),
        Triple(1L, 1L, 1L),
    )

    /** n → (k, r) with n = k²·r and r squarefree. */
    private val squareFree = listOf(
        72L to (6L to 2L), 999L to (3L to 111L), 1000L to (10L to 10L), 1L to (1L to 1L), 2L to (1L to 2L),
        4L to (2L to 1L), 12L to (2L to 3L), 49L to (7L to 1L), 97L to (1L to 97L), 8L to (2L to 2L),
        10_000_000_000L to (100_000L to 1L), 1_000_006_000_009L to (1_000_003L to 1L),
        2_000_012_000_018L to (1_000_003L to 2L), 999_983L * 1_000_003L to (1L to 999_983L * 1_000_003L),
        4L * 999_983L * 1_000_003L to (2L to 999_983L * 1_000_003L), 360_000L to (600L to 1L),
    )

    @Test
    fun `gcd of absolute values`() {
        for ((a, b, g) in gcds) assertEquals(g, DecMath.gcd(a, b), "gcd $a $b")
    }

    @Test
    fun `lcm of absolute values and null on overflow`() {
        for ((a, b, l) in lcms) assertEquals(l, DecMath.lcm(a, b), "lcm $a $b")
        // Their difference is 24, so their gcd divides 24 and the lcm is past 4×10²¹.
        assertEquals(null, DecMath.lcm(9_999_999_967_000L, 9_999_999_943_000L))
        assertEquals(null, DecMath.lcm(Long.MAX_VALUE, 2L))
    }

    @Test
    fun `squareFreePart splits off the largest square`() {
        for ((n, expected) in squareFree) {
            val (k, r) = DecMath.squareFreePart(n)
            assertEquals(expected, k to r, "squareFreePart $n")
            assertEquals(n, k * k * r, "reconstruct $n")
        }
    }

    @Test
    fun `squareFreePart leaves a squarefree radicand for every n up to 3000`() {
        for (n in 1L..3000L) {
            val (k, r) = DecMath.squareFreePart(n)
            assertEquals(n, k * k * r)
            for (p in 2L..55L) assertTrue(r % (p * p) != 0L, "$n left $r divisible by $p squared")
        }
    }

    @Test
    fun `perfect squares and floor square roots`() {
        val squares = listOf(0L, 1L, 4L, 144L, 3_037_000_499L * 3_037_000_499L, 1_000_000_000_000_000_000L)
        val notSquares = listOf(2L, 145L, -4L, Long.MAX_VALUE, 3_037_000_499L * 3_037_000_499L - 1)
        for (n in squares) assertTrue(DecMath.isPerfectSquare(n), "$n")
        for (n in notSquares) assertFalse(DecMath.isPerfectSquare(n), "$n")
        assertEquals(3L, DecMath.floorSqrt(15L))
        assertEquals(4L, DecMath.floorSqrt(16L))
        assertEquals(0L, DecMath.floorSqrt(0L))
        assertEquals(3_037_000_499L, DecMath.floorSqrt(Long.MAX_VALUE))
    }

    @Test
    fun `multiply add and power report overflow as null`() {
        assertEquals(12L, DecMath.multiplyExact(3L, 4L))
        assertEquals(null, DecMath.multiplyExact(Long.MAX_VALUE, 2L))
        assertEquals(null, DecMath.multiplyExact(Long.MIN_VALUE, -1L))
        assertEquals(null, DecMath.multiplyExact(-1L, Long.MIN_VALUE))
        assertEquals(Long.MIN_VALUE, DecMath.multiplyExact(Long.MIN_VALUE, 1L))
        assertEquals(0L, DecMath.multiplyExact(0L, Long.MIN_VALUE))
        assertEquals(9_223_372_030_926_249_001L, DecMath.multiplyExact(3_037_000_499L, 3_037_000_499L))
        assertEquals(null, DecMath.multiplyExact(3_037_000_500L, 3_037_000_500L))
        assertEquals(-2L, DecMath.addExact(5L, -7L))
        assertEquals(null, DecMath.addExact(Long.MAX_VALUE, 1L))
        assertEquals(null, DecMath.addExact(Long.MIN_VALUE, -1L))
        assertEquals(1_000_000_000_000_000_000L, DecMath.powExact(10L, 18))
        assertEquals(null, DecMath.powExact(10L, 19))
        assertEquals(4_611_686_018_427_387_904L, DecMath.powExact(2L, 62))
        assertEquals(null, DecMath.powExact(2L, 63))
        assertEquals(Long.MIN_VALUE, DecMath.powExact(-2L, 63))
        assertEquals(1L, DecMath.powExact(1L, Int.MAX_VALUE))
        assertEquals(-1L, DecMath.powExact(-1L, Int.MAX_VALUE))
        assertEquals(1L, DecMath.powExact(0L, 0))
        assertEquals(0L, DecMath.powExact(0L, 5))
        assertEquals(1L, DecMath.powExact(7L, 0))
    }
}
