package dev.inkstone.core.calc.mode

import dev.inkstone.core.calc.eval.Memory
import dev.inkstone.core.calc.machine.CellGrid
import dev.inkstone.core.calc.value.FractionReal
import dev.inkstone.core.calc.value.Real

/**
 * The Statistics Editor's data (research 03 §4.1): [size] rows of X, Y and FREQ, kept in a
 * [CellGrid] sized for the largest layout ([StatLayout.MAX_ROWS] × 3) and owned by [StatMode], so
 * the numbers outlive the editor screen. Every row has all three cells whatever the layout shows:
 * a 1-VAR row's Y is never read, and with FREQ off every frequency is 1, because a row is born
 * with it and changing the FREQ setting deletes the data ([StatMode.setupChanged]).
 *
 * A row is born X 0, Y 0, FREQ 1: "the untouched cells of the Y column show 0, and FREQ cells
 * default to 1" (research 03 §4.1, VERIFIED screens `img38`, `img42`). X's 0 is ⚠️ GUESS — a row
 * starts from X in every guide example, and an inserted row or a Y typed into the empty row is
 * the only way to see it.
 *
 * Capacity is the caller's to check ([StatLayout.capacity]): the table itself only refuses to go
 * past the grid.
 */
internal class StatTable {
    private val grid = CellGrid(rows = StatLayout.MAX_ROWS, columns = StatColumn.entries.size)

    /** Rows of data; row [size] is the editor's empty row, if the layout has room for it. */
    var size: Int = 0
        private set

    operator fun get(row: Int, column: StatColumn): Real {
        require(row in 0 until size) { "row $row is not data (size $size)" }
        return grid[row, column.ordinal]
    }

    operator fun set(row: Int, column: StatColumn, value: Real) {
        require(row in 0 until size) { "row $row is not data (size $size)" }
        grid[row, column.ordinal] = value
    }

    /** Adds a row of defaults at the end; false when the grid is full. */
    fun append(): Boolean = insert(size)

    /** Puts a row of defaults at [row], moving it and those after it down one; false when full. */
    fun insert(row: Int): Boolean {
        require(row in 0..size) { "row $row is outside 0..$size" }
        if (size == StatLayout.MAX_ROWS) return false
        for (r in size downTo row + 1) copyRow(from = r - 1, to = r)
        defaults(row)
        size++
        return true
    }

    /** Removes [row], moving the ones after it up (research 03 §4.1: `DEL` deletes a row, VERIFIED). */
    fun delete(row: Int) {
        require(row in 0 until size) { "row $row is not data (size $size)" }
        for (r in row until size - 1) copyRow(from = r + 1, to = r)
        size--
        defaults(size)
    }

    /** Every row gone (Del-A, a type change across 1-VAR and paired, leaving STAT, CLR All). */
    fun clear() {
        grid.zero()
        size = 0
    }

    /**
     * The rows as the statistics read them. With [frequency] off the FREQ column does not exist
     * and every row counts once (research 03 §4.5: "n = ΣFREQ"; without FREQ, n is the row count).
     */
    fun points(frequency: Boolean): List<StatPoint> = List(size) { r ->
        StatPoint(
            x = grid[r, StatColumn.X.ordinal].dec,
            y = grid[r, StatColumn.Y.ordinal].dec,
            f = if (frequency) grid[r, StatColumn.FREQ.ordinal].dec else ONE.dec,
        )
    }

    /** `5 F1/1 F0/1 F1/1 …`: the row count, then the whole grid's cells ([CellGrid.snapshot]). */
    fun snapshot(): String = "$size ${grid.snapshot()}"

    /**
     * Reads [snapshot]'s text; false, and the table unchanged, when it is not exactly such a text
     * or holds more rows than [capacity] — the layout it was saved under could not have held them
     * ([CellGrid.restore] leaves the grid alone when it refuses). Cells past [size] are kept as
     * read and never shown: a row is given its defaults whenever it is born.
     */
    fun restore(text: String, capacity: Int): Boolean {
        val count = text.substringBefore(' ').toIntOrNull()?.takeIf { it in 0..capacity } ?: return false
        if (!grid.restore(text.substringAfter(' ', ""))) return false
        size = count
        return true
    }

    private fun copyRow(from: Int, to: Int) {
        for (c in StatColumn.entries) grid[to, c.ordinal] = grid[from, c.ordinal]
    }

    private fun defaults(row: Int) {
        grid[row, StatColumn.X.ordinal] = Memory.ZERO
        grid[row, StatColumn.Y.ordinal] = Memory.ZERO
        grid[row, StatColumn.FREQ.ordinal] = ONE
    }

    private companion object {
        val ONE: Real = FractionReal(1, 1)
    }
}
