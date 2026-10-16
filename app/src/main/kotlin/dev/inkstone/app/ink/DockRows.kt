package dev.inkstone.app.ink

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.inkstone.android.ink.EraseMode
import dev.inkstone.android.ink.InkBrushKind
import dev.inkstone.app.ui.ChipDivider
import dev.inkstone.app.ui.ChipKind
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.Readout
import dev.inkstone.app.ui.ReadoutChip
import dev.inkstone.app.ui.SegmentChip
import dev.inkstone.app.ui.Segmented
import dev.inkstone.app.ui.SlimSlider
import dev.inkstone.app.ui.SwatchStrip
import dev.inkstone.app.ui.TextChip
import kotlin.math.roundToInt

/**
 * ⭐ ROADMAP M10 — row two of the Dynamic Dock for every tool but the pen.
 *
 * One `when` over [SecondaryRow] rather than a stack of `AnimatedVisibility`s, because the design's
 * row two *swaps* rather than accumulating. The pen's row is not here: it is the `DRAW` bar, made of
 * chips the user can rearrange and move elsewhere, and `DockSecondary` draws it with `ChromeContainer`.
 */
@Composable
internal fun RowScope.SecondaryContent(
    row: SecondaryRow,
    state: InkUiState,
    objects: ObjectToolState,
    actions: InkToolbarActions,
    objectActions: ObjectActions,
) {
    when (row) {
        SecondaryRow.NONE, SecondaryRow.DRAW -> Unit
        SecondaryRow.ERASE -> EraseControls(state)
        SecondaryRow.SELECT -> SelectControls(state, actions)
        SecondaryRow.IMAGE -> ImageInsertControls(objectActions)
        SecondaryRow.ELEMENT -> ElementControls(objects)
    }
}

/**
 * One chip of the design's Draw state: *"Brush Types · Width Slider · Inline Color Swatches ·
 * Extended Color Toggle"*, plus the two chips that change what a drawn stroke *becomes*.
 *
 * A brush is drawn as a bare segment; whichever bar holds it puts it in a recessed track — one track
 * for a run of brushes in a row, which with the stock order is the one segmented control the design
 * draws.
 *
 * ⚠️ Draw-and-hold lives with the pen rather than behind a settings panel because it changes what the
 * pen does — the argument M8b's `SnapRow` made and the redesign does not overturn. `Arrow` is enabled
 * only with it, because an arrowhead is a property of a *snapped* line (SPEC §4.8.7).
 */
@Composable
internal fun DrawChip(item: ChromeItem, state: InkUiState, onSummon: (PanelKind, Rect) -> Unit) {
    val brush = item.brush
    if (brush != null) {
        SegmentChip(label = brush.label, selected = state.kind == brush, onClick = { state.selectBrush(brush) })
        return
    }
    when (item) {
        ChromeItem.WIDTH_SLIDER -> WidthSlider(state)

        ChromeItem.WIDTH_READOUT -> WidthReadoutChip(state, onSummon)

        ChromeItem.SWATCHES -> QuickSwatches(state, onSummon)

        ChromeItem.SNAP -> TextChip(
            label = "Snap",
            selected = state.shapeSnapEnabled,
            onClick = { state.shapeSnapEnabled = !state.shapeSnapEnabled },
        )

        ChromeItem.ARROW -> TextChip(
            label = "Arrow",
            selected = state.arrowHeads,
            enabled = state.shapeSnapEnabled,
            onClick = { state.arrowHeads = !state.arrowHeads },
        )

        // ⚠️ Deliberately **not** gated on [InkUiState.shapeSnapEnabled], unlike Arrow. Draw-and-hold
        // is one of the three things this lattice catches — a move, a resize and a dragged vertex are
        // the others, and all three are reachable with draw-and-hold switched off.
        ChromeItem.GRID -> TextChip(
            label = "Grid",
            selected = state.gridSnapEnabled,
            onClick = { state.gridSnapEnabled = !state.gridSnapEnabled },
        )

        else -> Unit
    }
}

@Composable
private fun WidthSlider(state: InkUiState) {
    SlimSlider(
        value = state.pen.widthDp,
        // The top is the user's — `SizePanel`'s MAX.
        valueRange = MIN_WIDTH_DP..state.maxWidthDp,
        // ⭐ M10 — the slider magnetises onto the saved sizes, which are marked on its track.
        //
        // The pull lives at the call site rather than inside the slider because the *panel's* own
        // slider must not have it: that one exists to pick a width which is deliberately not yet
        // saved, and a control that snapped away from every new value would fight its own purpose.
        onValueChange = { state.setWidth(WidthMagnet.pull(it, state.widthSlots) ?: it) },
        label = WIDTH_LABEL,
        ticks = state.widthSlots,
        gamma = WIDTH_GAMMA,
    )
}

/** ⭐ M10 — the number opens the panel that manages the saved sizes. */
@Composable
private fun WidthReadoutChip(state: InkUiState, onSummon: (PanelKind, Rect) -> Unit) {
    var sizeAnchor by remember { mutableStateOf(Rect.Zero) }
    ReadoutChip(
        text = WidthLadder.label(state.pen.widthDp),
        onClick = { onSummon(PanelKind.SIZE, sizeAnchor) },
        contentDescription = WIDTH_LABEL,
        onBounds = { sizeAnchor = it },
        contentWidth = WidthReadout,
    )
}

@Composable
private fun QuickSwatches(state: InkUiState, onSummon: (PanelKind, Rect) -> Unit) {
    // ⭐ A panel line is the panel's full width, and three swatches in it were a long empty track with
    // a few dots in the middle. Six fill it and are three more colours one tap away. They are the
    // palette's first six slots, so a drag onto one is still a drop on that slot (`ColorDragState`).
    val quick = if (LocalBarArrangement.current == ChromeArrangement.PANEL) PANEL_QUICK_SLOTS else QUICK_SLOTS
    SwatchStrip(
        colors = state.slots.take(quick),
        selectedArgb = state.pen.colorArgb,
        // A tap on a quick swatch also *chooses* that slot, so opening the panel straight after
        // edits the one you just picked rather than nothing.
        onPick = { argb ->
            state.chooseSlot(state.slots.indexOf(argb))
            state.setColor(argb)
        },
        onMore = { onSummon(PanelKind.COLOUR, it) },
        drag = state.colorDrag,
        onDropped = state::swapSlots,
    )
}

/** The design's Erase state: *"Toggle between `PART` and `WHOLE`"*. SPEC §3.5 — partial is default. */
@Composable
private fun RowScope.EraseControls(state: InkUiState) {
    Segmented {
        SegmentChip(
            label = "Part",
            selected = state.eraseMode == EraseMode.PARTIAL,
            onClick = { state.eraseMode = EraseMode.PARTIAL },
        )
        SegmentChip(
            label = "Whole",
            selected = state.eraseMode == EraseMode.WHOLE_STROKE,
            onClick = { state.eraseMode = EraseMode.WHOLE_STROKE },
        )
    }
}

/**
 * The design's Select state: *"a `BOX` chip, enabling rectangular marquee selection instead of
 * freehand lasso"*.
 *
 * A segmented pair rather than a lone toggle, because *lasso* is a real choice and a single unlit
 * `BOX` chip leaves no way to see which of the two is active.
 */
@Composable
private fun RowScope.SelectControls(state: InkUiState, actions: InkToolbarActions) {
    Segmented {
        SegmentChip(
            label = "Lasso",
            selected = !state.marqueeSelection,
            onClick = { state.marqueeSelection = false },
        )
        SegmentChip(
            label = "Box",
            selected = state.marqueeSelection,
            onClick = { state.marqueeSelection = true },
        )
    }
    if (state.selectionSize > 0) {
        ChipDivider()
        TextChip(label = "Deselect", onClick = actions.onClearSelection)
    }
}

/**
 * The design's Image state. Only the two *inserts* — everything that can be done to a picture that
 * is already on the page is a contextual toolbar over the picture (`ContextBars`).
 */
@Composable
private fun RowScope.ImageInsertControls(actions: ObjectActions) {
    TextChip(label = "Gallery", onClick = { actions.onInsertImage(ImageSource.GALLERY) })
    TextChip(label = "Camera", onClick = { actions.onInsertImage(ImageSource.CAMERA) })
}

/**
 * The design's Elements state — one toggle for the library.
 *
 * ⚠️ **The capture gesture is a drag, and nothing on screen now says so.** That was a sentence
 * beside this chip until the design review, and the reason it was there is still true: `InkSelection`
 * exposes a count and not its object ids, and a stroke's bounds cannot be measured outside
 * `:android:ink` at all (SPEC §5.4), so `:app` can neither ask the lasso what it holds nor work it
 * out — a capture that silently left the handwriting behind would be discovered by stamping it. What
 * shows the armed state now is `ElementPanel`, which highlights the armed element; if that stops
 * being true, this is the gesture with no affordance.
 */
@Composable
private fun RowScope.ElementControls(objects: ObjectToolState) {
    TextChip(
        label = "Library",
        selected = objects.elementsOpen,
        onClick = { objects.elementsOpen = !objects.elementsOpen },
    )
}

/**
 * ROADMAP M8c — *"an exact numeric angle"*, and the three angles worth one tap.
 *
 * The edge itself is dragged and turned on the page by `:android:ink`'s `RulerController`; this is
 * the half a finger cannot do. Dragging a straight edge to exactly 30° is not a thing a hand can do,
 * and *"a diagram at exactly 30°"* is the case a ruler exists for.
 *
 * ⚠️ [InkUiState.rulerAngleDegrees] is a **mirror**, pushed back from the surface by
 * `onRulerChanged`, so the field follows a finger drag instead of going stale the moment anybody
 * touches the edge itself. `typed` is keyed on it for the same reason: a field that kept its own
 * string would show the angle the user last entered rather than the angle the ruler is at.
 */
@Composable
internal fun RulerControls(state: InkUiState, actions: ObjectActions) {
    var typed by remember(state.rulerAngleDegrees) {
        mutableStateOf(state.rulerAngleDegrees.roundToInt().toString())
    }
    MicroLabel("angle")
    OutlinedTextField(
        value = typed,
        onValueChange = { text ->
            typed = text
            // Applied only when it parses, and left alone when it does not: a half-typed "-" or
            // "3." must not swing the ruler to zero under the user's hand.
            text.trim().toFloatOrNull()?.let(actions.onRulerAngle)
        },
        singleLine = true,
        suffix = { Text("°") },
        modifier = Modifier.width(AngleFieldWidth),
    )
    SNAP_ANGLES.forEach { angle ->
        TextChip(
            label = "${angle.roundToInt()}°",
            kind = ChipKind.VALUE,
            onClick = { actions.onRulerAngle(angle) },
        )
    }
}

/** The brush a Draw-row chip selects, or null for every chip that is not a brush. */
internal val ChromeItem.brush: InkBrushKind?
    get() = when (this) {
        ChromeItem.BRUSH_FOUNTAIN -> InkBrushKind.FOUNTAIN
        ChromeItem.BRUSH_BALLPOINT -> InkBrushKind.BALLPOINT
        ChromeItem.BRUSH_PENCIL -> InkBrushKind.PENCIL
        ChromeItem.BRUSH_HIGHLIGHTER -> InkBrushKind.HIGHLIGHTER
        else -> null
    }

private val InkBrushKind.label: String
    get() = when (this) {
        InkBrushKind.FOUNTAIN -> "Fountain"
        InkBrushKind.BALLPOINT -> "Ballpoint"
        InkBrushKind.PENCIL -> "Pencil"
        InkBrushKind.HIGHLIGHTER -> "Highlighter"
    }

private val SNAP_ANGLES = listOf(0f, 45f, 90f)

/**
 * Wide enough for seven glyphs — "32.0 dp" — fixed so the swatches beside it do not shuffle as the
 * slider moves. The maximum is settable up to 200 dp now, and `WidthLadder.label` drops the decimal
 * from three digits up so a width never needs an eighth.
 *
 * ⚠️ **It was 52 dp and the comment already claimed this, and it was false** — measured on the
 * device, "16.0 dp" rendered as "16.0 …". Nothing had caught it because nothing could *reach* a
 * two-digit width without dragging the slider to the far end, and the readout was a caption nobody
 * read at 32 dp. M10's tap cycle reaches 16 in four presses, which is how a latent truncation
 * became a visible one. Seven monospace glyphs at `InkType.ReadoutSize` need ~66 dp; this is that
 * with headroom, and it is the width of the **text**, not of the chip around it.
 */
private val WidthReadout = 72.dp
private val AngleFieldWidth = 108.dp

/**
 * ⚠️ **The width slider is not linear, and it had to stop being so the moment it grew marks.**
 *
 * Stroke widths are used logarithmically — the difference between 1 and 2 dp matters as much as the
 * one between 8 and 16 — but a linear 0.5–32 track puts 1, 2, 3 and 4 dp within **18 px of each
 * other** and 24 px from the end stop. Measured on the emulator, the four marks merged into one
 * smudge and the whole useful range of a pen lived in the left eighth of the track.
 *
 * The square root spreads the same six saved sizes across 24–135 px of a 192 px track, with no two
 * closer than 10 px. It bends the *position* only: `SlimSlider.valueAt` is the exact inverse, so a
 * thumb on a mark still reads back as that mark's value.
 */
internal const val WIDTH_GAMMA = 0.5f

internal const val MIN_WIDTH_DP = 0.5f

/** The design's *"3–4 quick-access circular color slots"*. Three, plus the `+`. */
private const val QUICK_SLOTS = 3

/** The same strip in a panel, which is wide enough for half the palette. */
private const val PANEL_QUICK_SLOTS = 6
private const val WIDTH_LABEL = "Stroke width"
