package dev.inkstone.core.calc

/**
 * The fifty physical keys of the fx-991ZA PLUS II, named after what is printed **on** the key
 * (docs/calculator/research/01-hardware-and-keys.md §5). What a key *does* depends on SHIFT,
 * ALPHA and the mode; that is decided by the engine, never by the UI. The UI only reports which
 * physical key was pressed — exactly like the real keyboard matrix.
 *
 * Declared in physical order: the top cluster, then the function block row by row, then the
 * number pad row by row. The UI owns the geometry (research 01 §4.2 and Appendix A).
 */
enum class Key {
    // Top cluster. The four cursor keys sit between ALPHA and MODE and have no printed arrows.
    SHIFT,
    ALPHA,
    UP,
    LEFT,
    RIGHT,
    DOWN,
    MODE,
    ON,

    // Function row 1: two keys either side of the cursor pad.
    CALC,
    INTEGRAL,
    RECIPROCAL,
    LOG_BASE,

    // Function rows 2–4, six keys each.
    FRACTION,
    SQRT,
    SQUARE,
    POWER,
    LOG,
    LN,

    NEGATIVE,
    DMS,
    HYP,
    SIN,
    COS,
    TAN,

    RCL,
    ENG,
    OPEN_PAREN,
    CLOSE_PAREN,
    S_TO_D,
    M_PLUS,

    // Number pad, five keys per row.
    D7,
    D8,
    D9,
    DEL,
    AC,

    D4,
    D5,
    D6,
    MULTIPLY,
    DIVIDE,

    D1,
    D2,
    D3,
    PLUS,
    MINUS,

    D0,
    DECIMAL,
    EXP,
    ANS,
    EQUALS,
    ;

    /** The digit this key types, or -1 if it is not a digit key. */
    val digit: Int
        get() = DIGITS.indexOf(this)

    private companion object {
        val DIGITS = listOf(D0, D1, D2, D3, D4, D5, D6, D7, D8, D9)
    }
}
