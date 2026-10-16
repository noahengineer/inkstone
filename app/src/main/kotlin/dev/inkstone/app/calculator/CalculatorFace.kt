package dev.inkstone.app.calculator

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import dev.inkstone.core.calc.Key
import dev.inkstone.core.calc.lcd.LcdFrame

/**
 * ⭐ The fx-991ZA PLUS II's face: body, solar cell, LCD, honeycomb, fifty keys and every legend,
 * drawn at the real proportions — one millimetre of the body is `width / 77` of this composable.
 *
 * Lay it out at exactly `width × width · CalculatorPlacement.ASPECT`; the window does.
 *
 * ## One picture, one input handler
 *
 * Everything static is one `drawWithCache` layer, rebuilt only when the size changes. Presses are
 * one `pointerInput` over the whole face that hit-tests [CalculatorKeypad] — the same table the
 * painter draws from, so a key cannot be painted in one place and pressed in another.
 *
 * ### ⚠️ Why not a `clickable` per key
 *
 * The face must consume **every** pointer inside it: a stylus landing between two keys would
 * otherwise fall through to the ink surface underneath and draw a stroke the user cannot see, which
 * is journaled and synced (research 08 §4.4). But a parent that consumes a child `clickable`'s moves
 * cancels the click — the tap detector checks consumption on its final pass, and a pen tip always
 * moves a little — so "keys as clickables plus a consuming body" presses nothing reliably. One
 * handler that owns every pointer and decides the key itself has neither problem.
 *
 * A press registers on **release**, on the key it went down on. That lets a finger slide off to
 * withdraw a press, and lets Android's palm rejection withdraw one: a flagged palm arrives as a
 * cancel, whose changes are already consumed, and a consumed release presses nothing
 * (docs/CALCULATOR.md D5). The real keyboard fires on make; a touch panel is not a key switch.
 *
 * ### Accessibility is separate from input
 *
 * Each key also gets an empty node with its stable [KeySpec.description] and a click action, so
 * TalkBack can press it and `uiautomator dump` can find `7` by name (CLAUDE.md DoD #6). Those nodes
 * take no pointer input; a real tap still lands on the handler above.
 */
@Composable
internal fun CalculatorFace(
    frame: LcdFrame,
    onKey: (Key) -> Unit,
    modifier: Modifier = Modifier,
    answers: AnswerDrag? = null,
) {
    val pressed = remember { mutableStateOf(emptySet<Key>()) }
    val press = rememberUpdatedState(onKey)
    val answer = rememberUpdatedState(frame.answer)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.keypadInput(pressed, press)) {
        val mm = maxWidth / FaceMm.WIDTH
        Spacer(
            Modifier
                .size(maxWidth, maxHeight)
                .drawWithCache {
                    val art = buildFaceArt(size.width, CapSetter(measurer, this))
                    // `pressed` is read here and nowhere in composition: a press redraws this layer
                    // and recomposes nothing.
                    onDrawBehind { drawFace(art, pressed.value) }
                },
        )
        val glass = FaceMm.GLASS
        // ⭐ The glass is where an answer is dragged from, onto the page (docs/CALCULATOR.md §6).
        val lcd = Modifier.offset(mm * glass.left, mm * glass.top).size(mm * glass.width, mm * glass.height)
        CalculatorLcd(frame, answers?.let { lcd.answerDragSource(it, answer) } ?: lcd)
        KeyTargets(mm, press)
    }
}

/** One empty, pointer-transparent node per key, for TalkBack and `uiautomator`. */
@Composable
private fun KeyTargets(mm: Dp, press: State<(Key) -> Unit>) {
    CalculatorKeypad.KEYS.forEach { spec ->
        val target = spec.hitRect
        Box(
            Modifier
                .offset(mm * target.left, mm * target.top)
                .size(mm * target.width, mm * target.height)
                .semantics {
                    contentDescription = spec.description
                    role = Role.Button
                    onClick {
                        press.value(spec.key)
                        true
                    }
                },
        )
    }
}

/**
 * The keypad's one pointer handler. Keyed on `Unit` and reading [press] through a `State`, never
 * keyed on anything it changes — CLAUDE.md's *"never key a `pointerInput` on the value the gesture
 * is editing"*. [pressed] is the set of keys under a pointer right now, for the painter to darken.
 */
private fun Modifier.keypadInput(pressed: MutableState<Set<Key>>, press: State<(Key) -> Unit>): Modifier =
    pointerInput(Unit) {
        val presses = KeyPresses()
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent().changes.forEach { change -> track(change, size, presses, press.value) }
                val now = presses.held
                if (now != pressed.value) pressed.value = now
            }
        }
    }

/**
 * One pointer's step, handed to [KeyPresses], which owns the rule. ⚠️ Consumes the change
 * unconditionally — the face owns every pointer inside it — but reads whether something had
 * **already** consumed it first, because that is how a cancelled gesture (a rejected palm) says
 * "this release is not a press": Compose delivers a cancel as an up whose change is consumed.
 */
private fun track(change: PointerInputChange, size: IntSize, presses: KeyPresses, press: (Key) -> Unit) {
    val cancelled = change.isConsumed
    change.consume()
    val mmPerPx = FaceMm.WIDTH / size.width
    val here = CalculatorKeypad.keyAt(change.position.x * mmPerPx, change.position.y * mmPerPx)
    val pointer = change.id.value
    when {
        change.changedToDownIgnoreConsumed() -> presses.down(pointer, here)
        change.changedToUpIgnoreConsumed() -> presses.up(pointer, here, cancelled)?.let(press)
        change.pressed -> presses.move(pointer, here)
    }
}
