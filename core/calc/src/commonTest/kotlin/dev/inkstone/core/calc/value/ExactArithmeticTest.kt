package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.num.Dec
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The four operations and their exactness: fractions held to 14 digits, surds to two terms with
 * the record's limits, demotion to a decimal at the step that breaks them, and decimals winning
 * every mix (research 02 §8.5–8.6, docs/CALCULATOR.md §3).
 *
 * Every surd row is one of the guide's own examples (02 §8.6, the [S570] table it quotes, and
 * 04 B-03, B-05, B-06, C-02, E-20). The decimal they fall back to is compared at ten significant
 * digits — what the screen shows — because *which* fifteenth digit the firmware's fallback
 * produces is a guess (RealArith's KDoc).
 */
class ExactArithmeticTest {
    private val surds = listOf(
        Row("B-05", { show(int(10) * rt(2) + int(15) * (int(3) * rt(3))) }, "45√3+10√2"),
        Row("B-06 99 root 999 is 297 root 111", { show(int(99) * rt(999)) }, "~3129.089165"),
        Row("35 root 2 times 3 is 105 root 2", { show(int(35) * rt(2) * int(3)) }, "~148.492424"),
        Row("150 root 2 over 25", { show(int(150) * rt(2) / int(25)) }, "~8.485281374"),
        Row("2 times 3 minus 2 root 5", { show(int(2) * (int(3) - int(2) * rt(5))) }, "6-4√5"),
        Row("23 times 5 minus 2 root 3", { show(int(23) * (int(5) - int(2) * rt(3))) }, "~35.32566285"),
        Row("15 times 10 root 2 plus 3 root 3", { show(int(15) * (int(10) * rt(2) + int(3) * rt(3))) }, "~290.0743207"),
        Row("root 2 plus root 3 plus root 8", { show(rt(2) + rt(3) + rt(8)) }, "√3+3√2"),
        Row("root 2 plus root 3 plus root 6", { show(rt(2) + rt(3) + rt(6)) }, "~5.595754113"),
        Row(
            "a three term intermediate stays decimal",
            { show((int(1) + rt(2) + rt(3)) * (int(1) - rt(2) - rt(3))) },
            "~-8.898979486",
        ),
        Row("2 root 3 times 4", { show(int(2) * rt(3) * int(4)) }, "8√3"),
        Row("root 3 over 11 plus root 2 over 10", { show(rt(3) / int(11) + rt(2) / int(10)) }, "√3/11+√2/10"),
        Row("B-03 2 plus root 2 over 1 plus root 2", { show((int(2) + rt(2)) / (int(1) + rt(2))) }, "√2"),
        Row("C-02 root 2 plus 2 times root 3", { show((rt(2) + int(2)) * rt(3)) }, "√6+2√3"),
        Row("C-02 its decimal", { ((rt(2) + int(2)) * rt(3)).dec.roundSignificant(10).toString() }, "5.913591358"),
        Row("E-20 root 2 times 3", { show(rt(2) * int(3)) }, "3√2"),
        Row("root 2 times root 2 is rational", { show(rt(2) * rt(2)) }, "2"),
        Row("root 6 over root 2", { show(rt(6) / rt(2)) }, "√3"),
        Row("one over root 2 is rationalised", { show(int(1) / rt(2)) }, "√2/2"),
        Row("one over 2 minus root 3", { show(int(1) / (int(2) - rt(3))) }, "2+√3"),
        Row("negating keeps the rational term first", { show(-(int(2) - rt(3))) }, "-2+√3"),
        Row("abs of 1 minus root 2", { show(RealArith.abs(int(1) - rt(2))) }, "-1+√2"),
    )

    private val fractions = listOf(
        Row("A-01 1 over 200", { show(int(1) / int(200)) }, "1/200"),
        Row("14 over 10 times 2", { show(int(14) / int(10) * int(2)) }, "14/5"),
        Row("C-05 two thirds plus one half", { show(frac(2, 3) + frac(1, 2)) }, "7/6"),
        Row("C-08 4 minus 3 and a half", { show(int(4) - (int(3) + frac(1, 2))) }, "1/2"),
        Row("E-23 reciprocals", { show(RealArith.reciprocal(frac(1, 3) - frac(1, 4)).ok()) }, "12"),
        Row("fourteen digits stay exact", { show(frac(1234567, 7654321)) }, "1234567/7654321"),
        Row("fifteen digits become decimal", { show(frac(12345678, 7654321)) }, "~1.612903091"),
        Row("thirteen digit integer is exact", { show(int(9999999999999)) }, "9999999999999"),
        Row("fourteen digit integer is decimal", { show(int(10000000000000)) }, "~10000000000000"),
        Row("a sum past the limit falls back", { show(frac(1, 1234567) + frac(1, 7654321)) }, "~9.406457524E-7"),
        Row("a decimal and a fraction mix to decimal", { show(dec("0.5") + frac(1, 2)) }, "~1"),
        Row("one third plus a half decimal", { show(frac(1, 3) + dec("0.5")) }, "~0.8333333333"),
        Row("C-10 150 times 20 percent", { show(int(150) * RealArith.percent(int(20)).ok()) }, "30"),
        Row("C-11 660 over 880 percent", { show(int(660) / RealArith.percent(int(880)).ok()) }, "75"),
        Row("C-12 add 15 percent", { show(int(2500) + int(2500) * RealArith.percent(int(15)).ok()) }, "2875"),
        Row("C-13 take 25 percent", { show(int(3500) - int(3500) * RealArith.percent(int(25)).ok()) }, "2625"),
        Row("F-04 bracketed minus 2 squared", { show(RealArith.square(int(-2)).ok()) }, "4"),
        Row("F-04 minus 2 squared", { show(-RealArith.square(int(2)).ok()) }, "-4"),
    )

    /**
     * Added in review: research 07 T10's closure probes under the rules docs/CALCULATOR.md §3 chose
     * (these pin that GUESS until the emulator settles it), and the guide's printed examples this
     * package owns that had no row — E-37's Abs, E-43's `10÷3×3`, E-23 keyed as printed
     * (`(3⁻¹−4⁻¹)⁻¹`), and quirk Q1 of research 07 §5, Casio's π.
     */
    private val reviewed = listOf(
        Row("T10 three surds", { show(rt(2) + rt(3) + rt(5)) }, "~5.382332347"),
        Row("T10 root 2 plus root 3 squared", { show(RealArith.square(rt(2) + rt(3)).ok()) }, "5+2√6"),
        Row("T10 one over root 2 plus root 3", { show(int(1) / (rt(2) + rt(3))) }, "√3-√2"),
        Row("T10 root of a third", { show(Roots.sqrt(int(1) / int(3))) }, "√3/3"),
        Row("T10 root of a typed 0.5", { show(Roots.sqrt(dec("0.5"))) }, "~0.7071067812"),
        Row("T10 root 999", { show(rt(999)) }, "3√111"),
        Row("T10 root 1000", { show(rt(1000)) }, "10√10"),
        Row("E-37 abs 2 minus 7 times 2", { show(RealArith.abs(int(2) - int(7)) * int(2)) }, "10"),
        Row("E-43 10 over 3 times 3 is exact", { show(int(10) / int(3) * int(3)) }, "10"),
        Row(
            "E-23 as keyed",
            { show(RealArith.reciprocal(RealArith.reciprocal(int(3)).ok() - RealArith.reciprocal(int(4)).ok())) },
            "12",
        ),
        Row("Q1 Casio pi", { show(DecimalReal(Dec.PI) - dec("3.14159265358")) }, "~9.8E-12"),
    )

    @Test
    fun `the closure probes and the remaining printed examples`() = checkRows(reviewed)

    @Test
    fun `surds stay exact within two terms and the record limits and demote at the step`() = checkRows(surds)

    @Test
    fun `fractions stay exact to fourteen digits and decimals win every mix`() = checkRows(fractions)

    @Test
    fun `division by zero is a math error for every kind of value`() {
        RealArith.divide(int(14), int(0)).assertError(CalcError.MATH, "14 over 0")
        RealArith.divide(rt(2), dec("0")).assertError(CalcError.MATH, "root 2 over decimal 0")
        RealArith.reciprocal(int(0)).assertError(CalcError.MATH, "reciprocal of 0")
        Reals.fraction(1, 0).assertError(CalcError.MATH, "fraction with zero denominator")
    }

    @Test
    fun `results at or past 1E100 are math errors and below 1E-99 they are zero`() {
        RealArith.multiply(dec("1E99"), int(10)).assertError(CalcError.MATH, "1E99 times 10")
        RealArith.add(dec("5E99"), dec("5E99")).assertError(CalcError.MATH, "5E99 plus 5E99")
        assertEquals("~9.999999999E99", show(RealArith.multiply(dec("9.999999999E98"), int(10)).ok()))
        assertEquals("~0", show(RealArith.divide(dec("1E-99"), int(10)).ok()))
        RealArith.square(dec("1E50")).assertError(CalcError.MATH, "1E50 squared")
    }

    @Test
    fun `the surd decimal is correctly rounded from the exact sum`() {
        // 2 − √3 = 0,26794919243112270647…: one rounding, not two, gives the ROM's float twin
        // (research 07 §4.4: tan 15° = 0,267949192431123).
        assertEquals("0.267949192431123", (int(2) - rt(3)).dec.toString())
        assertEquals("1.4142135623731", rt(2).dec.toString())
        assertEquals("0.258819045102521", ((rt(6) - rt(2)) / int(4)).dec.toString())
    }

    @Test
    fun `degrees minutes seconds survive addition and scaling but not squaring`() {
        val a = Dms.of(int(2), int(20), int(30)).ok()
        val b = Dms.of(int(0), int(39), int(30)).ok()
        assertEquals("~3dms", show(a + b))
        assertEquals("~4.683333333dms", show(a * int(2)))
        assertEquals("~1.170833333dms", show(a / int(2)))
        assertEquals("~3.341666667dms", show(a + int(1)))
        assertEquals("~5.483402778", show(RealArith.square(a).ok()))
    }
}
