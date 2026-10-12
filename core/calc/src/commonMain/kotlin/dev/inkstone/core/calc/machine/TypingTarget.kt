package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.CalcError
import dev.inkstone.core.calc.input.Cursor
import dev.inkstone.core.calc.input.InputState
import dev.inkstone.core.calc.input.Item

/**
 * Anything a typing menu can type into: the calculation line ([Calculation]), a CALC or SOLVE
 * prompt, an editor cell ([CellEditor]). The hyp, DRG▶, CONST and CONV keys mean the same thing
 * wherever the cursor is — they put an item at it (research 02 §10.4–10.5, §10.16) — so their
 * screens are written once, against this, by [LineMenus].
 */
interface TypingTarget {
    /** What is being typed, for the place an error's Goto returns to. */
    val input: InputState

    /** Types [item] at the cursor, as its key would. */
    fun insert(item: Item)

    /** Shows [error] for what is being typed; `◀`/`▶` put the cursor at [at] (research 02 §11). */
    fun fail(error: CalcError, at: Cursor?)
}
