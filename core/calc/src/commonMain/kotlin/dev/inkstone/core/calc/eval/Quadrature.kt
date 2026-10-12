package dev.inkstone.core.calc.eval

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.parse.Integral

/**
 * `∫` by **adaptive Gauss–Kronrod 7/15** (research 07 §4.6; docs/CALCULATOR.md §3), in the
 * calculator's own 15-digit arithmetic:
 * - each piece is integrated with the 15-point Kronrod rule; its error is `|K15 − G7|`, the 7-point
 *   Gauss rule sharing every other node (the plain difference — QUADPACK scales it, the ROM's
 *   choice is UNKNOWN, a **GUESS** that errs on the safe side);
 * - the answer is accepted when the summed errors are within tol absolutely **or** relative to the
 *   answer (`E ≤ tol·max(1, |S|)`, docs/CALCULATOR.md §3's "absolute-or-relative" test);
 * - otherwise the piece with the largest error is halved (the ROM's "÷2 steps"), up to
 *   [MAX_SPLITS] times, then **Time Out** (research 02 §11). 47 is the ROM's second work counter
 *   (0x2F, 07 §4.6) read as a bisection budget — a **GUESS**, the counters' meaning is
 *   unconfirmed;
 * - `a = b` still evaluates f(a) and f(b), so a domain error there is reported, and answers 0
 *   (07 §4.6, INFERRED from the ROM).
 *
 * The weights are the published QUADPACK `qk15` values, each rounded to 15 significant digits by
 * [Dec.parse], which is what research 07 §4.6 says the ROM stores. The **nodes** are written out
 * as the ROM's 15 digits instead: six are the same rounding, but the ROM holds the innermost as
 * `0,207784955007899` where rounding `0,207784955007898468` gives `…898`, so rounding the
 * published value cannot reproduce it (a review finding; the effect is in the 15th digit).
 */
internal class Quadrature(private val session: Session, private val node: Integral) {
    private class Piece(val a: Dec, val b: Dec, val area: Dec, val error: Dec)

    fun integrate(a: Dec, b: Dec, tol: Dec): Ev<Dec> {
        if (a == b) return endpoints(a, b)
        val pieces = mutableListOf(rule(a, b).orElse { return it })
        repeat(MAX_SPLITS) {
            verdict(pieces, tol)?.let { return it }
            split(pieces).orElse { return it }
        }
        return verdict(pieces, tol) ?: failAt(CalcError.TIME_OUT, node)
    }

    /** The answer once the pieces' errors are within tol, Math ERROR past the range, else null. */
    private fun verdict(pieces: List<Piece>, tol: Dec): Ev<Dec>? {
        val area = pieces.fold(Dec.ZERO) { sum, p -> sum + p.area }
        val error = pieces.fold(Dec.ZERO) { sum, p -> sum + p.error }
        return when {
            area.isOverflow -> failAt(CalcError.MATH, node)
            error <= tol * maxOf(Dec.ONE, area.abs()) -> Ev.Ok(area)
            else -> null
        }
    }

    /** Halves the piece with the largest error. */
    private fun split(pieces: MutableList<Piece>): Ev<Unit> {
        val worst = pieces.maxBy { it.error }
        pieces.remove(worst)
        val middle = (worst.a + worst.b) / Dec.TWO
        pieces += rule(worst.a, middle).orElse { return it }
        pieces += rule(middle, worst.b).orElse { return it }
        return Ev.Ok(Unit)
    }

    private fun endpoints(a: Dec, b: Dec): Ev<Dec> {
        session.sample(node.f, a).orElse { return it }
        session.sample(node.f, b).orElse { return it }
        return Ev.Ok(Dec.ZERO)
    }

    /** The K15 and G7 sums on [a, b]. */
    private fun rule(a: Dec, b: Dec): Ev<Piece> {
        val centre = (a + b) / Dec.TWO
        val half = (b - a) / Dec.TWO
        val middle = f(centre).orElse { return it }
        var kronrod = KRONROD_CENTRE * middle
        var gauss = GAUSS_CENTRE * middle
        for (i in ABSCISSAE.indices) {
            val offset = half * ABSCISSAE[i]
            val pair = f(centre - offset).orElse { return it } + f(centre + offset).orElse { return it }
            kronrod += KRONROD_WEIGHTS[i] * pair
            GAUSS_WEIGHTS[i]?.let { gauss += it * pair }
        }
        return Ev.Ok(Piece(a, b, kronrod * half, ((kronrod - gauss) * half).abs()))
    }

    private fun f(x: Dec): Ev<Dec> = session.sample(node.f, x)

    companion object {
        /** tol when none is typed (research 02 §10.7: 1×10⁻⁵). */
        val DEFAULT_TOL: Dec = number("1E-5")

        const val MAX_SPLITS = 47

        /** ±xₖ, outermost first, as the ROM stores them (research 07 §4.6; see the class KDoc). */
        internal val ABSCISSAE: List<Dec> = listOf(
            "0.991455371120813",
            "0.949107912342759",
            "0.864864423359769",
            "0.741531185599394",
            "0.586087235467691",
            "0.405845151377397",
            "0.207784955007899",
        ).map(::number)

        private val KRONROD_WEIGHTS: List<Dec> = listOf(
            "0.022935322010529225",
            "0.063092092629978553",
            "0.104790010322250184",
            "0.140653259715525919",
            "0.169004726639267903",
            "0.190350578064785410",
            "0.204432940075298892",
        ).map(::number)

        /** The G7 weight at each Kronrod node that is also a Gauss node, else null. */
        private val GAUSS_WEIGHTS: List<Dec?> = listOf(
            null,
            "0.129484966168869693",
            null,
            "0.279705391489276668",
            null,
            "0.381830050505118945",
            null,
        ).map { it?.let(::number) }

        private val KRONROD_CENTRE: Dec = number("0.209482141084727828")
        private val GAUSS_CENTRE: Dec = number("0.417959183673469388")

        private fun number(text: String): Dec = Dec.parse(text) ?: error("bad constant $text")
    }
}
