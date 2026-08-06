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
            ToolRow(state) { colorPanelOpen = !colorPanelOpen }

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

            AnimatedVisibility(visible = colorPanelOpen) {
                ColorPicker(
                    argb = state.pen.colorArgb,
                    slots = state.slots,
                    onColorChange = state::setColor,
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

/** Brushes, eraser, erase mode, colour swatch and width. */
@Composable
private fun ToolRow(state: InkUiState, onToggleColorPanel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        InkBrushKind.entries.forEach { kind ->
            FilterChip(
                selected = !state.eraserSelected && state.kind == kind,
                onClick = { state.selectBrush(kind) },
                label = { Text(kind.label) },
            )
        }
        FilterChip(
            selected = state.eraserSelected,
            onClick = { state.eraserSelected = !state.eraserSelected },
            label = { Text("Eraser") },
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
