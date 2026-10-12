package dev.inkstone.core.calc.num

/**
 * An unsigned integer of any size, in base 10⁹ limbs, least significant first.
 *
 * It exists because common Kotlin has no `BigInteger` and `:core:*` may not use the JVM's
 * (CLAUDE.md rule 2): [Dec] needs exact 30-digit products and exact alignment of mismatched
 * exponents, and [HiDec] needs ~40-digit working precision for the transcendental functions.
 * Base 10⁹ rather than 2³² because everything built on it is *decimal* — rounding happens at a
 * decimal digit, and a power-of-ten shift is then a limb shift plus one small multiply.
 *
 * Immutable. A zero has no limbs, and no other value has a leading zero limb, so structural
 * equality is numeric equality.
 */
internal class BigNat private constructor(private val limbs: IntArray) : Comparable<BigNat> {

    val isZero: Boolean get() = limbs.isEmpty()

    /** Number of decimal digits; 0 for zero. */
    val digitCount: Int
        get() = if (limbs.isEmpty()) 0 else (limbs.size - 1) * LIMB_DIGITS + digitsOf(limbs.last())

    operator fun plus(other: BigNat): BigNat {
        val (a, b) = if (limbs.size >= other.limbs.size) limbs to other.limbs else other.limbs to limbs
        val out = IntArray(a.size + 1)
        var carry = 0L
        for (i in a.indices) {
            val s = a[i].toLong() + (if (i < b.size) b[i] else 0) + carry
            out[i] = (s % BASE).toInt()
            carry = s / BASE
        }
        out[a.size] = carry.toInt()
        return trimmed(out)
    }

    /** `this − other`; a caller passing a larger [other] has a bug, not a negative number. */
    operator fun minus(other: BigNat): BigNat {
        require(this >= other) { "BigNat underflow" }
        val out = IntArray(limbs.size)
        var borrow = 0L
        for (i in limbs.indices) {
            var s = limbs[i].toLong() - (if (i < other.limbs.size) other.limbs[i] else 0) - borrow
            borrow = if (s < 0) 1L else 0L
            if (s < 0) s += BASE
            out[i] = s.toInt()
        }
        return trimmed(out)
    }

    operator fun times(other: BigNat): BigNat {
        if (isZero || other.isZero) return ZERO
        val a = limbs
        val b = other.limbs
        val out = IntArray(a.size + b.size)
        for (i in a.indices) {
            var carry = 0L
            val ai = a[i].toLong()
            for (j in b.indices) {
                // (B−1)² + 2(B−1) = B² − 1, so neither the sum nor the carry can leave a Long.
                val t = out[i + j] + ai * b[j] + carry
                out[i + j] = (t % BASE).toInt()
                carry = t / BASE
            }
            out[i + b.size] = carry.toInt()
        }
        return trimmed(out)
    }

    /** `thisᵉ` for `e ≥ 0`, by repeated squaring (`0⁰ = 1`). */
    fun pow(e: Int): BigNat {
        require(e >= 0) { "negative exponent" }
        var result = ONE
        var square = this
        var k = e
        while (k > 0) {
            if (k % 2 == 1) result *= square
            k /= 2
            if (k > 0) square *= square
        }
        return result
    }

    /** Multiply by `0 ≤ m ≤ 10⁹`. */
    fun timesSmall(m: Int): BigNat {
        require(m in 0..BASE)
        if (isZero || m == 0) return ZERO
        return trimmed(scaled(limbs, m, limbs.size + 1))
    }

    /** Quotient and remainder by `1 ≤ d ≤ 10⁹`. */
    fun divRemSmall(d: Int): Pair<BigNat, Int> {
        require(d in 1..BASE)
        val out = IntArray(limbs.size)
        var r = 0L
        for (i in limbs.indices.reversed()) {
            val cur = r * BASE + limbs[i]
            out[i] = (cur / d).toInt()
            r = cur % d
        }
        return trimmed(out) to r.toInt()
    }

    /** Quotient and remainder (Knuth, TAOCP vol. 2, §4.3.1, algorithm D). */
    fun divRem(d: BigNat): Pair<BigNat, BigNat> {
        require(!d.isZero) { "division by zero" }
        if (this < d) return ZERO to this
        if (d.limbs.size == 1) {
            val (q, r) = divRemSmall(d.limbs[0])
            return q to of(r.toLong())
        }
        return knuthDivide(limbs, d.limbs)
    }

    fun timesPow10(k: Int): BigNat {
        require(k >= 0)
        if (isZero || k == 0) return this
        val small = timesSmall(POW10_INT[k % LIMB_DIGITS])
        val shift = k / LIMB_DIGITS
        if (shift == 0) return small
        val out = IntArray(small.limbs.size + shift)
        small.limbs.copyInto(out, shift)
        return BigNat(out)
    }

    /** `⌊this / 10ᵏ⌋`. */
    fun divPow10(k: Int): BigNat {
        require(k >= 0)
        if (isZero || k == 0) return this
        val shift = k / LIMB_DIGITS
        if (shift >= limbs.size) return ZERO
        val dropped = BigNat(limbs.copyOfRange(shift, limbs.size))
        return dropped.divRemSmall(POW10_INT[k % LIMB_DIGITS]).first
    }

    /** The value as a Long, or null if it does not fit. */
    fun toLongOrNull(): Long? {
        if (limbs.size > MAX_LONG_LIMBS) return null
        var r = 0L
        for (i in limbs.indices.reversed()) {
            if (r > (Long.MAX_VALUE - limbs[i]) / BASE) return null
            r = r * BASE + limbs[i]
        }
        return r
    }

    /**
     * The leading ~16 digits as `mantissa × 10^shift`, for seeding Newton iterations only — a
     * Double cannot hold the value itself once it passes 10³⁰⁸.
     */
    fun approx(): Pair<Double, Int> {
        var r = 0.0
        val from = maxOf(0, limbs.size - APPROX_LIMBS)
        for (i in limbs.size - 1 downTo from) r = r * BASE + limbs[i]
        return r to from * LIMB_DIGITS
    }

    override fun compareTo(other: BigNat): Int {
        if (limbs.size != other.limbs.size) return limbs.size.compareTo(other.limbs.size)
        for (i in limbs.indices.reversed()) {
            if (limbs[i] != other.limbs[i]) return limbs[i].compareTo(other.limbs[i])
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is BigNat && limbs.contentEquals(other.limbs)

    override fun hashCode(): Int = limbs.contentHashCode()

    override fun toString(): String {
        if (isZero) return "0"
        val sb = StringBuilder(limbs.last().toString())
        for (i in limbs.size - 2 downTo 0) sb.append(limbs[i].toString().padStart(LIMB_DIGITS, '0'))
        return sb.toString()
    }

    companion object {
        const val BASE = 1_000_000_000
        const val LIMB_DIGITS = 9
        private const val DECIMAL = 10
        private const val MAX_LONG_LIMBS = 3
        private const val APPROX_LIMBS = 3

        val ZERO = BigNat(IntArray(0))
        val ONE = BigNat(intArrayOf(1))

        /** Powers of ten that fit an Int, 10⁰…10⁹. */
        internal val POW10_INT = IntArray(LIMB_DIGITS + 1).also { p ->
            p[0] = 1
            for (i in 1..LIMB_DIGITS) p[i] = p[i - 1] * DECIMAL
        }

        fun of(v: Long): BigNat {
            require(v >= 0)
            if (v == 0L) return ZERO
            val out = ArrayList<Int>(MAX_LONG_LIMBS)
            var x = v
            while (x > 0) {
                out.add((x % BASE).toInt())
                x /= BASE
            }
            return BigNat(out.toIntArray())
        }

        /** Parse plain decimal digits (no sign, no point). */
        fun parse(digits: String): BigNat {
            require(digits.isNotEmpty() && digits.all { it in '0'..'9' })
            val count = (digits.length + LIMB_DIGITS - 1) / LIMB_DIGITS
            val out = IntArray(count)
            var end = digits.length
            for (i in 0 until count) {
                val start = maxOf(0, end - LIMB_DIGITS)
                out[i] = digits.substring(start, end).toInt()
                end = start
            }
            return trimmed(out)
        }

        fun pow10(k: Int): BigNat = ONE.timesPow10(k)

        private fun trimmed(a: IntArray): BigNat {
            var n = a.size
            while (n > 0 && a[n - 1] == 0) n--
            return BigNat(if (n == a.size) a else a.copyOf(n))
        }

        private fun knuthDivide(uIn: IntArray, vIn: IntArray): Pair<BigNat, BigNat> {
            val n = vIn.size
            val m = uIn.size - n
            // Normalise so the divisor's top limb is at least B/2; the quotient estimate is then
            // never more than two too large (Knuth's theorem B).
            val f = BASE / (vIn[n - 1] + 1)
            val u = scaled(uIn, f, uIn.size + 1)
            val v = scaled(vIn, f, n)
            val q = IntArray(m + 1)
            for (j in m downTo 0) q[j] = divideStep(u, v, j)
            val rem = trimmed(u.copyOf(n)).divRemSmall(f).first
            return trimmed(q) to rem
        }

        /** One quotient limb of algorithm D: estimate, multiply-subtract, add back if needed. */
        private fun divideStep(u: IntArray, v: IntArray, j: Int): Int {
            val n = v.size
            val vTop = v[n - 1].toLong()
            val num = u[j + n].toLong() * BASE + u[j + n - 1]
            var qHat = num / vTop
            var rHat = num % vTop
            while (rHat < BASE && (qHat >= BASE || qHat * v[n - 2] > rHat * BASE + u[j + n - 2])) {
                qHat--
                rHat += vTop
            }
            var carry = 0L
            var borrow = 0L
            for (i in 0 until n) {
                val p = qHat * v[i] + carry
                carry = p / BASE
                var s = u[i + j] - p % BASE - borrow
                borrow = if (s < 0) 1L else 0L
                if (s < 0) s += BASE
                u[i + j] = s.toInt()
            }
            val top = u[j + n] - carry - borrow
            if (top >= 0) {
                u[j + n] = top.toInt()
                return qHat.toInt()
            }
            // qHat was one too large: the partial remainder went negative. Add one divisor back.
            var c = 0L
            for (i in 0 until n) {
                val s = u[i + j].toLong() + v[i] + c
                u[i + j] = (s % BASE).toInt()
                c = s / BASE
            }
            u[j + n] = ((top + BASE + c) % BASE).toInt()
            return (qHat - 1).toInt()
        }
    }
}

/** `src × f` into a fresh array of [size] limbs (the caller guarantees it fits). */
private fun scaled(src: IntArray, f: Int, size: Int): IntArray {
    val out = IntArray(size)
    var carry = 0L
    for (i in src.indices) {
        val t = src[i].toLong() * f + carry
        out[i] = (t % BigNat.BASE).toInt()
        carry = t / BigNat.BASE
    }
    if (carry != 0L) out[src.size] = carry.toInt()
    return out
}

private fun digitsOf(limb: Int): Int {
    var n = 1
    while (n < BigNat.LIMB_DIGITS && limb >= BigNat.POW10_INT[n]) n++
    return n
}
