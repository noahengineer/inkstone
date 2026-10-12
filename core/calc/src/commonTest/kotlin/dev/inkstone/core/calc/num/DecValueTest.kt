package dev.inkstone.core.calc.num

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Properties, rounding helpers, ordering and the two Casio constants. */
class DecValueTest {
    /** value, digits kept, expected — half away from zero, as Norm and Sci display round (07 T5). */
    private val significant = listOf(
        Triple("1.25", 2, "1.3"),
        Triple("-1.25", 2, "-1.3"),
        Triple("1.24999999999999", 2, "1.2"),
        Triple("0.12345678905", 10, "0.1234567891"),
        Triple("-0.12345678905", 10, "-0.1234567891"),
        Triple("9999999999.6", 10, "10000000000"),
        Triple("9999999999.4", 10, "9999999999"),
        Triple("3.14159265358980", 10, "3.141592654"),
        Triple("123", 15, "123"),
        Triple("987654321", 1, "1E9"),
    )

    /** value, decimal places, expected — Fix n and Rnd under Fix (research 07 §4.2). */
    private val places = listOf(
        Triple("0.125", 2, "0.13"),
        Triple("-0.125", 2, "-0.13"),
        Triple("2.5", 0, "3"),
        Triple("-2.5", 0, "-3"),
        Triple("0.5", 0, "1"),
        Triple("0.4", 0, "0"),
        Triple("14.2857142857143", 3, "14.286"),
        Triple("99.995", 2, "100"),
        Triple("0.0004", 2, "0"),
        Triple("0.005", 2, "0.01"),
        Triple("1234.5", -2, "1200"),
        Triple("1250", -2, "1300"),
        Triple("7", 9, "7"),
    )

    /** value → floor, ceil, truncate, fractional part. */
    private val integerParts = listOf(
        listOf("2.5", "2", "3", "2", "0.5"),
        listOf("-2.5", "-3", "-2", "-2", "-0.5"),
        listOf("0.3", "0", "1", "0", "0.3"),
        listOf("-0.3", "-1", "0", "0", "-0.3"),
        listOf("7", "7", "7", "7", "0"),
        listOf("1E20", "1E20", "1E20", "1E20", "0"),
        listOf("1E-50", "0", "1", "0", "1E-50"),
        listOf("-123456.789012345", "-123457", "-123456", "-123456", "-0.789012345"),
    )

    @Test
    fun `roundSignificant rounds half away from zero`() {
        for ((v, n, expected) in significant) assertEquals(d(expected), d(v).roundSignificant(n), "$v to $n")
    }

    @Test
    fun `roundPlaces rounds half away from zero`() {
        for ((v, n, expected) in places) assertEquals(d(expected), d(v).roundPlaces(n), "$v to $n places")
    }

    @Test
    fun `floor ceil truncate and fractional part`() {
        for (row in integerParts) {
            val x = d(row[0])
            assertEquals(d(row[1]), x.floor(), "floor ${row[0]}")
            assertEquals(d(row[2]), x.ceil(), "ceil ${row[0]}")
            assertEquals(d(row[3]), x.truncate(), "truncate ${row[0]}")
            assertEquals(d(row[4]), x.fractionalPart(), "fractional part ${row[0]}")
        }
    }

    @Test
    fun `exponent significand sign and integer flags`() {
        val x = d("-0.000123450")
        assertEquals(-4, x.exponent)
        assertEquals("12345", x.significandDigits)
        assertEquals(-1, x.signum)
        assertTrue(x.isNegative)
        assertFalse(x.isInteger)
        assertEquals(2, d("123").exponent)
        assertTrue(d("1.2E5").isInteger)
        assertTrue(Dec.ZERO.isInteger)
        assertEquals("0", Dec.ZERO.significandDigits)
        assertEquals(0, Dec.ZERO.exponent)
        assertEquals(99, d("9.99999999999999E99").exponent)
    }

    @Test
    fun `equal values are equal however they were written`() {
        val groups = listOf(listOf("0.5", "0.50", "5E-1", "50E-2"), listOf("100", "1E2", "1.0E2", "0.001E5"))
        for (g in groups) {
            val values = g.map { d(it) }
            for (v in values) {
                assertEquals(values[0], v)
                assertEquals(values[0].hashCode(), v.hashCode())
                assertEquals(0, values[0].compareTo(v))
            }
        }
        assertNotEquals(d("0.5"), d("-0.5"))
    }

    @Test
    fun `compareTo orders values across signs and exponents`() {
        val ordered = listOf(
            "-1E150", "-1E50", "-2", "-1.5", "-1.49999999999999", "-1E-50", "0", "1E-99", "0.1",
            "1", "1.00000000000001", "2", "1E99", "1E150",
        ).map { d(it) }
        assertEquals(ordered, ordered.reversed().sorted())
        assertEquals(ordered, ordered.shuffled(kotlin.random.Random(7)).sorted())
    }

    @Test
    fun `exact integers convert to Long and Int`() {
        assertEquals(123L, d("123").toLongOrNull())
        assertEquals(-1_000_000_000_000_000_000L, d("-1E18").toLongOrNull())
        assertEquals(9_223_372_036_854_770_000L, d("9.22337203685477E18").toLongOrNull())
        assertNull(d("1E19").toLongOrNull())
        assertNull(d("1.5").toLongOrNull())
        assertEquals(0L, Dec.ZERO.toLongOrNull())
        assertEquals(Int.MAX_VALUE, d("2147483647").toIntOrNull())
        assertNull(d("2147483648").toIntOrNull())
        assertEquals(Int.MIN_VALUE, d("-2147483648").toIntOrNull())
    }

    @Test
    fun `of rounds a Long to fifteen digits`() {
        assertEquals(d("9.22337203685478E18"), Dec.of(Long.MAX_VALUE))
        assertEquals(d("-9.22337203685478E18"), Dec.of(Long.MIN_VALUE))
        assertEquals(d("1.23456789012346E17"), Dec.of(123_456_789_012_345_678L))
        assertEquals(d("-42"), Dec.of(-42))
        assertEquals(Dec.ZERO, Dec.of(0))
    }

    @Test
    fun `scaleByPowerOfTen is exact and obeys the range`() {
        assertEquals(d("1500"), d("1.5").scaleByPowerOfTen(3))
        assertEquals(d("0.0015"), d("1.5").scaleByPowerOfTen(-3))
        assertTrue(d("1E99").scaleByPowerOfTen(1).isOverflow)
        assertEquals(Dec.ZERO, d("1E-99").scaleByPowerOfTen(-1))
    }

    @Test
    fun `the named constants`() {
        assertEquals(d("0"), Dec.ZERO)
        assertEquals(d("1"), Dec.ONE)
        assertEquals(d("2"), Dec.TWO)
        assertEquals(d("10"), Dec.TEN)
        assertEquals(d("0.5"), Dec.HALF)
        assertEquals(0.1, d("0.1").toDouble())
        assertEquals(-2.5e-7, d("-2.5E-7").toDouble())
    }

    /**
     * Casio's π is `3,14159265358980` — 14 digits and a padded zero — and its e is truncated
     * (research 07 §4.2, VERIFIED from the guide). Quirk Q1 / test T2: `π − 3,14159265358` is
     * `9,8×10⁻¹²` on the calculator, not the true `9,79323846×10⁻¹²`. The displayed result is
     * INFERRED until T2 is run; the stored constant is not.
     */
    @Test
    fun `Casio pi and e are the guide digits not the true ones`() {
        assertEquals("3.1415926535898", Dec.PI.toCanonicalString())
        assertEquals(Dec.PI, d(Dec.PI.toCanonicalString()))
        assertEquals(d("9.8E-12"), Dec.PI - d("3.14159265358"))
        assertEquals(d("9.04E-12"), Dec.E - d("2.71828182845"))
        assertEquals("2.71828182845904", Dec.E.toCanonicalString())
        assertNotEquals(d("3.14159265358979"), Dec.PI)
    }
}
