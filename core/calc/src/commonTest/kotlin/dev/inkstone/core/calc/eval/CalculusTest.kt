package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.AngleUnit
import dev.inkstone.core.calc.InputOutput
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.NumberFormat
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.FractionReal
import kotlin.test.Test
import kotlin.test.assertEquals

private val RAD = Setup(angle = AngleUnit.RAD)
private val RAD_LINE = Setup(io = InputOutput.LINE_LINE, angle = AngleUnit.RAD)

/** Research 04 E-24 … E-32: every ∫, d/dx and Σ example in the guide. */
private val GUIDE_CALCULUS = listOf(
    Case("E-24", "~1"),
    Case("E-25", "~1"),
    Case("E-26", "~0.8"),
    // ∫₀^π (sin x + cos x)² dx shows as π (the formatter's recognition); here: the value and the flag.
    Case("E-27", "~3.141592654", setup = RAD, pi = true),
    Case("E-28", "~0", setup = RAD, pi = true),
    Case("E-29", "~0", setup = RAD_LINE, pi = true),
    Case("E-30", "~7"),
    Case("E-31", "20"),
    Case("E-32", "20"),
)

/** The rules around the guide's examples (research 02 §10.7–10.9, 07 §4.6–4.7). */
private val CALCULUS_RULES = listOf(
    // ∫ with a = b still samples f, then answers 0 (07 §4.6, INFERRED).
    Case("a equals b", "~0", keys = "∫ X x² ▶ 2 ▶ 2"),
    Case("a equals b samples f", "MATH", keys = "∫ ln X ) ▶ 0 ▶ 0"),
    Case("reversed limits", "~-0.5", keys = "∫ X ▶ 1 ▶ 0"),
    Case("smooth polynomial", "~0.25", keys = "∫ X x³ ▶ 0 ▶ 1"),
    Case("kink at the first split", "~1", keys = "∫ Abs X ▶ ▶ (−) 1 ▶ 1"),
    Case("negative tol", "ARGUMENT", keys = "∫ X ; 0 ; 1 ; (−) 1 )", natural = false),
    Case("negative d/dx tol", "ARGUMENT", keys = "d/dx X x² ; 1 ; (−) 1 )", natural = false),
    Case("d/dx of a curve", "~6", keys = "d/dx X x³ ▶ ( √ 2 ▶ )"),
    Case("d/dx in Deg is per degree", "~0.0151149947", keys = "d/dx sin X ) ▶ 3 0"),
    Case("d/dx of a domain edge", "MATH", keys = "d/dx √ X ▶ ▶ 0"),
    Case("d/dx that cannot converge", "TIME_OUT", keys = "d/dx 1 ÷ X ▶ 0"),
    Case("d/dx with a tol it cannot meet", "TIME_OUT", keys = "d/dx 1 ÷ X ; 0 ; 1 ×10 (−) 5 )", natural = false),
    Case("integral that cannot converge", "TIME_OUT", keys = "∫ sin 1 ÷ X ) ▶ 0 ▶ 1", setup = RAD),
    Case("sum of fractions stays exact", "137/60", keys = "Σ 1 ÷ X ▶ 1 ▶ 5"),
    Case("sum bound not an integer", "MATH", keys = "Σ X ▶ 1 , 5 ▶ 5"),
    Case("sum bounds reversed", "MATH", keys = "Σ X ▶ 5 ▶ 1"),
    Case("sum bound out of range", "MATH", keys = "Σ X ▶ 1 ▶ 1 ×10 1 0"),
    // Nesting and banned functions (02 §10.7–10.9): Syntax ERROR, a GUESS.
    Case("integral in an integral", "SYNTAX", keys = "∫ ∫ X ▶ 0 ▶ 1 ▶ ▶ 0 ▶ 1"),
    Case("sum in a limit", "SYNTAX", keys = "∫ X ▶ Σ X ▶ 1 ▶ 2 ▶ ▶ 3"),
    Case("Pol in f", "SYNTAX", keys = "∫ Pol X ; 1 ) ▶ 0 ▶ 1"),
    Case("remainder division in f", "SYNTAX", keys = "Σ X ÷R 2 ▶ 1 ▶ 3"),
    Case("not in CMPLX", "SYNTAX", keys = "Σ X ▶ 1 ▶ 3", mode = Mode.CMPLX),
)

class CalculusTest {
    @Test
    fun `the guide calculus examples give the printed values`() = verify(GUIDE_CALCULUS)

    @Test
    fun `calculus rules beyond the guide examples`() = verify(CALCULUS_RULES)

    @Test
    fun `the Kronrod nodes are the digits the ROM stores`() {
        // Research 07 §4.6 lists the ROM's 15-digit nodes. Six are the published qk15 values
        // rounded; the innermost is stored as ...899 where rounding 0,207784955007898468 gives
        // ...898, so it cannot be produced by rounding and has to be written as the ROM has it.
        val rom = listOf(
            "0.991455371120813",
            "0.949107912342759",
            "0.864864423359769",
            "0.741531185599394",
            "0.586087235467691",
            "0.405845151377397",
            "0.207784955007899",
        )
        assertEquals(rom, Quadrature.ABSCISSAE.map { it.toCanonicalString() })
    }

    @Test
    fun `X is put back after each calculus construct`() {
        // Research 07 §4.6 and test T14: set X = 7, ∫₀¹ X dx, recall X.
        for (keys in listOf("∫ X ▶ 0 ▶ 1", "d/dx X x² ▶ 3", "Σ X ▶ 1 ▶ 4", "∫ ln X ) ▶ (−) 1 ▶ 1")) {
            val memory = Memory().apply { variables[Variable.X] = FractionReal(7, 1) }
            evaluate(keys, true, context(memory = memory))
            assertEquals("7", text(memory.variables.getValue(Variable.X)), keys)
        }
    }

    @Test
    fun `the limits are read with X as it was`() {
        val memory = Memory().apply { variables[Variable.X] = FractionReal(2, 1) }
        assertEquals("~2", text(evaluate("∫ X ▶ 0 ▶ X", true, context(memory = memory)), NumberFormat.Norm(2)))
    }

    @Test
    fun `AC stops a long calculation and X is still put back`() {
        for (keys in listOf("∫ X ▶ 0 ▶ 1", "d/dx X ▶ 1", "Σ X ▶ 1 ▶ 9 ×10 9")) {
            val memory = Memory().apply { variables[Variable.X] = FractionReal(5, 1) }
            val outcome = evaluate(keys, true, context(memory = memory, interrupt = { true }))
            assertEquals(EvalOutcome.Interrupted, outcome, keys)
            assertEquals("5", text(memory.variables.getValue(Variable.X)), keys)
        }
    }

    @Test
    fun `an AC pressed part way through stops a sum`() {
        var polls = 0
        val outcome = evaluate("Σ X ▶ 1 ▶ 9 ×10 9", true, context(interrupt = { ++polls > STOP_AFTER }))
        assertEquals(EvalOutcome.Interrupted, outcome)
    }

    private companion object {
        const val STOP_AFTER = 1000
    }
}
