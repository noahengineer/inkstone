package dev.inkstone.app.ink

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import dev.inkstone.android.ink.EraseMode
import dev.inkstone.android.ink.InkBrushKind
import dev.inkstone.android.ink.InkInputModel
import dev.inkstone.android.ink.InkTool
import dev.inkstone.core.model.FitMode
import kotlin.math.roundToInt

/**
 * ROADMAP M1 — "minimal floating toolbar. **Don't design the toolbar here** —
 * M10 owns it."
 *
 * So this is chips and sliders in a card, and the effort went into making every
 * M1 acceptance criterion reachable without a rebuild: the brushes, the eraser,
 * the colour picker, the fit modes, and the switches the SPEC §3.6 measurements
 * need — prediction on/off for the Appendix B #2 spike, the 5,000-stroke
 * synthetic page for the frame-rate gate, and the latency readout.
 */
@Composable
fun InkToolbar(state: InkUiState, actions: InkToolbarActions, modifier: Modifier = Modifier) {
    var colorPanelOpen by remember { mutableStateOf(false) }
    var measurePanelOpen by remember { mutableStateOf(false) }

    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolRow(state, actions) { colorPanelOpen = !colorPanelOpen }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Also two-finger tap / three-finger tap on the page. The
                // buttons exist so the gesture is discoverable and testable.
                TextButton(onClick = actions.onUndo, enabled = state.canUndo) { Text("Undo") }
                TextButton(onClick = actions.onRedo, enabled = state.canRedo) { Text("Redo") }
                TextButton(onClick = { actions.onFit(FitMode.FIT_WIDTH) }) { Text("Fit width") }
                TextButton(onClick = { actions.onFit(FitMode.FIT_PAGE) }) { Text("Fit page") }
                TextButton(onClick = { actions.onFit(FitMode.ACTUAL_SIZE) }) { Text("100%") }
                Text(
                    "${state.zoomPercent.roundToInt()}% · ${state.strokeCount} strokes",
                    style = MaterialTheme.typography.labelSmall,
                )
                TextButton(onClick = { measurePanelOpen = !measurePanelOpen }) { Text("Measure") }
            }

            // ⭐ ROADMAP M8b — draw-and-hold. In the main row rather than behind "Measure",
            // because it changes what the pen does and a setting that changes the pen has to be
            // where the pen is. The acknowledgement is what makes the feature discoverable: the
            // first time it fires, the user finds out *why* their line straightened.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = state.shapeSnapEnabled,
                    onClick = { state.shapeSnapEnabled = !state.shapeSnapEnabled },
                    label = { Text("Hold to snap") },
                )
                FilterChip(
                    selected = state.arrowHeads,
                    onClick = { state.arrowHeads = !state.arrowHeads },
                    enabled = state.shapeSnapEnabled,
                    label = { Text("Arrow") },
                )
                state.lastSnapped?.let {
                    Text("snapped to $it", style = MaterialTheme.typography.labelSmall)
                }
            }

            // ⭐ ROADMAP M8a. Shown only when something is selected: an action row that is
            // always there but usually disabled says the tool is broken rather than idle.
            AnimatedVisibility(visible = state.selectionSize > 0) {
                SelectionRow(state, actions)
            }

            AnimatedVisibility(visible = colorPanelOpen) {
                ColorPicker(
                    argb = state.pen.colorArgb,
                    slots = state.slots,
                    // ⚠️ The picker drives **two** things now, and which one depends on whether
                    // anything is selected. Wiring it only to the pen was the obvious thing and
                    // would have made "select some writing, pick a colour" change the colour of
                    // the *next* stroke instead of the selected one — visibly doing something,
                    // and the wrong something.
                    onColorChange = { argb ->
                        if (state.selectionSize > 0) actions.onRecolourSelection(argb) else state.setColor(argb)
                    },
                    onSaveToSlot = state::saveToSlot,
                )
            }

            AnimatedVisibility(visible = measurePanelOpen) {
                MeasurementPanel(state, actions)
            }
        }
    }
}

/** SPEC §3.6's controls: the spike A/B switch, the load generator, the readout. */
@Composable
private fun MeasurementPanel(state: InkUiState, actions: InkToolbarActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SwitchRow(
            label = "Motion prediction (SPEC Appendix B #2)",
            checked = state.predictionEnabled,
            onCheckedChange = { state.predictionEnabled = it },
        )
        SwitchRow(
            label = "Spring input model (off = track the nib directly)",
            checked = state.inputModel == InkInputModel.SPRING,
            onCheckedChange = {
                state.inputModel = if (it) InkInputModel.SPRING else InkInputModel.DIRECT
            },
        )
        SwitchRow(
            label = "Finger drawing (off = palm rejection)",
            checked = state.fingerDrawingEnabled,
            onCheckedChange = { state.fingerDrawingEnabled = it },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { actions.onLoadSyntheticPage(GATE_STROKE_COUNT) }) { Text("Load 5,000 strokes") }
            TextButton(onClick = actions.onClear) { Text("Clear") }
            TextButton(onClick = actions.onResetLatency) { Text("Reset latency") }
        }
        val latency = state.latency
        Text(
            if (latency.sampleCount == 0L) {
                "Latency: no samples yet — draw a stroke"
            } else {
                "Latency: mean %.1f ms · worst %.1f ms · %d samples".format(
                    latency.meanMillis,
                    latency.worstMillis,
                    latency.sampleCount,
                )
            },
            style = MaterialTheme.typography.labelSmall,
        )
        PressureReadout(state)
        state.busyMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private val InkBrushKind.label: String
    get() = when (this) {
        InkBrushKind.FOUNTAIN -> "Fountain"
        InkBrushKind.BALLPOINT -> "Ballpoint"
        InkBrushKind.PENCIL -> "Pencil"
        InkBrushKind.HIGHLIGHTER -> "Highlighter"
    }

private const val MIN_WIDTH_DP = 0.5f
private const val MAX_WIDTH_DP = 32f
private const val GATE_STROKE_COUNT = 5000

/**
 * ⭐ ROADMAP M8a — what a selection can have done to it.
 *
 * Every button here calls straight through to `InkSurfaceView`, which emits one gesture's worth
 * of ops through the recorder. That is the whole path: no view model, no session, no file. The
 * ops reach disk on the next autosave drain exactly as a stroke does, and each button is one
 * undo entry.
 *
 * The width slider is deliberately the same control as the pen's, reading the *pen's* current
 * width — a selection has no single width to show (the strokes in it may differ, and SPEC §4.2's
 * `SetStyle` is per object), so the slider is an instruction rather than a readout. Dragging it
 * says "make them this wide"; it does not claim they already are.
 */
@Composable
private fun SelectionRow(state: InkUiState, actions: InkToolbarActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${state.selectionSize} selected",
            style = MaterialTheme.typography.labelSmall,
        )
        TextButton(onClick = actions.onDeleteSelection) { Text("Delete") }
        TextButton(onClick = actions.onDuplicateSelection) { Text("Duplicate") }
        TextButton(onClick = actions.onBringSelectionToFront) { Text("Front") }
        TextButton(onClick = actions.onSendSelectionToBack) { Text("Back") }
        TextButton(onClick = { actions.onResizeSelectionStroke(state.pen.widthDp) }) {
            Text("${state.pen.widthDp.roundToInt()} dp")
        }
        TextButton(onClick = actions.onClearSelection) { Text("Deselect") }
    }
}

/** Brushes, eraser, erase mode, colour swatch and width. */
@Composable
private fun ToolRow(state: InkUiState, actions: InkToolbarActions, onToggleColorPanel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        InkBrushKind.entries.forEach { kind ->
            FilterChip(
                selected = state.tool == InkTool.PEN && state.kind == kind,
                onClick = { state.selectBrush(kind) },
                label = { Text(kind.label) },
            )
        }
        FilterChip(
            selected = state.tool == InkTool.ERASER,
            onClick = { state.tool = if (state.tool == InkTool.ERASER) InkTool.PEN else InkTool.ERASER },
            label = { Text("Eraser") },
        )
        // ⭐ ROADMAP M8a. Leaving the tool clears the selection, because a box round objects
        // the toolbar no longer offers actions for is chrome that lies.
        FilterChip(
            selected = state.tool == InkTool.SELECT,
            onClick = {
                state.tool = if (state.tool == InkTool.SELECT) InkTool.PEN else InkTool.SELECT
                if (state.tool != InkTool.SELECT) actions.onClearSelection()
            },
            label = { Text("Select") },
        )
        // The rectangular half of M8a's "freehand AND rectangular" lasso. A mode, not a tool:
        // enabled only while Select is, the way Arrow is enabled only with Hold-to-snap.
        FilterChip(
            selected = state.tool == InkTool.SELECT && state.marqueeSelection,
            onClick = { state.marqueeSelection = !state.marqueeSelection },
            enabled = state.tool == InkTool.SELECT,
            label = { Text("Box") },
        )
        // SPEC §3.5, revised in M1. Partial is the default; whole-stroke is kept
        // because it never rebuilds geometry, so it stays fast on a dense page,
        // and some people simply prefer it.
        FilterChip(
            selected = state.eraseMode == EraseMode.WHOLE_STROKE,
            onClick = {
                state.eraseMode = if (state.eraseMode == EraseMode.PARTIAL) {
                    EraseMode.WHOLE_STROKE
                } else {
                    EraseMode.PARTIAL
                }
            },
            label = { Text(if (state.eraseMode == EraseMode.PARTIAL) "erase: part" else "erase: whole") },
        )

        Box(
            modifier = Modifier
                .size(28.dp)
                .background(Color(state.pen.colorArgb), CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable { onToggleColorPanel() },
        )

        Text("${state.pen.widthDp.roundToInt()} dp", style = MaterialTheme.typography.labelSmall)
        Slider(
            value = state.pen.widthDp,
            valueRange = MIN_WIDTH_DP..MAX_WIDTH_DP,
            onValueChange = state::setWidth,
            modifier = Modifier.width(120.dp),
        )
    }
}

/**
 * What the digitiser reports for pressure — the readout that answers "why doesn't the pen
 * react to how hard I press?"
 *
 * A stylus with real pressure spans a wide band within one written word. One that reports a
 * constant shows the same number for min and max however hard you press, and no brush tuning
 * will ever help it — the fix in that case is to drive width from speed and tilt instead.
 *
 * Deliberately blunt wording. This is a diagnostic, not a feature.
 */
@Composable
private fun PressureReadout(state: InkUiState) {
    val stylus = state.stylus
    Text(
        text = when {
            stylus.sampleCount == 0L -> "Pressure: no samples yet — draw a stroke"

            stylus.varies -> "Pressure: %.2f (range %.2f–%.2f) · %s%s — reported ✓".format(
                stylus.lastPressure,
                stylus.minPressure,
                stylus.maxPressure,
                stylus.toolName,
                if (stylus.reportsTilt) " · tilt ✓" else " · no tilt",
            )

            else -> "Pressure: FLAT at %.2f over %d samples · %s — this stylus reports no pressure".format(
                stylus.lastPressure,
                stylus.sampleCount,
                stylus.toolName,
            )
        },
        style = MaterialTheme.typography.labelSmall,
    )
}
