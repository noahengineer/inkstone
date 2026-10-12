package dev.inkstone.core.calc.format

import kotlin.test.Test

/**
 * The four display families at their boundaries — Norm 1, Norm 2, Fix, Sci — and the rounding
 * GUESSES of docs/CALCULATOR.md §3 pinned: half away from zero, and Norm's range tested on the
 * value **after** its ten-digit rounding (research 07 T5, T6). Values are 15-digit decimals in
 * canonical text, as the evaluator holds them.
 */
class NumberFormatTest {
    @Test
    fun `Norm 2 boundaries`() = checkAll(NORM2)

    @Test
    fun `Norm 1 boundaries`() = checkAll(NORM1)

    @Test
    fun `Fix boundaries and its digit budget`() = checkAll(FIX)

    @Test
    fun `Sci boundaries`() = checkAll(SCI)

    @Test
    fun `Fix and Sci keep exact forms in MathO`() = checkAll(EXACT_UNDER_FORMATS)
}

private fun norm2(text: String): String = shown(d(text), LINE)

private fun norm1(text: String): String = shown(d(text), LINE.norm(1))

private val NORM2 = listOf(
    case("zero", "0") { norm2("0") },
    case("T5 tie at the tenth digit rounds away", "0,1234567891") { norm2("0.12345678905") },
    case("T5 negative tie", "-0,1234567891") { norm2("-0.12345678905") },
    case("below the tie", "0,123456789") { norm2("0.12345678904") },
    case("T6 rounds up to ten to the ten", "1×10^10") { norm2("9999999999.6") },
    case("T6 stays plain", "9999999999") { norm2("9999999999.4") },
    case("eleven digits", "1,23456789×10^10") { norm2("12345678901") },
    case("lowest plain", "0,000000001") { norm2("1E-9") },
    case("rounded up into the plain range", "0,000000001") { norm2("9.9999999995E-10") },
    case("just below the plain range", "9,999999999×10^-10") { norm2("9.999999999E-10") },
    case("trailing zeros dropped", "0,5") { norm2("0.50") },
    case("integer padding", "1200") { norm2("1200") },
    case("ten significant digits after leading zeros", "0,001428571429") { norm2("0.00142857142857143") },
    case("largest", "9,999999999×10^99") { norm2("9.999999999E99") },
    case("smallest", "-1×10^-99") { norm2("-1E-99") },
    case("exact integer past ten digits", "1,23456789×10^12") { shown(q(1234567890123)) },
)

private val NORM1 = listOf(
    case("lowest plain", "0,01") { norm1("0.01") },
    case("rounded up into the plain range", "0,01") { norm1("0.0099999999996") },
    case("just below", "9,999999999×10^-3") { norm1("0.009999999999") },
    case("A-04", "5×10^-3") { norm1("0.005") },
    case("plain integer", "1234") { norm1("1234") },
    case("ten digits", "9999999999") { norm1("9999999999") },
    case("ten to the ten", "1×10^10") { norm1("1E10") },
)

private val FIX = listOf(
    case("Fix 3 pads", "10,000") { shown(d("10"), LINE.fix(3)) },
    case("Fix 3 of an exact integer", "5,000") { shown(q(5), MATH.fix(3)) },
    case("Fix 3 too small", "0,000") { shown(d("0.0004"), LINE.fix(3)) },
    case("Fix 3 half rounds up", "0,001") { shown(d("0.0005"), LINE.fix(3)) },
    case("Fix 3 negative too small has no sign", "0,000") { shown(d("-0.0004"), LINE.fix(3)) },
    case("Fix 3 negative half rounds away", "-0,001") { shown(d("-0.0005"), LINE.fix(3)) },
    case("T5 Fix 2 tie", "0,13") { shown(d("0.125"), LINE.fix(2)) },
    case("Fix 0 tie", "3") { shown(d("2.5"), LINE.fix(0)) },
    case("Fix 0 negative tie", "-3") { shown(d("-2.5"), LINE.fix(0)) },
    case("Fix 0 small", "0") { shown(d("0.4"), LINE.fix(0)) },
    case("Fix 9 fills ten positions", "0,523598776") { shown(d("0.523598775598299"), LINE.fix(9)) },
    case("Fix 9 GUESS ten digit budget", "176,2857143") { shown(d("176.285714285714"), LINE.fix(9)) },
    case("Fix 9 GUESS budget keeps zeros", "123,0000000") { shown(d("123"), LINE.fix(9)) },
    case("Fix 3 GUESS budget carry", "1000000000") { shown(d("999999999.9996"), LINE.fix(3)) },
    case("Fix 3 GUESS past ten digits", "1,235×10^10") { shown(d("12345678901"), LINE.fix(3)) },
    case("Fix 2 GUESS rounded past ten digits", "1,00×10^10") { shown(d("9999999999.996"), LINE.fix(2)) },
)

private val SCI = listOf(
    case("Sci 3 carry moves the exponent", "1,00×10^1") { shown(d("9.996"), LINE.sci(3)) },
    case("Sci 3 zero GUESS", "0,00×10^0") { shown(d("0"), LINE.sci(3)) },
    case("Sci 3 negative", "-1,23×10^-4") { shown(d("-0.000123456"), LINE.sci(3)) },
    case("Sci 1 has no comma", "2×10^-1") { shown(d("0.15"), LINE.sci(1)) },
    case("Sci 0 keeps ten digits", "5,000000000×10^-1") { shown(d("0.5"), LINE.sci(0)) },
    case("Sci 9", "3,14159265×10^0") { shown(d("3.1415926535898"), LINE.sci(9)) },
    case("Sci of an exact integer", "1,2×10^3") { shown(q(1200), MATH.sci(2)) },
)

private val EXACT_UNDER_FORMATS = listOf(
    case("Fix 3 fraction", "2/3") { shown(q(2, 3), MATH.fix(3)) },
    case("Fix 3 surd", "3√(2)") { shown(mul(q(3), sqrt(2)), MATH.fix(3)) },
    case("Fix 3 pi form", "1/6π") { shown(d("0.5235987755983"), MATH.fix(3), piEligible = true) },
    case("Fix 3 LineIO fraction", "2⌟3") { shown(q(2, 3), LINE.fix(3)) },
    case("Sci 2 fraction", "2/3") { shown(q(2, 3), MATH.sci(2)) },
    case("Fix 3 fraction too long for the display", "0,000") { shown(q(1, 123456789), MATH.fix(3)) },
)
