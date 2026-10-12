package dev.inkstone.core.calc.num

import kotlin.math.sqrt

/**
 * Exact integer arithmetic on Long for [DecMath]'s integer helpers: what fractions, surds, GCD,
 * LCM and FACT are built from. Every operation that can leave a Long says so with a null, because
 * a silently wrapped numerator is a wrong answer shown with full confidence.
 */
internal object LongMath {
    fun gcd(a: Long, b: Long): Long {
        require(a != Long.MIN_VALUE && b != Long.MIN_VALUE) { "|Long.MIN_VALUE| is not a Long" }
        var x = if (a < 0) -a else a
        var y = if (b < 0) -b else b
        while (y != 0L) {
            val t = x % y
            x = y
            y = t
        }
        return x
    }

    fun lcm(a: Long, b: Long): Long? {
        if (a == 0L || b == 0L) return 0L
        val g = gcd(a, b)
        val x = if (a < 0) -a else a
        val y = if (b < 0) -b else b
        return multiplyExact(x / g, y)
    }

    fun multiplyExact(a: Long, b: Long): Long? {
        if (a == 0L || b == 0L) return 0L
        // The two −1 × MIN_VALUE cases first: they are the overflows the division test cannot see,
        // and testing them first means `r / b` is never MIN_VALUE / −1, which LLVM — the Native
        // backend — leaves undefined.
        if (minOf(a, b) == Long.MIN_VALUE && maxOf(a, b) == -1L) return null
        val r = a * b
        return if (r / b != a) null else r
    }

    fun addExact(a: Long, b: Long): Long? {
        val r = a + b
        // Overflow happened exactly when both operands share a sign the result does not.
        return if (((a xor r) and (b xor r)) < 0) null else r
    }

    fun powExact(base: Long, exponent: Int): Long? {
        require(exponent >= 0) { "negative exponent" }
        if (base == 0L || base == 1L) return if (exponent == 0) 1L else base
        if (base == -1L) return if (exponent % 2 == 0) 1L else -1L
        // |base| ≥ 2 from here, so 64 factors are already past any Long.
        if (exponent >= Long.SIZE_BITS) return null
        var result = 1L
        repeat(exponent) { result = multiplyExact(result, base) ?: return null }
        return result
    }

    /** `⌊√n⌋` for `n ≥ 0`; a Double seed corrected exactly, without ever squaring past Long. */
    fun floorSqrt(n: Long): Long {
        require(n >= 0) { "floorSqrt of a negative" }
        var s = sqrt(n.toDouble()).toLong()
        while (s > 0 && s > n / s) s--
        while (s + 1 <= n / (s + 1)) s++
        return s
    }

    fun isPerfectSquare(n: Long): Boolean {
        if (n < 0) return false
        val s = floorSqrt(n)
        return s * s == n
    }

    /**
     * `n = k²·r` with `r` squarefree, as `k to r` — the simplification that turns `√72` into
     * `6√2` (research 02 §8.6). Trial division only up to the cube root of what is left: past
     * that the remainder has at most two prime factors, so it is a prime, a prime squared, or
     * squarefree, and one exact square root tells them apart.
     */
    fun squareFreePart(n: Long): Pair<Long, Long> {
        require(n > 0) { "squareFreePart needs a positive integer" }
        var m = n
        var k = 1L
        var r = 1L
        var p = 2L
        while (p <= m / p / p) {
            var count = 0
            while (m % p == 0L) {
                m /= p
                count++
            }
            repeat(count / 2) { k *= p }
            if (count % 2 == 1) r *= p
            p += if (p == 2L) 1L else 2L
        }
        val s = floorSqrt(m)
        return if (m > 1 && s * s == m) (k * s) to r else k to (r * m)
    }
}
