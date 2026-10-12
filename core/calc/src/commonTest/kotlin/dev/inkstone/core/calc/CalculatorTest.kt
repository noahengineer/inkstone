package dev.inkstone.core.calc

import dev.inkstone.core.calc.lcd.Indicator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The public facade as the app uses it: `Calculator(random)`, `press`, `frame`, `snapshot`,
 * `restore`, `autoPowerOff`. These run on the production wiring, so they assert only what stays
 * true when the TEMPORARY evaluator and formatter are replaced by the real ones.
 */
class CalculatorTest {
    private fun Calculator.type(vararg keys: Key): Calculator = apply { keys.forEach { press(it) } }

    @Test
    fun aNewCalculatorShowsAnEmptyCompLine() {
        val calculator = Calculator(Random(1))
        assertEquals("", calculator.frame.text)
        assertEquals(setOf(Indicator.DEG, Indicator.MATH), calculator.frame.indicators)
        assertNotNull(calculator.frame.cursor)
        assertFalse(calculator.frame.off)
    }

    @Test
    fun pressReturnsTheNewFrameAndKeepsIt() {
        val calculator = Calculator()
        val frame = calculator.press(Key.D7)
        assertSame(frame, calculator.frame)
        assertEquals("7", frame.text)
    }

    @Test
    fun wholeNumberArithmeticWorksOnTheProductionWiring() {
        val calculator = Calculator().type(Key.D1, Key.PLUS, Key.D2, Key.EQUALS)
        assertEquals("1+2\n3", calculator.frame.text)
        calculator.type(Key.D1, Key.DIVIDE, Key.D3, Key.EQUALS)
        assertEquals("1÷3\n1/3", calculator.frame.text)
    }

    @Test
    fun aSnapshotRestoresThroughThePublicApi() {
        val calculator = Calculator().type(Key.D5, Key.SHIFT, Key.RCL, Key.NEGATIVE, Key.MODE, Key.D2)
        val back = Calculator.restore(calculator.snapshot())
        assertEquals(calculator.snapshot(), back.snapshot())
        assertTrue(Indicator.CMPLX in back.frame.indicators)
        back.type(Key.RCL, Key.NEGATIVE)
        assertEquals("A\n5", back.frame.text)
    }

    /**
     * A 14-digit product is past the 14 digits a fraction record holds across numerator and
     * denominator (docs/CALCULATOR.md §3), so Ans holds it as a decimal — and the snapshot, which
     * refuses a fraction past that limit, restores it rather than starting over.
     */
    @Test
    fun aSnapshotHoldingALargeResultRestores() {
        val nines = Array(7) { Key.D9 }
        val calculator = Calculator().type(*nines, Key.MULTIPLY, *nines, Key.EQUALS)
        val text = calculator.snapshot()
        assertEquals(text, Calculator.restore(text).snapshot())
    }

    @Test
    fun anUnreadableSnapshotGivesAResetCalculator() {
        val back = Calculator.restore("not a snapshot")
        assertEquals(Calculator().snapshot(), back.snapshot())
    }

    @Test
    fun autoPowerOffBlanksTheGlassUntilOn() {
        val calculator = Calculator().type(Key.D4)
        assertTrue(calculator.autoPowerOff().off)
        assertTrue(calculator.press(Key.D5).off)
        assertFalse(calculator.press(Key.ON).off)
        assertEquals(10, calculator.autoPowerOffMinutes)
    }
}
