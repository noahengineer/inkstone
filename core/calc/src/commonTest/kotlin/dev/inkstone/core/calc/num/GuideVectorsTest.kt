package dev.inkstone.core.calc.num

import dev.inkstone.core.calc.input.MetricConversions
import dev.inkstone.core.calc.input.ScientificConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The guide's own worked examples (research 04, `[printed]` or `[screen]` only), at the level this
 * package can answer them: the arithmetic underneath, then the display rounding the guide's setup
 * names — 10 significant digits for Norm, `roundPlaces` for Fix, `roundSignificant` for Sci.
 *
 * These are the only oracles in this package that came from the calculator rather than from a
 * library: every other table is Python. What they do **not** exercise is everything above `num`
 * — the evaluator's exact fractions and surds, angle units, the formatter's Norm 1/2 ranges and
 * the comma — so a row here passing says the numbers are right, not that the screen would be.
 * Where the real calculator would hold an exact form (`√2×3` is `3√2` until `SHIFT =`), the row
 * computes the decimal that `SHIFT =` or `S⇔D` shows.
 */
class GuideVectorsTest {
    private class Vector(val id: String, val actual: Dec?, val expected: String)

    private fun norm(x: Dec?): Dec = assertNotNull(x).roundSignificant(NORM_DIGITS)

    private fun constant(number: Int): Dec = d(assertNotNull(ScientificConstants.get(number)).value)

    private fun factor(number: Int): Dec = d(assertNotNull(MetricConversions.get(number)).factor)

    /** Functions, Norm 2 (10 significant digits). */
    private val functions = listOf(
        Vector("E-03 sinh 1", norm(DecMath.sinh(Dec.ONE)), "1.175201194"),
        Vector("E-04 acosh 1", norm(DecMath.acosh(Dec.ONE)), "0"),
        Vector("E-09 log 1000", norm(DecMath.log10(d("1000"))), "3"),
        Vector("E-10 log 2 16", norm(DecMath.log(Dec.TWO, d("16"))), "4"),
        Vector("E-12 log2 of 4 cubed", norm(DecMath.pow(d("4"), d("3"))?.let { DecMath.log(Dec.TWO, it) }), "6"),
        Vector("E-13 log2 4 then cubed", norm(DecMath.log(Dec.TWO, d("4"))?.let { DecMath.pow(it, d("3")) }), "8"),
        Vector("E-15 1.2 times ten cubed", norm(DecMath.pow(Dec.TEN, d("3"))?.let { d("1.2") * it }), "1200"),
        Vector("E-16 two to the four", norm(DecMath.pow(Dec.ONE + Dec.ONE, Dec.TWO + Dec.TWO)), "16"),
        Vector("E-17 five squared cubed", norm(DecMath.pow(d("5"), Dec.TWO)?.let { DecMath.pow(it, d("3")) }), "15625"),
        Vector("E-18 fifth root of 32", norm(DecMath.nthRoot(d("32"), 5)), "2"),
        Vector("E-22 cube roots", norm(sum(DecMath.cbrt(d("5")), DecMath.cbrt(d("-27")))), "-1.290024053"),
        Vector("E-23 reciprocals", norm(Dec.ONE / (Dec.ONE / d("3") - Dec.ONE / d("4"))), "12"),
        Vector("E-36 eight factorial", norm(DecMath.factorial(8)), "40320"),
        Vector("E-49 solve x squared minus 2", norm(DecMath.sqrt(Dec.TWO)), "1.414213562"),
        Vector("F-06 sin 45 degrees", norm(DecMath.sin(d("45") * Dec.PI / d("180"))), "0.7071067812"),
        Vector("B-06 99 root 999", norm(DecMath.sqrt(d("999"))?.let { d("99") * it }), "3129.089165"),
        Vector("C-01 pi over 6", norm(Dec.PI / d("6")), "0.5235987756"),
        Vector(
            "C-02 root 6 plus 2 root 3",
            norm(sum(DecMath.sqrt(d("6")), DecMath.sqrt(d("3"))?.let { it * Dec.TWO })),
            "5.913591358",
        ),
    )

    /** Fix and Sci (research 04 A-06…A-10, E-07, E-14, E-20, E-43, E-44). */
    private val formats = listOf(
        Vector("A-06 100 over 7 Fix 3", (d("100") / d("7")).roundPlaces(3), "14.286"),
        Vector("A-07 100 over 7 Fix 2", (d("100") / d("7")).roundPlaces(2), "14.29"),
        Vector("A-08 1 over 7 Sci 5", (Dec.ONE / d("7")).roundSignificant(5), "0.14286"),
        Vector("A-09 1 over 7 Sci 4", (Dec.ONE / d("7")).roundSignificant(4), "0.1429"),
        Vector("A-10 1 over 7 Sci 0", (Dec.ONE / d("7")).roundSignificant(NORM_DIGITS), "0.1428571429"),
        Vector("E-07 e to the 5 times 2 Sci 3", DecMath.exp(d("5"))?.let { it * Dec.TWO }?.roundSignificant(3), "297"),
        Vector("E-14 ln 90 Sci 3", DecMath.ln(d("90"))?.roundSignificant(3), "4.5"),
        Vector("E-20 root 2 times 3 Fix 3", DecMath.sqrt(Dec.TWO)?.let { it * d("3") }?.roundPlaces(3), "4.243"),
        Vector("E-43 10 over 3 times 3 Fix 3", (d("10") / d("3") * d("3")).roundPlaces(3), "10"),
        Vector("E-44 Rnd 10 over 3 times 3 Fix 3", (d("10") / d("3")).roundPlaces(3) * d("3"), "9.999"),
        Vector("A-03 1 over 200", norm(Dec.ONE / d("200")), "0.005"),
        Vector("H-01 1 over 800", norm(Dec.ONE / d("800")), "0.00125"),
    )

    /** CONST and CONV through the stored digits (research 04 E-52…E-55, H-07, H-08). */
    private val tables = listOf(
        Vector("E-51 C0", norm(constant(28)), "299792458"),
        Vector(
            "E-52 one over root eps0 mu0",
            norm(DecMath.sqrt(constant(32) * constant(33))?.let { Dec.ONE / it }),
            "299792458",
        ),
        Vector("E-53 5 cm to in", norm(d("5") / factor(2)), "1.968503937"),
        Vector("E-54 100 g to oz", norm(d("100") / factor(22)), "3.527396584"),
        Vector("E-55 minus 31 C to F", norm(d("-31") * factor(38) + d("32")), "-23.8"),
        Vector("H-07 356 F to C", norm((d("356") - d("32")) / factor(37)), "180"),
        Vector("H-08 120 km per h to m per s", norm(d("120") / factor(19)), "33.33333333"),
    )

    @Test
    fun `function examples from the guide`() = check(functions)

    @Test
    fun `Fix and Sci examples from the guide`() = check(formats)

    @Test
    fun `CONST and CONV examples from the guide`() = check(tables)

    @Test
    fun `GCD and LCM examples from the guide`() {
        assertEquals(7L, DecMath.gcd(28L, 35L), "E-45")
        assertEquals(45L, DecMath.lcm(9L, 15L), "E-46")
    }

    private fun sum(a: Dec?, b: Dec?): Dec = assertNotNull(a) + assertNotNull(b)

    private fun check(vectors: List<Vector>) {
        for (v in vectors) assertEquals(d(v.expected), v.actual, v.id)
    }

    private companion object {
        const val NORM_DIGITS = 10
    }
}
