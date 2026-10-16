package dev.inkstone.app.ink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inkstone.android.ink.InkInputModel
import dev.inkstone.app.ui.ChipKind
import dev.inkstone.app.ui.GlassPanel
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.Readout
import dev.inkstone.app.ui.TextChip
import dev.inkstone.core.model.FitMode
import kotlin.math.roundToInt

/**
 * ⭐ ROADMAP M10 — the design's *"Bottom Left (History)"* zone, one chip at a time.
 *
 * > *"Separated from the main tools to prevent accidental taps during drawing."*
 *
 * Also two-finger tap and three-finger tap on the page. The chips exist so the gesture is
 * discoverable and so it is testable from `adb`, which is CLAUDE.md's definition-of-done #6 check
 * that catches an inert feature when a green suite does not. Both can be hidden — the gestures still
 * undo and redo — and then their bar is gone rather than an empty pill in the corner.
 */
@Composable
internal fun HistoryChip(item: ChromeItem, state: InkUiState, actions: InkToolbarActions) {
    if (item == ChromeItem.UNDO) {
        TextChip(label = "Undo", enabled = state.canUndo, onClick = actions.onUndo)
    } else {
        TextChip(label = "Redo", enabled = state.canRedo, onClick = actions.onRedo)
    }
}

/**
 * ⭐ ROADMAP M10 — the design's *"Bottom Right (Zoom)"* zone, one chip at a time: the level,
 * `FIT WIDTH`, `FIT PAGE`, `100%` and `⋯`.
 *
 * ⚠️ **The `⋯` chip is not decoration and cannot be hidden.** It is the only route to SPEC §3.6's
 * measurement controls, which is the only route to the frame-rate gate's readout on the one device
 * the gate is actually about: `dumpsys gfxinfo` needs adb, adb needs USB debugging authorised, and
 * on the physical Galaxy Tab that CLAUDE.md's definition of done #4 is about that is routinely not
 * granted and cannot be granted from the desktop. It is also the way into the layout editor, so
 * hiding it would hide the only way to show it again (`ChromeItem.hideable`). It can be *moved*.
 */
@Composable
internal fun ZoomChip(
    item: ChromeItem,
    state: InkUiState,
    actions: InkToolbarActions,
    diagnosticsOpen: Boolean,
    onToggleDiagnostics: () -> Unit,
) {
    when (item) {
        // ⚠️ Centred. The width is fixed so the bar does not re-flow between "9%" and "100%", and
        // left-aligned inside it the slack read as a random gap before the divider.
        ChromeItem.ZOOM_READOUT -> Readout(
            "${state.zoomPercent.roundToInt()}%",
            modifier = Modifier.width(ZoomReadout),
            textAlign = TextAlign.Center,
        )

        ChromeItem.FIT_WIDTH -> TextChip(label = "Fit width", onClick = { actions.onFit(FitMode.FIT_WIDTH) })

        ChromeItem.FIT_PAGE -> TextChip(label = "Fit page", onClick = { actions.onFit(FitMode.FIT_PAGE) })

        ChromeItem.ACTUAL_SIZE ->
            TextChip(label = "100%", kind = ChipKind.VALUE, onClick = { actions.onFit(FitMode.ACTUAL_SIZE) })

        else -> TextChip(
            label = "⋯",
            kind = ChipKind.GLYPH,
            selected = diagnosticsOpen,
            onClick = onToggleDiagnostics,
            contentDescription = "Diagnostics",
        )
    }
}

/**
 * SPEC §3.6's controls and readouts: the spike A/B switch, the load generator, the numbers.
 *
 * Behind a chip rather than in the dock, because none of it is a drawing tool — it is the
 * instrumentation the milestone gates are measured with, and putting it on the glass permanently is
 * what made the old toolbar eight rows tall.
 */
@Composable
fun DiagnosticsPanel(
    state: InkUiState,
    actions: InkToolbarActions,
    revision: Long,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Collected only while this panel is on screen — [FrameStats.attach] costs a thread.
    val context = LocalContext.current
    DisposableEffect(Unit) {
        state.frameStats.attach(context)
        onDispose { state.frameStats.detach() }
    }

    GlassPanel(modifier = modifier.width(PanelWidth)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.width(PanelWidth - PanelInset),
        ) {
            MicroLabel("Diagnostics", color = Ink.TextNormal)
            TextChip(label = "✕", kind = ChipKind.GLYPH, onClick = onClose, contentDescription = "Close")
        }

        // ⭐ The layout editor's front door, behind the one chip that can never be hidden. A long
        // press on any bar's background opens it too; this is the route that needs no discovering.
        TextChip(
            label = "Customise layout",
            onClick = {
                onClose()
                state.editingLayout = true
            },
        )

        // Headed, because three rows of unlabelled chips — one of which (`CLEAR`) empties the page —
        // was the editor's share of *"random buttons everywhere"*. `FINGER` and `SPRING` are also on
        // the library's Settings screen; both write the same `EditorPrefs`.
        MicroLabel("Pen input")
        InputSwitches(state)
        MicroLabel("Measurement")
        LoadAndReset(state, actions)

        // ⚠️ `Readout`, not `MicroLabel`: these lines are *measurements*, and MicroLabel uppercases.
        // "16.7 MS (DRAW 4.1 · ISSUE 12.6)" is a number nobody reads at a glance, which defeats the
        // point of a readout that exists so a gate is not scored on whether it felt smooth.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DiagnosticLine(frameLine(state))
            DiagnosticLine(latencyLine(state))
            DiagnosticLine(pressureLine(state))
            DiagnosticLine(
                "page ${state.strokeCount} objects · ${state.erasedCount} erased" +
                    if (state.hasHighlighter) " · highlighter" else "",
            )
            DiagnosticLine("dry layer ${state.dryLayer}")
            // ⭐ The autosave heartbeat, moved here from the page bar. `local_rev` is bumped on
            // every committed change (SPEC §7.6), so a number that stops moving while you write is
            // a bug you can see — which makes it instrumentation, and instrumentation lives behind
            // the `⋯` with the rest of it rather than taking 60 dp of a bar that did not have it.
            DiagnosticLine("revision r$revision")
            state.busyMessage?.let { DiagnosticLine(it, Ink.Focus) }
        }
    }
}

/** SPEC Appendix B #2's A/B switch, SPEC §3.4's input model, and SPEC §3.3's palm rejection. */
@Composable
private fun InputSwitches(state: InkUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(ChipGap)) {
        TextChip(
            label = "Predict",
            selected = state.predictionEnabled,
            onClick = { state.predictionEnabled = !state.predictionEnabled },
        )
        TextChip(
            label = "Spring",
            selected = state.inputModel == InkInputModel.SPRING,
            onClick = {
                state.inputModel =
                    if (state.inputModel == InkInputModel.SPRING) InkInputModel.DIRECT else InkInputModel.SPRING
            },
        )
        TextChip(
            label = "Finger",
            selected = state.fingerDrawingEnabled,
            onClick = { state.fingerDrawingEnabled = !state.fingerDrawingEnabled },
        )
    }
}

/** SPEC §3.6's load generator, and the two counters it is scored against. */
@Composable
private fun LoadAndReset(state: InkUiState, actions: InkToolbarActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(ChipGap)) {
        TextChip(label = "Load 5k", onClick = { actions.onLoadSyntheticPage(GATE_STROKE_COUNT) })
        TextChip(label = "Clear", danger = true, onClick = actions.onClear)
        TextChip(
            label = "Reset",
            onClick = {
                actions.onResetLatency()
                state.frameStats.reset()
            },
        )
    }
}

@Composable
private fun DiagnosticLine(text: String, color: androidx.compose.ui.graphics.Color = Ink.TextNormal) {
    Readout(text = text, color = color, fontSize = DiagnosticSize, maxLines = DIAGNOSTIC_LINES)
}

/**
 * SPEC §3.6's frame-rate half, on the glass.
 *
 * ⚠️ **Read `draw` and `issue` together — that pair is the whole diagnostic.** `draw` is the UI
 * thread recording a display list; `issue` is the RenderThread submitting it to the GPU. Low draw
 * with high issue is thousands of per-stroke draw calls, which a rasterised cache removes. Both low
 * against a large total is pixel shading or a stalled swap, which it does not.
 */
private fun frameLine(state: InkUiState): String {
    val frames = state.frames
    if (frames.frameCount == 0L) return "frames — pan or draw"
    return "%.0f fps · %.1f ms (draw %.1f · issue %.1f) · worst %.1f · %d/%d over %.0fHz".format(
        frames.fps,
        frames.meanMillis,
        frames.meanDrawMillis,
        frames.meanIssueMillis,
        frames.worstMillis,
        frames.jankyCount,
        frames.frameCount,
        frames.refreshHz,
    )
}

private fun latencyLine(state: InkUiState): String {
    val latency = state.latency
    if (latency.sampleCount == 0L) return "latency — draw a stroke"
    return "latency mean %.1f ms · worst %.1f ms · %d samples".format(
        latency.meanMillis,
        latency.worstMillis,
        latency.sampleCount,
    )
}

/**
 * What the digitiser reports for pressure — the readout that answers *"why doesn't the pen react to
 * how hard I press?"*
 *
 * A stylus with real pressure spans a wide band within one written word. One that reports a constant
 * shows the same number for min and max however hard you press, and no brush tuning will ever help
 * it. Deliberately blunt wording: this is a diagnostic, not a feature.
 */
private fun pressureLine(state: InkUiState): String {
    val stylus = state.stylus
    return when {
        stylus.sampleCount == 0L -> "pressure — draw a stroke"

        stylus.varies -> "pressure %.2f (%.2f–%.2f) · %s%s ✓".format(
            stylus.lastPressure,
            stylus.minPressure,
            stylus.maxPressure,
            stylus.toolName,
            if (stylus.reportsTilt) " · tilt" else " · no tilt",
        )

        else -> "pressure FLAT at %.2f over %d · %s reports none".format(
            stylus.lastPressure,
            stylus.sampleCount,
            stylus.toolName,
        )
    }
}

private val ZoomReadout = 54.dp
private val PanelWidth = 380.dp
private val PanelInset = 28.dp
private val DiagnosticSize = 11.sp
private val ChipGap = 4.dp

private const val DIAGNOSTIC_LINES = 2
private const val GATE_STROKE_COUNT = 5000
