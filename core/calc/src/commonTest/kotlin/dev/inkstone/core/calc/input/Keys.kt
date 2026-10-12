package dev.inkstone.core.calc.input

/**
 * Types a space-separated key sequence into an [InputState], so test tables can be written the way
 * research 04/05 print them. A word is one of:
 *
 * - a template key: `FRAC MIXED √ ∛ ⁿ√ x^ log□ 10^ e^ Abs ∫ d/dx Σ`;
 * - an editing key: `◀ ▶ ▲ ▼ DEL INS OVR START END`;
 * - `CONSTnn` / `CONVnn` for `SHIFT 7` / `SHIFT 8` nn;
 * - a token, by an override below or by its [Tok.text] (trimmed, or without its trailing `(`)
 *   when that names exactly one token.
 *
 * This is a seam: the machine layer that turns physical [dev.inkstone.core.calc.Key]s into these
 * calls is not under test here, only what each call does to the line.
 */
object Keys {
    private val overrides: Map<String, Tok> = mapOf(
        "A" to Tok.VAR_A, "B" to Tok.VAR_B, "C" to Tok.VAR_C, "D" to Tok.VAR_D, "E" to Tok.VAR_E,
        "F" to Tok.VAR_F, "nPr" to Tok.NPR, "nCr" to Tok.NCR, "DMS" to Tok.DMS, "deg" to Tok.DEG_MARK,
        "×10" to Tok.EXP10, "EQ" to Tok.EQUALS, "hA" to Tok.HEX_A, "hB" to Tok.HEX_B, "hC" to Tok.HEX_C,
        "hD" to Tok.HEX_D, "hE" to Tok.HEX_E, "hF" to Tok.HEX_F, "rA" to Tok.REG_A, "rB" to Tok.REG_B,
        "rC" to Tok.REG_C, "r" to Tok.REG_R, "x²" to Tok.SQUARE, "x³" to Tok.CUBE, "x⁻¹" to Tok.RECIPROCAL,
        "(−)" to Tok.NEGATIVE,
    )

    private val templates: Map<String, TemplateKind> = mapOf(
        "FRAC" to TemplateKind.FRACTION, "MIXED" to TemplateKind.MIXED_FRACTION, "√" to TemplateKind.SQRT,
        "∛" to TemplateKind.CBRT, "ⁿ√" to TemplateKind.NTH_ROOT, "x^" to TemplateKind.POWER,
        "log□" to TemplateKind.LOG_BASE, "10^" to TemplateKind.TEN_POWER, "e^" to TemplateKind.E_POWER,
        "Abs" to TemplateKind.ABS, "∫" to TemplateKind.INTEGRAL, "d/dx" to TemplateKind.DERIVATIVE,
        "Σ" to TemplateKind.SUMMATION,
    )

    private val edits: Map<String, (InputState) -> InputState> = mapOf(
        "◀" to InputState::moveLeft, "▶" to InputState::moveRight, "▲" to InputState::moveUp,
        "▼" to InputState::moveDown, "DEL" to InputState::delete, "OVR" to InputState::toggleOverwrite,
        "START" to InputState::toStart, "END" to InputState::toEnd,
        "INS" to { s -> if (s.natural) s.beginWrap() else s.toggleOverwrite() },
    )

    fun type(keys: String, natural: Boolean = true, start: InputState = InputState.empty(natural)): InputState =
        keys.split(' ').filter { it.isNotEmpty() }.fold(start) { state, word -> press(state, word) }

    fun press(state: InputState, word: String): InputState {
        edits[word]?.let { return it(state) }
        templates[word]?.let { return state.insertTemplate(it) }
        if (word.startsWith("CONST")) return state.insert(ConstantItem(word.removePrefix("CONST").toInt()))
        if (word.startsWith("CONV")) return state.insert(ConversionItem(word.removePrefix("CONV").toInt()))
        return state.insert(tok(word))
    }

    fun tok(word: String): Tok {
        overrides[word]?.let { return it }
        val exact = Tok.entries.filter { it.text.trim() == word }
        if (exact.size == 1) return exact.single()
        val call = Tok.entries.filter { it.text.endsWith("(") && it.text.removeSuffix("(") == word }
        require(call.size == 1) { "key word '$word' names ${exact.size + call.size} tokens" }
        return call.single()
    }
}
