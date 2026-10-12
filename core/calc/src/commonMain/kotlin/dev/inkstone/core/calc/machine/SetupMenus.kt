package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.AngleUnit
import dev.inkstone.core.calc.ComplexFormat
import dev.inkstone.core.calc.FractionFormat
import dev.inkstone.core.calc.InputOutput
import dev.inkstone.core.calc.NumberFormat
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.TableFunctions
import dev.inkstone.core.calc.lcd.LcdFrame
import dev.inkstone.core.calc.lcd.Screens
import dev.inkstone.core.calc.lcd.TextLine
import dev.inkstone.core.calc.lcd.TextRow

/**
 * SETUP, `SHIFT MODE` (research 02 §5, both pages VERIFIED from [G p.11]): every item, every
 * sub-prompt, and the contrast screen. An item that needs nothing more is applied at once and
 * closes SETUP; a sub-prompt replaces the menu and closes SETUP when answered. `AC` anywhere in
 * SETUP closes it unchanged — ⚠️ GUESS for the sub-prompts, which the guide never cancels.
 */
internal object SetupMenus {
    private sealed interface Pick {
        class Apply(val change: (Setup) -> Setup) : Pick

        /** A sub-menu under a title line: `Result Format?` / `1:MathO 2:LineO`. */
        class Choose(val title: String, val choices: List<Pair<String, (Setup) -> Setup>>) : Pick

        /** A one-digit prompt: `Fix 0~9?`. */
        class Digit(val title: String, val digits: IntRange, val change: (Setup, Int) -> Setup) : Pick

        data object Contrast : Pick
    }

    private fun choice(label: String, change: (Setup) -> Setup): Pair<String, (Setup) -> Setup> = label to change

    private val PAGES: List<MenuPage> = listOf(
        MenuPage.of("MthIO", "LineIO", "Deg", "Rad", "Gra", "Fix", "Sci", "Norm"),
        MenuPage.of("ab/c", "d/c", "CMPLX", "STAT", "TABLE", "APO", "◀CONT▶"),
    )

    /**
     * Page 1. `Fix 0~9?` and `Norm 1~2?` VERIFIED ([SA s.5]); `Sci 0~9?` and `Result Format?`
     * SECONDARY (sibling firmware strings). "Sci 0 means 10 significant digits" (research 02 §1 Z5)
     * is the formatter's business; 0 is stored as typed.
     */
    private val PAGE_1: List<Pick> = listOf(
        Pick.Choose(
            "Result Format?",
            listOf(
                choice("MathO") { s -> s.copy(io = InputOutput.MATH_MATH) },
                choice("LineO") { s -> s.copy(io = InputOutput.MATH_LINE) },
            ),
        ),
        Pick.Apply { it.copy(io = InputOutput.LINE_LINE) },
        Pick.Apply { it.copy(angle = AngleUnit.DEG) },
        Pick.Apply { it.copy(angle = AngleUnit.RAD) },
        Pick.Apply { it.copy(angle = AngleUnit.GRA) },
        Pick.Digit("Fix 0~9?", 0..9) { s, d -> s.copy(format = NumberFormat.Fix(d)) },
        Pick.Digit("Sci 0~9?", 0..9) { s, d -> s.copy(format = NumberFormat.Sci(d)) },
        Pick.Digit("Norm 1~2?", 1..2) { s, d -> s.copy(format = NumberFormat.Norm(d)) },
    )

    /**
     * Page 2. `Complex Result?` and `Frequency?` SECONDARY (sibling firmware strings). The TABLE and
     * APO items are 2nd-edition additions whose prompt text is UNKNOWN (research 02 §15 #1); their
     * titles here are ⚠️ GUESSES in the same style, and their choices are the guide's own labels.
     */
    private val PAGE_2: List<Pick> = listOf(
        Pick.Apply { it.copy(fraction = FractionFormat.MIXED) },
        Pick.Apply { it.copy(fraction = FractionFormat.IMPROPER) },
        Pick.Choose(
            "Complex Result?",
            listOf(
                choice("a+bi") { s -> s.copy(complex = ComplexFormat.RECTANGULAR) },
                choice("r∠θ") { s -> s.copy(complex = ComplexFormat.POLAR) },
            ),
        ),
        Pick.Choose(
            "Frequency?",
            listOf(
                choice("ON") { s -> s.copy(statFrequency = true) },
                choice("OFF") { s -> s.copy(statFrequency = false) },
            ),
        ),
        Pick.Choose(
            "Table Type?",
            listOf(
                choice("f(x)") { s -> s.copy(table = TableFunctions.F_ONLY) },
                choice("f(x),g(x)") { s -> s.copy(table = TableFunctions.F_AND_G) },
            ),
        ),
        Pick.Choose(
            "Auto Power Off?",
            listOf(
                choice("10Min.") { s -> s.copy(autoPowerOffMinutes = 10) },
                choice("60Min.") { s -> s.copy(autoPowerOffMinutes = 60) },
            ),
        ),
        Pick.Contrast,
    )

    private val PICKS: List<List<Pick>> = listOf(PAGE_1, PAGE_2)

    fun open(machine: Machine): MenuScreen = MenuScreen(machine, PAGES, onCancel = machine::closeGlobal) { page, n ->
        when (val pick = PICKS[page][n - 1]) {
            is Pick.Apply -> machine.changeSetup(pick.change(machine.setup))

            is Pick.Choose -> machine.replace(choose(machine, pick))

            is Pick.Digit -> machine.replace(
                DigitPrompt(machine, pick.title, pick.digits, machine::closeGlobal) { digit ->
                    machine.changeSetup(pick.change(machine.setup, digit))
                },
            )

            Pick.Contrast -> machine.replace(ContrastScreen(machine))
        }
    }

    private fun choose(machine: Machine, pick: Pick.Choose): MenuScreen {
        val page = MenuPage.numbered(pick.choices.map { it.first }, title = listOf(pick.title))
        return MenuScreen(machine, listOf(page), onCancel = machine::closeGlobal) { _, n ->
            machine.changeSetup(pick.choices[n - 1].second(machine.setup))
        }
    }
}

/**
 * SETUP ▼7 ◀CONT▶ (research 02 §5.2): `CONTRAST`, a blank line, `LIGHT       DARK`,
 * ` [◀]        [▶]` — the sibling firmware's strings (SECONDARY) for the screen [G p.6] shows.
 * `◀` lightens and `▶` darkens one of the 26 levels at a time, live; `AC` leaves SETUP.
 */
internal class ContrastScreen(private val machine: Machine) : Screen {
    override fun handle(press: Press) {
        when (press.command) {
            Command.LEFT -> machine.changeContrast(machine.setup.contrast - 1)
            Command.RIGHT -> machine.changeContrast(machine.setup.contrast + 1)
            Command.AC -> machine.closeGlobal()
            else -> Unit
        }
    }

    override fun render(): LcdFrame = Screens.text(
        machine.indicators(),
        listOf(
            TextLine(TITLE, TextRow.SMALL_1),
            TextLine(ENDS, TextRow.SMALL_3),
            TextLine(KEYS, TextRow.SMALL_4),
        ),
    )

    private companion object {
        const val TITLE = "CONTRAST"
        const val ENDS = "LIGHT       DARK"
        const val KEYS = " [◀]        [▶]"
    }
}
