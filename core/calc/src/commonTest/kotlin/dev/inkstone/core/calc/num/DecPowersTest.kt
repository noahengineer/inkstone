package dev.inkstone.core.calc.num

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `xʸ` for an exponent that is not an integer — the cases the first pass got wrong.
 *
 * - **Exact ties.** `x^(p/q)` can be a terminating decimal of exactly 16 significant digits ending
 *   in 5, which a half-away rounding must send *up*: `100005³ = 1000150007500125` is
 *   `10001000025^1,5`, and `625^5,5 = 5²²`. Through `exp(y·ln x)` the 42-digit value lands a hair
 *   either side of the tie and half of them round down. A small denominator is now an exact
 *   integer root, so the 16th digit is the true one.
 * - **A negative base with an odd denominator.** Research 02 §13 (VERIFIED, guide p.89–91): for
 *   `x < 0`, `y` may be `n` *or* `m/(2n+1)`. A typed `0,2` **is** `1/5`, so `(−32)^0,2 = −2` and
 *   `(−8)^0,4 = ⁵√64` — the first pass answered both with Math ERROR. An even denominator
 *   (`0,5`, `1,5`, `0,25`) is still an error. How the firmware recognises `0,2` as `1/5` is not
 *   documented; recognising every decimal whose reduced denominator is odd is a guess (research
 *   07 §4.5 has no algorithm for `xʸ`), and the one that agrees with the documented domain.
 * - **The documented floor on `y·log x`.** `x > 0` needs `−1×10¹⁰⁰ < y·log x` (research 02 §13);
 *   past it the calculator's own intermediate overflows, so it is Math ERROR and not an
 *   underflow to zero.
 *
 * Expected digits are Python 3.14 `decimal`, exact where the result terminates and at 200 digits
 * otherwise, rounded once to 15 significant digits `ROUND_HALF_UP` (`powgen.py`, session
 * scratchpad, beside `refgen.py`).
 */
class DecPowersTest {
    private class Pow(val x: String, val y: String, val expected: String)

    private val references = listOf(
        Pow("10001000025", "1.5", "1.00015000750013E15"),
        Pow("625", "5.5", "2.38418579101563E15"),
        Pow("16", "-5.5", "2.38418579101563E-7"),
        Pow("2.25", "1.5", "3.375E0"),
        Pow("-32", "0.2", "-2E0"),
        Pow("-8", "0.4", "2.29739670999407E0"),
        Pow("-1", "0.04", "-1E0"),
        Pow("-243", "-0.2", "-3.33333333333333E-1"),
        Pow("-32", "1.2", "6.4E1"),
        Pow("-2", "0.008", "-1.00556058039847E0"),
        Pow("-7.5", "0.6", "-3.34993791331143E0"),
        Pow("2", "0.5", "1.41421356237310E0"),
        Pow("123.456", "0.25", "3.33332799998720E0"),
        Pow("0.0001", "0.75", "1E-3"),
        Pow("1.00000000000001", "2.5", "1.00000000000003E0"),
        Pow("9.87654321", "-1.25", "5.71141594753572E-2"),
    )

    @Test
    fun `rational exponents are correctly rounded including exact ties`() {
        for (p in references) assertEquals(d(p.expected), DecMath.pow(d(p.x), d(p.y)), "${p.x} ^ ${p.y}")
    }

    /**
     * Forty seeded random cases for the exact path: every denominator it takes (2…40), numerators
     * to ±120, bases at exponents −30…30, a third of the odd-denominator ones negative
     * (`powsweep.py`, seed 20261004, same reference as above).
     */
    private val sweep = listOf(
        Pow("-5.323126E-3", "-1.04E1", "4.44493935260502E23"),
        Pow("5.49500E9", "2.85E0", "5.73994770605420E27"),
        Pow("3.48E21", "1.9375E0", "5.45518879190037E41"),
        Pow("9.48596E-2", "7.25E-1", "1.81294210121369E-1"),
        Pow("5.63956080E12", "7.2E-1", "1.51668923350940E9"),
        Pow("1.22995988E-11", "6.7E0", "7.98489729523169E-74"),
        Pow("5.334767E9", "-1.8125E0", "2.34208814568844E-18"),
        Pow("2.998392161174E18", "3.9375E0", "5.65911094402887E72"),
        Pow("7.87765E-4", "-5.7E0", "4.90386963032265E17"),
        Pow("1.8894571958837E-12", "-2.1875E0", "4.42092030574791E25"),
        Pow("-4.2117E-3", "4.76E0", "-4.92512617356389E-12"),
        Pow("1.874E15", "3.125E-1", "5.92571139438101E4"),
        Pow("2.5231E25", "3.34375E0", "8.66399838447184E84"),
        Pow("3.2901207E-26", "2.725E0", "3.62578658967875E-70"),
        Pow("9.56E-12", "2.825E0", "7.40957921996016E-32"),
        Pow("9.84191E-4", "3.40625E0", "5.72369745657995E-11"),
        Pow("1.750152442669E6", "-1.18E1", "2.14637021985473E-74"),
        Pow("9.650E5", "7.5E-2", "2.81086215643657E0"),
        Pow("8.1E-23", "-2.1875E0", "2.11440910952996E48"),
        Pow("-9.976784629959E12", "1.16E0", "-1.19902735872630E15"),
        Pow("4.5637752461E21", "-3.32E0", "1.23322833676950E-72"),
        Pow("1.22E4", "-3.3125E0", "2.91026851508675E-14"),
        Pow("8.6938824578076E-14", "4.28E0", "1.25844694477510E-56"),
        Pow("1.931022818E16", "4.64E0", "3.68174378425773E75"),
        Pow("7.543598712E8", "-3.45E0", "2.35696919790700E-31"),
        Pow("8.9138E-14", "2.1E0", "3.93669763790326E-28"),
        Pow("2.0618E-2", "-1.5E-1", "1.79004117028287E0"),
        Pow("7.36183795070E-10", "-1.825E0", "4.65318251362109E16"),
        Pow("-1.38653989062825E-6", "3.32E0", "-3.55807799732666E-20"),
        Pow("9.76953819636E27", "-7.5E-2", "7.95718485493210E-3"),
        Pow("8.75E-16", "-1.7E0", "3.96813382121570E25"),
        Pow("2.7862076850224E21", "-4.5E-1", "2.23740153346296E-10"),
        Pow("7.667171570E-8", "-1.35E0", "4.03406140638244E9"),
        Pow("7.3E-19", "-2.725E0", "2.64511894575248E49"),
        Pow("8.8624879E-4", "2.55E1", "1.45435091603631E-78"),
        Pow("1.12644127E22", "3.04E0", "1.08941663576280E67"),
        Pow("8.39127127299731E-3", "-1.0625E1", "1.14638379239588E22"),
        Pow("6.439199444580E-28", "-2.53125E0", "6.72425712061533E68"),
        Pow("3.36295840355848E-23", "-7.1875E-1", "1.42124491895686E16"),
        Pow("5.0E14", "3.4375E0", "3.37061686844114E50"),
    )

    @Test
    fun `random rational exponents are correctly rounded`() {
        val wrong = sweep.filter { DecMath.pow(d(it.x), d(it.y)) != d(it.expected) }
        val report = wrong.map { "${it.x} ^ ${it.y} = ${DecMath.pow(d(it.x), d(it.y))}, want ${it.expected}" }
        assertEquals(emptyList<String>(), report)
    }

    @Test
    fun `a negative base needs an odd denominator`() {
        val errors = listOf("-8" to "0.5", "-8" to "1.5", "-8" to "0.25", "-2" to "0.1", "-2" to "2.34567890123456")
        for ((x, y) in errors) assertNull(DecMath.pow(d(x), d(y)), "$x ^ $y")
    }

    @Test
    fun `a half power is the square root to the last digit`() {
        val values = listOf("2", "3", "0.5", "123.456", "99999999999999.9", "1E-99", "8.5E99", "7.77777777777777")
        for (v in values) assertEquals(DecMath.sqrt(d(v)), DecMath.pow(d(v), Dec.HALF), v)
    }

    @Test
    fun `y log x at or below minus ten to the hundred is an error not an underflow`() {
        // 1×10⁻⁹⁹ to the 1,02×10⁹⁸: y·log x = −1,0098×10¹⁰⁰.
        assertNull(DecMath.pow(d("1E-99"), d("1.02E98")))
        assertNull(DecMath.pow(d("1E-50"), d("3.5E98")))
        assertNull(DecMath.pow(d("-1E-50"), d("3E98")))
        // Just inside the floor it is an ordinary underflow to zero.
        assertEquals(Dec.ZERO, DecMath.pow(d("1E-99"), d("1E98")))
        assertEquals(Dec.ZERO, DecMath.pow(d("0.5"), d("1E99")))
    }

    @Test
    fun `the exact path keeps the range rules`() {
        assertNotNull(DecMath.pow(d("1.00000000000001"), d("79.5")))
        assertEquals(Dec.ZERO, DecMath.pow(d("1E-99"), d("1.5")))
        assertNull(DecMath.pow(d("1E99"), d("1.5")))
        assertEquals(d("1E-66"), DecMath.pow(d("1E-99"), d("0.666666666666666"))?.roundSignificant(10))
        assertEquals(d("1E-49"), DecMath.pow(d("1E-98"), d("0.5")))
    }
}
