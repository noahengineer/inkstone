package dev.inkstone.app.ink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.inkstone.app.ui.CardFlow
import dev.inkstone.app.ui.ChipKind
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.InkType
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.ReadoutFamily
import dev.inkstone.app.ui.SegmentChip
import dev.inkstone.app.ui.Segmented
import dev.inkstone.app.ui.TextChip

/**
 * ⭐ The layouts the user has kept (`SavedLayouts`, docs/LAYOUTS.md): tap one to put it on screen, keep
 * the one on screen as a new one, rename it, delete it. *Default* is always first and cannot be changed —
 * a change made to it starts a new layout, which is how there is always a way back.
 *
 * Delete asks twice, on the chip itself: a layout is somebody's afternoon of arranging, and a second tap
 * is cheaper than a dialog (CLAUDE.md rule 6 is about sync, but the reasoning — never modal — carries).
 */
@Composable
internal fun LayoutsTab(ui: InkUiState) {
    val saved = ui.savedLayouts
    val active = saved.active
    CardFlow(Modifier.fillMaxWidth()) {
        TextChip(
            label = SavedLayouts.DEFAULT_NAME,
            selected = active == null,
            kind = ChipKind.VALUE,
            onClick = { ui.switchLayout(null) },
        )
        saved.layouts.forEach { named ->
            TextChip(
                label = named.name,
                selected = active == named.id,
                kind = ChipKind.VALUE,
                onClick = { ui.switchLayout(named.id) },
            )
        }
    }
    var renaming by remember(active) { mutableStateOf(false) }
    var confirming by remember(active) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap)) {
        TextChip(label = "Save as new", onClick = { ui.duplicateLayout() })
        TextChip(label = "Rename", enabled = active != null, selected = renaming, onClick = { renaming = !renaming })
        TextChip(
            label = if (confirming) "Delete it" else "Delete",
            enabled = active != null,
            danger = true,
            onClick = {
                if (confirming && active != null) ui.deleteLayout(active) else confirming = true
            },
        )
    }
    if (renaming && active != null) {
        NameField(saved.activeName) { name ->
            ui.renameLayout(active, name)
            renaming = false
        }
    }
    MicroLabel(
        if (active == null) "Changing Default starts a new layout" else "Changes save to this layout",
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * ⭐ The panel column: which side it stands on, and the order panels stack in (docs/LAYOUTS.md). A panel
 * that has been dragged out of the column is marked, with the way back in beside it.
 */
@Composable
internal fun PanelsTab(ui: InkUiState) {
    val layout = ui.layout
    OptionRow("Column") {
        Segmented {
            PanelSide.entries.forEach { side ->
                SegmentChip(side.label, selected = layout.panelSide == side, onClick = {
                    ui.layout = ui.layout.withPanelSide(side)
                })
            }
        }
    }
    MicroLabel("Order, top first", modifier = Modifier.fillMaxWidth())
    val order = layout.orderedPanels
    order.forEachIndexed { index, slot ->
        OptionRow(slot.label) {
            TextChip(
                label = "▲",
                kind = ChipKind.GLYPH,
                enabled = index > 0,
                contentDescription = "Move ${slot.label} up",
                onClick = { ui.layout = ui.layout.panelMoved(slot, -1) },
            )
            TextChip(
                label = "▼",
                kind = ChipKind.GLYPH,
                enabled = index < order.lastIndex,
                contentDescription = "Move ${slot.label} down",
                onClick = { ui.layout = ui.layout.panelMoved(slot, 1) },
            )
            if (slot in layout.floatingPanels) {
                TextChip(label = "Dock", onClick = { ui.layout = ui.layout.panelPlaced(slot, null) })
            }
        }
    }
    MicroLabel("Drag a panel by its name to move it", modifier = Modifier.fillMaxWidth())
}

/** ⭐ The two style switches: the pen's brushes as a menu or as buttons, and a contextual bar's shape. */
@Composable
internal fun StyleTab(ui: InkUiState) {
    val layout = ui.layout
    OptionRow("Brushes") {
        Segmented {
            SegmentChip("Menu", selected = layout.brushMenu, onClick = { ui.layout = ui.layout.withBrushMenu(true) })
            SegmentChip("Buttons", selected = !layout.brushMenu, onClick = {
                ui.layout = ui.layout.withBrushMenu(false)
            })
        }
    }
    OptionRow("Object bars") {
        Segmented {
            CardStyle.entries.forEach { style ->
                SegmentChip(style.label, selected = layout.cardStyle == style, onClick = {
                    ui.layout = ui.layout.withCardStyle(style)
                })
            }
        }
    }
}

/** A label, then its controls, on one line. */
@Composable
private fun OptionRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap),
    ) {
        MicroLabel(label, modifier = Modifier.width(EditorLabelWidth), color = Ink.TextNormal)
        content()
    }
}

/**
 * A layout's name, typed. Done keeps it; a blank name is refused by `SavedLayouts.renamed` and the old
 * one stays, because two layouts with no name cannot be told apart.
 */
@Composable
private fun NameField(initial: String, onDone: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    BasicTextField(
        value = text,
        onValueChange = { text = it.take(SavedLayouts.MAX_NAME) },
        singleLine = true,
        textStyle = TextStyle(color = Ink.TextStrong, fontSize = InkType.ReadoutSize, fontFamily = ReadoutFamily),
        cursorBrush = SolidColor(Ink.Focus),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone(text) }),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Layout name" }
            .clip(InkDims.ChipCorner)
            .background(Ink.PanelRaised)
            .border(1.dp, Ink.Focus, InkDims.ChipCorner)
            .padding(horizontal = FieldPadding, vertical = InkDims.ChipPaddingV),
    )
}

private val FieldPadding = 10.dp
