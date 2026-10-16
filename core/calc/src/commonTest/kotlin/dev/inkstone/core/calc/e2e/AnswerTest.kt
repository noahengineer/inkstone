package dev.inkstone.core.calc.e2e

import dev.inkstone.core.calc.Calculator
import dev.inkstone.core.calc.machine.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [dev.inkstone.core.calc.lcd.LcdFrame.answer] — what the app lets a user drag onto a page — on the
 * public `Calculator()`, as the app builds it (see `EverydayCalculationsTest` for why that matters).
 *
 * The rule under test: an answer exists exactly while a result is on the glass, it is spelled as the
 * display shows it, and nothing else on the glass is one — not the line being typed, not BASE-N's
 * resting `0`, not an error, not a menu, not SOLVE's `L-R=`.
 */
class AnswerTest {
    private fun after(keys: String): Calculator = Calculator().also { c -> Session.keys(keys).forEach { c.press(it) } }

    @Test
    fun `a result is the answer as the display shows it`() {
        val rows = listOf(
            "100 ÷ 1 , 80 =" to "55,55555556",
            "1 ÷ 200 =" to "1/200",
            "(−) 2 , 5 + 1 =" to "-1,5",
            "12 × 12 =" to "144",
            "5 ÷R 2 =" to "2;R=1",
        )
        val wrong = rows.mapNotNull { (keys, expected) ->
            val answer = after(keys).frame.answer
            if (answer == expected) null else "'$keys' answered '$answer', expected '$expected'"
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `a two-line result is both lines`() {
        assertEquals("r=2\nθ=45", after("SETUP 2 Pol √ 2 ) ; √ 2 ) ) =").frame.answer)
    }

    @Test
    fun `SOLVE answers its variable and not L-R`() {
        assertEquals("X=2", after("3 X + 4 EQ 2 X + 6 SOLVE =").frame.answer)
    }

    @Test
    fun `nothing is an answer while typing`() {
        assertNull(Calculator().frame.answer)
        assertNull(after("12 ×").frame.answer)
        // A result, then the next key starts a new line: the old answer is gone with it.
        assertNull(after("2 + 3 = 4").frame.answer)
    }

    @Test
    fun `an error or a menu or the glass switched off is not an answer`() {
        assertNull(after("1 ÷ 0 =").frame.answer)
        assertNull(after("MODE").frame.answer)
        assertNull(after("2 + 3 = OFF").frame.answer)
    }
}
