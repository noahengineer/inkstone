package dev.inkstone.app.ink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.inkstone.app.ui.ChipDivider
import dev.inkstone.app.ui.GlassPanel
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.InkType
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.PanelHeader
import dev.inkstone.app.ui.Readout
import dev.inkstone.app.ui.ReadoutFamily
import dev.inkstone.app.ui.SlimSlider
import dev.inkstone.app.ui.TextChip
import kotlin.math.max

/**
 * ⭐ ROADMAP M10 — the saved sizes, and the range they live in.
 *
 * ## Every size is editable, there is no count, and the maximum is the user's
 *
 * *"i dont like the size thing it should not be preset sizes and should not be a set amount you
 * should be ablet to edit every one and you should be able to set the max size"*. The first version
 * offered `SAVE` (add the current width, up to ten) and a long press to forget — so the six stock
 * sizes could be removed but never *changed*, and the slider stopped at a hard-coded 32 dp. Now:
 *
 * - **Tap a size to edit it.** It lights, the pen takes it, and the slider and the number beneath
 *   write straight into it until it is tapped again, another is tapped, or the panel closes. That is
 *   the colour panel's *"choose a slot, then the controls edit it"*, so there is one idea to learn.
 * - **`ADD`** keeps the current width and starts editing it; **`DELETE`** forgets the one being
 *   edited — the last one too. No limit; the row wraps. A long press still forgets directly.
 * - **`MAX`** is the top of both width sliders. Lowering it brings every width above it down to it.
 *
 * ## ⚠️ Typed numbers apply on Done, not per keystroke
 *
 * Unlike the colour panel's hex field. A half-typed colour repaints one pen for a frame; a half-typed
 * maximum does not — typing `12` passes through `1`, and a maximum of 1 clamps every brush and every
 * saved size down to 1 dp on the way. So the text is kept locally and applied on the keyboard's Done
 * or when the field loses focus.
 *
 * ## Why a capsule and not a circle
 *
 * The colour panel's slot is a circle because a colour *is* the swatch. A width is not a colour, and
 * a row of identical circles labelled with numbers would be a worse control than the numbers alone. A
 * capsule drawn at the width it represents shows the thing being chosen — and clamps its drawn
 * height, because 32 dp of ink in a 26 dp row would be a black bar rather than a stroke.
 *
 * @param editingIndex which of [slots] the controls are writing into, or -1 — see
 *   `InkUiState.editingWidthSlot`, including why [slots] may be briefly out of order while it is set.
 * @param edits the writers, as one value — the shape `OpenPanels` and `Exports` already use here, and
 *   for the same reason: bare lambdas in a row can be swapped at a call site without the compiler
 *   noticing, and *"choose"* landing on *"forget"* is a deleted size.
 */
@Composable
internal fun SizePanel(
    widthDp: Float,
    slots: List<Float>,
    editingIndex: Int,
    defaultWidthDp: Float,
    maxWidthDp: Float,
    edits: SizeEdits,
    onClose: () -> Unit,
) {
    val editing = editingIndex in slots.indices
    GlassPanel(modifier = Modifier.width(PanelWidth)) {
        Column(verticalArrangement = Arrangement.spacedBy(InkDims.ChipGap)) {
            PanelHeader("Size", onClose)

            SlotRow(widthDp, slots, editingIndex, defaultWidthDp, edits)

            Row(
                horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ⚠️ Deliberately **unsnapped**, unlike the dock's. This is the slider that edits a
                // saved size and picks a width that is not saved yet, and a magnet here would pull
                // the size being edited back onto its neighbours — the control would fight its own
                // purpose.
                SlimSlider(
                    value = widthDp,
                    valueRange = MIN_WIDTH_DP..maxWidthDp,
                    onValueChange = edits.onWidthChange,
                    label = "Stroke width",
                    width = PanelSliderWidth,
                    // The same bend as the dock's, so a width picked here lands where the eye
                    // expects when the panel closes and the dock's thumb takes over.
                    gamma = WIDTH_GAMMA,
                )
                NumberField(value = widthDp, onCommit = edits.onWidthChange, description = "Stroke width in dp")
            }

            SizeActions(widthDp, slots, editing, defaultWidthDp, maxWidthDp, edits)
        }
    }
}

/** The saved sizes, wrapping, with the line underneath that says what a tap does right now. */
@Composable
private fun SlotRow(widthDp: Float, slots: List<Float>, editingIndex: Int, defaultWidthDp: Float, edits: SizeEdits) {
    val editing = editingIndex in slots.indices
    if (slots.isEmpty()) {
        MicroLabel("No saved sizes")
    } else {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap),
            verticalArrangement = Arrangement.spacedBy(InkDims.ChipGap),
        ) {
            slots.forEachIndexed { index, slot ->
                SizeSlot(
                    widthDp = slot,
                    // Lit while it is being edited; otherwise, lit if it is the pen's width.
                    selected = if (editing) index == editingIndex else slot == widthDp,
                    isDefault = slot == defaultWidthDp,
                    onClick = { edits.onChoose(index) },
                    onLongClick = { edits.onForget(index) },
                )
            }
        }
    }
    MicroLabel(
        when {
            editing -> "Editing · tap it again to stop"
            slots.isEmpty() -> "Add keeps the width below"
            else -> "Tap a size to edit it"
        },
    )
}

/** `ADD` · `DELETE` · `DEFAULT`, and the maximum. */
@Composable
private fun SizeActions(
    widthDp: Float,
    slots: List<Float>,
    editing: Boolean,
    defaultWidthDp: Float,
    maxWidthDp: Float,
    edits: SizeEdits,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap)) {
        TextChip(
            label = "Add",
            onClick = edits.onAdd,
            enabled = !editing && slots.none { it == WidthLadder.clamp(widthDp, maxWidthDp) },
        )
        TextChip(label = "Delete", onClick = edits.onDelete, enabled = editing, danger = true)
        ChipDivider()
        TextChip(
            label = "Default",
            onClick = { edits.onMakeDefault(widthDp) },
            selected = widthDp == defaultWidthDp,
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MicroLabel("Max size (dp)", modifier = Modifier.weight(1f))
        NumberField(value = maxWidthDp, onCommit = edits.onMaxChange, description = "Maximum stroke width")
    }
}

/**
 * A number the user can type, applied on Done or when focus leaves.
 *
 * ⚠️ The text is keyed on the **displayed** value, so a slider drag rewrites it and a refused entry
 * snaps back to what is actually applied — a field left reading `abc` or `900` beside a pen that is
 * at 32 dp would be a readout that lies.
 */
@Composable
private fun NumberField(value: Float, onCommit: (Float) -> Unit, description: String) {
    val shown = "%.1f".format(value)
    var text by remember(shown) { mutableStateOf(shown) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = TextStyle(
            color = Ink.TextStrong,
            fontSize = InkType.ReadoutSize,
            fontFamily = ReadoutFamily,
            textAlign = TextAlign.End,
        ),
        cursorBrush = SolidColor(Ink.Focus),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        // Done only drops focus; the focus change is the one place a value is applied.
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier
            .width(FieldWidth)
            .semantics { contentDescription = description }
            .onFocusChanged { state ->
                if (focused && !state.isFocused) {
                    WidthLadder.parse(text)?.let(onCommit)
                    text = shown
                }
                focused = state.isFocused
            }
            .clip(InkDims.ChipCorner)
            .background(Ink.PanelRaised)
            .border(1.dp, if (focused) Ink.Focus else Ink.Hairline, InkDims.ChipCorner)
            .padding(horizontal = FieldPaddingH, vertical = InkDims.ChipPaddingV),
    )
}

/**
 * One saved size: a capsule drawn at the width it sets.
 *
 * ⚠️ The **drawn** thickness is clamped. A 32 dp stroke in a 26 dp row is a filled rectangle, and
 * three of them in a row are indistinguishable — so past the cap the capsules stop growing and the
 * ordering carries the meaning. The number underneath is what actually says which is which, and it
 * is why there is a number at all.
 */
@Composable
private fun SizeSlot(
    widthDp: Float,
    selected: Boolean,
    isDefault: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SlotGap),
        modifier = Modifier
            .clip(InkDims.ChipCorner)
            .background(if (selected) Ink.SegmentActive else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = "Saved size ${WidthLadder.label(widthDp)}" }
            .padding(horizontal = SlotPaddingH, vertical = SlotPaddingV),
    ) {
        Box(
            modifier = Modifier
                .size(width = SlotInkWidth, height = max(widthDp, MIN_DRAWN).coerceAtMost(MAX_DRAWN).dp)
                .clip(CircleShape)
                .background(if (selected) Ink.TextStrong else Ink.TextNormal),
        )
        Readout(WidthLadder.number(widthDp), fontSize = InkType.MicroSize, textAlign = TextAlign.Center)
        // The default is marked rather than named: a second word per capsule would double the
        // panel's width for a fact that only matters to one of them.
        Box(
            modifier = Modifier
                .size(DefaultDot)
                .clip(CircleShape)
                .background(if (isDefault) Ink.Focus else Color.Transparent),
        )
    }
}

/**
 * Everything the size panel can write.
 *
 * See `InkUiState.defaultWidthDp` on why [onMakeDefault] is not the same as "the last one used", and
 * `InkUiState.setMaxWidth` on what [onMaxChange] does to widths above it.
 */
@androidx.compose.runtime.Immutable
internal class SizeEdits(
    val onWidthChange: (Float) -> Unit,
    val onChoose: (Int) -> Unit,
    val onForget: (Int) -> Unit,
    val onAdd: () -> Unit,
    val onDelete: () -> Unit,
    val onMakeDefault: (Float) -> Unit,
    val onMaxChange: (Float) -> Unit,
)

private val PanelWidth = 300.dp
private val PanelSliderWidth = 196.dp
private val FieldWidth = 62.dp
private val FieldPaddingH = 8.dp
private val SlotInkWidth = 26.dp
private val SlotPaddingH = 4.dp
private val SlotPaddingV = 6.dp
private val SlotGap = 5.dp
private val DefaultDot = 5.dp

/** Below this a capsule is a hairline nobody can compare; above it, a black bar. */
private const val MIN_DRAWN = 1.5f
private const val MAX_DRAWN = 14f

private val Float.dp get() = androidx.compose.ui.unit.Dp(this)
