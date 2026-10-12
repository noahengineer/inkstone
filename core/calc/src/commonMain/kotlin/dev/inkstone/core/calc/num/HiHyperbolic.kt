package dev.inkstone.core.calc.num

/**
 * The six hyperbolic functions on [HiDec] (the `hyp` menu, research 02 §10.5).
 *
 * Each one avoids the subtraction its textbook formula hides: `sinh` of a small argument is a
 * series rather than `(eˣ − e⁻ˣ)/2`, and the inverses go through [HiExpLog.log1p] rather than
 * `ln(…)`, because `asinh 10⁻²⁰` written as `ln(x + √(x²+1))` is `ln 1 = 0` at any precision a
 * calculator would choose. The callers (in [DecMath]) have already checked the domain and range.
 */
internal object HiHyperbolic {
    /** Past this `tanh x` is ±1 to far more than [HiConstants.WORK] digits: `1 − 2e⁻¹²⁰`. */
    private val TANH_SATURATION = HiDec.of(60L)
    private val TWO = HiDec.of(2L)

    fun sinh(x: HiDec, p: Int): HiDec {
        val g = p + HiConstants.GUARD
        val a = x.abs()
        val v = if (a < HiDec.ONE) {
            sinhSeries(a, g)
        } else {
            val e = HiExpLog.exp(a, g)
            e.sub(HiDec.ONE.div(e, g), g).divSmall(2, g)
        }
        return (if (x.negative) v.negate() else v).truncate(p)
    }

    fun cosh(x: HiDec, p: Int): HiDec {
        val g = p + HiConstants.GUARD
        val e = HiExpLog.exp(x.abs(), g)
        return e.add(HiDec.ONE.div(e, g), g).divSmall(2, g).truncate(p)
    }

    fun tanh(x: HiDec, p: Int): HiDec {
        val g = p + HiConstants.GUARD
        val a = x.abs()
        val v = when {
            a > TANH_SATURATION -> HiDec.ONE

            a < HiDec.ONE -> {
                val s = sinhSeries(a, g)
                s.div(HiDec.ONE.add(s.mul(s, g), g).sqrt(g), g)
            }

            else -> {
                val e2 = HiExpLog.exp(a.mulSmall(2, g), g)
                e2.sub(HiDec.ONE, g).div(e2.add(HiDec.ONE, g), g)
            }
        }
        return (if (x.negative) v.negate() else v).truncate(p)
    }

    /** `asinh x = ln(1 + u)`, `u = |x| + x²/(1 + √(1 + x²))` — no cancellation anywhere. */
    fun asinh(x: HiDec, p: Int): HiDec {
        val g = p + HiConstants.GUARD
        val a = x.abs()
        val a2 = a.mul(a, g)
        val root = HiDec.ONE.add(a2, g).sqrt(g)
        val u = a.add(a2.div(HiDec.ONE.add(root, g), g), g)
        val v = HiExpLog.log1p(u, g)
        return (if (x.negative) v.negate() else v).truncate(p)
    }

    /** `acosh x = ln(1 + t + √(t(t+2)))` with `t = x − 1`, for `x ≥ 1`. */
    fun acosh(x: HiDec, p: Int): HiDec {
        val g = p + HiConstants.GUARD
        val t = x.sub(HiDec.ONE, HiDec.EXACT)
        val root = t.mul(t.add(TWO, g), g).sqrt(g)
        return HiExpLog.log1p(t.add(root, g), g).truncate(p)
    }

    /** `atanh x = ½·ln(1 + 2|x|/(1 − |x|))`, sign restored, for `|x| < 1`. */
    fun atanh(x: HiDec, p: Int): HiDec {
        val g = p + HiConstants.GUARD
        val a = x.abs()
        val u = a.mulSmall(2, g).div(HiDec.ONE.sub(a, HiDec.EXACT), g)
        val v = HiExpLog.log1p(u, g).divSmall(2, g)
        return (if (x.negative) v.negate() else v).truncate(p)
    }

    /** `x + x³/3! + x⁵/5! + …` for `0 ≤ x < 1`. */
    private fun sinhSeries(x: HiDec, p: Int): HiDec {
        if (x.isZero) return HiDec.ZERO
        val x2 = x.mul(x, p)
        var term = x
        var sum = x
        var k = 1
        do {
            term = term.mul(x2, p).divSmall((2 * k) * (2 * k + 1), p)
            sum = sum.add(term, p)
            k++
        } while (!term.negligibleBeside(sum, p))
        return sum
    }
}
