package dev.inkstone.core.calc.parse

/**
 * Functions that take an argument list (priority level 2), with how many arguments each accepts.
 * Template functions share these entries with their LineIO spellings: [SQRT] is `√□` and `√(`,
 * [TEN_POW] is `10^□` and `10^(`, [EXP] is `e^□` and `e^(`, [ABS] is `|□|` and `Abs(`, and [LOG]
 * is `log(x)`, `log(a;b)` and `log▪▯` (base first, research 02 §10.6).
 *
 * The argument counts are the guide's syntax (research 06 §5): `GCD(a;b)` "exactly 2 arguments",
 * `Pol(x;y)`, `RanInt#(a;b)`. Any other count is a Syntax ERROR — ⚠️ GUESS: the guide has an
 * Argument ERROR too and never says which a wrong count raises.
 */
enum class Fn(val minArgs: Int = 1, val maxArgs: Int = 1) {
    SIN,
    COS,
    TAN,
    ASIN,
    ACOS,
    ATAN,
    SINH,
    COSH,
    TANH,
    ASINH,
    ACOSH,
    ATANH,
    LOG(1, 2),
    LN,
    SQRT,
    CBRT,
    TEN_POW,
    EXP,
    ABS,
    POL(2, 2),
    REC(2, 2),
    RND,
    GCD(2, 2),
    LCM(2, 2),
    RAN_INT(2, 2),
    ARG,
    CONJG,
    NOT,
    NEG,
    DET,
    TRN,
    REF,
    RREF,

    /** STAT `P(t)`, `Q(t)`, `R(t)` (research 03 §4.4). */
    DIST_P,
    DIST_Q,
    DIST_R,
    ;

    fun accepts(count: Int): Boolean = count in minArgs..maxArgs
}
