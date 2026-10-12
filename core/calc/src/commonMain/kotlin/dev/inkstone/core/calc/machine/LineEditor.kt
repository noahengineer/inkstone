package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.input.InputState
import dev.inkstone.core.calc.input.Item
import dev.inkstone.core.calc.lcd.CursorShape
import dev.inkstone.core.calc.lcd.LcdFrame
import dev.inkstone.core.calc.lcd.MathLayout

/**
 * Typing keys applied to an input line — the one place that turns an [Action] into an
 * [InputState] edit, shared by the calculation line, the CALC/SOLVE prompts and every mode's
 * editor cells, so a key types the same thing wherever the cursor is.
 *
 * Every function returns **the same instance** when the key is refused, exactly as [InputState]
 * does, so `===` still tells a caller that nothing happened.
 */
object LineEditor {
    /**
     * "An expression may be up to two screens tall (31 dots × 2). Beyond that, input is refused"
     * (research 02 §7.2, VERIFIED). Natural display only; a Linear line is one row.
     */
    const val MAX_NATURAL_HEIGHT = LcdFrame.HEIGHT * 2

    /** The INS heavy bar's width in dots (⚠️ GUESS, see [showWrap]); the insert bar is one. */
    const val WRAP_BAR = 2

    /** Applies a typing or cursor key; any other action leaves [state] as it is. */
    fun apply(state: InputState, action: Action): InputState {
        val next = when (action) {
            is Action.Type -> state.insert(action.tok)
            is Action.Open -> state.insertTemplate(action.kind)
            is Action.Do -> EDITS[action.command]?.invoke(state) ?: state
            Action.None -> state
        }
        return checked(state, next)
    }

    /** A constant, a conversion, or a prebuilt item at the cursor (the CONST/CONV prompts, menus). */
    fun insert(state: InputState, item: Item): InputState = checked(state, state.insert(item))

    /** True for the keys [apply] acts on: typing, templates, the cursor keys, DEL and INS. */
    fun edits(action: Action): Boolean = when (action) {
        is Action.Type, is Action.Open -> true
        is Action.Do -> action.command in EDITS
        Action.None -> false
    }

    /**
     * The cursor's look (research 02 §7.2, VERIFIED): the filled block once ten bytes or fewer are
     * left, else LineIO's overwrite underline, else the insert bar. That the block wins over the
     * underline is ⚠️ GUESS — the guide describes each alone.
     */
    fun cursorShape(state: InputState): CursorShape = when {
        state.nearlyFull -> CursorShape.BLOCK
        state.overwrite -> CursorShape.OVERWRITE
        else -> CursorShape.INSERT
    }

    /**
     * Natural display's INS (research 02 §7.5, VERIFIED): after `SHIFT DEL` "the cursor changes
     * shape: [the guide's] capture shows a heavy bar" until the next template key wraps the value
     * right of it. `lcd.CursorShape` has no such shape, so the insert bar the layout placed in
     * [frame] is widened to [WRAP_BAR] dots, onto the edge of the value about to be wrapped —
     * ⚠️ GUESS at the width, which the capture does not measure. The block cursor (ten bytes left)
     * is left as it is.
     */
    fun showWrap(frame: LcdFrame, state: InputState): LcdFrame {
        val cursor = frame.cursor
        if (cursor == null || !state.wrapPending || cursorShape(state) != CursorShape.INSERT) return frame
        val bar = cursor.copy(width = minOf(WRAP_BAR, LcdFrame.WIDTH - cursor.x))
        return LcdFrame(frame.pixels, frame.indicators, bar, frame.off, frame.text, frame.contrast)
    }

    /** [next], unless it is a Natural line taller than [MAX_NATURAL_HEIGHT]; then [state] itself. */
    private fun checked(state: InputState, next: InputState): InputState {
        if (next === state || !next.natural || next.expr == state.expr) return next
        return if (MathLayout.measure(next.expr, natural = true).height > MAX_NATURAL_HEIGHT) state else next
    }

    /** `SHIFT DEL` is INS: wrap in Natural display, overwrite in LineIO (research 02 §7.2, §7.5). */
    private val EDITS: Map<Command, (InputState) -> InputState> = mapOf(
        Command.LEFT to InputState::moveLeft,
        Command.RIGHT to InputState::moveRight,
        Command.UP to InputState::moveUp,
        Command.DOWN to InputState::moveDown,
        Command.DEL to InputState::delete,
        Command.INS to { state -> if (state.natural) state.beginWrap() else state.toggleOverwrite() },
    )
}
