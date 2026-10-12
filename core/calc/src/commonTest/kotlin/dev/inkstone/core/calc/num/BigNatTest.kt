package dev.inkstone.core.calc.num

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The integer underneath everything. Knuth's division and the Newton roots are checked by their
 * defining identities over thousands of seeded operands, chosen to hit the awkward shapes:
 * divisors whose top limb is 1 (maximal normalisation), runs of 9s (the add-back step), and
 * single-limb versus multi-limb paths.
 */
class BigNatTest {
    private val random = Random(20261003)

    /** A number of [digits] decimal digits, sometimes all nines, sometimes 1 followed by zeros. */
    private fun operand(digits: Int): BigNat {
        val text = when (random.nextInt(SHAPES)) {
            0 -> "9".repeat(digits)

            1 -> "1" + "0".repeat(digits - 1)

            2 -> "1" + "0".repeat(digits / 2) + "1".repeat(digits - 1 - digits / 2)

            else -> (1..digits).joinToString("") {
                if (it == 1) "${random.nextInt(1, 10)}" else "${random.nextInt(10)}"
            }
        }
        return BigNat.parse(text)
    }

    @Test
    fun `division satisfies q times d plus r equals n with r below d`() {
        repeat(ROUNDS) {
            val n = operand(random.nextInt(1, MAX_DIGITS))
            val divisor = operand(random.nextInt(1, MAX_DIGITS))
            val (q, r) = n.divRem(divisor)
            assertEquals(n, q * divisor + r, "$n / $divisor")
            assertTrue(r < divisor, "$n mod $divisor = $r")
        }
    }

    @Test
    fun `parse and toString round trip and count digits`() {
        repeat(ROUNDS) {
            val digits = random.nextInt(1, MAX_DIGITS)
            val n = operand(digits)
            assertEquals(n, BigNat.parse(n.toString()))
            assertEquals(digits, n.digitCount)
        }
        assertEquals("0", BigNat.ZERO.toString())
        assertEquals(BigNat.ZERO, BigNat.parse("0000"))
        assertEquals("1000000000", BigNat.of(1_000_000_000L).toString())
    }

    @Test
    fun `powers of ten shift exactly`() {
        repeat(ROUNDS) {
            val n = operand(random.nextInt(1, MAX_DIGITS))
            val k = random.nextInt(0, MAX_DIGITS)
            assertEquals(n, n.timesPow10(k).divPow10(k))
            assertEquals(n * BigNat.pow10(k), n.timesPow10(k))
        }
        assertEquals(BigNat.parse("12"), BigNat.parse("12345").divPow10(3))
        assertEquals(BigNat.ZERO, BigNat.parse("12345").divPow10(30))
    }

    @Test
    fun `small operations and Long conversion`() {
        assertEquals(BigNat.parse("1999999998"), BigNat.parse("999999999").timesSmall(2))
        val (q, r) = BigNat.parse("1000000000000000000007").divRemSmall(10)
        assertEquals(BigNat.parse("100000000000000000000"), q)
        assertEquals(7, r)
        assertEquals(Long.MAX_VALUE, BigNat.parse(Long.MAX_VALUE.toString()).toLongOrNull())
        assertEquals(null, BigNat.parse("9223372036854775808").toLongOrNull())
        assertEquals(0L, BigNat.ZERO.toLongOrNull())
    }

    @Test
    fun `integer square and cube roots are exact floors`() {
        repeat(ROUNDS) {
            val n = operand(random.nextInt(1, MAX_DIGITS))
            val s = NatRoots.isqrt(n)
            assertTrue(s * s <= n && n < (s + BigNat.ONE) * (s + BigNat.ONE), "isqrt $n = $s")
            val c = NatRoots.icbrt(n)
            val c1 = c + BigNat.ONE
            assertTrue(c * c * c <= n && n < c1 * c1 * c1, "icbrt $n = $c")
        }
        assertEquals(BigNat.parse("111111111"), NatRoots.isqrt(BigNat.parse("12345678987654321")))
        assertEquals(BigNat.ZERO, NatRoots.isqrt(BigNat.ZERO))
        assertEquals(BigNat.parse("12345"), NatRoots.icbrt(BigNat.parse("12345").let { it * it * it }))
    }

    /** Any index the rational-power path uses (2…49), on operands up to ~900 digits. */
    @Test
    fun `integer kth roots are exact floors`() {
        repeat(ROOT_ROUNDS) {
            val k = random.nextInt(2, MAX_ROOT_INDEX + 1)
            val n = operand(random.nextInt(1, MAX_ROOT_DIGITS))
            val r = NatRoots.iroot(n, k)
            assertTrue(r.pow(k) <= n && n < (r + BigNat.ONE).pow(k), "iroot($n, $k) = $r")
        }
        assertEquals(BigNat.parse("2384185791015625"), NatRoots.iroot(BigNat.parse("5").pow(22 * 7), 7))
        assertEquals(BigNat.ONE, NatRoots.iroot(BigNat.ONE, 49))
        assertEquals(BigNat.ONE, NatRoots.iroot(BigNat.parse("9".repeat(14)), 49))
    }

    @Test
    fun `pow is repeated multiplication`() {
        repeat(ROUNDS / 10) {
            val n = operand(random.nextInt(1, 20))
            val e = random.nextInt(0, 30)
            var expected = BigNat.ONE
            repeat(e) { expected *= n }
            assertEquals(expected, n.pow(e), "$n ^ $e")
        }
        assertEquals(BigNat.ONE, BigNat.ZERO.pow(0))
    }

    private companion object {
        const val ROOT_ROUNDS = 300
        const val MAX_ROOT_INDEX = 49
        const val MAX_ROOT_DIGITS = 900
        const val ROUNDS = 2000
        const val MAX_DIGITS = 90
        const val SHAPES = 6
    }
}
