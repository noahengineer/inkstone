package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.value.DecimalReal
import dev.inkstone.core.calc.value.FractionReal
import dev.inkstone.core.calc.value.Real
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared editor (research 03 §2.4) as a stage-3 mode will use it, on the largest coefficient
 * editor the calculator has: EQN's three unknowns, three numbered rows of `a b c d` (03 §6.1). The
 * controller here is a test stand-in registered for EQN; RATIO's real one is `RatioModeTest`'s.
 */
class CellEditorTest {
    private class EqnLike : ModeController {
        override val mode = Mode.EQN
        val grid = CellGrid(rows = 3, columns = 4)
        var solves = 0

        override fun enter(host: Host) {
            val layout = EditorLayout(listOf("a", "b", "c", "d"), rowNumbers = true)
            host.show(CellEditor(host, grid, layout, Solves()))
        }

        private inner class Solves : EditorHooks {
            override fun solve(editor: CellEditor, press: Press) {
                solves++
            }
        }
    }

    private val eqn = EqnLike()

    private fun session(): Session = Session(modes = ModeRegistry.standard(listOf(eqn))).press("MODE 5")

    private fun cells(): List<Long> = eqn.grid.values.map { (it as FractionReal).num }

    /** "Entry order is row by row, left to right, with `[=]` after each value" (research 03 §6.1). */
    @Test
    fun valuesGoInRowByRow() {
        val s = session().press("1 = 2 = 3 = 4 = 5 =")
        assertEquals(listOf(1L, 2, 3, 4, 5, 0, 0, 0, 0, 0, 0, 0), cells())
        assertEquals("a | b | c\n1: 1 | 2 | 3\n2: 5 | 0 | 0\n3: 0 | 0 | 0\n0", s.text)
    }

    /** "In the 3-unknown editor with the cursor on `d`, the headers shown are `b c d`" (VERIFIED). */
    @Test
    fun theViewFollowsTheCursorSideways() {
        val s = session().press("1 = 2 = 3 =")
        assertEquals("b | c | d\n1: 2 | 3 | 0\n2: 0 | 0 | 0\n3: 0 | 0 | 0\n0", s.text)
        s.press("4 =")
        assertTrue(s.text.startsWith("a | b | c\n"))
    }

    /** "After `[=]` on the last cell, the cursor stays on that cell" (VERIFIED); then a bare `=` is the mode's. */
    @Test
    fun theLastCellKeepsTheCursorAndABareEqualsSolves() {
        val s = session().press((1..12).joinToString(" ") { "$it =" })
        assertEquals((1L..12L).toList(), cells())
        assertEquals("12", s.lines.last())
        assertEquals(0, eqn.solves)
        s.press("=")
        assertEquals(1, eqn.solves)
        s.press("7 =")
        assertEquals(7L, cells().last())
        assertEquals(1, eqn.solves)
    }

    /** ⚠️ GUESS pinned (CellEditor.move): the cursor keys stop at the edges rather than wrapping. */
    @Test
    fun theCursorKeysMoveBetweenCellsAndStopAtTheEdges() {
        val s = session().press((1..12).joinToString(" ") { "$it =" })
        s.press("▲ ▲ ▲ ◀ ◀ ◀ ◀")
        assertEquals("1", s.lines.last())
        assertTrue(s.text.startsWith("a | b | c\n"))
        s.press("▶ ▶ ▶ ▶ ▼")
        assertEquals("8", s.lines.last())
        assertTrue(s.text.startsWith("b | c | d\n"))
    }

    /** While a value is being typed the cursor keys and DEL edit it, and it shows on the bottom line. */
    @Test
    fun typingShowsOnTheBottomLineAndTheCursorKeysEditIt() {
        val s = session().press("1 2")
        assertEquals("12", s.lines.last())
        assertNotNull(s.frame.cursor)
        s.press("◀ 3")
        assertEquals("132", s.lines.last())
        s.press("▶ DEL DEL DEL")
        assertNull(s.frame.cursor)
        s.press("▶ 4 =")
        assertEquals(listOf(0L, 4), cells().take(2))
    }

    /** "`[AC]` in the editor sets all coefficients to zero" (VERIFIED), and drops a value half typed. */
    @Test
    fun acZeroesEveryCell() {
        val s = session().press("1 = 2 = 3 = 4 = 5 = 6")
        s.press("AC")
        assertEquals(List(12) { 0L }, cells())
        assertEquals("0", s.lines.last())
        assertNull(s.frame.cursor)
    }

    /** Research 02 §11 in a cell: AC drops the typed value, Goto returns to it at the error. */
    @Test
    fun aCellErrorReturnsToTheValueTyped() {
        val s = session().press("1 ÷ 0 =")
        assertEquals("Math ERROR", s.lines[0])
        s.press("AC")
        assertEquals("0", s.lines.last())
        assertNull(s.frame.cursor)
        s.press("1 ÷ 0 = ▶")
        assertEquals("1÷0", s.lines.last())
        assertNotNull(s.frame.cursor)
        s.press("DEL 2 =")
        assertEquals(FractionReal(1, 2), eqn.grid[0, 0])
    }

    /** hyp and CONST type into the cell as they would into the line (⚠️ GUESS); CONST 41 is a Syntax ERROR. */
    @Test
    fun typingMenusTypeIntoTheCell() {
        assertEquals("sinh(", session().press("hyp 1").lines.last())
        assertEquals("2C0", session().press("2 CONST 28").lines.last())
        val s = session().press("2 CONST 41")
        assertEquals("Syntax ERROR", s.lines[0])
        s.press("◀")
        assertEquals("2", s.lines.last())
    }

    /** Research 03 §2.4: Pol, Rec, ÷R and multi-statements "cannot be input"; M+ and STO do nothing. */
    @Test
    fun theRefusedKeysTypeNothing() {
        val s = session().press("Pol SHIFT − : M+ STO 3")
        assertEquals("3", s.lines.last())
        assertFalse(s.text.contains("Pol"))
    }

    @Test
    fun aGridSavesItsCellsAndRefusesTextThatIsNotExactlyThem() {
        val grid = CellGrid(rows = 1, columns = 3)
        grid[0, 1] = FractionReal(-3, 4)
        grid[0, 2] = DecimalReal(Dec.of(5).div(Dec.of(7)))
        val text = grid.snapshot()
        val back = CellGrid(rows = 1, columns = 3)
        assertTrue(back.restore(text))
        assertEquals(grid.values, back.values)
        val before: List<Real> = back.values
        for (bad in listOf("", "F1/1 F2/1", "F1/1 F2/1 F3/1 F4/1", "F1/1 C F3/1", "F1/1 F2/4 F3/1")) {
            assertFalse(back.restore(bad), bad)
            assertEquals(before, back.values, bad)
        }
    }
}
