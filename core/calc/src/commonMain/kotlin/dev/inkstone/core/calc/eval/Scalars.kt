package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.input.ScientificConstants
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.parse.Const
import dev.inkstone.core.calc.parse.Dms
import dev.inkstone.core.calc.parse.MatrixRef
import dev.inkstone.core.calc.parse.Node
import dev.inkstone.core.calc.parse.NumberLit
import dev.inkstone.core.calc.parse.StatVar
import dev.inkstone.core.calc.parse.Sym
import dev.inkstone.core.calc.parse.Symbolic
import dev.inkstone.core.calc.parse.Var
import dev.inkstone.core.calc.parse.VectorRef
import dev.inkstone.core.calc.value.ComplexMath
import dev.inkstone.core.calc.value.ComplexValue
import dev.inkstone.core.calc.value.DecimalReal
import dev.inkstone.core.calc.value.Reals
import dev.inkstone.core.calc.value.Value
import dev.inkstone.core.calc.value.getOrNull
import dev.inkstone.core.calc.value.Dms as Sexagesimal

/** An exponent past an Int is far past 10¹⁰⁰ or below 10⁻⁹⁹ either way; this is plenty. */
private const val EXPONENT_CLAMP = 10_000

/** Ran# draws one of 0,000 … 0,999 (research 02 §10.11). */
private const val RAN_STEPS = 1000

/**
 * A typed number (research 02 §7.1, §8.5; docs/CALCULATOR.md §3):
 * - no comma: an integer, exact while it fits the record (13 digits), else the same value as a
 *   decimal. `2×10³` is the exact 2000; `1×10⁻³` is not a whole number, so it is a decimal — a
 *   typed decimal stays a decimal, which is docs/CALCULATOR.md §3's rule applied to the exponent;
 * - a comma: always a decimal, `0,5` included — decimals do not become fractions by themselves;
 * - a bare `×10³` has an implied mantissa of 1 (the parser's **GUESS**, Node.kt).
 * Past ±1×10¹⁰⁰ is Math ERROR; below 1×10⁻⁹⁹ is zero ([Dec]'s rule).
 */
internal fun Session.number(node: NumberLit): Ev<Value> {
    val whole = if (node.intDigits.isEmpty() && node.fracDigits == null) "1" else node.intDigits
    val dec = Dec.fromDigits(false, whole + node.fracDigits.orEmpty(), whole.length, exponentOf(node.exponent))
    val real = if (node.fracDigits == null) Reals.integerOrDecimal(dec) else Reals.decimal(dec)
    return real.at(node)
}

private fun exponentOf(text: String?): Int {
    if (text == null) return 0
    return text.toIntOrNull() ?: if (text.startsWith("-")) -EXPONENT_CLAMP else EXPONENT_CLAMP
}

/** Variables, Ans, the named constants, STAT variables and the matrix/vector slots. */
internal fun Session.named(node: Node): Ev<Value> = when (node) {
    is Var -> stored(memory.variables[node.variable] ?: Memory.ZERO)

    is Sym -> symbol(node)

    is Const -> constant(node)

    // No data, or a statistic undefined for the data, is Math ERROR (StatisticsSource's contract).
    // A statistic is computed, not typed, so STAT's linear display shows it as a decimal (x̄ of
    // {3;4} is 3,5) however exactly the source worked it out.
    is StatVar -> present(context.statistics?.value(node.stat), CalcError.MATH, node).map { line(it) }

    // An undefined matrix or vector is Dimension ERROR (research 03 §7.4).
    is MatrixRef -> present(memory.matrices[node.name], CalcError.DIMENSION, node)

    is VectorRef -> present(memory.vectors[node.name], CalcError.DIMENSION, node)

    else -> failAt(CalcError.SYNTAX, node)
}

private fun present(value: Value?, missing: CalcError, node: Node): Ev<Value> =
    if (value == null) failAt(missing, node) else Ev.Ok(value)

/**
 * A remembered value as this mode reads it. A complex number left in a variable by CMPLX is read
 * as its real part elsewhere — a **GUESS**; the guide only says the memories survive a mode change
 * (research 02 §9).
 */
private fun Session.stored(value: Value): Ev<Value> =
    Ev.Ok(if (value is ComplexValue && mode != Mode.CMPLX) value.re else value)

/**
 * π and e are Casio's 15-digit values (3,14159265358980 and 2,71828182845904, research 07 §4.2);
 * PreAns is the result before Ans (research 02 §9); `i` is CMPLX's unit.
 */
private fun Session.symbol(node: Sym): Ev<Value> = when (node.symbol) {
    Symbolic.ANS -> stored(memory.ans)
    Symbolic.PRE_ANS -> stored(memory.preAns)
    Symbolic.PI -> Ev.Ok(DecimalReal(Dec.PI))
    Symbolic.E -> Ev.Ok(DecimalReal(Dec.E))
    Symbolic.I -> Ev.Ok(ComplexMath.I)
    Symbolic.RAN -> Ev.Ok(randomFraction())
}

/**
 * `Ran#`: three random decimal places, "shown as a fraction in Natural Display" (research 02
 * §10.11) — so an exact fraction like `317/500` in MathO, and the decimal `0,634` in linear output,
 * where the guide shows Ran# results as plain numbers (04 E-39).
 */
private fun Session.randomFraction(): Value {
    val draw = context.random.nextInt(RAN_STEPS).toLong()
    // A denominator of 1000 is never zero and never past the record, so this cannot fail.
    val exact = Reals.fraction(draw, RAN_STEPS.toLong()).getOrNull() ?: Memory.ZERO
    return lineReal(exact)
}

/** CONST nn: CODATA 2014 at its published digits (research 03 §14), as a decimal. */
private fun constant(node: Const): Ev<Value> {
    val text = ScientificConstants.get(node.number)?.value ?: return failAt(CalcError.SYNTAX, node)
    val value = Dec.parse(text) ?: return failAt(CalcError.SYNTAX, node)
    return Ev.Ok(DecimalReal(value))
}

/**
 * `a°b'c"` (research 02 §8.9): degrees may be any value, minutes and seconds are typed numbers.
 * The value is a decimal flagged for sexagesimal display.
 */
internal fun Session.sexagesimal(node: Dms): Ev<Value> {
    val degrees = real(node.degrees).orElse { return it }
    val minutes = node.minutes?.let { real(it).orElse { e -> return e } }
    val seconds = node.seconds?.let { real(it).orElse { e -> return e } }
    return Sexagesimal.of(degrees, minutes, seconds).at(node)
}
