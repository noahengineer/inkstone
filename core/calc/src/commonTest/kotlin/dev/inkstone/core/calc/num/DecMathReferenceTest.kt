package dev.inkstone.core.calc.num

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DecMath against digits it did not compute.
 *
 * Two independent sources, and the test says which a row comes from:
 * - **published constants** — π, e, ln 2, ln 10, √2, sin 1, cos 1, π/4, 20! and 69! — whose
 *   digits are standard;
 * - a **table from Python 3.14's `decimal` module** at 130 digits, rounded once to 15 significant
 *   digits `ROUND_HALF_UP` (half away from zero). `exp`, `ln`, `log10`, `sqrt` and `**` are its
 *   built-ins; trig is Machin's π and Taylor series in Python, written separately from this
 *   code, and `atan` uses Euler's series rather than the halving used here. The script lives in
 *   the session scratchpad (`refgen.py`); the digits are pasted below unchanged.
 *
 * Every row demands the **correctly rounded** 15-digit value — exact equality, not a tolerance —
 * which is what CALCULATOR.md §3 promises (research 07 T11). What the real calculator shows in
 * the 15th digit is a separate question that only the emulator can answer.
 */
class DecMathReferenceTest {
    private class Row(val function: String, val argument: String, val expected: String)

    private class Pow(val x: String, val y: String, val expected: String)

    private val functions: Map<String, (Dec) -> Dec?> = mapOf(
        "exp" to DecMath::exp, "ln" to DecMath::ln, "log10" to DecMath::log10, "sqrt" to DecMath::sqrt,
        "cbrt" to DecMath::cbrt, "tenPow" to DecMath::tenPow, "sin" to DecMath::sin, "cos" to DecMath::cos,
        "tan" to DecMath::tan, "atan" to DecMath::atan, "asin" to DecMath::asin, "acos" to DecMath::acos,
        "sinh" to DecMath::sinh, "cosh" to DecMath::cosh, "tanh" to DecMath::tanh, "asinh" to DecMath::asinh,
        "acosh" to DecMath::acosh, "atanh" to DecMath::atanh,
    )

    @Test
    fun `published constants come out correctly rounded`() {
        val one = Dec.ONE
        // ln 2 = 0,693147180559945|309…  ln 10 = 2,30258509299404|568…  √2 = 1,41421356237309|5048…
        assertEquals(d("0.693147180559945"), DecMath.ln(Dec.TWO))
        assertEquals(d("2.30258509299405"), DecMath.ln(Dec.TEN))
        assertEquals(d("1.41421356237310"), DecMath.sqrt(Dec.TWO))
        // e = 2,71828182845904|5235…: the true e rounds UP, unlike Casio's truncated Dec.E.
        assertEquals(d("2.71828182845905"), DecMath.exp(one))
        // sin 1 = 0,841470984807896|5066…  cos 1 = 0,540302305868139|717…
        assertEquals(d("0.841470984807897"), DecMath.sin(one))
        assertEquals(d("0.540302305868140"), DecMath.cos(one))
        // atan 1 = π/4 = 0,785398163397448|3096… with the true π.
        assertEquals(d("0.785398163397448"), DecMath.atan(one))
        // π = 3,14159265358979|3238…, reached three ways; never Casio's …980.
        val truePi = d("3.14159265358979")
        assertEquals(truePi, DecMath.atan2(Dec.ZERO, -one))
        assertEquals(truePi, DecMath.acos(-one))
        assertEquals(truePi, DecMath.atan(one)?.let { it * d("4") }?.roundSignificant(15))
    }

    @Test
    fun `factorials are exact before the one rounding`() {
        assertEquals(d("2432902008176640000"), DecMath.factorial(20))
        // 69! = 1,71122452428141|31137…×10⁹⁸, the largest the calculator holds.
        assertEquals(d("1.71122452428141E98"), DecMath.factorial(69))
        assertEquals(Dec.ONE, DecMath.factorial(0))
        assertEquals(Dec.ONE, DecMath.factorial(1))
        assertEquals(d("3628800"), DecMath.factorial(10))
        assertNull(DecMath.factorial(70))
        assertNull(DecMath.factorial(-1))
    }

    @Test
    fun `the internal constants agree with published digits far past fifteen`() {
        val pi = "3141592653589793238462643383279502884197169399375105820974944592307816406286" +
            "208998628034825342117067982148086513282306647093844609550582231725359408128481"
        val ln2 = "69314718055994530941723212145817656807550013436025525412068000"
        val ln10 = "230258509299404568401799145468436420760110148862877297603332790"
        assertTrue(HiConstants.pi.mag.toString().startsWith(pi.substring(0, PI_CHECKED)))
        assertTrue(HiConstants.ln2.mag.toString().startsWith(ln2.substring(0, LOG_CHECKED)))
        assertTrue(HiConstants.ln10.mag.toString().startsWith(ln10.substring(0, LOG_CHECKED)))
    }

    @Test
    fun `every function matches the reference table`() {
        for (row in rows) {
            val f = functions.getValue(row.function)
            assertEquals(d(row.expected), f(d(row.argument)), "${row.function}(${row.argument})")
        }
    }

    @Test
    fun `pow matches the reference table`() {
        for (p in pows) assertEquals(d(p.expected), DecMath.pow(d(p.x), d(p.y)), "${p.x} ^ ${p.y}")
    }

    private val pows = listOf(
        Pow("2", "0.5", "1.41421356237310E0"),
        Pow("1.5", "2.5", "2.75567596063108E0"),
        Pow("10", "-0.5", "3.16227766016838E-1"),
        Pow("0.5", "100.5", "5.57808895494736E-31"),
        Pow("123.456", "7.89", "3.17710282581810E16"),
        Pow("2", "331", "4.37450144956602E99"),
        Pow("-2", "331", "-4.37450144956602E99"),
        Pow("-3", "4", "8.1E1"),
        Pow("1.0000001", "1000000", "1.10517091254979E0"),
        Pow("7", "-3", "2.91545189504373E-3"),
        Pow("0.3", "-50", "1.39295556909854E26"),
        Pow("123.45675", "2", "1.52415691205625E4"),
        Pow("9.87654321", "0.123456789", "1.32675500825942E0"),
    )

    private val rows = listOf(
        Row("exp", "1", "2.71828182845905E0"),
        Row("exp", "-1", "3.67879441171442E-1"),
        Row("exp", "0.5", "1.64872127070013E0"),
        Row("exp", "2.30258509299405", "1.00000000000000E1"),
        Row("exp", "100", "2.68811714181614E43"),
        Row("exp", "-100", "3.72007597602084E-44"),
        Row("exp", "230", "7.72201849998384E99"),
        Row("exp", "-227", "2.60107340111005E-99"),
        Row("exp", "1E-10", "1.00000000010000E0"),
        Row("exp", "12.345678901234", "2.29964194852858E5"),
        Row("exp", "0.000123456789", "1.00012346441010E0"),
        Row("exp", "-50.5", "1.16984591770620E-22"),
        Row("exp", "7", "1.09663315842846E3"),
        Row("ln", "2", "6.93147180559945E-1"),
        Row("ln", "10", "2.30258509299405E0"),
        Row("ln", "0.5", "-6.93147180559945E-1"),
        Row("ln", "1.00000000000001", "9.99999999999995E-15"),
        Row("ln", "0.999999999999999", "-1.00000000000000E-15"),
        Row("ln", "123456789.012345", "1.86314017662680E1"),
        Row("ln", "1E-99", "-2.27955924206411E2"),
        Row("ln", "9.99999999999999E99", "2.30258509299405E2"),
        Row("ln", "3", "1.09861228866811E0"),
        Row("ln", "1.5", "4.05465108108164E-1"),
        Row("ln", "0.001", "-6.90775527898214E0"),
        Row("ln", "2.71828182845904", "9.99999999999998E-1"),
        Row("log10", "2", "3.01029995663981E-1"),
        Row("log10", "3", "4.77121254719662E-1"),
        Row("log10", "0.002", "-2.69897000433602E0"),
        Row("log10", "7.5E50", "5.08750612633917E1"),
        Row("log10", "1.00000000000001", "4.34294481903250E-15"),
        Row("log10", "99", "1.99563519459755E0"),
        Row("sqrt", "2", "1.41421356237310E0"),
        Row("sqrt", "3", "1.73205080756888E0"),
        Row("sqrt", "0.5", "7.07106781186548E-1"),
        Row("sqrt", "1E-99", "3.16227766016838E-50"),
        Row("sqrt", "99999999999999.9", "9.99999999999999E6"),
        Row("sqrt", "123.456", "1.11110755554987E1"),
        Row("sqrt", "10", "3.16227766016838E0"),
        Row("sqrt", "8.5E99", "9.21954445729289E49"),
        Row("cbrt", "2", "1.25992104989487E0"),
        Row("cbrt", "-27", "-3.00000000000000E0"),
        Row("cbrt", "10", "2.15443469003188E0"),
        Row("cbrt", "0.001", "1.00000000000000E-1"),
        Row("cbrt", "1E-98", "2.15443469003188E-33"),
        Row("cbrt", "-5.5", "-1.76517416766303E0"),
        Row("cbrt", "1E99", "1.00000000000000E33"),
        Row("tenPow", "0.5", "3.16227766016838E0"),
        Row("tenPow", "-0.5", "3.16227766016838E-1"),
        Row("tenPow", "2.5", "3.16227766016838E2"),
        Row("tenPow", "99.9", "7.94328234724282E99"),
        Row("tenPow", "0.30102999566398", "1.99999999999999E0"),
        Row("tenPow", "-98.7", "1.99526231496888E-99"),
        Row("tenPow", "1E-10", "1.00000000023026E0"),
        Row("sin", "1", "8.41470984807897E-1"),
        Row("sin", "0.5", "4.79425538604203E-1"),
        Row("sin", "2", "9.09297426825682E-1"),
        Row("sin", "3", "1.41120008059867E-1"),
        Row("sin", "-1", "-8.41470984807897E-1"),
        Row("sin", "10", "-5.44021110889370E-1"),
        Row("sin", "100", "-5.06365641109759E-1"),
        Row("sin", "100000", "3.57487979720165E-2"),
        Row("sin", "10000000000", "-4.87506025087511E-1"),
        Row("sin", "157079632.6", "-7.94059777187522E-2"),
        Row("sin", "1E-10", "1.00000000000000E-10"),
        Row("sin", "0.785398163397448", "7.07106781186547E-1"),
        Row("sin", "1.5707963267949", "1.00000000000000E0"),
        Row("sin", "3.14159265358979", "3.23846264338328E-15"),
        Row("sin", "3.1415926535898", "-6.76153735661672E-15"),
        Row("sin", "6.28318530717959", "3.52307471323344E-15"),
        Row("sin", "1E50", "-7.89672493429310E-1"),
        Row("sin", "-123456.789", "9.98664082343447E-1"),
        // The top of the range: reducing these needs ~150 digits of π (Python run at 320).
        Row("sin", "9.99999999999999E99", "4.66814623140090E-1"),
        Row("cos", "9.99999999999999E99", "-8.84355193133718E-1"),
        Row("tan", "9.99999999999999E99", "-5.27858745857452E-1"),
        Row("sin", "1.23456789012345E99", "8.61522461324048E-2"),
        Row("cos", "1.23456789012345E99", "-9.96281983419524E-1"),
        Row("tan", "1.23456789012345E99", "-8.64737569946871E-2"),
        Row("sin", "-5.5E77", "5.58437982785510E-1"),
        Row("cos", "-5.5E77", "-8.29546273201472E-1"),
        Row("tan", "-5.5E77", "-6.73184849146905E-1"),
        Row("cos", "1", "5.40302305868140E-1"),
        Row("cos", "0.5", "8.77582561890373E-1"),
        Row("cos", "2", "-4.16146836547142E-1"),
        Row("cos", "3", "-9.89992496600445E-1"),
        Row("cos", "-1", "5.40302305868140E-1"),
        Row("cos", "10", "-8.39071529076452E-1"),
        Row("cos", "100", "8.62318872287684E-1"),
        Row("cos", "100000", "-9.99360807438212E-1"),
        Row("cos", "10000000000", "8.73119622676856E-1"),
        Row("cos", "157079632.6", "9.96842360006099E-1"),
        Row("cos", "1E-10", "1.00000000000000E0"),
        Row("cos", "1.5707963267949", "-3.38076867830836E-15"),
        Row("cos", "3.1415926535898", "-1.00000000000000E0"),
        Row("cos", "1E50", "-6.13528608233664E-1"),
        Row("tan", "1", "1.55740772465490E0"),
        Row("tan", "0.5", "5.46302489843791E-1"),
        Row("tan", "2", "-2.18503986326152E0"),
        Row("tan", "-1", "-1.55740772465490E0"),
        Row("tan", "10", "6.48360827459087E-1"),
        Row("tan", "1000", "1.47032415570272E0"),
        Row("tan", "1.5707963267949", "-2.95790719553274E14"),
        Row("tan", "1.57079632679", "2.04222536562248E11"),
        Row("tan", "1E-10", "1.00000000000000E-10"),
        Row("tan", "3.1415926535898", "6.76153735661672E-15"),
        Row("tan", "10000000000", "-5.58349637811242E-1"),
        Row("atan", "1", "7.85398163397448E-1"),
        Row("atan", "0.5", "4.63647609000806E-1"),
        Row("atan", "2", "1.10714871779409E0"),
        Row("atan", "-3", "-1.24904577239825E0"),
        Row("atan", "10000000000", "1.57079632669490E0"),
        Row("atan", "1E-10", "1.00000000000000E-10"),
        Row("atan", "1E99", "1.57079632679490E0"),
        Row("atan", "0.1", "9.96686524911620E-2"),
        Row("atan", "-0.999999999999999", "-7.85398163397448E-1"),
        Row("asin", "0.5", "5.23598775598299E-1"),
        Row("asin", "-0.5", "-5.23598775598299E-1"),
        Row("asin", "0.999999999999999", "1.57079628207354E0"),
        Row("asin", "1E-10", "1.00000000000000E-10"),
        Row("asin", "0.1", "1.00167421161560E-1"),
        Row("asin", "1", "1.57079632679490E0"),
        Row("asin", "-1", "-1.57079632679490E0"),
        Row("acos", "0.5", "1.04719755119660E0"),
        Row("acos", "-0.5", "2.09439510239320E0"),
        Row("acos", "0.999999999999999", "4.47213595499958E-8"),
        Row("acos", "1E-10", "1.57079632669490E0"),
        Row("acos", "0.1", "1.47062890563334E0"),
        Row("acos", "1", "0"),
        Row("acos", "-1", "3.14159265358979E0"),
        Row("acos", "0", "1.57079632679490E0"),
        Row("sinh", "1", "1.17520119364380E0"),
        Row("sinh", "0.5", "5.21095305493747E-1"),
        Row("sinh", "-2", "-3.62686040784702E0"),
        Row("sinh", "10", "1.10132328747034E4"),
        Row("sinh", "100", "1.34405857090807E43"),
        Row("sinh", "230", "3.86100924999192E99"),
        Row("sinh", "1E-10", "1.00000000000000E-10"),
        Row("sinh", "20", "2.42582597704895E8"),
        Row("sinh", "0.999", "1.17365870035245E0"),
        Row("cosh", "1", "1.54308063481524E0"),
        Row("cosh", "0.5", "1.12762596520638E0"),
        Row("cosh", "-2", "3.76219569108363E0"),
        Row("cosh", "10", "1.10132329201033E4"),
        Row("cosh", "100", "1.34405857090807E43"),
        Row("cosh", "230", "3.86100924999192E99"),
        Row("cosh", "1E-10", "1.00000000000000E0"),
        Row("cosh", "20", "2.42582597704895E8"),
        Row("tanh", "1", "7.61594155955765E-1"),
        Row("tanh", "0.5", "4.62117157260010E-1"),
        Row("tanh", "-2", "-9.64027580075817E-1"),
        Row("tanh", "10", "9.99999995877693E-1"),
        Row("tanh", "1E-10", "1.00000000000000E-10"),
        Row("tanh", "20", "1.00000000000000E0"),
        Row("tanh", "0.999", "7.61173861660570E-1"),
        Row("tanh", "50", "1.00000000000000E0"),
        Row("asinh", "1", "8.81373587019543E-1"),
        Row("asinh", "0.5", "4.81211825059603E-1"),
        Row("asinh", "-2", "-1.44363547517881E0"),
        Row("asinh", "10000000000", "2.37189981105004E1"),
        Row("asinh", "1E-10", "1.00000000000000E-10"),
        Row("asinh", "1E99", "2.28649071386970E2"),
        Row("acosh", "1.5", "9.62423650119207E-1"),
        Row("acosh", "2", "1.31695789692482E0"),
        Row("acosh", "10", "2.99322284612638E0"),
        Row("acosh", "1E50", "1.15822401830262E2"),
        Row("acosh", "1.00000000000001", "1.41421356237309E-7"),
        Row("acosh", "1", "0"),
        Row("atanh", "0.5", "5.49306144334055E-1"),
        Row("atanh", "-0.5", "-5.49306144334055E-1"),
        Row("atanh", "0.999999999999999", "1.76159617877353E1"),
        Row("atanh", "1E-10", "1.00000000000000E-10"),
        Row("atanh", "0.1", "1.00335347731076E-1"),
    )

    private companion object {
        /**
         * π is held to 170 digits and the logs to 62, all truncated; these prefixes stop short of
         * the last few, which a truncated series may legitimately leave one unit low.
         */
        const val PI_CHECKED = 150
        const val LOG_CHECKED = 55
    }
}
