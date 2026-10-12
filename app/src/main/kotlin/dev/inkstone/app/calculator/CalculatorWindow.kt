package dev.inkstone.app.calculator

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.inkstone.app.ink.DockRowGap
import dev.inkstone.app.ink.DockTopPadding
import dev.inkstone.app.ui.ChipKind
import dev.inkstone.app.ui.GlassPanel
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.PanelPadding
import dev.inkstone.app.ui.TextChip
import kotlin.math.roundToInt

/**
 * ⭐ The calculator window: a design-system frame with a drag strip and a `✕`, around the face.
 *
 * ## Not a `Popup`, `Dialog` or sheet, and no scrim
 *
 * An in-window layer in the screen's root `Box`, ordered by composition — the `ColourPopover`
 * argument: a popup is a second window, and this screen's wet ink is a `SurfaceView` composited over
 * the app's window, which is exactly what this codebase has been bitten by twice. And no scrim,
 * because a calculator is used *while writing*: read a number off the page, compute, write the answer
 * down. A press outside the window must reach the page (research 08 §4.1–4.2).
 *
 * ## ⚠️ …but every press inside it is the window's
 *
 * Compose hit-tests the topmost node first, and the ink surface below never sees an event a node
 * above it took. The face owns its whole rectangle (`CalculatorFace`), the strip and `✕` own theirs,
 * and a [swallowPointers] layer *underneath* them owns the frame between — so a stylus on the
 * window's padding cannot draw a stroke under the calculator that the user cannot see. It is a
 * sibling below the content rather than a parent around it so that it is never on the same hit path
 * as the `✕` chip: a consuming parent cancels a child's click.
 *
 * ## Placement
 *
 * - Sized by [CalculatorPlacement.faceWidth]: the available height with the body's aspect locked (D7).
 * - Placed by `Modifier.offset { }`, so a drag frame is a placement pass and never a recomposition.
 * - **Clamped at read** ([CalculatorPlacement.clamp]): the stored position is the user's; rotation
 *   cannot ratchet it into a corner.
 * - Until it is first dragged it **hangs from the chip** that opened it (D2), using the chip's
 *   `boundsInRoot` rebased into this layer's own space. ⚠️ The rebase is not optional: the screen's
 *   `Box` is inset by the `Scaffold` (the status bar), and skipping it *"opens one status bar too
 *   low, which reads as a rounding error"* (M10 §7.2).
 * - Invisible until it has been measured, so it never jumps in from the corner on its first frame
 *   (`AnchoredPanel`'s rule).
 */
@Composable
internal fun CalculatorWindow(calc: CalculatorViewModel, modifier: Modifier = Modifier) {
    val frame by calc.frame.collectAsState()
    val stored = calc.position.collectAsState()
    val density = LocalDensity.current.density
    val placement = remember { WindowPlacement() }
    BoxWithConstraints(modifier.onGloballyPositioned { placement.origin = it.positionInRoot() }) {
        val viewport = Viewport(
            width = maxWidth.value,
            height = maxHeight.value,
            safeTop = WindowSafeTop.value,
            bottomReserve = BottomReserve.value,
            edge = InkDims.EdgePadding.value,
        )
        val faceWidth = CalculatorPlacement.faceWidth(viewport, ChromeWidth.value, ChromeHeight.value)
        placement.viewport = viewport
        placement.density = density
        placement.estimate = CalculatorPlacement.windowSize(faceWidth, ChromeWidth.value, ChromeHeight.value)
        val shown = rememberUpdatedState { placement.shown(stored.value, calc.chipBounds) }
        val commit = rememberUpdatedState { at: WindowPosition ->
            calc.moveTo(CalculatorPlacement.clamp(at, placement.size(), placement.viewport))
        }
        Box(
            Modifier
                .offset {
                    val at = shown.value()
                    IntOffset(at.x.dp.roundToPx(), at.y.dp.roundToPx())
                }
                .onSizeChanged { placement.measured = it }
                .graphicsLayer { alpha = if (placement.measured == IntSize.Zero) 0f else 1f },
        ) {
            Spacer(Modifier.matchParentSize().swallowPointers())
            GlassPanel {
                TitleStrip(faceWidth.dp, Modifier.dragToMove(placement, shown, commit), onClose = calc::close)
                val faceHeight = faceWidth * CalculatorPlacement.ASPECT
                CalculatorFace(frame, calc::press, Modifier.size(faceWidth.dp, faceHeight.dp))
            }
        }
    }
}

/** The frame's title bar: a caption to drag by, and the close glyph (the `ElementPanel` pattern). */
@Composable
private fun TitleStrip(width: Dp, drag: Modifier, onClose: () -> Unit) {
    Row(Modifier.width(width).height(StripHeight), verticalAlignment = Alignment.CenterVertically) {
        Box(drag.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            MicroLabel("Calculator")
        }
        TextChip(label = "✕", kind = ChipKind.GLYPH, onClick = onClose, contentDescription = "Close")
    }
}

/**
 * What the window's position is made of, as state read only in the placement and draw phases.
 *
 * ⚠️ Every field here is written from composition or a callback and read **only** inside
 * `Modifier.offset { }` / `graphicsLayer { }` / the drag handler, so a drag or a re-measure moves the
 * window without recomposing it or the screen under it.
 */
@Stable
private class WindowPlacement {
    var viewport by mutableStateOf(Viewport(0f, 0f, 0f, 0f, 0f))
    var estimate by mutableStateOf(WindowSize(0f, 0f))
    var origin by mutableStateOf(Offset.Zero)
    var measured by mutableStateOf(IntSize.Zero)

    /** The live drag, unclamped, in dp; null when nothing is being dragged. */
    var drag by mutableStateOf<WindowPosition?>(null)
    var density = 1f

    /** The window's size in dp: measured once it has been, the computed estimate before that. */
    fun size(): WindowSize =
        if (measured == IntSize.Zero) estimate else WindowSize(measured.width / density, measured.height / density)

    /** Where the window is drawn: the live drag, else the stored place, else hanging from the chip. */
    fun shown(stored: WindowPosition?, chipInRoot: Rect?): WindowPosition {
        val size = size()
        val wanted = drag ?: stored
            ?: CalculatorPlacement.hangFrom(anchorOf(chipInRoot), size, viewport, InkDims.ChipGap.value)
        return CalculatorPlacement.clamp(wanted, size, viewport)
    }

    /** The chip's root-coordinate bounds, rebased into this layer and turned into dp. */
    private fun anchorOf(chipInRoot: Rect?): Anchor? = chipInRoot?.let {
        Anchor(
            left = (it.left - origin.x) / density,
            top = (it.top - origin.y) / density,
            right = (it.right - origin.x) / density,
            bottom = (it.bottom - origin.y) / density,
        )
    }
}

/**
 * Drags the window by the strip. The gesture starts from where the window is **shown** (already
 * clamped), not from the stored value, so a window pushed in from a portrait edge moves the moment
 * the finger does instead of first paying back the overshoot. On release — or cancellation, which is
 * a second pointer or the window losing the gesture, not a change of mind — the clamped end is
 * committed, and only then does the controller hear about it: one write per drag, not per frame.
 */
private fun Modifier.dragToMove(
    placement: WindowPlacement,
    shown: State<() -> WindowPosition>,
    commit: State<(WindowPosition) -> Unit>,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        val from = shown.value()
        var moved = Offset.Zero
        try {
            do {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                if (change != null && change.pressed) {
                    moved += change.positionChange()
                    change.consume()
                    placement.drag = WindowPosition(from.x + moved.x / density, from.y + moved.y / density)
                }
            } while (change?.pressed == true)
        } finally {
            placement.drag?.let { commit.value(it) }
            placement.drag = null
        }
    }
}

/**
 * Takes every gesture that reaches it and does nothing with it. Laid *under* the window's content,
 * it only ever receives presses on the frame between the strip and the face — which would otherwise
 * fall through to the page.
 */
private fun Modifier.swallowPointers(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/** One floating `GlassBar`: a chip plus the bar's padding above and below it. */
private val BarHeight: Dp = InkDims.ChipMinHeight + InkDims.BarPadding * 2

/**
 * The window's top line: below **both** rows of top chrome, never inside the second.
 *
 * ⚠️ Not `DockSafeTop`, which research 08 §4.5 named and this window first clamped against. That
 * line (78 dp) clears the dock's *primary* row only — right for `ContextBars`, because a contextual
 * bar hides row two — but row two is on screen in the editor's **default** state (the pen's
 * brushes, colours and sizes: 66–112 dp, 915 dp wide), and the window is composed under the chrome,
 * so the pen row covered the window's drag strip and its `✕` and took their presses. The library's
 * chrome is the same two rows on the same tokens (toolbar, then breadcrumb), and there the window is
 * the top layer, so it covered `LIBRARY` instead. Built from the dock's own tokens so it moves with
 * them; `CalculatorPlacementTest` asserts it against row two rather than against a copied number.
 */
internal val WindowSafeTop: Dp = DockTopPadding + BarHeight + DockRowGap + BarHeight + InkDims.GroupGap

/** The title strip: one chip tall. */
private val StripHeight: Dp = InkDims.ChipMinHeight

/** `GlassPanel`'s padding either side of the face. */
private val ChromeWidth: Dp = PanelPadding * 2

/** `GlassPanel`'s padding above and below, the strip, and the panel's gap between strip and face. */
private val ChromeHeight: Dp = PanelPadding * 2 + StripHeight + InkDims.GroupGap

/**
 * Kept clear at the bottom: the bottom zone's one-chip pill (a chip plus the bar's padding), its
 * edge padding, and a gap (research 08 §4.6). Built from the tokens so it moves when they do.
 */
private val BottomReserve: Dp = BarHeight + InkDims.EdgePadding + InkDims.GroupGap
