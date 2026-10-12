package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.AngleUnit
import dev.inkstone.core.calc.AngleUnit.DEG
import dev.inkstone.core.calc.AngleUnit.GRA
import dev.inkstone.core.calc.AngleUnit.RAD
import dev.inkstone.core.calc.num.Dec
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * sin, cos and tan: the ROM's exact values at every multiple of 15° in every quadrant (research
 * 07 §4.4), keyed on the angle in each unit (docs/CALCULATOR.md §3, a GUESS pinned here), the
 * input limits of research 02 §13, and correctly rounded decimals everywhere else. Decimal
 * references are Python's `math` at double precision, compared at ten digits.
 */
class TrigTest {
    private fun sin(x: Real, unit: AngleUnit = DEG) = show(Trig.sin(x, unit))

    private fun cos(x: Real, unit: AngleUnit = DEG) = show(Trig.cos(x, unit))

    private fun tan(x: Real, unit: AngleUnit = DEG) = show(Trig.tan(x, unit))

    /** `π × n ÷ d` as the user types it, with Casio's π. */
    private fun pi(n: Long, d: Long): Real = DecimalReal(Dec.PI) * int(n) / int(d)

    private val degrees = listOf(
        Row("sin 0", { sin(int(0)) }, "0"),
        Row("sin 15", { sin(int(15)) }, "√6/4-√2/4"),
        Row("sin 30", { sin(int(30)) }, "1/2"),
        Row("sin 45", { sin(int(45)) }, "√2/2"),
        Row("sin 60", { sin(int(60)) }, "√3/2"),
        Row("sin 75", { sin(int(75)) }, "√6/4+√2/4"),
        Row("sin 90", { sin(int(90)) }, "1"),
        Row("sin 105", { sin(int(105)) }, "√6/4+√2/4"),
        Row("sin 150", { sin(int(150)) }, "1/2"),
        Row("sin 180", { sin(int(180)) }, "0"),
        Row("sin 210", { sin(int(210)) }, "-1/2"),
        Row("sin 255", { sin(int(255)) }, "-√6/4-√2/4"),
        Row("sin 270", { sin(int(270)) }, "-1"),
        Row("sin 330", { sin(int(330)) }, "-1/2"),
        Row("sin minus 30", { sin(int(-30)) }, "-1/2"),
        Row("sin 390", { sin(int(390)) }, "1/2"),
        Row("sin minus 345", { sin(int(-345)) }, "√6/4-√2/4"),
        Row("sin 8999999970", { sin(int(8999999970)) }, "-1/2"),
        Row("cos 0", { cos(int(0)) }, "1"),
        Row("cos 15", { cos(int(15)) }, "√6/4+√2/4"),
        Row("cos 60", { cos(int(60)) }, "1/2"),
        Row("cos 90", { cos(int(90)) }, "0"),
        Row("cos 120", { cos(int(120)) }, "-1/2"),
        Row("cos 135", { cos(int(135)) }, "-√2/2"),
        Row("cos 180", { cos(int(180)) }, "-1"),
        Row("cos 300", { cos(int(300)) }, "1/2"),
        Row("tan 0", { tan(int(0)) }, "0"),
        Row("tan 15", { tan(int(15)) }, "2-√3"),
        Row("tan 30", { tan(int(30)) }, "√3/3"),
        Row("tan 45", { tan(int(45)) }, "1"),
        Row("tan 60", { tan(int(60)) }, "√3"),
        Row("tan 75", { tan(int(75)) }, "2+√3"),
        Row("tan 105", { tan(int(105)) }, "-2-√3"),
        Row("tan 120", { tan(int(120)) }, "-√3"),
        Row("tan 135", { tan(int(135)) }, "-1"),
        Row("tan 150", { tan(int(150)) }, "-√3/3"),
        Row("tan 165", { tan(int(165)) }, "-2+√3"),
        Row("tan 180", { tan(int(180)) }, "0"),
        Row("tan 225", { tan(int(225)) }, "1"),
        Row("tan 90", { tan(int(90)) }, "MATH"),
        Row("tan 270", { tan(int(270)) }, "MATH"),
        Row("tan minus 90", { tan(int(-90)) }, "MATH"),
        Row("tan 450", { tan(int(450)) }, "MATH"),
        Row("E-01 sin 30 decimal", { sin(dec("30")) }, "1/2"),
        Row("within the tolerance is special", { sin(dec("30.0000000000001")) }, "1/2"),
        Row("outside the tolerance is decimal", { sin(dec("30.00001")) }, "~0.5000001511"),
        Row("sin 18 is not in the table", { sin(int(18)) }, "~0.3090169944"),
        Row("sin 1 degree", { sin(int(1)) }, "~0.01745240644"),
        Row("a large angle is reduced exactly", { sin(dec("123456789.123")) }, "~-0.1585544278"),
        Row("sin 9E9 is out of range", { sin(dec("9E9")) }, "MATH"),
        Row("sin minus 9E9 is out of range", { sin(dec("-9E9")) }, "MATH"),
        Row("cos just inside the range", { cos(dec("8999999999")) }, "~0.9998476952"),
    )

    private val otherUnits = listOf(
        Row("rad sin pi over 6", { sin(pi(1, 6), RAD) }, "1/2"),
        Row("rad sin pi", { sin(pi(1, 1), RAD) }, "0"),
        Row("rad cos pi", { cos(pi(1, 1), RAD) }, "-1"),
        Row("rad cos 2 pi over 3", { cos(pi(2, 3), RAD) }, "-1/2"),
        Row("rad tan pi over 4", { tan(pi(1, 4), RAD) }, "1"),
        Row("rad tan pi over 2", { tan(pi(1, 2), RAD) }, "MATH"),
        Row("rad sin 5 pi over 12", { sin(pi(5, 12), RAD) }, "√6/4+√2/4"),
        Row("rad sin 19 pi is still special", { sin(pi(19, 1), RAD) }, "0"),
        Row("rad sin 1", { sin(int(1), RAD) }, "~0.8414709848"),
        Row("rad sin 0 exact", { sin(int(0), RAD) }, "0"),
        Row("rad limit", { sin(dec("157079632.7"), RAD) }, "MATH"),
        Row("gra sin 100", { sin(int(100), GRA) }, "1"),
        Row("gra sin 50", { sin(int(50), GRA) }, "√2/2"),
        Row("gra sin 50 thirds", { sin(frac(50, 3), GRA) }, "√6/4-√2/4"),
        Row("gra cos 200", { cos(int(200), GRA) }, "-1"),
        Row("gra tan 50", { tan(int(50), GRA) }, "1"),
        Row("gra tan 100", { tan(int(100), GRA) }, "MATH"),
        Row("gra past 10000 is decimal but still exact in value", { sin(int(10100), GRA) }, "~1"),
        Row("gra limit", { sin(dec("1E10"), GRA) }, "MATH"),
    )

    @Test
    fun `degrees give the exact table in every quadrant and decimals elsewhere`() = checkRows(degrees)

    @Test
    fun `radians and grads key the same table on their own steps`() = checkRows(otherUnits)

    /**
     * Review regressions. The special-angle test used to run on the *unreduced* angle with a
     * tolerance relative to it, so near 9×10⁹° it accepted anything within ~10⁻³° of a 15° step:
     * 8999999985,0001° (≡ 345,0001°) came back as the table's sin 345°, wrong in the seventh digit,
     * and 0,0001° past the pole at 8999999910° was Math ERROR. And the reduction itself rounded for
     * a negative angle, so a tiny negative angle became a whole turn and its sine 0. References are
     * Python's decimal module at 80 digits (true π), compared at ten digits.
     */
    private val reductions = listOf(
        Row("near 9E9 a non special angle stays decimal", { sin(dec("8999999985.0001")) }, "~-0.2588173592"),
        Row("near 9E9 just past a pole is finite", { tan(dec("8999999910.0001")) }, "~-572957.7951"),
        Row("near 9E9 cos control", { cos(dec("8999999970.001")) }, "~0.8660341303"),
        Row("near 9E9 an exact multiple is still special", { sin(int(8999999985)) }, "-√6/4+√2/4"),
        Row("a tiny negative angle keeps its sine", { sin(dec("-1E-15")) }, "~-1.745329252E-17"),
        Row("a tiny negative tangent", { tan(dec("-1E-20")) }, "~-1.745329252E-22"),
        Row("a tiny negative grad angle", { sin(dec("-1E-15"), GRA) }, "~-1.570796327E-17"),
        Row("minus 360 is a whole turn", { sin(int(-360)) }, "0"),
        Row("minus 345 after reduction", { cos(int(-345)) }, "√6/4+√2/4"),
        Row("sin minus 1000.5", { sin(dec("-1000.5")) }, "~0.9832549076"),
        Row("cos minus 725.25", { cos(dec("-725.25")) }, "~0.9958049276"),
        Row("tan minus 1000.5", { tan(dec("-1000.5")) }, "~5.395517174"),
        Row("a small negative angle with many digits", { sin(dec("-0.000123456789012")) }, "~-0.000002154727452"),
        Row("near minus 9E9 with decimals", { sin(dec("-8999999999.12345")) }, "~0.01529808679"),
        Row("grads minus 1000.5", { sin(dec("-1000.5"), GRA) }, "~0.007853900889"),
        Row("grads past the special range", { sin(dec("-12345.678"), GRA) }, "~0.7534463521"),
    )

    @Test
    fun `the special angle test and the reduction work on the exactly reduced angle`() = checkRows(reductions)

    /**
     * Added in review: every exact value's decimal is the ROM's own float twin (research 07 §4.4),
     * digit for digit at 15 — the value `S⇔D` and every later decimal step start from.
     */
    @Test
    fun `each special value carries the ROM float twin`() {
        val twins = listOf(
            Trig.sin(int(15), DEG) to "0.258819045102521",
            Trig.sin(int(30), DEG) to "0.5",
            Trig.sin(int(45), DEG) to "0.707106781186548",
            Trig.sin(int(60), DEG) to "0.866025403784439",
            Trig.sin(int(75), DEG) to "0.965925826289068",
            Trig.tan(int(15), DEG) to "0.267949192431123",
            Trig.tan(int(30), DEG) to "0.577350269189626",
            Trig.tan(int(60), DEG) to "1.73205080756888",
            Trig.tan(int(75), DEG) to "3.73205080756888",
        )
        for ((value, twin) in twins) assertEquals(twin, value.ok().dec.toString())
    }

    @Test
    fun `past twenty pi a radian angle is no longer special`() {
        // 21π with Casio's π is 65,9734457253858, which is 1,4199228449×10⁻¹³ past the true 21π, so its
        // sine is minus that (Python's decimal module at 50 digits). A Double gets −1,43×10⁻¹³:
        // the product 21 × 3,1415926535898 is already wrong in binary in its 15th digit.
        assertEquals("~-1.419922845E-13", sin(pi(21, 1), RAD))
    }
}
