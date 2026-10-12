package dev.inkstone.core.calc.input

/**
 * The input line while it is being typed: the expression, where the cursor is, and the two modes
 * that change what a key does to it (research 02 §7).
 *
 * Immutable: every operation returns a new state, or **this same state when the key is refused**
 * (a full buffer, `x² x²`, DEL on an empty line…) — which is what the real calculator does with a
 * key it will not take. A refused edit returns this very instance, so `===` tells the machine a
 * key was ignored; a move that goes nowhere is merely equal (`==`).
 *
 * Keys are not modelled here: the machine layer decides that `FRAC` means [insertTemplate] with
 * [TemplateKind.FRACTION], and this class decides what that does in each display mode.
 *
 * @property natural MthIO input (templates) when true, LineIO when false.
 * @property overwrite LineIO's overwrite mode (`SHIFT DEL`); always false in Natural display, which
 *   "is always in insert mode" (research 02 §7.2).
 * @property wrapPending INS was pressed in Natural display: the next template key wraps the value
 *   right of the cursor (research 02 §7.5). Any other key cancels it.
 */
data class InputState(
    val expr: Expr = Expr.EMPTY,
    val cursor: Cursor = Cursor(),
    val natural: Boolean = true,
    val overwrite: Boolean = false,
    val wrapPending: Boolean = false,
) {
    /** Bytes the expression occupies, see [InputBytes]. */
    val bytesUsed: Int get() = InputBytes.of(expr)

    val bytesLeft: Int get() = InputBytes.CAPACITY - bytesUsed

    /** Ten bytes or fewer left: the cursor is drawn as a filled block (research 02 §7.2). */
    val nearlyFull: Boolean get() = bytesLeft <= InputBytes.BLOCK_CURSOR_THRESHOLD

    /** Type one token at the cursor. */
    fun insert(tok: Tok): InputState = Editing.insertItem(settled(), TokenItem(tok)) ?: this

    /**
     * Type a constant (`SHIFT 7` nn) or a conversion (`SHIFT 8` nn); a [TokenItem] is the same as
     * [insert] with its token, and a prebuilt [Template] is inserted whole with the cursor after it.
     */
    fun insert(item: Item): InputState = when (item) {
        is TokenItem -> insert(item.tok)
        else -> Editing.insertItem(settled(), item) ?: this
    }

    /**
     * A template key. In LineIO the same key types its linear spelling instead (research 02 §7.4):
     * `⌟` for both fraction keys, `√(`, `∛(`, `ˣ√(`, `^(`, `10^(`, `e^(`, `Abs(`, `∫(`, `d/dx(`,
     * `Σ(`. `log□` types `log(` in LineIO — ⚠️ GUESS: the guide calls the template "MathO only"
     * without saying what the key does otherwise, and `log(a;b)` is the same function.
     */
    fun insertTemplate(kind: TemplateKind): InputState {
        if (natural) return Editing.insertTemplate(this, kind) ?: this
        val tok = lineSpelling(kind) ?: return this
        return insert(tok)
    }

    /** DEL; see [Editing.delete] for templates. */
    fun delete(): InputState = Editing.delete(settled()) ?: this

    /** ◀. Natural display wraps from the start to the end (research 02 §7.3); LineIO stops. */
    fun moveLeft(): InputState = moved(Navigation.left(expr, cursor, wrap = natural))

    /** ▶. Natural display wraps from the end to the start (research 02 §7.3); LineIO stops. */
    fun moveRight(): InputState = moved(Navigation.right(expr, cursor, wrap = natural))

    /** ▲: up a template level in Natural display; the start of the line in LineIO (research 02 §7.3). */
    fun moveUp(): InputState = if (natural) moved(Navigation.vertical(expr, cursor, down = false)) else toStart()

    /** ▼: down a template level in Natural display; the end of the line in LineIO. */
    fun moveDown(): InputState = if (natural) moved(Navigation.vertical(expr, cursor, down = true)) else toEnd()

    fun toStart(): InputState = moved(Cursor())

    fun toEnd(): InputState = moved(Cursor(index = expr.items.size))

    /** `SHIFT DEL` in Natural display. In LineIO the same key is [toggleOverwrite]. */
    fun beginWrap(): InputState = if (natural) copy(wrapPending = true) else this

    /** `SHIFT DEL` in LineIO. Natural display has no overwrite mode, so it is refused there. */
    fun toggleOverwrite(): InputState = if (natural) this else copy(overwrite = !overwrite)

    private fun moved(to: Cursor): InputState = copy(cursor = to, wrapPending = false)

    private fun settled(): InputState = if (wrapPending) copy(wrapPending = false) else this

    companion object {
        /** An empty line in the given display mode; switching to MthIO forces insert mode. */
        fun empty(natural: Boolean = true): InputState = InputState(natural = natural)

        /** An existing expression opened for editing with the cursor at its end (replay, ◀ after `=`). */
        fun editing(expr: Expr, natural: Boolean): InputState =
            InputState(expr = expr, cursor = Cursor(index = expr.items.size), natural = natural)

        /** What a template key types in LineIO instead; null for the result-only SCI_EXPONENT. */
        internal fun lineSpelling(kind: TemplateKind): Tok? = LINE_SPELLING[kind]

        private val LINE_SPELLING: Map<TemplateKind, Tok> = mapOf(
            TemplateKind.FRACTION to Tok.FRAC_SEP,
            TemplateKind.MIXED_FRACTION to Tok.FRAC_SEP,
            TemplateKind.SQRT to Tok.SQRT_FN,
            TemplateKind.CBRT to Tok.CBRT_FN,
            TemplateKind.NTH_ROOT to Tok.ROOT_FN,
            TemplateKind.POWER to Tok.POWER_FN,
            TemplateKind.LOG_BASE to Tok.LOG,
            TemplateKind.TEN_POWER to Tok.TEN_POW_FN,
            TemplateKind.E_POWER to Tok.E_POW_FN,
            TemplateKind.ABS to Tok.ABS_FN,
            TemplateKind.INTEGRAL to Tok.INTEGRAL_FN,
            TemplateKind.DERIVATIVE to Tok.DERIVATIVE_FN,
            TemplateKind.SUMMATION to Tok.SUM_FN,
        )
    }
}
