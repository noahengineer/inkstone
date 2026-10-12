package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.parse.Equation
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.DecimalReal

/**
 * SOLVE (research 02 §10.15, 07 §4.7, docs/CALCULATOR.md §3): **Newton's method** on
 * `g(v) = L − R` (R = 0 for an expression with no `=`), from the variable's value, with the slope
 * taken numerically as a central difference. The iteration count and convergence test are UNKNOWN
 * (research 07 T15); these are the **GUESSES**:
 * - at most [MAX_ITERATIONS] steps per run, then `Continue:[=]` ([SolveOutcome.Unfinished]); `=`
 *   runs again from where it stopped;
 * - converged when `g` is exactly 0, or a step moves the value by no more than 1×10⁻¹³ of itself
 *   (two 15-digit neighbours apart at most);
 * - a step landing where the equation cannot be evaluated (`√` of a negative, `÷0`) is pulled
 *   halfway back, up to [BACKTRACKS] times; a zero slope moves the value by 1×10⁻³ of itself to get
 *   off a flat spot, up to [MAX_NUDGES] times; a start that cannot be evaluated, or a value pushed
 *   past ±1×10¹⁰⁰, is **Can't Solve** (research 02 §11);
 * - `L−R` is reported as 0 when it is below 1×10⁻¹³ of the larger of 1, |L| and |R| — the noise of
 *   the last digit. That is what the guide's `X=1,414213562`, `L−R=0` needs (research 04 E-49):
 *   no 15-digit X has `X²` exactly 2, so the computed residual is 1×10⁻¹⁴, which the real
 *   calculator evidently does not show.
 *
 * The answer is always a decimal (research 02 §10.15). It is stored in the variable when the run
 * solves or stops for `Continue`; on any failure the variable is put back as it was.
 */
internal class Solver(private val session: Session, private val equation: Equation, private val variable: Variable) {
    private class Sides(val left: Dec, val right: Dec) {
        val difference: Dec get() = left - right
    }

    /** Where the iteration stands: a value, both sides there, and whether the last step settled. */
    private class Point(val x: Dec, val sides: Sides, val settled: Boolean = false) {
        val solved: Boolean get() = settled || sides.difference.isZero
    }

    private var nudges = 0

    fun run(start: Dec): Ev<SolveOutcome> {
        val saved = session.memory.variables[variable] ?: Memory.ZERO
        val outcome = iterate(start)
        session.memory.variables[variable] = when (val result = (outcome as? Ev.Ok)?.value) {
            is SolveOutcome.Solved -> result.value
            is SolveOutcome.Unfinished -> result.current
            else -> saved
        }
        return outcome
    }

    /** Up to [MAX_ITERATIONS] steps; [sides] polls AC at every evaluation of the equation. */
    private fun iterate(start: Dec): Ev<SolveOutcome> {
        var point = Point(start, sides(start).orElse { return giveUp(it) })
        repeat(MAX_ITERATIONS) {
            if (point.solved) return solved(point)
            point = advance(point).orElse { return giveUp(it) }
        }
        return if (point.solved) solved(point) else Ev.Ok(SolveOutcome.Unfinished(variable, DecimalReal(point.x)))
    }

    private fun advance(point: Point): Ev<Point> {
        val next = newtonStep(point.x, point.sides.difference).orElse { return it }
        val (landed, sides) = evaluableNear(point.x, next).orElse { return it }
        return Ev.Ok(Point(landed, sides, settled = (landed - point.x).abs() <= STEP_TOL * landed.abs()))
    }

    private fun solved(point: Point): Ev<SolveOutcome> {
        val sides = point.sides
        val scale = maxOf(Dec.ONE, maxOf(sides.left.abs(), sides.right.abs()))
        val residual = sides.difference.takeUnless { it.abs() < NOISE * scale } ?: Dec.ZERO
        return Ev.Ok(SolveOutcome.Solved(variable, DecimalReal(point.x), DecimalReal(residual)))
    }

    /** A failure inside the iteration is the solver's failure, except AC, which stays AC. */
    private fun giveUp(why: Ev<Nothing>): Ev<SolveOutcome> =
        if (why is Ev.Stop) Ev.Stop else failAt(CalcError.CANT_SOLVE, equation)

    /** `x − g/g'`, or a nudge off a flat spot. */
    private fun newtonStep(x: Dec, g: Dec): Ev<Dec> {
        val slope = slope(x, g).orElse { return it }
        if (slope.isZero) {
            nudges++
            if (nudges > MAX_NUDGES) return failAt(CalcError.CANT_SOLVE, equation)
            return Ev.Ok(x + scale(x) * NUDGE)
        }
        val next = x - g / slope
        return if (next.isOverflow) failAt(CalcError.CANT_SOLVE, equation) else Ev.Ok(next)
    }

    /**
     * `g'(x)` as a central difference, or a one-sided one when the equation cannot be evaluated
     * on one side (`√X` near 0).
     */
    private fun slope(x: Dec, g: Dec): Ev<Dec> {
        val h = scale(x) * SLOPE_STEP
        val above = difference(x + h)
        val below = difference(x - h)
        return when {
            above != null && below != null -> Ev.Ok((above - below) / ((x + h) - (x - h)))
            above != null -> Ev.Ok((above - g) / ((x + h) - x))
            below != null -> Ev.Ok((g - below) / (x - (x - h)))
            session.interrupted() -> Ev.Stop
            else -> failAt(CalcError.CANT_SOLVE, equation)
        }
    }

    private fun difference(x: Dec): Dec? = (sides(x) as? Ev.Ok)?.value?.difference

    /** [next], or a point pulled back toward [x] until the equation can be evaluated there. */
    private fun evaluableNear(x: Dec, next: Dec): Ev<Pair<Dec, Sides>> {
        var candidate = next
        repeat(BACKTRACKS) {
            when (val s = sides(candidate)) {
                is Ev.Ok -> return Ev.Ok(candidate to s.value)
                Ev.Stop -> return Ev.Stop
                is Ev.Fail -> candidate = (x + candidate) / Dec.TWO
            }
        }
        return failAt(CalcError.CANT_SOLVE, equation)
    }

    private fun sides(x: Dec): Ev<Sides> {
        if (session.interrupted()) return Ev.Stop
        session.memory.variables[variable] = DecimalReal(x)
        val left = session.real(equation.lhs).orElse { return it }.dec
        val right = equation.rhs?.let { session.real(it).orElse { e -> return e }.dec } ?: Dec.ZERO
        return Ev.Ok(Sides(left, right))
    }

    private fun scale(x: Dec): Dec = maxOf(Dec.ONE, x.abs())

    companion object {
        const val MAX_ITERATIONS = 50
        private const val BACKTRACKS = 10
        private const val MAX_NUDGES = 10
        private val STEP_TOL: Dec = Dec.ONE.scaleByPowerOfTen(-13)
        private val NOISE: Dec = Dec.ONE.scaleByPowerOfTen(-13)
        private val SLOPE_STEP: Dec = Dec.ONE.scaleByPowerOfTen(-6)
        private val NUDGE: Dec = Dec.ONE.scaleByPowerOfTen(-3)
    }
}
