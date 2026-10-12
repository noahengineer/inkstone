package dev.inkstone.core.calc.num

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DecMath's identities, its exact results, and where it answers null (Math ERROR).
 *
 * The identities are checked to **one unit in the 15th digit** of the expected value, on
 * arguments where the identity is well conditioned — `exp(ln x)` for `x` near 1, `asin(sin x)`
 * for `|x| ≤ 0,5` — because each side is rounded to 15 digits on the way and a badly conditioned
 * argument multiplies that rounding (e.g. `exp(ln 10⁵⁰)` is only good to about 50 units).
 */
class DecMathBehaviourTest {
    private val nearOne = listOf("0.5", "0.75", "0.999999999999999", "1.00000000000001", "1.23456789012345", "2", "2.5")
    private val angles = listOf("0.1", "0.5", "1", "2", "3", "-7.5", "10", "100", "100000", "1E10", "0.000001")
    private val small = listOf("0.5", "-0.5", "0.3", "0.123456789012345", "-0.0001", "1E-20", "0.25")

    private val exact = listOf(
        "sqrt 2.25" to (DecMath.sqrt(d("2.25")) to "1.5"),
        "sqrt 1E-98" to (DecMath.sqrt(d("1E-98")) to "1E-49"),
        "cbrt 3.375" to (DecMath.cbrt(d("3.375")) to "1.5"),
        "cbrt -1E-99" to (DecMath.cbrt(d("-1E-99")) to "-1E-33"),
        "log10 1000" to (DecMath.log10(d("1000")) to "3"),
        "log10 1E-99" to (DecMath.log10(d("1E-99")) to "-99"),
        "log 2 8" to (DecMath.log(Dec.TWO, d("8")) to "3"),
        "log 4 8" to (DecMath.log(d("4"), d("8")) to "1.5"),
        "pow 2 10" to (DecMath.pow(Dec.TWO, d("10")) to "1024"),
        "pow 2 -3" to (DecMath.pow(Dec.TWO, d("-3")) to "0.125"),
        "pow -2 3" to (DecMath.pow(d("-2"), d("3")) to "-8"),
        "pow -2 -2" to (DecMath.pow(d("-2"), d("-2")) to "0.25"),
        "pow 9999.9995 2" to (DecMath.pow(d("9999.9995"), Dec.TWO) to "99999990.0000003"),
        "pow 1.5 10" to (DecMath.pow(d("1.5"), d("10")) to "57.6650390625"),
        "pow 7 0" to (DecMath.pow(d("7"), Dec.ZERO) to "1"),
        "pow 0 5" to (DecMath.pow(Dec.ZERO, d("5")) to "0"),
        "pow 1E-10 10" to (DecMath.pow(d("1E-10"), d("10")) to "0"),
        "pow 4 0.5" to (DecMath.pow(d("4"), Dec.HALF) to "2"),
        "tenPow 2" to (DecMath.tenPow(Dec.TWO) to "100"),
        "tenPow -99" to (DecMath.tenPow(d("-99")) to "1E-99"),
        "tenPow -100" to (DecMath.tenPow(d("-100")) to "0"),
        "root3 -8" to (DecMath.nthRoot(d("-8"), 3) to "-2"),
        "root5 32" to (DecMath.nthRoot(d("32"), 5) to "2"),
        "root4 16" to (DecMath.nthRoot(d("16"), 4) to "2"),
        "root-2 4" to (DecMath.nthRoot(d("4"), -2) to "0.5"),
        "root-1 8" to (DecMath.nthRoot(d("8"), -1) to "0.125"),
        "root7 -128" to (DecMath.nthRoot(d("-128"), 7) to "-2"),
        "exp 0" to (DecMath.exp(Dec.ZERO) to "1"),
        "ln 1" to (DecMath.ln(Dec.ONE) to "0"),
        "sin 0" to (DecMath.sin(Dec.ZERO) to "0"),
        "cos 0" to (DecMath.cos(Dec.ZERO) to "1"),
        "tan 0" to (DecMath.tan(Dec.ZERO) to "0"),
        "asin 0" to (DecMath.asin(Dec.ZERO) to "0"),
        "acos 1" to (DecMath.acos(Dec.ONE) to "0"),
        "atanh 0" to (DecMath.atanh(Dec.ZERO) to "0"),
        "cosh 0" to (DecMath.cosh(Dec.ZERO) to "1"),
        "atan2 1 -1" to (DecMath.atan2(Dec.ONE, -Dec.ONE) to "2.35619449019234"),
        "atan2 -1 -1" to (DecMath.atan2(-Dec.ONE, -Dec.ONE) to "-2.35619449019234"),
        "atan2 1 0" to (DecMath.atan2(Dec.ONE, Dec.ZERO) to "1.5707963267949"),
        "atan2 -1 0" to (DecMath.atan2(-Dec.ONE, Dec.ZERO) to "-1.5707963267949"),
        "atan2 0 5" to (DecMath.atan2(Dec.ZERO, d("5")) to "0"),
        "exp -1000" to (DecMath.exp(d("-1000")) to "0"),
        "exp -230" to (DecMath.exp(d("-230")) to "0"),
    )

    /** Each of these is Math ERROR on the calculator: a domain error or a result ≥ 10¹⁰⁰. */
    private val mathErrors: List<Pair<String, Dec?>> = listOf(
        "sqrt -1" to DecMath.sqrt(d("-1")),
        "ln 0" to DecMath.ln(Dec.ZERO),
        "ln -1" to DecMath.ln(d("-1")),
        "log10 0" to DecMath.log10(Dec.ZERO),
        "log 1 5" to DecMath.log(Dec.ONE, d("5")),
        "log -2 5" to DecMath.log(d("-2"), d("5")),
        "log 2 0" to DecMath.log(Dec.TWO, Dec.ZERO),
        "asin 1.1" to DecMath.asin(d("1.1")),
        "acos -1.00000000000001" to DecMath.acos(d("-1.00000000000001")),
        "acosh 0.5" to DecMath.acosh(Dec.HALF),
        "atanh 1" to DecMath.atanh(Dec.ONE),
        "atanh -1.5" to DecMath.atanh(d("-1.5")),
        "pow 0 0" to DecMath.pow(Dec.ZERO, Dec.ZERO),
        "pow 0 -1" to DecMath.pow(Dec.ZERO, d("-1")),
        "pow -8 0.5" to DecMath.pow(d("-8"), Dec.HALF),
        "pow 10 100" to DecMath.pow(Dec.TEN, d("100")),
        "pow 2 333" to DecMath.pow(Dec.TWO, d("333")),
        "pow -2 2000" to DecMath.pow(d("-2"), d("2000")),
        "pow 1.0000000001 1E50" to DecMath.pow(d("1.0000000001"), d("1E50")),
        "root0 5" to DecMath.nthRoot(d("5"), 0),
        "root4 -16" to DecMath.nthRoot(d("-16"), 4),
        "root-2 0" to DecMath.nthRoot(Dec.ZERO, -2),
        "exp 231" to DecMath.exp(d("231")),
        "exp 230.2585093" to DecMath.exp(d("230.2585093")),
        "tenPow 100" to DecMath.tenPow(d("100")),
        "sinh 241" to DecMath.sinh(d("241")),
        "cosh -231" to DecMath.cosh(d("-231")),
        // Research 02 §13 limits sinh and cosh to the range of eˣ, not to their own (|x| < 230,95).
        "sinh 230.258509299405" to DecMath.sinh(d("230.258509299405")),
        "sinh 230.9" to DecMath.sinh(d("230.9")),
        "cosh -230.5" to DecMath.cosh(d("-230.5")),
        // ... and sinh⁻¹, cosh⁻¹ to below 5×10⁹⁹, though the logarithm itself would be about 230.
        "asinh 5E99" to DecMath.asinh(d("5E99")),
        "asinh -6E99" to DecMath.asinh(d("-6E99")),
        "acosh 5E99" to DecMath.acosh(d("5E99")),
        "atan2 0 0" to DecMath.atan2(Dec.ZERO, Dec.ZERO),
        "factorial 70" to DecMath.factorial(70),
        "sin overflow" to DecMath.sin(d("1E99") * Dec.TEN),
    )

    @Test
    fun `exp of ln x is x`() {
        for (s in nearOne) assertWithinOneUlp(d(s), DecMath.ln(d(s))?.let(DecMath::exp), "exp ln $s")
    }

    @Test
    fun `sin squared plus cos squared is one`() {
        for (s in angles) {
            val sin = assertNotNull(DecMath.sin(d(s)))
            val cos = assertNotNull(DecMath.cos(d(s)))
            assertWithinOneUlp(Dec.ONE, sin * sin + cos * cos, "sin2 plus cos2 at $s")
        }
    }

    @Test
    fun `asin of sin x is x`() {
        for (s in small) assertWithinOneUlp(d(s), DecMath.sin(d(s))?.let(DecMath::asin), "asin sin $s")
    }

    @Test
    fun `tan is sin over cos and odd functions are odd`() {
        for (s in angles) {
            val x = d(s)
            val tan = assertNotNull(DecMath.tan(x))
            val ratio = assertNotNull(DecMath.sin(x)) / assertNotNull(DecMath.cos(x))
            assertTrue(ulps(tan, ratio) <= 2, "tan $s")
            assertEquals(DecMath.sin(-x), DecMath.sin(x)?.let { -it }, "sin is odd at $s")
            assertEquals(DecMath.cos(-x), DecMath.cos(x), "cos is even at $s")
            assertEquals(DecMath.sinh(-x), DecMath.sinh(x)?.let { -it }, "sinh is odd at $s")
        }
    }

    @Test
    fun `exact cases are exact`() {
        for ((label, pair) in exact) assertEquals(d(pair.second), pair.first, label)
    }

    @Test
    fun `out of domain or out of range is null`() {
        for ((label, value) in mathErrors) assertNull(value, label)
    }

    @Test
    fun `the largest representable results are not errors`() {
        assertNotNull(DecMath.exp(d("230.2585092")))
        assertNotNull(DecMath.tenPow(d("99.99999999")))
        // The last x on each side of the hyperbolic limits; digits from Python's decimal at 120.
        assertEquals(d("4.99999999999716E99"), DecMath.sinh(d("230.258509299404")))
        assertEquals(d("4.99999999999716E99"), DecMath.cosh(d("-230.258509299404")))
        assertEquals(d("2.30258509299405E2"), DecMath.asinh(d("4.99999999999999E99")))
        assertEquals(d("-2.30258509299405E2"), DecMath.asinh(d("-4.99999999999999E99")))
        assertEquals(d("2.30258509299405E2"), DecMath.acosh(d("4.99999999999999E99")))
        assertEquals(d("1.76159617877353E1"), DecMath.atanh(d("0.999999999999999")))
        assertNotNull(DecMath.pow(Dec.TWO, d("332")))
        assertEquals(d("1.5707963267949"), DecMath.atan(d("9.99999999999999E99")))
        assertEquals(d("2.15443469003188E33"), DecMath.cbrt(d("9.99999999999999E99")))
        assertEquals(d("1.71122452428141E98"), DecMath.factorial(69))
    }

    /** `|a − b|` in units of the 15th digit of the larger, rounded up. */
    private fun ulps(a: Dec, b: Dec): Long {
        val unit = Dec.ONE.scaleByPowerOfTen(maxOf(a.exponent, b.exponent) - (Dec.MAX_DIGITS - 1))
        return assertNotNull(((a - b).abs() / unit).ceil().toLongOrNull())
    }

    private fun assertWithinOneUlp(expected: Dec, actual: Dec?, label: String) {
        val a = assertNotNull(actual, label)
        assertTrue(ulps(expected, a) <= 1, "$label: expected $expected, got $a")
    }
}
