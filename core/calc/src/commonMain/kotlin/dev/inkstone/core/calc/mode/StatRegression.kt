package dev.inkstone.core.calc.mode

import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.parse.PostfixOp

/**
 * One regression's coefficients — `Reg`'s A, B, C and r (research 03 §4.2–4.3) — and its estimates,
 * the postfix `x̂`, `x̂₁`, `x̂₂` and `ŷ` (research 03 §4.4). Null anywhere is Math ERROR.
 */
internal sealed interface StatFit {
    val a: Dec
    val b: Dec

    /** The quadratic's C; null for every other type (`Reg` has no C there, research 03 §4.2). */
    val c: Dec?

    /** The correlation coefficient; null for the quadratic ("There is no r", 03 §4.3) or when undefined. */
    val r: Dec?

    /** [op] applied to [value]: `ŷ` from an x, `x̂` (or `x̂₁`, `x̂₂`) from a y; null when undefined. */
    fun estimate(op: PostfixOp, value: Dec): Dec?
}

/**
 * The guide gives no formulas (research 03 §4.5). These are ordinary weighted least squares, which
 * research 03 §4.5 found to reproduce every STAT result the guide prints: linear r = 0,923 and the
 * ln X regression's A, B, r and x̂ (05 EX-STAT-03, 04). The other types follow the same
 * transform-then-fit reading — ⚠️ GUESS for e^X, A•B^X, A•X^B, 1/X and the quadratic, which no
 * printed example checks:
 *
 * | type | fits | A, B from the fitted line's intercept a and slope b |
 * |---|---|---|
 * | `A+BX` | y on x | A = a, B = b |
 * | `ln X` | y on ln x | A = a, B = b |
 * | `e^X` | ln y on x | A = eᵃ, B = b |
 * | `A•B^X` | ln y on x | A = eᵃ, B = eᵇ |
 * | `A•X^B` | ln y on ln x | A = eᵃ, B = b |
 * | `1/X` | y on 1/x | A = a, B = b |
 *
 * r is the correlation of the variables fitted (INFERRED; checked for ln X only). A point whose
 * transform is undefined — ln of a non-positive x in `ln X`, 1/0 in `1/X` — makes the whole fit a
 * Math ERROR, a step the calculator cannot take (⚠️ GUESS that it does not skip the point).
 */
internal object StatRegression {
    fun fit(type: StatType, points: List<StatPoint>): StatFit? = when (type) {
        StatType.ONE_VAR -> null
        StatType.QUADRATIC -> quadratic(points)
        else -> line(type, points)
    }

    /**
     * Slope Suv/Suu, intercept v̄ − slope·ū, and r = Suv/(√Suu·√Svv) in a work of its own: every y
     * the same makes r 0/0 — Math ERROR for r alone, while A and B stand.
     *
     * Whether the data determine a line is asked of the data, not of Suu: one distinct x makes Suu
     * zero in exact arithmetic, but 15-digit rounding can leave it a hair off zero, and a slope
     * divided by a hair is a confident wrong answer where the calculator's Math ERROR belongs
     * (⚠️ GUESS that the firmware errs there too).
     */
    private fun line(type: StatType, points: List<StatPoint>): StatFit? = StatWork.checked {
        val t = points.map { StatPoint(across(type, it.x), up(type, it.y), it.f) }
        need(t.distinctBy { it.x }.size >= 2)
        val suv = covariance(t)
        val suu = spread(t) { it.x }
        val slope = div(suv, suu)
        val intercept = sub(mean(t) { it.y }, mul(slope, mean(t) { it.x }))
        val r = StatWork.checked {
            need(t.distinctBy { it.y }.size >= 2)
            div(suv, mul(sqrt(suu), sqrt(spread(t) { it.y })))
        }
        Line(type, intercept(type, intercept), slope(type, slope), r)
    }

    /** Σf·(u − ū)(v − v̄), with the transformed points' x as u and y as v. */
    private fun StatWork.covariance(t: List<StatPoint>): Dec {
        val mu = mean(t) { it.x }
        val mv = mean(t) { it.y }
        return total(t) { mul(sub(it.x, mu), sub(it.y, mv)) }
    }

    /** The variable a type fits along: x, ln x or 1/x. */
    private fun StatWork.across(type: StatType, x: Dec): Dec = when (type) {
        StatType.LOGARITHMIC, StatType.POWER -> ln(x)
        StatType.INVERSE -> div(Dec.ONE, x)
        else -> x
    }

    /** The variable a type fits: y or ln y. */
    private fun StatWork.up(type: StatType, y: Dec): Dec = when (type) {
        StatType.E_EXPONENTIAL, StatType.AB_EXPONENTIAL, StatType.POWER -> ln(y)
        else -> y
    }

    private fun StatWork.intercept(type: StatType, fitted: Dec): Dec =
        if (type in EXPONENTIAL_INTERCEPT) exp(fitted) else fitted

    private fun StatWork.slope(type: StatType, fitted: Dec): Dec =
        if (type == StatType.AB_EXPONENTIAL) exp(fitted) else fitted

    /**
     * y = A + Bx + Cx² by least squares over x and x² (⚠️ GUESS: research 03 §4.5's "ordinary least
     * squares", no printed example). Centred sums keep the 15-digit arithmetic away from the
     * cancellation `Σx⁴ − (Σx²)²/n` invites. Fewer than three distinct x make the system singular —
     * Math ERROR, asked of the data for the reason [line] gives: `{1, 2, 1}` leaves a determinant of
     * 2×10⁻¹⁵ after rounding, not 0.
     */
    private fun quadratic(points: List<StatPoint>): StatFit? = StatWork.checked {
        need(points.distinctBy { it.x }.size >= PARABOLA_POINTS)
        val mx = mean(points) { it.x }
        val my = mean(points) { it.y }
        val mq = mean(points) { square(it.x) }
        val sxx = total(points) { square(sub(it.x, mx)) }
        val sxy = total(points) { mul(sub(it.x, mx), sub(it.y, my)) }
        val sxq = total(points) { mul(sub(it.x, mx), sub(square(it.x), mq)) }
        val sqq = total(points) { square(sub(square(it.x), mq)) }
        val sqy = total(points) { mul(sub(square(it.x), mq), sub(it.y, my)) }
        val det = sub(mul(sxx, sqq), square(sxq))
        val b = div(sub(mul(sxy, sqq), mul(sqy, sxq)), det)
        val c = div(sub(mul(sqy, sxx), mul(sxy, sxq)), det)
        Quadratic(sub(sub(my, mul(b, mx)), mul(c, mq)), b, c)
    }

    /** Types whose A is e to the fitted intercept. */
    private val EXPONENTIAL_INTERCEPT = setOf(StatType.E_EXPONENTIAL, StatType.AB_EXPONENTIAL, StatType.POWER)

    /** Distinct x a parabola needs. */
    private const val PARABOLA_POINTS = 3
}

/**
 * Every type but the quadratic: the estimates invert the model as written (research 03 §4.5, the
 * ln X one checked by EX-STAT-04's `-130x̂ = 4,861`):
 *
 * | type | ŷ | x̂ |
 * |---|---|---|
 * | `A+BX` | A + Bx | (y − A)/B |
 * | `ln X` | A + B·ln x | e^((y − A)/B) |
 * | `e^X` | A·e^(Bx) | (ln y − ln A)/B |
 * | `A•B^X` | A·Bˣ | (ln y − ln A)/ln B |
 * | `A•X^B` | A·x^B | e^((ln y − ln A)/B) |
 * | `1/X` | A + B/x | B/(y − A) |
 */
private data class Line(val type: StatType, override val a: Dec, override val b: Dec, override val r: Dec?) : StatFit {
    override val c: Dec? get() = null

    override fun estimate(op: PostfixOp, value: Dec): Dec? = when (op) {
        PostfixOp.EST_Y -> StatWork.checked { yFor(value) }
        PostfixOp.EST_X -> StatWork.checked { xFor(value) }
        else -> null
    }

    private fun StatWork.yFor(x: Dec): Dec = when (type) {
        StatType.LOGARITHMIC -> add(a, mul(b, ln(x)))
        StatType.E_EXPONENTIAL -> mul(a, exp(mul(b, x)))
        StatType.AB_EXPONENTIAL -> mul(a, pow(b, x))
        StatType.POWER -> mul(a, pow(x, b))
        StatType.INVERSE -> add(a, div(b, x))
        else -> add(a, mul(b, x))
    }

    private fun StatWork.xFor(y: Dec): Dec = when (type) {
        StatType.LOGARITHMIC -> exp(div(sub(y, a), b))
        StatType.E_EXPONENTIAL -> div(sub(ln(y), ln(a)), b)
        StatType.AB_EXPONENTIAL -> div(sub(ln(y), ln(a)), ln(b))
        StatType.POWER -> exp(div(sub(ln(y), ln(a)), b))
        StatType.INVERSE -> div(b, sub(y, a))
        else -> div(sub(y, a), b)
    }
}

/**
 * `_+CX²`: ŷ = A + Bx + Cx², and the two x for a y from the quadratic formula,
 * x̂₁ = (−B + √(B² − 4C(A − y)))/2C and x̂₂ with −√ — ⚠️ GUESS at which is which (research 03 §4.5
 * calls the order UNKNOWN): this is the order the fx-570MS/991MS-series guides write the two
 * formulas in, as recalled, not re-checked for this build. A negative discriminant or C = 0 is
 * Math ERROR.
 */
private data class Quadratic(override val a: Dec, override val b: Dec, override val c: Dec) : StatFit {
    override val r: Dec? get() = null

    override fun estimate(op: PostfixOp, value: Dec): Dec? = when (op) {
        PostfixOp.EST_Y -> StatWork.checked { add(add(a, mul(b, value)), mul(c, square(value))) }
        PostfixOp.EST_X1 -> root(value, plus = true)
        PostfixOp.EST_X2 -> root(value, plus = false)
        else -> null
    }

    private fun root(y: Dec, plus: Boolean): Dec? = StatWork.checked {
        val shift = sqrt(sub(square(b), mul(mul(FOUR, c), sub(a, y))))
        div(if (plus) add(-b, shift) else sub(-b, shift), mul(Dec.TWO, c))
    }

    private companion object {
        val FOUR: Dec = Dec.of(4)
    }
}
