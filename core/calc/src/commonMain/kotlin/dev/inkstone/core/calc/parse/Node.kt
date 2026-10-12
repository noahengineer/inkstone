package dev.inkstone.core.calc.parse

/**
 * The parsed expression, for every mode. The parser decides **structure** only; what a node means
 * — exact or decimal, real or complex, a number or a matrix, legal in this mode or not — is the
 * evaluator's call, because the same tree is evaluated in COMP, CMPLX, BASE-N, MATRIX, … and only
 * the evaluator knows which.
 *
 * Natural-display templates and their LineIO spellings produce the **same** nodes: the `√` template
 * and `√(` are both `Call(SQRT, …)`, `log▪▯` and `log(a;b)` are both `Call(LOG, [a, b])`, the
 * fraction template and `a⌟b` are both [Fraction].
 *
 * Leaves are data classes rather than objects even when they carry one enum, so that every node a
 * parse produces is a distinct instance and [SourceMap] can find it by identity.
 */
sealed interface Node

/**
 * A number as typed. Converting it is the evaluator's job (it knows the mode: in BASE-N the same
 * digits are in the current base). `1,2×10⁻³` is `NumberLit("1", "2", "-3")`.
 *
 * @property intDigits digits before the decimal comma; empty for `,5` and for a bare `×10³`, whose
 *   mantissa is an implied 1 (⚠️ GUESS, as on other Casio models).
 * @property fracDigits null if no comma was typed, else the digits after it (possibly none: `5,`).
 * @property exponent null if `×10` was not typed, else its digits with a leading `-` when `(−)`
 *   signed it.
 */
data class NumberLit(val intDigits: String, val fracDigits: String? = null, val exponent: String? = null) : Node

/** BASE-N: a literal holding hex digits, or one with a `d`/`h`/`b`/`o` prefix ([radix] null = current base). */
data class BaseLit(val radix: Radix?, val digits: String) : Node

enum class Radix { DEC, HEX, BIN, OCT }

/** `a°b'c"`, `a°b'` or `a°` (research 02 §8.9). Minutes and seconds are literals as typed. */
data class Dms(val degrees: Node, val minutes: NumberLit? = null, val seconds: NumberLit? = null) : Node

/** The nine variables (research 02 §9). */
data class Var(val variable: Variable) : Node

enum class Variable { A, B, C, D, E, F, M, X, Y }

/** A named value with no operand. */
data class Sym(val symbol: Symbolic) : Node

enum class Symbolic { ANS, PRE_ANS, PI, E, I, RAN }

/** Scientific constant 1…40 (`SHIFT 7`), see `ScientificConstants`. */
data class Const(val number: Int) : Node

data class MatrixRef(val name: Slot) : Node

data class VectorRef(val name: Slot) : Node

/** MatA/MatB/MatC/MatAns, VctA/…/VctAns. */
enum class Slot { A, B, C, ANS }

/** STAT variables from the `SHIFT 1` sub-menus (research 03 §4.2). */
data class StatVar(val stat: StatVariable) : Node

enum class StatVariable {
    N,
    MEAN_X,
    SIGMA_X,
    SAMPLE_SX,
    MEAN_Y,
    SIGMA_Y,
    SAMPLE_SY,
    SUM_X,
    SUM_X2,
    SUM_Y,
    SUM_Y2,
    SUM_XY,
    SUM_X3,
    SUM_X2Y,
    SUM_X4,
    MIN_X,
    MAX_X,
    MIN_Y,
    MAX_Y,
    Q1,
    MEDIAN,
    Q3,
    REG_A,
    REG_B,
    REG_C,
    REG_R,
}

/** The `(−)` key (priority level 5), and a `−` typed where a value is expected. */
data class Negate(val operand: Node) : Node

data class Binary(val op: BinOp, val left: Node, val right: Node) : Node

/**
 * Infix operators with their priority level (research 02 §12; 1 binds tightest). Equal levels
 * associate left to right. [IMPLICIT_MUL] is the omitted `×`, which has its own level 7 so that
 * `1÷2π` is `1÷(2π)`.
 */
enum class BinOp(val level: Int) {
    IMPLICIT_MUL(Levels.IMPLICIT),
    NPR(Levels.PERMUTATION),
    NCR(Levels.PERMUTATION),
    ANGLE(Levels.PERMUTATION),
    DOT(Levels.DOT),
    MUL(Levels.PRODUCT),
    DIV(Levels.PRODUCT),
    DIV_R(Levels.PRODUCT),
    ADD(Levels.SUM),
    SUB(Levels.SUM),
    AND(Levels.AND),
    OR(Levels.OR),
    XOR(Levels.OR),
    XNOR(Levels.OR),
}

/**
 * Postfix operators: level 3 (powers, `!`, `%`, angle marks, `▶t`), level 6 (STAT estimates), and
 * the result-format commands `▶r∠θ` / `▶a+bi`, which close a whole statement (research 03 §3).
 */
enum class PostfixOp(val level: Int) {
    SQUARE(Levels.POSTFIX),
    CUBE(Levels.POSTFIX),
    RECIPROCAL(Levels.POSTFIX),
    FACTORIAL(Levels.POSTFIX),
    PERCENT(Levels.POSTFIX),
    DEG(Levels.POSTFIX),
    RAD(Levels.POSTFIX),
    GRAD(Levels.POSTFIX),
    TO_T(Levels.POSTFIX),
    EST_X(Levels.CONVERSION),
    EST_X1(Levels.CONVERSION),
    EST_X2(Levels.CONVERSION),
    EST_Y(Levels.CONVERSION),
    TO_POLAR(Levels.STATEMENT),
    TO_RECT(Levels.STATEMENT),
}

data class Postfix(val op: PostfixOp, val operand: Node) : Node

/** Metric conversion 1…40 applied to [operand] (`SHIFT 8`, level 6), see `MetricConversions`. */
data class Convert(val number: Int, val operand: Node) : Node

/** x^□ and `^(` (level 3). */
data class Power(val base: Node, val exponent: Node) : Node

/** ⁿ√ template and LineIO `nˣ√(…)` (level 3). */
data class Root(val index: Node, val radicand: Node) : Node

/** The fraction template and LineIO `a⌟b` (level 4). */
data class Fraction(val numerator: Node, val denominator: Node) : Node

/** The mixed-fraction template and LineIO `a⌟b⌟c`. */
data class MixedFraction(val whole: Node, val numerator: Node, val denominator: Node) : Node

/** A function with its argument list (level 2). [Fn.arity] has been checked. */
data class Call(val fn: Fn, val args: List<Node>) : Node

/** ∫ (research 02 §10.7). [tol] is only ever typed in LineIO. */
data class Integral(val f: Node, val lower: Node, val upper: Node, val tol: Node? = null) : Node

/** d/dx at [at] (research 02 §10.8). */
data class Derivative(val f: Node, val at: Node, val tol: Node? = null) : Node

/** Σ from [lower] to [upper] (research 02 §10.9). */
data class Summation(val f: Node, val lower: Node, val upper: Node) : Node

/**
 * CALC and SOLVE (research 02 §10.14–10.15): `lhs = rhs`, `lhs = rhs ; V` (solve for V), or
 * `expr ; V` (`= 0` implied, [rhs] null). Only at the top of a statement.
 */
data class Equation(val lhs: Node, val rhs: Node?, val solveFor: Variable? = null) : Node

/** `a:b:c`, run one per `=` (research 02 §10.1). Only at the top. */
data class Statements(val list: List<Node>) : Node

/** The priority levels of research 02 §12, by name. */
object Levels {
    const val POSTFIX = 3
    const val FRACTION = 4
    const val NEGATIVE = 5
    const val CONVERSION = 6
    const val IMPLICIT = 7
    const val PERMUTATION = 8
    const val DOT = 9
    const val PRODUCT = 10
    const val SUM = 11
    const val AND = 12
    const val OR = 13

    /** Not in the guide's table: `▶r∠θ` / `▶a+bi` apply to everything before them. */
    const val STATEMENT = 14
}
