package dev.inkstone.core.calc.parse

import dev.inkstone.core.calc.input.InputState
import dev.inkstone.core.calc.input.Keys
import dev.inkstone.core.calc.input.linearText
import kotlin.test.Test
import kotlin.test.assertEquals

/** One guide input: its id, the keys, the line they must edit to, and optionally the parse. */
class Vector(
    val id: String,
    val keys: String,
    val linear: String,
    val tree: String? = null,
    val natural: Boolean = true,
)

/**
 * Every typed input in research 04 and 05 edits to the line the guide prints and parses. The keys
 * go through [InputState] exactly as the machine layer will send them, so a navigation or FRAC
 * rule that is wrong shows up here as a wrong line rather than as a wrong answer three layers on.
 */
class GuideVectorsTest {
    @Test
    fun `display memory and history inputs edit to the printed line and parse`() = BASIC_VECTORS.forEach(::check)

    @Test
    fun `general and COMP inputs edit to the printed line and parse`() = COMP_VECTORS.forEach(::check)

    @Test
    fun `calculus CALC SOLVE CONST and CONV inputs edit to the printed line and parse`() =
        CALCULUS_VECTORS.forEach(::check)

    @Test
    fun `mode chapter inputs edit to the printed line and parse`() = MODE_VECTORS.forEach(::check)

    @Test
    fun `a Natural template and its LineIO spelling parse to the same tree`() {
        val pairs = listOf(
            "E-07" to "E-08", "E-18" to "E-19", "E-20" to "E-21", "E-24" to "E-25", "E-28" to "E-29",
            "E-31" to "E-32", "E-33" to "E-34", "E-37" to "E-38", "C-05" to "C-07", "C-08" to "C-09", "E-10" to "E-11",
        )
        val all = (COMP_VECTORS + CALCULUS_VECTORS).associateBy { it.id }
        for ((natural, linear) in pairs) {
            assertEquals(treeOf(all.getValue(natural)), treeOf(all.getValue(linear)), "$natural vs $linear")
        }
    }

    @Test
    fun `replay opens the line with the cursor at its end`() {
        // C-28: 4×3+2 = 14, then ◀ DEL DEL − 7 = 5. ◀ on a result reopens the line at its end.
        val replayed = InputState.editing(Keys.type("4 × 3 + 2").expr, natural = true)
        assertEquals("4×3−7", linearText(Keys.type("DEL DEL − 7", start = replayed).expr))
    }

    private fun check(vector: Vector) {
        val state = Keys.type(vector.keys, vector.natural)
        assertEquals(vector.linear, linearText(state.expr), "${vector.id} line")
        val node = parsed(state.expr, vector.id).node
        vector.tree?.let { assertEquals(it, show(node), "${vector.id} tree") }
    }

    private fun treeOf(vector: Vector): String = show(parsed(Keys.type(vector.keys, vector.natural).expr).node)
}
