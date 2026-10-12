package dev.inkstone.core.calc.parse

import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.input.Keys
import dev.inkstone.core.calc.input.PathStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The evaluator's Goto needs to know where each node ended (research 02 §11, research 04 F-02). */
class SourceMapTest {
    @Test
    fun `the division in 14 divided by 0 times 2 ends just after the 0`() {
        val ok = parsed(Keys.type("1 4 ÷ 0 × 2").expr)
        val product = assertIs<Binary>(ok.node)
        assertEquals(Cursor(index = 4), ok.spans.endOf(product.left))
        assertEquals(Cursor(index = 6), ok.spans.endOf(product))
    }

    @Test
    fun `a node inside a template slot ends inside that slot`() {
        val ok = parsed(Keys.type("2 + √ 1 ÷ 0 ▶").expr)
        val sum = assertIs<Binary>(ok.node)
        val root = assertIs<Call>(sum.right)
        assertEquals(Cursor(listOf(PathStep(2, 0)), 3), ok.spans.endOf(root.args.single()))
        assertEquals(Cursor(index = 3), ok.spans.endOf(root))
    }

    @Test
    fun `a SOLVE variable cut off the line still ends where it was typed`() {
        val ok = assertIs<ParseResult.Ok>(parse(Keys.type("1 : Y EQ X ; Y").expr, solve = true))
        val line = assertIs<Statements>(ok.node)
        assertEquals(Cursor(index = 7), ok.spans.endOf(line))
        assertEquals(Cursor(index = 7), ok.spans.endOf(line.list.last()))
        assertEquals(Cursor(index = 1), ok.spans.endOf(line.list.first()))
    }

    @Test
    fun `equal nodes at different places are told apart`() {
        val ok = parsed(Keys.type("π + π").expr)
        val sum = assertIs<Binary>(ok.node)
        assertEquals(Cursor(index = 1), ok.spans.endOf(sum.left))
        assertEquals(Cursor(index = 3), ok.spans.endOf(sum.right))
        assertNull(ok.spans.endOf(Sym(Symbolic.PI)))
    }
}
