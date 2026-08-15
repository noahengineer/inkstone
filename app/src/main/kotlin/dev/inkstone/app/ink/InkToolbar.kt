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
import dev.inkstone.core.model.ConnectorRoute
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
fun InkToolbar(
    state: InkUiState,
    objects: ObjectToolState,
    actions: InkToolbarActions,
    objectActions: ObjectActions,
    modifier: Modifier = Modifier,
) {
    var colorPanelOpen by remember { mutableStateOf(false) }
    var measurePanelOpen by remember { mutableStateOf(false) }

    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolRow(state, actions) { colorPanelOpen = !colorPanelOpen }

            // ⭐ ROADMAP M8c — the five new tools, in the row under the brushes. Every one of them
            // is a chip a finger can reach: CLAUDE.md's definition-of-done #6 is the whole point of
            // this milestone, and a feature that is correct in every test and unreachable in the
            // app has shipped broken six times in this repo already.
            ObjectToolRow(state, objects, objectActions.onRulerEnabled)

            ObjectRows(state, objects, objectActions)

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
                    // ⭐ **Adjust** — the one tap between "the shape appeared" and being able to
                    // edit it. `InkSurfaceView.onSnapped` deliberately does *not* switch the tool
                    // (someone snapping five boxes for a flowchart would pay five taps to get the
                    // pen back) and it selects the new shape instead, so its point handles are
                    // already on screen — under a pen that draws straight through them.
                    //
                    // ⚠️ Its own KDoc has claimed since M8b that *"InkScreen surfaces an 'Adjust'
                    // action instead"*. It did not: nothing in `:app` had ever contained the word.
                    // A comment describing a caller that does not exist is CLAUDE.md's
                    // definition-of-done #6 written down as a promise, and the handles this
                    // milestone adds are exactly what it was promising access to.
                    TextButton(onClick = { state.tool = EditorTool.SELECT }) { Text("Adjust") }
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

/**
 * ⭐ ROADMAP M8c — the row that belongs to whichever object tool is selected.
 *
 * Each appears only when it has something to act on. A format bar with no text box, or a crop
 * button with no picture, is a row of controls that do nothing when pressed — the dishonest-button
 * problem `UndoStack.commit` fixes one layer down and `ShapeRow` follows on this one.
 */
@Composable
private fun ObjectRows(state: InkUiState, objects: ObjectToolState, actions: ObjectActions) {
    AnimatedVisibility(visible = state.tool == EditorTool.IMAGE) { ImageRow(objects, actions) }
    AnimatedVisibility(visible = state.tool == EditorTool.ELEMENT) { ElementRow(objects, actions) }
    AnimatedVisibility(visible = objects.editing != null) { TextFormatRow(objects, state.slots, actions) }
    AnimatedVisibility(visible = state.rulerEnabled) { RulerRow(state, actions) }
}

/**
 * ⭐ ROADMAP M8c — Text, Image, Elements, Laser, and the ruler.
 *
 * Four tools and one mode, which is why the ruler is a separate chip and not a fifth entry in the
 * same exclusive group: it is used *with* the pen. `EditorTool` carries the same reasoning — one
 * value for the tool so that "the text tool and the eraser are both on" is unrepresentable, and a
 * boolean for the ruler because it genuinely coexists.
 *
 * ⚠️ Leaving a tool clears what it was working on, for the reason M8a's Select chip clears the
 * selection: a text field floating over a page whose tool is now the eraser, or crop handles round
 * a picture nothing can crop, is chrome that lies about what a press will do.
 */
@Composable
private fun ObjectToolRow(state: InkUiState, objects: ObjectToolState, onRuler: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OBJECT_TOOLS.forEach { (tool, label) ->
            FilterChip(
                selected = state.tool == tool,
                onClick = {
                    state.tool = if (state.tool == tool) EditorTool.PEN else tool
                    if (state.tool != tool) objects.reset()
                },
                label = { Text(label) },
            )
        }
        // ⚠️ The ruler is a **mode**, not a member of the group above: the pen keeps drawing while
        // it is out, which is the whole point of a straight edge. It also does not write through
        // the UI state — `InkSurfaceView.ruler` owns the edge and the snap, and this state is a
        // mirror it pushes back (see `InkUiState.rulerEnabled`).
        FilterChip(
            selected = state.rulerEnabled,
            onClick = { onRuler(!state.rulerEnabled) },
            label = { Text("Ruler") },
        )
    }
}

private val OBJECT_TOOLS = listOf(
    EditorTool.TEXT to "Text",
    EditorTool.IMAGE to "Image",
    EditorTool.ELEMENT to "Elements",
    EditorTool.LASER to "Laser",
)

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
// ⚠️ A **Column**, and it is not decoration. This composable is called from an
// `AnimatedVisibility`, whose content slot lays its children out in a Box — so the two rows below
// were drawn *on top of each other*, "1 selected" over "1 shape" and every button over another
// button. Compiled, ran, and was unreadable; a screenshot is what found it, which is the second
// time in this milestone that looking at the screen beat reading the code.
@Composable
private fun SelectionRow(state: InkUiState, actions: InkToolbarActions) = Column {
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
    ShapeRow(state, actions)
}

/**
 * ⭐ ROADMAP M8b — fill, rounded corners and a connector's route.
 *
 * ## Why this row appears and disappears
 *
 * It is shown only when the selection holds a shape, and the route chip only when one of them is a
 * connector. A *"Fill"* button over a selection of pen strokes is a button that does nothing when
 * pressed — the dishonest-button problem `UndoStack.commit` fixes one layer down, and the same
 * answer applies here: do not offer what cannot happen.
 *
 * ## Why these are toggles and not a property panel
 *
 * A fill picker, an opacity slider and a radius slider are what this wants to become, and none of
 * them is in ROADMAP M8b's scope list — which asks for fill, opacity and rounded corners to
 * *exist*, not for a panel to set them from (CLAUDE.md: *"don't gold-plate"*). Fill takes the pen's
 * current colour, which is the colour the user most recently chose and is already on screen beside
 * this row; the opacity is fixed at [FILL_OPACITY] so that a filled box does not hide the ink drawn
 * over it. Both are one tap and both undo.
 */
@Composable
private fun ShapeRow(state: InkUiState, actions: InkToolbarActions) {
    if (state.selectedShapes == 0) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${state.selectedShapes} shape", style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { actions.onFillSelection(state.pen.colorArgb, FILL_OPACITY) }) { Text("Fill") }
        TextButton(onClick = { actions.onFillSelection(null, 1f) }) { Text("No fill") }
        TextButton(onClick = { actions.onRoundSelection(CORNER_RADIUS_PT) }) { Text("Round") }
        TextButton(onClick = { actions.onRoundSelection(0f) }) { Text("Square") }
        if (state.selectedConnectors > 0) {
            ConnectorRoute.entries.forEach { route ->
                TextButton(onClick = { actions.onRouteSelection(route) }) {
                    Text(route.name.lowercase().replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}

/** Translucent enough that ink drawn over a filled box still reads. Not user-settable — see [ShapeRow]. */
private const val FILL_OPACITY = 0.35f

/** One tap's worth of rounding — a visible radius at any page size, and not a pill. */
private const val CORNER_RADIUS_PT = 8f

/** Brushes, eraser, erase mode, colour swatch and width. */
@Composable
private fun ToolRow(state: InkUiState, actions: InkToolbarActions, onToggleColorPanel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        InkBrushKind.entries.forEach { kind ->
            FilterChip(
                selected = state.tool == EditorTool.PEN && state.kind == kind,
                onClick = { state.selectBrush(kind) },
                label = { Text(kind.label) },
            )
        }
        FilterChip(
            selected = state.tool == EditorTool.ERASER,
            onClick = { state.tool = if (state.tool == EditorTool.ERASER) EditorTool.PEN else EditorTool.ERASER },
            label = { Text("Eraser") },
        )
        // ⭐ ROADMAP M8a. Leaving the tool clears the selection, because a box round objects
        // the toolbar no longer offers actions for is chrome that lies.
        FilterChip(
            selected = state.tool == EditorTool.SELECT,
            onClick = {
                state.tool = if (state.tool == EditorTool.SELECT) EditorTool.PEN else EditorTool.SELECT
                if (state.tool != EditorTool.SELECT) actions.onClearSelection()
            },
            label = { Text("Select") },
        )
        // The rectangular half of M8a's "freehand AND rectangular" lasso. A mode, not a tool:
        // enabled only while Select is, the way Arrow is enabled only with Hold-to-snap.
        FilterChip(
            selected = state.tool == EditorTool.SELECT && state.marqueeSelection,
            onClick = { state.marqueeSelection = !state.marqueeSelection },
            enabled = state.tool == EditorTool.SELECT,
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
