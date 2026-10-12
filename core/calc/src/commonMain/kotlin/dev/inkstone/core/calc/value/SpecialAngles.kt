package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.AngleUnit
import dev.inkstone.core.calc.num.Dec

/**
 * The exact sines, cosines and tangents of multiples of 15° — the ROM's paired tables (research
 * 07 §4.4: sin 15° = (√6−√2)/4, 30° = ½, 45° = √2/2, 60° = √3/2, 75° = (√6+√2)/4; tan 15° = 2−√3,
 * 30° = √3/3, 60° = √3, 75° = 2+√3), extended to every quadrant by symmetry.
 *
 * Angles are **keyed on the angle**, not on the computed value (docs/CALCULATOR.md §3, 07 T8, a
 * **GUESS**): an angle is "the K-th multiple of 15°" when x/15° is within 1×10⁻¹³ (relative) of the
 * integer K, in Deg anywhere in its input range, in Rad for `|x| < 20π` (step π/12, Casio's π),
 * and in Gra for `|x| < 10000` (step 50/3) — research 02 §8.6's table. So sin 18° is a decimal
 * even though (√5−1)/4 would fit a surd record.
 *
 * Degrees and grads are tested **after** the exact reduction by whole turns ([Angles.reduce]), so
 * K is under one turn's 24 steps and the tolerance is about 10⁻¹¹° whatever the angle. Testing the
 * raw angle made the tolerance grow with it — about 10⁻³° near the 9×10⁹ limit — which turned
 * `sin 8999999985,0001°` (≡ 345,0001°) into the table's sin 345°, wrong in the seventh digit, and
 * `tan 8999999910,0001°` into a pole (a review finding; research 02 §8.2 promises ±1 in the 10th
 * digit). Radians cannot be reduced exactly, but their `|x| < 20π` bound keeps K below 240.
 */
internal object SpecialAngles {
    /** K counts 15° steps; a full turn is 24 of them, a half turn 12, a right angle 6. */
    const val TURN = 24
    const val HALF_TURN = 12
    const val RIGHT = 6
    private const val TAN_RANGE = 5

    private val TOLERANCE = Dec.fromDigits(false, "1", 1, -13)
    private val FIFTEEN = Dec.of(15)
    private val THREE = Dec.of(3)
    private val FIFTY = Dec.of(50)
    private val TWELVE = Dec.of(12)
    private val GRAD_LIMIT = Dec.of(10_000)
    private val RADIAN_LIMIT = Dec.of(20) * Dec.PI

    // The ROM's exact twins (research 07 §4.4), as surd records.
    private val SIN_15: Real = surd(6L to Q(1, 4), 2L to Q(-1, 4))
    private val SIN_30: Real = FractionReal(1, 2)
    private val SIN_45: Real = surd(2L to Q(1, 2))
    private val SIN_60: Real = surd(3L to Q(1, 2))
    private val SIN_75: Real = surd(6L to Q(1, 4), 2L to Q(1, 4))
    private val TAN_15: Real = surd(1L to Q(2, 1), 3L to Q(-1, 1))
    private val TAN_30: Real = surd(3L to Q(1, 3))
    private val TAN_60: Real = surd(3L to Q(1, 1))
    private val TAN_75: Real = surd(1L to Q(2, 1), 3L to Q(1, 1))

    /** sin(K·15°) for K = 0…12. */
    private val SINES: List<Real> = listOf(
        Reals.ZERO, SIN_15, SIN_30, SIN_45, SIN_60, SIN_75, Reals.ONE,
        SIN_75, SIN_60, SIN_45, SIN_30, SIN_15, Reals.ZERO,
    )

    /** tan(K·15°) for K = 0…5; K = 6 is the pole. */
    private val TANGENTS: List<Real> = listOf(Reals.ZERO, TAN_15, TAN_30, Reals.ONE, TAN_60, TAN_75)

    /**
     * A K with `x ≡ K × 15°` (in [unit]'s measure) — the same angle up to whole turns, which is all
     * sin, cos and tan read — or null when x is not such an angle.
     */
    fun indexOf(x: Dec, unit: AngleUnit): Long? {
        val k = steps(x, unit) ?: return null
        if (k.isZero) return 0L
        val nearest = k.roundPlaces(0)
        if (nearest.isZero) return null
        return if ((k - nearest).abs() <= nearest.abs() * TOLERANCE) nearest.toLongOrNull() else null
    }

    /** x in 15° steps, Deg and Gra after the exact reduction; null outside the special range. */
    private fun steps(x: Dec, unit: AngleUnit): Dec? = when (unit) {
        AngleUnit.DEG -> reduced(x, unit) / FIFTEEN
        AngleUnit.GRA -> if (x.abs() >= GRAD_LIMIT) null else reduced(x, unit) * THREE / FIFTY
        AngleUnit.RAD -> if (x.abs() >= RADIAN_LIMIT) null else x * TWELVE / Dec.PI
    }

    private fun reduced(x: Dec, unit: AngleUnit): Dec = Angles.turn(unit)?.let { Angles.reduce(x, it) } ?: x

    fun sin(k: Long): Real {
        val m = k.mod(TURN)
        return if (m <= HALF_TURN) SINES[m] else RealArith.negate(SINES[m - HALF_TURN])
    }

    fun cos(k: Long): Real = sin(k + RIGHT)

    /** tan(K·15°), or null at an odd multiple of 90°, where it is Math ERROR (research 02 §13). */
    fun tan(k: Long): Real? {
        val m = k.mod(HALF_TURN)
        return when {
            m == RIGHT -> null
            m < RIGHT -> TANGENTS[m]
            else -> RealArith.negate(TANGENTS[HALF_TURN - m])
        }
    }

    /** The K in −6…6 whose sine is exactly [v], for `sin⁻¹`. */
    fun asinIndex(v: Real): Int? = (-RIGHT..RIGHT).firstOrNull { Reals.exactEquals(sin(it.toLong()), v) }

    /** The K in 0…12 whose cosine is exactly [v], for `cos⁻¹`. */
    fun acosIndex(v: Real): Int? = (0..HALF_TURN).firstOrNull { Reals.exactEquals(cos(it.toLong()), v) }

    /** The K in −5…5 whose tangent is exactly [v], for `tan⁻¹` and the exact Pol/arg angle. */
    fun atanIndex(v: Real): Int? =
        (-TAN_RANGE..TAN_RANGE).firstOrNull { k -> tan(k.toLong())?.let { Reals.exactEquals(it, v) } == true }

    /**
     * K × 15° in [unit]: exact in Deg (`30`) and Gra (`100/3`), and in Rad the decimal `K·π/12`
     * with Casio's π — the same number `π÷12×K` typed in gives, so the π-form display recognises
     * it and a `sin` of it is special again.
     */
    fun angle(k: Int, unit: AngleUnit): Real = when (unit) {
        AngleUnit.DEG -> Reals.of(FIFTEEN_DEGREES * k)
        AngleUnit.GRA -> checkNotNull(Reals.fraction(GRADS_NUMERATOR * k, GRADS_DENOMINATOR).getOrNull())
        AngleUnit.RAD -> DecimalReal(Dec.PI * Dec.of(k) / TWELVE)
    }

    private fun surd(vararg terms: Pair<Long, Q>): Real =
        checkNotNull(Reals.exactOrNull(SurdSum.of(terms.toMap()))) { "a special value outside the record" }

    private const val FIFTEEN_DEGREES = 15L
    private const val GRADS_NUMERATOR = 50L
    private const val GRADS_DENOMINATOR = 3L
}
