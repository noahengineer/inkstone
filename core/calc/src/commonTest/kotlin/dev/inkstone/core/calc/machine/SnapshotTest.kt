package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.Calculator
import dev.inkstone.core.calc.ComplexFormat
import dev.inkstone.core.calc.InputOutput
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.NumberFormat
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.eval.Memory
import dev.inkstone.core.calc.lcd.Indicator
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.parse.Slot
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.ComplexValue
import dev.inkstone.core.calc.value.DecimalReal
import dev.inkstone.core.calc.value.FractionReal
import dev.inkstone.core.calc.value.MatrixValue
import dev.inkstone.core.calc.value.SurdReal
import dev.inkstone.core.calc.value.SurdTerm
import dev.inkstone.core.calc.value.Value
import dev.inkstone.core.calc.value.VectorValue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `snapshot()` / `restore()` (docs/CALCULATOR.md D4): lossless for everything the real calculator
 * keeps while switched off, and a fresh calculator — never a throw — for anything unreadable.
 */
class SnapshotTest {
    private fun fresh(): Calculator = Calculator(FakeEvaluator(), FakeFormatter(), Random(1))

    private fun restored(text: String): Calculator = Calculator.restore(text) { fresh() }

    private val dec = Dec.parse("-1.2345678901234E-50") ?: error("test value")
    private val values: Map<Variable, Value> = mapOf(
        Variable.A to FractionReal(-7, 3),
        Variable.B to DecimalReal(dec),
        Variable.C to DecimalReal(Dec.parse("2.255") ?: error("test value"), dms = true),
        Variable.D to SurdReal(listOf(SurdTerm(-1, 2, 3), SurdTerm(5, 1, 2)), Dec.parse("6.2") ?: error("test value")),
        Variable.X to ComplexValue(FractionReal(1, 2), DecimalReal(dec)),
    )

    /** A calculator moved away from every default it can be. */
    private fun used(): Session {
        val s = Session().press("SETUP 4 SETUP 6 3 SETUP ▼ 1 SETUP ▼ 3 2 SETUP ▼ 4 1 SETUP ▼ 5 1 SETUP ▼ 6 2")
        s.press("SETUP ▼ 7 ▶ AC SETUP 1 2 4 = 5 = MODE 4 x^")
        val memory = s.machine.memory
        // Leaving COMP cleared PreAns; BASE-N cannot set it, so it is put back by hand to prove it is saved.
        memory.preAns = FractionReal(4, 1)
        values.forEach { (variable, value) -> memory.variables[variable] = value }
        memory.matrices[Slot.B] = MatrixValue(2, 1, listOf(FractionReal(1, 1), DecimalReal(dec)))
        memory.vectors[Slot.ANS] = VectorValue(listOf(FractionReal(3, 1), FractionReal(4, 1), FractionReal(0, 1)))
        return s
    }

    @Test
    fun everythingKeptRoundTripsLosslessly() {
        val s = used()
        val text = s.calculator.snapshot()
        val back = restored(text)
        assertEquals(text, back.snapshot())
        val memory = back.machine.memory
        values.forEach { (variable, value) -> assertEquals(value, memory.variables[variable], "$variable") }
        assertEquals(FractionReal(5, 1), memory.ans)
        assertEquals(FractionReal(4, 1), memory.preAns)
        assertEquals(s.machine.memory.matrices, memory.matrices)
        assertEquals(s.machine.memory.vectors, memory.vectors)
        assertEquals(Mode.BASE_N, back.machine.mode)
        val setup = back.machine.setup
        assertEquals(Setup.DEFAULT_CONTRAST + 1, setup.contrast)
        assertEquals(NumberFormat.Fix(3), setup.format)
        assertEquals(ComplexFormat.POLAR, setup.complex)
        assertEquals(InputOutput.MATH_LINE, setup.io)
        assertEquals(60, back.autoPowerOffMinutes)
    }

    @Test
    fun aRestoredCalculatorIsSwitchedOnAtItsModesRestingScreen() {
        val back = restored(used().calculator.snapshot())
        assertEquals(listOf("", "Hex", "0"), back.frame.text.split('\n'))
        assertTrue(Indicator.RAD in back.frame.indicators && Indicator.FIX in back.frame.indicators)
        assertEquals(Setup.DEFAULT_CONTRAST + 1, back.frame.contrast)
    }

    @Test
    fun historyAndTheLineAreNotKept() {
        val s = Session().press("1 + 1 = 2 +")
        val back = restored(s.calculator.snapshot())
        assertEquals("", back.frame.text)
        assertTrue(Indicator.UP !in back.frame.indicators)
    }

    @Test
    fun aFreshCalculatorRoundTrips() {
        val text = fresh().snapshot()
        assertTrue(text.startsWith("inkstone-calc/1\n"))
        assertEquals(text, restored(text).snapshot())
    }

    @Test
    fun unreadableTextGivesAFreshCalculator() {
        val good = used().calculator.snapshot()
        val freshText = fresh().snapshot()
        val corrupt = listOf(
            "",
            "garbage",
            "inkstone-calc/2\nmode=COMP",
            good.replace("mode=BASE_N", "mode=TURBO"),
            good.replace("var.A=F-7/3", "var.A=F1/0"),
            good.replace("var.A=F-7/3", "var.A=Fx/3"),
            good.replace("var.A=F-7/3\n", ""),
            good.replace("contrast=14", "contrast=99"),
            good.replace("format=Fix:3", "format=Fix:12"),
            good.replace("apo=60", "apo=30"),
            good.replace("mat.B=M 2 1", "mat.B=M 3 1"),
            good.replace("ctl.BASE_N=HEX", "ctl.BASE_N=HEXADECIMAL"),
            good.replace("ctl.BASE_N=HEX", "ctl.BASE_N=HEX\\"),
            good + "\nctl.COMP=\\q",
        )
        for (text in corrupt) assertEquals(freshText, restored(text).snapshot(), "restoring <$text>")
    }

    @Test
    fun unknownKeysAreIgnored() {
        val good = used().calculator.snapshot()
        val back = restored(good + "\nfuture.thing=42")
        assertEquals(good, back.snapshot())
    }

    @Test
    fun aRestoreOutsideCompKeepsPreAnsAsSaved() {
        val back = restored(used().calculator.snapshot())
        assertEquals(FractionReal(4, 1), back.machine.memory.preAns)
        assertNull(back.machine.memory.matrices[Slot.A])
        assertEquals(Memory.ZERO, back.machine.memory.variables[Variable.M])
    }
}
