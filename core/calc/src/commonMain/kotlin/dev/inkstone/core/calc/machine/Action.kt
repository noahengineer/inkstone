package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.Key
import dev.inkstone.core.calc.input.TemplateKind
import dev.inkstone.core.calc.input.Tok

/**
 * Which printed legend a press means: the key cap, the gold SHIFT legend above it at the left, or
 * the magenta ALPHA legend at the right (research 01 §5.1, VERIFIED).
 */
enum class Layer { NONE, SHIFT, ALPHA }

/** One key on one layer — the unit [Keymap]'s tables are written in. */
data class Binding(val layer: Layer, val key: Key)

/**
 * What a press means once SHIFT/ALPHA and the mode have been resolved by [Keymap]. Screens act on
 * this, and look at [Press.key] only where the calculator itself reads the physical key (a menu's
 * digit, STO's variable, which needs no ALPHA — research 06 §5.25).
 */
sealed interface Action {
    /** Types one token at the cursor: `7`, `sin(`, the variable `A`, `;`. */
    data class Type(val tok: Tok) : Action

    /**
     * A template key. Natural display opens the template; LineIO types its linear spelling instead
     * (research 02 §7.4) — [dev.inkstone.core.calc.input.InputState.insertTemplate] decides which.
     */
    data class Open(val kind: TemplateKind) : Action

    /** Every key that is not typing. */
    data class Do(val command: Command) : Action

    /**
     * A combination with no legend (`SHIFT 6`, `ALPHA sin` in BASE-N, the cursor keys under
     * SHIFT): it does nothing, but it still spends the S/A latch. Research 06 §4 calls these
     * UNKNOWN with "the usual Casio behaviour is that they do nothing" (INFERRED).
     */
    data object None : Action
}

/** The non-typing functions, named after their legends (research 06 §4, 01 §5). */
enum class Command {
    LEFT,
    RIGHT,
    UP,
    DOWN,
    DEL,

    /** `SHIFT DEL`: wrap in Natural display, overwrite in LineIO (research 02 §7.2, §7.5). */
    INS,
    AC,

    /** `SHIFT AC`. */
    OFF,
    EQUALS,

    /** `SHIFT =`: a decimal result in Natural display (research 02 §8.8). */
    EQUALS_DECIMAL,
    S_TO_D,

    /** `SHIFT S⇔D`: a b/c ⇔ d/c. */
    MIXED,
    ENG,

    /** `SHIFT ENG` (←). */
    ENG_LEFT,

    /** `SHIFT °'"`. */
    FACT,
    STO,
    RCL,
    M_PLUS,
    M_MINUS,
    CALC,
    SOLVE,
    MODE,
    SETUP,
    CLR,

    /** The `hyp` menu (research 02 §10.5). */
    HYP,

    /** `SHIFT Ans`: the DRG▶ menu (research 02 §10.4). */
    DRG,

    /** `SHIFT 7`. */
    CONST,

    /** `SHIFT 8`. */
    CONV,

    /**
     * The current mode's own `SHIFT` digit menu: `SHIFT 1` STAT/DIST, `SHIFT 2` CMPLX, `SHIFT 3`
     * BASE, `SHIFT 4` MATRIX, `SHIFT 5` VECTOR. Each legend is bracketed because it works only in
     * its own mode (research 01 §5.1), so [Keymap] produces this only there.
     */
    MODE_MENU,

    /** BASE-N's cyan legends, pressed without SHIFT: `x²` DEC, `x^` HEX, `log` BIN, `ln` OCT. */
    BASE_DEC,
    BASE_HEX,
    BASE_BIN,
    BASE_OCT,
}

/**
 * One press, as a screen receives it: the physical [key], the [layer] the S/A latch gave it, what
 * it [action]s to, and the [interrupt] that long evaluations poll so `AC` can break them (research
 * 07 §4.6, `Calculator.press`).
 */
class Press(val key: Key, val layer: Layer, val action: Action, val interrupt: () -> Boolean = { false }) {
    /** The command, or null for typing keys. */
    val command: Command? get() = (action as? Action.Do)?.command

    /** 0–9 when this is an unshifted number key — how menus and prompts take a digit — else -1. */
    val digit: Int get() = if (layer == Layer.NONE) key.digit else -1
}
