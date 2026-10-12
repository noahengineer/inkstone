package dev.inkstone.core.calc.num

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Parse a test literal; a typo in the table should fail loudly, not as a null. */
internal fun d(text: String): Dec = assertNotNull(Dec.parse(text), "bad test literal $text")

/**
 * `Dec`'s arithmetic semantics. Several rows pin a **guess about Casio's firmware**, chosen in
 * docs/CALCULATOR.md §3 and not yet checked against the emulator: rounding every result to 15
 * digits half away from zero with no guard digit (research 07 T3), and underflow to zero (T4).
 * Those rows say so; when T3/T4 are run, they are the ones to revisit.
 */
class DecArithmeticTest {
    /** `start op₁ a₁ op₂ a₂ … = expected`, left to right, each step rounded. */
    private class Chain(val start: String, val steps: List<Pair<Char, String>>, val expected: String)

    private fun chain(start: String, vararg steps: Pair<Char, String>, expected: String) =
        Chain(start, steps.toList(), expected)

    private fun run(c: Chain): Dec = c.steps.fold(d(c.start)) { acc, (op, operand) ->
        when (op) {
            '+' -> acc + d(operand)
            '-' -> acc - d(operand)
            '*' -> acc * d(operand)
            '/' -> acc / d(operand)
            else -> error("unknown op $op")
        }
    }

    /** The reason the engine is decimal: these are exact here and wrong in a Double. */
    private val exactDecimal = listOf(
        chain("1", '-' to "0.9", '-' to "0.1", expected = "0"),
        chain("0.1", '+' to "0.2", expected = "0.3"),
        chain("0.7", '+' to "0.1", '-' to "0.8", expected = "0"),
        chain("1.1", '*' to "1.1", expected = "1.21"),
        chain("0.3", '/' to "0.1", expected = "3"),
    )

    /**
     * Fifteen digits and no guard digit (CALCULATOR.md §3, a guess: research 07 T3). `1÷3×3` is
     * `0,999999999999999` internally; the display rounds it to `1`.
     */
    private val fifteenDigits = listOf(
        chain("1", '/' to "3", expected = "0.333333333333333"),
        chain("2", '/' to "3", expected = "0.666666666666667"),
        chain("-2", '/' to "3", expected = "-0.666666666666667"),
        chain("1", '/' to "7", expected = "0.142857142857143"),
        chain("100", '/' to "7", expected = "14.2857142857143"),
        chain("1", '/' to "3", '*' to "3", expected = "0.999999999999999"),
        chain("2", '/' to "3", '*' to "3", expected = "2"),
        chain("10", '/' to "3", '*' to "3", expected = "9.99999999999999"),
        chain("2", '/' to "3", '*' to "3", '-' to "2", expected = "0"),
        chain("1", '-' to "1", '/' to "3", '*' to "3", expected = "0"),
    )

    /** A 16th digit of exactly 5 with nothing after it rounds away from zero, in every operation. */
    private val ties = listOf(
        chain("1", '+' to "5E-15", expected = "1.00000000000001"),
        chain("-1", '-' to "5E-15", expected = "-1.00000000000001"),
        chain("1", '-' to "5E-16", expected = "1"),
        chain("9999.9995", '*' to "9999.9995", expected = "99999990.0000003"),
        chain("-9999.9995", '*' to "9999.9995", expected = "-99999990.0000003"),
        chain("3.00000000000001", '/' to "2", expected = "1.50000000000001"),
        chain("-3.00000000000001", '/' to "2", expected = "-1.50000000000001"),
    )

    /**
     * An addend 17 or more places below the other operand cannot move a 15-digit result — and at
     * 16 places it can. Both sides of that boundary, including below a power of ten.
     */
    private val negligibleAddends = listOf(
        chain("1", '+' to "9.99999999999999E-17", expected = "1"),
        chain("1", '-' to "9.99999999999999E-17", expected = "1"),
        chain("1.00000000000001", '-' to "9.99999999999999E-17", expected = "1.00000000000001"),
        chain("1", '-' to "9.99999999999999E-16", expected = "0.999999999999999"),
        chain("1", '-' to "1E-16", expected = "1"),
        chain("1E50", '+' to "1", expected = "1E50"),
        chain("1E-50", '+' to "1E50", expected = "1E50"),
    )

    /** Range: underflow becomes zero (a guess, research 07 T4); overflow is kept, not clamped. */
    private val range = listOf(
        chain("1E-99", '/' to "10", expected = "0"),
        chain("1E-50", '*' to "1E-50", expected = "0"),
        chain("1.5E-99", '-' to "1E-99", expected = "0"),
        chain("1E-99", '*' to "1", expected = "1E-99"),
        chain("1E99", '*' to "9.99", expected = "9.99E99"),
        chain("1E99", '*' to "1E99", expected = "1E198"),
        chain("9.99999999999999E99", '+' to "4E84", expected = "9.99999999999999E99"),
        chain("9.99999999999999E99", '+' to "5E84", expected = "1E100"),
    )

    @Test
    fun `decimal arithmetic is exact where binary arithmetic is not`() = check(exactDecimal)

    @Test
    fun `every result is rounded to fifteen digits with no guard digit`() = check(fifteenDigits)

    @Test
    fun `ties round half away from zero`() = check(ties)

    @Test
    fun `a negligible addend leaves the result unchanged and the boundary is sharp`() = check(negligibleAddends)

    @Test
    fun `underflow becomes zero and overflow is kept`() = check(range)

    /**
     * Research 07 T3's own two probes, written as the calculator groups them, with the answers the
     * chosen rule predicts (a guess until T3 is run): `(2÷3)×3−2` is `0` — `0,666666666666667×3`
     * rounds back to exactly 2 — while `1−(1÷3)×3` is `1×10⁻¹⁵`, the one-unit shortfall of
     * `0,333333333333333×3`. Extra guard digits would make the second 0 as well, and truncating
     * instead of rounding would make the first `−2×10⁻¹⁵` — which is why T3 asks exactly these.
     */
    @Test
    fun `the T3 probes give what fifteen digits and no guard digit predict`() {
        assertEquals(Dec.ZERO, Dec.TWO / d("3.0") * d("3") - Dec.TWO)
        assertEquals(d("1E-15"), Dec.ONE - Dec.ONE / d("3.0") * d("3"))
    }

    @Test
    fun `one minus nine tenths minus one tenth is exactly zero`() {
        val r = d("1") - d("0.9") - d("0.1")
        assertTrue(r.isZero)
        assertEquals(Dec.ZERO, r)
        assertEquals(0, r.signum)
    }

    @Test
    fun `the overflow flag starts at ten to the hundred`() {
        assertFalse(d("9.99999999999999E99").isOverflow)
        assertTrue(d("1E100").isOverflow)
        assertTrue(d("-1E100").isOverflow)
        assertTrue((d("1E99") * d("10")).isOverflow)
        assertFalse(Dec.ZERO.isOverflow)
        val huge = d("1E99") * d("1E99")
        assertEquals(198, huge.exponent, "overflow is not clamped to the range")
        assertTrue((huge * huge).isOverflow)
    }

    @Test
    fun `there is no negative zero`() {
        val zeros = listOf(d("-0"), d("1") - d("1"), d("0") * d("-5"), -Dec.ZERO, d("-1E-200"), d("-0.000"))
        for (z in zeros) {
            assertEquals(Dec.ZERO, z)
            assertFalse(z.isNegative)
            assertEquals(Dec.ZERO.hashCode(), z.hashCode())
        }
    }

    private fun check(table: List<Chain>) {
        for (c in table) {
            val label = c.start + c.steps.joinToString("") { (op, v) -> " $op $v" }
            assertEquals(d(c.expected), run(c), label)
        }
    }
}
