package dev.inkstone.core.calc.mode

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.eval.StatisticsSource
import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.input.InputState
import dev.inkstone.core.calc.input.Item
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.lcd.GridCell
import dev.inkstone.core.calc.lcd.GridRow
import dev.inkstone.core.calc.lcd.GridSpec
import dev.inkstone.core.calc.lcd.LcdFrame
import dev.inkstone.core.calc.lcd.MathLayout
import dev.inkstone.core.calc.lcd.Screens
import dev.inkstone.core.calc.machine.Action
import dev.inkstone.core.calc.machine.Command
import dev.inkstone.core.calc.machine.ErrorScreen
import dev.inkstone.core.calc.machine.Host
import dev.inkstone.core.calc.machine.LineEditor
import dev.inkstone.core.calc.machine.LineMenus
import dev.inkstone.core.calc.machine.LineValue
import dev.inkstone.core.calc.machine.Press
import dev.inkstone.core.calc.machine.Screen
import dev.inkstone.core.calc.machine.TypingTarget
import dev.inkstone.core.calc.machine.evaluateLine
import dev.inkstone.core.calc.parse.Radix
import dev.inkstone.core.calc.value.Real

/**
 * The Statistics Editor (research 03 §4.1, VERIFIED unless marked; screens `img37`–`img39`,
 * `img42`–`img44`). It shares the coefficient editors' parts — typing on the bottom line
 * ([LineEditor]), `=` evaluating the cell ([evaluateLine]), the refused keys, hyp/DRG▶/CONST/CONV
 * into the cell ([LineMenus]), its errors ([ErrorScreen]), cells truncated by `Formatter.cellText`
 * and the full value bottom right, all drawn by `Screens.grid` — but not `machine.CellEditor`
 * itself, whose grid is fixed and whose `=` walks across a row. A statistics table grows a row at
 * a time and `=` walks **down** a column:
 *
 * - **Rows.** Row numbers down the left, headers `X`, `Y`, `FREQ` across the top, three rows on
 *   screen. Below the data is one **empty row**, blank, where the next value goes; typing into any
 *   of its cells makes it a row of data, "the untouched cells of the Y column show 0, and FREQ cells
 *   default to 1" ([StatTable]). A full table ([StatLayout.capacity]) has no empty row.
 * - **`=`** stores the typed value "and moves down one row", staying put on the last row of a full
 *   table. With nothing typed it does nothing (⚠️ GUESS: research 03 §2.4 calls it UNKNOWN).
 * - **`▼` from the last row wraps to row 1** — the only way the guide's `170 = 173 = 179 = ▼ ▶`
 *   ends on row 1 of Y (research 03 §4.1, INFERRED). `▲` from row 1 wraps to the last (⚠️ GUESS, by
 *   symmetry); `◀`/`▶` stop at the edges (⚠️ GUESS, as `CellEditor`'s do).
 * - **`DEL`** with nothing typed deletes the row under the cursor (VERIFIED); on the empty row it
 *   does nothing (⚠️ GUESS). `SHIFT 1 3 1` inserts a row of defaults there and `SHIFT 1 3 2` deletes
 *   every row (VERIFIED; [insertRow], [deleteAll]); a full table takes no insert (⚠️ GUESS).
 * - **`AC`** goes to the Statistics Calculation Screen (VERIFIED), dropping anything half typed
 *   (⚠️ GUESS, as `CellEditor`'s `AC` drops it).
 * - A statistic typed into a cell (`SHIFT 1 4 2` puts `x̄` there) is worked out over the data as it
 *   stands (⚠️ GUESS).
 */
internal class StatEditor(
    private val host: Host,
    private val table: StatTable,
    private val type: StatType,
    private val actions: Actions,
) : Screen,
    TypingTarget {
    /** What the editor needs from [StatMode]. */
    interface Actions {
        /** The statistics of the data as it stands. */
        fun statistics(): StatisticsSource

        /** `AC`: on to the Statistics Calculation Screen. */
        fun calculate()

        /** `SHIFT 1`: the STAT/DIST menu with Edit in it. */
        fun menu(editor: StatEditor)
    }

    /** The row under the cursor; [StatTable.size] is the empty row. */
    var row: Int = 0
        private set

    /** The cursor's column, an index into [StatLayout.columns]. */
    var column: Int = 0
        private set

    private var firstRow = 0

    /** What is being typed into the current cell, LineIO; empty while nothing is. */
    override var input: InputState = InputState.empty(natural = false)
        private set

    private val typing: Boolean get() = !input.expr.isEmpty

    private val layout: StatLayout get() = StatLayout.of(type, host.setup.statFrequency)

    /** The last row the cursor can reach: the empty row while there is room for one. */
    private val lastRow: Int get() = if (table.size < layout.capacity) table.size else table.size - 1

    override fun handle(press: Press) {
        val command = press.command
        when {
            refused(press.action) -> Unit
            command == null -> edit(press.action)
            command == Command.EQUALS -> enter(press)
            command == Command.AC -> actions.calculate()
            command == Command.DEL && !typing -> deleteRow()
            command in MOVES && !typing -> move(command)
            LineEditor.edits(press.action) -> if (typing) edit(press.action)
            command == Command.MODE_MENU -> actions.menu(this)
            else -> LineMenus.open(host, command, this)
        }
    }

    /** A menu pick (hyp, CONST, a statistic…) types into the cell, as its key would. */
    override fun insert(item: Item) {
        input = LineEditor.insert(input, item)
    }

    /** Shows [error] over the editor: `AC` drops the typing, `◀`/`▶` return to it at [at] (or its end). */
    override fun fail(error: CalcError, at: Cursor?) {
        val typed = input
        val goto = at ?: Cursor(index = typed.expr.items.size)
        host.push(
            ErrorScreen(
                host,
                error,
                onAc = {
                    host.pop()
                    input = InputState.empty(natural = false)
                },
                onGoto = {
                    host.pop()
                    input = typed.copy(cursor = goto)
                },
            ),
        )
    }

    /**
     * A change of the FREQ setting deletes the data (research 03 §2.3, VERIFIED; [StatMode] does
     * that) and changes the columns, so the cursor goes back to row 1, X.
     */
    override fun setupChanged(previous: Setup) {
        if (previous.statFrequency != host.setup.statFrequency) home()
    }

    /** Edit → Ins: a row of defaults at the cursor, the rows from there on moving down. */
    fun insertRow() {
        input = InputState.empty(natural = false)
        if (table.size >= layout.capacity) return
        table.insert(row)
        follow()
    }

    /** Edit → Del-A: every row gone, the cursor back at row 1, X. */
    fun deleteAll() {
        table.clear()
        home()
    }

    override fun render(): LcdFrame {
        val columns = layout.columns
        val spec = GridSpec(
            headers = columns.map { it.header },
            rows = (firstRow until firstRow + GridSpec.MAX_ROWS).map { r ->
                GridRow(r + 1, columns.map { c -> if (r < table.size) cellText(table[r, c]) else "" })
            },
            current = GridCell(row - firstRow, column),
            value = if (row < table.size) fullText(table[row, columns[column]]) else "",
            editing = if (typing) input.expr else null,
            editCursor = if (typing) input.cursor else null,
            cursorShape = LineEditor.cursorShape(input),
        )
        return Screens.grid(host.indicators(), spec)
    }

    private fun home() {
        input = InputState.empty(natural = false)
        row = 0
        column = 0
        firstRow = 0
    }

    private fun edit(action: Action) {
        input = LineEditor.apply(input, action)
    }

    private fun enter(press: Press) {
        if (!typing) return
        when (val typed = host.evaluateLine(input.expr, press.interrupt, actions.statistics())) {
            is LineValue.Ok -> store(typed.value as? Real)
            is LineValue.Error -> fail(typed.error, typed.at)
            LineValue.Interrupted -> Unit
        }
    }

    /** The value into the cell — making the empty row a row of data — and down one row. */
    private fun store(value: Real?) {
        if (value == null) {
            fail(CalcError.MATH, null)
            return
        }
        if (row == table.size) table.append()
        table[row, layout.columns[column]] = value
        input = InputState.empty(natural = false)
        row = minOf(row + 1, lastRow)
        follow()
    }

    private fun move(command: Command?) {
        when (command) {
            Command.UP -> row = if (row > 0) row - 1 else lastRow
            Command.DOWN -> row = if (row < lastRow) row + 1 else 0
            Command.LEFT -> column = (column - 1).coerceAtLeast(0)
            Command.RIGHT -> column = (column + 1).coerceAtMost(layout.columns.size - 1)
            else -> Unit
        }
        follow()
    }

    private fun deleteRow() {
        if (row >= table.size) return
        table.delete(row)
        row = minOf(row, lastRow)
        follow()
    }

    /** Keeps the cursor's row among the three on screen, moving the view as little as possible. */
    private fun follow() {
        firstRow = when {
            row < firstRow -> row
            row >= firstRow + GridSpec.MAX_ROWS -> row - GridSpec.MAX_ROWS + 1
            else -> firstRow
        }
    }

    private fun cellText(real: Real): String = host.formatter.cellText(real, host.setup)

    private fun fullText(real: Real): String =
        MathLayout.plainText(host.formatter.valueExpr(real, host.setup, host.mode, Radix.DEC))

    private fun refused(action: Action): Boolean = action is Action.Type && action.tok in REFUSED

    private companion object {
        val MOVES = setOf(Command.LEFT, Command.RIGHT, Command.UP, Command.DOWN)

        /** `Pol`, `Rec`, `÷R` and `:` "cannot be used inside an editor cell" (research 03 §2.4). */
        val REFUSED = setOf(Tok.POL, Tok.REC, Tok.DIV_R, Tok.COLON)
    }
}
