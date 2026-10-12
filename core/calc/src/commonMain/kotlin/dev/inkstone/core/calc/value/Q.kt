package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.num.DecMath

/**
 * An exact rational `num/den` in lowest terms with `den > 0`: the coefficient of one surd term,
 * and the whole of a fraction. Every operation that would leave a Long answers **null** rather
 * than wrap — and a coefficient that large is far past every limit the calculator can hold
 * (14 digits for a fraction, under 100 for a surd), so the caller's answer to null is always the
 * same: the step is not exact, do it in decimal (docs/CALCULATOR.md §3).
 *
 * `Long.MIN_VALUE` is refused at construction, so negation can never overflow.
 */
internal data class Q(val num: Long, val den: Long) {
    val isZero: Boolean get() = num == 0L

    operator fun unaryMinus(): Q = Q(-num, den)

    fun abs(): Q = if (num < 0L) Q(-num, den) else this

    operator fun plus(other: Q): Q? {
        // Over the lcm, not the product: the only common factor the sum can still have divides
        // gcd(den, other.den), so this keeps the intermediates as small as they can be.
        val g = DecMath.gcd(den, other.den)
        val a = DecMath.multiplyExact(num, other.den / g) ?: return null
        val b = DecMath.multiplyExact(other.num, den / g) ?: return null
        val n = DecMath.addExact(a, b) ?: return null
        val d = DecMath.multiplyExact(den / g, other.den) ?: return null
        return of(n, d)
    }

    operator fun minus(other: Q): Q? = plus(-other)

    operator fun times(other: Q): Q? {
        // Cross-cancel first, so the product is already in lowest terms and overflows only when
        // the answer itself would.
        val g1 = DecMath.gcd(num, other.den)
        val g2 = DecMath.gcd(other.num, den)
        val n = DecMath.multiplyExact(num / g1, other.num / g2) ?: return null
        val d = DecMath.multiplyExact(den / g2, other.den / g1) ?: return null
        return of(n, d)
    }

    /** `1/q`. The caller has already refused zero (`÷0` is Math ERROR before it gets here). */
    fun inverse(): Q {
        check(num != 0L) { "the inverse of zero" }
        return if (num < 0L) Q(-den, -num) else Q(den, num)
    }

    operator fun div(other: Q): Q? = times(other.inverse())

    companion object {
        val ZERO = Q(0L, 1L)
        val ONE = Q(1L, 1L)

        /** Reduced, with the sign on the numerator; null for a part that cannot be negated. */
        fun of(num: Long, den: Long): Q? {
            check(den != 0L) { "a zero denominator" }
            if (num == Long.MIN_VALUE || den == Long.MIN_VALUE) return null
            val g = DecMath.gcd(num, den)
            val sign = if (den < 0L) -1L else 1L
            return Q(sign * num / g, sign * den / g)
        }

        fun of(n: Long): Q? = of(n, 1L)
    }
}
