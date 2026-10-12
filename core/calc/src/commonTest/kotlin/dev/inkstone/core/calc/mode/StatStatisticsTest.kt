package dev.inkstone.core.calc.mode

import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.parse.PostfixOp
import dev.inkstone.core.calc.parse.StatVariable
import dev.inkstone.core.calc.value.DecimalReal
import dev.inkstone.core.calc.value.Real
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun dec(text: String): Dec = Dec.parse(text) ?: error("not a number: $text")

private fun point(x: String, y: String = "0", f: String = "1") = StatPoint(dec(x), dec(y), dec(f))

private fun pairs(vararg xy: Pair<String, String>): List<StatPoint> = xy.map { (x, y) -> point(x, y) }

private fun weighted(vararg xf: Pair<String, String>): List<StatPoint> = xf.map { (x, f) -> point(x, f = f) }

/** A value as the display would carry it: ten significant digits, or MATH for Math ERROR. */
private fun ten(real: Real?): String = real?.dec?.roundSignificant(TEN)?.toCanonicalString() ?: "MATH"

private fun ten(text: String): String = dec(text).roundSignificant(TEN).toCanonicalString()

private const val TEN = 10

/**
 * [StatStatistics] and [StatRegression] against values computed independently: Python's `decimal`
 * at 50 digits, solving each least-squares fit from its normal equations (the quadratic's with exact
 * fractions) rather than from the centred sums the Kotlin uses, so the two share no formula
 * (`scratchpad/calc-dev/stat-ref/refs.py`, not committed). Compared at the ten digits the display
 * shows; none of the references is within 10⁻¹¹ of a rounding tie at that digit.
 *
 * The linear and ln X data are the guide's own (EX-STAT-03/04), and those results are also what
 * the guide prints; the other types' data sets are ordinary textbook ones with no printed oracle
 * — their formulas are research 03 §4.5's ⚠️ GUESSES, and these tests pin the guesses, not the
 * firmware.
 */
class StatStatisticsTest {
    private fun check(source: StatStatistics, expected: Map<StatVariable, String>) {
        for ((variable, value) in expected) {
            assertEquals(ten(value), ten(source.value(variable)), variable.name)
        }
    }

    private fun estimate(source: StatStatistics, op: PostfixOp, at: String): String =
        ten(source.estimate(op, DecimalReal(dec(at))))

    @Test
    fun linearRegressionOnTheGuidesData() {
        val s = StatStatistics(StatType.LINEAR, GUIDE_PAIRS)
        check(
            s,
            mapOf(
                StatVariable.REG_A to "3703.222222222222",
                StatVariable.REG_B to "22.18888888888889",
                StatVariable.REG_R to "0.9228479016423498",
            ),
        )
        assertEquals(ten("5922.111111111111"), estimate(s, PostfixOp.EST_Y, "100"))
        assertEquals(ten("58.44266399599399"), estimate(s, PostfixOp.EST_X, "5000"))
    }

    @Test
    fun logarithmicRegressionOnTheGuidesData() {
        val s = StatStatistics(StatType.LOGARITHMIC, GUIDE_PAIRS)
        check(
            s,
            mapOf(
                StatVariable.REG_A to "-3857.984413270896",
                StatVariable.REG_B to "2357.531551398172",
                StatVariable.REG_R to "0.9983344682610158",
            ),
        )
        assertEquals(ten("4.861312126420021"), estimate(s, PostfixOp.EST_X, "-130"))
        assertEquals(ten("6472.781637039930"), estimate(s, PostfixOp.EST_Y, "80"))
    }

    @Test
    fun eExponentialRegression() {
        val s = StatStatistics(StatType.E_EXPONENTIAL, E_DATA)
        check(
            s,
            mapOf(
                StatVariable.REG_A to "30.49758742585542",
                StatVariable.REG_B to "-0.04920370830766393",
                StatVariable.REG_R to "-0.9972473519877514",
            ),
        )
        assertEquals(ten("13.87915739425939"), estimate(s, PostfixOp.EST_Y, "16"))
        assertEquals(ten("8.574868046549239"), estimate(s, PostfixOp.EST_X, "20"))
    }

    @Test
    fun abExponentialRegression() {
        val s = StatStatistics(StatType.AB_EXPONENTIAL, AB_DATA)
        check(
            s,
            mapOf(
                StatVariable.REG_A to "0.4888666399783183",
                StatVariable.REG_B to "2.007499343779171",
                StatVariable.REG_R to "0.9999873551795406",
            ),
        )
        assertEquals(ten("16944.22001736919"), estimate(s, PostfixOp.EST_Y, "15"))
        assertEquals(ten("1.055357865351322"), estimate(s, PostfixOp.EST_X, "1.02"))
    }

    @Test
    fun powerRegression() {
        val s = StatStatistics(StatType.POWER, POWER_DATA)
        check(
            s,
            mapOf(
                StatVariable.REG_A to "0.2388010685337722",
                StatVariable.REG_B to "2.771866157637706",
                StatVariable.REG_R to "0.9989062551235148",
            ),
        )
        assertEquals(ten("6587.674589094791"), estimate(s, PostfixOp.EST_Y, "40"))
        assertEquals(ten("20.26225681092049"), estimate(s, PostfixOp.EST_X, "1000"))
    }

    @Test
    fun inverseRegression() {
        val s = StatStatistics(StatType.INVERSE, INVERSE_DATA)
        check(
            s,
            mapOf(
                StatVariable.REG_A to "-0.09344061817312389",
                StatVariable.REG_B to "20.26709711457078",
                StatVariable.REG_R to "0.9998526952656155",
            ),
        )
        assertEquals(ten("5.697158557418527"), estimate(s, PostfixOp.EST_Y, "3.5"))
        assertEquals(ten("1.342775158247773"), estimate(s, PostfixOp.EST_X, "15"))
    }

    /** x̂₁ takes +√ and x̂₂ −√ (⚠️ GUESS at the order, StatRegression's Quadratic): here x̂₁ is the smaller. */
    @Test
    fun quadraticRegressionAndItsTwoEstimates() {
        val s = StatStatistics(StatType.QUADRATIC, QUADRATIC_DATA)
        check(
            s,
            mapOf(
                StatVariable.REG_A to "-34.86377535119357",
                StatVariable.REG_B to "1.466337699460278",
                StatVariable.REG_C to "-0.006467508930799888",
            ),
        )
        assertEquals("MATH", ten(s.value(StatVariable.REG_R)), "the quadratic has no r")
        assertEquals(ten("-13.05805444611389"), estimate(s, PostfixOp.EST_Y, "16"))
        assertEquals(ten("47.27156322452015"), estimate(s, PostfixOp.EST_X1, "20"))
        assertEquals(ten("179.4521591776575"), estimate(s, PostfixOp.EST_X2, "20"))
        assertEquals("MATH", estimate(s, PostfixOp.EST_X, "20"), "x̂ is not a quadratic estimate")
        assertEquals("MATH", estimate(s, PostfixOp.EST_X1, "100"), "no real x gives y = 100")
    }

    /** EX-STAT-02's {1;1, 2;2, 3;3, 4;2, 5;1}: the guide prints x̄ = 3 and σx = 1,154700538. */
    @Test
    fun oneVariableWithFrequencies() {
        val s = StatStatistics(StatType.ONE_VAR, weighted("1" to "1", "2" to "2", "3" to "3", "4" to "2", "5" to "1"))
        check(
            s,
            mapOf(
                StatVariable.N to "9",
                StatVariable.SUM_X to "27",
                StatVariable.SUM_X2 to "93",
                StatVariable.MEAN_X to "3",
                StatVariable.SIGMA_X to "1.154700538379252",
                StatVariable.SAMPLE_SX to "1.224744871391589",
                StatVariable.MIN_X to "1",
                StatVariable.MAX_X to "5",
                StatVariable.MEDIAN to "3",
                StatVariable.Q1 to "2",
                StatVariable.Q3 to "4",
            ),
        )
    }

    /** EX-STAT-05: x̄ = 5,1, σx = 2,75499546, `3▶t` = −0,76225171 (research 05's recomputation). */
    @Test
    fun normalisedVariateUsesThePopulationSigma() {
        val data = weighted(
            "0" to "1", "1" to "2", "2" to "1", "3" to "2", "4" to "2",
            "5" to "2", "6" to "3", "7" to "4", "9" to "2", "10" to "1",
        )
        val s = StatStatistics(StatType.ONE_VAR, data)
        val expected = mapOf(
            StatVariable.N to "20",
            StatVariable.MEAN_X to "5.1",
            StatVariable.SIGMA_X to "2.754995462791182",
        )
        check(s, expected)
        assertEquals(ten("-0.7622517090726591"), ten(s.normalise(DecimalReal(dec("3")))))
    }

    /** EX-SA-STAT-01 (VERIFIED capture): Q1 = 2198,5; med, Q3 and maxX are research 05's computed values. */
    @Test
    fun quartilesOfAnEvenCountSplitTheDataInHalves() {
        val values = "2182 2323 2267 2334 2346 2175 2293 2263 2215 2271 2018 2254".split(' ')
        val s = StatStatistics(StatType.ONE_VAR, values.map { point(it) })
        check(
            s,
            mapOf(
                StatVariable.MIN_X to "2018",
                StatVariable.Q1 to "2198.5",
                StatVariable.MEDIAN to "2265",
                StatVariable.Q3 to "2308",
                StatVariable.MAX_X to "2346",
            ),
        )
    }

    /** ⚠️ GUESS pinned (StatQuartiles): an odd count leaves the median out of both halves — research 06 §13 #9. */
    @Test
    fun quartilesOfAnOddCountLeaveTheMedianOut() {
        val s = StatStatistics(StatType.ONE_VAR, (1..7).map { point("$it") })
        check(s, mapOf(StatVariable.Q1 to "2", StatVariable.MEDIAN to "4", StatVariable.Q3 to "6"))
        val one = StatStatistics(StatType.ONE_VAR, listOf(point("5")))
        check(one, mapOf(StatVariable.Q1 to "5", StatVariable.MEDIAN to "5", StatVariable.Q3 to "5"))
    }

    /** The data expanded by frequency: {1;3, 2;1, 4;2} is 1 1 1 2 4 4. */
    @Test
    fun quartilesCountEachValueAsOftenAsItsFrequency() {
        val s = StatStatistics(StatType.ONE_VAR, weighted("4" to "2", "1" to "3", "2" to "1"))
        check(s, mapOf(StatVariable.Q1 to "1", StatVariable.MEDIAN to "1.5", StatVariable.Q3 to "4"))
    }

    @Test
    fun withNoDataOnlyTheCountAndTheSumsHaveValues() {
        val s = StatStatistics(StatType.LINEAR, emptyList())
        check(s, mapOf(StatVariable.N to "0", StatVariable.SUM_X to "0", StatVariable.SUM_XY to "0"))
        for (variable in listOf(StatVariable.MEAN_X, StatVariable.SIGMA_X, StatVariable.MIN_Y, StatVariable.REG_A)) {
            assertNull(s.value(variable), variable.name)
        }
        assertNull(s.estimate(PostfixOp.EST_Y, DecimalReal(Dec.ONE)))
        assertNull(StatStatistics(StatType.ONE_VAR, emptyList()).normalise(DecimalReal(Dec.ONE)))
    }

    @Test
    fun oneValueHasNoSampleDeviationAndNoNormalisedVariate() {
        val s = StatStatistics(StatType.ONE_VAR, listOf(point("7")))
        check(s, mapOf(StatVariable.SIGMA_X to "0", StatVariable.MEAN_X to "7"))
        assertNull(s.value(StatVariable.SAMPLE_SX))
        assertNull(s.normalise(DecimalReal(dec("7"))), "(7 − 7)/0")
    }

    @Test
    fun aRegressionTheDataCannotDetermineIsAMathError() {
        val sameX = StatStatistics(StatType.LINEAR, pairs("2" to "1", "2" to "5"))
        assertNull(sameX.value(StatVariable.REG_B), "every x the same: Sxx = 0")
        val sameY = StatStatistics(StatType.LINEAR, pairs("1" to "4", "2" to "4", "3" to "4"))
        check(sameY, mapOf(StatVariable.REG_A to "4", StatVariable.REG_B to "0"))
        assertNull(sameY.value(StatVariable.REG_R), "every y the same: r is 0/0")
        assertNull(sameY.estimate(PostfixOp.EST_X, DecimalReal(dec("4"))), "x̂ divides by B = 0")
        val twoX = StatStatistics(StatType.QUADRATIC, pairs("1" to "1", "2" to "4", "1" to "2"))
        assertNull(twoX.value(StatVariable.REG_A), "two distinct x cannot fix a parabola")
    }

    @Test
    fun aTransformOutsideItsDomainIsAMathErrorForTheFitAlone() {
        val zeroX = StatStatistics(StatType.LOGARITHMIC, pairs("0" to "1", "1" to "2", "2" to "3"))
        assertNull(zeroX.value(StatVariable.REG_A), "ln 0")
        check(zeroX, mapOf(StatVariable.N to "3", StatVariable.MEAN_X to "1"))
        val negativeY = StatStatistics(StatType.E_EXPONENTIAL, pairs("1" to "-1", "2" to "2", "3" to "3"))
        assertNull(negativeY.value(StatVariable.REG_B), "ln −1")
        val zeroInverse = StatStatistics(StatType.INVERSE, pairs("0" to "1", "1" to "2"))
        assertNull(zeroInverse.value(StatVariable.REG_A), "1/0")
    }

    /** ⚠️ GUESSES pinned (StatStatistics): FREQ 0 does not occur, a negative FREQ is an error, a fraction weights. */
    @Test
    fun frequenciesZeroNegativeAndFractional() {
        val zero = StatStatistics(StatType.ONE_VAR, weighted("1" to "0", "5" to "1", "9" to "1"))
        check(zero, mapOf(StatVariable.N to "2", StatVariable.MIN_X to "5", StatVariable.MEDIAN to "7"))
        val negative = StatStatistics(StatType.ONE_VAR, weighted("1" to "-1", "5" to "2"))
        assertNull(negative.value(StatVariable.N))
        assertNull(negative.normalise(DecimalReal(Dec.ONE)))
        val fraction = StatStatistics(StatType.ONE_VAR, weighted("2" to "0.5", "4" to "1.5"))
        check(fraction, mapOf(StatVariable.N to "2", StatVariable.MEAN_X to "3.5"))
        assertNull(fraction.value(StatVariable.Q1), "quartiles count whole data")
    }

    /** Questions the menus of a type cannot ask (research 03 §4.2) answer Math ERROR. */
    @Test
    fun theOtherKindOfTypesVariablesAreMathErrors() {
        val single = StatStatistics(StatType.ONE_VAR, listOf(point("1"), point("2")))
        for (variable in listOf(StatVariable.MEAN_Y, StatVariable.SUM_XY, StatVariable.REG_A, StatVariable.MAX_Y)) {
            assertNull(single.value(variable), variable.name)
        }
        assertNull(single.estimate(PostfixOp.EST_Y, DecimalReal(Dec.ONE)))
        val paired = StatStatistics(StatType.LINEAR, GUIDE_PAIRS)
        for (variable in listOf(StatVariable.Q1, StatVariable.MEDIAN, StatVariable.Q3, StatVariable.REG_C)) {
            assertNull(paired.value(variable), variable.name)
        }
        assertNull(paired.estimate(PostfixOp.EST_X1, DecimalReal(Dec.ONE)), "x̂₁ is the quadratic's")
        check(
            paired,
            mapOf(StatVariable.SUM_X2Y to "1224682000", StatVariable.MAX_Y to "9310", StatVariable.MIN_Y to "3150"),
        )
    }

    /** A step past ±1×10¹⁰⁰ is Math ERROR for the statistic that took it, not for the others. */
    @Test
    fun overflowFailsOnlyTheStatisticThatOverflows() {
        val s = StatStatistics(StatType.ONE_VAR, listOf(point("1E60"), point("3E60")))
        check(s, mapOf(StatVariable.SUM_X to "4E60", StatVariable.MEAN_X to "2E60"))
        assertNull(s.value(StatVariable.SUM_X2), "Σx² = 1×10¹²⁰")
        assertNull(s.value(StatVariable.SIGMA_X), "its spread overflows on the way")
    }

    private companion object {
        /** EX-STAT-03's (20;3150), (110;7310), (200;8800), (290;9310). */
        val GUIDE_PAIRS = pairs("20" to "3150", "110" to "7310", "200" to "8800", "290" to "9310")
        val E_DATA = pairs("6.9" to "21.4", "12.9" to "15.7", "19.8" to "12.1", "26.7" to "8.5", "35.1" to "5.2")
        val AB_DATA = pairs("-1" to "0.24", "3" to "4", "5" to "16.2", "10" to "513")
        val POWER_DATA = pairs("28" to "2410", "30" to "3033", "33" to "3895", "35" to "4491", "38" to "5717")
        val INVERSE_DATA = pairs("1.1" to "18.3", "2.1" to "9.7", "2.9" to "6.8", "4.0" to "4.9", "4.9" to "4.1")
        val QUADRATIC_DATA = pairs("29" to "1.6", "50" to "23.5", "74" to "38.0", "103" to "46.4", "118" to "48.9")
    }
}
