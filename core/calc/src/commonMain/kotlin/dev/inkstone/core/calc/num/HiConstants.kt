package dev.inkstone.core.calc.num

/**
 * The true π, ln 2 and ln 10, to far more digits than the calculator holds, each computed once on
 * first use — and the working precision every transcendental function in [DecMath] runs at.
 *
 * **Computed, not typed.** A wrong digit deep inside a 170-digit literal is invisible to a reader
 * and to every test that does not happen to land on it; a series has no such place to hide, and
 * `DecMathReferenceTest` checks all three against published digits.
 *
 * These are the *true* constants. Casio's own π and e — `3,14159265358980`, `2,71828182845904`,
 * the ones a user types — are [Dec.PI] and [Dec.E], and are never used for reduction: reducing
 * `sin` by a π that is wrong in the 15th digit would put that error into every large argument.
 */
internal object HiConstants {
    /**
     * Digits every transcendental function computes at before the single rounding to 15.
     * CALCULATOR.md §3 asks for at least 25 (research 07 T11); 42 leaves room for the ×1024 that
     * [HiExpLog.exp]'s squarings put on its error, and costs nothing a person could notice.
     */
    const val WORK = 42

    /** Extra digits each series carries over its caller's precision, against truncation drift. */
    const val GUARD = 6

    /**
     * π must cover a reduction of any `|x| < 10¹⁰⁰` (the calculator's whole range) to [WORK]
     * digits: 100 integer digits of the quotient, then [WORK] and the guard below them.
     */
    private const val PI_DIGITS = 170

    /**
     * ln 2 and ln 10 are multiplied by an integer before use — `n·ln 10` in [HiExpLog.exp] with
     * `|n|` up to about 100, `e·ln 10` in [HiExpLog.ln] with `|e| ≤ 99` — so they carry 20 digits
     * more than [WORK], which leaves the product's error far below the working precision.
     */
    private const val LOG_DIGITS = WORK + 20

    // Machin, 1706: π/4 = 4·atan(1/5) − atan(1/239), so π = 16·atan(1/5) − 4·atan(1/239).
    private const val MACHIN_SMALL = 5
    private const val MACHIN_LARGE = 239
    private const val MACHIN_SMALL_WEIGHT = 16
    private const val MACHIN_LARGE_WEIGHT = 4

    // ln 2 = 2·atanh(1/3); ln 10 = 3·ln 2 + ln(5/4), and ln(5/4) = 2·atanh(1/9).
    private const val LN2_ARGUMENT = 3
    private const val LN_FIVE_QUARTERS_ARGUMENT = 9
    private const val CUBE = 3
    private const val HALF_PI_NUMERATOR = 5

    val pi: HiDec by lazy {
        val p = PI_DIGITS + GUARD
        val a = inverseSeries(MACHIN_SMALL, p, alternating = true).mulSmall(MACHIN_SMALL_WEIGHT, p)
        val b = inverseSeries(MACHIN_LARGE, p, alternating = true).mulSmall(MACHIN_LARGE_WEIGHT, p)
        a.sub(b, p).truncate(PI_DIGITS)
    }

    val ln2: HiDec by lazy {
        val p = LOG_DIGITS + GUARD
        inverseSeries(LN2_ARGUMENT, p, alternating = false).mulSmall(2, p).truncate(LOG_DIGITS)
    }

    val ln10: HiDec by lazy {
        val p = LOG_DIGITS + GUARD
        val fiveQuarters = inverseSeries(LN_FIVE_QUARTERS_ARGUMENT, p, alternating = false).mulSmall(2, p)
        ln2.mulSmall(CUBE, p).add(fiveQuarters, p).truncate(LOG_DIGITS)
    }

    /** π/2 to [p] significant digits (at most [PI_DIGITS]); `π × 5 ÷ 10` is exact. */
    fun halfPi(p: Int): HiDec = pi.truncate(p + 1).mulSmall(HALF_PI_NUMERATOR, HiDec.EXACT).scale10(-1).truncate(p)

    /**
     * `Σ (±1)ᵏ / ((2k+1)·n^(2k+1))` — `atan(1/n)` when [alternating], else `atanh(1/n)`. Each
     * term costs two small divisions, which is why both constants are built from these.
     */
    private fun inverseSeries(n: Int, p: Int, alternating: Boolean): HiDec {
        val square = n * n
        var power = HiDec.ONE.divSmall(n, p)
        var sum = power
        var k = 1
        do {
            power = power.divSmall(square, p)
            val term = power.divSmall(2 * k + 1, p)
            sum = if (alternating && k % 2 == 1) sum.sub(term, p) else sum.add(term, p)
            k++
        } while (!term.negligibleBeside(sum, p))
        return sum
    }
}
