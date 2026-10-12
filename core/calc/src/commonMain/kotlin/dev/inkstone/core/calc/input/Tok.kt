package dev.inkstone.core.calc.input

/**
 * Every atomic token an expression can hold — the calculator's "bytes" (research 02 §7).
 *
 * [text] is what the token looks like on the LCD in **linear** form, character for character.
 * The LCD font must cover every character used here (and in [ScientificConstants] /
 * [MetricConversions]); a test enumerates them. Two-dimensional constructs (fractions, roots,
 * powers, ∫, d/dx, Σ, log with a base, |x|) are [TemplateKind]s in Natural display and the
 * `…FN` / [FRAC_SEP] tokens here in LineIO.
 *
 * Tokens that look alike are kept distinct because they *mean* different things: [MINUS]
 * (subtraction) vs [NEGATIVE] (the `(−)` key); variable [VAR_A] vs hexadecimal digit [HEX_A] vs
 * regression coefficient [REG_A]. Only the engine's parser cares; the renderer draws by [text].
 *
 * [DMS] is a single token for the `°'"` key; it is drawn as `°`, `'` or `"` depending on whether
 * it is the first, second or third mark in a run of digits (research 02 §8.9) — the renderer
 * decides, by counting.
 */
enum class Tok(val text: String) {
    // ---- Numbers ---------------------------------------------------------------------------
    D0("0"),
    D1("1"),
    D2("2"),
    D3("3"),
    D4("4"),
    D5("5"),
    D6("6"),
    D7("7"),
    D8("8"),
    D9("9"),

    /** The ZA PLUS II's decimal separator is a comma, on the key and on the display. */
    DECIMAL(","),

    /** `×10ˣ` key: the exponent marker of a number literal, drawn as one compact `×₁₀` glyph. */
    EXP10("×₁₀"),

    // ---- Arithmetic ------------------------------------------------------------------------
    PLUS("+"),
    MINUS("−"),
    TIMES("×"),
    DIVIDE("÷"),

    /** `ALPHA FRAC`: remainder division, COMP only. */
    DIV_R("÷R"),

    /** The `(−)` key: a prefix negation. Drawn shorter than [MINUS] on the real LCD. */
    NEGATIVE("-"),
    OPEN("("),
    CLOSE(")"),

    /** `SHIFT )`: the ZA PLUS II's argument separator. */
    SEMICOLON(";"),

    /** `ALPHA ∫`: multi-statement separator. */
    COLON(":"),

    /** `ALPHA CALC`: the equals sign of an equation (CALC, SOLVE). */
    EQUALS("="),

    // ---- Postfix ---------------------------------------------------------------------------
    SQUARE("²"),
    CUBE("³"),
    RECIPROCAL("⁻¹"),
    FACTORIAL("!"),
    PERCENT("%"),
    DMS("°"),

    /** Angle-unit markers from the `DRG▶` menu (`SHIFT Ans`). */
    DEG_MARK("°"),
    RAD_MARK("ʳ"),
    GRAD_MARK("ᵍ"),

    // ---- Infix -----------------------------------------------------------------------------
    NPR("P"),
    NCR("C"),

    /** CMPLX polar entry, `SHIFT (−)`. */
    ANGLE("∠"),

    // ---- Values ----------------------------------------------------------------------------
    PI("π"),
    E("e"),
    IMAGINARY("i"),
    RAN("Ran#"),
    ANS("Ans"),
    PRE_ANS("PreAns"),
    VAR_A("A"),
    VAR_B("B"),
    VAR_C("C"),
    VAR_D("D"),
    VAR_E("E"),
    VAR_F("F"),
    VAR_M("M"),
    VAR_X("X"),
    VAR_Y("Y"),

    // ---- Functions that open a parenthesis --------------------------------------------------
    SIN("sin("),
    COS("cos("),
    TAN("tan("),
    ASIN("sin⁻¹("),
    ACOS("cos⁻¹("),
    ATAN("tan⁻¹("),
    SINH("sinh("),
    COSH("cosh("),
    TANH("tanh("),
    ASINH("sinh⁻¹("),
    ACOSH("cosh⁻¹("),
    ATANH("tanh⁻¹("),

    /** `log(x)` or `log(a;b)` (base a). */
    LOG("log("),
    LN("ln("),
    POL("Pol("),
    REC("Rec("),
    RND("Rnd("),
    GCD("GCD("),
    LCM("LCM("),
    RAN_INT("RanInt#("),

    // LineIO spellings of what Natural display draws as templates.
    ABS_FN("Abs("),
    SQRT_FN("√("),
    CBRT_FN("∛("),
    TEN_POW_FN("10^("),
    E_POW_FN("e^("),
    POWER_FN("^("),

    /** LineIO index root: `5ˣ√(32)` is the fifth root of 32. */
    ROOT_FN("ˣ√("),
    INTEGRAL_FN("∫("),
    DERIVATIVE_FN("d/dx("),
    SUM_FN("Σ("),

    /** LineIO fraction separator: `2⌟3`, and mixed `1⌟2⌟3`. */
    FRAC_SEP("⌟"),

    // ---- CMPLX (SHIFT 2) ---------------------------------------------------------------------
    ARG("arg("),
    CONJG("Conjg("),
    TO_POLAR("▶r∠θ"),
    TO_RECT("▶a+bi"),

    // ---- BASE-N (SHIFT 3) --------------------------------------------------------------------
    AND(" and "),
    OR(" or "),
    XOR(" xor "),
    XNOR(" xnor "),
    NOT("Not("),
    NEG("Neg("),
    PREFIX_DEC("d"),
    PREFIX_HEX("h"),
    PREFIX_BIN("b"),
    PREFIX_OCT("o"),
    HEX_A("A"),
    HEX_B("B"),
    HEX_C("C"),
    HEX_D("D"),
    HEX_E("E"),
    HEX_F("F"),

    // ---- MATRIX (SHIFT 4) --------------------------------------------------------------------
    MAT_A("MatA"),
    MAT_B("MatB"),
    MAT_C("MatC"),
    MAT_ANS("MatAns"),
    DET("det("),
    TRN("Trn("),
    REF("Ref("),
    RREF("Rref("),

    // ---- VECTOR (SHIFT 5) --------------------------------------------------------------------
    VCT_A("VctA"),
    VCT_B("VctB"),
    VCT_C("VctC"),
    VCT_ANS("VctAns"),
    DOT("·"),

    // ---- STAT (SHIFT 1): statistic variables and distribution functions ----------------------
    STAT_N("n"),
    MEAN_X("x̄"),
    SIGMA_X("σx"),
    SAMPLE_SX("sx"),
    MEAN_Y("ȳ"),
    SIGMA_Y("σy"),
    SAMPLE_SY("sy"),
    SUM_X("Σx"),
    SUM_X2("Σx²"),
    SUM_Y("Σy"),
    SUM_Y2("Σy²"),
    SUM_XY("Σxy"),
    SUM_X3("Σx³"),
    SUM_X2Y("Σx²y"),
    SUM_X4("Σx⁴"),
    MIN_X("minX"),
    MAX_X("maxX"),
    MIN_Y("minY"),
    MAX_Y("maxY"),
    Q1("Q1"),
    MEDIAN("med"),
    Q3("Q3"),
    REG_A("A"),
    REG_B("B"),
    REG_C("C"),
    REG_R("r"),

    /** Estimates are postfix: `-130x̂`. */
    EST_X("x̂"),
    EST_X1("x̂₁"),
    EST_X2("x̂₂"),
    EST_Y("ŷ"),
    DIST_P("P("),
    DIST_Q("Q("),
    DIST_R("R("),
    TO_T("▶t"),

    // ---- Result-only tokens: never typed, used by the formatter to lay results out ----------

    /** The `r` of a MathO `Pol` result, `r=2;θ=45` (research 02 §8.1). Not [REG_R]. */
    RESULT_R("r"),
    THETA("θ"),

    /** The `R` of a MathO `÷R` result, `2;R=1` (research 02 §8.1). */
    REMAINDER("R"),

    /** INEQ solutions such as `X≤A;B≤X` (research 03 §11.3). */
    LESS("<"),
    GREATER(">"),
    LESS_EQUAL("≤"),
    GREATER_EQUAL("≥"),
}
