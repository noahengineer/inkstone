package dev.inkstone.core.calc.value

import kotlin.test.Test

/**
 * Powers and roots: integer exponents exact while a record holds the answer, fraction exponents
 * exact only when the root is (`8^(1/3) = 2`, `(−8)^(1/3) = −2`), `√` the one root that makes a
 * surd, and `∛`/`ⁿ√` exact only for perfect powers (docs/CALCULATOR.md §3, research 02 §8.6,
 * §10.6, §13). The `E-` rows are the guide's printed results (research 04).
 */
class PowerRootTest {
    private val powers = listOf(
        Row("8 to the one third", { show(RealArith.pow(int(8), frac(1, 3))) }, "2"),
        Row("4 to the one half", { show(RealArith.pow(int(4), frac(1, 2))) }, "2"),
        Row("minus 8 to the one third", { show(RealArith.pow(int(-8), frac(1, 3))) }, "-2"),
        Row("4 to the three halves", { show(RealArith.pow(int(4), frac(3, 2))) }, "8"),
        Row("8 to the two thirds", { show(RealArith.pow(int(8), frac(2, 3))) }, "4"),
        Row("minus 8 to the two thirds", { show(RealArith.pow(int(-8), frac(2, 3))) }, "4"),
        Row("8 to the minus one third", { show(RealArith.pow(int(8), frac(-1, 3))) }, "1/2"),
        Row("27 eighths to the one third", { show(RealArith.pow(frac(27, 8), frac(1, 3))) }, "3/2"),
        Row("2 to the one half is not a surd", { show(RealArith.pow(int(2), frac(1, 2))) }, "~1.414213562"),
        Row("minus 2 to the one third", { show(RealArith.pow(int(-2), frac(1, 3))) }, "~-1.25992105"),
        Row("minus 8 to the one half", { show(RealArith.pow(int(-8), frac(1, 2))) }, "MATH"),
        Row("minus 32 to the decimal 0.2", { show(RealArith.pow(int(-32), dec("0.2"))) }, "~-2"),
        Row("2 to the 10", { show(RealArith.pow(int(2), int(10))) }, "1024"),
        Row("2 to the minus 3", { show(RealArith.pow(int(2), int(-3))) }, "1/8"),
        Row("2 to the 50 leaves the record", { show(RealArith.pow(int(2), int(50))) }, "~1125899907000000"),
        Row("1 plus root 2 cubed", { show(RealArith.cube(int(1) + rt(2))) }, "7+5√2"),
        Row("1 plus root 2 to the 6", { show(RealArith.powInt(int(1) + rt(2), 6)) }, "99+70√2"),
        Row("1 plus root 2 to the 7", { show(RealArith.powInt(int(1) + rt(2), 7)) }, "~478.002092"),
        Row("1 plus root 2 to the minus 1", { show(RealArith.powInt(int(1) + rt(2), -1)) }, "-1+√2"),
        Row("root 2 to the 4", { show(RealArith.pow(rt(2), int(4))) }, "4"),
        Row("root 2 cubed", { show(RealArith.cube(rt(2))) }, "2√2"),
        Row("zero to the zero", { show(RealArith.pow(int(0), int(0))) }, "MATH"),
        Row("zero to the minus 1", { show(RealArith.pow(int(0), int(-1))) }, "MATH"),
        Row("zero to the one half", { show(RealArith.pow(int(0), frac(1, 2))) }, "0"),
        Row("10 to the 100 overflows", { show(RealArith.pow(int(10), int(100))) }, "MATH"),
        Row("decimal 0.5 to the 1000 underflows", { show(RealArith.pow(dec("0.5"), int(1000))) }, "~0"),
        Row("E-15 1.2 times 10 cubed", { show(dec("1.2") * RealArith.pow(int(10), int(3)).ok()) }, "~1200"),
        Row("E-16 1 plus 1 to the 2 plus 2", { show(RealArith.pow(int(1) + int(1), int(2) + int(2))) }, "16"),
        Row("E-17 5 squared cubed", { show(RealArith.cube(RealArith.square(int(5)).ok())) }, "15625"),
    )

    private val roots = listOf(
        Row("root 8", { show(Roots.sqrt(int(8))) }, "2√2"),
        Row("root of one third", { show(Roots.sqrt(frac(1, 3))) }, "√3/3"),
        Row("root of four ninths", { show(Roots.sqrt(frac(4, 9))) }, "2/3"),
        Row("root 1000", { show(Roots.sqrt(int(1000))) }, "10√10"),
        Row("root of one thousandth needs denominator 100", { show(Roots.sqrt(frac(1, 1000))) }, "~0.0316227766"),
        Row("root 999999 needs radicand 111111", { show(Roots.sqrt(int(999999))) }, "~999.9995"),
        Row("root of minus 1", { show(Roots.sqrt(int(-1))) }, "MATH"),
        Row("root of decimal 0.25", { show(Roots.sqrt(dec("0.25"))) }, "~0.5"),
        Row("root of a surd is decimal", { show(Roots.sqrt(rt(2))) }, "~1.189207115"),
        Row("cube root 8", { show(Roots.cbrt(int(8))) }, "2"),
        Row("cube root minus 27", { show(Roots.cbrt(int(-27))) }, "-3"),
        Row("cube root 27 eighths", { show(Roots.cbrt(frac(27, 8))) }, "3/2"),
        Row("cube root 2", { show(Roots.cbrt(int(2))) }, "~1.25992105"),
        Row("E-18 fifth root of 32", { show(Roots.root(int(5), int(32))) }, "2"),
        Row("E-22 cube roots", { show(Roots.cbrt(int(5)).ok() + Roots.cbrt(int(-27)).ok()) }, "~-1.290024053"),
        Row("index 2 root 8 is not a surd", { show(Roots.root(int(2), int(8))) }, "~2.828427125"),
        Row("index 0", { show(Roots.root(int(0), int(8))) }, "MATH"),
        Row("index 2 root minus 4", { show(Roots.root(int(2), int(-4))) }, "MATH"),
        Row("index 3 root minus 8", { show(Roots.root(int(3), int(-8))) }, "-2"),
        Row("index minus 2 root 4", { show(Roots.root(int(-2), int(4))) }, "1/2"),
        Row("index one half root 3", { show(Roots.root(frac(1, 2), int(3))) }, "9"),
        Row("index three halves root minus 8", { show(Roots.root(frac(3, 2), int(-8))) }, "4"),
        Row("index 4 root 81 sixteenths", { show(Roots.root(int(4), frac(81, 16))) }, "3/2"),
    )

    /**
     * Review regression: research 02 §13 states `ˣ√y`'s range by the index's *value* — `y < 0` needs
     * `x = 2n+1` or `(2n+1)/m` — but a decimal index was turned into a rounded reciprocal first, so
     * `3,0 ˣ√ −8` and `1,5 ˣ√ −8` were Math ERROR where `3` and `3/2` give −2 and 4. A decimal index
     * is now read as the exact rational it is (as `DecMath.pow` already reads a decimal exponent),
     * and the answer is a decimal because the index was.
     */
    private val decimalIndices = listOf(
        Row("decimal index 3 root minus 8", { show(Roots.root(dec("3"), int(-8))) }, "~-2"),
        Row("decimal index 1.5 root minus 8", { show(Roots.root(dec("1.5"), int(-8))) }, "~4"),
        Row("decimal index 0.2 root minus 2", { show(Roots.root(dec("0.2"), int(-2))) }, "~-32"),
        Row("decimal index 3 root 27 is a decimal 3", { show(Roots.root(dec("3"), int(27))) }, "~3"),
        Row("decimal index minus 2 root 4", { show(Roots.root(dec("-2"), int(4))) }, "~0.5"),
        Row("decimal index 2 root minus 4", { show(Roots.root(dec("2"), int(-4))) }, "MATH"),
        Row("decimal index 2.5 is 5 halves", { show(Roots.root(dec("2.5"), int(-4))) }, "~1.741101127"),
        Row("exact index 5 halves agrees", { show(Roots.root(frac(5, 2), int(-4))) }, "~1.741101127"),
        Row("decimal index 0", { show(Roots.root(dec("0"), int(8))) }, "MATH"),
        Row("decimal index 2.5 root 32", { show(Roots.root(dec("2.5"), int(32))) }, "~4"),
    )

    @Test
    fun `a decimal index is read by its exact value`() = checkRows(decimalIndices)

    @Test
    fun `powers are exact while a record holds them and decimal otherwise`() = checkRows(powers)

    @Test
    fun `square roots make surds and other roots are exact only for perfect powers`() = checkRows(roots)
}
