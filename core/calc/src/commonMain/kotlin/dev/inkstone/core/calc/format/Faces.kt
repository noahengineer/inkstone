package dev.inkstone.core.calc.format

import dev.inkstone.core.calc.NumberFormat
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.SurdReal

/**
 * A real result in each [Form], and what each display key turns one form into. Every function
 * here answers null for "the key does nothing to this result", as [Formatter] promises.
 */
internal object Faces {
    /** [x] drawn in [p]'s form. A form that cannot be drawn any more falls back to a plain face. */
    fun real(x: Real, p: Presentation, style: Style, piEligible: Boolean): Shape = when (p.form) {
        Form.STANDARD -> RealForms.standard(x, style, piEligible)
        Form.DECIMAL -> RealForms.number(x.dec, style)
        Form.RECOVERED -> RealForms.recovered(x, style, piEligible) ?: RealForms.number(x.dec, style)
        Form.DMS -> RealForms.dms(x) ?: RealForms.number(x.dec, style)
        Form.ENGINEERING -> engineered(x, style.format, p.engExponent)
        Form.FACTORS -> Factors.shape(x, style.natural) ?: RealForms.standard(x, style, piEligible)
    }

    private fun engineered(x: Real, format: NumberFormat, exponent: Int): Shape =
        Shape(Items.numeral(Numerals.engineering(x.dec, format, exponent)), Look.ENGINEERING)

    /**
     * `S⇔D` (research 02 §8.8, VERIFIED): an exact form (fraction, √, π) goes to its decimal; a
     * decimal goes back to the exact form `=` showed — unless that is a √ form and the decimal
     * came from `SHIFT =` ([Presentation.shiftEquals]), which never returns to √ — or else to the
     * fraction or π form recovered from it. Nothing happens on a DMS or FACT display (a GUESS:
     * `°'"` and FACT are their own way out).
     */
    fun exact(x: Real, p: Presentation, style: Style, piEligible: Boolean): Presentation? {
        val now = real(x, p, style, piEligible)
        return when {
            p.form == Form.FACTORS || now.look == Look.DMS -> null
            now.isExact -> p.copy(form = Form.DECIMAL, engExponent = 0)
            else -> towardExact(x, p, style, piEligible)
        }
    }

    private fun towardExact(x: Real, p: Presentation, style: Style, piEligible: Boolean): Presentation? {
        val exactAgain = RealForms.standard(x, style, piEligible).isExact && !(x is SurdReal && p.shiftEquals)
        return when {
            exactAgain -> p.copy(form = Form.STANDARD, engExponent = 0)
            RealForms.recovered(x, style, piEligible) != null -> p.copy(form = Form.RECOVERED, engExponent = 0)
            else -> null
        }
    }

    /**
     * `S⇔D` on a result drawn in several parts — a complex number, `Pol`, `Rec`: every part to its
     * decimal and back (a **GUESS** — the guide shows exact parts, `1/2+1/2i`, `r=√2;θ=45`, and
     * S⇔D only for reals). After `SHIFT =` it stays decimal if any of the [drawn] parts is a √
     * form, as a real's would (research 02 §8.8, quirk Q4) — the parts as drawn, so `r` of a polar
     * complex, not its `a` and `b`.
     */
    fun partsExact(drawn: List<Real>, p: Presentation): Presentation? = when {
        p.form != Form.DECIMAL -> p.copy(form = Form.DECIMAL)
        p.shiftEquals && drawn.any { it is SurdReal } -> null
        else -> p.copy(form = Form.STANDARD)
    }

    /**
     * `SHIFT S⇔D` (a b/c ⇔ d/c, research 02 §8.5): flips a fraction — or a π form's coefficient —
     * between mixed and improper. Only where that changes what is drawn; a mixed form past the
     * ten-digit limit is refused, as converting to it is "impossible" (02 §8.5). On anything else,
     * a decimal included, it does nothing — a **GUESS**.
     */
    fun mixed(x: Real, p: Presentation, style: Style, piEligible: Boolean): Presentation? {
        val now = real(x, p, style, piEligible)
        if (now.look != Look.FRACTION && now.look != Look.PI) return null
        val flipped = p.copy(mixed = !p.mixed)
        val next = real(x, flipped, style.copy(mixed = flipped.mixed), piEligible)
        return flipped.takeIf { next.look == now.look && next.items != now.items }
    }

    /**
     * `°'"` on a result toggles decimal ⇔ sexagesimal (research 02 §8.9, VERIFIED: `2°15'18"` →
     * `2,255` → `2°15'18"`), within `9999999°59'59"`; on a factorisation it leaves FACT (§10.13).
     */
    fun dms(x: Real, p: Presentation, style: Style, piEligible: Boolean): Presentation? = when {
        p.form == Form.FACTORS -> p.copy(form = Form.STANDARD)
        real(x, p, style, piEligible).look == Look.DMS -> p.copy(form = Form.DECIMAL)
        RealForms.dms(x) != null -> p.copy(form = Form.DMS)
        else -> null
    }

    /** `ENG` / `SHIFT ENG` ([Engineering]); on a factorisation either leaves FACT (§10.13). */
    fun engineering(x: Real, p: Presentation, format: NumberFormat, left: Boolean): Presentation? {
        if (p.form == Form.FACTORS) return p.copy(form = Form.STANDARD)
        val current = if (p.form == Form.ENGINEERING) p.engExponent else null
        val next = Engineering.next(x.dec, format, current, left) ?: return null
        return p.copy(form = Form.ENGINEERING, engExponent = next)
    }

    /** `FACT` (research 02 §10.13); pressed again it goes back to the result. */
    fun factorize(x: Real, p: Presentation, natural: Boolean): Presentation? = when {
        p.form == Form.FACTORS -> p.copy(form = Form.STANDARD)
        Factors.shape(x, natural) != null -> p.copy(form = Form.FACTORS)
        else -> null
    }
}
