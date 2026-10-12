package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.lcd.LcdFrame
import dev.inkstone.core.calc.lcd.MenuItem
import dev.inkstone.core.calc.lcd.MenuStyle
import dev.inkstone.core.calc.lcd.Screens
import dev.inkstone.core.calc.lcd.TextLine
import dev.inkstone.core.calc.lcd.TextRow

/** The screens behind the three global keys: `MODE`, `SHIFT MODE` (SETUP) and `SHIFT 9` (CLR). */
internal object GlobalMenus {
    fun open(machine: Machine, command: Command?): Screen = when (command) {
        Command.SETUP -> SetupMenus.open(machine)
        Command.CLR -> clear(machine)
        else -> mode(machine)
    }

    /**
     * The MODE menu (research 02 §4, VERIFIED from [SA s.2, s.15]): two pages in the MODE look —
     * `[1]COMP [2]CMPLX` … `[8]VECTOR`, then `[1]DIST [2]INEQ [3]RATIO` — a digit enters a mode,
     * `0` shows the authenticity screen, `AC` closes the menu.
     */
    fun mode(machine: Machine): MenuScreen = MenuScreen(
        machine,
        MODE_PAGES.map { modes -> MenuPage(modes.mapIndexed { i, mode -> MenuItem(i + 1, mode.menuLabel) }) },
        style = MenuStyle.MODE,
        onCancel = machine::closeGlobal,
        onUnlisted = { _, digit -> if (digit == 0) machine.replace(AuthenticityScreen(machine)) },
    ) { page, number -> machine.selectMode(MODE_PAGES[page][number - 1]) }

    private val MODE_PAGES: List<List<Mode>> = Mode.entries.chunked(MODES_PER_PAGE)

    /** "1:Setup 2:Memory" / "3:All" under `Clear?` (research 02 §5.3, VERIFIED [SA s.4]). */
    private fun clear(machine: Machine): MenuScreen = MenuScreen(
        machine,
        listOf(MenuPage.of("Setup", "Memory", "All", title = listOf("Clear?"))),
        onCancel = machine::closeGlobal,
    ) { _, number ->
        val scope = ClearScope.entries[number - 1]
        machine.replace(ConfirmScreen(machine, CONFIRM.getValue(scope), machine::closeGlobal) { reset(machine, scope) })
    }

    /**
     * `=` on the confirmation. After Setup and All the calculator is in COMP with a fresh line under
     * the notice; after Memory the notice sits over the screen CLR was opened from. `AC` on the
     * notice returns to the mode's resting screen.
     */
    private fun reset(machine: Machine, scope: ClearScope) {
        machine.closeGlobal()
        machine.clear(scope)
        val message = if (scope == ClearScope.ALL) RESET_ALL else COMPLETE
        machine.push(NoticeScreen(machine, message, machine::home))
    }

    private const val MODES_PER_PAGE = 8

    /** `Clear Setup?` and `Clear Memory?` SECONDARY (sibling firmware strings); `Reset All?` VERIFIED. */
    private val CONFIRM: Map<ClearScope, String> = mapOf(
        ClearScope.SETUP to "Clear Setup?",
        ClearScope.MEMORY to "Clear Memory?",
        ClearScope.ALL to "Reset All?",
    )

    private const val RESET_ALL = "Reset All"
    private const val COMPLETE = "Complete!"
}

/**
 * `MODE 0` (research 02 §4, VERIFIED): "a 24-character calculator ID plus a QR code for
 * wes.casio.com/calc/. `AC` returns to the mode menu." There is no real device ID to show and no
 * QR code is drawn (docs/CALCULATOR.md D1 — nothing that points at Casio's service); the ID is a
 * fixed placeholder, so this screen exists only so `MODE 0` goes somewhere and back.
 */
private class AuthenticityScreen(private val machine: Machine) : Screen {
    override fun handle(press: Press) {
        if (press.command == Command.AC) machine.replace(GlobalMenus.mode(machine))
    }

    override fun render(): LcdFrame = Screens.text(
        machine.indicators(),
        listOf(
            TextLine(TITLE, TextRow.SMALL_1),
            TextLine(ID.take(HALF), TextRow.SMALL_2),
            TextLine(ID.drop(HALF), TextRow.SMALL_3),
        ),
    )

    private companion object {
        const val TITLE = "Calculator ID:"

        /** 24 characters, like the real one; not a real ID. */
        const val ID = "INKSTONE0000000000000000"
        const val HALF = 12
    }
}
