package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.eval.Answer
import dev.inkstone.core.calc.eval.EvalContext
import dev.inkstone.core.calc.eval.EvalOutcome
import dev.inkstone.core.calc.eval.Memory
import dev.inkstone.core.calc.input.Keys
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.parse.BinOp
import dev.inkstone.core.calc.parse.Binary
import dev.inkstone.core.calc.parse.Call
import dev.inkstone.core.calc.parse.Fn
import dev.inkstone.core.calc.parse.Fraction
import dev.inkstone.core.calc.parse.Integral
import dev.inkstone.core.calc.parse.Negate
import dev.inkstone.core.calc.parse.Node
import dev.inkstone.core.calc.parse.NumberLit
import dev.inkstone.core.calc.parse.ParseResult
import dev.inkstone.core.calc.parse.SourceMap
import dev.inkstone.core.calc.parse.parse
import dev.inkstone.core.calc.value.DecimalReal
import dev.inkstone.core.calc.value.FractionReal
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.SurdReal
import dev.inkstone.core.calc.value.SurdTerm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/** [Literals] (a held value back as exact nodes) and [NodeTree] (the machine's walk over a parse). */
class LiteralsTest {
    private fun dec(text: String): Dec = checkNotNull(Dec.parse(text))

    private fun lit(digits: String) = NumberLit(digits)

    @Test
    fun fractionsAndIntegersAreWrittenAsTheirIntegers() {
        assertEquals(Fraction(lit("3"), lit("4")), Literals.of(FractionReal(3, 4)))
        assertEquals(Negate(lit("5")), Literals.of(FractionReal(-5, 1)))
        assertEquals(Negate(Fraction(lit("1"), lit("3"))), Literals.of(FractionReal(-1, 3)))
    }

    /** A decimal is the fraction of integers it is, so the evaluator can keep it exact (EX-SA-RATIO-01). */
    @Test
    fun decimalsAreWrittenAsTheFractionsTheyAre() {
        val cases: List<Pair<String, Node>> = listOf(
            "1.5" to Fraction(lit("15"), lit("10")),
            "-0.125" to Negate(Fraction(lit("125"), lit("1000"))),
            "1200" to lit("1200"),
            "0" to lit("0"),
            "123456789012345" to lit("123456789012345"),
            "1.5E50" to NumberLit("1", "5", "50"),
            "2.5E-30" to NumberLit("2", "5", "-30"),
            "7E-20" to NumberLit("7", null, "-20"),
        )
        for ((text, node) in cases) assertEquals(node, Literals.of(DecimalReal(dec(text))), text)
    }

    @Test
    fun surdsAreWrittenTermByTerm() {
        val surd = SurdReal(listOf(SurdTerm(1, 2, 3), SurdTerm(-1, 1, 1)), dec("-0.133974596215561"))
        val half = Fraction(Binary(BinOp.MUL, lit("1"), Call(Fn.SQRT, listOf(lit("3")))), lit("2"))
        assertEquals(Binary(BinOp.ADD, half, Negate(lit("1"))), Literals.of(surd))
    }

    /** What the evaluator gives back is the value itself — exactly, for a decimal that fits a fraction. */
    @Test
    fun theEvaluatorReadsTheValueBack() {
        val cases: List<Pair<Real, Real>> = listOf(
            FractionReal(-7, 9) to FractionReal(-7, 9),
            DecimalReal(dec("1.5")) to FractionReal(3, 2),
            DecimalReal(dec("-12.5")) to FractionReal(-25, 2),
        )
        val context = EvalContext(Mode.COMP, Setup(), Memory())
        for ((held, back) in cases) {
            val outcome = FakeEvaluator().evaluate(Literals.of(held), SourceMap(), context)
            assertEquals(back, assertIs<Answer.Plain>(assertIs<EvalOutcome.Done>(outcome).answer).value, "$held")
        }
    }

    @Test
    fun theTreeIsSearchedDepthFirstIncludingTheCalculusTemplates() {
        val parsed = assertIs<ParseResult.Ok>(parse(Keys.type("1 + ∫ X ▶ 0 ▶ 1 ▶ × 2").expr))
        val integral = assertIs<Integral>(NodeTree.find(parsed.node) { it is Integral })
        assertSame(integral, NodeTree.find(parsed.node) { it === integral })
        assertEquals(lit("2"), NodeTree.find(parsed.node) { it == lit("2") })
        assertNull(NodeTree.find(parsed.node) { it == lit("3") })
    }
}
