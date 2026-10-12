package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.Calculator
import dev.inkstone.core.calc.Key
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.eval.Answer
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.input.TokenItem
import dev.inkstone.core.calc.lcd.Indicator
import dev.inkstone.core.calc.lcd.LcdFrame
import dev.inkstone.core.calc.lcd.Screens
import dev.inkstone.core.calc.lcd.TextLine
import dev.inkstone.core.calc.lcd.TextRow
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.FractionReal
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The extension point as a stage-3 mode will use it: a controller registered for STAT that reuses
 * the calculation line through [CalculationHooks], adds its `SHIFT 1` menu, refuses a key through
 * its overlay, takes over one kind of answer with a screen of its own, and persists text that
 * spans lines.
 */
class ModeControllerTest {
    private class StatLike : ModeController {
        override val mode = Mode.STAT
        override val keys = mapOf(Binding(Layer.NONE, Key.SIN) to Action.None)
        var entered = 0
        var left = 0
        val cleared = mutableListOf<ClearScope>()
        var data = "row 1\nrow 2\\end"

        override fun enter(host: Host) {
            entered++
            host.show(Calculation(host, Hooks(host)))
        }

        override fun leave(host: Host) {
            left++
        }

        override fun clear(scope: ClearScope) {
            cleared += scope
        }

        override fun snapshot(): String = data

        override fun restore(text: String): Boolean {
            data = text
            return true
        }

        private class Hooks(private val host: Host) : CalculationHooks {
            override fun modeMenu(line: Calculation) {
                val page = MenuPage.of("Σx", "n")
                host.push(LineMenus.typing(host, line, listOf(page), listOf(listOf(TOKEN_SUM, TOKEN_N))))
            }

            override fun takeAnswer(answer: Answer, line: Calculation): Boolean {
                if (answer.value != FractionReal(TAKEN, 1)) return false
                // The mode owns Ans now, and records it as MatAns would.
                host.recordAnswer(answer.value)
                host.push(Taken(host, line))
                return true
            }
        }

        /** Stands for a MatAns-like screen: `+` goes back to the line with a prefix, AC clears it. */
        private class Taken(private val host: Host, private val line: Calculation) : Screen {
            override fun handle(press: Press) {
                when {
                    press.action == Action.Type(Tok.PLUS) -> {
                        host.pop()
                        line.startWith(TokenItem(Tok.ANS), press)
                    }

                    press.command == Command.AC -> {
                        host.pop()
                        line.clear()
                    }
                }
            }

            override fun render(): LcdFrame =
                Screens.text(host.indicators(), listOf(TextLine("TAKEN", TextRow.SMALL_1)))
        }
    }

    private fun session(stat: StatLike = StatLike()) = Session(modes = ModeRegistry.standard(listOf(stat)))

    @Test
    fun enteringTheModeShowsItsLineWithItsIconAndLinearInput() {
        val stat = StatLike()
        val s = session(stat).press("MODE 3")
        assertEquals(1, stat.entered)
        assertTrue(Indicator.STAT in s.icons)
        assertFalse(Indicator.MATH in s.icons)
        s.press("1 + 2 =")
        assertEquals(listOf("1+2", "3"), s.lines)
    }

    @Test
    fun theModesShiftMenuTypesIntoTheLine() {
        val s = session().press("MODE 3 SHIFT 1")
        assertEquals("1:Σx  2:n", s.text)
        s.press("2")
        assertEquals("n", s.text)
    }

    @Test
    fun theOverlayRefusesAKey() {
        val s = session().press("MODE 3 sin 4")
        assertEquals("4", s.text)
    }

    @Test
    fun theModeIsToldWhenItIsLeftAndCleared() {
        val stat = StatLike()
        val s = session(stat).press("MODE 3 MODE 1")
        assertEquals(1, stat.left)
        s.press("CLR 3 =")
        assertEquals(listOf(ClearScope.ALL), stat.cleared)
    }

    @Test
    fun anAnswerCanBeTakenOverAndTheLineResumedWithAPrefix() {
        val s = session().press("MODE 3 6 × 7 =")
        assertEquals("TAKEN", s.text)
        s.press("+ 1")
        assertEquals("Ans+1", s.text)
        s.press("AC 6 × 7 = AC")
        assertEquals("", s.text)
    }

    /**
     * "The hook owns Ans" ([CalculationHooks.takeAnswer]): STO and M+ on a typed line whose answer
     * the mode took must not also put it in a variable or M — a matrix in A would be a value no
     * variable can hold.
     */
    @Test
    fun stoAndMPlusLeaveATakenAnswerToTheMode() {
        val s = session().press("MODE 3 6 × 7 STO (−)")
        assertEquals("TAKEN", s.text)
        assertEquals(FractionReal(0, 1), s.machine.memory.variables[Variable.A])
        assertEquals(FractionReal(TAKEN, 1), s.machine.memory.ans)
        s.press("AC 6 × 7 M+")
        assertEquals("TAKEN", s.text)
        assertEquals(FractionReal(0, 1), s.machine.memory.variables[Variable.M])
    }

    @Test
    fun itsStateIsPersistedEvenAcrossLines() {
        val stat = StatLike()
        val text = session(stat).press("MODE 3").calculator.snapshot()
        val restoredStat = StatLike()
        restoredStat.data = ""
        val back = Calculator.restore(text) {
            Calculator(FakeEvaluator(), FakeFormatter(), Random(1), ModeRegistry.standard(listOf(restoredStat)))
        }
        assertEquals(stat.data, restoredStat.data)
        assertEquals(Mode.STAT, back.machine.mode)
        assertEquals(text, back.snapshot())
    }

    private companion object {
        const val TAKEN = 42L
        val TOKEN_SUM = TokenItem(Tok.SUM_X)
        val TOKEN_N = TokenItem(Tok.STAT_N)
    }
}
