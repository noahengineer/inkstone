package dev.inkstone.core.calc.e2e

import dev.inkstone.core.calc.Calculator
import dev.inkstone.core.calc.machine.Session
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every STAT example of research 05 (EX-STAT-01…05) and the SA handout's five-number summary
 * (EX-SA-STAT-01), typed on the calculator **as the app builds it** — the public `Calculator()`,
 * never the internal constructor (docs/CALCULATOR.md §5, the 2026-10-11 note: every test was green
 * while the app's constructor still built a stand-in evaluator).
 *
 * Each row starts from a fresh calculator (the guide's "initial default setup", research 05) and
 * types the guide's keys as printed, the SETUP steps included, cumulatively where an example goes
 * on from the one before (EX-STAT-04 continues EX-STAT-03). The expected value is the **whole**
 * LCD text: the input line and the result for a calculation; the headers, the three rows on screen
 * (`n: x | y`) and the bottom line for an editor (research 03 §4.1); the items for a menu.
 *
 * Status of what is compared (research 05 "How to read a vector"): results are **P** (printed);
 * editor screens and menus are **S** (read off the guide's screens and the SA captures). One S
 * reading is not used as printed: research 05 says EX-STAT-05's screen shows "FREQ row 10 shows 2",
 * but the keys it prints (`…3 = 4 = 2 = 1 =`) and its own data line `{9;2, 10;1}` put the 2 in row 9
 * and 1 in row 10 — the keys win.
 */
class GuideStatExamplesTest {
    private data class Row(val id: String, val keys: String, val shows: String)

    private val rows = listOf(
        // EX-STAT-01 (screens img37, img38): the editor only, no result.
        Row("TYPES", "MODE 3", TYPE_MENU),
        Row("STAT01a", "MODE 3 2", "X | Y\n1:  | \n2:  | \n3:  | \n"),
        Row("STAT01b", "$STAT01 ▼ ▶", "X | Y\n1: 170 | 0\n2: 173 | 0\n3: 179 | 0\n0"),
        Row("STAT01c", "$STAT01 ▼ ▶ 66 = 68 = 75 =", "X | Y\n2: 173 | 68\n3: 179 | 75\n4:  | \n"),
        // EX-STAT-02: 1-VAR with FREQ, x̄ and σx.
        Row("STAT02a", STAT02, "X | FREQ\n3: 3 | 3\n4: 4 | 2\n5: 5 | 1\n1"),
        Row("STAT02b", "$STAT02 AC SHIFT 1 4 2 =", "x̄\n3"),
        Row("STAT02c", "$STAT02 AC SHIFT 1 4 2 = AC SHIFT 1 4 3 =", "σx\n1,154700538"),
        // EX-STAT-03: linear and logarithmic regression, Fix 3; a type change between paired keeps the data.
        Row("STAT03a", STAT03, "X | Y\n3: 200 | 8800\n4: 290 | 9310\n5:  | \n"),
        Row("STAT03b", "$STAT03 $LINEAR_R", "r\n0,923"),
        Row("STAT03c", "$STAT03 $LINEAR_R $LN_R", "r\n0,998"),
        Row("STAT03d", "$STAT03 $LINEAR_R $LN_R AC SHIFT 1 5 1 =", "A\n-3857,984"),
        Row("STAT03e", "$STAT03 $LINEAR_R $LN_R AC SHIFT 1 5 1 = AC SHIFT 1 5 2 =", "B\n2357,532"),
        // EX-STAT-04 continues EX-STAT-03 (Fix 3, ln X).
        Row("STAT04", "$STAT03 $LINEAR_R $LN_R AC (−) 130 SHIFT 1 5 4 =", "-130x̂\n4,861"),
        // EX-STAT-05: ▶t, then P( of it, Fix 3.
        Row("STAT05a", STAT05, "X | FREQ\n9: 9 | 2\n10: 10 | 1\n11:  | \n"),
        Row("STAT05b", "$STAT05 AC 3 SHIFT 1 5 4 =", "3▶t\n-0,762"),
        Row("STAT05c", "$STAT05 AC 3 SHIFT 1 5 4 = SHIFT 1 5 1 Ans ) =", "P(Ans)\n0,223"),
        // EX-SA-STAT-01 (SA slides 17–18): the menus as captured, minX and Q1.
        Row("SA01a", "$SA01 AC SHIFT 1", "1:Type  2:Data\n3:Sum  4:Var\n5:Distr  6:MinMax"),
        Row("SA01b", "$SA01 AC SHIFT 1 6", "1:minX  2:maxX\n3:Q1  4:med\n5:Q3"),
        Row("SA01c", "$SA01 AC SHIFT 1 6 1 =", "minX\n2018"),
        Row("SA01d", "$SA01 AC SHIFT 1 6 1 = AC SHIFT 1 6 3 =", "Q1\n2198,5"),
    )

    @Test
    fun `research 05 STAT examples show the printed results`() {
        val failures = rows.mapNotNull { row ->
            val calculator = Calculator()
            Session.keys(row.keys).forEach { calculator.press(it) }
            val shown = calculator.frame.text
            if (shown == row.shows) null else "${row.id} '${row.keys}' showed '$shown', expected '${row.shows}'"
        }
        assertEquals(emptyList(), failures)
    }

    private companion object {
        /** Screen `img36` (research 03 §4, VERIFIED). */
        const val TYPE_MENU = "1:1-VAR  2:A+BX\n3:_+CX²  4:ln X\n5:e^X  6:A•B^X\n7:A•X^B  8:1/X"

        const val STAT01 = "MODE 3 2 170 = 173 = 179 ="

        const val STAT02 = "SETUP ▼ 4 1 MODE 3 1 1 = 2 = 3 = 4 = 5 = ▼ ▶ 1 = 2 = 3 = 2 ="

        const val STAT03 = "SETUP ▼ 4 2 SETUP 6 3 MODE 3 2 20 = 110 = 200 = 290 = ▼ ▶ 3150 = 7310 = 8800 = 9310 ="
        const val LINEAR_R = "AC SHIFT 1 5 3 ="
        const val LN_R = "AC SHIFT 1 1 4 AC SHIFT 1 5 3 ="

        const val STAT05 = "SETUP ▼ 4 1 SETUP 6 3 MODE 3 1 0 = 1 = 2 = 3 = 4 = 5 = 6 = 7 = 9 = 10 = ▼ ▶ " +
            "1 = 2 = 1 = 2 = 2 = 2 = 3 = 4 = 2 = 1 ="

        const val SA01 = "MODE 3 1 2182 = 2323 = 2267 = 2334 = 2346 = 2175 = 2293 = 2263 = 2215 = 2271 = 2018 = 2254 ="
    }
}
