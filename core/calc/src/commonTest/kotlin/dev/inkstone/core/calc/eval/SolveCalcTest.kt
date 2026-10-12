package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.AngleUnit
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.NumberFormat
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.ComplexMath
import dev.inkstone.core.calc.value.FractionReal
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.Roots
import dev.inkstone.core.calc.value.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun int(n: Long): Real = FractionReal(n, 1)

private fun root(n: Long): Real = assertNotNull(Roots.sqrt(int(n)).getOrNull())

/** SOLVE [keys] for the variable the machine would pick, from [start]. */
private fun solve(
    keys: String,
    start: Real,
    memory: Memory = Memory(),
    mode: Mode = Mode.COMP,
    interrupt: () -> Boolean = { false },
): SolveOutcome {
    val ok = parsed(keys, natural = true, solve = true)
    val equation = assertNotNull(Evaluation.equationOf(ok.node), keys)
    val variable = Evaluation.solveTarget(equation) ?: Variable.X
    val context = context(mode = mode, memory = memory, interrupt = interrupt)
    return Evaluation.solve(equation, ok.spans, context, variable, start)
}

private fun text(outcome: SolveOutcome): String = when (outcome) {
    is SolveOutcome.Solved -> "${outcome.variable}=${text(outcome.value)};L-R=${text(outcome.leftMinusRight)}"
    is SolveOutcome.Unfinished -> "Continue ${outcome.variable}=${text(outcome.current)}"
    is SolveOutcome.Failed -> outcome.error.name
    SolveOutcome.Interrupted -> "INTERRUPTED"
}

private fun memory(vararg values: Pair<Variable, Real>): Memory =
    Memory().apply { values.forEach { (v, x) -> variables[v] = x } }

class SolveCalcTest {
    @Test
    fun `SOLVE for X with other variables E-49`() {
        val memory = memory(Variable.Y to int(0), Variable.A to int(1), Variable.B to int(-2))
        val outcome = solve(GUIDE.getValue("E-49").keys, int(1), memory)
        // L−R is exactly 1×10⁻¹⁴ in 15 digits; the guide shows 0 (see Solver's KDoc).
        assertEquals("X=~1.414213562;L-R=~0", text(outcome))
        assertEquals("~1.414213562", text(memory.variables.getValue(Variable.X)))
    }

    @Test
    fun `SOLVE repeated for several Y values E-50`() {
        val keys = GUIDE.getValue("E-50").keys
        val memory = memory(Variable.Y to int(3))
        assertEquals("X=~2;L-R=~0", text(solve(keys, int(1), memory)))
        memory.variables[Variable.Y] = int(7)
        assertEquals("X=~3;L-R=~0", text(solve(keys, memory.variables.getValue(Variable.X) as Real, memory)))
        memory.variables[Variable.Y] = int(13)
        assertEquals("X=~4;L-R=~0", text(solve(keys, memory.variables.getValue(Variable.X) as Real, memory)))
    }

    @Test
    fun `SOLVE from the current X H-05 and H-06`() {
        val memory = Memory()
        assertEquals("X=~2;L-R=~0", text(solve(GUIDE.getValue("H-05").keys, int(0), memory)))
        val keys = GUIDE.getValue("H-06").keys
        assertEquals("X=~0;L-R=~0", text(solve(keys, memory.variables.getValue(Variable.X) as Real, memory)))
        assertEquals("X=~6;L-R=~0", text(solve(keys, int(5), memory)))
    }

    @Test
    fun `SOLVE for a named variable`() {
        val memory = memory(Variable.X to int(2))
        assertEquals("Y=~7;L-R=~0", text(solve("Y EQ X + 5 ; Y", int(0), memory)))
        assertEquals("~7", text(memory.variables.getValue(Variable.Y)))
    }

    @Test
    fun `SOLVE errors`() {
        // Variable ERROR: no X and no named variable (research 02 §11).
        assertNull(Evaluation.solveTarget(assertNotNull(Evaluation.equationOf(parsed("A + 1", true).node))))
        assertEquals("VARIABLE", text(solve("A + 1", int(0))))
        // Can't Solve: the start value cannot be evaluated.
        assertEquals("CANT_SOLVE", text(solve("√ X ▶ EQ 2", int(-1))))
        assertEquals("X=~4;L-R=~0", text(solve("√ X ▶ EQ 2", int(1))))
        // ∫, d/dx, Σ, Pol, Rec and ÷R are not allowed (02 §10.15); SOLVE is COMP only.
        assertEquals("SYNTAX", text(solve("∫ X ▶ 0 ▶ X ▶ EQ 2", int(1))))
        assertEquals("SYNTAX", text(solve("X ÷R 2 EQ 1", int(1))))
        assertEquals("SYNTAX", text(solve("X EQ 2", int(1), mode = Mode.CMPLX)))
        // A multi-statement has no equation to solve.
        assertNull(Evaluation.equationOf(parsed("X : X", true).node))
    }

    @Test
    fun `SOLVE pulls a step that leaves the domain back into it`() {
        // From 3, Newton's first step on ln X lands at −0,296, where ln is undefined; the solver
        // halves back towards 3 instead of giving up (Solver's KDoc).
        assertEquals("X=~1;L-R=~0", text(solve("ln X )", int(3))))
    }

    @Test
    fun `a SOLVE that fails or is stopped puts the variable back`() {
        // The start value is written into X before √ refuses it; Can't Solve must not leave it there.
        val failed = memory(Variable.X to int(7))
        assertEquals("CANT_SOLVE", text(solve("√ X ▶ EQ 2", int(-1), failed)))
        assertEquals("7", text(failed.variables.getValue(Variable.X)))
        // AC part way through, after several values have been tried.
        var polls = 0
        val stopped = memory(Variable.X to int(7))
        assertEquals(SolveOutcome.Interrupted, solve("X x² − 2", int(1), stopped, interrupt = { ++polls > STOP_AFTER }))
        assertEquals("7", text(stopped.variables.getValue(Variable.X)))
    }

    @Test
    fun `SOLVE without a root runs out of iterations and can continue T15`() {
        val memory = memory(Variable.X to int(1))
        val first = assertIs<SolveOutcome.Unfinished>(solve("X x² + 1", int(1), memory))
        assertEquals(text(first.current), text(memory.variables.getValue(Variable.X)))
        assertIs<SolveOutcome.Unfinished>(solve("X x² + 1", first.current, memory))
    }

    @Test
    fun `AC stops SOLVE and leaves the variable as it was`() {
        val memory = memory(Variable.X to int(3))
        assertEquals(SolveOutcome.Interrupted, solve("X x² − 2", int(1), memory, interrupt = { true }))
        assertEquals("3", text(memory.variables.getValue(Variable.X)))
    }

    @Test
    fun `CALC prompts in typing order`() {
        fun prompts(keys: String) = Evaluation.variablesOf(parsed(keys, true).node)
        assertEquals(listOf(Variable.A, Variable.B), prompts(GUIDE.getValue("E-47").keys))
        assertEquals(listOf(Variable.A, Variable.B), prompts(GUIDE.getValue("H-04").keys))
        // An assignment computes its left side, so CALC asks only for the right side's variables.
        assertEquals(listOf(Variable.A, Variable.X, Variable.B), prompts(GUIDE.getValue("E-49").keys))
        assertEquals(listOf(Variable.X, Variable.Y), prompts("X + Y : X ( X + Y )"))
        assertEquals(listOf(Variable.B, Variable.C), prompts("A EQ B + C : A × 2"))
        assertEquals(listOf(Variable.A), prompts("A EQ A + 1"))
        assertEquals(listOf(Variable.A), prompts("∫ A X ▶ 0 ▶ 1"))
        // SOLVE asks for every variable but its own, the left side included (04 E-49).
        val equation = assertNotNull(Evaluation.equationOf(parsed(GUIDE.getValue("E-49").keys, true).node))
        assertEquals(listOf(Variable.Y, Variable.A, Variable.B), Evaluation.solvePrompts(equation, Variable.X))
    }

    @Test
    fun `CALC with an assignment stores the result`() {
        val memory = memory(Variable.X to int(2))
        val outcome = evaluate("Y EQ X x² + X + 3", true, context(memory = memory))
        assertEquals("9", text(outcome, NumberFormat.Norm(2)))
        assertEquals("9", text(memory.variables.getValue(Variable.Y)))
        // An equation that is not an assignment is SOLVE's; = on it is Syntax ERROR (a GUESS).
        assertEquals("SYNTAX", text(evaluate(GUIDE.getValue("H-05").keys, true, context()), NumberFormat.Norm(2)))
    }

    @Test
    fun `CALC in CMPLX with a polar command E-48`() {
        val keys = GUIDE.getValue("E-48").keys
        for ((a, b, angle) in listOf(Triple(root(3), int(1), "30"), Triple(int(1), root(3), "60"))) {
            val memory = memory(Variable.A to a, Variable.B to b)
            val outcome = assertIs<EvalOutcome.Done>(evaluate(keys, true, context(mode = Mode.CMPLX, memory = memory)))
            val z = ComplexMath.of(a, b)
            assertEquals(text(z), text(outcome.answer.value))
            val polar = assertNotNull(ComplexMath.toPolar(z, AngleUnit.DEG).getOrNull())
            assertEquals("2", text(polar.r))
            assertEquals(angle, text(polar.theta))
        }
    }

    private companion object {
        /** Evaluations of the equation before AC: past the start and the first slope. */
        const val STOP_AFTER = 5
    }
}
