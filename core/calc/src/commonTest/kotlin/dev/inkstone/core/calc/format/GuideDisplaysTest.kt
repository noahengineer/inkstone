package dev.inkstone.core.calc.format

import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.eval.Answer
import kotlin.test.Test

/**
 * Every display in research 04 (COMP) that is a single result, built from the value the evaluator
 * would hand over and compared with what the guide prints. Values come straight from the value
 * library where it decides them (fractions, surds), or as the 15-digit decimal it would hold.
 *
 * Where a vector is in LineIO and the result is a decimal although the value library would give a
 * fraction (E-01's `sin 30`, C-03's `1÷5`), the value is written as the decimal: in linear output
 * only a ⌟-typed fraction stays a fraction, and that is the evaluator's decision, not the
 * formatter's (see `open_issues` of this stage).
 */
class GuideDisplaysTest {
    @Test
    fun `setup and display format examples A`() = checkAll(SETUP_CASES)

    @Test
    fun `input and basic calculation examples B and C`() = checkAll(BASIC_CASES)

    @Test
    fun `function examples E`() = checkAll(FUNCTION_CASES)

    @Test
    fun `display memory and handout examples D F H`() = checkAll(OTHER_CASES)

    @Test
    fun `surd forms of research 02 section 8 6 and the ROM tables`() = checkAll(SURD_CASES)

    @Test
    fun `whole-number results print as their digits`() = checkAll(WHOLE_NUMBERS)
}

/** Several results in a row, as the guide lists them, space-separated. */
private fun all(vararg values: Long, setup: Setup = MATH): String = values.joinToString(" ") { shown(q(it), setup) }

private val WHOLE_NUMBERS = listOf(
    case("C-11 and C-13 percent", "75 2625") { all(75, 2625) },
    case("D-03 Fibonacci", "1 1 2 3 5") { all(1, 1, 2, 3, 5) },
    case("D-04 and D-05 memory", "8 80 8 0 0 50 15 35") { all(8, 80, 8, 0, 0, 50, 15, 35) },
    case("E-02 E-05 E-06 angles", "30 90 45") { all(30, 90, 45, setup = LINE) },
    case("E-08 LineIO Sci 3", "2,97×10^2") { shown(d("296.826318205153"), LINE.sci(3)) },
    case("E-09 to E-13 logs", "3 4 4 6 8") { all(3, 4, 4, 6, 8) },
    case("E-16 E-18 E-19 powers and roots", "16 2 2") { all(16, 2, 2) },
    case("E-23 reciprocals", "12") { all(12, setup = LINE) },
    case("E-24 to E-32 calculus", "1 1 0 0 7 20 20") { all(1, 1, 0, 0, 7, 20, 20) },
    case("E-37 to E-46", "10 10 210 7 45") { all(10, 10, 210, 7, 45) },
    case("E-47 and E-50 CALC and SOLVE", "25 41 2 3 4") { all(25, 41, 2, 3, 4) },
    case("E-52 and EX-CONST-02", "299792458") { all(299792458) },
)

private val SETUP_CASES = listOf(
    case("A-01 fraction", "1/200") { shown(q(1, 200)) },
    case("A-02 LineO Norm 1", "5×10^-3") { shown(d("0.005"), MATH_LINE.norm(1)) },
    case("A-03 LineO Norm 2", "0,005") { shown(d("0.005"), MATH_LINE) },
    case("A-04 LineIO Norm 1", "5×10^-3") { shown(d("0.005"), LINE.norm(1)) },
    case("A-05 LineIO Norm 2", "0,005") { shown(d("0.005"), LINE) },
    case("A-06 Fix 3", "14,286") { shown(d("14.2857142857143"), LINE.fix(3)) },
    case("A-07 Fix 2", "14,29") { shown(d("14.2857142857143"), LINE.fix(2)) },
    case("A-08 Sci 5", "1,4286×10^-1") { shown(d("0.142857142857143"), LINE.sci(5)) },
    case("A-09 Sci 4", "1,429×10^-1") { shown(d("0.142857142857143"), LINE.sci(4)) },
    case("A-10 Sci 0 is ten digits", "1,428571429×10^-1") { shown(d("0.142857142857143"), LINE.sci(0)) },
)

private val BASIC_CASES = listOf(
    case("B-01 integer", "120") { shown(q(120)) },
    case("B-03 surd quotient", "√(2)") { shown(div(add(q(2), sqrt(2)), add(q(1), sqrt(2)))) },
    case("B-05 two-term surd", "45√(3)+10√(2)") { shown(add(mul(q(10), sqrt(2)), mul(q(15), mul(q(3), sqrt(3))))) },
    case("B-06 coefficient out of range", "3129,089165") { shown(mul(q(99), sqrt(999))) },
    case("C-01 pi form", "1/6π") { shown(d("0.5235987755983"), piEligible = true) },
    case("C-02 surd sum", "√(6)+2√(3)") { shown(mul(add(sqrt(2), q(2)), sqrt(3))) },
    case("C-03 LineIO division", "0,2") { shown(d("0.2"), LINE) },
    case("C-04 LineIO typed fraction", "1⌟5") { shown(q(1, 5), LINE) },
    case("C-05 fraction sum", "7/6") { shown(add(q(2, 3), q(1, 2))) },
    case("C-07 LineIO fraction sum", "7⌟6") { shown(add(q(2, 3), q(1, 2)), LINE) },
    case("C-08 mixed template answer", "1/2") { shown(sub(q(4), q(7, 2))) },
    case("C-09 LineIO", "1⌟2") { shown(sub(q(4), q(7, 2)), LINE) },
    case("C-10 percent", "30") { shown(q(30)) },
    case("C-12 percent increase", "2875") { shown(q(2875)) },
    case("C-14 DMS sum", "3°0'0\"") { shown(dmsValue("3")) },
    case("C-15 DMS", "2°15'18\"") { shown(dmsValue("2.255")) },
    case("C-16 multi-statement", "9") { shown(q(9)) },
    case("C-19 remainder division", "2;R=1") { shownAnswer(Answer.Division(q(2), q(1))) },
    case("C-20 LineIO remainder division", "2 / R=1") { shownAnswer(Answer.Division(q(2), q(1)), LINE) },
    case("C-22 quotient only", "12") { shown(q(12)) },
)

private val FUNCTION_CASES = listOf(
    case("E-01 LineIO sin 30", "0,5") { shown(d("0.5"), LINE) },
    case("E-03 sinh 1", "1,175201194") { shown(d("1.1752011936438")) },
    case("E-04 acosh 1", "0") { shown(q(0)) },
    case("E-07 Sci 3", "2,97×10^2") { shown(d("296.826318205153"), MATH.sci(3)) },
    case("E-14 Sci 3 keeps zeros", "4,50×10^0") { shown(d("4.49980967033027"), MATH.sci(3)) },
    case("E-15", "1200") { shown(q(1200)) },
    case("E-17", "15625") { shown(q(15625)) },
    case("E-20 Fix keeps the surd", "3√(2)") { shown(mul(sqrt(2), q(3)), MATH.fix(3)) },
    case("E-20 then SHIFT equals", "4,243") { shown(mul(sqrt(2), q(3)), MATH.fix(3), decimal = true) },
    case("E-21 LineIO Fix 3", "4,243") { shown(mul(sqrt(2), q(3)), LINE.fix(3)) },
    case("E-22 cube roots", "-1,290024053") { shown(d("-1.2900240533233"), LINE) },
    case("E-26 integral", "0,8") { shown(d("0.8"), LINE) },
    case("E-27 integral to pi", "π") { shown(d("3.14159265358979"), piEligible = true) },
    case("E-33 Pol", "r=2;θ=45") { shownAnswer(Answer.Coordinates(q(2), q(45), polar = true)) },
    case("E-34 LineIO Pol", "r=2 / θ=45") { shownAnswer(Answer.Coordinates(q(2), q(45), polar = true), LINE) },
    case("E-35 Rec", "X=1;Y=1") { shownAnswer(Answer.Coordinates(q(1), q(1), polar = false)) },
    case("E-36 factorial", "40320") { shown(q(40320)) },
    case("E-41 nPr", "5040") { shown(q(5040)) },
    case("E-43 Fix 3 of 15 digits", "10,000") { shown(d("9.99999999999999"), LINE.fix(3)) },
    case("E-44 Rnd", "9,999") { shown(d("9.999"), LINE.fix(3)) },
    case("E-49 SOLVE", "1,414213562") { shown(d("1.4142135623731")) },
    case("E-51 constant", "299792458") { shown(q(299792458)) },
    case("E-53 cm to in", "1,968503937") { shown(d("1.96850393700787"), LINE) },
    case("E-54 g to oz", "3,527396584") { shown(d("3.52739658385638"), LINE) },
    case("E-55 C to F", "-23,8") { shown(d("-23.8"), LINE) },
)

private val OTHER_CASES = listOf(
    case("D-01 Ans continuation", "0,4") { shown(d("0.4"), LINE) },
    case("D-02 Ans", "210") { shown(q(210)) },
    case("F-01 Pol in Rad stays decimal", "r=2;θ=0,7853981634") {
        shownAnswer(Answer.Coordinates(d("2"), d("0.785398163397448"), polar = true, piEligible = true))
    },
    case("F-02 Goto then fix", "14/5") { shown(q(14, 5)) },
    case("F-05", "15,5") { shown(d("15.5"), LINE) },
    case("F-06 sin 45", "0,7071067812") { shown(d("0.707106781186548"), LINE) },
    case("H-02 Fix keeps the fraction", "2/3") { shown(q(2, 3), MATH.fix(2)) },
    case("H-03 remainder division", "18;R=3") { shownAnswer(Answer.Division(q(18), q(3))) },
    case("H-04 CALC", "116") { shown(q(116)) },
    case("H-07 F to C", "180") { shown(q(180)) },
    case("EX-SA-STAT-01 Q1", "2198,5") { shown(d("2198.5")) },
    case("EX-STAT-02 sigma", "1,154700538") { shown(d("1.15470053837925")) },
    case("EX-STAT-03 r Fix 3", "0,923") { shown(d("0.922847901"), MATH.fix(3)) },
    case("EX-STAT-03 A Fix 3", "-3857,984") { shown(d("-3857.98441"), MATH.fix(3)) },
    case("EX-STAT-03 B Fix 3", "2357,532") { shown(d("2357.53155"), MATH.fix(3)) },
    case("EX-STAT-04 x hat", "4,861") { shown(d("4.861312"), MATH.fix(3)) },
    case("EX-STAT-05 t", "-0,762") { shown(d("-0.76225171"), MATH.fix(3)) },
    case("EX-VEC-07 cosine", "0,984") { shown(d("0.98386991"), MATH.fix(3)) },
    case("EX-VEC-07 angle", "10,305") { shown(d("10.30485"), MATH.fix(3)) },
    case("EX-DIST-01 Normal PD", "0,1760326634") { shown(d("0.17603266338")) },
    case("EX-MAT-05 det", "1") { shown(q(1), mode = Mode.MATRIX) },
)

private val SURD_CASES = listOf(
    case("2√3×4", "8√(3)") { shown(mul(mul(q(2), sqrt(3)), q(4))) },
    case("35√2×3", "148,492424") { shown(mul(mul(q(35), sqrt(2)), q(3))) },
    case("150√2/25", "8,485281374") { shown(div(mul(q(150), sqrt(2)), q(25))) },
    case("2×(3−2√5)", "6-4√(5)") { shown(mul(q(2), sub(q(3), mul(q(2), sqrt(5))))) },
    case("23×(5−2√3)", "35,32566285") { shown(mul(q(23), sub(q(5), mul(q(2), sqrt(3))))) },
    case("√2+√3+√8", "√(3)+3√(2)") { shown(add(add(sqrt(2), sqrt(3)), sqrt(8))) },
    case("√2+√3+√6", "5,595754113") { shown(add(add(sqrt(2), sqrt(3)), sqrt(6))) },
    case("√3/11+√2/10", "(10√(3)+11√(2))/110") { shown(add(div(sqrt(3), q(11)), div(sqrt(2), q(10)))) },
    case("three terms on the way", "-8,898979486") {
        shown(mul(add(add(q(1), sqrt(2)), sqrt(3)), sub(sub(q(1), sqrt(2)), sqrt(3))))
    },
    case("sin 60", "√(3)/2") { shown(div(sqrt(3), q(2))) },
    case("one negative term", "-√(2)/2") { shown(div(sqrt(2), q(-2))) },
    case("sin 15", "(√(6)-√(2))/4") { shown(div(sub(sqrt(6), sqrt(2)), q(4))) },
    case("tan 15", "2-√(3)") { shown(sub(q(2), sqrt(3))) },
    case("tan 30", "√(3)/3") { shown(div(sqrt(3), q(3))) },
    case("EX-EQN-03 X1", "(3+√(57))/4") { shown(div(add(q(3), sqrt(57)), q(4))) },
    case("EX-INEQ-04 both signs inside", "(-3-√(21))/6") { shown(div(sub(q(-3), sqrt(21)), q(6))) },
    case("rational term with a denominator", "(1+2√(2))/2") { shown(add(q(1, 2), sqrt(2))) },
)
