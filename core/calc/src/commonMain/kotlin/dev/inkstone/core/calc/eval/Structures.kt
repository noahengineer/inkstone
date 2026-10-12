package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.input.MetricConversions
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.parse.Convert
import dev.inkstone.core.calc.parse.Fraction
import dev.inkstone.core.calc.parse.MixedFraction
import dev.inkstone.core.calc.parse.Node
import dev.inkstone.core.calc.parse.Power
import dev.inkstone.core.calc.parse.Root
import dev.inkstone.core.calc.value.ComplexMath
import dev.inkstone.core.calc.value.ComplexValue
import dev.inkstone.core.calc.value.DecimalReal
import dev.inkstone.core.calc.value.FractionReal
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.RealArith
import dev.inkstone.core.calc.value.Reals
import dev.inkstone.core.calc.value.Res
import dev.inkstone.core.calc.value.Roots
import dev.inkstone.core.calc.value.Value
import dev.inkstone.core.calc.value.then

private val THIRTY_TWO: Real = FractionReal(32, 1)

/** Powers, roots and fractions: the level-3 and level-4 constructs that carry two operands. */
internal fun Session.structured(node: Node): Ev<Value> = when (node) {
    is Power -> power(node)
    is Root -> root(node)
    is Fraction -> fraction(node)
    is MixedFraction -> mixed(node)
    else -> failAt(CalcError.SYNTAX, node)
}

/**
 * `x^□` (research 02 §10.6, ranges §13): [RealArith.pow] for reals; a complex base takes an
 * integer power only, anything else is Math ERROR (a **GUESS** — the guide never raises a
 * complex number to a non-integer); a matrix cannot be raised with `x^□` at all (research 03 §7.1:
 * "x^□ cannot be used") — Syntax ERROR. In linear output ([powerForm]) a power with a
 * non-negative integer exponent is repeated multiplication and keeps a fraction exact, a negative
 * integer one is a division ([Session.lineQuotient]), and any other goes through [Session.line]
 * like the other functions.
 */
private fun Session.power(node: Power): Ev<Value> {
    val base = scalar(node.base).orElse { return it }
    val exponent = real(node.exponent).orElse { return it }
    val result = if (base is ComplexValue) {
        complexPower(node, base, exponent)
    } else {
        RealArith.pow(base as Real, exponent).at(node)
    }
    return result.map { settle(powerForm(it, base, exponent)) }
}

/** Repeated multiplication stays exact; a negative integer power is a division; the rest is a function. */
private fun Session.powerForm(value: Value, base: Value, exponent: Real): Value = when {
    exponent !is FractionReal || exponent.den != 1L -> line(value)
    exponent.num >= 0L -> value
    else -> lineQuotient(value, base)
}

private fun complexPower(node: Power, base: ComplexValue, exponent: Real): Ev<Value> {
    val n = Reals.toLongExact(exponent) ?: return failAt(CalcError.MATH, node)
    return ComplexMath.powInt(base, n).at(node)
}

/** `ⁿ√` / `ˣ√(` (research 02 §13 for the index rules, which [Roots.root] holds). */
private fun Session.root(node: Root): Ev<Value> {
    val index = real(node.index).orElse { return it }
    val radicand = real(node.radicand).orElse { return it }
    return Roots.root(index, radicand).at(node).map { line(it) }
}

/**
 * A fraction as typed — the `FRAC` template or LineIO `a⌟b` — is a division that stays exact in
 * every display: `1−4⌟5` = `1⌟5` in LineIO (research 02 §8.5, 04 C-04), while `1÷5` there is
 * `0,2`. Complex numerators and denominators divide as complex numbers.
 */
private fun Session.fraction(node: Fraction): Ev<Value> {
    val numerator = scalar(node.numerator).orElse { return it }
    val denominator = scalar(node.denominator).orElse { return it }
    if (numerator is Real && denominator is Real) return RealArith.divide(numerator, denominator).at(node)
    return ComplexMath.divide(complexOf(numerator), complexOf(denominator)).at(node).map(::settle)
}

/**
 * `a b⁄c`: the whole number plus the fraction, the whole number's sign applying to both, as a
 * mixed number is read (`(−)` typed before the template negates the finished value anyway,
 * research 02 §12 level 5 binds looser than level 4). `4−3⌟1⌟2` = `1⌟2` (04 C-09).
 */
private fun Session.mixed(node: MixedFraction): Ev<Value> {
    val whole = real(node.whole).orElse { return it }
    val numerator = real(node.numerator).orElse { return it }
    val denominator = real(node.denominator).orElse { return it }
    val part = RealArith.divide(numerator, denominator).at(node).orElse { return it }
    val sum = if (whole.dec.isNegative) RealArith.subtract(whole, part) else RealArith.add(whole, part)
    return sum.at(node)
}

/**
 * `SHIFT 8` nn (research 02 §10.16, 03 §15): one stored factor per pair, multiplied in one
 * direction and divided in the other; °F/°C are affine. The factor is used **exactly** — `3,6`
 * is 18/5 — which is what makes `120km/h▶m/s` the fraction `100/3` in MathO, whose `S⇔D` is
 * `33,33333333` (research 04 H-08, INFERRED there from the key sequence). In linear output the
 * result then follows [Session.line], so `5cm▶in` is `1,968503937` (04 E-53).
 */
internal fun Session.convert(node: Convert): Ev<Value> {
    val entry = MetricConversions.get(node.number) ?: return failAt(CalcError.SYNTAX, node)
    val factor = exactFactor(entry.factor) ?: return failAt(CalcError.SYNTAX, node)
    val x = real(node.operand).orElse { return it }
    val result: Res<Real> = when (entry.op) {
        MetricConversions.Op.MULTIPLY -> RealArith.multiply(x, factor)

        MetricConversions.Op.DIVIDE -> RealArith.divide(x, factor)

        MetricConversions.Op.FAHRENHEIT_TO_CELSIUS ->
            RealArith.subtract(x, THIRTY_TWO).then { RealArith.divide(it, factor) }

        MetricConversions.Op.CELSIUS_TO_FAHRENHEIT ->
            RealArith.multiply(x, factor).then { RealArith.add(it, THIRTY_TWO) }
    }
    return result.at(node).map { line(it) }
}

/** A conversion factor's exact value, or its decimal when no fraction record holds it (pc▶km). */
private fun exactFactor(text: String): Real? {
    val dec = Dec.parse(text) ?: return null
    return Reals.rationalValue(dec)?.let(Reals::fractionOrNull) ?: DecimalReal(dec)
}
