package dev.inkstone.core.calc.value

import kotlin.test.Test

/**
 * x!, nPr, nCr, GCD, LCM, ÷R and FACT (research 02 §10.11–10.13 and §13's ranges; the `C-`, `E-`
 * and `H-` rows are research 04's printed results). Big references are Python's exact integers.
 */
class IntegerFunctionsTest {
    private fun division(r: Res<Division>): String = when (r) {
        is Res.Err -> r.error.name

        is Res.Ok -> when (val d = r.value) {
            is Division.WithRemainder -> "${show(d.quotient)} R ${show(d.remainder)}"
            is Division.Ordinary -> "plain ${show(d.quotient)}"
        }
    }

    private fun factors(r: Res<Factorization>): String = when (r) {
        is Res.Err -> r.error.name

        is Res.Ok -> {
            val powers = r.value.powers.map { if (it.exponent == 1) "${it.prime}" else "${it.prime}^${it.exponent}" }
            val leftover = if (r.value.leftover == 1L) emptyList() else listOf("(${r.value.leftover})")
            (powers + leftover).joinToString(" ")
        }
    }

    private val counting = listOf(
        Row("E-36 5 plus 3 factorial", { show(IntegerFunctions.factorial(int(5) + int(3))) }, "40320"),
        Row("0 factorial", { show(IntegerFunctions.factorial(int(0))) }, "1"),
        Row("15 factorial is exact", { show(IntegerFunctions.factorial(int(15))) }, "1307674368000"),
        Row("16 factorial leaves the record", { show(IntegerFunctions.factorial(int(16))) }, "~20922789890000"),
        Row("69 factorial", { show(IntegerFunctions.factorial(int(69))) }, "~1.711224524E98"),
        Row("70 factorial", { show(IntegerFunctions.factorial(int(70))) }, "MATH"),
        Row("minus 1 factorial", { show(IntegerFunctions.factorial(int(-1))) }, "MATH"),
        Row("2.5 factorial", { show(IntegerFunctions.factorial(dec("2.5"))) }, "MATH"),
        Row("decimal 5 factorial", { show(IntegerFunctions.factorial(dec("5"))) }, "~120"),
        Row("E-41 10 P 4", { show(IntegerFunctions.permutations(int(10), int(4))) }, "5040"),
        Row("E-42 10 C 4", { show(IntegerFunctions.combinations(int(10), int(4))) }, "210"),
        Row("5 P 0", { show(IntegerFunctions.permutations(int(5), int(0))) }, "1"),
        Row("5 C 5", { show(IntegerFunctions.combinations(int(5), int(5))) }, "1"),
        Row("4 P 5", { show(IntegerFunctions.permutations(int(4), int(5))) }, "MATH"),
        Row("n of 1E10", { show(IntegerFunctions.permutations(int(10_000_000_000), int(1))) }, "MATH"),
        Row("n just under 1E10", { show(IntegerFunctions.permutations(int(9_999_999_999), int(1))) }, "9999999999"),
        Row("69 P 69", { show(IntegerFunctions.permutations(int(69), int(69))) }, "~1.711224524E98"),
        Row("70 P 70", { show(IntegerFunctions.permutations(int(70), int(70))) }, "MATH"),
        Row("100 C 50", { show(IntegerFunctions.combinations(int(100), int(50))) }, "~1.008913445E29"),
        Row("400 C 50 fails on its numerator", { show(IntegerFunctions.combinations(int(400), int(50))) }, "MATH"),
        Row("200 C 197", { show(IntegerFunctions.combinations(int(200), int(197))) }, "1313400"),
        Row("E-45 GCD 28 35", { show(IntegerFunctions.gcd(int(28), int(35))) }, "7"),
        Row("E-46 LCM 9 15", { show(IntegerFunctions.lcm(int(9), int(15))) }, "45"),
        Row("GCD of a negative", { show(IntegerFunctions.gcd(int(-12), int(18))) }, "6"),
        Row("GCD at 1E10", { show(IntegerFunctions.gcd(int(10_000_000_000), int(2))) }, "MATH"),
        Row("GCD of a fraction", { show(IntegerFunctions.gcd(frac(5, 2), int(5))) }, "MATH"),
        Row("LCM of a negative", { show(IntegerFunctions.lcm(int(-1), int(2))) }, "MATH"),
        Row(
            "LCM past a Long",
            { show(IntegerFunctions.lcm(int(9_999_999_999), int(9_999_999_998))) },
            "~99999999970000000000",
        ),
    )

    private val remainders = listOf(
        Row("C-19 5 R 2", { division(IntegerFunctions.divideWithRemainder(int(5), int(2))) }, "2 R 1"),
        Row("H-03 75 R 4", { division(IntegerFunctions.divideWithRemainder(int(75), int(4))) }, "18 R 3"),
        Row("C-22", { show(IntegerFunctions.divideWithRemainder(int(17), int(6)).ok().quotient) }, "2"),
        Row("7 halves R 1", { division(IntegerFunctions.divideWithRemainder(frac(7, 2), int(1))) }, "3 R 1/2"),
        Row("6 R 3", { division(IntegerFunctions.divideWithRemainder(int(6), int(3))) }, "2 R 0"),
        Row("2 R 5", { division(IntegerFunctions.divideWithRemainder(int(2), int(5))) }, "0 R 2"),
        Row("decimal 7.5 R 2", { division(IntegerFunctions.divideWithRemainder(dec("7.5"), int(2))) }, "~3 R ~1.5"),
        Row("C-24", { division(IntegerFunctions.divideWithRemainder(int(-5), int(2))) }, "plain -5/2"),
        Row(
            "C-23 a very large dividend divides",
            { division(IntegerFunctions.divideWithRemainder(int(20_000_000_000), int(17))) },
            "plain 20000000000/17",
        ),
        Row("a surd divides", { division(IntegerFunctions.divideWithRemainder(rt(8), int(2))) }, "plain √2"),
        Row("R 0", { division(IntegerFunctions.divideWithRemainder(int(5), int(0))) }, "MATH"),
    )

    private val factorisations = listOf(
        Row("C-25 1014", { factors(IntegerFunctions.factorize(int(1014))) }, "2 3 13^2"),
        Row("C-26 4104676", { factors(IntegerFunctions.factorize(int(4104676))) }, "2^2 (1026169)"),
        Row("ten nines", { factors(IntegerFunctions.factorize(int(9_999_999_999))) }, "3^2 11 41 271 (9091)"),
        Row("a lone prime past 997", { factors(IntegerFunctions.factorize(int(1013))) }, "(1013)"),
        Row("997 itself", { factors(IntegerFunctions.factorize(int(997))) }, "997"),
        Row("1", { factors(IntegerFunctions.factorize(int(1))) }, ""),
        Row("an integer valued decimal", { factors(IntegerFunctions.factorize(dec("1014"))) }, "2 3 13^2"),
        Row("0", { factors(IntegerFunctions.factorize(int(0))) }, "MATH"),
        Row("a negative", { factors(IntegerFunctions.factorize(int(-6))) }, "MATH"),
        Row("a fraction", { factors(IntegerFunctions.factorize(frac(1, 2))) }, "MATH"),
        Row("eleven digits", { factors(IntegerFunctions.factorize(int(10_000_000_000))) }, "MATH"),
    )

    @Test
    fun `factorials permutations combinations GCD and LCM follow the documented ranges`() = checkRows(counting)

    @Test
    fun `remainder division gives quotient and remainder or falls back to division`() = checkRows(remainders)

    @Test
    fun `FACT divides out primes to 997 and brackets the rest`() = checkRows(factorisations)
}
