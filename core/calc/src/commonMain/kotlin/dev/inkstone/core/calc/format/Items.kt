package dev.inkstone.core.calc.format

import dev.inkstone.core.calc.input.Expr
import dev.inkstone.core.calc.input.Item
import dev.inkstone.core.calc.input.Template
import dev.inkstone.core.calc.input.TemplateKind
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.input.TokenItem
import kotlin.math.abs

/**
 * The pieces a result is built from. A result is an [Expr] like any input, so one layout engine
 * draws both lines (docs/CALCULATOR.md §4): a MathO fraction is a [TemplateKind.FRACTION], a LineIO
 * one is `7⌟6`, exponential form is a [TemplateKind.SCI_EXPONENT].
 *
 * Every minus sign in a result is [Tok.NEGATIVE], the short sign — the leading one of `-23,8`
 * and the one between the terms of `6-4√5` or `3-i` alike. Research says results draw `-` (02
 * §8.3, the 5-dot bar being the firmware *hyphen*, 00 §5a); whether a subtraction inside a result
 * uses the longer glyph is UNKNOWN (02 §15 #13), so treating both the same is a **GUESS**.
 */
internal object Items {
    val MINUS: Item = TokenItem(Tok.NEGATIVE)
    val PLUS: Item = TokenItem(Tok.PLUS)

    fun tok(t: Tok): Item = TokenItem(t)

    /** Digit text as tokens: `0`–`9`, the decimal comma, and BASE-N's `A`–`F`. */
    fun text(digits: String): List<Item> = digits.map { TokenItem(CHARACTERS.getValue(it)) }

    /** A non-negative whole number's digits. */
    fun integer(n: Long): List<Item> = text(n.toString())

    fun signed(negative: Boolean, magnitude: List<Item>): List<Item> =
        if (negative) listOf(MINUS) + magnitude else magnitude

    fun numeral(n: Numeral): List<Item> {
        val mantissa = signed(n.negative, text(n.mantissa))
        val exponent = n.exponent ?: return mantissa
        return mantissa + exponent(exponent)
    }

    /** `×10ⁿ`: the exponent's digits, with a leading `-` token when it is negative (Expr.kt's contract). */
    fun exponent(e: Int): Item =
        Template(TemplateKind.SCI_EXPONENT, listOf(Expr(signed(e < 0, integer(abs(e.toLong()))))))

    /** Stacked in MathO, `n⌟d` in linear output (research 02 §8.5). */
    fun fraction(num: List<Item>, den: List<Item>, natural: Boolean): List<Item> = if (natural) {
        listOf(Template(TemplateKind.FRACTION, listOf(Expr(num), Expr(den))))
    } else {
        num + tok(Tok.FRAC_SEP) + den
    }

    /** `a b/c` stacked, or `a⌟b⌟c` — the LineIO spelling is INFERRED (research 02 §8.5). */
    fun mixed(whole: List<Item>, num: List<Item>, den: List<Item>, natural: Boolean): List<Item> = if (natural) {
        listOf(Template(TemplateKind.MIXED_FRACTION, listOf(Expr(whole), Expr(num), Expr(den))))
    } else {
        whole + tok(Tok.FRAC_SEP) + num + tok(Tok.FRAC_SEP) + den
    }

    fun sqrt(radicand: Long): Item = Template(TemplateKind.SQRT, listOf(Expr(integer(radicand))))

    /** [pieces] joined by [separator]: `2×3×13²`. */
    fun joined(pieces: List<List<Item>>, separator: Item): List<Item> =
        pieces.flatMapIndexed { i, piece -> if (i == 0) piece else listOf(separator) + piece }

    private val CHARACTERS: Map<Char, Tok> = mapOf(
        '0' to Tok.D0, '1' to Tok.D1, '2' to Tok.D2, '3' to Tok.D3, '4' to Tok.D4,
        '5' to Tok.D5, '6' to Tok.D6, '7' to Tok.D7, '8' to Tok.D8, '9' to Tok.D9,
        ',' to Tok.DECIMAL,
        'A' to Tok.HEX_A, 'B' to Tok.HEX_B, 'C' to Tok.HEX_C, 'D' to Tok.HEX_D, 'E' to Tok.HEX_E, 'F' to Tok.HEX_F,
    )
}
