package dev.inkstone.app.ink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.inkstone.core.model.ImageCrop
import dev.inkstone.core.model.ListStyle
import dev.inkstone.core.model.ObjectFormat
import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.TextAlign
import dev.inkstone.core.model.TextFont
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * ROADMAP M8c — *"font, size, colour, alignment, bullet and numbered lists with nesting"*.
 *
 * Shown only while a text box is being edited. A format bar that was always present would be a row
 * of controls that do nothing when pressed, which is the dishonest-button problem `UndoStack.commit`
 * fixes one layer down and `ShapeRow` follows on this one.
 *
 * ⚠️ **Colour is on this row and goes to `SetStyle`, not into the record.** SPEC §4.9.2: one of the
 * five properties in the scope line was never homeless, and noticing that came from reading the
 * type rather than the sentence.
 */
@Composable
fun TextFormatRow(state: ObjectToolState, slots: List<Int>, actions: ObjectActions) {
    if (state.editing == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FontRow(state, actions)
        ParagraphRow(state, actions)
        ColourRow(state, slots, actions)
    }
}

/** Family and size. Both are `SetFormat`, never `SetTextContent` — SPEC §4.9.2. */
@Composable
private fun FontRow(state: ObjectToolState, actions: ObjectActions) {
    val id = state.editing?.id
    val format = state.format
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextFont.entries.forEach { font ->
            FilterChip(
                selected = format.font == font,
                onClick = {
                    state.format = format.copy(font = font)
                    actions.onFormatChanged(id) { it.copy(font = font) }
                },
                label = { Text(font.label) },
            )
        }
        TextButton(onClick = { state.resize(id, -SIZE_STEP_PT, actions) }) { Text("A−") }
        Text("${format.sizePt.roundToInt()} pt", style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { state.resize(id, SIZE_STEP_PT, actions) }) { Text("A+") }
    }
}

/** Alignment, and *"bullet and numbered lists with nesting"*. */
@Composable
private fun ParagraphRow(state: ObjectToolState, actions: ObjectActions) {
    val id = state.editing?.id
    val format = state.format
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextAlign.entries.forEach { align ->
            FilterChip(
                selected = format.align == align,
                onClick = {
                    state.format = format.copy(align = align)
                    actions.onFormatChanged(id) { it.copy(align = align) }
                },
                label = { Text(align.label) },
            )
        }
        ListStyle.entries.forEach { style ->
            FilterChip(
                selected = format.listStyle == style,
                onClick = { state.setList(id, style, actions) },
                label = { Text(style.label) },
            )
        }
        TextButton(onClick = { state.indent(id, -1, actions) }, enabled = format.listLevel > 1) { Text("◀") }
        Text("level ${format.listLevel}", style = MaterialTheme.typography.labelSmall)
        TextButton(
            onClick = { state.indent(id, 1, actions) },
            enabled = format.listStyle != ListStyle.NONE && format.listLevel < MAX_LIST_LEVEL,
        ) { Text("▶") }
    }
}

/**
 * ⚠️ Colour, and it is **`SetStyle`** — not the format record.
 *
 * SPEC §4.9.2: one of the five properties in the scope line was never homeless, and noticing that
 * came from reading the type rather than the sentence.
 */
@Composable
private fun ColourRow(state: ObjectToolState, slots: List<Int>, actions: ObjectActions) {
    val id = state.editing?.id
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        slots.take(SWATCHES).forEach { argb ->
            Box(
                modifier = Modifier
                    .size(SWATCH_DP.dp)
                    .background(Color(argb), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable {
                        state.style = state.style.copy(colorArgb = argb)
                        actions.onColourChanged(id, argb)
                    },
            )
        }
        TextButton(
            onClick = {
                actions.onTextChanged(id, state.draft)
                state.stopEditing()
            },
        ) { Text("Done") }
    }
}

/**
 * ROADMAP M8c — insert an image, and what can then be done to it.
 *
 * The two inserts are always offered; the transforms appear once a picture has been tapped with
 * the Image tool, because until then there is nothing for them to act on.
 *
 * ⚠️ **Flip is here because Goodnotes cannot do it** (ROADMAP M8c acceptance 2), and it is one
 * `SetTransform` carrying a negated matrix — absolute, like everything else in that register.
 */
@Composable
fun ImageRow(state: ObjectToolState, actions: ObjectActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { actions.onInsertImage(ImageSource.GALLERY) }) { Text("Gallery") }
            TextButton(onClick = { actions.onInsertImage(ImageSource.CAMERA) }) { Text("Camera") }
            Text(
                if (state.image == null) "tap a picture to change it" else "1 picture",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        val focused = state.image ?: return@Column

        // ⭐ While cropping the row is the crop's own controls and nothing else. A Rotate button
        // live during a crop would write a `SetTransform` under the overlay's feet — the handles
        // are positioned from the frame it just changed — and the user would watch the frame jump
        // off the picture.
        if (state.cropping != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("drag the corners", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { state.commitCrop(actions) }) { Text("Done") }
                TextButton(onClick = { state.cancelCrop() }) { Text("Cancel") }
                TextButton(onClick = { state.resetCrop() }) { Text("Reset") }
            }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { actions.onTurn(focused.id, QUARTER_TURN, false, false) }) { Text("Rotate") }
            TextButton(onClick = { actions.onTurn(focused.id, 0f, true, false) }) { Text("Flip ⇄") }
            TextButton(onClick = { actions.onTurn(focused.id, 0f, false, true) }) { Text("Flip ⇅") }
            TextButton(onClick = { state.beginCrop() }) { Text("Crop") }
            TextButton(onClick = { actions.onCrop(focused.id, null) }) { Text("Uncrop") }
        }
    }
}

/**
 * ROADMAP M8c — the Elements row: capture, browse, stamp.
 *
 * ⚠️ **"Drag a box" and not "save the selection", and that is a seam rather than a preference.**
 * `InkSelection` exposes a *count* and not its object ids, and a stroke's bounds cannot be
 * measured outside `:android:ink` at all (SPEC §5.4) — so `:app` can neither ask the lasso what it
 * holds nor work it out. The label says so, because a capture that silently left the handwriting
 * behind would be discovered by stamping it.
 */
@Composable
fun ElementRow(state: ObjectToolState, actions: ObjectActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = state.elementsOpen,
            onClick = { state.elementsOpen = !state.elementsOpen },
            label = { Text("Elements") },
        )
        Text(
            when {
                state.pendingElement != null -> "tap the page to stamp ${state.pendingElement?.name}"
                else -> "drag a box round shapes, pictures or text to save one"
            },
            style = MaterialTheme.typography.labelSmall,
        )
    }
    state.capturedArea?.let { NameElementDialog(state, actions) }
}

/** Name and file the captured area. Two fields, because a library with one collection is a list. */
@Composable
private fun NameElementDialog(state: ObjectToolState, actions: ObjectActions) {
    var name by remember { mutableStateOf("") }
    var collection by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { state.capturedArea = null },
        title = { Text("Save as Element") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                OutlinedTextField(
                    value = collection,
                    onValueChange = { collection = it },
                    label = { Text("Collection") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { actions.onSaveElement(name, collection) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { state.capturedArea = null }) { Text("Cancel") } },
    )
}

/**
 * ROADMAP M8c — *"an exact numeric angle"*, and the three angles worth one tap.
 *
 * The edge itself is dragged and turned on the page, by `:android:ink`'s `RulerController`; this
 * row is the half a finger cannot do. Dragging a straight edge to exactly 30° is not a thing a
 * hand can do, and *"a diagram at exactly 30°"* is the case a ruler exists for.
 *
 * ⚠️ [InkUiState.rulerAngleDegrees] is a **mirror**, pushed back from the surface by
 * `onRulerChanged` — so the field follows a finger drag instead of going stale the moment anybody
 * touches the ruler itself. `typed` is keyed on it for the same reason: a field that kept its own
 * string would show the angle the user last entered rather than the angle the ruler is at.
 */
@Composable
fun RulerRow(ui: InkUiState, actions: ObjectActions) {
    var typed by remember(ui.rulerAngleDegrees) { mutableStateOf(ui.rulerAngleDegrees.roundToInt().toString()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = typed,
            onValueChange = { text ->
                typed = text
                // Applied only when it parses, and left alone when it does not: a half-typed "-"
                // or "3." must not swing the ruler to zero under the user's hand.
                text.trim().toFloatOrNull()?.let(actions.onRulerAngle)
            },
            label = { Text("angle °") },
            singleLine = true,
            modifier = Modifier.width(110.dp),
        )
        listOf(0f, HALF_RIGHT_ANGLE, RIGHT_ANGLE).forEach { angle ->
            TextButton(onClick = { actions.onRulerAngle(angle) }) { Text("${angle.roundToInt()}°") }
        }
    }
}

private fun ObjectToolState.resize(id: ObjectId?, delta: Float, actions: ObjectActions) {
    val next = (format.sizePt + delta).coerceIn(MIN_SIZE_PT, MAX_SIZE_PT)
    format = format.copy(sizePt = next)
    actions.onFormatChanged(id) { it.copy(sizePt = next) }
}

/**
 * Turning a list on gives it level 1, and turning it off leaves the level alone.
 *
 * Level 0 means *"not a list"* ([ObjectFormat]'s own KDoc), so a bullet chip that left the level at
 * zero would be a list style with nothing to indent — the chip would highlight and the text would
 * not move.
 */
private fun ObjectToolState.setList(id: ObjectId?, style: ListStyle, actions: ObjectActions) {
    val next = if (format.listStyle == style) ListStyle.NONE else style
    val level = if (next == ListStyle.NONE) 0 else format.listLevel.coerceAtLeast(1)
    format = format.copy(listStyle = next, listLevel = level)
    actions.onFormatChanged(id) { it.copy(listStyle = next, listLevel = level) }
}

private fun ObjectToolState.indent(id: ObjectId?, delta: Int, actions: ObjectActions) {
    val level = (format.listLevel + delta).coerceIn(1, MAX_LIST_LEVEL)
    format = format.copy(listLevel = level)
    actions.onFormatChanged(id) { it.copy(listLevel = level) }
}

private val TextFont.label: String
    get() = when (this) {
        TextFont.SANS -> "Sans"
        TextFont.SERIF -> "Serif"
        TextFont.MONO -> "Mono"
    }

private val TextAlign.label: String
    get() = when (this) {
        TextAlign.START -> "Left"
        TextAlign.CENTER -> "Centre"
        TextAlign.END -> "Right"
    }

private val ListStyle.label: String
    get() = when (this) {
        ListStyle.NONE -> "No list"
        ListStyle.BULLET -> "Bullets"
        ListStyle.NUMBERED -> "Numbers"
    }

private const val SIZE_STEP_PT = 2f
private const val MIN_SIZE_PT = 6f
private const val MAX_SIZE_PT = 96f

/** Three levels of nesting. Past that the indent is wider than a page column. */
private const val MAX_LIST_LEVEL = 3

private const val SWATCHES = 8
private const val SWATCH_DP = 22
private const val QUARTER_TURN = (PI / 2).toFloat()
private const val HALF_RIGHT_ANGLE = 45f
private const val RIGHT_ANGLE = 90f
