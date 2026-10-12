package dev.inkstone.core.calc.mode

/**
 * STAT's eight calculation types, in the type menu's order with its exact labels (research 03 §4,
 * VERIFIED screen `img36`: `1:1-VAR 2:A+BX / 3:_+CX² 4:ln X / 5:e^X 6:A•B^X / 7:A•X^B 8:1/X`).
 * Every type but 1-VAR is a paired-variable regression; what each fits is [StatRegression]'s.
 */
enum class StatType(val label: String, val paired: Boolean) {
    /** One variable, X (with FREQ when SETUP says so). */
    ONE_VAR("1-VAR", false),

    /** y = A + Bx. */
    LINEAR("A+BX", true),

    /** y = A + Bx + Cx² — the only type with C, x̂₁ and x̂₂ and without r (research 03 §4.3). */
    QUADRATIC("_+CX²", true),

    /** y = A + B·ln x. */
    LOGARITHMIC("ln X", true),

    /** y = A·e^(Bx). */
    E_EXPONENTIAL("e^X", true),

    /** y = A·B^x. */
    AB_EXPONENTIAL("A•B^X", true),

    /** y = A·x^B. */
    POWER("A•X^B", true),

    /** y = A + B/x. */
    INVERSE("1/X", true),
}

/** One column of the Statistics Editor, with the header it shows (research 03 §4.1, VERIFIED). */
enum class StatColumn(val header: String) {
    X("X"),
    Y("Y"),
    FREQ("FREQ"),
}

/**
 * Which columns the Statistics Editor shows, and how many rows it holds — decided by the type
 * (one variable or paired) and SETUP ▼4 STAT (`Frequency?` ON adds the FREQ column, research 02
 * §5.2, 03 §2.3).
 *
 * The row capacities are the guide's as printed: 40 for X alone and for X, Y; 20 for X, FREQ; 26
 * for X, Y, FREQ (research 03 §4.1, VERIFIED). That two columns hold fewer rows than three looks
 * inverted, and the international ES PLUS prints 80/40/40/26 — but the ZA guide and the North
 * American one both print these, so they are kept as printed until the emulator says otherwise
 * (research 00 C11).
 */
data class StatLayout(val paired: Boolean, val frequency: Boolean) {
    val columns: List<StatColumn> = buildList {
        add(StatColumn.X)
        if (paired) add(StatColumn.Y)
        if (frequency) add(StatColumn.FREQ)
    }

    val capacity: Int = when {
        paired && frequency -> PAIRED_WITH_FREQUENCY
        frequency -> SINGLE_WITH_FREQUENCY
        else -> WITHOUT_FREQUENCY
    }

    companion object {
        const val WITHOUT_FREQUENCY = 40
        const val SINGLE_WITH_FREQUENCY = 20
        const val PAIRED_WITH_FREQUENCY = 26

        /** The most rows any layout holds: what the table is sized for. */
        const val MAX_ROWS = WITHOUT_FREQUENCY

        fun of(type: StatType, frequency: Boolean): StatLayout = StatLayout(type.paired, frequency)
    }
}
