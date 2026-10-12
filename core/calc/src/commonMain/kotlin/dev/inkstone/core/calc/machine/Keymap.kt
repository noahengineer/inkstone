package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.Key
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.input.TemplateKind
import dev.inkstone.core.calc.input.Tok

/**
 * Physical key + S/A latch + mode → [Action], as data: one table per layer for what is printed
 * on and above every key (research 01 §5.2–5.4, 06 §4, both VERIFIED from Casio's render and the
 * guide's key sequences), and one table per mode for the legends that only work there.
 *
 * Lookup order: a [ModeController]'s own overlay, then the built-in mode table, then the layer
 * table, then [Action.None]. `SHIFT`, `ALPHA` and `ON` are not in any table — the machine handles
 * them before resolving, because they act on the latch and the power rather than on a screen.
 */
object Keymap {
    fun resolve(key: Key, layer: Layer, mode: Mode, overlay: Map<Binding, Action> = emptyMap()): Action {
        val binding = Binding(layer, key)
        return overlay[binding] ?: MODES[mode]?.get(binding) ?: BASE[binding] ?: Action.None
    }

    private fun type(tok: Tok): Action = Action.Type(tok)

    private fun open(kind: TemplateKind): Action = Action.Open(kind)

    private fun run(command: Command): Action = Action.Do(command)

    private fun layer(layer: Layer, keys: Map<Key, Action>): Map<Binding, Action> =
        keys.mapKeys { (key, _) -> Binding(layer, key) }

    /** What is printed on each key cap (research 01 §5.2–5.4). */
    private val PLAIN: Map<Key, Action> = mapOf(
        Key.UP to run(Command.UP),
        Key.LEFT to run(Command.LEFT),
        Key.RIGHT to run(Command.RIGHT),
        Key.DOWN to run(Command.DOWN),
        Key.MODE to run(Command.MODE),
        Key.CALC to run(Command.CALC),
        Key.INTEGRAL to open(TemplateKind.INTEGRAL),
        Key.RECIPROCAL to type(Tok.RECIPROCAL),
        Key.LOG_BASE to open(TemplateKind.LOG_BASE),
        Key.FRACTION to open(TemplateKind.FRACTION),
        Key.SQRT to open(TemplateKind.SQRT),
        Key.SQUARE to type(Tok.SQUARE),
        Key.POWER to open(TemplateKind.POWER),
        Key.LOG to type(Tok.LOG),
        Key.LN to type(Tok.LN),
        Key.NEGATIVE to type(Tok.NEGATIVE),
        Key.DMS to type(Tok.DMS),
        Key.HYP to run(Command.HYP),
        Key.SIN to type(Tok.SIN),
        Key.COS to type(Tok.COS),
        Key.TAN to type(Tok.TAN),
        Key.RCL to run(Command.RCL),
        Key.ENG to run(Command.ENG),
        Key.OPEN_PAREN to type(Tok.OPEN),
        Key.CLOSE_PAREN to type(Tok.CLOSE),
        Key.S_TO_D to run(Command.S_TO_D),
        Key.M_PLUS to run(Command.M_PLUS),
        Key.D7 to type(Tok.D7),
        Key.D8 to type(Tok.D8),
        Key.D9 to type(Tok.D9),
        Key.DEL to run(Command.DEL),
        Key.AC to run(Command.AC),
        Key.D4 to type(Tok.D4),
        Key.D5 to type(Tok.D5),
        Key.D6 to type(Tok.D6),
        Key.MULTIPLY to type(Tok.TIMES),
        Key.DIVIDE to type(Tok.DIVIDE),
        Key.D1 to type(Tok.D1),
        Key.D2 to type(Tok.D2),
        Key.D3 to type(Tok.D3),
        Key.PLUS to type(Tok.PLUS),
        Key.MINUS to type(Tok.MINUS),
        Key.D0 to type(Tok.D0),
        Key.DECIMAL to type(Tok.DECIMAL),
        Key.EXP to type(Tok.EXP10),
        Key.ANS to type(Tok.ANS),
        Key.EQUALS to run(Command.EQUALS),
    )

    /**
     * The gold legends (research 01 §5.3–5.4). The bracketed mode menus (`SHIFT 1`–`5`) and the
     * violet `∠` are left out here and added by [MODES] for the one mode each belongs to; `SHIFT 6`
     * has nothing printed, and `SHIFT =` has no legend but gives a decimal result (research 06 §4).
     */
    private val SHIFTED: Map<Key, Action> = mapOf(
        Key.MODE to run(Command.SETUP),
        Key.CALC to run(Command.SOLVE),
        Key.INTEGRAL to open(TemplateKind.DERIVATIVE),
        Key.RECIPROCAL to type(Tok.FACTORIAL),
        Key.LOG_BASE to open(TemplateKind.SUMMATION),
        Key.FRACTION to open(TemplateKind.MIXED_FRACTION),
        Key.SQRT to open(TemplateKind.CBRT),
        Key.SQUARE to type(Tok.CUBE),
        Key.POWER to open(TemplateKind.NTH_ROOT),
        Key.LOG to open(TemplateKind.TEN_POWER),
        Key.LN to open(TemplateKind.E_POWER),
        Key.DMS to run(Command.FACT),
        Key.HYP to open(TemplateKind.ABS),
        Key.SIN to type(Tok.ASIN),
        Key.COS to type(Tok.ACOS),
        Key.TAN to type(Tok.ATAN),
        Key.RCL to run(Command.STO),
        Key.ENG to run(Command.ENG_LEFT),
        Key.OPEN_PAREN to type(Tok.PERCENT),
        Key.CLOSE_PAREN to type(Tok.SEMICOLON),
        Key.S_TO_D to run(Command.MIXED),
        Key.M_PLUS to run(Command.M_MINUS),
        Key.D7 to run(Command.CONST),
        Key.D8 to run(Command.CONV),
        Key.D9 to run(Command.CLR),
        Key.DEL to run(Command.INS),
        Key.AC to run(Command.OFF),
        Key.MULTIPLY to type(Tok.NPR),
        Key.DIVIDE to type(Tok.NCR),
        Key.PLUS to type(Tok.POL),
        Key.MINUS to type(Tok.REC),
        Key.D0 to type(Tok.RND),
        Key.DECIMAL to type(Tok.RAN),
        Key.EXP to type(Tok.PI),
        Key.ANS to run(Command.DRG),
        Key.EQUALS to run(Command.EQUALS_DECIMAL),
    )

    /**
     * The magenta legends (research 01 §5.3–5.4; C–F on `hyp`/`sin`/`cos`/`tan` per research 00
     * C7). `ALPHA FRAC` (÷R) and `ALPHA Ans` (PreAns) are COMP-only and live in [MODES].
     */
    private val ALPHA: Map<Key, Action> = mapOf(
        Key.CALC to type(Tok.EQUALS),
        Key.INTEGRAL to type(Tok.COLON),
        Key.NEGATIVE to type(Tok.VAR_A),
        Key.DMS to type(Tok.VAR_B),
        Key.HYP to type(Tok.VAR_C),
        Key.SIN to type(Tok.VAR_D),
        Key.COS to type(Tok.VAR_E),
        Key.TAN to type(Tok.VAR_F),
        Key.CLOSE_PAREN to type(Tok.VAR_X),
        Key.S_TO_D to type(Tok.VAR_Y),
        Key.M_PLUS to type(Tok.VAR_M),
        Key.MULTIPLY to type(Tok.GCD),
        Key.DIVIDE to type(Tok.LCM),
        Key.DECIMAL to type(Tok.RAN_INT),
        Key.EXP to type(Tok.E),
    )

    private val BASE: Map<Binding, Action> =
        layer(Layer.NONE, PLAIN) + layer(Layer.SHIFT, SHIFTED) + layer(Layer.ALPHA, ALPHA)

    private fun shift(key: Key) = Binding(Layer.SHIFT, key)

    private fun plain(key: Key) = Binding(Layer.NONE, key)

    /**
     * PreAns is "COMP only" (research 02 §9, VERIFIED). ÷R is only *documented* in COMP — "the
     * guide's examples are in COMP. Other modes are U" (research 06 §5.6), and research 02 §1 Z8
     * lists it among COMP's extras — so leaving it dead elsewhere is ⚠️ GUESS.
     */
    private val COMP: Map<Binding, Action> = mapOf(
        Binding(Layer.ALPHA, Key.FRACTION) to type(Tok.DIV_R),
        Binding(Layer.ALPHA, Key.ANS) to type(Tok.PRE_ANS),
    )

    /** `ENG` types i, `SHIFT (−)` the violet ∠, `SHIFT 2` the CMPLX menu (research 03 §3, VERIFIED). */
    private val CMPLX: Map<Binding, Action> = mapOf(
        plain(Key.ENG) to type(Tok.IMAGINARY),
        shift(Key.NEGATIVE) to type(Tok.ANGLE),
        shift(Key.D2) to run(Command.MODE_MENU),
    )

    /**
     * BASE-N (research 03 §5.1–5.3, VERIFIED): the cyan base keys and hex digits pressed without
     * ALPHA, `SHIFT 3` the BASE menu; CONST and CONV are not available (research 03 §5.3). The
     * comma and `×10ˣ` are refused because BASE-N takes "no fractions and no exponents" — that
     * the keys do nothing rather than raise an error is ⚠️ GUESS.
     */
    private val BASE_N: Map<Binding, Action> = mapOf(
        plain(Key.SQUARE) to run(Command.BASE_DEC),
        plain(Key.POWER) to run(Command.BASE_HEX),
        plain(Key.LOG) to run(Command.BASE_BIN),
        plain(Key.LN) to run(Command.BASE_OCT),
        plain(Key.NEGATIVE) to type(Tok.HEX_A),
        plain(Key.DMS) to type(Tok.HEX_B),
        plain(Key.HYP) to type(Tok.HEX_C),
        plain(Key.SIN) to type(Tok.HEX_D),
        plain(Key.COS) to type(Tok.HEX_E),
        plain(Key.TAN) to type(Tok.HEX_F),
        plain(Key.DECIMAL) to Action.None,
        plain(Key.EXP) to Action.None,
        shift(Key.D3) to run(Command.MODE_MENU),
        shift(Key.D7) to Action.None,
        shift(Key.D8) to Action.None,
    )

    private val MODES: Map<Mode, Map<Binding, Action>> = mapOf(
        Mode.COMP to COMP,
        Mode.CMPLX to CMPLX,
        Mode.BASE_N to BASE_N,
        Mode.STAT to mapOf(shift(Key.D1) to run(Command.MODE_MENU)),
        Mode.DIST to mapOf(shift(Key.D1) to run(Command.MODE_MENU)),
        Mode.MATRIX to mapOf(shift(Key.D4) to run(Command.MODE_MENU)),
        Mode.VECTOR to mapOf(shift(Key.D5) to run(Command.MODE_MENU)),
        // CONV "can be used in every mode except BASE-N and TABLE" (research 03 §15, VERIFIED).
        Mode.TABLE to mapOf(shift(Key.D8) to Action.None),
    )
}
