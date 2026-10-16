package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.Mode
import dev.inkstone.core.calc.Setup
import dev.inkstone.core.calc.eval.Answer
import dev.inkstone.core.calc.eval.Memory
import dev.inkstone.core.calc.format.Form
import dev.inkstone.core.calc.format.Presentation
import dev.inkstone.core.calc.input.InputState
import dev.inkstone.core.calc.lcd.Indicator
import dev.inkstone.core.calc.lcd.LcdFont
import dev.inkstone.core.calc.lcd.LcdFrame
import dev.inkstone.core.calc.lcd.MathLayout
import dev.inkstone.core.calc.lcd.Screens
import dev.inkstone.core.calc.num.Dec
import dev.inkstone.core.calc.parse.Radix
import dev.inkstone.core.calc.value.Real

/*
 * The result side of the calculation line: history replay, the keys that restyle a result, and
 * drawing. Extensions rather than members only to keep [Calculation] a readable size.
 */

/**
 * On an **empty** line `◀`/`▶` bring the last calculation back for editing and `▲` shows it — the
 * REPLAY keys of research 02 §7.6 ("press AC, then ◀/▶") and §10.2. That ▲ also works from an
 * empty line, and does nothing when history is empty, is ⚠️ GUESS.
 */
internal fun Calculation.replays(command: Command): Boolean =
    command in REPLAY_KEYS && input.expr.isEmpty && canReplay()

private fun Calculation.canReplay(): Boolean = hooks.keepsHistory && !host.history.isEmpty()

private val REPLAY_KEYS = setOf(Command.LEFT, Command.RIGHT, Command.UP)

internal fun Calculation.replay(command: Command) {
    val index = host.history.lastIndex
    val entry = host.history.getOrNull(index) ?: return
    when (command) {
        Command.LEFT -> reopen(entry.expr, atStart = false)
        Command.RIGHT -> reopen(entry.expr, atStart = true)
        else -> showEntry(entry, index)
    }
}

/**
 * Shows history entry [index] as the result on screen. The line under it becomes the entry's own
 * line, as [Calculation.input] promises: an error raised on this result — FACT's Math ERROR, a
 * CONST number out of range — goes back to *this* calculation (research 02 §11), not to whatever
 * was typed last.
 */
internal fun Calculation.showEntry(entry: HistoryEntry, index: Int) {
    input = InputState.editing(entry.expr, host.naturalInput)
    scroll = 0
    val presentation = present(entry.answer, entry.decimal, entry.complex)
    view = CalcView.Result(entry.expr, entry.answer, presentation, entry.decimal, 0, 1, index, entry.complex)
}

/**
 * `▲`/`▼` on a result step through the history: "after 1+1=, 2+2= and 3+3=, pressing ▲ shows 4
 * and ▲ again shows 2" (research 02 §10.2, VERIFIED). The ends do nothing.
 */
internal fun Calculation.browse(shown: CalcView.Result, step: Int) {
    val index = (shown.historyIndex ?: return) + step
    val entry = host.history.getOrNull(index) ?: return
    showEntry(entry, index)
}

/**
 * `◀`/`▶` on a result. A result too wide for the line scrolls (research 02 §8.1: "a filled ▶ …
 * scroll it with ▶/◀"); otherwise the keys re-open the line for editing (research 02 §10.2). The
 * guide's "if both ▶ and ▷ are shown, press AC first and then scroll" is what makes the two
 * readings fit together.
 */
internal fun Calculation.arrow(shown: CalcView.Result, right: Boolean) {
    val limit = scrollLimit(shown)
    if (limit == 0) {
        reopen(shown.expr, atStart = right)
        return
    }
    val step = if (right) LcdFont.CELL else -LcdFont.CELL
    shown.scroll = (shown.scroll + step).coerceIn(0, limit)
}

private fun Calculation.scrollLimit(shown: CalcView.Result): Int {
    val laidOut = host.formatter.show(shown.presentation, host.setupFor(shown.complex), host.mode, radix())
    val result = laidOut.result ?: return 0
    return MathLayout.resultScrollLimit(result, laidOut.naturalResult)
}

private fun Calculation.radix(): Radix = hooks.radix ?: Radix.DEC

/**
 * S⇔D, `SHIFT S⇔D`, ENG and `SHIFT ENG` on any shown result — the line's, a CALC result, a mode's
 * solution screen: the formatter's next presentation, or null when the key does nothing to it.
 * "S⇔D and °'" are disabled while a ÷R result is displayed" (research 02 §8.8, VERIFIED) is
 * enforced here as well as left to the formatter. [setup] is SETUP as this result is drawn — its
 * own complex form, if its statement asked for one ([ComplexOverride]).
 */
internal fun Host.restyle(presentation: Presentation, command: Command, setup: Setup = this.setup): Presentation? {
    if (command == Command.S_TO_D && presentation.answer is Answer.Division) return null
    return when (command) {
        Command.S_TO_D -> formatter.toggleExact(presentation, setup, mode)
        Command.MIXED -> formatter.toggleMixed(presentation, setup, mode)
        Command.ENG -> formatter.engineering(presentation, setup, mode, left = false)
        Command.ENG_LEFT -> formatter.engineering(presentation, setup, mode, left = true)
        else -> null
    }
}

/** `°'"` on a shown result toggles decimal ⇔ sexagesimal (research 02 §8.9); not on a ÷R result (§8.8). */
internal fun Host.toggleDms(presentation: Presentation, setup: Setup = this.setup): Presentation? =
    if (presentation.answer is Answer.Division) null else formatter.toggleDms(presentation, setup, mode)

internal fun Calculation.restyle(shown: CalcView.Result, command: Command) {
    host.restyle(shown.presentation, command, host.setupFor(shown.complex))?.let { restyled(shown, it) }
}

internal fun Calculation.toggleDms(shown: CalcView.Result) {
    host.toggleDms(shown.presentation, host.setupFor(shown.complex))?.let { restyled(shown, it) }
}

/**
 * FACT (research 02 §10.13, VERIFIED): COMP only; refused while a Pol, Rec or ÷R result is shown;
 * a result that is not a positive integer of up to 10 digits is a Math ERROR. The formatter's
 * contract can only say "nothing happens" (null), so the error is decided here. Pressed again on
 * the factorisation it is handed straight to the formatter, which owns the way back.
 */
internal fun Calculation.factorize(shown: CalcView.Result) {
    if (host.mode != Mode.COMP || shown.answer !is Answer.Plain) return
    if (shown.presentation.form != Form.FACTORS) {
        val real = shown.answer.value as? Real ?: return
        if (!factorable(real.dec)) {
            fail(CalcError.MATH, null)
            return
        }
    }
    host.formatter.factorize(shown.presentation, host.setupFor(shown.complex), host.mode)?.let { restyled(shown, it) }
}

private val FACT_LIMIT: Dec = Dec.of(10_000_000_000L)

private fun factorable(dec: Dec): Boolean = dec.isInteger && dec.signum > 0 && dec < FACT_LIMIT

private fun restyled(shown: CalcView.Result, next: Presentation) {
    shown.presentation = next
    shown.scroll = 0
}

internal fun Calculation.renderEditing(): LcdFrame {
    val extra = buildSet {
        pendingIcon()?.let(::add)
        if (input.expr.isEmpty && canReplay()) add(Indicator.UP)
    }
    val radix = hooks.radix
    // "MODE 4 … shows Dec at the right with 0 below it" (research 03 §5, VERIFIED on entry); that
    // the 0 stays whenever the BASE-N line is empty is ⚠️ GUESS.
    val resting = if (radix != null && input.expr.isEmpty) {
        host.formatter.valueExpr(Memory.ZERO, host.setup, host.mode, radix)
    } else {
        null
    }
    val frame = Screens.calculation(
        host.indicators(extra),
        input.expr,
        input.cursor,
        input.natural,
        result = resting,
        naturalResult = false,
        inputScroll = scroll,
        cursorShape = LineEditor.cursorShape(input),
        baseLabel = radix?.let { BASE_LABELS.getValue(it) },
    )
    return LineEditor.showWrap(frame, input)
}

/**
 * A result. Two-part results the formatter stacks (LineIO `r=`/`θ=`, ÷R's `R=`, a CMPLX result in
 * Linear display) use the stacked screen; everything else the one-line one, with the input shown
 * from its start (research 02 §7.6: part of a long input "may be cut off").
 */
internal fun Calculation.renderResult(shown: CalcView.Result): LcdFrame {
    val laidOut = host.formatter.show(shown.presentation, host.setupFor(shown.complex), host.mode, radix())
    val extra = buildSet {
        pendingIcon()?.let(::add)
        if (shown.intermediate) add(Indicator.DISP)
        shown.historyIndex?.let { index ->
            if (index > 0) add(Indicator.UP)
            if (index < host.history.lastIndex) add(Indicator.DOWN)
        }
    }
    val icons = host.indicators(extra)
    if (laidOut.parts.isNotEmpty()) {
        return Screens.stacked(icons, shown.expr, laidOut.parts, natural = input.natural, answer = laidOut.parts.size)
    }
    return Screens.calculation(
        icons,
        shown.expr,
        null,
        input.natural,
        result = laidOut.result,
        naturalResult = laidOut.naturalResult,
        resultScroll = shown.scroll,
        baseLabel = laidOut.baseLabel,
    )
}

/** Research 02 §11: the message, `[AC]  :Cancel`, `[◀][▶]:Goto`, with the usual icons lit. */
internal fun Calculation.renderFailure(shown: CalcView.Failure): LcdFrame =
    Screens.error(host.indicators(), shown.error)

private fun Calculation.pendingIcon(): Indicator? = when (pending) {
    Command.STO -> Indicator.STO
    Command.RCL -> Indicator.RCL
    else -> null
}

/** The `Dec`/`Hex`/`Bin`/`Oct` label (research 03 §5.3, SECONDARY strings). */
internal val BASE_LABELS: Map<Radix, String> = mapOf(
    Radix.DEC to "Dec",
    Radix.HEX to "Hex",
    Radix.BIN to "Bin",
    Radix.OCT to "Oct",
)
