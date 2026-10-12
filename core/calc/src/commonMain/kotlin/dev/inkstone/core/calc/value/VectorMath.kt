package dev.inkstone.core.calc.value

import dev.inkstone.core.calc.AngleUnit

/**
 * VECTOR mode's operations on 2-D and 3-D vectors (research 03 §9, 06 §6.9). Cells are [Real]s,
 * exact where they can be: `|(1,2)|·|(3,4)| = 5√5`.
 *
 * Errors: vectors of different sizes are Dimension ERROR (research 03 §7.4, which covers both
 * editors); a cell outside ±1×10¹⁰⁰ is Math ERROR.
 */
object VectorMath {
    private const val PLANE = 2
    private const val SPACE = 3

    /** `VctA + VctB`: `(1,2)+(3,4) = (4,6)` (05 EX-VEC-01). */
    fun add(a: VectorValue, b: VectorValue): Res<VectorValue> = cellwise(a, b) { x, y -> RealArith.add(x, y) }

    /** `VctAns − VctB`: `(3,6)−(3,4) = (0,2)` (05 EX-VEC-03). */
    fun subtract(a: VectorValue, b: VectorValue): Res<VectorValue> = cellwise(a, b) { x, y -> RealArith.subtract(x, y) }

    /** `3 × VctA = (3,6)` (05 EX-VEC-03), either way round. */
    fun scale(k: Real, v: VectorValue): Res<VectorValue> = map(v) { RealArith.multiply(k, it) }

    /** `VctA ÷ k`; Math ERROR for `k = 0`. */
    fun divide(v: VectorValue, k: Real): Res<VectorValue> =
        if (k.dec.isZero) Res.MATH else map(v) { RealArith.divide(it, k) }

    fun negate(v: VectorValue): VectorValue = VectorValue(v.cells.map(RealArith::negate))

    /** `VctA•VctB`, a scalar: `(1,2)•(3,4) = 11` (05 EX-VEC-04). */
    fun dot(a: VectorValue, b: VectorValue): Res<Real> {
        if (a.cells.size != b.cells.size) return Res.DIMENSION
        var sum = Reals.ZERO
        for (i in a.cells.indices) {
            val term = RealArith.multiply(a.cells[i], b.cells[i]).valueOr { return it }
            sum = RealArith.add(sum, term).valueOr { return it }
        }
        return Res.Ok(sum)
    }

    /**
     * `VctA × VctB`, the cross product. Two 2-D vectors give a **3-D** one, as if each had a zero
     * third component: `(1,2)×(3,4) = (0,0,−2)` (05 EX-VEC-05, VERIFIED). A 2-D vector crossed
     * with a 3-D one is Dimension ERROR — the guide shows only matching sizes, so padding the
     * smaller one is not assumed (a **GUESS**).
     */
    fun cross(a: VectorValue, b: VectorValue): Res<VectorValue> {
        if (a.cells.size != b.cells.size) return Res.DIMENSION
        val u = padded(a)
        val v = padded(b)
        val cells = List(SPACE) { i ->
            val j = (i + 1) % SPACE
            val k = (i + 2) % SPACE
            val first = RealArith.multiply(u[j], v[k]).valueOr { return it }
            val second = RealArith.multiply(u[k], v[j]).valueOr { return it }
            RealArith.subtract(first, second).valueOr { return it }
        }
        return Res.Ok(VectorValue(cells))
    }

    /** `Abs(VctC)`, the length: `|(2,−1,2)| = 3` (05 EX-VEC-06). */
    fun magnitude(v: VectorValue): Res<Real> = dot(v, v).then { Roots.sqrt(it) }

    /**
     * The angle between two vectors, `cos⁻¹((A•B)/(|A||B|))`, in [unit]. The calculator has no
     * such command — the guide types this expression (05 EX-VEC-07: `10,305` in Fix 3) — so this
     * is exactly that expression, including its Math ERROR for a zero vector and for a cosine a
     * rounding hair past ±1, rather than a "better" formula that would disagree with it.
     */
    fun angle(a: VectorValue, b: VectorValue, unit: AngleUnit): Res<Real> {
        val lengths = magnitude(a).zip(magnitude(b), RealArith::multiply)
        return dot(a, b).zip(lengths, RealArith::divide).then { Trig.acos(it, unit) }
    }

    private fun padded(v: VectorValue): List<Real> = if (v.cells.size == PLANE) v.cells + Reals.ZERO else v.cells

    private inline fun map(v: VectorValue, f: (Real) -> Res<Real>): Res<VectorValue> =
        Res.Ok(VectorValue(v.cells.map { f(it).valueOr { e -> return e } }))

    private inline fun cellwise(a: VectorValue, b: VectorValue, f: (Real, Real) -> Res<Real>): Res<VectorValue> {
        if (a.cells.size != b.cells.size) return Res.DIMENSION
        return Res.Ok(VectorValue(a.cells.indices.map { f(a.cells[it], b.cells[it]).valueOr { e -> return e } }))
    }
}
