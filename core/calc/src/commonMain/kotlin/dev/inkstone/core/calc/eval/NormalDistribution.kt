package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.num.DecMath

/**
 * STAT's standard-normal `P(t)`, `Q(t)`, `R(t)` (research 03 §4.4) the way the ES PLUS ROM
 * computes them: the Zelen–Severo polynomial of Abramowitz & Stegun 26.2.17 with the **ROM's**
 * coefficients (research 07 §4.8), which differ from the textbook's in a₁ and a₃ — so `P(1)` is
 * `0,8413445602`, not the true `0,8413447461`. docs/CALCULATOR.md §3 chose the ROM set because a
 * faithful copy is the goal; DIST mode (not this) uses accurate algorithms.
 *
 * Computed in the calculator's own 15-digit arithmetic, every step rounded once.
 */
internal object NormalDistribution {
    private val P = constant("0.2316419")

    /** a₅ … a₁, for Horner's rule (research 07 §4.8, as transcribed from the ROM). */
    private val COEFFICIENTS = listOf("1.330274429", "-1.821255978", "1.78147937", "-0.356563782", "0.319381503")
        .map(::constant)

    /** 1/√(2π), to 15 digits. */
    private val DENSITY_SCALE = constant("0.398942280401433")

    /** Past this the tail is far below 1×10⁻⁹⁹ (e^(−t²/2) at t = 22 is already 10⁻¹⁰⁶). */
    private val TAIL_LIMIT = Dec.of(TAIL_LIMIT_T)

    private val HALF = Dec.HALF

    /** `P(t)`, the area from −∞ to t. */
    fun lower(t: Dec): Dec {
        val tail = upperTail(t.abs())
        return if (t.isNegative) tail else Dec.ONE - tail
    }

    /**
     * `R(t)`, the area from t to +∞: for t ≥ 0 the polynomial's tail itself, so `R(t)` is exactly
     * `P(−t)`, as the polynomial is built to be. Taken as `1 − P(t)` it kept only the digits P had
     * to spare — `R(8)` was 1×10⁻¹⁵ rather than 6,28×10⁻¹⁶, and `R(9)` was 0 (a review finding).
     */
    fun upper(t: Dec): Dec {
        val tail = upperTail(t.abs())
        return if (t.isNegative) Dec.ONE - tail else tail
    }

    /**
     * `Q(t)`, the area between 0 and t — taken as a positive area for t < 0 too, which is what
     * the guide's shaded illustration shows; its sign there is UNKNOWN (research 03 §4.4), so a
     * **GUESS**.
     */
    fun central(t: Dec): Dec = (lower(t) - HALF).abs()

    /** The area beyond u ≥ 0: φ(u)·(a₁k + a₂k² + … + a₅k⁵), k = 1/(1 + p·u). */
    private fun upperTail(u: Dec): Dec {
        if (u >= TAIL_LIMIT) return Dec.ZERO
        val k = Dec.ONE / (Dec.ONE + P * u)
        var poly = Dec.ZERO
        for (a in COEFFICIENTS) poly = (poly + a) * k
        val density = (DecMath.exp(-(u * u) * HALF) ?: Dec.ZERO) * DENSITY_SCALE
        return density * poly
    }

    private fun constant(text: String): Dec = Dec.parse(text) ?: error("bad constant $text")

    private const val TAIL_LIMIT_T = 40
}
