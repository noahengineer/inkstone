package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.AngleUnit

/**
 * CMPLX mode's arithmetic (research 03 §3, 07 §4.5's complex routines). Each part is a [Real]
 * and keeps its own exactness, so `(1−i)⁻¹ = 1/2+1/2 i` (05 EX-CMPLX-04) and `2∠45 = √2+√2i`
 * (EX-CMPLX-02) come out exact. A real number is a [ComplexValue] with a zero imaginary part;
 * whether a result whose imaginary part is zero is shown as a real is the formatter's call.
 *
 * Angles (`arg`, `r∠θ`) are in the current unit and lie in `(−180°, 180°]` (research 03 §3,
 * VERIFIED for Deg; the Rad/Gra range is the same half-open turn, INFERRED).
 */
object ComplexMath {
    val I: ComplexValue = ComplexValue(Reals.ZERO, Reals.ONE)

    fun of(re: Real, im: Real = Reals.ZERO): ComplexValue = ComplexValue(re, im)

    fun isReal(z: ComplexValue): Boolean = z.im.dec.isZero

    fun add(a: ComplexValue, b: ComplexValue): Res<ComplexValue> =
        parts(RealArith.add(a.re, b.re), RealArith.add(a.im, b.im))

    fun subtract(a: ComplexValue, b: ComplexValue): Res<ComplexValue> = add(a, negate(b))

    fun negate(z: ComplexValue): ComplexValue = ComplexValue(RealArith.negate(z.re), RealArith.negate(z.im))

    /** `(a+bi)(c+di) = (ac−bd) + (ad+bc)i`. */
    fun multiply(a: ComplexValue, b: ComplexValue): Res<ComplexValue> {
        val re = RealArith.multiply(a.re, b.re).zip(RealArith.multiply(a.im, b.im), RealArith::subtract)
        val im = RealArith.multiply(a.re, b.im).zip(RealArith.multiply(a.im, b.re), RealArith::add)
        return parts(re, im)
    }

    /**
     * `z/w`; Math ERROR for `w = 0`. `(2+6i)÷(2i) = 3−i` (05 EX-CMPLX-01).
     *
     * All-exact operands use `z·w̄ / |w|²`, which is exact and cannot leave the range (no exact
     * part reaches 10¹⁴). Anything with a decimal in it uses [scaled] instead, because `|w|²` and
     * `z·w̄` leave the range long before the quotient does: `1 ÷ 1×10⁻⁵⁰` underflowed to `÷0` and
     * `(1+i) ÷ 1×10⁶⁰` and `6×10⁹⁹ ÷ 2` overflowed — Math ERROR in CMPLX where COMP answers all
     * three (a review finding). The firmware's own complex division (07 §4.5) is UNKNOWN; this
     * never errs on a quotient that is in range, and gives the same exactness as before.
     */
    fun divide(a: ComplexValue, b: ComplexValue): Res<ComplexValue> {
        if (isReal(b) && b.re.dec.isZero) return Res.MATH
        if (!listOf(a.re, a.im, b.re, b.im).all(Reals::isExact)) return scaled(a, b)
        val norm = RealArith.square(b.re).zip(RealArith.square(b.im), RealArith::add)
        return norm.zip(multiply(a, conjugate(b))) { n, p ->
            parts(RealArith.divide(p.re, n), RealArith.divide(p.im, n))
        }
    }

    /**
     * Smith's division, which forms no square: with `|c| ≥ |d|`, `t = d/c` and `s = c + d·t`,
     * `(a+bi)/(c+di) = ((a + b·t) + (b − a·t)i) / s`; otherwise the roles of c and d swap and it is
     * `((a·t + b) + (b·t − a)i) / s`. A real divisor makes t zero, so it is a plain division of
     * each part, and an imaginary one likewise.
     */
    private fun scaled(z: ComplexValue, w: ComplexValue): Res<ComplexValue> {
        val wide = w.re.dec.abs() >= w.im.dec.abs()
        val (big, small) = if (wide) w.re to w.im else w.im to w.re
        val t = RealArith.divide(small, big).valueOr { return it }
        val s = RealArith.multiply(small, t).then { RealArith.add(big, it) }.valueOr { return it }
        val (first, second) = if (wide) z.re to z.im else z.im to z.re
        val re = RealArith.multiply(second, t).then { RealArith.add(first, it) }
        val im = RealArith.multiply(first, t).then { crossed ->
            if (wide) RealArith.subtract(z.im, crossed) else RealArith.subtract(crossed, z.re)
        }
        return parts(re.then { RealArith.divide(it, s) }, im.then { RealArith.divide(it, s) })
    }

    fun reciprocal(z: ComplexValue): Res<ComplexValue> = divide(of(Reals.ONE), z)

    fun square(z: ComplexValue): Res<ComplexValue> = multiply(z, z)

    fun cube(z: ComplexValue): Res<ComplexValue> = powInt(z, CUBE)

    /** `zⁿ` by repeated squaring; `0⁰` and `0` to a negative power are Math ERROR. */
    fun powInt(z: ComplexValue, n: Long): Res<ComplexValue> {
        val zero = z.re.dec.isZero && z.im.dec.isZero
        if (n == Long.MIN_VALUE || (zero && n <= 0L)) return Res.MATH
        var base = if (n < 0L) reciprocal(z) else Res.Ok(z)
        var k = if (n < 0L) -n else n
        var result: Res<ComplexValue> = Res.Ok(of(Reals.ONE))
        while (k > 0L) {
            if (k % 2L == 1L) result = result.zip(base) { r, b -> multiply(r, b) }
            k /= 2L
            if (k > 0L) base = base.then { multiply(it, it) }
        }
        return result
    }

    /** `Conjg(2+3i) = 2−3i` (05 EX-CMPLX-06). */
    fun conjugate(z: ComplexValue): ComplexValue = ComplexValue(z.re, RealArith.negate(z.im))

    /** The modulus, `Abs(1+i) = √2` (05 EX-CMPLX-07): exact where the parts are. */
    fun abs(z: ComplexValue): Res<Real> = Coordinates.hypotenuse(z.re, z.im)

    /** `arg(1+i) = 45` in Deg (05 EX-CMPLX-08); `arg 0` is 0, a **GUESS** (see [Trig.atan2]). */
    fun arg(z: ComplexValue, unit: AngleUnit): Res<Real> = Trig.atan2(z.im, z.re, unit)

    /** `▶r∠θ`: `√2+√2i` is `2∠45` (05 EX-CMPLX-03). */
    fun toPolar(z: ComplexValue, unit: AngleUnit): Res<Polar> = Coordinates.pol(z.re, z.im, unit)

    /**
     * `r∠θ` as `a+bi`: `2∠45 = √2+√2i` (05 EX-CMPLX-02). Unlike `Rec`, a negative r is taken as
     * written (it points the other way) — the guide gives no range for `∠`, a **GUESS**.
     */
    fun polar(r: Real, theta: Real, unit: AngleUnit): Res<ComplexValue> =
        Coordinates.rectangular(r, theta, unit).map { ComplexValue(it.x, it.y) }

    /**
     * The principal square root. In CMPLX `√(−4)` is `2i`: the guide is silent (research 00 §5
     * #14) but 07 §4.5's dispatch table names a complex square root, so a negative real gets an
     * imaginary root rather than Math ERROR — a **GUESS**.
     *
     * Otherwise `t = √((|z| + |a|)/2)` is the larger part — the real part for `a ≥ 0`, the
     * imaginary one (with b's sign) for `a < 0` — and the other part is `b/(2t)`. Both parts used
     * to come from `|z| ± a`, and the one with the minus cancels to nothing when `|b| ≪ |a|`:
     * `√(4 + 10⁻¹⁰ i)` lost its imaginary part (a review finding). The same values stay exact:
     * `√(3+4i) = 2+i`, `√(−3+4i) = 1+2i`, `√i = √2/2 + √2/2 i`.
     */
    fun sqrt(z: ComplexValue): Res<ComplexValue> {
        if (isReal(z)) {
            return Roots.sqrt(RealArith.abs(z.re)).map { if (z.re.dec.isNegative) of(Reals.ZERO, it) else of(it) }
        }
        val t = abs(z).then { RealArith.add(it, RealArith.abs(z.re)) }
            .then { RealArith.divide(it, TWO) }
            .then { Roots.sqrt(it) }
            .valueOr { return it }
        val other = RealArith.multiply(TWO, t).then { RealArith.divide(z.im, it) }.valueOr { return it }
        if (!z.re.dec.isNegative) return Res.Ok(ComplexValue(t, other))
        return Res.Ok(ComplexValue(RealArith.abs(other), if (z.im.dec.isNegative) RealArith.negate(t) else t))
    }

    /** Both parts, or the first part's error, then the second's. */
    private fun parts(re: Res<Real>, im: Res<Real>): Res<ComplexValue> =
        re.zip(im) { x, y -> Res.Ok(ComplexValue(x, y)) }

    private const val CUBE = 3L
    private val TWO = Reals.of(2)
}
