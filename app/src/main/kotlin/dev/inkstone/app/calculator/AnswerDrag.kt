package dev.inkstone.app.calculator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * ⭐ Where a dragged answer can land — the note editor's page (docs/CALCULATOR.md §6). The library
 * has no page, so it passes none and the LCD is not a drag source there: absent, not greyed
 * (research 08 §5.1).
 *
 * @property pxPerPt how many screen pixels a page point is at the current zoom (`Camera.scale`), so
 *   the ghost under the finger is the size the answer will land at.
 * @property drop the answer was let go at [Offset], in the calculator layer's own pixels — which are
 *   the ink surface's view pixels, because the layer fills the editor's root `Box` exactly as the
 *   surface does. [AnswerText] is the ghost's text, measured in page points.
 */
internal class AnswerTarget(val pxPerPt: () -> Float, val drop: (answer: String, at: Offset, text: AnswerText) -> Unit)

/** The ghost's text as the page will hold it, in page points: its extent, and its first baseline from its top. */
internal data class AnswerText(val widthPt: Float, val heightPt: Float, val baselinePt: Float)

/** An answer in flight from the LCD, and where the finger is, in window pixels. */
internal data class HeldAnswer(val answer: String, val window: Offset)

/**
 * ⭐ A drag of the answer off the LCD: the `ElementPanelLayer` / `WriteDrop` shape, for their reason.
 *
 * Positions are carried in **window** pixels while the finger moves, because the source (the LCD,
 * inside a window that may float anywhere) and the destination (the page, under the calculator
 * layer) have different local spaces, and the window is the one both can convert from. The layer
 * converts once, at the drop.
 *
 * Letting go over the calculator window itself is *"never mind"*, not *"put it under the
 * calculator"* — the same rule as the Element and Write panels, and the ruler lesson in CLAUDE.md:
 * a tool's chrome must not cover the thing the tool produces.
 */
@Stable
internal class AnswerDrag {
    /** The calculator layer — the page's view space. */
    var layer by mutableStateOf<LayoutCoordinates?>(null)

    /**
     * The LCD the drag starts from. Held here rather than in a local of [answerDragSource]: that is
     * called on every recomposition while its `pointerInput` is kept, so a local would leave the
     * gesture reading the first composition's copy.
     */
    var source: LayoutCoordinates? = null

    /** The calculator window, in window pixels: a drop inside it is cancelled. */
    var windowBounds by mutableStateOf<Rect?>(null)

    var held by mutableStateOf<HeldAnswer?>(null)
        private set

    /** What a drop does with the answer and where it landed, in [layer]'s pixels. Set by the layer. */
    var onDrop: (answer: String, at: Offset) -> Unit = { _, _ -> }

    fun start(answer: String, window: Offset) {
        held = HeldAnswer(answer, window)
    }

    fun move(window: Offset) {
        held = held?.copy(window = window)
    }

    fun cancel() {
        held = null
    }

    /** The finger lifted: hand the answer to [onDrop], unless it was let go over the calculator. */
    fun release() {
        val dropped = held ?: return
        held = null
        if (windowBounds?.contains(dropped.window) == true) return
        val local = layer?.windowToLocal(dropped.window) ?: return
        onDrop(dropped.answer, local)
    }
}

/**
 * Makes the LCD a drag source for [answer] — when there is one. With no answer on the glass the
 * gesture is left alone, so the face's own handler sees exactly what it always did.
 *
 * ⚠️ **A tap is not a drag.** The press becomes a drag only past the touch slop, so a pen resting on
 * the glass does nothing; and once it is a drag every change is consumed, which the face's keypad
 * handler (the parent) reads as a cancelled gesture and so presses nothing. Keyed on [drag] alone and
 * reading [answer] through a `State` at the down — CLAUDE.md's *"never key a `pointerInput` on the
 * value the gesture is editing"*.
 */
internal fun Modifier.answerDragSource(drag: AnswerDrag, answer: State<String?>): Modifier {
    return onGloballyPositioned { drag.source = it }.pointerInput(drag) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val text = answer.value ?: return@awaitEachGesture
            var moved = Offset.Zero
            var dragging = false
            try {
                var change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                while (change != null && change.pressed) {
                    moved += change.positionChange()
                    val window = drag.source?.localToWindow(change.position)
                    if (window != null && !dragging && moved.getDistance() > viewConfiguration.touchSlop) {
                        dragging = true
                        drag.start(text, window)
                    }
                    if (window != null && dragging) {
                        change.consume()
                        drag.move(window)
                    }
                    change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                }
                // Lifted, as a drag: the drop. A pointer that vanished without lifting is the finally's cancel.
                if (dragging && change != null) {
                    change.consume()
                    dragging = false
                    drag.release()
                }
            } finally {
                // Cancelled — a second finger, or the window going away under the drag: nothing lands.
                if (dragging) drag.cancel()
            }
        }
    }
}

/**
 * What the finger carries: the answer as it will land — the text box's default face and size at the
 * page's zoom — centred on the finger, on a paper-coloured card in a faint frame. Drawn without
 * input, so it never takes the touch it is following.
 */
@Composable
internal fun AnswerGhost(held: HeldAnswer, layer: LayoutCoordinates?, pxPerPt: Float) {
    val at = layer?.windowToLocal(held.window) ?: return
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layout = measurer.measure(held.answer, answerStyle(density, pxPerPt, GhostInk))
    Canvas(Modifier.fillMaxSize()) {
        val topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f)
        val pad = GHOST_PAD_PT * pxPerPt
        // A paper-coloured card under the text: the finger crosses the dark margin and the calculator
        // itself on the way to the page, and dark ink on either is invisible (found on the emulator).
        drawRoundRect(
            color = GhostPaper,
            topLeft = topLeft - Offset(pad, pad),
            size = Size(layout.size.width + 2 * pad, layout.size.height + 2 * pad),
            cornerRadius = CornerRadius(pad, pad),
        )
        drawRoundRect(
            color = GhostFrame,
            topLeft = topLeft - Offset(pad, pad),
            size = Size(layout.size.width + 2 * pad, layout.size.height + 2 * pad),
            cornerRadius = CornerRadius(pad, pad),
            style = Stroke(width = GHOST_OUTLINE_PX),
        )
        drawText(layout, topLeft = topLeft)
    }
}

/** The text in page points, measured the way [AnswerGhost] draws it. */
internal fun TextLayoutResult.inPagePoints(pxPerPt: Float): AnswerText =
    AnswerText(size.width / pxPerPt, size.height / pxPerPt, firstBaseline / pxPerPt)

/**
 * The text box's own face and size (`ObjectFormat.DEFAULT_SIZE_PT`, sans) at [pxPerPt], as a Compose
 * style. ⚠️ Divided by the font scale: a page point is a page point whatever the system font size
 * is, and the box the answer lands in is not scaled by it either.
 */
internal fun answerStyle(density: Density, pxPerPt: Float, color: Color): TextStyle =
    TextStyle(color = color, fontSize = pxToSp(density, dev.inkstone.core.model.ObjectFormat.DEFAULT_SIZE_PT * pxPerPt))

private fun pxToSp(density: Density, px: Float): TextUnit = (px / (density.density * density.fontScale)).sp

private val GhostInk = Color(0xCC1A1A1A)
private val GhostFrame = Color(0xCC0EA5E9)
private val GhostPaper = Color(0xE6FFFFFF)
private const val GHOST_PAD_PT = 3f
private const val GHOST_OUTLINE_PX = 2f
