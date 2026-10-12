package dev.inkstone.core.calc.input

/**
 * Which keys, pressed **first** while a result is on screen, start the new line with `Ans`
 * (research 02 §9: `3×4 =` then `÷30 =` shows `Ans÷30`). The machine layer asks this before it
 * clears the line; it then types the [Result]'s [Result.ans] and the key.
 *
 * For a number, VERIFIED only for `÷`. Every other entry is INFERRED from it: every key that needs
 * a value on its left — the binary operators, the postfix functions, x^□, the conversions and the
 * estimates — because typing one onto an empty line could only be a Syntax ERROR. `⌟` (LineIO
 * FRAC) is left out: in Natural display the same key opens an empty fraction, and a key that
 * behaves differently in the two displays is the wrong one to guess about (research 00 §5 #16).
 *
 * Matrix and vector results are VERIFIED and narrower ([Result.MATRIX], [Result.VECTOR]).
 */
object AnsContinuation {
    /** What kind of result is on screen, and so which `Ans` the new line starts with. */
    enum class Result(val ans: Tok) {
        /** A number, real or complex: `Ans` (research 02 §9). */
        VALUE(Tok.ANS),

        /** The MatAns screen: `MatAns` (research 03 §7). */
        MATRIX(Tok.MAT_ANS),

        /** The VctAns screen: `VctAns` (research 03 §9.3). */
        VECTOR(Tok.VCT_ANS),
    }

    private val TOKENS: Set<Tok> = setOf(
        Tok.PLUS, Tok.MINUS, Tok.TIMES, Tok.DIVIDE, Tok.DIV_R,
        Tok.SQUARE, Tok.CUBE, Tok.RECIPROCAL, Tok.FACTORIAL, Tok.PERCENT,
        Tok.DEG_MARK, Tok.RAD_MARK, Tok.GRAD_MARK, Tok.POWER_FN, Tok.ROOT_FN,
        Tok.NPR, Tok.NCR, Tok.ANGLE, Tok.DOT, Tok.AND, Tok.OR, Tok.XOR, Tok.XNOR,
        Tok.EST_X, Tok.EST_X1, Tok.EST_X2, Tok.EST_Y, Tok.TO_T, Tok.TO_POLAR, Tok.TO_RECT,
    )

    /** "On that screen only `+ − × ÷` jump to the calculation line with VctAns" (research 03 §9.3). */
    private val VECTOR_TOKENS: Set<Tok> = setOf(Tok.PLUS, Tok.MINUS, Tok.TIMES, Tok.DIVIDE)

    /** "…`+ − × ÷ x⁻¹ x²` or `SHIFT x²(x³)` jumps to the calculation line with MatAns" (research 03 §7). */
    private val MATRIX_TOKENS: Set<Tok> = VECTOR_TOKENS + setOf(Tok.RECIPROCAL, Tok.SQUARE, Tok.CUBE)

    /** For a number on screen. */
    fun continues(tok: Tok): Boolean = tok in TOKENS

    fun continues(tok: Tok, result: Result): Boolean = when (result) {
        Result.VALUE -> continues(tok)
        Result.MATRIX -> tok in MATRIX_TOKENS
        Result.VECTOR -> tok in VECTOR_TOKENS
    }

    /** Of the templates only x^□ applies to the value before it (a number on screen). */
    fun continues(kind: TemplateKind): Boolean = kind == TemplateKind.POWER

    /**
     * The same question for a template **key** in either display, which is the one the machine
     * can actually ask. In LineIO a template key types its linear spelling instead (research 02
     * §7.4), and the answer follows that token: `SHIFT x^` opens the ⁿ√ template in Natural
     * display, which needs nothing on its left, but types the infix `ˣ√(` in LineIO, which does.
     */
    fun continues(kind: TemplateKind, natural: Boolean): Boolean =
        if (natural) continues(kind) else InputState.lineSpelling(kind)?.let { continues(it) } ?: false

    /** For a number on screen. */
    fun continues(item: Item): Boolean = when (item) {
        is TokenItem -> continues(item.tok)
        is ConversionItem -> true
        is Template -> continues(item.kind)
        is ConstantItem -> false
    }
}
