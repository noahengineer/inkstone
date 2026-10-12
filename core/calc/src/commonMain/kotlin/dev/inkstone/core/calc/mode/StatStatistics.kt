package dev.inkstone.core.calc.mode

import dev.inkstone.core.calc.eval.StatisticsSource
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.parse.PostfixOp
import dev.inkstone.core.calc.parse.StatVariable
import dev.inkstone.core.calc.value.DecimalReal
import dev.inkstone.core.calc.value.Real

/**
 * STAT's answers to the calculation line (research 03 §4.2–4.5): every variable of the `SHIFT 1`
 * sub-menus, the regression estimates and `▶t`, computed from the editor's rows as they stand when
 * the line is evaluated. Null is Math ERROR ([StatisticsSource]'s contract); every value is a
 * decimal, as STAT's always-Linear display shows statistics (`eval.Scalars` turns it into one
 * anyway).
 *
 * The formulas are research 03 §4.5's — the ones that reproduce every printed STAT result:
 * x̄ = Σfx/n, σx = √(Sxx/n), sx = √(Sxx/(n − 1)) with Sxx = Σf(x − x̄)² and n = ΣFREQ; the
 * regressions are [StatRegression]'s. Each is worked in the calculator's 15-digit steps, the
 * spreads from the deviations (two passes) rather than from Σx² − (Σx)²/n — ⚠️ GUESS: the firmware's
 * order of operations is unknown, and the choice shows only where Σx² − (Σx)²/n would cancel away
 * the digits held (a large mean with a small spread), which none of the guide's data has.
 *
 * Frequencies (⚠️ GUESSES throughout; the guide says only that FREQ weights the data):
 * - a row whose FREQ is 0 does not occur — it adds nothing, and is no candidate for minX or a
 *   quartile;
 * - a negative FREQ makes every statistic a Math ERROR;
 * - a fractional FREQ weights the sums as it is, but the median and quartiles, which count data,
 *   need whole frequencies (Math ERROR otherwise).
 *
 * With no data, n and the Σ sums are 0 — empty sums — and everything that divides by n, or needs a
 * value to exist (minX, med), is Math ERROR (⚠️ GUESS).
 *
 * Questions that belong to the other kind of type — ȳ in 1-VAR, Q1 in A+BX, C outside `_+CX²` —
 * cannot be reached through the menus (research 03 §4.2), so they answer Math ERROR rather than a
 * number nobody could ask for.
 */
internal class StatStatistics(private val type: StatType, rows: List<StatPoint>) : StatisticsSource {
    private val usable = rows.none { it.f.isNegative }
    private val points = rows.filterNot { it.f.isZero }
    private val fit: StatFit? by lazy { StatRegression.fit(type, points) }

    override fun value(variable: StatVariable): Real? {
        if (!usable || !belongs(variable)) return null
        return READERS[variable]?.invoke(this)?.let(::DecimalReal)
    }

    override fun estimate(op: PostfixOp, operand: Real): Real? =
        if (usable) fit?.estimate(op, operand.dec)?.let(::DecimalReal) else null

    /** `X▶t` = (X − x̄)/σx, with the **population** σx (research 03 §4.4, VERIFIED by EX-STAT-05). */
    override fun normalise(operand: Real): Real? = if (!usable) {
        null
    } else {
        StatWork.checked {
            val n = count(points)
            div(sub(operand.dec, mean(points) { it.x }), sqrt(div(spread(points) { it.x }, n)))
        }?.let(::DecimalReal)
    }

    /** Whether [variable] is a question this type's menus can ask (see the class KDoc). */
    private fun belongs(variable: StatVariable): Boolean = when (variable) {
        in PAIRED_ONLY -> type.paired
        in SINGLE_ONLY -> !type.paired
        else -> true
    }

    private fun sum(term: StatWork.(StatPoint) -> Dec): Dec? = StatWork.checked { total(points) { term(it) } }

    private fun average(g: (StatPoint) -> Dec): Dec? = StatWork.checked { mean(points, g) }

    /**
     * σ (population) or s (sample): √(S/n) or √(S/(n − 1)). One value has σ = 0 and no s — the
     * division by n − 1 = 0 is the Math ERROR.
     */
    private fun deviation(sample: Boolean, g: (StatPoint) -> Dec): Dec? = StatWork.checked {
        val n = count(points)
        sqrt(div(spread(points, g), if (sample) sub(n, Dec.ONE) else n))
    }

    private fun extreme(largest: Boolean, g: (StatPoint) -> Dec): Dec? {
        val values = points.map(g)
        return if (largest) values.maxOrNull() else values.minOrNull()
    }

    private companion object {
        val PAIRED_ONLY = setOf(
            StatVariable.MEAN_Y, StatVariable.SIGMA_Y, StatVariable.SAMPLE_SY, StatVariable.SUM_Y,
            StatVariable.SUM_Y2, StatVariable.SUM_XY, StatVariable.SUM_X2Y, StatVariable.MIN_Y,
            StatVariable.MAX_Y, StatVariable.REG_A, StatVariable.REG_B, StatVariable.REG_C, StatVariable.REG_R,
        )

        /** "Paired types have no quartiles or median" (research 03 §4.2, VERIFIED from the MinMax list). */
        val SINGLE_ONLY = setOf(StatVariable.Q1, StatVariable.MEDIAN, StatVariable.Q3)

        val READERS: Map<StatVariable, StatStatistics.() -> Dec?> = mapOf(
            StatVariable.N to { sum { Dec.ONE } },
            StatVariable.SUM_X to { sum { it.x } },
            StatVariable.SUM_X2 to { sum { square(it.x) } },
            StatVariable.SUM_X3 to { sum { mul(square(it.x), it.x) } },
            StatVariable.SUM_X4 to { sum { square(square(it.x)) } },
            StatVariable.SUM_Y to { sum { it.y } },
            StatVariable.SUM_Y2 to { sum { square(it.y) } },
            StatVariable.SUM_XY to { sum { mul(it.x, it.y) } },
            StatVariable.SUM_X2Y to { sum { mul(square(it.x), it.y) } },
            StatVariable.MEAN_X to { average { it.x } },
            StatVariable.MEAN_Y to { average { it.y } },
            StatVariable.SIGMA_X to { deviation(sample = false) { it.x } },
            StatVariable.SAMPLE_SX to { deviation(sample = true) { it.x } },
            StatVariable.SIGMA_Y to { deviation(sample = false) { it.y } },
            StatVariable.SAMPLE_SY to { deviation(sample = true) { it.y } },
            StatVariable.MIN_X to { extreme(largest = false) { it.x } },
            StatVariable.MAX_X to { extreme(largest = true) { it.x } },
            StatVariable.MIN_Y to { extreme(largest = false) { it.y } },
            StatVariable.MAX_Y to { extreme(largest = true) { it.y } },
            StatVariable.Q1 to { StatQuartiles.of(points, StatQuartiles.Part.Q1) },
            StatVariable.MEDIAN to { StatQuartiles.of(points, StatQuartiles.Part.MEDIAN) },
            StatVariable.Q3 to { StatQuartiles.of(points, StatQuartiles.Part.Q3) },
            StatVariable.REG_A to { fit?.a },
            StatVariable.REG_B to { fit?.b },
            StatVariable.REG_C to { fit?.c },
            StatVariable.REG_R to { fit?.r },
        )
    }
}

/**
 * Q1, med and Q3 of 1-VAR's MinMax (research 03 §4.2), over the data sorted and expanded by
 * frequency. med is the middle value, or the mean of the middle two. Q1 is the median of the lower
 * half and Q3 of the upper half, where for an **even** n the halves are the first and last n/2 —
 * which is what gives the SA capture's `Q1 = 2198,5` for twelve values (research 05 EX-SA-STAT-01,
 * VERIFIED) — and for an **odd** n they are the (n − 1)/2 values each side of the median, the
 * median itself left out. That odd-n rule is ⚠️ GUESS (research 03 §4.5 and 06 §6.4: UNKNOWN): of
 * the two common textbook rules it is the one that excludes the median, so `1…7` gives Q1 = 2 here
 * and would give 2,5 under the other — research 06 §13 #9's emulator check. One value is its own
 * Q1 and Q3 (⚠️ GUESS: its halves are empty).
 */
internal object StatQuartiles {
    enum class Part { Q1, MEDIAN, Q3 }

    fun of(points: List<StatPoint>, part: Part): Dec? {
        if (points.isEmpty() || points.any { !it.f.isInteger }) return null
        val sorted = points.sortedBy { it.x }
        val n = sorted.fold(Dec.ZERO) { total, p -> total + p.f }
        val half = (n / Dec.TWO).floor()
        return when {
            part == Part.MEDIAN || half.isZero -> median(sorted, Dec.ONE, n)
            part == Part.Q1 -> median(sorted, Dec.ONE, half)
            else -> median(sorted, n - half + Dec.ONE, half)
        }
    }

    /** The median of the [count] values from 1-based position [first] on. */
    private fun median(sorted: List<StatPoint>, first: Dec, count: Dec): Dec {
        val middle = first + ((count - Dec.ONE) / Dec.TWO).floor()
        val odd = !(count / Dec.TWO).isInteger
        return if (odd) at(sorted, middle) else (at(sorted, middle) + at(sorted, middle + Dec.ONE)) / Dec.TWO
    }

    /** The value at 1-based [position] of the data expanded by frequency. */
    private fun at(sorted: List<StatPoint>, position: Dec): Dec {
        var reached = Dec.ZERO
        for (p in sorted) {
            reached += p.f
            if (reached >= position) return p.x
        }
        return sorted.last().x
    }
}
