package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.parse.BinOp
import dev.inkstone.core.calc.parse.Binary
import dev.inkstone.core.calc.parse.Call
import dev.inkstone.core.calc.parse.Fn
import dev.inkstone.core.calc.parse.Fraction
import dev.inkstone.core.calc.parse.Negate
import dev.inkstone.core.calc.parse.Node
import dev.inkstone.core.calc.parse.NumberLit
import dev.inkstone.core.calc.value.DecimalReal
import dev.inkstone.core.calc.value.FractionReal
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.SurdReal
import dev.inkstone.core.calc.value.SurdTerm

/**
 * A value a mode already holds, written back as the nodes that denote it **exactly**, so the mode
 * can hand the [dev.inkstone.core.calc.eval.Evaluator] a formula over its own numbers — RATIO's
 * `a×d÷b` over its three coefficients — and get back what the calculator's arithmetic gives,
 * without parsing text or doing any arithmetic of its own.
 *
 * Exactly means the evaluator reads the same number back: an integer as its digits, a fraction as
 * `num⌟den`, a surd as its terms `(c×√r)⌟q`, and a decimal as the fraction of integers it *is*
 * (`1,5` → `15⌟10`) — which the evaluator keeps exact while the reduced fraction fits its record
 * and turns into a decimal when it does not (docs/CALCULATOR.md §3), so nothing here decides
 * exactness. A decimal too long or too small for that (more than 15 digits of denominator) is
 * written as the decimal literal it is, `d,ddd×10ⁿ`, and stays a decimal.
 *
 * Why decimals are written as fractions: RATIO shows `1,5 : 12,5 = 5 : X` as `X=` `125/3`
 * (research 05 EX-SA-RATIO-01, a VERIFIED capture), which is exact arithmetic on the typed
 * decimals; read as decimals they would give `41,66666667`, which the capture shows only after
 * S⇔D. ⚠️ GUESS that every mode that computes over held values does the same.
 */
object Literals {
    fun of(real: Real): Node = when (real) {
        is FractionReal -> signed(real.num < 0, ratio(magnitude(real.num), real.den))
        is DecimalReal -> signed(real.dec.isNegative, decimal(real.dec.abs()))
        is SurdReal -> real.terms.map(::term).reduce { sum, next -> Binary(BinOp.ADD, sum, next) }
    }

    /** `(c×√r)⌟q`, or `c⌟q` for a rational term (research 02 §8.6's term shape). */
    private fun term(term: SurdTerm): Node {
        val coefficient = NumberLit(magnitude(term.num).toString())
        val radical = if (term.radicand == 1L) {
            coefficient
        } else {
            Binary(BinOp.MUL, coefficient, Call(Fn.SQRT, listOf(NumberLit(term.radicand.toString()))))
        }
        val over = if (term.den == 1L) radical else Fraction(radical, NumberLit(term.den.toString()))
        return signed(term.num < 0, over)
    }

    private fun ratio(num: Long, den: Long): Node =
        if (den == 1L) NumberLit(num.toString()) else Fraction(NumberLit(num.toString()), NumberLit(den.toString()))

    /** A non-negative decimal: `D × 10^shift`, with `D` its significant digits. */
    private fun decimal(dec: Dec): Node {
        val digits = dec.significandDigits
        val shift = dec.exponent + 1 - digits.length
        return when {
            dec.isZero -> NumberLit("0")
            shift >= 0 && digits.length + shift <= Dec.MAX_DIGITS -> NumberLit(digits + "0".repeat(shift))
            shift < 0 && -shift < Dec.MAX_DIGITS -> Fraction(NumberLit(digits), NumberLit("1" + "0".repeat(-shift)))
            else -> NumberLit(digits.take(1), digits.drop(1).ifEmpty { null }, dec.exponent.toString())
        }
    }

    /** |n| for a held value, which is never `Long.MIN_VALUE` (a fraction holds at most 14 digits). */
    private fun magnitude(n: Long): Long = if (n < 0) -n else n

    private fun signed(negative: Boolean, magnitude: Node): Node = if (negative) Negate(magnitude) else magnitude
}
