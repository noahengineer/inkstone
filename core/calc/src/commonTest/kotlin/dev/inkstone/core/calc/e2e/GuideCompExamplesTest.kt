package dev.inkstone.core.calc.e2e

import dev.inkstone.core.calc.Calculator
import dev.inkstone.core.calc.machine.Session
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The guide's own worked examples, typed on the calculator **as the app builds it** — the public
 * `Calculator()`, physical keys in, the LCD's text out — so the key machine, the real evaluator and
 * the real formatter are tested together, which no other suite does (each of those runs on fakes or
 * on its own layer).
 *
 * Rows are research 04's vectors (A–E: the user's guide; H: captures of a real ZA PLUS II from the
 * Casio SA handout) and research 05's CONV vectors, with the keys written as the guide prints them
 * ([Session.keys]). Each row starts from a fresh calculator, which is the guide's DEFAULT state
 * (MthIO-MathO, Deg, Norm 2); a row that needs another setup types it first (`SETUP 2` is LineIO).
 *
 * The expected text is what the LCD shows **after the input line**, in `LcdFrame.text`'s plain
 * spelling: a stacked fraction is `a/b`, a surd is `√(2)`, exponential form is `×10^-3`, a stacked
 * two-part result is joined with ` | `. Every value was checked against the guide's printed one.
 */
class GuideCompExamplesTest {
    private data class Row(val id: String, val keys: String, val shows: String)

    private val rows = listOf(
        Row("A01", "1 ÷ 200 =", "1/200"),
        Row("A02", "SETUP 1 2 SETUP 8 1 1 ÷ 200 =", "5×10^-3"),
        Row("A03", "SETUP 1 2 1 ÷ 200 =", "0,005"),
        Row("A04", "SETUP 2 SETUP 8 1 1 ÷ 200 =", "5×10^-3"),
        Row("A05", "SETUP 2 1 ÷ 200 =", "0,005"),
        Row("A06", "SETUP 2 SETUP 6 3 100 ÷ 7 =", "14,286"),
        Row("A07", "SETUP 2 SETUP 6 2 100 ÷ 7 =", "14,29"),
        Row("A08", "SETUP 2 SETUP 7 5 1 ÷ 7 =", "1,4286×10^-1"),
        Row("A09", "SETUP 2 SETUP 7 4 1 ÷ 7 =", "1,429×10^-1"),
        Row("A10", "SETUP 2 SETUP 7 0 1 ÷ 7 =", "1,428571429×10^-1"),
        Row("B01", "4 × sin 30 ) × ( 30 + 10 × 3 ) =", "120"),
        Row("B02", "4 sin 30 ) ( 30 + 10 × 3 =", "120"),
        Row("B03", "FRAC 2 + √ 2 ▶ ▶ 1 + √ 2 =", "√(2)"),
        Row("B05", "10 √ 2 ▶ + 15 × 3 √ 3 =", "45√(3)+10√(2)"),
        Row("B06", "99 √ 999 =", "3129,089165"),
        Row("C01a", "π ÷ 6 =", "1/6π"),
        Row("C01b", "π ÷ 6 = S⇔D", "0,5235987756"),
        Row("C01c", "π ÷ 6 = S⇔D S⇔D", "1/6π"),
        Row("C02a", "( √ 2 ▶ + 2 ) × √ 3 =", "√(6)+2√(3)"),
        Row("C02b", "( √ 2 ▶ + 2 ) × √ 3 = S⇔D", "5,913591358"),
        Row("C03a", "SETUP 2 1 ÷ 5 =", "0,2"),
        Row("C03b", "SETUP 2 1 ÷ 5 = S⇔D", "1⌟5"),
        Row("C04a", "SETUP 2 1 − 4 FRAC 5 =", "1⌟5"),
        Row("C04b", "SETUP 2 1 − 4 FRAC 5 = S⇔D", "0,2"),
        Row("C05", "2 FRAC 3 ▶ + 1 FRAC 2 =", "7/6"),
        Row("C06", "FRAC 2 ▼ 3 ▶ + FRAC 1 ▼ 2 =", "7/6"),
        Row("C07", "SETUP 2 2 FRAC 3 + 1 FRAC 2 =", "7⌟6"),
        Row("C08", "4 − MIXED 3 ▶ 1 ▼ 2 =", "1/2"),
        Row("C09", "SETUP 2 4 − 3 FRAC 1 FRAC 2 =", "1⌟2"),
        Row("C10", "150 × 20 % =", "30"),
        Row("C11", "660 ÷ 880 % =", "75"),
        Row("C12", "2500 + 2500 × 15 % =", "2875"),
        Row("C13", "3500 − 3500 × 25 % =", "2625"),
        Row("C14", "2 °'\" 20 °'\" 30 °'\" + 0 °'\" 39 °'\" 30 °'\" =", "3°0'0\""),
        Row("C15a", "2 °'\" 15 °'\" 18 °'\" =", "2°15'18\""),
        Row("C15b", "2 °'\" 15 °'\" 18 °'\" = °'\"", "2,255"),
        Row("C16a", "3 + 3 : 3 × 3 =", "6"),
        Row("C16b", "3 + 3 : 3 × 3 = =", "9"),
        Row("C17a", "1234 = ENG", "1,234×10^3"),
        Row("C17b", "1234 = ENG ENG", "1234×10^0"),
        Row("C18a", "123 = ←ENG", "0,123×10^3"),
        Row("C18b", "123 = ←ENG ←ENG", "0,000123×10^6"),
        Row("C19", "5 ÷R 2 =", "2;R=1"),
        Row("C20", "SETUP 2 5 ÷R 2 =", "2 | R=1"),
        Row("C22", "10 + 17 ÷R 6 =", "12"),
        Row("C25", "1014 = FACT", "2×3×13²"),
        Row("C26", "4104676 = FACT", "2²×(1026169)"),
        Row("C27a", "1 + 1 = 2 + 2 = 3 + 3 = ▲", "4"),
        Row("C27b", "1 + 1 = 2 + 2 = 3 + 3 = ▲ ▲", "2"),
        Row("C28", "4 × 3 + 2 = ◀ DEL DEL − 7 =", "5"),
        Row("D01", "SETUP 2 3 × 4 = ÷ 30 =", "0,4"),
        Row("D02", "123 + 456 = 789 − Ans =", "210"),
        Row("D03", "1 = 1 = Ans + PreAns = = =", "5"),
        Row("D04a", "3 + 5 STO (−)", "8"),
        Row("D04b", "3 + 5 STO (−) A × 10 =", "80"),
        Row("D04c", "3 + 5 STO (−) RCL (−)", "8"),
        Row("D05", "0 STO M+ 10 × 5 M+ 10 + 5 M− RCL M+", "35"),
        Row("E01", "SETUP 2 sin 30 ) =", "0,5"),
        Row("E02", "SETUP 2 sin⁻¹ 0 , 5 ) =", "30"),
        Row("E03", "hyp 1 1 ) =", "1,175201194"),
        Row("E04", "hyp 5 1 ) =", "0"),
        Row("E05", "( π ÷ 2 ) DRG 2 =", "90"),
        Row("E06", "50 DRG 3 =", "45"),
        Row("E07", "SETUP 7 3 SHIFT ln 5 ▶ × 2 =", "2,97×10^2"),
        Row("E09", "log 1000 ) =", "3"),
        Row("E10", "log 2 ; 16 ) =", "4"),
        Row("E11", "log□ 2 ▶ 16 =", "4"),
        Row("E12", "log□ 2 ▶ 4 x³ =", "6"),
        Row("E13", "log□ 2 ▶ 4 ▶ x³ =", "8"),
        Row("E14", "SETUP 7 3 ln 90 ) =", "4,50×10^0"),
        Row("E15", "1 , 2 × 10 x^ 3 =", "1200"),
        Row("E16", "( 1 + 1 ) x^ 2 + 2 =", "16"),
        Row("E17", "( 5 x² ) x³ =", "15625"),
        Row("E18", "SHIFT x^ 5 ▶ 32 =", "2"),
        Row("E19", "SETUP 2 5 SHIFT x^ 32 ) =", "2"),
        Row("E20a", "SETUP 6 3 √ 2 ▶ × 3 =", "3√(2)"),
        Row("E20b", "SETUP 6 3 √ 2 ▶ × 3 = SHIFT=", "4,243"),
        Row("E21", "SETUP 2 SETUP 6 3 √ 2 ) × 3 =", "4,243"),
        Row("E22", "SETUP 2 SHIFT √ 5 ) + SHIFT √ (−) 27 ) =", "-1,290024053"),
        Row("E23", "SETUP 2 ( 3 x⁻¹ − 4 x⁻¹ ) x⁻¹ =", "12"),
        Row("E24", "∫ ln X ) ▶ 1 ▶ e =", "1"),
        Row("E26", "SETUP 2 ∫ 1 ÷ X x² ; 1 ; 5 ; 1 ×10 (−) 7 ) =", "0,8"),
        Row("E27", "SETUP 4 ∫ ( sin X ) + cos X ) ) x² ▶ 0 ▲ π =", "π"),
        Row("E28", "SETUP 4 SHIFT ∫ sin X ) ▶ FRAC π ▶ 2 =", "0"),
        Row("E30", "SETUP 2 SHIFT ∫ 3 X x² − 5 X + 2 ; 2 ; 1 ×10 (−) 12 ) =", "7"),
        Row("E31", "SHIFT log□ X + 1 ▶ 1 ▶ 5 =", "20"),
        Row("E33", "Pol √ 2 ▶ ; √ 2 ▶ ) =", "r=2;θ=45"),
        Row("E34", "SETUP 2 Pol √ 2 ) ; √ 2 ) ) =", "r=2 | θ=45"),
        Row("E35", "SHIFT − √ 2 ▶ ; 45 ) =", "X=1;Y=1"),
        Row("E36", "( 5 + 3 ) x! =", "40320"),
        Row("E37", "Abs 2 − 7 ▶ × 2 =", "10"),
        Row("E41", "10 SHIFT × 4 =", "5040"),
        Row("E42", "10 SHIFT ÷ 4 =", "210"),
        Row("E43", "SETUP 2 SETUP 6 3 10 ÷ 3 × 3 =", "10,000"),
        Row("E44", "SETUP 2 SETUP 6 3 SHIFT 0 10 ÷ 3 ) × 3 =", "9,999"),
        Row("E45", "ALPHA × 28 ; 35 ) =", "7"),
        Row("E46", "ALPHA ÷ 9 ; 15 ) =", "45"),
        Row("E47a", "3 A + B CALC 5 = 10 =", "25"),
        Row("E47b", "3 A + B CALC 5 = 10 = CALC 7 = 20 =", "41"),
        Row("E49", "Y EQ A X x² + B SOLVE 0 = 1 = (−) 2 = 1 =", "X=1,414213562 | L-R=0"),
        Row("E51", "CONST 28 =", "299792458"),
        Row("E52", "FRAC 1 ▼ √ CONST 32 CONST 33 =", "299792458"),
        // research 05: all three CONV examples are LineIO.
        Row("CONV01", "SETUP 2 5 CONV 02 =", "1,968503937"),
        Row("CONV02", "SETUP 2 100 CONV 22 =", "3,527396584"),
        Row("CONV03", "SETUP 2 (−) 31 CONV 38 =", "-23,8"),
        // research 04 H: screen captures of a real fx-991ZA PLUS II (the Casio SA handout).
        Row("H01", "FRAC 1 ▼ 800 = S⇔D", "0,00125"),
        Row("H02", "SETUP 6 2 FRAC 2 ▼ 3 = S⇔D", "0,67"),
        Row("H03", "75 ÷R 4 =", "18;R=3"),
        Row("H04", "3 A x² + 4 B CALC 6 = 2 =", "116"),
        Row("H05", "3 X + 4 EQ 2 X + 6 SOLVE =", "X=2 | L-R=0"),
        Row("H07", "CONV 37 ◀ 356 =", "180"),
        Row("H08", "CONV 19 ◀ 120 = S⇔D", "33,33333333"),
    )

    @Test
    fun `research 04 COMP examples show the printed results`() {
        val failures = rows.mapNotNull { row ->
            val calculator = Calculator()
            Session.keys(row.keys).forEach { calculator.press(it) }
            val shown = calculator.frame.text.split('\n').drop(1).joinToString(" | ")
            if (shown == row.shows) null else "${row.id} '${row.keys}' showed '$shown', expected '${row.shows}'"
        }
        assertEquals(emptyList(), failures)
    }
}
