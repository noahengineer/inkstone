package dev.inkstone.core.calc.lcd

import dev.inkstone.core.calc.Setup

/**
 * The 18 segment icons above the dot matrix, left to right (research 01 §3.2). They are fixed
 * shapes, not dots; the UI draws them. [inverted] icons are light letters in a dark box.
 */
enum class Indicator(val label: String, val inverted: Boolean = false) {
    SHIFT("S", inverted = true),
    ALPHA("A", inverted = true),
    MEMORY("M"),
    STO("STO"),
    RCL("RCL"),
    STAT("STAT"),
    CMPLX("CMPLX"),
    MAT("MAT"),
    VCT("VCT"),
    DEG("D", inverted = true),
    RAD("R", inverted = true),
    GRA("G", inverted = true),
    FIX("FIX"),
    SCI("SCI"),
    MATH("Math"),
    DOWN("▼"),
    UP("▲"),
    DISP("Disp"),
}

/**
 * Where the blinking cursor is, in matrix dots. The engine reports it separately from [LcdFrame.pixels]
 * so the UI can blink it in the draw phase without asking the engine for a new frame.
 * [block] is the filled cursor shown when ten bytes or fewer of input remain (research 02 §7.2).
 */
data class LcdCursor(val x: Int, val y: Int, val width: Int, val height: Int, val block: Boolean = false)

/**
 * One complete picture of the display: the 96 × 31 dot matrix, the status icons, and the cursor.
 *
 * [pixels] is row-major, `pixels[y * WIDTH + x]`, `true` = a dark dot. Never mutated after
 * construction — the engine builds a new frame per key.
 */
class LcdFrame(
    val pixels: BooleanArray,
    val indicators: Set<Indicator>,
    val cursor: LcdCursor? = null,
    /** The whole glass is blank, as after `SHIFT AC` (OFF). */
    val off: Boolean = false,
    /**
     * Plain text of what the matrix shows, line by line, for accessibility and for tests: the
     * linear form of the input line and the result as the display shows them (`1/200` for a
     * stacked fraction, `1,25×10^-3` for exponential form).
     */
    val text: String = "",
    /** SETUP ◀CONT▶, `Setup.MIN_CONTRAST..MAX_CONTRAST`; the UI maps it to ink density. */
    val contrast: Int = Setup.DEFAULT_CONTRAST,
) {
    init {
        require(pixels.size == WIDTH * HEIGHT) { "an LCD frame is $WIDTH×$HEIGHT dots" }
    }

    operator fun get(x: Int, y: Int): Boolean = pixels[y * WIDTH + x]

    companion object {
        const val WIDTH = 96
        const val HEIGHT = 31

        val BLANK = LcdFrame(BooleanArray(WIDTH * HEIGHT), emptySet())
        val OFF = LcdFrame(BooleanArray(WIDTH * HEIGHT), emptySet(), off = true)
    }
}
