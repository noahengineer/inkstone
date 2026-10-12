package dev.inkstone.app.calculator

import dev.inkstone.core.calc.Key

/** A rectangle on the face, in millimetres from the body's top-left corner. */
internal data class MmRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centreX: Float get() = (left + right) / 2f
    val centreY: Float get() = (top + bottom) / 2f

    /** Half-open, so two rectangles that share an edge never both claim a point on it. */
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    fun inflate(by: Float): MmRect = MmRect(left - by, top - by, right + by, bottom + by)
}

/** How a key is shaped and painted (research 01 §4.3, §6). */
internal enum class KeyKind {
    /** SHIFT, ALPHA, MODE, ON: round, silver-white, spun texture, nothing printed on the key. */
    ROUND,

    /** The four REPLAY keys: pentagons pointing at the pad's centre, no arrows printed. */
    CURSOR,

    /** The white function block, dark-grey caps. */
    FUNCTION,

    /** The grey number pad, white caps. */
    NUMBER,

    /** DEL and AC: the number pad's lime pair. */
    CLEAR,
}

/** The one corner of a block-corner key that is rounded much more than the rest (research 01 §4.3). */
internal enum class BigCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * One physical key: where it is, what is printed on it and above it, and what it is called.
 *
 * @param cx … [height] the key top's centre and size in mm, from research 01 Appendix A and §4.2.
 * @param cap what is printed **on** the key. Empty for the round and cursor keys, which are blank.
 * @param description the key's stable accessibility name — what TalkBack says and what
 *   `uiautomator dump` lists, so an `adb` walk can press `7` by name (CLAUDE.md DoD #6).
 * @param shift the gold SHIFT legend above it; [alpha] the ALPHA (or BASE-N / CMPLX) one.
 * @param name the key's own printed name, for the blank round keys only (`SHIFT`, `MODE`, `ON`…).
 */
internal data class KeySpec(
    val key: Key,
    val kind: KeyKind,
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val cap: List<Glyph>,
    val description: String,
    val shift: Legend? = null,
    val alpha: Legend? = null,
    val name: Legend? = null,
    val corner: BigCorner? = null,
) {
    /** The key top as drawn. */
    val rect: MmRect get() = MmRect(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)

    /**
     * Where a press counts: the key top plus [HIT_MARGIN_MM] all round.
     *
     * A face 270 dp wide makes a function key about 33 × 20 dp — fine for a pen nib, mean for a
     * fingertip — so every key claims half a millimetre of the face around it. **Except the cursor
     * keys**, which interlock 0.05 mm apart (UP's side edge against LEFT's end), so any margin there
     * would make two keys answer one press. `CalculatorKeypadTest` asserts no two targets overlap
     * anywhere on the face.
     */
    val hitRect: MmRect get() = if (kind == KeyKind.CURSOR) rect else rect.inflate(HIT_MARGIN_MM)
}

/**
 * Half a millimetre — under half the narrowest gap between two non-cursor keys: 1.3 mm, between `√▪`
 * (column 2's right edge, 27.0) and `x²` (column 3's left, 28.3).
 */
internal const val HIT_MARGIN_MM = 0.5f

/**
 * The body's own regions, in millimetres from its top-left (research 01 §2.2, measured on Casio's
 * to-scale render at 5.325 px/mm, ±0.3 mm — INFERRED).
 */
internal object FaceMm {
    /** 77 × 161.5 mm — VERIFIED (guide p.91). */
    const val WIDTH = 77f
    const val HEIGHT = 161.5f

    /** ≈ 8 mm plan-view corner (research 01 §2.1, INFERRED from the render's alpha mask). */
    const val CORNER = 8f

    val BODY = MmRect(0f, 0f, WIDTH, HEIGHT)

    /**
     * Where Casio prints its wordmark. Inkstone prints a small `INKSTONE` here and nothing else —
     * no CASIO, no model name (docs/CALCULATOR.md D1).
     */
    val LOGO = MmRect(9.8f, 8.3f, 26.5f, 11.1f)
    val SOLAR = MmRect(34.2f, 6.4f, 66.7f, 18.0f)
    val BEZEL = MmRect(5.8f, 22.9f, 70.8f, 52.0f)

    /** The visible LCD window, 61.4 × 24.6 mm; `CalculatorLcd` fills exactly this. */
    val GLASS = MmRect(7.7f, 25.2f, 69.1f, 49.8f)

    /** The honeycomb plate under the cursor pad, ≈ 19 × 15 mm centred on it (research 01 §2.3). */
    val HONEYCOMB = MmRect(28.9f, 60.4f, 47.9f, 75.4f)

    /** The stadium-shaped wells the round keys sit in, SHIFT+ALPHA and MODE+ON (research 01 §2.3). */
    val WELLS = listOf(MmRect(7.3f, 59.1f, 25.0f, 66.3f), MmRect(51.9f, 59.1f, 69.6f, 66.3f))
}

/**
 * ⭐ All fifty keys of the fx-991ZA PLUS II — where they are, how they are shaped, and every legend
 * printed on or above them (research 01 §4–5 and Appendix A).
 *
 * Plain data, deliberately: `CalculatorKeypadTest` reads it on the JVM to prove there is exactly one
 * entry per `Key`, that no two press targets overlap, and that every one is on the body. The painter
 * draws it and the input handler hit-tests it, so a key cannot be drawn in one place and pressed in
 * another.
 *
 * ## Where the numbers come from
 *
 * Positions use research 01 §4.2's **grid** (column and row centres) rather than each key's own
 * Appendix A measurement. The two agree within the stated ±0.4 mm, and the grid is what the real
 * moulding is; the per-key scatter is measurement noise, and two pieces of it made the cursor pad's
 * DOWN key overlap RIGHT by 0.05 mm. The pad is laid out symmetrically about its centre
 * (38.4, 67.9) for the same reason. Number keys are all 11.1 × 8.2 mm, which Appendix A recommends.
 *
 * ## ⚠️ Branding (docs/CALCULATOR.md D1)
 *
 * Every legend here is a fact about which function a key reaches, written as text and drawn in
 * Inkstone's own type. None of Casio's legend artwork, wordmark or model name is reproduced.
 */
internal object CalculatorKeypad {

    val KEYS: List<KeySpec> = listOf(
        // ---- Top cluster: four blank round keys and the four cursor keys (research 01 §5.2) ----
        round(Key.SHIFT, 11.1f, "SHIFT", name = gold(t("SHIFT"))),
        round(Key.ALPHA, 21.2f, "ALPHA", name = magenta(t("ALPHA"))),
        cursor(Key.UP, 38.4f, 62.8f, 6.3f, 8.5f, "up"),
        cursor(Key.LEFT, 30.7f, 67.9f, 9.0f, 6.0f, "left"),
        cursor(Key.RIGHT, 46.1f, 67.9f, 9.0f, 6.0f, "right"),
        cursor(Key.DOWN, 38.4f, 73.0f, 6.3f, 8.5f, "down"),
        round(Key.MODE, 55.7f, "MODE", name = ink(LegendInk.LABEL, t("MODE")), shift = gold(t("SETUP"))),
        round(Key.ON, 65.8f, "ON", name = ink(LegendInk.LABEL, t("ON"))),

        // ---- Function row 1: two keys either side of the cursor pad (research 01 §5.3) ----
        fn(Key.CALC, 0, 0, listOf(t("CALC")), "CALC", gold(t("SOLVE")), magenta(t("=")), BigCorner.TOP_LEFT),
        fn(
            Key.INTEGRAL,
            1,
            0,
            listOf(t("∫"), limits(HOLLOW, HOLLOW), Glyph.Bar),
            "integral",
            gold(Glyph.Stack(listOf(t("d")), listOf(t("d"), math("x")), bar = true), FILLED),
            magenta(t(":")),
            BigCorner.TOP_RIGHT,
        ),
        fn(
            Key.RECIPROCAL,
            4,
            0,
            listOf(math("x"), sup(t("−1"))),
            "x to the minus 1",
            gold(math("x"), t("!")),
            corner = BigCorner.TOP_LEFT,
        ),
        fn(
            Key.LOG_BASE,
            5,
            0,
            listOf(t("log"), sub(FILLED), HOLLOW),
            "log base",
            gold(t("Σ"), limits(HOLLOW, HOLLOW), FILLED),
            corner = BigCorner.TOP_RIGHT,
        ),

        // ---- Function row 2 ----
        fn(
            Key.FRACTION,
            0,
            1,
            listOf(over(Glyph.Bar, HOLLOW)),
            "fraction",
            gold(FILLED, over(HOLLOW, HOLLOW)),
            magenta(t("÷R")),
        ),
        fn(Key.SQRT, 1, 1, listOf(t("√"), FILLED), "square root", gold(sup(t("3")), t("√"), FILLED)),
        fn(
            Key.SQUARE,
            2,
            1,
            listOf(math("x"), sup(t("2"))),
            "x squared",
            gold(math("x"), sup(t("3"))),
            ink(LegendInk.BASE, t("DEC")),
        ),
        fn(
            Key.POWER,
            3,
            1,
            listOf(math("x"), sup(FILLED)),
            "x to the power",
            gold(sup(FILLED), t("√"), HOLLOW),
            ink(LegendInk.BASE, t("HEX")),
        ),
        fn(Key.LOG, 4, 1, listOf(t("log")), "log", gold(t("10"), sup(FILLED)), ink(LegendInk.BASE, t("BIN"))),
        fn(Key.LN, 5, 1, listOf(t("ln")), "ln", gold(math("e"), sup(FILLED)), ink(LegendInk.BASE, t("OCT"))),

        // ---- Function row 3: A–F in cyan brackets type hex digits directly in BASE-N ----
        fn(
            Key.NEGATIVE,
            0,
            2,
            listOf(t("(−)")),
            "negative",
            gold(t("∠"), bracket = LegendInk.CMPLX),
            magenta(t("A"), bracket = LegendInk.BASE),
        ),
        fn(Key.DMS, 1, 2, listOf(t("°′″")), "degrees minutes seconds", gold(t("FACT")), hex("B")),
        fn(Key.HYP, 2, 2, listOf(t("hyp")), "hyp", gold(t("Abs")), hex("C")),
        fn(Key.SIN, 3, 2, listOf(t("sin")), "sin", gold(t("sin"), sup(t("−1"))), hex("D")),
        fn(Key.COS, 4, 2, listOf(t("cos")), "cos", gold(t("cos"), sup(t("−1"))), hex("E")),
        fn(Key.TAN, 5, 2, listOf(t("tan")), "tan", gold(t("tan"), sup(t("−1"))), hex("F")),

        // ---- Function row 4 ----
        fn(Key.RCL, 0, 3, listOf(t("RCL")), "RCL", gold(t("STO")), corner = BigCorner.BOTTOM_LEFT),
        fn(Key.ENG, 1, 3, listOf(t("ENG")), "ENG", gold(t("←")), ink(LegendInk.CMPLX, math("i"))),
        fn(Key.OPEN_PAREN, 2, 3, listOf(t("(")), "open bracket", gold(t("%"))),
        fn(Key.CLOSE_PAREN, 3, 3, listOf(t(")")), "close bracket", gold(t(";")), magenta(t("X"))),
        fn(
            Key.S_TO_D,
            4,
            3,
            listOf(t("S⇔D")),
            "S to D",
            gold(t("a"), over(t("b"), t("c")), t("⇔"), over(t("d"), t("c"))),
            magenta(t("Y")),
        ),
        fn(Key.M_PLUS, 5, 3, listOf(t("M+")), "M plus", gold(t("M−")), magenta(t("M")), BigCorner.BOTTOM_RIGHT),

        // ---- Number pad (research 01 §5.4). Grey keys, white caps; DEL and AC lime ----
        pad(Key.D7, 0, 0, "7", gold(t("CONST")), corner = BigCorner.TOP_LEFT),
        pad(Key.D8, 1, 0, "8", gold(t("CONV"))),
        pad(Key.D9, 2, 0, "9", gold(t("CLR"))),
        pad(Key.DEL, 3, 0, "DEL", gold(t("INS")), kind = KeyKind.CLEAR),
        pad(Key.AC, 4, 0, "AC", gold(t("OFF")), corner = BigCorner.TOP_RIGHT, kind = KeyKind.CLEAR),

        pad(Key.D4, 0, 1, "4", gold(t("MATRIX"), bracket = LegendInk.MODE)),
        pad(Key.D5, 1, 1, "5", gold(t("VECTOR"), bracket = LegendInk.MODE)),
        // Nothing printed above 6 on this model; the US twin has ┌VERIFY┐ here (research 01 §5.5).
        pad(Key.D6, 2, 1, "6"),
        pad(Key.MULTIPLY, 3, 1, "×", gold(t("nPr")), magenta(t("GCD")), description = "multiply"),
        pad(Key.DIVIDE, 4, 1, "÷", gold(t("nCr")), magenta(t("LCM")), description = "divide"),

        pad(Key.D1, 0, 2, "1", gold(t("STAT/DIST"), bracket = LegendInk.MODE)),
        pad(Key.D2, 1, 2, "2", gold(t("CMPLX"), bracket = LegendInk.CMPLX)),
        pad(Key.D3, 2, 2, "3", gold(t("BASE"), bracket = LegendInk.BASE)),
        pad(Key.PLUS, 3, 2, "+", gold(t("Pol")), description = "plus"),
        pad(Key.MINUS, 4, 2, "−", gold(t("Rec")), description = "minus"),

        pad(Key.D0, 0, 3, "0", gold(t("Rnd")), corner = BigCorner.BOTTOM_LEFT),
        // The decimal COMMA: this model's separator, on the key and on the display (research 01 §1).
        pad(Key.DECIMAL, 1, 3, ",", gold(t("Ran#")), magenta(t("RanInt")), description = "decimal comma"),
        KeySpec(
            Key.EXP, KeyKind.NUMBER, PAD_COLUMNS[2], PAD_ROWS[3], PAD_W, PAD_H,
            listOf(t("×10"), sup(math("x"))), "times ten to the x",
            shift = gold(math("π")), alpha = magenta(math("e")),
        ),
        pad(Key.ANS, 3, 3, "Ans", gold(t("DRG▶")), magenta(t("PreAns"))),
        pad(Key.EQUALS, 4, 3, "=", corner = BigCorner.BOTTOM_RIGHT, description = "equals"),
    )

    private val BY_KEY: Map<Key, KeySpec> = KEYS.associateBy { it.key }

    fun spec(key: Key): KeySpec = BY_KEY.getValue(key)

    /** The key whose press target holds the point ([xMm], [yMm]), or null between keys and off the pad. */
    fun keyAt(xMm: Float, yMm: Float): Key? = KEYS.firstOrNull { it.hitRect.contains(xMm, yMm) }?.key
}

/**
 * ⭐ Which key each pointer on the face is pressing, and which key a release presses — the keypad's
 * whole input rule as plain data, so `KeyPressesTest` can hold it to docs/CALCULATOR.md D5 without a
 * device. `CalculatorFace` feeds it pointer ids and `CalculatorKeypad.keyAt` answers; nothing else.
 *
 * A press registers on **release**, on the key it went down on. Sliding off withdraws it for good
 * (sliding back does not restore it), and a **cancelled** release — Android's palm rejection arrives
 * as a cancel — presses nothing. The real keyboard fires on make; this is a touch-panel decision, not
 * a guess about Casio's firmware.
 */
internal class KeyPresses {
    private val pointers = HashMap<Long, Key?>()

    /** The keys a pointer is on right now and still on the key it went down on: what is drawn darkened. */
    val held: Set<Key> get() = pointers.values.filterNotNullTo(HashSet())

    /** [pointer] went down on [at] (null: between keys). */
    fun down(pointer: Long, at: Key?) {
        pointers[pointer] = at
    }

    /** [pointer] moved to [at]. Off the key it went down on, the press is withdrawn and stays so. */
    fun move(pointer: Long, at: Key?) {
        if (pointer in pointers && pointers[pointer] != at) pointers[pointer] = null
    }

    /** [pointer] lifted over [at]; the key that pressed, or null. A [cancelled] lift presses nothing. */
    fun up(pointer: Long, at: Key?, cancelled: Boolean): Key? =
        pointers.remove(pointer)?.takeIf { it == at && !cancelled }
}

// ---- The grid (research 01 §4.2) --------------------------------------------------------------

private const val ROUND_ROW_Y = 62.7f
private const val ROUND_DIAMETER = 6.6f

private val FN_COLUMNS = floatArrayOf(11.4f, 22.3f, 33.0f, 43.9f, 54.7f, 65.6f)
private val FN_ROWS = floatArrayOf(75.7f, 85.1f, 94.3f, 103.7f)
private const val FN_W = 9.4f
private const val FN_H = 5.8f

private val PAD_COLUMNS = floatArrayOf(12.2f, 25.5f, 38.6f, 51.6f, 64.7f)
private val PAD_ROWS = floatArrayOf(115.6f, 126.9f, 138.2f, 149.7f)
private const val PAD_W = 11.1f
private const val PAD_H = 8.2f

private fun round(key: Key, cx: Float, description: String, name: Legend, shift: Legend? = null) = KeySpec(
    key, KeyKind.ROUND, cx, ROUND_ROW_Y, ROUND_DIAMETER, ROUND_DIAMETER, emptyList(), description, shift,
    name = name,
)

private fun cursor(key: Key, cx: Float, cy: Float, width: Float, height: Float, description: String) =
    KeySpec(key, KeyKind.CURSOR, cx, cy, width, height, emptyList(), description)

private fun fn(
    key: Key,
    column: Int,
    row: Int,
    cap: List<Glyph>,
    description: String,
    shift: Legend? = null,
    alpha: Legend? = null,
    corner: BigCorner? = null,
) = KeySpec(
    key, KeyKind.FUNCTION, FN_COLUMNS[column], FN_ROWS[row], FN_W, FN_H, cap, description, shift, alpha,
    corner = corner,
)

/** A number-pad key whose cap is one run of text, described by that text unless told otherwise. */
private fun pad(
    key: Key,
    column: Int,
    row: Int,
    cap: String,
    shift: Legend? = null,
    alpha: Legend? = null,
    corner: BigCorner? = null,
    kind: KeyKind = KeyKind.NUMBER,
    description: String = cap,
) = KeySpec(
    key, kind, PAD_COLUMNS[column], PAD_ROWS[row], PAD_W, PAD_H, listOf(t(cap)), description, shift, alpha,
    corner = corner,
)

/** A magenta hex digit in cyan brackets: typed directly in BASE-N, ALPHA elsewhere (research 01 §5.1). */
private fun hex(letter: String) = magenta(t(letter), bracket = LegendInk.BASE)
