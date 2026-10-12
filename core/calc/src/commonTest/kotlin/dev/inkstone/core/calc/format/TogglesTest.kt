package dev.inkstone.core.calc.format

import dev.inkstone.core.calc.ComplexFormat
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.eval.Answer
import dev.inkstone.core.calc.format.Press.DMS
import dev.inkstone.core.calc.format.Press.ENG
import dev.inkstone.core.calc.format.Press.ENG_LEFT
import dev.inkstone.core.calc.format.Press.FACT
import dev.inkstone.core.calc.format.Press.MIXED
import dev.inkstone.core.calc.format.Press.SD
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * The keys that restyle a result, as sequences: each row is the screen after `=` (or `SHIFT =`)
 * and after every key, `(none)` where the key does nothing. The guide's own sequences (04 C-01,
 * C-02, C-03, C-04, C-15, C-17, C-18, C-25, C-26, E-20, H-01, H-02, H-08; 05 EX-SA-RATIO-01) are
 * VERIFIED; the rest pin this formatter's GUESSES, each named in the row.
 */
class TogglesTest {
    @Test
    fun `S to D on the guide examples`() = checkAll(GUIDE_SD)

    @Test
    fun `S to D never returns to a surd after SHIFT equals`() = checkAll(SHIFT_EQUALS)

    @Test
    fun `S to D recovers fractions and pi forms from decimals`() = checkAll(RECOVERY)

    @Test
    fun `SHIFT S to D flips mixed and improper`() = checkAll(MIXED_CASES)

    @Test
    fun `ENG and SHIFT ENG`() = checkAll(ENG_CASES)

    @Test
    fun `DMS key toggles sexagesimal`() = checkAll(DMS_CASES)

    @Test
    fun `FACT factorises and the keys that leave it`() = checkAll(FACT_CASES)

    /**
     * `÷R` disables `S⇔D` and `°'"` (research 02 §8.8, §10.12, VERIFIED) and every two-part result
     * refuses `FACT` (§10.10, §10.13). The rest on Pol/Rec is a GUESS: only `S⇔D` works there (see
     * [POLAR_SD]), because the guide singles out `FACT` for Pol/Rec where it names `S⇔D` for ÷R.
     */
    @Test
    fun `two-part results take no restyling key but S to D on Pol and Rec`() {
        val setup = MATH
        val pol = Formatting.present(Answer.Coordinates(sqrt(2), q(45), polar = true), setup, Mode.COMP, false)
        val rem = Formatting.present(Answer.Division(q(2), q(1)), setup, Mode.COMP, false)
        assertNull(Formatting.toggleExact(rem, setup, Mode.COMP))
        for (p in listOf(pol, rem)) {
            assertNull(Formatting.toggleMixed(p, setup, Mode.COMP))
            assertNull(Formatting.engineering(p, setup, Mode.COMP, left = false))
            assertNull(Formatting.toggleDms(p, setup, Mode.COMP))
            assertNull(Formatting.factorize(p, setup, Mode.COMP))
        }
    }

    @Test
    fun `S to D on Pol and Rec GUESS`() = checkAll(POLAR_SD)

    @Test
    fun `display keys on a real shown in polar do nothing but S to D`() = checkAll(POLAR_REAL)

    @Test
    fun `BASE-N takes no restyling key`() {
        val p = Formatting.present(Answer.Plain(q(555)), MATH, Mode.BASE_N, false)
        assertNull(Formatting.toggleExact(p, MATH, Mode.BASE_N))
        assertNull(Formatting.engineering(p, MATH, Mode.BASE_N, left = true))
        assertNull(Formatting.toggleDms(p, MATH, Mode.BASE_N))
        assertNull(Formatting.factorize(p, MATH, Mode.BASE_N))
    }
}

private val GUIDE_SD = listOf(
    case("C-01 pi form both ways", "1/6π > 0,5235987756 > 1/6π") {
        walkFrom(d("0.5235987755983"), MATH, listOf(SD, SD), piEligible = true)
    },
    case("C-02 surd both ways", "√(6)+2√(3) > 5,913591358 > √(6)+2√(3)") {
        walkFrom(mul(add(sqrt(2), q(2)), sqrt(3)), MATH, listOf(SD, SD))
    },
    case("C-03 LineIO decimal to fraction", "0,2 > 1⌟5 > 0,2") { walkFrom(d("0.2"), LINE, listOf(SD, SD)) },
    case("C-04 LineIO fraction to decimal", "1⌟5 > 0,2 > 1⌟5") { walkFrom(q(1, 5), LINE, listOf(SD, SD)) },
    case("G-01 and H-01 Norm 2", "1/800 > 0,00125") { walkFrom(q(1, 800), MATH, listOf(SD)) },
    case("A-02 S to D in Norm 1", "1/200 > 5×10^-3") { walkFrom(q(1, 200), MATH.norm(1), listOf(SD)) },
    case("H-02 Fix 2 after S to D", "2/3 > 0,67 > 2/3") { walkFrom(q(2, 3), MATH.fix(2), listOf(SD, SD)) },
    case("H-08 km per h", "100/3 > 33,33333333") { walkFrom(q(100, 3), MATH, listOf(SD)) },
    case("EX-SA-RATIO-01", "125/3 > 41,66666667") { walkFrom(q(125, 3), MATH, listOf(SD)) },
    case("Sci keeps a fraction too GUESS", "2/3 > 6,67×10^-1") { walkFrom(q(2, 3), MATH.sci(3), listOf(SD)) },
    case("an integer has no other face", "5 > (none)") { walkFrom(q(5), MATH, listOf(SD)) },
    case("complex parts both ways GUESS", "1/2+1/2i > 0,5+0,5i > 1/2+1/2i") {
        walkFrom(z(q(1, 2), q(1, 2)), MATH, listOf(SD, SD))
    },
    case("complex integers have no other face", "3-i > (none)") { walkFrom(z(q(3), q(-1)), MATH, listOf(SD)) },
)

private val SHIFT_EQUALS = listOf(
    case("E-20 SHIFT equals then S to D", "4,243 > (none)") {
        walkFrom(mul(sqrt(2), q(3)), MATH.fix(3), listOf(SD), decimal = true)
    },
    case("SHIFT equals on a fraction then S to D", "0,005 > 1/200 > 0,005") {
        walkFrom(q(1, 200), MATH, listOf(SD, SD), decimal = true)
    },
    case("SHIFT equals on pi then S to D", "0,5235987756 > 1/6π") {
        walkFrom(d("0.5235987755983"), MATH, listOf(SD), piEligible = true, decimal = true)
    },
    case("SHIFT equals on a complex surd", "1,414213562+1,414213562i > (none)") {
        val start = Formatting.present(Answer.Plain(z(sqrt(2), sqrt(2))), MATH, Mode.CMPLX, true)
        walk(start, MATH, listOf(SD), Mode.CMPLX)
    },
    // Review: the surd test looked at a and b, but a polar display draws r and θ — 1+i is r=√2.
    case("SHIFT equals on a polar complex never returns to a surd", "1,414213562∠45 > (none)") {
        val start = Formatting.present(Answer.Plain(z(q(1), q(1))), MATH.polar(), Mode.CMPLX, true)
        walk(start, MATH.polar(), listOf(SD), Mode.CMPLX)
    },
    case("S to D on a polar complex returns to the surd", "√(2)∠45 > 1,414213562∠45 > √(2)∠45") {
        val start = Formatting.present(Answer.Plain(z(q(1), q(1))), MATH.polar(), Mode.CMPLX, false)
        walk(start, MATH.polar(), listOf(SD, SD), Mode.CMPLX)
    },
    case("SHIFT equals on surd parts shown in polar as integers", "2∠45 > (none)") {
        val start = Formatting.present(Answer.Plain(z(sqrt(2), sqrt(2))), MATH.polar(), Mode.CMPLX, true)
        walk(start, MATH.polar(), listOf(SD), Mode.CMPLX)
    },
    // Review: SHIFT = only replaces a √, π or fraction form (02 §8.8); a DMS result is none of them.
    case("SHIFT equals keeps a DMS result", "2°15'18\" > 2,255 > 2°15'18\"") {
        walkFrom(dmsValue("2.255"), MATH, listOf(DMS, DMS), decimal = true)
    },
    case("SHIFT equals keeps a DMS result in LineIO", "2°15'18\" > 2,255") {
        walkFrom(dmsValue("2.255"), LINE, listOf(DMS), decimal = true)
    },
)

/** [walk] from what `=` (or `SHIFT =`) shows for [answer] in [mode]. */
private fun walkAnswer(
    answer: Answer,
    keys: List<Press>,
    setup: Setup = MATH,
    decimal: Boolean = false,
    mode: Mode = Mode.COMP,
): String = walk(Formatting.present(answer, setup, mode, decimal), setup, keys, mode)

private val POL = Answer.Coordinates(sqrt(2), q(45), polar = true)

private val POLAR_SD = listOf(
    case("Pol both ways", "r=√(2);θ=45 > r=1,414213562;θ=45 > r=√(2);θ=45") { walkAnswer(POL, listOf(SD, SD)) },
    case("Pol after SHIFT equals never returns to a surd", "r=1,414213562;θ=45 > (none)") {
        walkAnswer(POL, listOf(SD), decimal = true)
    },
    case("Rec with a fraction after SHIFT equals", "X=0,5;Y=1 > X=1/2;Y=1") {
        walkAnswer(Answer.Coordinates(q(1, 2), q(1), polar = false), listOf(SD), decimal = true)
    },
    case("integer parts have no other face", "r=2;θ=45 > (none)") {
        walkAnswer(Answer.Coordinates(q(2), q(45), polar = true), listOf(SD))
    },
    case("LineIO parts", "r=1,414213562 / θ=45 > (none)") { walkAnswer(POL, listOf(SD), LINE) },
)

/**
 * `2▶r∠θ` draws a real as `2∠0` (05 EX-CMPLX-09's command on a real, a GUESS): the keys that
 * restyle a real number have nothing to restyle there, and must say so rather than move the
 * presentation to a form the polar drawing ignores — which left `S⇔D` dead afterwards.
 */
private val POLAR_REAL = listOf(
    case("ENG and DMS then S to D", "√(2)∠0 > (none) > (none) > (none) > 1,414213562∠0") {
        val answer = Answer.Plain(sqrt(2), complexFormat = ComplexFormat.POLAR)
        walkAnswer(answer, listOf(ENG_LEFT, ENG, DMS, SD), mode = Mode.CMPLX)
    },
)

private val RECOVERY = listOf(
    case("typed decimal to fraction", "0,5 > 1/2 > 0,5") { walkFrom(d("0.5"), MATH, listOf(SD, SD)) },
    case("fifteen threes", "0,3333333333 > 1/3") { walkFrom(d("0.333333333333333"), MATH, listOf(SD)) },
    case("ten threes is no third T7", "0,3333333333 > (none)") { walkFrom(d("0.3333333333"), MATH, listOf(SD)) },
    case("too many digits to show", "0,000000008100000074 > (none)") {
        walkFrom(d("8.10000007371E-9"), MATH, listOf(SD))
    },
    case("T16 GUESS no pi form without pi", "0,7853981634 > (none)") {
        walkFrom(d("0.785398163397448"), MATH, listOf(SD))
    },
    case("the same value from a pi expression", "1/4π") { shown(d("0.785398163397448"), piEligible = true) },
    case("pi form needs MathO", "0,7853981634 > (none)") {
        walkFrom(d("0.785398163397448"), LINE, listOf(SD), piEligible = true)
    },
    case("T9 22 over 7 is no pi multiple", "3,142857143") { shown(d("3.14285714285714"), piEligible = true) },
    case("T9 100000 pi", "100000π") { shown(d("314159.26535898"), piEligible = true) },
    case("T9 the 10 to the 6 limit", "3141592,654") { shown(d("3141592.6535898"), piEligible = true) },
    case("negative pi multiple", "-2π") { shown(d("-6.2831853071796"), piEligible = true) },
    case("mixed decimal recovery", "1,5 > 1 1/2") { walkFrom(d("1.5"), MATH.mixed(), listOf(SD)) },
    // Review: research 07 T7 and T9's own inputs, pinned to docs/CALCULATOR.md section 3's rules (GUESSES).
    case("T7 a seven-digit denominator fits", "1/1234567") { shown(q(1, 1234567)) },
    case("T7 reduced to exactly ten digits", "4115/33333") { shown(q(12345, 99999)) },
    case("T7 eleven digits is a decimal", "0,1818355894") { shown(q(12345, 67891)) },
    case("T9 pi over 7", "1/7π") { shown(d("0.448798950512829"), piEligible = true) },
    case("T9 355 over 113 is a fraction", "355/113") { shown(q(355, 113), piEligible = true) },
    case("T9 and T16 typed digits of pi", "3,141592654") { shown(d("3.14159265359")) },
    case("T9 999999 pi is past the limit", "3141589,512") { shown(d("3141589.51199715"), piEligible = true) },
    case("T9 root 2 times pi", "4,442882938") { shown(d("4.44288293815837"), piEligible = true) },
    // Review 2: 02 §8.5 (VERIFIED) — a decimal cannot become a mixed fraction of more than ten
    // digits; 1000001/97 is 10309 28/97 mixed (eleven) and fits only as d/c (ten).
    case("decimal to an improper fraction of ten digits", "10309,28866 > 1000001/97") {
        walkFrom(d("10309.2886597938"), MATH, listOf(SD))
    },
    case("decimal to a mixed fraction past ten digits is refused", "10309,28866 > (none)") {
        walkFrom(d("10309.2886597938"), MATH.mixed(), listOf(SD))
    },
)

private val MIXED_CASES = listOf(
    case("SETUP ab over c", "1 1/6 > 7/6 > 1 1/6") { walkFrom(q(7, 6), MATH.mixed(), listOf(MIXED, MIXED)) },
    case("d over c first", "7/6 > 1 1/6") { walkFrom(q(7, 6), MATH, listOf(MIXED)) },
    case("LineIO mixed spelling", "7⌟6 > 1⌟1⌟6") { walkFrom(q(7, 6), LINE, listOf(MIXED)) },
    case("negative mixed", "-7/6 > -1 1/6") { walkFrom(q(-7, 6), MATH, listOf(MIXED)) },
    case("a proper fraction has one form", "1/2 > (none)") { walkFrom(q(1, 2), MATH, listOf(MIXED)) },
    case("a decimal is not converted GUESS", "0,5 > (none)") { walkFrom(d("0.5"), MATH, listOf(MIXED)) },
    case("pi coefficient", "7/6π > 1 1/6π") {
        walkFrom(d("3.66519142918809"), MATH, listOf(MIXED), piEligible = true)
    },
    case("mixed past ten digits is refused", "1000001/97 > (none)") { walkFrom(q(1000001, 97), MATH, listOf(MIXED)) },
)

private val ENG_CASES = listOf(
    case("C-17 and beyond", "1234 > 1,234×10^3 > 1234×10^0 > 1234000×10^-3 > 1234000000×10^-6 > (none)") {
        walkFrom(q(1234), MATH, listOf(ENG, ENG, ENG, ENG, ENG))
    },
    case("C-18 and beyond", "123 > 0,123×10^3 > 0,000123×10^6 > 0,000000123×10^9 > (none)") {
        walkFrom(q(123), MATH, listOf(ENG_LEFT, ENG_LEFT, ENG_LEFT, ENG_LEFT))
    },
    case("back and forth", "1234 > 1,234×10^3 > 0,001234×10^6 > 1,234×10^3") {
        walkFrom(q(1234), MATH, listOf(ENG, ENG_LEFT, ENG))
    },
    case("small values floor the exponent", "0,05 > 50×10^-3") { walkFrom(d("0.05"), MATH, listOf(ENG)) },
    case("ten digits", "0,1428571429 > 142,8571429×10^-3") { walkFrom(d("0.142857142857143"), MATH, listOf(ENG)) },
    case("a fraction goes to its decimal", "1/200 > 5×10^-3") { walkFrom(q(1, 200), MATH, listOf(ENG)) },
    case("negative", "-1234 > -1,234×10^3") { walkFrom(q(-1234), MATH, listOf(ENG)) },
    case("zero has one form", "0 > 0×10^0 > (none)") { walkFrom(q(0), MATH, listOf(ENG, ENG)) },
    case("Sci 3 digits GUESS", "1,23×10^3 > 1,23×10^3 > 1230×10^0") {
        walkFrom(q(1234), MATH.sci(3), listOf(ENG, ENG))
    },
    case("Fix 2 places GUESS", "1234,00 > 1,23×10^3") { walkFrom(q(1234), MATH.fix(2), listOf(ENG)) },
    case("S to D from ENG", "1/200 > 5×10^-3 > 1/200") { walkFrom(q(1, 200), MATH, listOf(ENG, SD)) },
    // Review: the exponent is two digits on the LCD and the range is 10^-99 to 10^99 (07 §4.1).
    case("ENG stops at the largest exponent GUESS", "9,5×10^99 > 9,5×10^99 > (none)") {
        walkFrom(d("9.5E99"), MATH, listOf(ENG, ENG_LEFT))
    },
    case("ENG stops at the smallest exponent GUESS", "1×10^-99 > 1×10^-99 > (none)") {
        walkFrom(d("1E-99"), MATH, listOf(ENG, ENG))
    },
    // Review: the first ENG is standard form, 1 to 1000 (02 §8.4) — also when Fix's rounding carries.
    case("Fix carry still starts at standard form GUESS", "1000,000 > 1,000×10^3 > 1000,000×10^0") {
        walkFrom(d("999.9996"), MATH.fix(3), listOf(ENG, ENG))
    },
    // Review 2: an ENG mantissa under Fix keeps Fix's own ten-digit budget (Numerals.fix), as the
    // plain number beside it does — it showed 999,999999600×10^6 and 1234,000000000×10^0.
    case("Fix 9 ENG keeps the ten-digit budget GUESS", "999999999,6 > 999,9999996×10^6") {
        walkFrom(d("999999999.6"), MATH.fix(9), listOf(ENG))
    },
    case("Fix 9 ENG steps keep the budget GUESS", FIX9_STEPS) {
        walkFrom(q(1234), MATH.fix(9), listOf(ENG, ENG, ENG, ENG, ENG))
    },
    case("Fix 9 budget carry is standard form GUESS", "1000,000000 > 1,000000000×10^3") {
        walkFrom(d("999.999999996"), MATH.fix(9), listOf(ENG))
    },
    case("SHIFT ENG stops at exactly 10^-9 GUESS", "1,5 > 0,0015×10^3 > 0,0000015×10^6 > 0,0000000015×10^9 > (none)") {
        walkFrom(d("1.5"), MATH, listOf(ENG_LEFT, ENG_LEFT, ENG_LEFT, ENG_LEFT))
    },
)

private const val FIX9_STEPS =
    "1234,000000 > 1,234000000×10^3 > 1234,000000×10^0 > 1234000,000×10^-3 > 1234000000×10^-6 > (none)"

private val DMS_CASES = listOf(
    case("C-15 both ways", "2°15'18\" > 2,255 > 2°15'18\"") { walkFrom(dmsValue("2.255"), MATH, listOf(DMS, DMS)) },
    case("a plain decimal", "2,255 > 2°15'18\" > 2,255") { walkFrom(d("2.255"), MATH, listOf(DMS, DMS)) },
    case("a third of a degree exactly", "1/3 > 0°20'0\" > 0,3333333333") { walkFrom(q(1, 3), MATH, listOf(DMS, DMS)) },
    case("seconds to two places GUESS", "10,1234 > 10°7'24,24\"") { walkFrom(d("10.1234"), MATH, listOf(DMS)) },
    case("negative", "-2,5 > -2°30'0\"") { walkFrom(d("-2.5"), MATH, listOf(DMS)) },
    case("rounding carries into minutes", "1 > 1°0'0\"") {
        walkFrom(d("0.99999999999999"), MATH, listOf(DMS))
    },
    case("past the conversion range", "10000000 > (none)") { walkFrom(q(10000000), MATH, listOf(DMS)) },
    case("LineIO", "2°15'18\"") { shown(dmsValue("2.255"), LINE) },
    // Review 2: a negative angle that rounds to 0°0'0" carries no sign.
    case("a tiny negative angle has no sign", "-1×10^-12 > 0°0'0\"") {
        walkFrom(d("-0.000000000001"), MATH, listOf(DMS))
    },
)

private val FACT_CASES = listOf(
    case("C-25", "1014 > 2×3×13² > 1014") { walkFrom(q(1014), MATH, listOf(FACT, FACT)) },
    case("C-26", "4104676 > 2²×(1026169)") { walkFrom(q(4104676), MATH, listOf(FACT)) },
    case("a cube", "24 > 2³×3") { walkFrom(q(24), MATH, listOf(FACT)) },
    case("a high power in MathO GUESS", "1024 > 2^10") { walkFrom(q(1024), MATH, listOf(FACT)) },
    case("a high power in LineIO GUESS", "1024 > 2^(10)") { walkFrom(q(1024), LINE, listOf(FACT)) },
    case("one GUESS", "1 > 1") { walkFrom(q(1), MATH, listOf(FACT)) },
    case("a large prime alone", "1026169 > (1026169)") { walkFrom(q(1026169), MATH, listOf(FACT)) },
    case("a fraction cannot", "1/2 > (none)") { walkFrom(q(1, 2), MATH, listOf(FACT)) },
    case("ENG leaves FACT", "1014 > 2×3×13² > 1014") { walkFrom(q(1014), MATH, listOf(FACT, ENG)) },
    case("DMS leaves FACT", "1014 > 2×3×13² > 1014") { walkFrom(q(1014), MATH, listOf(FACT, DMS)) },
    case("S to D does nothing on FACT", "1014 > 2×3×13² > (none)") { walkFrom(q(1014), MATH, listOf(FACT, SD)) },
)
