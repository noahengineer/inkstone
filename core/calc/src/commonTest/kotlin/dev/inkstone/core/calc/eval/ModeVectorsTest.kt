package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.ComplexFormat
import dev.inkstone.core.calc.InputOutput
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.NumberFormat
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.parse.Radix
import dev.inkstone.core.calc.parse.Slot
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.FractionReal
import dev.inkstone.core.calc.value.MatrixValue
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.VectorValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private val BIN = Radix.BIN
private val MAT = Mode.MATRIX
private val VCT = Mode.VECTOR
private val HALVES = FractionReal(5, 2)

private fun ints(vararg n: Long): List<Real> = n.map { FractionReal(it, 1) }

private fun matrix(rows: Int, cols: Int, vararg cells: Long) = MatrixValue(rows, cols, ints(*cells))

/** Research 05's shared MATRIX state: MatA, MatB (2×2) and MatC (2×3). */
private fun matrices(memory: Memory) {
    memory.matrices[Slot.A] = matrix(2, 2, 2, 1, 1, 1)
    memory.matrices[Slot.B] = matrix(2, 2, 2, -1, -1, 2)
    memory.matrices[Slot.C] = matrix(2, 3, 1, 0, -1, 0, -1, 1)
}

/** EX-VEC-03 continued: VctAns holds 3×VctA. */
private fun vectorsAfterThreeA(memory: Memory) {
    vectors(memory)
    memory.vectors[Slot.ANS] = VectorValue(ints(3, 6))
}

private fun oneToNine(memory: Memory) {
    memory.matrices[Slot.A] = matrix(3, 3, 1, 2, 3, 4, 5, 6, 7, 8, 9)
}

/** Research 05's shared VECTOR state: VctA = (1,2), VctB = (3,4), VctC = (2,−1,2). */
private fun vectors(memory: Memory) {
    memory.vectors[Slot.A] = VectorValue(ints(1, 2))
    memory.vectors[Slot.B] = VectorValue(ints(3, 4))
    memory.vectors[Slot.C] = VectorValue(ints(2, -1, 2))
}

private val CMPLX = listOf(
    Case("EX-CMPLX-01", "3|-1", mode = Mode.CMPLX),
    Case("EX-CMPLX-02", "√2|√2", mode = Mode.CMPLX),
    Case("EX-CMPLX-03", "√2|√2", mode = Mode.CMPLX, setup = Setup(complex = ComplexFormat.POLAR)),
    Case("EX-CMPLX-04", "1/2|1/2", mode = Mode.CMPLX),
    Case("EX-CMPLX-05", "0", mode = Mode.CMPLX),
    Case("EX-CMPLX-06", "2|-3", mode = Mode.CMPLX),
    Case("EX-CMPLX-07", "√2", mode = Mode.CMPLX),
    Case("EX-CMPLX-08", "45", mode = Mode.CMPLX),
    Case("EX-CMPLX-09", "√2|√2", mode = Mode.CMPLX),
    Case("EX-CMPLX-10", "√2|√2", mode = Mode.CMPLX),
    // Beyond the guide: the square root of a negative number is imaginary in CMPLX (a GUESS),
    // i² is a real −1, and a real-only function of a complex number is Math ERROR (a GUESS).
    Case("CMPLX sqrt", "0|2", keys = "√ (−) 4", mode = Mode.CMPLX),
    Case("CMPLX i squared", "-1", keys = "i x²", mode = Mode.CMPLX),
    Case("CMPLX quotient", "0|1", keys = "( 1 + i ) ÷ ( 1 − i )", mode = Mode.CMPLX),
    Case("CMPLX polar right angle", "0|2", keys = "2 ∠ 9 0", mode = Mode.CMPLX),
    Case("CMPLX sin of i", "MATH", keys = "sin i )", mode = Mode.CMPLX),
    Case("CMPLX power", "-4", keys = "( 1 + i ) x^ 4", mode = Mode.CMPLX),
    Case("CMPLX LineIO", "~0.5|~0.5", keys = "( 1 − i ) x⁻¹", natural = false, mode = Mode.CMPLX),
)

private val BASE_N = listOf(
    Case("EX-BASEN-01", "4", mode = Mode.BASE_N, radix = Radix.BIN),
    Case("EX-BASEN-02", "32", mode = Mode.BASE_N, radix = Radix.HEX),
    Case("EX-BASEN-03", "8", mode = Mode.BASE_N, radix = Radix.OCT),
    Case("EX-BASEN-04", "36", mode = Mode.BASE_N),
    Case("EX-BASEN-05", "555", mode = Mode.BASE_N),
    Case("EX-BASEN-06", "8", mode = Mode.BASE_N, radix = Radix.BIN),
    Case("EX-BASEN-07", "27", mode = Mode.BASE_N, radix = Radix.BIN),
    Case("EX-BASEN-08", "6", mode = Mode.BASE_N, radix = Radix.BIN),
    Case("EX-BASEN-09", "-11", mode = Mode.BASE_N, radix = Radix.BIN),
    Case("EX-BASEN-10", "-11", mode = Mode.BASE_N, radix = Radix.BIN),
    Case("EX-BASEN-11", "-45", mode = Mode.BASE_N, radix = Radix.BIN),
    // Beyond the guide (research 03 §5): truncating division, two's complement, ranges.
    Case("BASE-N divide", "3", keys = "7 ÷ 2", mode = Mode.BASE_N),
    Case("BASE-N divide negative", "-3", keys = "(−) 7 ÷ 2", mode = Mode.BASE_N),
    Case("BASE-N divide by zero", "MATH", keys = "1 ÷ 0", mode = Mode.BASE_N),
    Case("BASE-N all ones", "-1", keys = "hF hF hF hF hF hF hF hF", mode = Mode.BASE_N, radix = Radix.HEX),
    Case("BASE-N BIN overflow", "MATH", keys = "1 1 1 1 1 1 1 1 1 1 1 1 1 1 1 + 1", mode = Mode.BASE_N, radix = BIN),
    Case("BASE-N DEC literal too wide", "MATH", keys = "2 1 4 7 4 8 3 6 4 8", mode = Mode.BASE_N),
    Case("BASE-N DEC upper bound", "2147483647", keys = "2 1 4 7 4 8 3 6 4 7", mode = Mode.BASE_N),
    Case("BASE-N binary digit", "SYNTAX", keys = "2", mode = Mode.BASE_N, radix = Radix.BIN),
    Case("BASE-N no fractions", "SYNTAX", keys = "2 , 5", mode = Mode.BASE_N),
    Case("BASE-N Not of zero", "-1", keys = "Not 0 )", mode = Mode.BASE_N),
    Case("BASE-N no constants", "SYNTAX", keys = "CONST28", mode = Mode.BASE_N),
    Case("BASE-N variables cut toward zero", "2", keys = "Ans", mode = Mode.BASE_N, prepare = { it.ans = HALVES }),
)

private val MATRIX = listOf(
    Case("EX-MAT-01", "3 0 | 1 1", mode = Mode.MATRIX, prepare = ::matrices),
    Case("EX-MAT-02", "4 0 | 0 3", mode = Mode.MATRIX, prepare = ::matrices),
    Case("EX-MAT-04", "6 3 | 3 3", mode = Mode.MATRIX, prepare = ::matrices),
    Case("EX-MAT-05", "1", mode = Mode.MATRIX, prepare = ::matrices),
    Case("EX-MAT-06", "1 0 | 0 -1 | -1 1", mode = Mode.MATRIX, prepare = ::matrices),
    Case("EX-MAT-07", "1 -1 | -1 2", mode = Mode.MATRIX, prepare = ::matrices),
    Case("EX-MAT-08", "2 1 | 1 2", mode = Mode.MATRIX, prepare = ::matrices),
    Case("EX-MAT-09", "5 3 | 3 2", mode = Mode.MATRIX, prepare = ::matrices),
    Case("EX-MAT-09b", "13 8 | 8 5", mode = Mode.MATRIX, prepare = ::matrices),
    // MATRIX is always Linear (research 03 §2.3), so the default MthIO-MathO setup gives the same
    // decimals as LineIO; the guide's screen shows them truncated, 1,1428 (05 EX-MAT-10).
    Case(
        "EX-MAT-10",
        "1 ~1.142857143 ~1.285714286 | 0 1 2 | 0 0 0",
        mode = Mode.MATRIX,
        setup = math(),
        prepare = ::oneToNine,
    ),
    Case(
        "EX-MAT-10",
        "1 ~1.142857143 ~1.285714286 | 0 1 2 | 0 0 0",
        mode = Mode.MATRIX,
        setup = line(),
        prepare = ::oneToNine,
    ),
    Case("EX-MAT-11", "1 0 -1 | 0 1 2 | 0 0 0", mode = Mode.MATRIX, prepare = ::oneToNine),
    // Beyond the guide (research 03 §7.4): sizes, undefined matrices, the matrix stack.
    Case("MAT sizes differ", "DIMENSION", keys = "MatA + MatC", natural = false, mode = MAT, prepare = ::matrices),
    Case("MAT undefined", "DIMENSION", keys = "MatB", natural = false, mode = Mode.MATRIX),
    Case("MAT det of 2x3", "DIMENSION", keys = "det MatC )", natural = false, mode = Mode.MATRIX, prepare = ::matrices),
    Case("MAT plus a number", "SYNTAX", keys = "MatA + 1", natural = false, mode = Mode.MATRIX, prepare = ::matrices),
    Case("MAT divided by 2", "1 ~0.5 | ~0.5 ~0.5", keys = "MatA ÷ 2", mode = MAT, setup = math(), prepare = ::matrices),
    Case(
        "MAT inverse in the default setup",
        "~0.6666666667 ~0.3333333333 | ~0.3333333333 ~0.6666666667",
        keys = "MatB x⁻¹",
        natural = false,
        mode = MAT,
        setup = math(),
        prepare = ::matrices,
    ),
    Case(
        "MAT three on the stack",
        "6 3 | 3 2",
        keys = "MatA × ( MatB × MatA )",
        natural = false,
        mode = Mode.MATRIX,
        prepare = ::matrices,
    ),
    Case(
        "MAT four on the stack",
        "STACK",
        keys = "MatA × ( MatB × ( MatA × MatB ) )",
        natural = false,
        mode = Mode.MATRIX,
        prepare = ::matrices,
    ),
    Case(
        "MAT singular inverse",
        "MATH",
        keys = "MatA x⁻¹",
        natural = false,
        mode = Mode.MATRIX,
        prepare = { it.matrices[Slot.A] = matrix(2, 2, 1, 2, 2, 4) },
    ),
)

private val VECTOR = listOf(
    Case("EX-VEC-01", "4 6", mode = Mode.VECTOR, prepare = ::vectors),
    Case("EX-VEC-03", "3 6", mode = Mode.VECTOR, prepare = ::vectors),
    Case("EX-VEC-03b", "0 2", mode = Mode.VECTOR, prepare = ::vectorsAfterThreeA),
    Case("EX-VEC-04", "11", mode = Mode.VECTOR, prepare = ::vectors),
    Case("EX-VEC-05", "0 0 -2", mode = Mode.VECTOR, prepare = ::vectors),
    Case("EX-VEC-06", "3", mode = Mode.VECTOR, prepare = ::vectors),
    Case("EX-VEC-07", "~0.984", mode = Mode.VECTOR, setup = line(NumberFormat.Fix(3)), prepare = ::vectors),
    // The guide sets up EX-VEC-07 from the default MthIO-MathO with only Fix 3 added, and prints
    // 0,984. VECTOR is always Linear (research 03 §2.3); were it MathO, the answer would be the surd
    // 11√5/25, which Fix leaves alone (research 02 §8.3), not 0,984.
    Case(
        "EX-VEC-07 as the guide sets it up",
        "~0.984",
        keys = GUIDE.getValue("EX-VEC-07").keys,
        natural = false,
        mode = Mode.VECTOR,
        setup = math(NumberFormat.Fix(3)),
        prepare = ::vectors,
    ),
    Case(
        "VCT magnitude in the default setup",
        "~2.236067977",
        keys = "Abs VctA )",
        natural = false,
        mode = Mode.VECTOR,
        setup = math(),
        prepare = ::vectors,
    ),
    Case("VCT sizes differ", "DIMENSION", keys = "VctA + VctC", natural = false, mode = VCT, prepare = ::vectors),
    Case("VCT dot with a number", "SYNTAX", keys = "VctA · 3", natural = false, mode = VCT, prepare = ::vectors),
    Case(
        "VCT five on the stack",
        "1 2",
        keys = "VctA + ( VctB + ( VctA − ( VctB + VctA ) ) )",
        natural = false,
        mode = Mode.VECTOR,
        prepare = ::vectors,
    ),
    // Exactly six: five held and the sixth produced (seven would not pin the limit).
    Case(
        "VCT six on the stack",
        "STACK",
        keys = "VctA + ( VctB + ( VctA + ( VctB + ( VctA + VctB ) ) ) )",
        natural = false,
        mode = Mode.VECTOR,
        prepare = ::vectors,
    ),
)

private val CONSTANTS_CONVERSIONS = listOf(
    Case("EX-CONST-01", "~299792458", keys = "CONST28"),
    Case("EX-CONST-02", "~299792458"),
    // A MathO conversion is exact (research 07 T17 asks; INFERRED from 04 H-08).
    Case("CONV exact in MathO", "250/127", keys = "5 CONV02"),
    Case("CONV not in TABLE", "SYNTAX", keys = "5 CONV02", mode = Mode.TABLE),
    Case("CONST in TABLE", "~299792458", keys = "CONST28", mode = Mode.TABLE),
)

/** EX-TABLE-01: f(X) = X²+1/2 at the five X values the table generates. */
private val TABLE_ROWS = listOf(
    "(−) 1" to "3/2",
    "(−) 0 , 5" to "~0.75",
    "0" to "1/2",
    "0 , 5" to "~0.75",
    "1" to "3/2",
)

class ModeVectorsTest {
    @Test
    fun `CMPLX examples give the printed values`() = verify(CMPLX)

    @Test
    fun `BASE-N examples give the printed values`() = verify(BASE_N)

    @Test
    fun `MATRIX examples give the printed values`() = verify(MATRIX)

    @Test
    fun `VECTOR examples give the printed values`() = verify(VECTOR)

    @Test
    fun `CONST and CONV examples give the printed values`() = verify(CONSTANTS_CONVERSIONS)

    @Test
    fun `the angle between vectors keeps full precision in Ans EX-VEC-07`() {
        val fix3 = line(NumberFormat.Fix(3))
        val memory = Memory().also(::vectors)
        val first = evaluate(GUIDE.getValue("EX-VEC-07").keys, false, context(Mode.VECTOR, fix3, memory))
        memory.ans = assertIs<EvalOutcome.Done>(first).answer.value
        val angle = evaluate(GUIDE.getValue("EX-VEC-07b").keys, false, context(Mode.VECTOR, fix3, memory))
        assertEquals("~10.305", text(angle, fix3.format))
    }

    @Test
    fun `TABLE evaluates f at each X EX-TABLE-01`() {
        val f = GUIDE.getValue("EX-TABLE-01f").keys
        for ((x, expected) in TABLE_ROWS) {
            val memory = Memory()
            val table = context(Mode.TABLE, memory = memory)
            memory.variables[Variable.X] = assertIs<EvalOutcome.Done>(evaluate(x, true, table)).answer.value
            assertEquals(expected, text(evaluate(f, true, table), NumberFormat.Norm(2)), x)
        }
    }

    @Test
    fun `LineO output follows linear rules too`() {
        val setup = Setup(io = InputOutput.MATH_LINE)
        assertEquals("~0.5", text(evaluate("sin 3 0 )", true, context(setup = setup)), setup.format))
        assertEquals("1/5", text(evaluate("1 − 4 FRAC 5", true, context(setup = setup)), setup.format))
    }
}
