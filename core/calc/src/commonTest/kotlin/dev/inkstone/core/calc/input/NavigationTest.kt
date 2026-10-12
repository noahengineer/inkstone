package dev.inkstone.core.calc.input

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cursor movement, from the sequences research 02 §7.3 prints. Each row is the keys and where the
 * cursor must be afterwards: a path of (item, slot) steps and an index.
 */
class NavigationTest {
    private class Row(val keys: String, val at: Cursor, val natural: Boolean = true)

    private fun at(index: Int, vararg steps: Pair<Int, Int>) =
        Cursor(steps.map { (item, slot) -> PathStep(item, slot) }, index)

    private val templates = listOf(
        Row("FRAC", at(0, 0 to 0)),
        Row("2 FRAC", at(0, 0 to 1)),
        Row("FRAC 2 ▼", at(0, 0 to 1)),
        Row("FRAC 2 ▶", at(0, 0 to 1)),
        Row("FRAC 2 ▶ 3 ▶", at(1)),
        Row("FRAC 2 + √ 2 ▶", at(3, 0 to 0)),
        Row("FRAC 2 + √ 2 ▶ ▶", at(0, 0 to 1)),
        Row("MIXED", at(0, 0 to 0)),
        Row("MIXED 3 ▶", at(0, 0 to 1)),
        Row("MIXED 3 ▶ 1 ▼", at(0, 0 to 2)),
        Row("∫ X ▶", at(0, 0 to 1)),
        Row("∫ X ▶ 1 ▶", at(0, 0 to 2)),
        Row("∫ X ▶ 0 ▲", at(0, 0 to 2)),
        Row("∫ X ▶ 1 ▶ 2 ▶", at(1)),
        Row("d/dx X ▶", at(0, 0 to 1)),
        Row("Σ X ▶ 1 ▶", at(0, 0 to 2)),
        Row("log□ 2 ▶", at(0, 0 to 1)),
        Row("log□ 2 ▶ 4 ▶", at(1)),
        Row("ⁿ√ 5 ▶", at(0, 0 to 1)),
        Row("√ 2 ▶", at(1)),
        Row("2 x^ 3 ▶", at(2)),
        Row("e^ 5 ▶", at(1)),
        Row("10^ 5 ▶", at(1)),
        Row("Abs 2 ▶", at(1)),
    )

    @Test
    fun `the cursor walks template slots in the guide order`() {
        for (row in templates) assertEquals(row.at, Keys.type(row.keys, row.natural).cursor, row.keys)
    }

    private val lines = listOf(
        Row("1 2 ▶", at(0)),
        Row("1 2 START ◀", at(2)),
        Row("1 2 ◀ ◀ ◀", at(2)),
        Row("FRAC 1 ▼ 2 ▶ ◀", at(1, 0 to 1)),
        Row("FRAC 1 ▼ 2 ▶ ◀ ◀ ◀", at(1, 0 to 0)),
        Row("FRAC 1 ▼ 2 ▶ ◀ ◀ ◀ ◀ ◀", at(0)),
        Row("1 2", at(2), natural = false),
        Row("1 2 ▲", at(0), natural = false),
        Row("1 2 ▲ ▼", at(2), natural = false),
        Row("1 2 ▶", at(2), natural = false),
        Row("1 2 START ◀", at(0), natural = false),
        Row("1 2 ▲", at(2)),
        Row("FRAC 1 ▼ 2 ▲", at(1, 0 to 0)),
        Row("FRAC FRAC 1 ▼ 2 ▼", at(0, 0 to 1)),
    )

    @Test
    fun `the line wraps in Natural display and stops in LineIO`() {
        for (row in lines) assertEquals(row.at, Keys.type(row.keys, row.natural).cursor, row.keys)
    }

    @Test
    fun `a move cancels a pending INS`() {
        val state = Keys.type("1 2 START INS ▶")
        assertEquals(false, state.wrapPending)
        assertEquals("1√()2", linearText(Keys.press(state, "√").expr))
    }
}
