package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.num.BigNat
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.num.DecMath

/**
 * `÷R`'s answer (research 02 §10.12). [quotient] is what goes on into a longer expression, to
 * `Ans` and to `STO` either way.
 */
sealed interface Division {
    val quotient: Real

    /** `5÷R2` → `2;R=1`. */
    data class WithRemainder(override val quotient: Real, val remainder: Real) : Division

    /** The operands were outside ÷R's range, so it was an ordinary `÷` (`−5÷R2` is `−5÷2`). */
    data class Ordinary(override val quotient: Real) : Division
}

/** `p^exponent`, one factor of a `FACT` display. */
data class PrimePower(val prime: Long, val exponent: Int)

/**
 * `FACT`'s answer: the prime powers found by trial division, smallest first, and the [leftover]
 * that has no prime factor of three digits or fewer — shown in parentheses, `2²×(1026169)`
 * (research 02 §10.13). [leftover] is 1 when the factorisation is complete.
 */
data class Factorization(val powers: List<PrimePower>, val leftover: Long)

/**
 * The integer functions: x!, nPr, nCr, GCD, LCM, ÷R and FACT (research 02 §10.11–10.13, ranges
 * from §13).
 *
 * Arguments are read by **value** ([Reals.toLongExact]): `5`, `10/2` and `5,0` are all the integer
 * 5, as the guide's ranges are stated ("x an integer"). A non-integer is Math ERROR. A result is
 * exact when every argument is exact, and a decimal when any is — the mixing rule of research 02
 * §8.5 carried over, a **GUESS** for these functions, invisible on the screen either way.
 */
object IntegerFunctions {
    private const val MAX_FACTORIAL = 69L
    private const val INTEGER_LIMIT = 10_000_000_000L
    private const val FACT_LIMIT = 9_999_999_999L
    private const val LARGEST_TRIAL_PRIME = 997
    private const val RESULT_DIGIT_LIMIT = 100

    private val PRIMES: List<Long> by lazy {
        (2..LARGEST_TRIAL_PRIME).filter { p -> (2 until p).none { p % it == 0 } }.map { it.toLong() }
    }

    /** `x!` for an integer `0 ≤ x ≤ 69` (research 02 §13); `(5+3)! = 40320` (04 E-36). */
    fun factorial(x: Real): Res<Real> {
        val n = Reals.toLongExact(x)
        if (n == null || n !in 0L..MAX_FACTORIAL) return Res.MATH
        val value = DecMath.factorial(n.toInt()) ?: return Res.MATH
        return integerResult(value, Reals.isExact(x))
    }

    /**
     * `nPr` for integers `0 ≤ n < 10¹⁰`, `0 ≤ r ≤ n`, with `n!/(n−r)! < 10¹⁰⁰` (research 02 §13):
     * `10P4 = 5040` (04 E-41).
     */
    fun permutations(n: Real, r: Real): Res<Real> {
        val (nn, rr) = choiceArguments(n, r) ?: return Res.MATH
        val product = fallingProduct(nn, rr) ?: return Res.MATH
        return integerResult(Dec.fromBig(false, product, 0L), Reals.isExact(n) && Reals.isExact(r))
    }

    /**
     * `nCr` with nPr's argument ranges: `10C4 = 210` (04 E-42). The guide's range is
     * `n!/r! < 10¹⁰⁰` **or** `n!/(n−r)! < 10¹⁰⁰`, i.e. the shorter falling product must stay in
     * range — so it is computed as that product divided by `k!`, and a combination whose own value
     * is small can still be Math ERROR because its numerator is not, as the range says.
     */
    fun combinations(n: Real, r: Real): Res<Real> {
        val (nn, rr) = choiceArguments(n, r) ?: return Res.MATH
        val k = minOf(rr, nn - rr)
        val product = fallingProduct(nn, k) ?: return Res.MATH
        var divisor = BigNat.ONE
        for (i in 2L..k) divisor *= BigNat.of(i)
        val value = Dec.fromBig(false, product.divRem(divisor).first, 0L)
        return integerResult(value, Reals.isExact(n) && Reals.isExact(r))
    }

    /** `GCD(a;b)` for integers `|a|, |b| < 10¹⁰` (research 02 §13): `GCD(28;35) = 7` (04 E-45). */
    fun gcd(a: Real, b: Real): Res<Real> {
        val x = integerIn(a, SIGNED_RANGE) ?: return Res.MATH
        val y = integerIn(b, SIGNED_RANGE) ?: return Res.MATH
        return integerResult(Dec.of(DecMath.gcd(x, y)), Reals.isExact(a) && Reals.isExact(b))
    }

    /** `LCM(a;b)` for integers `0 ≤ a, b < 10¹⁰` (research 02 §13): `LCM(9;15) = 45` (04 E-46). */
    fun lcm(a: Real, b: Real): Res<Real> {
        val x = integerIn(a, NATURAL_RANGE) ?: return Res.MATH
        val y = integerIn(b, NATURAL_RANGE) ?: return Res.MATH
        val g = DecMath.gcd(x, y)
        val value = if (g == 0L) Dec.ZERO else Dec.of(x / g) * Dec.of(y)
        return integerResult(value, Reals.isExact(a) && Reals.isExact(b))
    }

    /**
     * `a ÷R b` (research 02 §10.12, 06 §5.6): quotient and remainder, `75÷R4 = 18;R=3`
     * (04 H-03). It falls back to an ordinary division when an operand is "very large" or the
     * quotient or remainder would not be positive (`−5÷R2` is `−5÷2`, 04 C-24). Both thresholds
     * are **GUESSES** (research 00 §5 #12): "very large" is read as `|x| ≥ 10¹⁰`, the ten-digit
     * limit of every other integer function (so `20000000000÷R17` divides, 04 C-23), and
     * "positive" as "not negative", so `6÷R3` is `2;R=0` and `2÷R5` is `0;R=2`. Fractions are
     * accepted and give a fractional remainder (`7/2 ÷R 1 = 3;R=1/2`), the guide's "positive
     * fractional value"; a surd operand divides ordinarily. `÷0` is Math ERROR.
     */
    fun divideWithRemainder(a: Real, b: Real): Res<Division> = when {
        b.dec.isZero -> Res.MATH
        remainderOperand(a) && remainderOperand(b) -> withRemainder(a, b)
        else -> RealArith.divide(a, b).map { Division.Ordinary(it) }
    }

    /**
     * `q = ⌊a/b⌋` and `r = a − q·b`. The quotient a/b was rounded before the floor, so the first
     * guess can be one out either way; the neighbour whose remainder lands in `[0, b)` is the one.
     */
    private fun withRemainder(a: Real, b: Real): Res<Division> {
        val exact = Reals.isExact(a) && Reals.isExact(b)
        val guess = (a.dec / b.dec).floor()
        for (q in listOf(guess, guess - Dec.ONE, guess + Dec.ONE).map { wholeNumber(it, exact) }) {
            val r = RealArith.multiply(q, b).then { RealArith.subtract(a, it) }.valueOr { return it }
            if (!r.dec.isNegative && r.dec < b.dec) return Res.Ok(Division.WithRemainder(q, r))
        }
        return RealArith.divide(a, b).map { Division.Ordinary(it) }
    }

    /**
     * `FACT` on a positive integer of at most ten digits (research 02 §10.13, 06 §5.7): trial
     * division by the primes up to 997, and whatever is left over — prime or not — goes in
     * parentheses: `1014 = 2×3×13²`, `4104676 = 2²×(1026169)` although `1026169 = 1013²`.
     * Math ERROR for zero, a negative, a fraction or a decimal with a fractional part; an
     * integer-valued decimal is accepted, a **GUESS** (the guide's "decimal" reads as "not an
     * integer").
     */
    fun factorize(x: Real): Res<Factorization> {
        var n = Reals.toLongExact(x) ?: return Res.MATH
        if (n !in 1L..FACT_LIMIT) return Res.MATH
        val powers = mutableListOf<PrimePower>()
        for (p in PRIMES) {
            var exponent = 0
            while (n % p == 0L) {
                n /= p
                exponent++
            }
            if (exponent > 0) powers += PrimePower(p, exponent)
        }
        return Res.Ok(Factorization(powers, n))
    }

    private fun choiceArguments(n: Real, r: Real): Pair<Long, Long>? {
        val nn = Reals.toLongExact(n) ?: return null
        val rr = Reals.toLongExact(r) ?: return null
        return if (nn in 0L until INTEGER_LIMIT && rr in 0L..nn) nn to rr else null
    }

    /** `n(n−1)…(n−k+1)` exactly, or null once it reaches 10¹⁰⁰ (so the loop stays short). */
    private fun fallingProduct(n: Long, k: Long): BigNat? {
        var product = BigNat.ONE
        for (i in 0L until k) {
            product *= BigNat.of(n - i)
            if (product.digitCount > RESULT_DIGIT_LIMIT) return null
        }
        return product
    }

    /** [x]'s integer value when it lies in [range], else null. */
    private fun integerIn(x: Real, range: LongRange): Long? = Reals.toLongExact(x)?.takeIf { it in range }

    /** Not a surd, not negative, not "very large" (see [divideWithRemainder]). */
    private fun remainderOperand(x: Real): Boolean = x !is SurdReal && !x.dec.isNegative && x.dec < LARGE

    private fun wholeNumber(d: Dec, exact: Boolean): Real =
        if (exact) Reals.integerOrDecimal(d).getOrNull() ?: DecimalReal(d) else DecimalReal(d)

    private fun integerResult(value: Dec, exact: Boolean): Res<Real> =
        if (exact) Reals.integerOrDecimal(value) else Reals.decimal(value)

    private val LARGE = Dec.of(INTEGER_LIMIT)
    private val SIGNED_RANGE = (1L - INTEGER_LIMIT) until INTEGER_LIMIT
    private val NATURAL_RANGE = 0L until INTEGER_LIMIT
}
