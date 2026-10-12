package dev.inkstone.core.calc.num

/**
 * `eˣ`, `ln x` and `ln(1 + u)` on [HiDec], by argument reduction and a short series — the
 * textbook route, chosen because each step's error is easy to bound, which is the whole job when
 * the answer must be right in the 15th digit (CALCULATOR.md §3, research 07 T11).
 *
 * Casio's own algorithms are unknown (research 07 §4.5: CORDIC or polynomial, never documented).
 * These compute the *true* function to ~[HiConstants.WORK] digits and let one rounding decide the
 * 15th, so any disagreement with the real calculator is the calculator's last-digit error, which
 * the guide puts at ±1 in the 10th digit (02 §8.2) and test T11 would catalogue.
 */
internal object HiExpLog {
    /** `eʳ = (e^(r/1024))^1024`: the series then needs about a dozen terms at 48 digits. */
    private const val HALVINGS = 10
    private const val HALVING_DIVISOR = 1024

    private val ONE_AND_A_HALF = HiDec.parse("1.5")
    private val THREE_QUARTERS = HiDec.parse("0.75")
    private val ROOT_TEN = HiDec.parse("3.16227766")
    private val HALF = HiDec.parse("0.5")
    private val TWO = HiDec.of(2L)

    /**
     * `eˣ` to [p] digits. Written `10ⁿ·eʳ` with `r = x − n·ln 10`, `|r| ≤ 1,16`, so the result's
     * exponent comes from `n` exactly and nothing overflows inside; the caller has already decided
     * over- and underflow (`|x|` stays well under 10⁶ here).
     */
    fun exp(x: HiDec, p: Int): HiDec {
        if (x.isZero) return HiDec.ONE
        val g = p + HiConstants.GUARD
        val ln10 = HiConstants.ln10
        val quotient = x.div(ln10, maxOf(x.lead, 0) + HiConstants.GUARD)
        val nAbs = quotient.roundAbs().toLongOrNull() ?: 0L
        val n = if (quotient.negative) -nAbs else nAbs
        val r = x.sub(HiDec.of(n).mul(ln10, HiDec.EXACT), g)
        var e = taylor(r.divSmall(HALVING_DIVISOR, g), g)
        repeat(HALVINGS) { e = e.mul(e, g) }
        return e.scale10(n.toInt()).truncate(p)
    }

    /**
     * `ln x` for `x > 0`, to [p] digits. `x = m·2ᵏ·10ᵉ` with `m ∈ [0,75; 1,5]`, then
     * `ln m = 2·atanh((m−1)/(m+1))`. The decade is chosen so `m` straddles 1 — `0,999999999999999`
     * is `0,999…·10⁰`, not `9,99…·10⁻¹` — so a value near 1 never subtracts two nearly equal logs.
     */
    fun ln(x: HiDec, p: Int): HiDec {
        require(x.signum > 0) { "ln of a non-positive value" }
        val g = p + HiConstants.GUARD
        var e = x.lead
        var m = x.scale10(-e)
        if (m > ROOT_TEN) {
            m = m.scale10(-1)
            e++
        }
        var k = 0
        while (m > ONE_AND_A_HALF) {
            m = m.divSmall(2, g)
            k++
        }
        while (m < THREE_QUARTERS) {
            m = m.mulSmall(2, g)
            k--
        }
        val z = m.sub(HiDec.ONE, g).div(m.add(HiDec.ONE, g), g)
        var result = atanhSeries(z, g).mulSmall(2, g)
        if (k != 0) result = result.add(HiDec.of(k.toLong()).mul(HiConstants.ln2, g), g)
        if (e != 0) result = result.add(HiDec.of(e.toLong()).mul(HiConstants.ln10, g), g)
        return result.truncate(p)
    }

    /**
     * `ln(1 + u)` for `u > −1`, accurate to [p] digits *relative to the result* even when `u` is
     * tiny — which `ln(1 + u)` written out is not, because `1 + u` drops `u`'s digits. asinh,
     * acosh and atanh are all built on this.
     */
    fun log1p(u: HiDec, p: Int): HiDec {
        val g = p + HiConstants.GUARD
        if (u.abs() >= HALF) return ln(u.add(HiDec.ONE, HiDec.EXACT), p)
        val z = u.div(u.add(TWO, g), g)
        return atanhSeries(z, g).mulSmall(2, g).truncate(p)
    }

    /** `z + z³/3 + z⁵/5 + …` for `|z| ≤ 1/3`. */
    private fun atanhSeries(z: HiDec, p: Int): HiDec {
        if (z.isZero) return HiDec.ZERO
        val z2 = z.mul(z, p)
        var power = z
        var sum = z
        var k = 1
        do {
            power = power.mul(z2, p)
            val term = power.divSmall(2 * k + 1, p)
            sum = sum.add(term, p)
            k++
        } while (!term.negligibleBeside(sum, p))
        return sum
    }

    /** `1 + s + s²/2! + …` for a small `s`. */
    private fun taylor(s: HiDec, p: Int): HiDec {
        var term = HiDec.ONE
        var sum = HiDec.ONE
        var k = 1
        do {
            term = term.mul(s, p).divSmall(k, p)
            sum = sum.add(term, p)
            k++
        } while (!term.negligibleBeside(sum, p))
        return sum
    }
}
