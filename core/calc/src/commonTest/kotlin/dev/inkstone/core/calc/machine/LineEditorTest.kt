package dev.inkstone.core.calc.machine

import dev.inkstone.core.calc.input.InputState
import dev.inkstone.core.calc.input.Keys
import dev.inkstone.core.calc.input.TemplateKind
import dev.inkstone.core.calc.input.Tok
import dev.inkstone.core.calc.lcd.CursorShape
import dev.inkstone.core.calc.lcd.MathLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The shared typing rules: the two-screen height limit (research 02 §7.2) and the cursor's shape. */
class LineEditorTest {
    private val fraction = Action.Open(TemplateKind.FRACTION)

    @Test
    fun naturalInputTallerThanTwoScreensIsRefused() {
        var state = InputState.empty(natural = true)
        var refused = false
        repeat(LIMIT) {
            val next = LineEditor.apply(state, fraction)
            if (next === state) refused = true else state = next
        }
        assertTrue(refused, "nested fractions never reached the height limit")
        assertTrue(MathLayout.measure(state.expr, natural = true).height <= LineEditor.MAX_NATURAL_HEIGHT)
        val deeper = state.copy(cursor = state.cursor)
        assertSame(deeper, LineEditor.apply(deeper, fraction))
    }

    @Test
    fun aLinearLineHasNoHeightLimit() {
        var state = InputState.empty(natural = false)
        repeat(LIMIT) { state = LineEditor.apply(state, fraction) }
        assertEquals(LIMIT, state.expr.items.size)
    }

    @Test
    fun cursorKeysAndDelAreEditsButOtherCommandsAreNot() {
        val edits = listOf(Command.LEFT, Command.RIGHT, Command.UP, Command.DOWN, Command.DEL, Command.INS)
        for (command in Command.entries) {
            assertEquals(command in edits, LineEditor.edits(Action.Do(command)), "$command")
        }
        assertTrue(LineEditor.edits(Action.Type(Tok.D1)))
        assertEquals(false, LineEditor.edits(Action.None))
    }

    @Test
    fun theCursorShapeFollowsTheBufferAndTheMode() {
        assertEquals(CursorShape.INSERT, LineEditor.cursorShape(InputState.empty()))
        val overwriting = Keys.type("1 2", natural = false).toggleOverwrite()
        assertEquals(CursorShape.OVERWRITE, LineEditor.cursorShape(overwriting))
        val nearlyFull = Keys.type(List(90) { "1" }.joinToString(" "), natural = false).toggleOverwrite()
        assertEquals(CursorShape.BLOCK, LineEditor.cursorShape(nearlyFull))
    }

    private companion object {
        const val LIMIT = 10
    }
}
