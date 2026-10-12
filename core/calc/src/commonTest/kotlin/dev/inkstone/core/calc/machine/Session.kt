package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.Calculator
import dev.inkstone.core.calc.Key
import dev.inkstone.core.calc.lcd.Indicator
import dev.inkstone.core.calc.lcd.LcdFrame
import dev.inkstone.core.calc.mode.StandardModes
import kotlin.random.Random

/**
 * A calculator on the test fakes, driven by key words the way research 04/05 print sequences:
 * `press("3 + 5 STO (−)")`. A word is
 * - a run of digits, typed one key each (`14` is `1 4`);
 * - a key's printed name or its [Key] name (`sin`, `x²`, `◀`, `AC`, `SHIFT`, `RIGHT`);
 * - a shifted or alpha legend, which presses SHIFT or ALPHA first (`STO`, `M−`, `:`, `A`, `π`).
 *
 * Everything goes through [Calculator.press], the public entry point. The modes are the
 * production set ([StandardModes]) unless a test registers its own.
 */
class Session(
    val evaluator: FakeEvaluator = FakeEvaluator(),
    val formatter: FakeFormatter = FakeFormatter(),
    modes: ModeRegistry = StandardModes.registry(),
) {
    val calculator = Calculator(evaluator, formatter, Random(SEED), modes)

    /** Set by a test to make the next presses report an `AC` waiting behind them. */
    var interrupted = false

    /** The machine behind [calculator], for reading memory and SETUP. */
    val machine: Machine get() = calculator.machine

    val frame: LcdFrame get() = calculator.frame
    val text: String get() = frame.text
    val icons: Set<Indicator> get() = frame.indicators

    /** The lines of [text]: input first, then the result. */
    val lines: List<String> get() = text.split('\n')

    fun press(words: String): Session {
        keys(words).forEach { calculator.press(it) { interrupted } }
        return this
    }

    companion object {
        private const val SEED = 7

        private val PLAIN: Map<String, Key> = mapOf(
            "+" to Key.PLUS, "−" to Key.MINUS, "-" to Key.MINUS, "×" to Key.MULTIPLY, "÷" to Key.DIVIDE,
            "=" to Key.EQUALS, "," to Key.DECIMAL, "(" to Key.OPEN_PAREN, ")" to Key.CLOSE_PAREN,
            "◀" to Key.LEFT, "▶" to Key.RIGHT, "▲" to Key.UP, "▼" to Key.DOWN, "(−)" to Key.NEGATIVE,
            "x²" to Key.SQUARE, "x^" to Key.POWER, "x⁻¹" to Key.RECIPROCAL, "FRAC" to Key.FRACTION,
            "√" to Key.SQRT, "°'\"" to Key.DMS, "S⇔D" to Key.S_TO_D, "M+" to Key.M_PLUS, "Ans" to Key.ANS,
            "×10" to Key.EXP, "hyp" to Key.HYP, "sin" to Key.SIN, "cos" to Key.COS, "tan" to Key.TAN,
            "log" to Key.LOG, "ln" to Key.LN, "log□" to Key.LOG_BASE, "∫" to Key.INTEGRAL,
        )

        private val SHIFTED: Map<String, Key> = mapOf(
            "STO" to Key.RCL, "M−" to Key.M_PLUS, "SETUP" to Key.MODE, "CLR" to Key.D9, "OFF" to Key.AC,
            "CONST" to Key.D7, "CONV" to Key.D8, "INS" to Key.DEL, "SOLVE" to Key.CALC, "FACT" to Key.DMS,
            "DRG" to Key.ANS, ";" to Key.CLOSE_PAREN, "π" to Key.EXP, "SHIFT=" to Key.EQUALS, "x³" to Key.SQUARE,
            "←ENG" to Key.ENG, "ab/c" to Key.S_TO_D, "Pol" to Key.PLUS, "∠" to Key.NEGATIVE, "Abs" to Key.HYP,
            "MIXED" to Key.FRACTION, "%" to Key.OPEN_PAREN, "x!" to Key.RECIPROCAL, "sin⁻¹" to Key.SIN,
        )

        private val ALPHAS: Map<String, Key> = mapOf(
            ":" to Key.INTEGRAL, "EQ" to Key.CALC, "÷R" to Key.FRACTION, "PreAns" to Key.ANS,
            "A" to Key.NEGATIVE, "B" to Key.DMS, "C" to Key.HYP, "D" to Key.SIN, "E" to Key.COS,
            "F" to Key.TAN, "X" to Key.CLOSE_PAREN, "Y" to Key.S_TO_D, "M" to Key.M_PLUS, "e" to Key.EXP,
        )

        fun keys(words: String): List<Key> = words.split(' ').filter { it.isNotEmpty() }.flatMap(::word)

        private fun word(word: String): List<Key> {
            if (word.all { it.isDigit() }) return word.map(::digit)
            PLAIN[word]?.let { return listOf(it) }
            SHIFTED[word]?.let { return listOf(Key.SHIFT, it) }
            ALPHAS[word]?.let { return listOf(Key.ALPHA, it) }
            return listOf(Key.entries.firstOrNull { it.name == word } ?: error("unknown key word '$word'"))
        }

        private fun digit(c: Char): Key = Key.entries.first { it.digit == c - '0' }
    }
}
