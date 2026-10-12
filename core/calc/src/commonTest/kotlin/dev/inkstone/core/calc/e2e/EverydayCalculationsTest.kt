package dev.inkstone.core.calc.e2e

import dev.inkstone.core.calc.Calculator
import dev.inkstone.core.calc.machine.Session
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Everyday calculations typed on the calculator **exactly as the app builds it**: the public
 * `Calculator()` constructor, physical keys in, the LCD's text out.
 *
 * ⚠️ This file exists because the app shipped with that constructor wired to the stand-in evaluator
 * the key machine was built against (integer arithmetic only), and every machine test was green —
 * they all run on documented fakes through the internal constructor, which is a fixture more generous
 * than production by construction. So `100 ÷ 1,80 =` showed **Math ERROR on the tablet** while
 * nothing failed. Nothing here may use the internal constructor: the point is the wiring.
 *
 * Expected values follow the default SETUP (MthIO-MathO, Deg, Norm 2, decimal comma) and the rules in
 * docs/CALCULATOR.md §3: a typed decimal makes a decimal result; integers and ÷ stay exact in MathO.
 */
class EverydayCalculationsTest {
    /** Key words as research 04 prints them; the decimal comma is its own key, so it is its own word. */
    private val rows = listOf(
        // The user's report: a decimal divisor.
        "100 ÷ 1 , 80 =" to "55,55555556",
        "100 ÷ 1 , 80 ÷ 0 , 8 =" to "69,44444444",
        "800 ÷ 1000 =" to "4/5",
        "0 , 8 × 1 , 80 =" to "1,44",
        "2 + 3 =" to "5",
        "1 ÷ 3 =" to "1/3",
        "0 , 5 + 0 , 25 =" to "0,75",
        "1 , 5 × 4 =" to "6",
        "1 − 0 , 9 − 0 , 1 =" to "0",
        "12 × 12 =" to "144",
        "1 ÷ 200 =" to "1/200",
        "3 , 14 × 2 =" to "6,28",
        "(−) 2 , 5 + 1 =" to "-1,5",
    )

    @Test
    fun `everyday calculations show the right answer through the public calculator`() {
        val failures = rows.mapNotNull { (keys, expected) ->
            val calculator = Calculator()
            Session.keys(keys).forEach { calculator.press(it) }
            val shown = calculator.frame.text.split('\n').last()
            if (shown == expected) null else "'$keys' showed '$shown', expected '$expected'"
        }
        assertEquals(emptyList(), failures)
    }
}
