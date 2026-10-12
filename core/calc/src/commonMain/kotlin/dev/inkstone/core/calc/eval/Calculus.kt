package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.parse.Derivative
import dev.inkstone.core.calc.parse.Integral
import dev.inkstone.core.calc.parse.Node
import dev.inkstone.core.calc.parse.Summation
import dev.inkstone.core.calc.parse.Variable
import dev.inkstone.core.calc.value.DecimalReal
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.RealArith
import dev.inkstone.core.calc.value.Reals
import dev.inkstone.core.calc.value.Value

/** Σ's bounds: integers with −1×10¹⁰ < a ≤ b < 1×10¹⁰ (research 02 §10.9). */
private val SUM_BOUNDS = -9_999_999_999L..9_999_999_999L

/**
 * ∫, d/dx and Σ (research 02 §10.7–10.9). What they share:
 * - COMP only, and none may sit inside another; Pol, Rec and ÷R may not sit in f(x) — both
 *   refused before evaluation ([Restrictions]);
 * - the limits (and tol) are evaluated first, with X as it was, then f is evaluated with X set to
 *   each sample point, and **X is restored afterwards**, whatever happened (research 07 §4.6:
 *   "the parser saves X before calling ∫, d/dx or Σ and writes it back");
 * - AC breaks them ([Session.interrupted], polled per sample).
 */
internal fun Session.calculus(node: Node): Ev<Value> = when (node) {
    is Integral -> integral(node)
    is Derivative -> derivative(node)
    is Summation -> summation(node)
    else -> failAt(CalcError.SYNTAX, node)
}

/**
 * f([x]) as a decimal, X set to [x]: the one way the calculus routines sample a function. Stops
 * when AC was pressed.
 */
internal fun Session.sample(f: Node, x: Dec): Ev<Dec> {
    if (interrupted()) return Ev.Stop
    memory.variables[Variable.X] = DecimalReal(x)
    return real(f).map { it.dec }
}

/** Runs [block] and puts X back as it was, on every path out (research 07 §4.6). */
internal inline fun <T> Session.keepingX(block: () -> T): T {
    val saved = memory.variables[Variable.X] ?: Memory.ZERO
    try {
        return block()
    } finally {
        memory.variables[Variable.X] = saved
    }
}

/**
 * A tolerance typed in LineIO (research 02 §10.7–10.8), or null when none was. A negative one is
 * Argument ERROR (research 07 §4.6, INFERRED from the ROM's sign check).
 */
private fun Session.tolerance(tol: Node?): Ev<Dec?> {
    if (tol == null) return Ev.Ok(null)
    val value = real(tol).orElse { return it }.dec
    return if (value.isNegative) failAt(CalcError.ARGUMENT, tol) else Ev.Ok(value)
}

/**
 * `∫ₐᵇ f(x)dx` by adaptive Gauss–Kronrod ([Quadrature]); default tol 1×10⁻⁵ (research 02 §10.7).
 * The answer is a decimal — the calculator then shows `π` for ∫₀^π (sin x + cos x)² dx by
 * recognising it (04 E-27), which is the formatter's business.
 */
private fun Session.integral(node: Integral): Ev<Value> {
    val a = real(node.lower).orElse { return it }.dec
    val b = real(node.upper).orElse { return it }.dec
    val tol = tolerance(node.tol).orElse { return it } ?: Quadrature.DEFAULT_TOL
    val area = keepingX { Quadrature(this, node).integrate(a, b, tol) }.orElse { return it }
    return Reals.decimal(area).at(node)
}

/**
 * `d/dx(f(x))|ₓ₌ₐ` by central differences with Richardson extrapolation ([Differentiation]);
 * default tol 1×10⁻¹⁰, which is relaxed when it cannot be met, as the guide says it is when tol
 * is omitted (research 02 §10.8).
 */
private fun Session.derivative(node: Derivative): Ev<Value> {
    val at = real(node.at).orElse { return it }.dec
    val tol = tolerance(node.tol).orElse { return it }
    val slope = keepingX { Differentiation(this, node).at(at, tol) }.orElse { return it }
    return Reals.decimal(slope).at(node)
}

/**
 * `Σ(f;a;b)` = f(a) + f(a+1) + … + f(b) (research 02 §10.9), added in the calculator's own
 * arithmetic, so a sum of exact terms is exact: Σ(x+1) from 1 to 5 is `20` (04 E-31). Bounds that
 * are not integers, or out of range or order, are Math ERROR (a **GUESS**: the guide states the
 * range and no error).
 */
private fun Session.summation(node: Summation): Ev<Value> {
    val a = bound(node.lower).orElse { return it }
    val b = bound(node.upper).orElse { return it }
    if (a > b) return failAt(CalcError.MATH, node)
    return keepingX { sum(node, a, b) }
}

private fun Session.bound(node: Node): Ev<Long> {
    val x = real(node).orElse { return it }
    return Reals.toLongExact(x)?.takeIf { it in SUM_BOUNDS }?.let { Ev.Ok(it) } ?: failAt(CalcError.MATH, node)
}

private fun Session.sum(node: Summation, a: Long, b: Long): Ev<Value> {
    var total: Real = Reals.ZERO
    for (i in a..b) {
        if (interrupted()) return Ev.Stop
        memory.variables[Variable.X] = Reals.of(i)
        val term = real(node.f).orElse { return it }
        total = RealArith.add(total, term).at(node).orElse { return it }
    }
    return Ev.Ok(total)
}
