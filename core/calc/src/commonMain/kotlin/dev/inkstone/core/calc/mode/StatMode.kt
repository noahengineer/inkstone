package dev.inkstone.core.calc.mode

import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.eval.StatisticsSource
import dev.inkstone.core.calc.machine.Calculation
import dev.inkstone.core.calc.machine.CalculationHooks
import dev.inkstone.core.calc.machine.ClearScope
import dev.inkstone.core.calc.machine.Host
import dev.inkstone.core.calc.machine.MenuScreen
import dev.inkstone.core.calc.machine.ModeController
import dev.inkstone.core.calc.machine.TypingTarget

/**
 * STAT (`MODE 3`, research 03 §4, 06 §6.4; examples 05 EX-STAT-01…05 and EX-SA-STAT-01) — the
 * shape of [RatioMode], with a calculation line behind the editor.
 *
 * - **Entry.** The type menu (`1:1-VAR 2:A+BX / … / 7:A•X^B 8:1/X`, two columns, screen `img36`);
 *   choosing a type opens an empty Statistics Editor ([StatEditor]). `AC` on that menu does nothing
 *   — there is nothing under it (⚠️ GUESS, as RATIO's).
 * - **Editor → line.** `AC` in the editor goes to the Statistics Calculation Screen, "an ordinary
 *   calculation line" (research 03 §4.2): a [Calculation] whose [CalculationHooks.statistics] is
 *   the data as it stands ([StatStatistics]), with no history (research 02 §10.2: COMP, CMPLX and
 *   BASE-N only).
 * - **`SHIFT 1`** ([StatMenus]): `Type` brings the type menu back, `Data` the editor, `Edit` (in the
 *   editor) Ins and Del-A, and Sum/Var/Distr or Reg/MinMax type their items into the line or the
 *   cell.
 * - **What deletes the data** (research 03 §4.1, VERIFIED): leaving STAT, switching between 1-VAR
 *   and a paired type ("Changing between paired types keeps the data", EX-STAT-03), and changing
 *   SETUP's STAT (FREQ) item. Choosing a type at entry starts empty — the data went when STAT was
 *   last left, and `MODE 3` in STAT runs the mode afresh (the machine's ⚠️ GUESS).
 * - **Home.** `ON`, switching on and the end of a CLR return to the editor with the data as it was,
 *   once a type has been chosen; before that, to the type menu (⚠️ GUESS, as RATIO's: research 02 §9
 *   says the data survives power-off, not which screen comes back).
 * - **Persistence.** The type, the FREQ setting the rows were typed under, and the rows survive
 *   power-off (docs/CALCULATOR.md D4). CLR All forgets them, CLR Memory does not (⚠️ GUESS, as
 *   RATIO's: "Memory" is the variables and answers, research 02 §5.3). Rows saved under the other
 *   FREQ setting than the one restored are dropped at [home], as the SETUP change would have
 *   dropped them — a restored calculator never shows data in a layout it was not typed in.
 * - **Display.** Linear only, with the STAT icon ([ModeController.linearOnly], the machine's).
 */
class StatMode : ModeController {
    override val mode: Mode = Mode.STAT

    /** The type chosen, or null until one is (a fresh calculator, STAT left, CLR All). */
    private var type: StatType? = null

    private val table = StatTable()

    /** SETUP's FREQ setting when the rows were typed. */
    private var frequency = false

    override fun enter(host: Host) {
        type = null
        table.clear()
        host.show(typeMenu(host, entering = true))
    }

    override fun home(host: Host) {
        val chosen = type
        if (chosen == null) {
            enter(host)
            return
        }
        if (frequency != host.setup.statFrequency) adopt(host.setup)
        host.show(editorFor(host, chosen))
    }

    override fun leave(host: Host) {
        type = null
        table.clear()
    }

    /** "Changing the Stat Format setting deletes all Statistics Editor data" (research 03 §2.3, VERIFIED). */
    override fun setupChanged(previous: Setup, host: Host) {
        if (previous.statFrequency != host.setup.statFrequency) adopt(host.setup)
    }

    override fun clear(scope: ClearScope) {
        if (scope != ClearScope.ALL) return
        type = null
        table.clear()
    }

    /** `LINEAR 1 3 F20/1 F3150/1 F1/1 …`: the type, the FREQ setting (1/0), then [StatTable.snapshot]. */
    override fun snapshot(): String? = type?.let { "${it.name} ${if (frequency) ON else OFF} ${table.snapshot()}" }

    override fun restore(text: String): Boolean {
        val words = text.split(' ', limit = SNAPSHOT_PARTS)
        val saved = StatType.entries.firstOrNull { it.name == words.first() } ?: return false
        val savedFrequency = FLAGS[words.getOrNull(1)] ?: return false
        if (!table.restore(words.getOrNull(2).orEmpty(), StatLayout.of(saved, savedFrequency).capacity)) return false
        type = saved
        frequency = savedFrequency
        return true
    }

    /** No rows, typed under SETUP as it now is. */
    private fun adopt(setup: Setup) {
        table.clear()
        frequency = setup.statFrequency
    }

    /**
     * The type menu: at entry `AC` does nothing; from `SHIFT 1` it closes, back to the screen it was
     * opened over. A type of the other kind (1-VAR ⇔ paired) deletes the data; either way the editor
     * comes up, as after `MODE 3` (EX-STAT-03's `Type`, `ln X` is followed by `AC` to leave it).
     */
    private fun typeMenu(host: Host, entering: Boolean): MenuScreen {
        val onCancel: () -> Unit = if (entering) ({}) else host::pop
        return MenuScreen(host, StatMenus.TYPES, onCancel = onCancel) { _, number ->
            val chosen = StatType.entries[number - 1]
            val previous = type
            if (previous == null || previous.paired != chosen.paired) adopt(host.setup)
            type = chosen
            host.show(editorFor(host, chosen))
        }
    }

    private fun editorFor(host: Host, type: StatType): StatEditor =
        StatEditor(host, table, type, EditorActions(host, type))

    private fun statisticsOf(host: Host, type: StatType): StatisticsSource =
        StatStatistics(type, table.points(host.setup.statFrequency))

    /**
     * `SHIFT 1` on the line or in the editor. Type, Data and Edit act; the rest open a typing
     * sub-menu over [target] — the line, or the cell being typed.
     */
    private fun openMenu(host: Host, type: StatType, target: TypingTarget, editor: StatEditor?) {
        val entries = StatMenus.entries(type, editing = editor != null)
        host.push(
            MenuScreen(host, StatMenus.main(entries)) { _, number ->
                when (val picked = entries[number - 1]) {
                    StatMenu.TYPE -> host.replace(typeMenu(host, entering = false))
                    StatMenu.DATA -> host.show(editorFor(host, type))
                    StatMenu.EDIT -> editor?.let { host.replace(editMenu(host, it)) }
                    else -> host.replace(StatMenus.typing(host, type, picked, target))
                }
            },
        )
    }

    private fun editMenu(host: Host, editor: StatEditor): MenuScreen = MenuScreen(host, StatMenus.EDIT) { _, number ->
        host.pop()
        if (number == StatMenus.INSERT) editor.insertRow() else editor.deleteAll()
    }

    private inner class EditorActions(private val host: Host, private val type: StatType) : StatEditor.Actions {
        override fun statistics(): StatisticsSource = statisticsOf(host, type)

        override fun calculate() = host.show(Calculation(host, LineHooks(host, type)))

        override fun menu(editor: StatEditor) = openMenu(host, type, editor, editor)
    }

    /** The Statistics Calculation Screen's differences from COMP's line: STAT's data, its `SHIFT 1`. */
    private inner class LineHooks(private val host: Host, private val type: StatType) : CalculationHooks {
        override val statistics: StatisticsSource get() = statisticsOf(host, type)

        override fun modeMenu(line: Calculation) = openMenu(host, type, line, null)
    }

    private companion object {
        const val ON = "1"
        const val OFF = "0"
        const val SNAPSHOT_PARTS = 3
        val FLAGS: Map<String?, Boolean> = mapOf(ON to true, OFF to false)
    }
}
