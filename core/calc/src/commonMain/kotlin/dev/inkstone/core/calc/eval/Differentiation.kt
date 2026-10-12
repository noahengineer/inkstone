package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.parse.Derivative

/**
 * `d/dx` by **central differences with Richardson extrapolation** (research 02 §10.8 "central
 * difference", docs/CALCULATOR.md §3), in the calculator's 15-digit arithmetic.
 *
 * Row j of the table is the central difference with step `h₀/2ʲ`, divided by the distance between
 * the two points actually used (so a rounded `a ± h` costs nothing); each row is extrapolated
 * against the one before it, cancelling the h², h⁴ … error terms. The answer is the diagonal once
 * two successive diagonal entries agree within tol, absolutely or relative to the slope.
 *
 * - `h₀` is 1×10⁻³ times the power of ten of `max(1, |a|)` — a **GUESS** (the ROM has constants
 *   with exponents −10, −7, −3 and −2 whose roles are unknown, research 07 §4.7). A power of ten
 *   keeps `a ± h` exact for a typed `a`, which is what makes `d/dx sin x` at `π/2` exactly `0`
 *   (research 04 E-28): both samples round to the same 15-digit value.
 * - At most [MAX_ROWS] rows. If tol was typed and is not met, **Time Out** (research 02 §11). If it
 *   was omitted, "tol is adjusted automatically" (02 §10.8): the best-agreeing estimate is
 *   accepted if it agrees within [RELAXED_TOL] — a **GUESS** at how far the adjustment goes.
 */
internal class Differentiation(private val session: Session, private val node: Derivative) {
    /** The closest two successive estimates came: the fallback when tol cannot be met. */
    private class Best(val estimate: Dec, val gap: Dec)

    fun at(a: Dec, tol: Dec?): Ev<Dec> {
        val target = tol ?: DEFAULT_TOL
        var step = initialStep(a)
        var previous: List<Dec> = emptyList()
        var best: Best? = null
        for (j in 0 until MAX_ROWS) {
            val row = extrapolate(difference(a, step).orElse { return it }, previous)
            if (j > 0) {
                val candidate = Best(row.last(), (row.last() - previous.last()).abs())
                if (candidate.gap <= target * scale(candidate.estimate)) return Ev.Ok(candidate.estimate)
                best = closer(best, candidate)
            }
            previous = row
            step /= Dec.TWO
        }
        return settleLoosely(tol, best)
    }

    private fun closer(best: Best?, candidate: Best): Best =
        if (best == null || candidate.gap < best.gap) candidate else best

    private fun settleLoosely(tol: Dec?, best: Best?): Ev<Dec> =
        if (tol == null && best != null && best.gap <= RELAXED_TOL * scale(best.estimate)) {
            Ev.Ok(best.estimate)
        } else {
            failAt(CalcError.TIME_OUT, node)
        }

    /** `(f(a+h) − f(a−h)) / ((a+h) − (a−h))`. */
    private fun difference(a: Dec, h: Dec): Ev<Dec> {
        val right = a + h
        val left = a - h
        val fRight = session.sample(node.f, right).orElse { return it }
        val fLeft = session.sample(node.f, left).orElse { return it }
        val width = right - left
        if (width.isZero) return failAt(CalcError.TIME_OUT, node)
        return Ev.Ok((fRight - fLeft) / width)
    }

    /** One Richardson row: `T[j][k] = T[j][k−1] + (T[j][k−1] − T[j−1][k−1]) / (4ᵏ − 1)`. */
    private fun extrapolate(first: Dec, above: List<Dec>): List<Dec> {
        val row = mutableListOf(first)
        for (k in above.indices) {
            val current = row[k]
            row += current + (current - above[k]) / FOUR_POWERS_LESS_ONE[k]
        }
        return row
    }

    private fun initialStep(a: Dec): Dec = Dec.ONE.scaleByPowerOfTen(maxOf(a.abs(), Dec.ONE).exponent + STEP_EXPONENT)

    private fun scale(x: Dec): Dec = maxOf(Dec.ONE, x.abs())

    companion object {
        /** tol when none is typed (research 02 §10.8: 1×10⁻¹⁰). */
        val DEFAULT_TOL: Dec = Dec.ONE.scaleByPowerOfTen(-10)

        private val RELAXED_TOL: Dec = Dec.ONE.scaleByPowerOfTen(-5)

        const val MAX_ROWS = 10

        private const val STEP_EXPONENT = -3

        /** 4ᵏ − 1 for k = 1, 2, …: 3, 15, 63, … */
        private val FOUR_POWERS_LESS_ONE: List<Dec> = (1..MAX_ROWS).map { k ->
            var p = 1L
            repeat(k) { p *= 4L }
            Dec.of(p - 1L)
        }
    }
}
