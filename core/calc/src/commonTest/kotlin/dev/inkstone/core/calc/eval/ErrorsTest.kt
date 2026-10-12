package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.input.Cursor
import kotlin.test.Test
import kotlin.test.assertEquals

/** Research 02 §11 and §13: inputs outside a function's range, results outside ±1×10¹⁰⁰, ÷0. */
private val MATH_ERRORS = listOf(
    Case("F-02", "MATH"),
    Case("reciprocal of zero", "MATH", keys = "0 x⁻¹"),
    Case("root of a negative", "MATH", keys = "√ (−) 1"),
    Case("tan at 90", "MATH", keys = "tan 9 0 )"),
    Case("factorial past 69", "MATH", keys = "7 0 !"),
    Case("factorial of a fraction", "MATH", keys = "FRAC 1 ▼ 2 ▶ !"),
    Case("sin past its range", "MATH", keys = "sin 9 ×10 9 )"),
    Case("arccos past 1", "MATH", keys = "cos⁻¹ 2 )"),
    Case("log of zero", "MATH", keys = "ln 0 )"),
    Case("nPr with r past n", "MATH", keys = "1 0 nPr 1 1"),
    Case("RanInt# with a above b", "MATH", keys = "RanInt# 6 ; 1 )"),
    // The range is a < b, strictly (research 02 §13).
    Case("RanInt# with a equal to b", "MATH", keys = "RanInt# 3 ; 3 )"),
    Case("overflow", "MATH", keys = "1 ×10 9 9 × 1 0"),
    Case("literal overflow", "MATH", keys = "1 ×10 1 0 0"),
    Case("power of zero", "MATH", keys = "0 x^ 0"),
    Case("even root of a negative", "MATH", keys = "ⁿ√ 2 ▶ (−) 4"),
)

/**
 * Items the mode or the position forbids: Syntax ERROR (a GUESS throughout, see Restrictions).
 * On the real calculator most cannot be typed at all in the wrong mode.
 */
private val SYNTAX_ERRORS = listOf(
    Case("i in COMP", "SYNTAX", keys = "2 i"),
    Case("PreAns outside COMP", "SYNTAX", keys = "PreAns", mode = Mode.CMPLX),
    Case("MatA in COMP", "SYNTAX", keys = "MatA", natural = false),
    Case("VctA in MATRIX", "SYNTAX", keys = "VctA", natural = false, mode = Mode.MATRIX),
    Case("Pol inside an expression", "SYNTAX", keys = "Pol 1 ; 1 ) + 1"),
    Case("remainder division in CMPLX", "SYNTAX", keys = "5 ÷R 2", mode = Mode.CMPLX),
    Case("logic in COMP", "SYNTAX", keys = "1 and 1", natural = false),
    Case("polar command in COMP", "SYNTAX", keys = "1 ▶r∠θ"),
    Case("angle entry in COMP", "SYNTAX", keys = "2 ∠ 3 0"),
    Case("Not in COMP", "SYNTAX", keys = "Not 1 )", natural = false),
    Case("CALC assignment in TABLE", "SYNTAX", keys = "Y EQ X", mode = Mode.TABLE),
)

/** Research 07 T3/T4 and quirk Q1, under docs/CALCULATOR.md §3's rules (all GUESSES until run). */
private val ROUNDING_PROBES = listOf(
    // 2÷3,0 = 0,666666666666667; ×3 = 2,000000000000001, which 15 digits round to 2.
    Case("T3 a", "~0", keys = "( 2 ÷ 3 , 0 ) × 3 − 2", natural = false),
    // 1÷3,0 = 0,333333333333333; ×3 = 0,999999999999999; no guard digit brings it back.
    Case("T3 b", "~1E-15", keys = "1 − ( 1 ÷ 3 , 0 ) × 3", natural = false),
    // Below 1×10⁻⁹⁹ is zero, not Math ERROR.
    Case("T4 a", "~0", keys = "1 ×10 (−) 9 9 ÷ 1 0", natural = false),
    Case("T4 b", "~0", keys = "1 ×10 (−) 5 0 × 1 ×10 (−) 5 0", natural = false),
    Case("T4 literal", "0", keys = "1 ×10 (−) 1 0 0", natural = false),
    // Casio's π is 3,14159265358980: π − 3,14159265358 = 9,8×10⁻¹².
    Case("Q1", "~9.8E-12", keys = "π − 3 , 1 4 1 5 9 2 6 5 3 5 8", natural = false),
    // A typed decimal stays a decimal (docs/CALCULATOR.md §3), an integer literal is exact.
    Case("typed decimal", "~0.5", keys = "0 , 5"),
    // Even when its value is whole: 5,0 is a decimal, and so is a MathO division by one.
    Case("whole typed decimal", "~5", keys = "5 , 0"),
    Case("division by a typed decimal", "~0.5", keys = "2 ÷ 4 , 0"),
    Case("integer with exponent", "2000", keys = "2 ×10 3"),
    Case("decimal times fraction", "~0.25", keys = "0 , 5 × FRAC 1 ▼ 2"),
)

class ErrorsTest {
    @Test
    fun `out of range inputs and results are Math ERROR`() = verify(MATH_ERRORS)

    @Test
    fun `items the mode or position forbids are Syntax ERROR`() = verify(SYNTAX_ERRORS)

    @Test
    fun `rounding and underflow probes T3 T4 and Q1`() = verify(ROUNDING_PROBES)

    @Test
    fun `Goto puts the cursor after the node that failed F-02`() {
        // 14÷0×2 = Math ERROR; ▶ shows 14÷0|×2, the cursor just after the 0 (research 02 §11).
        val outcome = evaluate(GUIDE.getValue("F-02").keys, true, context())
        assertEquals(EvalOutcome.Failed(CalcError.MATH, Cursor(emptyList(), 4)), outcome)
        // An out-of-range argument: the end of the function call, after its ")".
        val range = evaluate("sin 9 ×10 9 )", true, context())
        assertEquals(EvalOutcome.Failed(CalcError.MATH, Cursor(emptyList(), 5)), range)
        // A forbidden item is placed at itself: `2i` in COMP fails at the i.
        assertEquals(EvalOutcome.Failed(CalcError.SYNTAX, Cursor(emptyList(), 2)), evaluate("2 i", true, context()))
    }
}
