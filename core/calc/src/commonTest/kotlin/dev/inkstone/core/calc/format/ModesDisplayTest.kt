package dev.inkstone.core.calc.format

import dev.inkstone.core.calc.ComplexFormat
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.eval.Answer
import dev.inkstone.core.calc.eval.Memory
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.lcd.MathLayout
import dev.inkstone.core.calc.parse.Radix
import dev.inkstone.core.calc.value.MatrixValue
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.Value
import dev.inkstone.core.calc.value.VectorMath
import dev.inkstone.core.calc.value.VectorValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Research 05's mode examples that end in one value on the screen — CMPLX, BASE-N, the EQN, INEQ
 * and RATIO solutions, the editors' cells — plus the two-part results' remaining shapes. Rows
 * marked GUESS pin this formatter's choices where no example exists.
 */
class ModesDisplayTest {
    @Test
    fun `CMPLX results`() = checkAll(COMPLEX)

    @Test
    fun `CMPLX results in Linear display take two lines`() = checkAll(COMPLEX_LINEAR)

    @Test
    fun `BASE-N results`() = checkAll(BASE_N)

    @Test
    fun `values on mode screens`() = checkAll(VALUES)

    @Test
    fun `editor cells are cut not rounded`() = checkAll(CELLS)

    @Test
    fun `two-part results in every output`() = checkAll(TWO_PARTS)

    @Test
    fun `natural layout and the BASE-N label travel with the result`() {
        val half = Formatting.present(Answer.Plain(q(1, 2)), MATH, Mode.COMP, false)
        assertTrue(Formatting.show(half, MATH, Mode.COMP, Radix.DEC).naturalResult)
        assertFalse(Formatting.show(half, LINE, Mode.COMP, Radix.DEC).naturalResult)
        assertFalse(Formatting.show(half, MATH_LINE, Mode.COMP, Radix.DEC).naturalResult)
        assertNull(Formatting.show(half, MATH, Mode.COMP, Radix.DEC).baseLabel)
        val labels = Radix.entries.map { Formatting.show(half, MATH, Mode.BASE_N, it) }
        assertEquals(listOf("Dec", "Hex", "Bin", "Oct"), labels.map { it.baseLabel })
        assertTrue(labels.none { it.naturalResult })
    }

    /**
     * STAT, BASE-N, MATRIX and VECTOR force Linear Display (research 03 §2.2 and §2.3, VERIFIED;
     * DIST INFERRED). The machine leaves SETUP alone there and hands the formatter SETUP as it is,
     * so the mode is what has to decide. The values are built the way production builds them:
     * EX-VEC-07's cosine through [VectorMath] is the exact surd `11√5/25`, which a MthIO SETUP in
     * Fix 3 would otherwise keep as `11√5/25` where the guide's screen shows `0,984`.
     */
    @Test
    fun `forced Linear modes draw every result linearly`() {
        checkAll(FORCED_LINEAR)
        for (mode in listOf(Mode.STAT, Mode.MATRIX, Mode.VECTOR, Mode.DIST)) {
            val p = Formatting.present(Answer.Plain(q(1, 3)), MATH, mode, false)
            assertFalse(Formatting.show(p, MATH, mode, Radix.DEC).naturalResult, "$mode")
        }
        val eqn = Formatting.present(Answer.Plain(q(1, 3)), MATH, Mode.EQN, false)
        assertTrue(Formatting.show(eqn, MATH, Mode.EQN, Radix.DEC).naturalResult)
    }

    @Test
    fun `a matrix has no calculation-line form`() {
        val m = MatrixValue(1, 2, listOf(q(1), q(2)))
        val p = Formatting.present(Answer.Plain(m), MATH, Mode.MATRIX, false)
        val drawn = Formatting.show(p, MATH, Mode.MATRIX, Radix.DEC)
        assertNull(drawn.result)
        assertTrue(drawn.parts.isEmpty())
        assertEquals(Expr.EMPTY, Formatting.valueExpr(m, MATH, Mode.MATRIX, Radix.DEC))
    }
}

/** A CMPLX-mode result, with `▶r∠θ` / `▶a+bi` as [override]. */
private fun cmplx(value: Value, setup: Setup = MATH, override: ComplexFormat? = null): String =
    text(Formatting.present(Answer.Plain(value, complexFormat = override), setup, Mode.CMPLX, false), setup, Mode.CMPLX)

private fun base(value: Value, radix: Radix): String =
    text(Formatting.present(Answer.Plain(value), MATH, Mode.BASE_N, false), MATH, Mode.BASE_N, radix)

private fun valueText(value: Value, setup: Setup = MATH, mode: Mode = Mode.EQN, radix: Radix = Radix.DEC): String =
    MathLayout.plainText(Formatting.valueExpr(value, setup, mode, radix))

private fun cell(x: Real): String = Formatting.cellText(x, MATH)

private fun cells(vararg n: Long): String = n.joinToString(" ") { cell(q(it)) }

private fun values(vararg n: Long): String = n.joinToString(" ") { valueText(q(it)) }

private val COMPLEX = listOf(
    case("EX-CMPLX-01", "3-i") { cmplx(z(q(3), q(-1))) },
    case("EX-CMPLX-02", "√(2)+√(2)i") { cmplx(z(sqrt(2), sqrt(2))) },
    case("EX-CMPLX-03 polar setup", "2∠45") { cmplx(z(sqrt(2), sqrt(2)), MATH.polar()) },
    case("EX-CMPLX-04", "1/2+1/2i") { cmplx(z(q(1, 2), q(1, 2))) },
    case("EX-CMPLX-05 zero", "0") { cmplx(z(q(0), q(0))) },
    case("EX-CMPLX-06", "2-3i") { cmplx(z(q(2), q(-3))) },
    case("EX-CMPLX-07 Abs is real", "√(2)") { cmplx(sqrt(2)) },
    case("EX-CMPLX-08 arg", "45") { cmplx(q(45)) },
    case("EX-CMPLX-09 to polar overrides a+bi", "2∠45") { cmplx(z(sqrt(2), sqrt(2)), override = ComplexFormat.POLAR) },
    case("EX-CMPLX-10 to a+bi overrides polar", "√(2)+√(2)i") {
        cmplx(z(sqrt(2), sqrt(2)), MATH.polar(), ComplexFormat.RECTANGULAR)
    },
    case("E-48 first", "2∠30") { cmplx(z(sqrt(3), q(1)), override = ComplexFormat.POLAR) },
    case("E-48 second", "2∠60") { cmplx(z(q(1), sqrt(3)), override = ComplexFormat.POLAR) },
    case("pure imaginary GUESS", "2i") { cmplx(z(q(0), q(2))) },
    case("minus i", "-i") { cmplx(z(q(0), q(-1))) },
    case("i", "i") { cmplx(z(q(0), q(1))) },
    case("a sum coefficient is bracketed GUESS", "(1+√(2))i") { cmplx(z(q(0), add(q(1), sqrt(2)))) },
    case("after a real part", "1+(1+√(2))i") { cmplx(z(q(1), add(q(1), sqrt(2)))) },
    // Review 2: a two-term coefficient keeps its own signs, as the real surd does ((-3-√21)/6,
    // 05 EX-INEQ-04) — negating it printed 1-(-1+√2)/2i, because √2-1 is canonically -1+√2.
    case("a negative two-term coefficient over a denominator GUESS", "1+(1-√(2))/2i") {
        cmplx(z(q(1), div(sub(q(1), sqrt(2)), q(2))))
    },
    case("the same coefficient alone GUESS", "(1-√(2))/2i") { cmplx(z(q(0), div(sub(q(1), sqrt(2)), q(2)))) },
    case("a whole negative sum keeps its signs too GUESS", "1+(-1-√(2))i") { cmplx(z(q(1), sub(q(-1), sqrt(2)))) },
    case("a one-term coefficient keeps its sign outside", "1-√(2)/2i") { cmplx(z(q(1), div(sqrt(2), q(-2)))) },
    case("a real in polar setup stays real GUESS", "2") { cmplx(z(q(2), q(0)), MATH.polar()) },
    case("an explicit polar real GUESS", "2∠0") { cmplx(z(q(2), q(0)), override = ComplexFormat.POLAR) },
    case("negative angle", "√(2)∠-45") { cmplx(z(q(1), q(-1)), MATH.polar()) },
    case("to polar on a plain real GUESS", "2∠180") { cmplx(q(-2), override = ComplexFormat.POLAR) },
    case("decimal parts", "1,5-2,25i") { cmplx(z(d("1.5"), d("-2.25"))) },
)

private val COMPLEX_LINEAR = listOf(
    case("a and bi", "3 / -i") { cmplx(z(q(3), q(-1)), LINE) },
    case("decimal parts", "1,414213562 / 1,414213562i") { cmplx(z(sqrt(2), sqrt(2)), LINE) },
    case("r and angle", "2 / ∠45") { cmplx(z(sqrt(2), sqrt(2)), LINE.polar()) },
    case("one part one line GUESS", "2i") { cmplx(z(q(0), q(2)), LINE) },
    case("fraction parts", "1⌟2 / 1⌟2i") { cmplx(z(q(1, 2), q(1, 2)), LINE) },
)

private val BASE_N = listOf(
    case("EX-BASEN-01 resting zero", "0000000000000000") {
        valueText(Memory.ZERO, mode = Mode.BASE_N, radix = Radix.BIN)
    },
    case("EX-BASEN-01", "0000000000000100") { base(q(4), Radix.BIN) },
    case("EX-BASEN-02", "00000020") { base(q(32), Radix.HEX) },
    case("EX-BASEN-03", "00000000010") { base(q(8), Radix.OCT) },
    case("EX-BASEN-04", "36") { base(q(36), Radix.DEC) },
    case("EX-BASEN-05 Dec", "555") { base(q(555), Radix.DEC) },
    case("EX-BASEN-05 Hex", "0000022B") { base(q(555), Radix.HEX) },
    case("EX-BASEN-05 Bin", "0000001000101011") { base(q(555), Radix.BIN) },
    case("EX-BASEN-05 Oct", "00000001053") { base(q(555), Radix.OCT) },
    case("EX-BASEN-06", "0000000000001000") { base(q(8), Radix.BIN) },
    case("EX-BASEN-07", "0000000000011011") { base(q(27), Radix.BIN) },
    case("EX-BASEN-08", "0000000000000110") { base(q(6), Radix.BIN) },
    case("EX-BASEN-09 and 10", "1111111111110101") { base(q(-11), Radix.BIN) },
    case("EX-BASEN-11", "1111111111010011") { base(q(-45), Radix.BIN) },
    case("negative Dec", "-45") { base(q(-45), Radix.DEC) },
    case("negative Hex", "FFFFFFFF") { base(q(-1), Radix.HEX) },
    case("negative Oct", "37777777777") { base(q(-1), Radix.OCT) },
    case("a fraction is cut off", "7") { base(d("7.9"), Radix.DEC) },
    case("largest Dec", "2147483647") { base(q(2147483647), Radix.DEC) },
)

private val VALUES = listOf(
    case("EX-EQN-01 X", "-1") { valueText(q(-1)) },
    case("EX-EQN-02 and EX-EQN-05", "1 2 3 -1 2 1") { values(1, 2, 3, -1, 2, 1) },
    case("EX-RATIO-02 and 03 and EX-VEC-04 and 06", "5 20 11 3") { values(5, 20, 11, 3) },
    case("EX-EQN-03 X1", "(3+√(57))/4") { valueText(div(add(q(3), sqrt(57)), q(4))) },
    case("EX-EQN-03 X2", "(3-√(57))/4") { valueText(div(sub(q(3), sqrt(57)), q(4))) },
    case("EX-EQN-03 vertex X", "3/4") { valueText(q(3, 4)) },
    case("EX-EQN-03 vertex Y", "-57/8") { valueText(q(-57, 8)) },
    case("EX-EQN-03 in LineIO", "2,637458609") { valueText(div(add(q(3), sqrt(57)), q(4)), LINE) },
    case("EX-EQN-04 double root", "√(2)") { valueText(sqrt(2)) },
    case("EX-SA-EQN-02 Y", "-4") { valueText(q(-4)) },
    case("EX-INEQ-03", "3/2") { valueText(q(3, 2), mode = Mode.INEQ) },
    case("EX-INEQ-04 A", "(-3-√(21))/6") { valueText(div(sub(q(-3), sqrt(21)), q(6)), mode = Mode.INEQ) },
    case("EX-INEQ-04 C", "(-3+√(21))/6") { valueText(div(add(q(-3), sqrt(21)), q(6)), mode = Mode.INEQ) },
    case("EX-INEQ-04 LineIO A", "-1,263762616") { valueText(d("-1.26376261582597"), LINE, Mode.INEQ) },
    case("EX-INEQ-04 LineIO C", "0,2637626158") { valueText(d("0.263762615825973"), LINE, Mode.INEQ) },
    case("EX-RATIO-01", "9/2") { valueText(q(9, 2), mode = Mode.RATIO) },
    case("EX-SA-RATIO-01", "125/3") { valueText(q(125, 3), mode = Mode.RATIO) },
    case("never a pi form", "3,141592654") { valueText(d("3.1415926535898")) },
    case("mixed SETUP", "1 1/6") { valueText(q(7, 6), MATH.mixed()) },
    case("a complex value on one line", "1/2+1/2i") { valueText(z(q(1, 2), q(1, 2)), mode = Mode.CMPLX) },
    case("a complex value in LineIO", "1⌟2+1⌟2i") { valueText(z(q(1, 2), q(1, 2)), LINE, Mode.CMPLX) },
)

private val CELLS = listOf(
    case("EX-MAT-10 eight sevenths", "1,1428") { cell(q(8, 7)) },
    case("EX-MAT-10 nine sevenths", "1,2857") { cell(q(9, 7)) },
    case("EX-MAT-10 zero", "0") { cell(q(0)) },
    case("EX-MAT-01 and EX-MAT-11 cells", "3 0 1 1 -1 2") { cells(3, 0, 1, 1, -1, 2) },
    case("EX-VEC-05 cells", "0 0 -2") { cells(0, 0, -2) },
    case("EX-TABLE-01 F and G", "0,75 -0,5") { cell(d("0.75")) + " " + cell(d("-0.5")) },
    case("EX-TABLE-01", "-0,25") { cell(d("-0.25")) },
    case("EX-TABLE-01 again", "1,5") { cell(d("1.5")) },
    case("EX-TABLE-01 X", "-1") { cell(q(-1)) },
    case("EX-DIST-02 first", "0,1859") { cell(d("0.185937845")) },
    case("EX-DIST-02 second", "0,1267") { cell(d("0.126775803")) },
    case("EX-DIST-02 third", "0,0633") { cell(d("0.063387902")) },
    case("EX-DIST-02 fourth", "0,0219") { cell(d("0.021941966")) },
    case("six digits fit GUESS", "123456") { cell(q(123456)) },
    case("a sign takes a place GUESS", "-12345") { cell(q(-12345)) },
    case("too long GUESS", "1,23E6") { cell(q(1234567)) },
    case("too long and negative GUESS", "-1,2E6") { cell(q(-1234567)) },
    case("too small GUESS", "1E-5") { cell(d("0.00001")) },
    case("the smallest plain GUESS", "-0,001") { cell(d("-0.0012345")) },
    case("trailing zeros are not written GUESS", "1E7") { cell(q(10000001)) },
)

private val VCT_A = VectorValue(listOf(q(1), q(2)))
private val VCT_B = VectorValue(listOf(q(3), q(4)))

/** `(VctA•VctB)÷(Abs(VctA)Abs(VctB))` exactly as [VectorMath] computes it (05 EX-VEC-07). */
private fun cosineAB(): Real =
    div(ok(VectorMath.dot(VCT_A, VCT_B)), mul(ok(VectorMath.magnitude(VCT_A)), ok(VectorMath.magnitude(VCT_B))))

private val FORCED_LINEAR = listOf(
    case("EX-VEC-07 cosine as VECTOR mode holds it", "0,984") { shown(cosineAB(), MATH.fix(3), mode = Mode.VECTOR) },
    case("the same value is a surd in COMP", "(11√(5))/25") { shown(cosineAB(), MATH.fix(3)) },
    case("Abs of VctA", "2,236067977") { shown(ok(VectorMath.magnitude(VCT_A)), mode = Mode.VECTOR) },
    case("an exact sigma in STAT", "1,154700538") { shown(div(mul(q(2), sqrt(3)), q(3)), mode = Mode.STAT) },
    case("a fraction in MATRIX is spelt as LineIO spells it", "1⌟3") { shown(q(1, 3), mode = Mode.MATRIX) },
    case("no pi form in MATRIX", "0,5235987756") {
        shown(d("0.5235987755983"), piEligible = true, mode = Mode.MATRIX)
    },
    case("Pol in VECTOR stacks as in LineIO", "r=2 / θ=45") {
        shownAnswer(Answer.Coordinates(q(2), q(45), polar = true), mode = Mode.VECTOR)
    },
    case("DIST INFERRED", "1⌟2") { shown(q(1, 2), mode = Mode.DIST) },
    case("a value on a VECTOR screen", "2,236067977") { valueText(sqrt(5), mode = Mode.VECTOR) },
    case("S to D in STAT recovers no pi form", "0,5235987756 > (none)") {
        val p = Formatting.present(Answer.Plain(d("0.5235987755983"), piEligible = true), MATH, Mode.STAT, false)
        walk(p, MATH, listOf(Press.SD), Mode.STAT)
    },
)

private val TWO_PARTS = listOf(
    case("Rec in LineIO", "X=1 / Y=1") { shownAnswer(Answer.Coordinates(q(1), q(1), polar = false), LINE) },
    case("Pol in MthIO-LineO GUESS", "r=2 / θ=45") {
        shownAnswer(Answer.Coordinates(q(2), q(45), polar = true), MATH_LINE)
    },
    case("an exact part", "r=√(2);θ=45") { shownAnswer(Answer.Coordinates(sqrt(2), q(45), polar = true)) },
    case("SHIFT equals", "r=1,414213562;θ=45") {
        shownAnswer(Answer.Coordinates(sqrt(2), q(45), polar = true), decimal = true)
    },
    case("a fractional remainder", "3;R=1/2") { shownAnswer(Answer.Division(q(3), q(1, 2))) },
    case("in LineIO", "3 / R=1⌟2") { shownAnswer(Answer.Division(q(3), q(1, 2)), LINE) },
)
