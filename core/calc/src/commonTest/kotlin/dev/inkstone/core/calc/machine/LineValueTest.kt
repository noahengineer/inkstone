package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.input.Keys
import dev.inkstone.core.calc.value.FractionReal
import kotlin.test.Test
import kotlin.test.assertEquals

/** [evaluateLine], the shared "evaluate what was typed in this cell or prompt" (research 03 §2.4). */
class LineValueTest {
    private val host: Host = Session().machine

    private fun valueOf(keys: String) = host.evaluateLine(Keys.type(keys, natural = false).expr, { false })

    @Test
    fun aTypedLineGivesItsValueWithoutTouchingAns() {
        assertEquals(LineValue.Ok(FractionReal(7, 1)), valueOf("3 + 4"))
        assertEquals(FractionReal(0, 1), host.memory.ans)
    }

    @Test
    fun aParseErrorCarriesItsPlace() {
        assertEquals(LineValue.Error(CalcError.SYNTAX, Cursor(index = 2)), valueOf("1 + × 2"))
    }

    @Test
    fun anEvaluationErrorCarriesItsPlace() {
        assertEquals(LineValue.Error(CalcError.MATH, Cursor(index = 3)), valueOf("1 ÷ 0 + 2"))
    }

    @Test
    fun aMultiStatementIsASyntaxError() {
        assertEquals(LineValue.Error(CalcError.SYNTAX, null), valueOf("1 : 2"))
    }
}
