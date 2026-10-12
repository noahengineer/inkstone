package dev.inkstone.core.calc.num

/**
 * Circular functions on [HiDec], in **radians**.
 *
 * The argument is reduced by the *true* π/2 ([HiConstants.halfPi]) to as many digits as the
 * argument has integer digits plus the working precision, so `sin(10¹⁰)` is as right as `sin 1`:
 * the subtraction `x − k·π/2` is done **exactly** and only then rounded. A reduction by a 15-digit
 * π — Casio's or anyone's — would leave an error of `k × 10⁻¹⁵` in the reduced angle, which for
 * `x = 10¹⁰` is the fifth digit of the answer.
 *
 * Exact values at special angles (`sin 30° = ½`, `cos 45° = √2/2`) are not this layer's job: the
 * evaluator keys them on the angle (CALCULATOR.md §3, research 07 T8) before calling here.
 */
internal object HiTrig {
    /** `atan y = 2·atan(y / (1 + √(1 + y²)))`, three times: `|y| ≤ 1` becomes `|y| < 0,1`. */
    private const val ATAN_HALVINGS = 3
    private const val ATAN_HALVING_FACTOR = 8
    private const val QUADRANTS = 4

    fun sin(x: HiDec, p: Int): HiDec {
        val g = p + HiConstants.GUARD
        val (quadrant, r) = reduce(x, g)
        val v = when (quadrant) {
            0 -> sinSeries(r, g)
            1 -> cosSeries(r, g)
            2 -> sinSeries(r, g).negate()
            else -> cosSeries(r, g).negate()
        }
        return v.truncate(p)
    }

    fun cos(x: HiDec, p: Int): HiDec {
        val g = p + HiConstants.GUARD
        val (quadrant, r) = reduce(x, g)
        val v = when (quadrant) {
            0 -> cosSeries(r, g)
            1 -> sinSeries(r, g).negate()
            2 -> cosSeries(r, g).negate()
            else -> sinSeries(r, g)
        }
        return v.truncate(p)
    }

    /**
     * `tan x`, or null at a pole. A decimal argument is never an exact odd multiple of π/2, so in
     * practice this is never null; the evaluator rejects `tan 90°` itself (research 02 §13).
     */
    fun tan(x: HiDec, p: Int): HiDec? {
        val g = p + HiConstants.GUARD
        val (quadrant, r) = reduce(x, g)
        val s = sinSeries(r, g)
        val c = cosSeries(r, g)
        val (num, den) = if (quadrant % 2 == 0) s to c else c.negate() to s
        return if (den.isZero) null else num.div(den, g).truncate(p)
    }

    /** `atan t` in `(−π/2, π/2)`. */
    fun atan(t: HiDec, p: Int): HiDec {
        if (t.isZero) return HiDec.ZERO
        val g = p + HiConstants.GUARD
        val a = t.abs()
        val v = if (a > HiDec.ONE) {
            HiConstants.halfPi(g).sub(atanAtMostOne(HiDec.ONE.div(a, g), g), g)
        } else {
            atanAtMostOne(a, g)
        }
        return (if (t.negative) v.negate() else v).truncate(p)
    }

    /**
     * The angle of the point `(x, y)`, in `(−π, π]`; null at the origin, where it is undefined.
     * Pol and the complex `arg` both read it (research 06 §5.14).
     */
    fun atan2(y: HiDec, x: HiDec, p: Int): HiDec? {
        val g = p + HiConstants.GUARD
        if (x.isZero) {
            if (y.isZero) return null
            val half = HiConstants.halfPi(p)
            return if (y.negative) half.negate() else half
        }
        val base = atan(y.div(x, g), g)
        val pi = HiConstants.pi.truncate(g)
        val v = when {
            !x.negative -> base
            !y.negative -> base.add(pi, g)
            else -> base.sub(pi, g)
        }
        return v.truncate(p)
    }

    /**
     * `x = k·π/2 + r`, `|r| ≤ π/4`: returns `k mod 4` and `r` to [p] digits. `k·π/2` is formed
     * with a π long enough that its error times `k` is below `r`'s last digit, and the difference
     * is exact before it is rounded.
     */
    private fun reduce(x: HiDec, p: Int): Pair<Int, HiDec> {
        if (x.isZero) return 0 to HiDec.ZERO
        val integerDigits = maxOf(x.lead, 0)
        val halfPi = HiConstants.halfPi(integerDigits + p + HiConstants.GUARD)
        val k = x.div(halfPi, integerDigits + HiConstants.GUARD).roundAbs()
        if (k.isZero) return 0 to x.truncate(p)
        val multiple = HiDec.make(x.negative, k, 0).mul(halfPi, HiDec.EXACT)
        val r = x.sub(multiple, HiDec.EXACT).truncate(p)
        val q = k.divRemSmall(QUADRANTS).second
        return (if (x.negative) (QUADRANTS - q) % QUADRANTS else q) to r
    }

    /** `r − r³/3! + r⁵/5! − …` */
    private fun sinSeries(r: HiDec, p: Int): HiDec {
        if (r.isZero) return HiDec.ZERO
        val r2 = r.mul(r, p)
        var term = r
        var sum = r
        var k = 1
        do {
            term = term.mul(r2, p).divSmall((2 * k) * (2 * k + 1), p).negate()
            sum = sum.add(term, p)
            k++
        } while (!term.negligibleBeside(sum, p))
        return sum
    }

    /** `1 − r²/2! + r⁴/4! − …` */
    private fun cosSeries(r: HiDec, p: Int): HiDec {
        if (r.isZero) return HiDec.ONE
        val r2 = r.mul(r, p)
        var term = HiDec.ONE
        var sum = HiDec.ONE
        var k = 1
        do {
            term = term.mul(r2, p).divSmall((2 * k - 1) * (2 * k), p).negate()
            sum = sum.add(term, p)
            k++
        } while (!term.negligibleBeside(sum, p))
        return sum
    }

    /** `atan a` for `0 ≤ a ≤ 1`: three halvings, then `y − y³/3 + y⁵/5 − …` with `y < 0,1`. */
    private fun atanAtMostOne(a: HiDec, p: Int): HiDec {
        var y = a
        repeat(ATAN_HALVINGS) {
            val root = HiDec.ONE.add(y.mul(y, p), p).sqrt(p)
            y = y.div(HiDec.ONE.add(root, p), p)
        }
        if (y.isZero) return HiDec.ZERO
        val y2 = y.mul(y, p)
        var power = y
        var sum = y
        var k = 1
        do {
            power = power.mul(y2, p).negate()
            val term = power.divSmall(2 * k + 1, p)
            sum = sum.add(term, p)
            k++
        } while (!term.negligibleBeside(sum, p))
        return sum.mulSmall(ATAN_HALVING_FACTOR, p)
    }
}
