package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.AngleUnit
import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.parse.Call
import dev.inkstone.core.calc.parse.Fn
import dev.inkstone.core.calc.value.ComplexMath
import dev.inkstone.core.calc.value.ComplexValue
import dev.inkstone.core.calc.value.Coordinates
import dev.inkstone.core.calc.value.IntegerFunctions
import dev.inkstone.core.calc.value.MatrixMath
import dev.inkstone.core.calc.value.MatrixValue
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.RealArith
import dev.inkstone.core.calc.value.Reals
import dev.inkstone.core.calc.value.Res
import dev.inkstone.core.calc.value.Roots
import dev.inkstone.core.calc.value.RowReduction
import dev.inkstone.core.calc.value.Transcendental
import dev.inkstone.core.calc.value.Trig
import dev.inkstone.core.calc.value.Value
import dev.inkstone.core.calc.value.VectorMath
import dev.inkstone.core.calc.value.VectorValue

/**
 * The one-argument functions of a real number, with the angle unit for the ones that use it.
 * Their ranges and exact special values are the value package's (research 02 §13, 07 §4.4).
 */
private val REAL_FUNCTIONS: Map<Fn, (Real, AngleUnit) -> Res<Real>> = mapOf(
    Fn.SIN to Trig::sin,
    Fn.COS to Trig::cos,
    Fn.TAN to Trig::tan,
    Fn.ASIN to Trig::asin,
    Fn.ACOS to Trig::acos,
    Fn.ATAN to Trig::atan,
    Fn.SINH to { x, _ -> Transcendental.sinh(x) },
    Fn.COSH to { x, _ -> Transcendental.cosh(x) },
    Fn.TANH to { x, _ -> Transcendental.tanh(x) },
    Fn.ASINH to { x, _ -> Transcendental.asinh(x) },
    Fn.ACOSH to { x, _ -> Transcendental.acosh(x) },
    Fn.ATANH to { x, _ -> Transcendental.atanh(x) },
    Fn.LN to { x, _ -> Transcendental.ln(x) },
    Fn.TEN_POW to { x, _ -> Transcendental.tenPow(x) },
    Fn.EXP to { x, _ -> Transcendental.exp(x) },
    Fn.CBRT to { x, _ -> Roots.cbrt(x) },
)

/** RanInt#'s operands and their difference stay below 1×10¹⁰ (research 02 §13). */
private const val RANDOM_LIMIT = 10_000_000_000L

/**
 * A function call (research 02 §12 level 2). Every result that has an exact form only Natural
 * display can show goes through [Session.line]. `Pol` / `Rec` stand only as a whole statement
 * ([Session.statement]); `Not` / `Neg` are BASE-N's ([BaseN]); a call reaching here in the wrong
 * mode has already been refused by [Restrictions].
 */
internal fun Session.call(node: Call): Ev<Value> {
    REAL_FUNCTIONS[node.fn]?.let { f -> return realFunction(node) { f(it, unit) } }
    return when (node.fn) {
        Fn.LOG -> logarithm(node)
        Fn.SQRT -> squareRoot(node)
        Fn.ABS -> absolute(node)
        Fn.RND -> real(node.args[0]).then { Coordinates.rnd(it, setup.format).at(node) }
        Fn.GCD, Fn.LCM -> greatestOrLeast(node)
        Fn.RAN_INT -> randomInteger(node)
        Fn.ARG, Fn.CONJG -> complexFunction(node)
        Fn.DET, Fn.TRN, Fn.REF, Fn.RREF -> matrixFunction(node)
        Fn.DIST_P, Fn.DIST_Q, Fn.DIST_R -> distribution(node)
        else -> failAt(CalcError.SYNTAX, node)
    }
}

private inline fun Session.realFunction(node: Call, f: (Real) -> Res<Real>): Ev<Value> =
    real(node.args[0]).then { f(it).at(node) }.map { line(it) }

/** `log x` (base 10) and `log(a;b)` / `log▪▯`, base first (research 02 §10.6). */
private fun Session.logarithm(node: Call): Ev<Value> {
    val first = real(node.args[0]).orElse { return it }
    if (node.args.size == 1) return Transcendental.log10(first).at(node).map { line(it) }
    val x = real(node.args[1]).orElse { return it }
    return Transcendental.log(first, x).at(node).map { line(it) }
}

/**
 * `√`: a surd where it can be (research 02 §8.6). In CMPLX a negative number or a complex one
 * has a complex root, `√(−4)` = `2i` — a **GUESS** the guide does not settle (research 00 §5 #14).
 */
private fun Session.squareRoot(node: Call): Ev<Value> {
    val x = scalar(node.args[0]).orElse { return it }
    val complex = x is ComplexValue || (mode == Mode.CMPLX && x is Real && x.dec.isNegative)
    if (complex) return ComplexMath.sqrt(complexOf(x)).at(node).map { settle(line(it)) }
    return Roots.sqrt(x as Real).at(node).map { line(it) }
}

/**
 * `Abs`: the absolute value of a real (exact stays exact — it only drops a sign, so a typed
 * fraction stays one in linear output too), each cell of a matrix (EX-MAT-08); the modulus of a
 * complex number (`Abs(1+i)` = `√2`, 05 EX-CMPLX-07) and the length of a vector (EX-VEC-06),
 * which are square roots and go through [Session.line].
 */
private fun Session.absolute(node: Call): Ev<Value> {
    val x = eval(node.args[0]).orElse { return it }
    return when (x) {
        is Real -> Ev.Ok(RealArith.abs(x))
        is MatrixValue -> Ev.Ok(MatrixMath.abs(x))
        is ComplexValue -> ComplexMath.abs(x).at(node).map { line(it) }
        is VectorValue -> VectorMath.magnitude(x).at(node).map { line(it) }
    }
}

private fun Session.greatestOrLeast(node: Call): Ev<Value> {
    val a = real(node.args[0]).orElse { return it }
    val b = real(node.args[1]).orElse { return it }
    return (if (node.fn == Fn.GCD) IntegerFunctions.gcd(a, b) else IntegerFunctions.lcm(a, b)).at(node)
}

/**
 * `RanInt#(a;b)`: a random integer from a to b inclusive, for integers with `a < b`,
 * `|a|, |b| < 1×10¹⁰` and `b − a < 1×10¹⁰` (research 02 §13); anything else is Math ERROR, the
 * guide's error for an input out of range (02 §11).
 */
private fun Session.randomInteger(node: Call): Ev<Value> {
    val a = real(node.args[0]).orElse { return it }
    val b = real(node.args[1]).orElse { return it }
    val low = Reals.toLongExact(a)
    val high = Reals.toLongExact(b)
    if (low == null || high == null || !randomRange(low, high)) return failAt(CalcError.MATH, node)
    return Ev.Ok(Reals.of(context.random.nextLong(low, high + 1)))
}

private fun randomRange(low: Long, high: Long): Boolean =
    low < high && low > -RANDOM_LIMIT && high < RANDOM_LIMIT && high - low < RANDOM_LIMIT

/** CMPLX's `arg(` (in the current unit, `arg(1+i)` = 45, 05 EX-CMPLX-08) and `Conjg(`. */
private fun Session.complexFunction(node: Call): Ev<Value> {
    val z = complexOf(scalar(node.args[0]).orElse { return it })
    if (node.fn == Fn.CONJG) return Ev.Ok(settle(ComplexMath.conjugate(z)))
    return ComplexMath.arg(z, unit).at(node).map { line(it) }
}

/**
 * MATRIX's `det(`, `Trn(`, `Ref(`, `Rref(` (research 03 §7.1). The argument must be a matrix;
 * anything else is Syntax ERROR (a **GUESS**). `Trn` only moves cells, so nothing about their form
 * changes; the others compute, and in linear output give decimals ([Session.line]) — `Ref` of
 * research 05 EX-MAT-10 shows `1,1428` for 8/7.
 */
private fun Session.matrixFunction(node: Call): Ev<Value> {
    val m = eval(node.args[0]).orElse { return it } as? MatrixValue ?: return failAt(CalcError.SYNTAX, node)
    if (node.fn == Fn.TRN) return Ev.Ok(MatrixMath.transpose(m))
    val result: Res<Value> = when (node.fn) {
        Fn.DET -> MatrixMath.determinant(m)
        Fn.REF -> RowReduction.ref(m)
        else -> RowReduction.rref(m)
    }
    return result.at(node).map { line(it) }
}

/** STAT's `P(`, `Q(`, `R(` with the ROM's polynomial ([NormalDistribution]). */
private fun Session.distribution(node: Call): Ev<Value> = real(node.args[0]).then { t ->
    val p: Dec = when (node.fn) {
        Fn.DIST_P -> NormalDistribution.lower(t.dec)
        Fn.DIST_Q -> NormalDistribution.central(t.dec)
        else -> NormalDistribution.upper(t.dec)
    }
    Reals.decimal(p).at(node)
}
