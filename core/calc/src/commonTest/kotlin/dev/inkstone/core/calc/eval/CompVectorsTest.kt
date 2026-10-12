package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.AngleUnit
import dev.inkstone.core.calc.InputOutput
import dev.inkstone.core.calc.NumberFormat
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.ComplexValue
import dev.inkstone.core.calc.value.FractionReal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun int(n: Long) = FractionReal(n, 1)

/** E-47's second CALC run: A = 7, B = 20. */
private fun secondCalc(memory: Memory) {
    memory.variables[Variable.A] = int(7)
    memory.variables[Variable.B] = int(20)
}

/** Research 04 §A: display formats. The value is checked through the format's own rounding. */
private val DISPLAY = listOf(
    Case("A-01", "1/200"),
    Case("A-02", "~0.005", keys = "1 ÷ 2 0 0", setup = Setup(InputOutput.MATH_LINE, format = NumberFormat.Norm(1))),
    Case("A-03", "~0.005", keys = "1 ÷ 2 0 0", setup = Setup(io = InputOutput.MATH_LINE)),
    Case("A-04", "~0.005", setup = line(NumberFormat.Norm(1))),
    Case("A-05", "~0.005", keys = "1 ÷ 2 0 0", natural = false),
    Case("A-06", "~14.286", setup = line(NumberFormat.Fix(3))),
    Case("A-07", "~14.29", keys = "1 0 0 ÷ 7", natural = false, setup = line(NumberFormat.Fix(2))),
    Case("A-08", "~0.14286", setup = line(NumberFormat.Sci(5))),
    Case("A-09", "~0.1429", keys = "1 ÷ 7", natural = false, setup = line(NumberFormat.Sci(4))),
    Case("A-10", "~0.1428571429", keys = "1 ÷ 7", natural = false, setup = line(NumberFormat.Sci(0))),
    Case("G-01", "1/200", keys = "1 ÷ 2 0 0"),
    Case("H-01", "1/800"),
    Case("H-02", "2/3", setup = math(NumberFormat.Fix(2))),
)

/** Research 04 §B and §C: input forms, fractions, surds, %, DMS, ÷R. */
private val BASICS = listOf(
    Case("B-01", "120"),
    Case("B-02", "120"),
    Case("B-03", "√2"),
    Case("B-04", "1+√42/6"),
    Case("B-05", "45√3+10√2"),
    Case("B-06", "~3129.089165"),
    Case("C-01", "~0.5235987756", pi = true),
    Case("C-02", "√6+2√3"),
    Case("C-03", "~0.2"),
    Case("C-04", "1/5"),
    Case("C-05", "7/6"),
    Case("C-06", "7/6"),
    Case("C-07", "7/6"),
    Case("C-08", "1/2"),
    Case("C-09", "1/2"),
    // The whole number's sign covers the fraction too: −3½ is −7/2, not −3 + ½ (Structures.mixed).
    Case("mixed fraction with a negative whole part", "-7/2", keys = "MIXED (−) 3 ▶ 1 ▼ 2"),
    Case("C-10", "30"),
    Case("C-11", "75"),
    Case("C-12", "2875"),
    Case("C-13", "2625"),
    Case("C-14", "~3dms"),
    Case("C-15", "~2.255dms"),
    Case("C-16", "9"),
    Case("C-16a", "6", keys = "3 + 3"),
    Case("C-27a", "2"),
    Case("C-27b", "4"),
    Case("C-27c", "6"),
    Case("C-28", "14"),
    Case("C-28b", "5", keys = "4 × 3 − 7"),
    // ENG and FACT act on these results afterwards (the formatter's job); the values themselves:
    Case("C-17", "1234"),
    Case("C-18", "123"),
    Case("C-25", "1014"),
    Case("C-26", "4104676"),
    Case("C-19", "2;R=1"),
    Case("C-20", "2;R=1"),
    Case("C-22", "12"),
    // C-23 and C-24 print no value: an ordinary division, which MathO keeps as a fraction (INFERRED).
    Case("C-23", "20000000000/17"),
    Case("C-24", "-5/2"),
    Case("H-03", "18;R=3"),
    Case("F-02b", "14/5"),
    Case("F-04a", "4"),
    Case("F-04b", "-4"),
)

/** Research 04 §D: Ans, PreAns and variables. */
private val MEMORY = listOf(
    Case("D-01", "~0.4", prepare = { it.ans = int(12) }),
    Case("D-02", "210", prepare = { it.ans = int(579) }),
    Case("D-03", "2", prepare = {
        it.ans = int(1)
        it.preAns = int(1)
    }),
    Case("D-03b", "3", keys = "Ans + PreAns", prepare = {
        it.ans = int(2)
        it.preAns = int(1)
    }),
    Case("D-02a", "579"),
    Case("D-04a", "8"),
    Case("D-04b", "0"),
    Case("D-04", "80", prepare = { it.variables[Variable.A] = int(8) }),
    Case("D-05a", "50"),
    Case("D-05b", "15"),
    Case("E-47", "25", prepare = {
        it.variables[Variable.A] = int(5)
        it.variables[Variable.B] = int(10)
    }),
    Case("E-47b", "41", keys = "3 A + B", prepare = ::secondCalc),
    Case("H-04", "116", prepare = {
        it.variables[Variable.A] = int(6)
        it.variables[Variable.B] = int(2)
    }),
    // A complex Ans left by CMPLX is read as its real part in COMP (Scalars.stored, a GUESS).
    Case("complex Ans outside CMPLX", "3", keys = "Ans", prepare = { it.ans = ComplexValue(int(3), int(4)) }),
)

/** Research 04 §E (not calculus), §F and §H: functions, constants, conversions. */
private val FUNCTIONS = listOf(
    Case("E-01", "~0.5"),
    Case("E-02", "~30"),
    Case("E-03", "~1.175201194"),
    Case("E-04", "~0"),
    Case("E-05", "~90"),
    Case("E-06", "45"),
    Case("E-07", "~297", setup = math(NumberFormat.Sci(3))),
    Case("E-08", "~297", setup = line(NumberFormat.Sci(3))),
    Case("E-09", "~3"),
    Case("E-10", "~4"),
    Case("E-11", "~4"),
    Case("E-12", "~6"),
    Case("E-13", "~8"),
    Case("E-14", "~4.5", setup = math(NumberFormat.Sci(3))),
    Case("E-15", "~1200"),
    Case("E-16", "16"),
    Case("E-17", "15625"),
    Case("E-18", "2"),
    Case("E-19", "2"),
    Case("E-20", "3√2", setup = math(NumberFormat.Fix(3))),
    Case("E-21", "~4.243", setup = line(NumberFormat.Fix(3))),
    Case("E-22", "~-1.290024053"),
    Case("E-23", "~12"),
    Case("E-36", "40320"),
    Case("E-37", "10"),
    Case("E-38", "10"),
    Case("E-41", "5040"),
    Case("E-42", "210"),
    Case("E-43", "~10", setup = line(NumberFormat.Fix(3))),
    Case("E-44", "~9.999", setup = line(NumberFormat.Fix(3))),
    // Rnd rounds the stored value to the display setting (research 02 §10.11): ×3 shows the cut.
    Case("Rnd Norm", "~0.9999999999", keys = "Rnd 1 ÷ 3 ) × 3"),
    Case("Rnd Sci 2", "~0.99", keys = "Rnd 1 ÷ 3 ) × 3", setup = math(NumberFormat.Sci(2))),
    Case("Rnd Sci 0", "~0.9999999999", keys = "Rnd 1 ÷ 3 ) × 3", setup = math(NumberFormat.Sci(0))),
    Case("Rnd Fix 0", "~3", keys = "Rnd 1 , 4 ) × 3", setup = math(NumberFormat.Fix(0))),
    Case("E-45", "7"),
    Case("E-46", "45"),
    Case("E-51", "~299792458"),
    Case("E-52", "~299792458"),
    Case("E-53", "~1.968503937"),
    Case("E-54", "~3.527396584"),
    Case("E-55", "~-23.8"),
    Case("F-05", "~15.5"),
    Case("F-06", "~0.7071067812"),
    Case("H-07", "180"),
    Case("H-08", "100/3"),
)

/**
 * Linear output: a division of whole numbers is a decimal (04 C-03), but fraction arithmetic stays
 * fraction arithmetic (C-04, C-07) — the GUESS of Session.lineQuotient for ÷, x⁻¹, % and powers.
 */
private val LINEAR_FRACTIONS = listOf(
    Case("fraction divided", "1/6", keys = "1 FRAC 2 ÷ 3", natural = false),
    Case("fraction inverted", "3/2", keys = "( 2 FRAC 3 ) x⁻¹", natural = false),
    Case("fraction percent", "1/200", keys = "( 1 FRAC 2 ) %", natural = false),
    Case("whole number percent", "~0.05", keys = "5 %", natural = false),
    Case("fraction to a negative power", "9/4", keys = "( 2 FRAC 3 ) ^( (−) 2 )", natural = false),
    Case("integer to a negative power", "~0.125", keys = "2 ^( (−) 3 )", natural = false),
    Case("integer inverted", "~0.25", keys = "4 x⁻¹", natural = false),
    Case("whole quotient", "3", keys = "6 ÷ 2", natural = false),
    Case("square root", "~1.414213562", keys = "√ 2 )", natural = false),
)

/** Research 04 E-33…E-35 and F-01: Pol and Rec. */
private val COORDINATES = listOf(
    Case("E-33", "r=2;θ=45"),
    // In linear output √2 is a decimal, so both values are too; they show as 2 and 45 all the same.
    Case("E-34", "r=~2;θ=~45"),
    Case("E-35", "X=1;Y=1"),
    // F-01's keys are INFERRED and its input is cut off at 15 characters; research 06 §5.14 reads the
    // same screen as Pol(√2;√2) in Rad, which is what this types.
    Case("F-01", "r=2;θ=~0.7853981634", keys = "Pol √ 2 ▶ ; √ 2 ▶ )", setup = Setup(angle = AngleUnit.RAD), pi = true),
)

/**
 * Every COMP example in research 04 that is a calculation, typed with the guide's keys and
 * compared with the printed result. Ran#, RanInt#, FACT, ENG and the CALC/SOLVE screens are
 * checked in their own tests.
 */
class CompVectorsTest {
    @Test
    fun `display format examples A give the printed values`() = verify(DISPLAY)

    @Test
    fun `input and basic calculation examples B and C give the printed values`() = verify(BASICS)

    @Test
    fun `memory examples D give the printed values`() = verify(MEMORY)

    @Test
    fun `function constant and conversion examples E F and H give the printed values`() = verify(FUNCTIONS)

    @Test
    fun `linear output keeps typed fractions exact and makes divisions of integers decimal`() = verify(LINEAR_FRACTIONS)

    @Test
    fun `Pol and Rec answer both values and store them in X and Y`() {
        verify(COORDINATES)
        val memory = Memory()
        evaluate(GUIDE.getValue("E-33").keys, true, context(memory = memory))
        assertEquals("2", text(memory.variables.getValue(Variable.X)))
        assertEquals("45", text(memory.variables.getValue(Variable.Y)))
    }

    @Test
    fun `Ran draws three decimal places and RanInt stays in its range`() {
        val random = kotlin.random.Random(SEED)
        repeat(DRAWS) {
            val ran = evaluate("1 0 0 0 Ran#", true, context(random = random))
            val n = text(ran, NumberFormat.Norm(2)).toLong()
            assertTrue(n in 0L..999L, "1000Ran# gave $n")
            val die = text(evaluate("RanInt# 1 ; 6 )", true, context(random = random)), NumberFormat.Norm(2)).toLong()
            assertTrue(die in 1L..6L, "RanInt#(1;6) gave $die")
        }
        // In linear output Ran# is a plain decimal (04 E-39 prints integers from 1000Ran#).
        val linear = evaluate("Ran#", false, context(setup = line()))
        assertTrue(text(linear, NumberFormat.Norm(2)).startsWith("~"), "LineIO Ran# should be a decimal")
    }

    private companion object {
        const val DRAWS = 40
    }
}
