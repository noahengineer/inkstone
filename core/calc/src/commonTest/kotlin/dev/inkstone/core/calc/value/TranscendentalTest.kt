package dev.inkstone.core.calc.value

import kotlin.test.Test

/**
 * Logs, exponentials and hyperbolics through [Transcendental]: the guide's printed results
 * (research 04 E-03…E-14) and the documented limits (research 02 §13). `10ⁿ` with an exact integer
 * n is the one exact result here.
 */
class TranscendentalTest {
    private val rows = listOf(
        Row("E-03 sinh 1", { show(Transcendental.sinh(int(1))) }, "~1.175201194"),
        Row("E-04 acosh 1", { show(Transcendental.acosh(int(1))) }, "~0"),
        Row("acosh below 1", { show(Transcendental.acosh(frac(1, 2))) }, "MATH"),
        Row("cosh past the limit", { show(Transcendental.cosh(dec("230.2585093"))) }, "MATH"),
        Row("cosh at the printed limit", { show(Transcendental.cosh(dec("230.2585092"))) }, "~4.999999503E99"),
        Row("atanh 1", { show(Transcendental.atanh(int(1))) }, "MATH"),
        Row("tanh 1", { show(Transcendental.tanh(int(1))) }, "~0.761594156"),
        Row("asinh 1", { show(Transcendental.asinh(int(1))) }, "~0.881373587"),
        Row("E-09 log 1000", { show(Transcendental.log10(int(1000))) }, "~3"),
        Row("E-10 log 2 of 16", { show(Transcendental.log(int(2), int(16))) }, "~4"),
        Row("E-12 log 2 of 4 cubed", { show(Transcendental.log(int(2), RealArith.cube(int(4)).ok())) }, "~6"),
        Row("E-13 log 2 of 4 then cubed", { show(RealArith.cube(Transcendental.log(int(2), int(4)).ok())) }, "~8"),
        Row("E-14 ln 90", { show(Transcendental.ln(int(90))) }, "~4.49980967"),
        Row("E-07 e to the 5 times 2", { show(Transcendental.exp(int(5)).ok() * int(2)) }, "~296.8263182"),
        Row("ln 0", { show(Transcendental.ln(int(0))) }, "MATH"),
        Row("log of negative", { show(Transcendental.log10(int(-1))) }, "MATH"),
        Row("log base 1", { show(Transcendental.log(int(1), int(5))) }, "MATH"),
        Row("e to the 231", { show(Transcendental.exp(int(231))) }, "MATH"),
        Row("e to the minus 300 underflows", { show(Transcendental.exp(int(-300))) }, "~0"),
        Row("10 to the 3 is exact", { show(Transcendental.tenPow(int(3))) }, "1000"),
        Row("10 to the minus 2 is exact", { show(Transcendental.tenPow(int(-2))) }, "1/100"),
        Row("10 to the 99", { show(Transcendental.tenPow(int(99))) }, "~1E99"),
        Row("10 to the 100", { show(Transcendental.tenPow(int(100))) }, "MATH"),
        Row("10 to the one half", { show(Transcendental.tenPow(frac(1, 2))) }, "~3.16227766"),
        Row("10 to the decimal 2", { show(Transcendental.tenPow(dec("2"))) }, "~100"),
    )

    @Test
    fun `logs exponentials and hyperbolics follow the guide and its limits`() = checkRows(rows)
}
