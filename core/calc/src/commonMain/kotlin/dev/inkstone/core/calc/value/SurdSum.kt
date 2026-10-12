package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.num.DecMath

/**
 * The exact working form behind [FractionReal] and [SurdReal]: `Σ qᵢ·√rᵢ` with every `rᵢ`
 * squarefree and distinct, and no zero coefficient. Radicand 1 is the rational term.
 *
 * It is deliberately *wider* than either record. A product of two two-term surds has four terms
 * before like terms combine, and `(1+√2)⁵` passes through three on the way to `41+29√2`; holding
 * the whole sum until the operation is over is what lets "demote at the step that breaks the
 * limits" mean the *operation*, not some half-way point inside it (research 02 §8.6,
 * docs/CALCULATOR.md §3). [Reals.exactOrNull] is where the record's limits are applied, once.
 *
 * Every operation answers null when a coefficient leaves a Long; see [Q] for why that always
 * means "not exact" and never "wrong".
 */
internal class SurdSum private constructor(val terms: Map<Long, Q>) {
    operator fun plus(other: SurdSum): SurdSum? {
        val out = terms.toMutableMap()
        for ((r, q) in other.terms) {
            out[r] = accumulate(out[r], q) ?: return null
        }
        return of(out)
    }

    operator fun unaryMinus(): SurdSum = SurdSum(terms.mapValues { -it.value })

    operator fun times(other: SurdSum): SurdSum? {
        val out = mutableMapOf<Long, Q>()
        for ((r1, q1) in terms) {
            for ((r2, q2) in other.terms) {
                // √r₁·√r₂ = k√s with s squarefree: √2·√6 = 2√3.
                val product = DecMath.multiplyExact(r1, r2) ?: return null
                val (k, s) = DecMath.squareFreePart(product)
                val kq = Q.of(k) ?: return null
                val q = (q1 * q2)?.times(kq) ?: return null
                out[s] = accumulate(out[s], q) ?: return null
            }
        }
        return of(out)
    }

    /**
     * `1/x` for a non-zero sum of at most two terms — all a [Real] can hold. One term inverts
     * directly, `1/(q√r) = √r/(q·r)`; two are rationalised by the conjugate,
     * `1/(A+B) = (A−B)/(A²−B²)`, where `A²` and `B²` are rational. `A² = B²` would need
     * `q₁²r₁ = q₂²r₂` with distinct squarefree radicands, which is impossible, so the
     * denominator is never zero. This is what makes `(2+√2)/(1+√2) = √2` (research 04 B-03).
     */
    fun inverse(): SurdSum? {
        check(terms.size in 1..2) { "inverse of a sum of ${terms.size} terms" }
        val (a, b) = terms.entries.toList().let { it[0] to it.getOrNull(1) }
        if (b == null) return (Q.of(a.key)?.let { a.value.inverse() / it })?.let { of(mapOf(a.key to it)) }
        val denominator = square(a.value, a.key)?.let { a2 -> square(b.value, b.key)?.let { b2 -> a2 - b2 } }
        val scale = denominator?.inverse() ?: return null
        val first = (a.value * scale) ?: return null
        return (-b.value * scale)?.let { of(mapOf(a.key to first, b.key to it)) }
    }

    /** `xⁿ` for `n ≥ 0` by repeated squaring; `x⁰ = 1` (the caller refuses `0⁰`). */
    fun pow(n: Long): SurdSum? {
        var result = ONE
        var base = this
        var k = n
        while (k > 0L) {
            if (k % 2L == 1L) result = (result * base) ?: return null
            k /= 2L
            if (k > 0L) base = (base * base) ?: return null
        }
        return result
    }

    /** The single rational coefficient, when that is all this is (zero included). */
    fun rationalOrNull(): Q? = when {
        terms.isEmpty() -> Q.ZERO
        terms.size == 1 -> terms[1L]
        else -> null
    }

    companion object {
        val ONE = SurdSum(mapOf(1L to Q.ONE))

        fun of(terms: Map<Long, Q>): SurdSum = SurdSum(terms.filterValues { !it.isZero })

        fun rational(q: Q): SurdSum = of(mapOf(1L to q))

        /** `(q·√r)² = q²·r`, rational. */
        private fun square(q: Q, r: Long): Q? = (q * q)?.let { s -> Q.of(r)?.let { s * it } }

        /** [existing] + [q], where a missing [existing] is zero; null only on overflow. */
        private fun accumulate(existing: Q?, q: Q): Q? = if (existing == null) q else existing + q

        /** The exact form of [real], or null for a decimal, which has none. */
        fun of(real: Real): SurdSum? = when (real) {
            is FractionReal -> Q.of(real.num, real.den)?.let(::rational)
            is SurdReal -> of(real.terms.associate { it.radicand to Q(it.num, it.den) })
            is DecimalReal -> null
        }
    }
}
