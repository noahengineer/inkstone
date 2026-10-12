package dev.inkstone.core.calc

/**
 * The eleven calculation modes, in MODE-menu order (research 02 §4). Page 1 holds the first
 * eight (`MODE 1`…`MODE 8`), page 2 the last three (`MODE ▼ 1`…`MODE ▼ 3`).
 */
enum class Mode(val menuLabel: String) {
    COMP("COMP"),
    CMPLX("CMPLX"),
    STAT("STAT"),
    BASE_N("BASE-N"),
    EQN("EQN"),
    MATRIX("MATRIX"),
    TABLE("TABLE"),
    VECTOR("VECTOR"),
    DIST("DIST"),
    INEQ("INEQ"),
    RATIO("RATIO"),
}

/** SETUP 1/2: `MthIO` with `MathO` or `LineO` results, or `LineIO` (research 02 §5.2). */
enum class InputOutput {
    /** Natural input, natural results (the default). The `Math` indicator is on. */
    MATH_MATH,

    /** Natural input, linear (decimal) results. */
    MATH_LINE,

    /** Linear input and output: `√(`, `^(`, `⌟` and `;` instead of templates. */
    LINE_LINE,
    ;

    val naturalInput: Boolean get() = this != LINE_LINE
    val naturalOutput: Boolean get() = this == MATH_MATH
}

enum class AngleUnit { DEG, RAD, GRA }

/** SETUP 6/7/8. `Sci 0` means ten significant digits (research 02 §1 Z5). */
sealed interface NumberFormat {
    data class Fix(val places: Int) : NumberFormat

    data class Sci(val digits: Int) : NumberFormat

    /** [range] is 1 or 2. Norm 2 is the ZA PLUS II's default (research 02 §1 Z3). */
    data class Norm(val range: Int) : NumberFormat
}

/** SETUP ▼1/▼2: `ab/c` (mixed) or `d/c` (improper, the default). */
enum class FractionFormat { MIXED, IMPROPER }

/** SETUP ▼3: CMPLX results as `a+bi` (default) or `r∠θ`. */
enum class ComplexFormat { RECTANGULAR, POLAR }

/** SETUP ▼5: TABLE with `f(x)` only, or `f(x),g(x)` (the ZA default). */
enum class TableFunctions { F_ONLY, F_AND_G }

/**
 * Every SETUP item (research 02 §5.2). The defaults are the ZA PLUS II's initialisation table,
 * which is also what `SHIFT 9 1 =` (Clear Setup) restores — except [contrast], which no reset
 * touches.
 */
data class Setup(
    val io: InputOutput = InputOutput.MATH_MATH,
    val angle: AngleUnit = AngleUnit.DEG,
    val format: NumberFormat = NumberFormat.Norm(2),
    val fraction: FractionFormat = FractionFormat.IMPROPER,
    val complex: ComplexFormat = ComplexFormat.RECTANGULAR,
    val statFrequency: Boolean = false,
    val table: TableFunctions = TableFunctions.F_AND_G,
    val autoPowerOffMinutes: Int = 10,
    val contrast: Int = DEFAULT_CONTRAST,
) {
    companion object {
        /** 26 levels (research 01 §3.1); the real default is unknown, so the middle one. */
        const val MIN_CONTRAST = 0
        const val MAX_CONTRAST = 25
        const val DEFAULT_CONTRAST = 13
    }
}

/**
 * The calculator's error screens (research 02 §11, texts from the sibling firmware's string
 * table, 07 §4.9a). An error is a **value** the engine returns, never an exception.
 */
enum class CalcError(val message: String) {
    MATH("Math ERROR"),
    STACK("Stack ERROR"),
    SYNTAX("Syntax ERROR"),
    ARGUMENT("Argument ERROR"),
    DIMENSION("Dimension ERROR"),
    VARIABLE("Variable ERROR"),
    TIME_OUT("Time Out"),
    CANT_SOLVE("Can't Solve"),
    INSUFFICIENT_MEM("Insufficient MEM"),
}
