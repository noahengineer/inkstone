package dev.inkstone.core.calc.input

/**
 * How the editor and the parser group [Tok]s. One place, because the two must agree: the editor's
 * "FRAC takes the number before it" and the parser's number literal are the same run of tokens,
 * and a disagreement would make a fraction whose numerator the parser then splits.
 */
internal object TokenKinds {
    val digits: Set<Tok> = setOf(
        Tok.D0, Tok.D1, Tok.D2, Tok.D3, Tok.D4, Tok.D5, Tok.D6, Tok.D7, Tok.D8, Tok.D9,
    )

    val hexDigits: Set<Tok> = setOf(Tok.HEX_A, Tok.HEX_B, Tok.HEX_C, Tok.HEX_D, Tok.HEX_E, Tok.HEX_F)

    /** What a number literal is made of (research 02 §7.4: FRAC "directly after typing a number"). */
    val numberParts: Set<Tok> = digits + Tok.DECIMAL + Tok.EXP10

    /**
     * x², x³, x⁻¹ and `^(` — the keys that "cannot follow one another directly" (research 02 §7.4,
     * VERIFIED). The template form, [TemplateKind.POWER], is the fourth member; see [isPowerPostfix].
     */
    val powerPostfix: Set<Tok> = setOf(Tok.SQUARE, Tok.CUBE, Tok.RECIPROCAL, Tok.POWER_FN)

    /** A single-item value: what INS wraps and what `a⌟b` may leave unbracketed. */
    val values: Set<Tok> = setOf(
        Tok.PI, Tok.E, Tok.IMAGINARY, Tok.RAN, Tok.ANS, Tok.PRE_ANS,
        Tok.VAR_A, Tok.VAR_B, Tok.VAR_C, Tok.VAR_D, Tok.VAR_E, Tok.VAR_F, Tok.VAR_M, Tok.VAR_X, Tok.VAR_Y,
        Tok.MAT_A, Tok.MAT_B, Tok.MAT_C, Tok.MAT_ANS, Tok.VCT_A, Tok.VCT_B, Tok.VCT_C, Tok.VCT_ANS,
    )

    /** A token that opens a parenthesis: `(` itself and every `name(` function. */
    fun opensParen(tok: Tok): Boolean = tok.text.endsWith("(")
}

/** True for x², x³, x⁻¹, `^(` and the x^□ template. */
internal fun Item.isPowerPostfix(): Boolean = when (this) {
    is TokenItem -> tok in TokenKinds.powerPostfix
    is Template -> kind == TemplateKind.POWER
    else -> false
}

internal fun Item.tokOrNull(): Tok? = (this as? TokenItem)?.tok

/**
 * Whether [items]`[index]` belongs to a number literal: a digit, the decimal comma, `×10`, or the
 * `(−)` that signs an exponent (it counts only directly after `×10`).
 */
internal fun isNumberPart(items: List<Item>, index: Int): Boolean {
    val tok = items[index].tokOrNull() ?: return false
    if (tok in TokenKinds.numberParts) return true
    return tok == Tok.NEGATIVE && index > 0 && items[index - 1].tokOrNull() == Tok.EXP10
}
