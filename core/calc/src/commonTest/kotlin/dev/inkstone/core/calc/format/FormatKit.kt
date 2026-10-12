package dev.inkstone.core.calc.format

import dev.inkstone.core.calc.ComplexFormat
import dev.inkstone.core.calc.FractionFormat
import dev.inkstone.core.calc.InputOutput
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.NumberFormat
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.eval.Answer
import dev.inkstone.core.calc.lcd.MathLayout
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.parse.Radix
import dev.inkstone.core.calc.value.ComplexValue
import dev.inkstone.core.calc.value.DecimalReal
import dev.inkstone.core.calc.value.Real
import dev.inkstone.core.calc.value.RealArith
import dev.inkstone.core.calc.value.Reals
import dev.inkstone.core.calc.value.Res
import dev.inkstone.core.calc.value.Roots
import dev.inkstone.core.calc.value.Value
import kotlin.test.fail

/*
 * Building results and reading back what the formatter draws, for the format tests.
 *
 * What is compared is MathLayout.plainText of the result — the LcdFrame.text spelling: `1/200` for
 * a stacked fraction, `√(2)` for a root, `×10^-3` for exponential form, `⌟` for a LineIO fraction —
 * or, for a stacked two-part result, each line as label + value joined by " / ". Every minus in a
 * result is the short (−) sign, whose text is "-".
 */

/** SETUP defaults (MthIO-MathO, Deg, Norm 2, d/c) — research 04's DEFAULT. */
internal val MATH = Setup()

/** LineIO: linear input and output. */
internal val LINE = Setup(io = InputOutput.LINE_LINE)

/** MthIO-LineO: natural input, linear results. */
internal val MATH_LINE = Setup(io = InputOutput.MATH_LINE)

internal fun Setup.norm(range: Int): Setup = copy(format = NumberFormat.Norm(range))

internal fun Setup.fix(places: Int): Setup = copy(format = NumberFormat.Fix(places))

internal fun Setup.sci(digits: Int): Setup = copy(format = NumberFormat.Sci(digits))

internal fun Setup.mixed(): Setup = copy(fraction = FractionFormat.MIXED)

internal fun Setup.polar(): Setup = copy(complex = ComplexFormat.POLAR)

internal fun q(num: Long, den: Long = 1L): Real = ok(Reals.fraction(num, den))

/** A decimal from canonical text with a `.` point (`Dec.parse`), as the evaluator would hold it. */
internal fun d(text: String): Real = DecimalReal(Dec.parse(text) ?: fail("bad literal $text"))

/** A DMS-flagged decimal — what `°'"` input and DMS arithmetic produce (research 02 §8.9). */
internal fun dmsValue(text: String): Real = DecimalReal(Dec.parse(text) ?: fail("bad literal $text"), dms = true)

internal fun sqrt(n: Long): Real = ok(Roots.sqrt(Reals.of(n)))

internal fun add(a: Real, b: Real): Real = ok(RealArith.add(a, b))

internal fun sub(a: Real, b: Real): Real = ok(RealArith.subtract(a, b))

internal fun mul(a: Real, b: Real): Real = ok(RealArith.multiply(a, b))

internal fun div(a: Real, b: Real): Real = ok(RealArith.divide(a, b))

internal fun z(re: Real, im: Real): ComplexValue = ComplexValue(re, im)

internal fun <T> ok(r: Res<T>): T = when (r) {
    is Res.Ok -> r.value
    is Res.Err -> fail("expected a value, got ${r.error}")
}

/** What `=` (or, with [decimal], `SHIFT =`) shows for [value]. */
internal fun shown(
    value: Value,
    setup: Setup = MATH,
    piEligible: Boolean = false,
    decimal: Boolean = false,
    mode: Mode = Mode.COMP,
): String = text(Formatting.present(Answer.Plain(value, piEligible), setup, mode, decimal), setup, mode)

/** What [answer] shows after `=`. */
internal fun shownAnswer(
    answer: Answer,
    setup: Setup = MATH,
    decimal: Boolean = false,
    mode: Mode = Mode.COMP,
): String = text(Formatting.present(answer, setup, mode, decimal), setup, mode)

internal fun text(p: Presentation, setup: Setup = MATH, mode: Mode = Mode.COMP, radix: Radix = Radix.DEC): String =
    textOf(Formatting.show(p, setup, mode, radix))

internal fun textOf(s: Shown): String = s.result?.let { MathLayout.plainText(it) }
    ?: s.parts.joinToString(" / ") { it.label + MathLayout.plainText(it.value) }

/** The display keys that restyle a result. */
internal enum class Press { SD, MIXED, ENG, ENG_LEFT, DMS, FACT }

/**
 * Presses [keys] on [start] in turn and spells what is on screen after each, `" > "` between: a key
 * that does nothing (the formatter answers null) adds `(none)` and leaves the screen as it was.
 */
internal fun walk(start: Presentation, setup: Setup, keys: List<Press>, mode: Mode = Mode.COMP): String {
    var p = start
    val out = mutableListOf(text(p, setup, mode))
    for (key in keys) {
        val next = when (key) {
            Press.SD -> Formatting.toggleExact(p, setup, mode)
            Press.MIXED -> Formatting.toggleMixed(p, setup, mode)
            Press.ENG -> Formatting.engineering(p, setup, mode, left = false)
            Press.ENG_LEFT -> Formatting.engineering(p, setup, mode, left = true)
            Press.DMS -> Formatting.toggleDms(p, setup, mode)
            Press.FACT -> Formatting.factorize(p, setup, mode)
        }
        if (next == null) out += "(none)" else p = next.also { out += text(it, setup, mode) }
    }
    return out.joinToString(" > ")
}

/** [walk] from what `=` (or `SHIFT =`) shows for [value]. */
internal fun walkFrom(
    value: Value,
    setup: Setup,
    keys: List<Press>,
    piEligible: Boolean = false,
    decimal: Boolean = false,
): String = walk(Formatting.present(Answer.Plain(value, piEligible), setup, Mode.COMP, decimal), setup, keys)

/** One table row: an id for the failure message, what was computed, what the guide prints. */
internal class Case(val id: String, val actual: () -> String, val expected: String)

internal fun case(id: String, expected: String, actual: () -> String): Case = Case(id, actual, expected)

/** Runs every row and reports every mismatch at once, not just the first. */
internal fun checkAll(cases: List<Case>) {
    val failures = cases.mapNotNull { row ->
        val got = row.actual()
        if (got == row.expected) null else "${row.id}: expected <${row.expected}>, got <$got>"
    }
    if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
}
