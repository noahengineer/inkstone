package dev.inkstone.core.calc.mode

import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.num.DecMath

/** One data row as the statistics read it: [x], [y] (unused in 1-VAR) and its frequency [f]. */
internal data class StatPoint(val x: Dec, val y: Dec, val f: Dec)

/**
 * The arithmetic of one statistic, in the calculator's own 15-digit [Dec] steps (docs/CALCULATOR.md
 * §3: every operation rounds to 15 significant digits). Any step can leave the calculation range
 * (|x| ≥ 1×10¹⁰⁰), divide by zero, or take a root or logarithm outside its domain — each is the
 * calculator's Math ERROR at that step. Rather than test every step in place, a [StatWork] remembers
 * that one failed and [result] then answers null, which is Math ERROR under the
 * [dev.inkstone.core.calc.eval.StatisticsSource] contract.
 *
 * Why not let an overflowed [Dec] run on: it is kept and flagged, not clamped, and most arithmetic
 * keeps the flag — but two overflowed values divide to a finite 1 and subtract to 0, which would
 * turn a regression of huge data into a plausible-looking wrong answer instead of an error.
 *
 * One [StatWork] per statistic: a failure in one (A of a regression whose data defeat it) must not
 * leak into another (n of the same data).
 */
internal class StatWork {
    var failed: Boolean = false
        private set

    fun add(a: Dec, b: Dec): Dec = keep(a + b)

    fun sub(a: Dec, b: Dec): Dec = keep(a - b)

    fun mul(a: Dec, b: Dec): Dec = keep(a * b)

    fun div(a: Dec, b: Dec): Dec = if (b.isZero) fail() else keep(a / b)

    fun square(a: Dec): Dec = mul(a, a)

    fun sqrt(a: Dec): Dec = DecMath.sqrt(a) ?: fail()

    fun exp(a: Dec): Dec = DecMath.exp(a) ?: fail()

    fun ln(a: Dec): Dec = DecMath.ln(a) ?: fail()

    fun pow(a: Dec, b: Dec): Dec = DecMath.pow(a, b) ?: fail()

    /** Σ f·[term] over [points] — every sum the statistics take is weighted by FREQ (research 03 §4.5). */
    fun total(points: List<StatPoint>, term: (StatPoint) -> Dec): Dec =
        points.fold(Dec.ZERO) { sum, p -> add(sum, mul(term(p), p.f)) }

    /** n = ΣFREQ (research 03 §4.5). */
    fun count(points: List<StatPoint>): Dec = total(points) { Dec.ONE }

    /** Σf·g / n. */
    fun mean(points: List<StatPoint>, g: (StatPoint) -> Dec): Dec = div(total(points, g), count(points))

    /** Σf·(g − ḡ)²: the spread every deviation and regression is built from (research 03 §4.5). */
    fun spread(points: List<StatPoint>, g: (StatPoint) -> Dec): Dec {
        val centre = mean(points, g)
        return total(points) { square(sub(g(it), centre)) }
    }

    /** A condition the statistic needs (enough distinct data): when it does not hold, Math ERROR. */
    fun need(holds: Boolean) {
        if (!holds) failed = true
    }

    /** [value], or null when any step on the way failed. */
    fun <T> result(value: T): T? = if (failed) null else value

    private fun keep(d: Dec): Dec {
        if (d.isOverflow) failed = true
        return d
    }

    private fun fail(): Dec {
        failed = true
        return Dec.ZERO
    }

    companion object {
        /** Runs [block] in a fresh [StatWork] and answers its value, or null if any step failed. */
        fun <T> checked(block: StatWork.() -> T): T? = StatWork().let { it.result(it.block()) }
    }
}
