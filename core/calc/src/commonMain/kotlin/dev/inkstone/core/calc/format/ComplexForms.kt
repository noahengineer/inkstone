package dev.inkstone.core.calc.format

import dev.inkstone.core.calc.AngleUnit
import dev.inkstone.core.calc.ComplexFormat
import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.Item
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.lcd.LabelledValue
import dev.inkstone.core.calc.value.ComplexMath
import dev.inkstone.core.calc.value.ComplexValue
import dev.inkstone.core.calc.value.Polar
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.RealArith
import dev.inkstone.core.calc.value.SurdReal
import dev.inkstone.core.calc.value.getOrNull

/** How one complex result is to be drawn: the form, the angle unit, and whether `▶r∠θ` asked for it. */
internal data class ComplexLook(val format: ComplexFormat, val unit: AngleUnit, val explicit: Boolean)

/**
 * CMPLX results (research 03 §3, VERIFIED): `a+bi` or `r∠θ` as SETUP says, or as a closing
 * `▶a+bi` / `▶r∠θ` overrides it. Each part keeps its own exactness in MathO — `1/2+1/2i`,
 * `√2+√2i`, `√2∠45` (05 EX-CMPLX-02/03/04) — and is a decimal (or `n⌟d`) in linear output, where
 * the result takes **two lines**: `a` over `bi`, or `r` over `∠θ`.
 *
 * **GUESSES**, none settled by a printed example:
 * - a result with no imaginary part is drawn as a plain real, unless `▶r∠θ` asked for polar
 *   (`2∠0`); a pure imaginary one is just `bi` (`2i`, `-i`) — one line in Linear display too;
 * - a coefficient whose display is `1` is not written (`3-i`, 05 EX-CMPLX-01, is the evidence for
 *   −1); a two-term surd coefficient keeps its own signs, always with `+` before it, and is
 *   bracketed unless a fraction bar holds it: `(1+√2)i`, `(-1-√2)i`, `(1-√2)/2 i`;
 * - parts are never shown in π form (the value carries no eligibility of its own).
 */
internal object ComplexForms {
    private val I: Item = Items.tok(Tok.IMAGINARY)
    private val ANGLE: Item = Items.tok(Tok.ANGLE)

    /** The one-line form (MathO, and anywhere a single line is all there is). */
    fun line(z: ComplexValue, decimal: Boolean, style: Style, look: ComplexLook): List<Item> {
        val (first, second) = lines(z, decimal, style, look)
        return if (second == null) first else first + second
    }

    /** MathO: one line. Linear output: the two lines, stacked (research 03 §3). */
    fun shown(z: ComplexValue, decimal: Boolean, style: Style, look: ComplexLook): Shown {
        val (first, second) = lines(z, decimal, style, look)
        if (second == null) return Shown(Expr(first), style.natural)
        if (style.natural) return Shown(Expr(first + second), true)
        val lower = if (second.firstOrNull() == Items.PLUS) second.drop(1) else second
        return Shown(null, false, listOf(LabelledValue("", Expr(first)), LabelledValue("", Expr(lower))))
    }

    /**
     * The numbers the result is drawn with: `r` and `θ` when it is drawn in polar form, `a` and `b`
     * otherwise. `S⇔D` after `SHIFT =` asks these whether a √ form is on offer — `1+i` in r∠θ is
     * `√2∠45`, a surd that neither `a` nor `b` is (research 02 §8.8, quirk Q4).
     */
    fun drawnParts(z: ComplexValue, look: ComplexLook): List<Real> {
        val p = polarOf(z, look) ?: return listOf(z.re, z.im)
        return listOf(p.r, p.theta)
    }

    /**
     * `r∠θ`, θ in (−180°, 180°] (03 §3), when the result is drawn in polar form; null when it is
     * drawn as `a+bi` — including when the modulus is out of range.
     */
    private fun polarOf(z: ComplexValue, look: ComplexLook): Polar? {
        val polar = look.format == ComplexFormat.POLAR && (look.explicit || !ComplexMath.isReal(z))
        return if (polar) ComplexMath.toPolar(z, look.unit).getOrNull() else null
    }

    /**
     * The two halves: the real part (or r), and the signed imaginary term (or `∠θ`) — null when
     * the number is drawn as one part only. The second half starts with its sign, so a line is
     * the two concatenated.
     */
    private fun lines(
        z: ComplexValue,
        decimal: Boolean,
        style: Style,
        look: ComplexLook,
    ): Pair<List<Item>, List<Item>?> {
        polarOf(z, look)?.let { return part(it.r, decimal, style) to listOf(ANGLE) + part(it.theta, decimal, style) }
        val reZero = z.re.dec.isZero
        val imZero = z.im.dec.isZero
        return when {
            imZero -> part(z.re, decimal, style) to null
            reZero -> imaginary(z.im, decimal, style, leading = true) to null
            else -> part(z.re, decimal, style) to imaginary(z.im, decimal, style, leading = false)
        }
    }

    /**
     * The `bi` term: `+i`, `-3i`, `+1/2i`, `+(1+√2)i`, `+(1-√2)/2 i`. [leading] drops a `+`.
     *
     * A two-term surd coefficient keeps the signs it has as a real number, inside, with `+` before
     * it — the way the guide draws a two-term surd (`(-3-√21)/6`, 05 EX-INEQ-04, VERIFIED) — and is
     * bracketed when no fraction bar holds it together. Negating it instead would print a double
     * negative, `-(-1+√2)/2 i`, because `√2-1` is canonically `-1+√2` ([dev.inkstone.core.calc.value.Reals]).
     */
    private fun imaginary(im: Real, decimal: Boolean, style: Style, leading: Boolean): List<Item> {
        val twoTermSurd = im is SurdReal && im.terms.size == 2
        if (twoTermSurd && !decimal && style.natural) {
            val drawn = RealForms.surd(im).items
            val coefficient = if (isWholeSum(im)) listOf(Items.tok(Tok.OPEN)) + drawn + Items.tok(Tok.CLOSE) else drawn
            return (if (leading) emptyList() else listOf(Items.PLUS)) + coefficient + I
        }
        val magnitude = part(RealArith.abs(im), decimal, style)
        val coefficient = if (magnitude == ONE) emptyList() else magnitude
        val sign = when {
            im.dec.isNegative -> listOf(Items.MINUS)
            leading -> emptyList()
            else -> listOf(Items.PLUS)
        }
        return sign + coefficient + I
    }

    /** Two surd terms with no common denominator: drawn as a bare sum, so `i` needs brackets. */
    private fun isWholeSum(x: Real): Boolean = x is SurdReal && x.terms.size == 2 && x.terms.all { it.den == 1L }

    private fun part(x: Real, decimal: Boolean, style: Style): List<Item> = RealForms.part(x, decimal, style)

    private val ONE: List<Item> = Items.integer(1L)
}
