package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.NumberFormat
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.parse.PostfixOp
import dev.inkstone.core.calc.parse.StatVariable
import dev.inkstone.core.calc.value.DecimalReal
import dev.inkstone.core.calc.value.FractionReal
import dev.inkstone.core.calc.value.Real
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private fun dec(text: String): Real = DecimalReal(Dec.parse(text) ?: error(text))

/**
 * STAT's answers for research 05's examples, as STAT mode would compute them from the editor
 * (its own job, not this package's): the values are the guide's, at the precision research 05
 * recomputed them.
 */
private class GuideStatistics(
    private val values: Map<StatVariable, Real>,
    private val mean: Dec = Dec.ZERO,
    private val sigma: Dec = Dec.ONE,
) : StatisticsSource {
    override fun value(variable: StatVariable): Real? = values[variable]

    override fun estimate(op: PostfixOp, operand: Real): Real? =
        if (op == PostfixOp.EST_X && operand.dec == Dec.of(-130)) dec("4.861312") else null

    override fun normalise(operand: Real): Real = DecimalReal((operand.dec - mean) / sigma)
}

/** EX-STAT-02: {1;1, 2;2, 3;3, 4;2, 5;1}. */
private val ONE_VAR = GuideStatistics(
    mapOf(StatVariable.MEAN_X to FractionReal(3, 1), StatVariable.SIGMA_X to dec("1.15470053837925")),
)

/** EX-STAT-03/04: the ln X regression, Fix 3. */
private val LN_X = GuideStatistics(
    mapOf(
        StatVariable.REG_R to dec("0.998334470"),
        StatVariable.REG_A to dec("-3857.98441"),
        StatVariable.REG_B to dec("2357.53155"),
    ),
)

/** EX-STAT-05: x̄ = 5,1, σx = 2,75499546. */
private val NORMALISED = GuideStatistics(emptyMap(), mean = dec("5.1").dec, sigma = dec("2.75499546").dec)

private val FIX3 = line(NumberFormat.Fix(3))

class StatisticsTest {
    @Test
    fun `STAT variables and estimates come from STAT EX-STAT-02 to 04`() {
        verify(
            listOf(Case("EX-STAT-02", "3", mode = Mode.STAT), Case("EX-STAT-02b", "~1.154700538", mode = Mode.STAT)),
            ONE_VAR,
        )
        verify(
            listOf(
                Case("EX-STAT-03", "~0.998", mode = Mode.STAT, setup = FIX3),
                Case("EX-STAT-03b", "~-3857.984", mode = Mode.STAT, setup = FIX3),
                Case("EX-STAT-03c", "~2357.532", mode = Mode.STAT, setup = FIX3),
                Case("EX-STAT-04", "~4.861", mode = Mode.STAT, setup = FIX3),
            ),
            LN_X,
        )
    }

    @Test
    fun `normalised variate and P of it EX-STAT-05`() {
        val memory = Memory()
        val t = evaluate(GUIDE.getValue("EX-STAT-05").keys, false, context(Mode.STAT, FIX3, memory, NORMALISED))
        assertEquals("~-0.762", text(t, FIX3.format))
        memory.ans = assertIs<EvalOutcome.Done>(t).answer.value
        val p = evaluate(GUIDE.getValue("EX-STAT-05b").keys, false, context(Mode.STAT, FIX3, memory, NORMALISED))
        assertEquals("~0.223", text(p, FIX3.format))
    }

    @Test
    fun `P Q and R use the ROM coefficients`() {
        // Research 07 §4.8's predictions for the ROM set (the true P(1) is 0,8413447461).
        val cases = listOf(
            "P( 0 , 5 )" to "~0.6914621132",
            "P( 1 )" to "~0.8413445602",
            "P( 2 )" to "~0.9772499143",
            "P( (−) 1 )" to "~0.1586554398",
            "R( 1 )" to "~0.1586554398",
            "Q( 1 )" to "~0.3413445602",
            "Q( (−) 1 )" to "~0.3413445602",
            // The ROM's a₁…a₅ sum to 1,253315542 rather than √(π/2), so even P(0) misses ½ in the
            // 7th digit — a sharp check for research 07 T13.
            "P( 0 )" to "~0.4999994396",
            "P( 5 0 )" to "~1",
        )
        for ((keys, expected) in cases) {
            assertEquals(expected, text(evaluate(keys, false, context(Mode.STAT, line())), NumberFormat.Norm(2)), keys)
        }
    }

    @Test
    fun `R is the upper tail itself so it mirrors P`() {
        // The polynomial gives the tail beyond |t| directly (research 07 §4.8). R(t) for t > 0 is
        // that tail; as 1 − P(t) it kept only the digits P had to spare: R(8) came out 1×10⁻¹⁵
        // instead of 6,28×10⁻¹⁶, and R(9) came out 0.
        val stat = context(Mode.STAT, line())
        for (t in listOf("3", "5", "8", "9")) {
            val upper = text(evaluate("R( $t )", false, stat), NumberFormat.Norm(2))
            val mirrored = text(evaluate("P( (−) $t )", false, stat), NumberFormat.Norm(2))
            assertEquals(mirrored, upper, "R($t) against P(-$t)")
        }
        assertEquals("~1.145192757E-19", text(evaluate("R( 9 )", false, stat), NumberFormat.Norm(2)))
    }

    @Test
    fun `STAT is always Linear so statistics and quotients come out decimal`() {
        // Research 03 §2.3 (VERIFIED, [G] p.12): STAT switches to Linear Display whatever SETUP
        // says, so the default MthIO-MathO must not leave fractions on its screen.
        val halves = GuideStatistics(mapOf(StatVariable.MEAN_X to FractionReal(7, 2)))
        val stat = context(Mode.STAT, Setup(), statistics = halves)
        assertEquals("~3.5", text(evaluate("x̄", false, stat), NumberFormat.Norm(2)))
        assertEquals("~0.3333333333", text(evaluate("1 ÷ 3", false, stat), NumberFormat.Norm(2)))
    }

    @Test
    fun `STAT items without data or outside STAT`() {
        assertEquals("MATH", text(evaluate("x̄", false, context(Mode.STAT, line())), NumberFormat.Norm(2)))
        val withData = context(Mode.STAT, line(), statistics = ONE_VAR)
        assertEquals("MATH", text(evaluate("3 x̂", false, withData), NumberFormat.Norm(2)))
        val comp = context(Mode.COMP, line(), statistics = ONE_VAR)
        assertEquals("SYNTAX", text(evaluate("x̄", false, comp), NumberFormat.Norm(2)))
        assertEquals("SYNTAX", text(evaluate("P( 1 )", false, context(Mode.COMP, line())), NumberFormat.Norm(2)))
    }
}
