package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.AngleUnit.DEG
import dev.inkstone.core.calc.AngleUnit.RAD
import kotlin.test.Test

/**
 * CMPLX mode at value level: every printed example of research 05 (EX-CMPLX-01…10), each part
 * keeping its own exactness, plus the square root and powers the key machine will need.
 * A result is written `re im i`, each part in the shared `show` notation.
 */
class ComplexTest {
    private fun c(re: Real, im: Real) = ComplexValue(re, im)

    private fun show(r: Res<ComplexValue>): String = when (r) {
        is Res.Err -> r.error.name
        is Res.Ok -> showComplex(r.value)
    }

    private fun polar(r: Res<Polar>): String = when (r) {
        is Res.Err -> r.error.name
        is Res.Ok -> "${show(r.value.r)}∠${show(r.value.theta)}"
    }

    private val one = int(1)
    private val i = ComplexMath.I

    private val examples = listOf(
        Row("EX-CMPLX-01", { show(ComplexMath.divide(c(int(2), int(6)), c(int(0), int(2)))) }, "3 -1i"),
        Row("EX-CMPLX-02 2 angle 45", { show(ComplexMath.polar(int(2), int(45), DEG)) }, "√2 √2i"),
        Row("EX-CMPLX-03 root 2 plus root 2 i in polar", { polar(ComplexMath.toPolar(c(rt(2), rt(2)), DEG)) }, "2∠45"),
        Row("EX-CMPLX-04 1 minus i inverse", { show(ComplexMath.reciprocal(c(one, int(-1)))) }, "1/2 1/2i"),
        Row(
            "EX-CMPLX-05 squares cancel",
            { show(ComplexMath.add(ComplexMath.square(c(one, one)).ok(), ComplexMath.square(c(one, int(-1))).ok())) },
            "0 0i",
        ),
        Row("EX-CMPLX-06 conjugate", { showComplex(ComplexMath.conjugate(c(int(2), int(3)))) }, "2 -3i"),
        Row("EX-CMPLX-07 abs 1 plus i", { show(ComplexMath.abs(c(one, one))) }, "√2"),
        Row("EX-CMPLX-08 arg 1 plus i", { show(ComplexMath.arg(c(one, one), DEG)) }, "45"),
        Row("EX-CMPLX-09 to polar", { polar(ComplexMath.toPolar(c(rt(2), rt(2)), DEG)) }, "2∠45"),
        Row("EX-CMPLX-10 to rectangular", { show(ComplexMath.polar(int(2), int(45), DEG)) }, "√2 √2i"),
    )

    private val more = listOf(
        Row("i squared", { show(ComplexMath.square(i)) }, "-1 0i"),
        Row("i cubed", { show(ComplexMath.cube(i)) }, "0 -1i"),
        Row("1 plus i to the 8", { show(ComplexMath.powInt(c(one, one), 8)) }, "16 0i"),
        Row("1 plus i to the minus 2", { show(ComplexMath.powInt(c(one, one), -2)) }, "0 -1/2i"),
        Row("0 to the 0", { show(ComplexMath.powInt(c(int(0), int(0)), 0)) }, "MATH"),
        Row("over zero", { show(ComplexMath.divide(c(one, one), c(int(0), int(0)))) }, "MATH"),
        Row("root of minus 4 is 2i", { show(ComplexMath.sqrt(c(int(-4), int(0)))) }, "0 2i"),
        Row("root of minus 2", { show(ComplexMath.sqrt(c(int(-2), int(0)))) }, "0 √2i"),
        Row("root of 3 plus 4i", { show(ComplexMath.sqrt(c(int(3), int(4)))) }, "2 1i"),
        Row("root of 3 minus 4i", { show(ComplexMath.sqrt(c(int(3), int(-4)))) }, "2 -1i"),
        Row("root of i", { show(ComplexMath.sqrt(i)) }, "√2/2 √2/2i"),
        Row("2 angle minus 150", { show(ComplexMath.polar(int(2), int(-150), DEG)) }, "-√3 -1i"),
        Row("minus 2 angle 0 points the other way", { show(ComplexMath.polar(int(-2), int(0), DEG)) }, "-2 0i"),
        Row("arg of minus 1", { show(ComplexMath.arg(c(int(-1), int(0)), DEG)) }, "180"),
        Row("arg of minus i in radians", { show(ComplexMath.arg(c(int(0), int(-1)), RAD)) }, "~-1.570796327"),
        Row("abs of 3 plus 4i", { show(ComplexMath.abs(c(int(3), int(4)))) }, "5"),
        Row("subtract", { show(ComplexMath.subtract(c(int(5), int(2)), c(frac(1, 2), dec("0.5")))) }, "9/2 ~1.5i"),
        Row("multiply", { show(ComplexMath.multiply(c(int(2), int(3)), c(int(4), int(-5)))) }, "23 2i"),
    )

    /**
     * Review regressions. `÷` went through `|w|²`, which leaves the range long before the quotient
     * does — `1 ÷ 1×10⁻⁵⁰` underflowed to `÷0` and `(1+i) ÷ 1×10⁶⁰` overflowed, both Math ERROR in
     * CMPLX while COMP answers them. And `√z` took both parts from `|z| ± a`, which cancels to 0
     * when `|b| ≪ |a|`: `√(4 + 10⁻¹⁰ i)` lost its imaginary part entirely. References are exact
     * (Python's decimal module at 80 digits for the roots).
     */
    private fun quotient(a: ComplexValue, b: ComplexValue) = show(ComplexMath.divide(a, b))

    private fun root(re: Real, im: Real) = show(ComplexMath.sqrt(c(re, im)))

    private val ranges = listOf(
        Row("1 plus i over 1E60", { quotient(c(one, one), c(dec("1E60"), int(0))) }, "~1E-60 ~1E-60i"),
        Row("1 over 1E-50", { quotient(c(one, int(0)), c(dec("1E-50"), int(0))) }, "~1E50 ~0i"),
        Row("6E99 over an exact 2", { quotient(c(dec("6E99"), int(0)), c(int(2), int(0))) }, "~3E99 ~0i"),
        Row("9E99 over 2 plus 2i", { quotient(c(dec("9E99"), int(0)), c(int(2), int(2))) }, "~2.25E99 ~-2.25E99i"),
        Row("reciprocal of a tiny point", { quotient(c(one, int(0)), c(dec("1E-60"), dec("1E-60"))) }, "~5E59 ~-5E59i"),
        Row("over a tiny imaginary", { quotient(c(int(2), int(6)), c(int(0), dec("2E-60"))) }, "~3E60 ~-1E60i"),
        Row("decimal division", { quotient(c(int(2), int(6)), c(dec("1"), dec("-1"))) }, "~-2 ~4i"),
        Row("a quotient past 1E100", { quotient(c(dec("1E60"), int(0)), c(dec("1E-60"), dec("1E-60"))) }, "MATH"),
        Row("over a decimal zero", { quotient(c(one, one), c(dec("0"), dec("0"))) }, "MATH"),
        Row("root of 4 plus a tiny i", { root(int(4), dec("1E-10")) }, "~2 ~2.5E-11i"),
        Row("root of minus 1 plus a tiny i", { root(int(-1), dec("1E-10")) }, "~5E-11 ~1i"),
        Row("root of minus 3 plus 4i", { root(int(-3), int(4)) }, "1 2i"),
        Row("root of minus 3 minus 4i", { root(int(-3), int(-4)) }, "1 -2i"),
        Row("root of 2i", { root(int(0), int(2)) }, "1 1i"),
        Row("root of 5 plus 12i", { root(int(5), int(12)) }, "3 2i"),
    )

    @Test
    fun `division and the square root stay in range and keep both parts`() = checkRows(ranges)

    @Test
    fun `the guide complex examples hold at value level`() = checkRows(examples)

    @Test
    fun `powers roots and polar forms keep each part exact`() = checkRows(more)
}
