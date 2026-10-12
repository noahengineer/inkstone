package dev.inkstone.core.calc.format

import dev.inkstone.core.calc.NumberFormat
import dev.inkstone.core.calc.input.Item
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.num.DecMath
import dev.inkstone.core.calc.value.DecimalReal
import dev.inkstone.core.calc.value.Dms
import dev.inkstone.core.calc.value.FractionReal
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.SurdReal
import dev.inkstone.core.calc.value.SurdTerm
import kotlin.math.abs

/**
 * How a result is being drawn: the SETUP number format, MathO (templates) or linear output, and
 * whether fractions are mixed (`a b/c`) — the presentation's flag, which starts from SETUP and
 * `SHIFT S⇔D` flips.
 */
internal data class Style(val format: NumberFormat, val natural: Boolean, val mixed: Boolean)

/** What kind of thing is on the screen — what the toggles decide by. */
internal enum class Look { NUMBER, FRACTION, SURD, PI, DMS, ENGINEERING, FACTORS }

/** A rendered value: its items and what kind of display they are. */
internal data class Shape(val items: List<Item>, val look: Look) {
    /** A fraction, surd or π form: what `S⇔D` turns into a decimal (research 02 §8.8). */
    val isExact: Boolean get() = look == Look.FRACTION || look == Look.SURD || look == Look.PI
}

/**
 * A [Real] drawn in each of its faces (research 02 §8.5–8.9). [standard] is what `=` shows: the
 * exact form when the value is exact and MathO can draw it, otherwise the decimal in the SETUP
 * format.
 */
internal object RealForms {
    /** Seconds show two decimals — the place the guide says is good to ±1 (research 02 §8.9). GUESS. */
    private const val SECOND_PLACES = 2

    private val MARK: Item = Items.tok(Tok.DMS)
    private val PI: Item = Items.tok(Tok.PI)

    /**
     * What `=` shows:
     * - a fraction while it fits ten digits (research 02 §8.5; docs/CALCULATOR.md §3), stacked in
     *   MathO and `7⌟6` in linear output; Fix and Sci do not turn it into a decimal (02 §8.3,
     *   VERIFIED: Fix 2 shows `2/3`);
     * - a surd in √ form in MathO only — linear output has no √ form (04 E-21: `4,243`);
     * - a DMS-flagged decimal in degrees, minutes and seconds (02 §8.9);
     * - a π multiple when the expression made it eligible ([piEligible], docs/CALCULATOR.md §3) and
     *   the display is MathO (π form is a Natural Display form, 02 §8.7);
     * - otherwise the number. A decimal never becomes a fraction by itself (docs/CALCULATOR.md §3).
     */
    fun standard(x: Real, style: Style, piEligible: Boolean): Shape = when (x) {
        is FractionReal -> fraction(x, style) ?: number(x.dec, style)

        is SurdReal -> if (style.natural) surd(x) else number(x.dec, style)

        is DecimalReal -> when {
            x.dms -> dms(x) ?: number(x.dec, style)
            piEligible && style.natural -> pi(x.dec, style) ?: number(x.dec, style)
            else -> number(x.dec, style)
        }
    }

    fun number(d: Dec, style: Style): Shape = Shape(Items.numeral(Numerals.of(d, style.format)), Look.NUMBER)

    /** One part of a two-part or complex result: its decimal, or as `=` shows it but never in π form. */
    fun part(x: Real, decimal: Boolean, style: Style): List<Item> =
        if (decimal) number(x.dec, style).items else standard(x, style, piEligible = false).items

    /**
     * `S⇔D` from a decimal (research 02 §8.8): the fraction it stands for, else — in MathO, for an
     * eligible expression — its π form; never a √ form, which cannot be recovered from a decimal
     * (07 §4.3, quirk Q4). Null when there is neither.
     */
    fun recovered(x: Real, style: Style, piEligible: Boolean): Shape? {
        Recovery.fraction(x.dec, style.mixed)?.let { return ratio(it, style) }
        return if (piEligible && style.natural) pi(x.dec, style) else null
    }

    /** A non-integer fraction that fits the display, or null (an integer is a number). */
    private fun fraction(x: FractionReal, style: Style): Shape? {
        if (x.den == 1L) return null
        val r = Ratio(x.num, x.den)
        return if (Recovery.fits(r, style.mixed)) ratio(r, style) else null
    }

    private fun ratio(r: Ratio, style: Style): Shape {
        if (r.den == 1L) return number(Dec.of(r.num), style)
        val magnitude = Ratio(abs(r.num), r.den)
        return Shape(Items.signed(r.num < 0L, fractionItems(magnitude, style)), Look.FRACTION)
    }

    /** A positive fraction, mixed when that is asked for and it is more than 1 (`1 1/6`). */
    private fun fractionItems(r: Ratio, style: Style): List<Item> = if (style.mixed && r.num > r.den) {
        Items.mixed(Items.integer(r.num / r.den), Items.integer(r.num % r.den), Items.integer(r.den), style.natural)
    } else {
        Items.fraction(Items.integer(r.num), Items.integer(r.den), style.natural)
    }

    /**
     * The π form: `π`, `2π`, `1/6 π` (stacked, then π — research 04 C-01), `1 1/2 π` when mixed
     * (02 §8.7 lists `a b/c π`). Only below 10⁶ and only for a coefficient that fits ([Recovery]).
     */
    fun pi(x: Dec, style: Style): Shape? {
        val r = Recovery.piMultiple(x, style.mixed) ?: return null
        val magnitude = abs(r.num)
        val coefficient = when {
            r.den != 1L -> fractionItems(Ratio(magnitude, r.den), style)
            magnitude == 1L -> emptyList()
            else -> Items.integer(magnitude)
        }
        return Shape(Items.signed(r.num < 0L, coefficient + PI), Look.PI)
    }

    /**
     * `3°0'0"` — no zero padding (research 02 §8.9, 04 C-14). The `°'"` mark is one token; the
     * renderer draws the first, second and third of a number as `°`, `'` and `"`. Null outside
     * the guide's conversion range, `9999999°59'59"`, where the decimal shows instead.
     */
    fun dms(x: Real): Shape? {
        val parts = Dms.parts(x)?.roundedSeconds(SECOND_PLACES) ?: return null
        val s = parts.seconds
        val seconds = if (s.isZero) "0" else Numerals.positional(s.significandDigits, s.exponent + 1)
        val items = Items.integer(parts.degrees) + MARK + Items.integer(parts.minutes.toLong()) + MARK +
            Items.text(seconds) + MARK
        val zero = parts.degrees == 0L && parts.minutes == 0 && s.isZero
        return Shape(Items.signed(parts.negative && !zero, items), Look.DMS)
    }

    /**
     * √ form (research 02 §8.6). The terms come in the value library's canonical order — the
     * rational term, then the larger radicand first — which is every printed example's order:
     * `45√3+10√2`, `√3+3√2`, `6-4√5`, `(√6-√2)/4`. Over a common denominator `lcm(c, f)` (02 §8.6,
     * INFERRED from S570): one term keeps its sign outside the bar like a fraction does
     * (`-√2/2`); two terms keep theirs inside (`(-3-√21)/6`, 05 EX-INEQ-04, VERIFIED-capture).
     */
    fun surd(x: SurdReal): Shape {
        val common = x.terms.fold(1L) { acc, t -> acc / DecMath.gcd(acc, t.den) * t.den }
        val scaled = x.terms.map { SurdTerm(it.num * (common / it.den), common, it.radicand) }
        val items = when {
            common == 1L -> terms(scaled)

            scaled.size == 1 -> {
                val t = scaled.single()
                Items.signed(t.num < 0L, Items.fraction(term(abs(t.num), t.radicand), Items.integer(common), true))
            }

            else -> Items.fraction(terms(scaled), Items.integer(common), true)
        }
        return Shape(items, Look.SURD)
    }

    /** Signed terms in a row: `45√3+10√2`, `-3-√21`. */
    fun terms(terms: List<SurdTerm>): List<Item> = terms.flatMapIndexed { i, t ->
        val sign = when {
            t.num < 0L -> listOf(Items.MINUS)
            i > 0 -> listOf(Items.PLUS)
            else -> emptyList()
        }
        sign + term(abs(t.num), t.radicand)
    }

    /** `3`, `√2`, `3√2`: a coefficient of 1 is not written. */
    private fun term(coefficient: Long, radicand: Long): List<Item> = when {
        radicand == 1L -> Items.integer(coefficient)
        coefficient == 1L -> listOf(Items.sqrt(radicand))
        else -> Items.integer(coefficient) + Items.sqrt(radicand)
    }
}
