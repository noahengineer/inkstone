package dev.inkstone.core.calc.e2e

import dev.inkstone.core.calc.Calculator
import dev.inkstone.core.calc.machine.Session
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Research 05's CMPLX, BASE-N and RATIO examples (and 04 E-48, CALC in CMPLX) typed on the calculator
 * as the app builds it — see [GuideCompExamplesTest] for why that matters. Each expected value is the
 * **whole** LCD text (input line, BASE-N's base label, result; ` | ` between lines), checked against
 * the guide's printed result. A row's first key word chooses the mode (`MODE 2` CMPLX, `MODE 4`
 * BASE-N, `MODE ▼ 3` RATIO).
 */
class GuideModeExamplesTest {
    private data class Row(val id: String, val keys: String, val shows: String)

    private val rows = listOf(
        Row("CMPLX01", "MODE 2 ( 2 + 6 ENG ) ÷ ( 2 ENG ) =", "(2+6i)÷(2i) | 3-i"),
        Row("CMPLX02", "MODE 2 2 ∠ 45 =", "2∠45 | √(2)+√(2)i"),
        Row("CMPLX03", "MODE 2 SETUP ▼ 3 2 √ 2 ▶ + √ 2 ▶ ENG =", "√(2)+√(2)i | 2∠45"),
        Row("CMPLX04", "MODE 2 ( 1 − ENG ) x⁻¹ =", "(1−i)⁻¹ | 1/2+1/2i"),
        Row("CMPLX05", "MODE 2 ( 1 + ENG ) x² + ( 1 − ENG ) x² =", "(1+i)²+(1−i)² | 0"),
        Row("CMPLX06", "MODE 2 SHIFT 2 2 2 + 3 ENG ) =", "Conjg(2+3i) | 2-3i"),
        Row("CMPLX07", "MODE 2 Abs 1 + ENG =", "Abs(1+i) | √(2)"),
        Row("CMPLX08", "MODE 2 SHIFT 2 1 1 + ENG ) =", "arg(1+i) | 45"),
        Row("CMPLX09", "MODE 2 √ 2 ▶ + √ 2 ▶ ENG SHIFT 2 3 =", "√(2)+√(2)i▶r∠θ | 2∠45"),
        Row("CMPLX10", "MODE 2 2 ∠ 45 SHIFT 2 4 =", "2∠45▶a+bi | √(2)+√(2)i"),
        Row("E48", "MODE 2 A + B ENG SHIFT 2 3 CALC √ 3 ) = 1 =", "A+Bi▶r∠θ | 2∠30"),
        Row("BASEN01", "MODE 4 log 11 + 1 =", "11+1 | Bin | 0000000000000100"),
        Row("BASEN02", "MODE 4 log 11 + 1 = AC x^ 1 tan + 1 =", "1F+1 | Hex | 00000020"),
        Row("BASEN03", "MODE 4 log 11 + 1 = AC x^ 1 tan + 1 = AC ln 7 + 1 =", "7+1 | Oct | 00000000010"),
        // The guide prints this sequence ending in `+`; it is a misprint for `=` (research 05).
        Row(
            "BASEN04",
            "MODE 4 x² SHIFT 3 ▼ 1 10 + SHIFT 3 ▼ 2 10 + SHIFT 3 ▼ 3 10 + SHIFT 3 ▼ 4 10 =",
            "d10+h10+b10+o10 | Dec | 36",
        ),
        Row("BASEN05a", "MODE 4 x² 15 × 37 =", "15×37 | Dec | 555"),
        Row("BASEN05b", "MODE 4 x² 15 × 37 = x^", "15×37 | Hex | 0000022B"),
        Row("BASEN05c", "MODE 4 x² 15 × 37 = x^ log", "15×37 | Bin | 0000001000101011"),
        Row("BASEN05d", "MODE 4 x² 15 × 37 = x^ log ln", "15×37 | Oct | 00000001053"),
        Row("BASEN06", "MODE 4 log 1010 SHIFT 3 1 1100 =", "1010 and 1100 | Bin | 0000000000001000"),
        Row("BASEN07", "MODE 4 log 1011 SHIFT 3 2 11010 =", "1011 or 11010 | Bin | 0000000000011011"),
        Row("BASEN08", "MODE 4 log 1010 SHIFT 3 3 1100 =", "1010 xor 1100 | Bin | 0000000000000110"),
        Row("BASEN09", "MODE 4 log 1111 SHIFT 3 4 101 =", "1111 xnor 101 | Bin | 1111111111110101"),
        Row("BASEN10", "MODE 4 log SHIFT 3 5 1010 ) =", "Not(1010) | Bin | 1111111111110101"),
        Row("BASEN11", "MODE 4 log SHIFT 3 6 101101 ) =", "Neg(101101) | Bin | 1111111111010011"),
        Row("RATIO01", "MODE ▼ 3 1 3 = 8 = 12 = =", "X= | 9/2"),
        Row("RATIO02", "MODE ▼ 3 1 1 = 2 = 10 = =", "X= | 5"),
        Row("RATIO03", "MODE ▼ 3 2 1 = 2 = 10 = =", "X= | 20"),
        Row("RATIO04", "MODE ▼ 3 1 0 = 2 = 10 = =", "Math ERROR | [AC]  :Cancel | [◀][▶]:Goto"),
    )

    @Test
    fun `research 05 CMPLX BASE-N and RATIO examples show the printed results`() {
        val failures = rows.mapNotNull { row ->
            val calculator = Calculator()
            Session.keys(row.keys).forEach { calculator.press(it) }
            val shown = calculator.frame.text.replace("\n", " | ")
            if (shown == row.shows) null else "${row.id} '${row.keys}' showed '$shown', expected '${row.shows}'"
        }
        assertEquals(emptyList(), failures)
    }
}
