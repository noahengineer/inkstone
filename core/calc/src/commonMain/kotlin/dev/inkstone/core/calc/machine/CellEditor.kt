package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.eval.Memory
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
import dev.inkstone.core.calc.parse.Radix
import dev.inkstone.core.calc.value.Real

/**
 * A mode's table of real numbers — RATIO's three coefficients, EQN's 2×3 or 3×4, a 3×3 matrix —
 * row-major, every cell 0 to begin with. Owned by the mode's [ModeController], not by the editor
 * screen that shows it, so the numbers outlive the screen: they survive power-off (research 02
 * §9; [snapshot]) and `ON`, and only the mode's own rules (choosing a type, `AC`, CLR) clear them.
 */
class CellGrid(val rows: Int, val columns: Int) {
    init {
        require(rows > 0 && columns > 0) { "a grid has at least one cell" }
    }

    private val cells: MutableList<Real> = MutableList(rows * columns) { Memory.ZERO }

    operator fun get(row: Int, column: Int): Real = cells[index(row, column)]

    operator fun set(row: Int, column: Int, value: Real) {
        cells[index(row, column)] = value
    }

    /** Every cell, row-major. */
    val values: List<Real> get() = cells.toList()

    /** Every cell back to 0 (the coefficient editors' `AC`, research 03 §6.1, §12). */
    fun zero() {
        cells.indices.forEach { cells[it] = Memory.ZERO }
    }

    /** The cells as lossless text, row-major, one value word each (see [ValueCodec]). */
    fun snapshot(): String = cells.joinToString(" ") { ValueCodec.encode(it) }

    /** Reads [snapshot]'s text; false, and the grid unchanged, when it is not exactly this grid's cells. */
    fun restore(text: String): Boolean {
        val words = text.split(' ')
        if (words.size != cells.size) return false
        val read = words.map { ValueCodec.decode(it) as? Real ?: return false }
        read.forEachIndexed { i, value -> cells[i] = value }
        return true
    }

    private fun index(row: Int, column: Int): Int {
        require(row in 0 until rows && column in 0 until columns) { "($row, $column) is outside a $rows×$columns grid" }
        return row * columns + column
    }
}

/**
 * How an editor looks (research 03 §2.4 and the grid measured in `lcd.GridSpec`): one header per
 * column, row numbers or not (EQN's rows are numbered, RATIO's one row is not), brackets round
 * the rows (the coefficient, matrix and vector editors), the template under them (`a:b=X:d`), and
 * a top-left [title] (a matrix's letter).
 */
data class EditorLayout(
    val headers: List<String>,
    val rowNumbers: Boolean = false,
    val brackets: Boolean = true,
    val footer: String? = null,
    val title: String? = null,
)

/**
 * What a mode decides about its editor; everything else is the editor's (see [CellEditor]).
 * The defaults are the **coefficient editors'** (EQN, INEQ, RATIO), which share every rule.
 */
interface EditorHooks {
    /**
     * `=` with nothing newly typed: "In the Coefficient Editors (EQN/INEQ/RATIO), a `[=]` pressed
     * with nothing newly typed solves, even when the cursor is not on the last cell" (research 03
     * §2.4, INFERRED from the guide's key sequences). By default nothing happens.
     */
    fun solve(editor: CellEditor, press: Press) = Unit

    /**
     * `AC`: "`[AC]` in the editor sets all coefficients to zero" (research 03 §6.1, §11.2, §12,
     * VERIFIED) — the default. STAT, MATRIX and VECTOR leave their editors for the calculation
     * screen instead and override this.
     */
    fun clear(editor: CellEditor) = editor.cells.zero()

    /** The mode's `SHIFT 1`/`4`/`5` menu ([Command.MODE_MENU]), pressed in the editor. */
    fun modeMenu(editor: CellEditor) = Unit

    companion object {
        /** EQN, INEQ and RATIO: `AC` zeroes, a bare `=` does nothing until a mode says it solves. */
        val COEFFICIENTS: EditorHooks = object : EditorHooks {}
    }
}

/**
 * The editor screen every mode with a table shares (research 03 §2.4, VERIFIED unless marked):
 * the Coefficient Editor of EQN, INEQ and RATIO, and — with their own [EditorHooks] — the matrix,
 * vector and statistics editors.
 *
 * - The cursor stands on one cell, shown inverted; `◀▶▲▼` move it, stopping at the edges (⚠️
 *   GUESS: the guide never moves past one). At most three columns and three rows show; the view
 *   follows the cursor, so the 3-unknown editor shows `b c d` with the cursor on `d` (03 §6.1).
 * - Typing replaces the cell under the cursor: "move the cursor to the appropriate cell, input the
 *   new value, and then press `[=]`". What is typed shows on the bottom line, linearly, with its own
 *   cursor, and the cursor keys and DEL edit it while it is there. Any expression is accepted and
 *   evaluated by `=` ([evaluateLine]) — "Any other expression can be typed into a cell" (INFERRED).
 * - `=` stores the value and moves to the next cell, row by row ("`1=2=3=2=3=4=`"); "After `[=]` on
 *   the **last** cell, the cursor stays on that cell". `=` with nothing typed is [EditorHooks.solve].
 * - "These cannot be used inside an editor cell: `[M+]`, `[SHIFT][M+](M-)`, `[SHIFT][RCL](STO)`,
 *   `Pol`, `Rec`, `÷R`, and multi-statements": the keys do nothing (⚠️ GUESS: refused rather than
 *   an error, as "cannot be input" reads). hyp, DRG▶, CONST and CONV type into the cell, as on the
 *   line (⚠️ GUESS).
 * - Each cell shows its short form ([dev.inkstone.core.calc.format.Formatter.cellText], "truncated,
 *   not rounded"), and "the bottom-right of the screen shows the full value of the cell under the
 *   cursor" ([dev.inkstone.core.calc.format.Formatter.valueExpr], as plain text).
 * - A cell holds a real number; a value that is not one is a Math ERROR (⚠️ GUESS). An error shows
 *   over the editor ([ErrorScreen]): `AC` drops what was typed, `◀`/`▶` return to it with the cursor
 *   at the error (research 02 §11, as on the line).
 * - `AC` is [EditorHooks.clear], and drops whatever was being typed with it (⚠️ GUESS: the guide's
 *   "AC sets all coefficients to zero" does not say what happens to a value half typed).
 */
class CellEditor(
    private val host: Host,
    val cells: CellGrid,
    private val layout: EditorLayout,
    private val hooks: EditorHooks = EditorHooks.COEFFICIENTS,
) : Screen,
    TypingTarget {
    init {
        require(layout.headers.size == cells.columns) { "one header per column" }
    }

    /** The cell under the cursor. */
    var row: Int = 0
        private set
    var column: Int = 0
        private set

    private var firstRow = 0
    private var firstColumn = 0

    /** What is being typed into the current cell, LineIO; empty while nothing is. */
    override var input: InputState = InputState.empty(natural = false)
        private set

    private val typing: Boolean get() = !input.expr.isEmpty

    override fun handle(press: Press) {
        val command = press.command
        when {
            refused(press.action) -> Unit
            command == null -> edit(press.action)
            command == Command.EQUALS -> enter(press)
            command == Command.AC -> clearAll()
            command in MOVES && !typing -> move(command)
            LineEditor.edits(press.action) -> if (typing) edit(press.action)
            command == Command.MODE_MENU -> hooks.modeMenu(this)
            else -> LineMenus.open(host, command, this)
        }
    }

    /** A menu pick (hyp, CONST…) types into the cell, as its key would. */
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

    override fun render(): LcdFrame {
        val shownColumns = firstColumn until minOf(firstColumn + GridSpec.MAX_COLUMNS, cells.columns)
        val shownRows = firstRow until minOf(firstRow + GridSpec.MAX_ROWS, cells.rows)
        val spec = GridSpec(
            headers = shownColumns.map { layout.headers[it] },
            rows = shownRows.map { r ->
                GridRow(if (layout.rowNumbers) r + 1 else null, shownColumns.map { c -> cellText(cells[r, c]) })
            },
            current = GridCell(row - firstRow, column - firstColumn),
            value = fullText(cells[row, column]),
            title = layout.title,
            brackets = layout.brackets,
            footer = layout.footer,
            editing = if (typing) input.expr else null,
            editCursor = if (typing) input.cursor else null,
            cursorShape = LineEditor.cursorShape(input),
            hiddenLeft = firstColumn > 0,
            hiddenRight = shownColumns.last < cells.columns - 1,
        )
        return Screens.grid(host.indicators(), spec)
    }

    private fun edit(action: Action) {
        input = LineEditor.apply(input, action)
    }

    /** `=`: the typed value into the cell and on to the next one; nothing typed is the mode's solve. */
    private fun enter(press: Press) {
        if (!typing) {
            hooks.solve(this, press)
            return
        }
        when (val typed = host.evaluateLine(input.expr, press.interrupt)) {
            is LineValue.Ok -> store(typed.value as? Real)
            is LineValue.Error -> fail(typed.error, typed.at)
            LineValue.Interrupted -> Unit
        }
    }

    private fun store(value: Real?) {
        if (value == null) {
            fail(CalcError.MATH, null)
            return
        }
        cells[row, column] = value
        input = InputState.empty(natural = false)
        advance()
    }

    /** Row by row, left to right; the last cell keeps the cursor (research 03 §2.4, VERIFIED). */
    private fun advance() {
        when {
            column < cells.columns - 1 -> column++

            row < cells.rows - 1 -> {
                row++
                column = 0
            }
        }
        follow()
    }

    private fun move(command: Command?) {
        when (command) {
            Command.LEFT -> column = (column - 1).coerceAtLeast(0)
            Command.RIGHT -> column = (column + 1).coerceAtMost(cells.columns - 1)
            Command.UP -> row = (row - 1).coerceAtLeast(0)
            Command.DOWN -> row = (row + 1).coerceAtMost(cells.rows - 1)
            else -> Unit
        }
        follow()
    }

    private fun clearAll() {
        input = InputState.empty(natural = false)
        hooks.clear(this)
    }

    /** Keeps the cursor's cell in the three-by-three view, moving the view as little as possible. */
    private fun follow() {
        firstColumn = window(column, firstColumn, GridSpec.MAX_COLUMNS)
        firstRow = window(row, firstRow, GridSpec.MAX_ROWS)
    }

    private fun window(index: Int, first: Int, size: Int): Int = when {
        index < first -> index
        index >= first + size -> index - size + 1
        else -> first
    }

    private fun cellText(real: Real): String = host.formatter.cellText(real, host.setup)

    private fun fullText(real: Real): String =
        MathLayout.plainText(host.formatter.valueExpr(real, host.setup, host.mode, Radix.DEC))

    private fun refused(action: Action): Boolean = action is Action.Type && action.tok in REFUSED

    private companion object {
        val MOVES = setOf(Command.LEFT, Command.RIGHT, Command.UP, Command.DOWN)

        /** `Pol`, `Rec`, `÷R` and `:` "cannot be input" (research 03 §2.4); M+, M− and STO reach nothing here. */
        val REFUSED = setOf(Tok.POL, Tok.REC, Tok.DIV_R, Tok.COLON)
    }
}
