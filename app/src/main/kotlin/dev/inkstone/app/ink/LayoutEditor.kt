package dev.inkstone.app.ink

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.inkstone.app.ui.GlassPanel
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.Readout
import dev.inkstone.app.ui.SlimSlider
import dev.inkstone.app.ui.TextChip
import kotlin.math.roundToInt

/**
 * ⭐ The layout editor's own controls: the overall size, a size for each panel and contextual bar,
 * *Reset* and *Done*.
 *
 * Everything else the editor does happens on the bars themselves (`ChromeBarFrame`, `ChromeContainer`) —
 * a bar is moved by its handle, reshaped by its arrange key and resized by its grip where it is, and a
 * chip is carried to wherever it should go, because *"where should this go"* is a question you answer
 * by looking at the page. The panels are the exception: they are not on screen while the editor is,
 * so their sizes are here.
 *
 * - **It can be moved.** Drag the caption. It opens in the middle, which is exactly where a user
 *   rearranging bars may want to put one — and a panel that cannot be moved is a part of the screen
 *   no chip can be dropped on.
 * - ⚠️ **It is drawn at the screen's own size, never at [ChromeLayout.uiScale].** It holds the slider
 *   that sets that scale, and a slider that grows under the finger dragging it maps the same finger
 *   position to a different value every frame — it would chase itself.
 * - ⚠️ Back closes the editor rather than the note: the editor is a mode of this screen, and a user
 *   pressing Back in it means *"stop arranging"*, not *"leave"*.
 */
@Composable
internal fun BoxScope.LayoutEditorPanel(ui: InkUiState) {
    BackHandler { ui.editingLayout = false }
    var moved by remember { mutableStateOf(Offset.Zero) }
    GlassPanel(
        modifier = Modifier
            .align(Alignment.Center)
            .offset { IntOffset(moved.x.roundToInt(), moved.y.roundToInt()) },
    ) {
        MicroLabel(
            "Customise layout",
            color = Ink.TextNormal,
            modifier = Modifier
                .width(CaptionWidth)
                .semantics { contentDescription = "Move the layout panel" }
                .pointerInput(Unit) {
                    detectDragGestures { change, delta ->
                        change.consume()
                        moved += delta
                    }
                },
        )
        ScaleRow("UI size", ui.layout.uiScale, ChromeLayout.UI_SCALE) { ui.layout = ui.layout.withUiScale(it) }
        ChromePanel.entries.forEach { panel ->
            val own = ui.layout.panelScales[panel] ?: 1f
            ScaleRow(panel.label, own, ChromeLayout.PANEL_SCALE) { scale ->
                ui.layout = ui.layout.withPanelScale(panel, scale)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap)) {
            TextChip(label = "Reset", enabled = ui.layout != ChromeLayout(), onClick = { ui.layout = ChromeLayout() })
            TextChip(label = "Done", selected = true, onClick = { ui.editingLayout = false })
        }
    }
}

@Composable
private fun ScaleRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
    ) {
        MicroLabel(label, modifier = Modifier.width(LabelWidth))
        SlimSlider(
            value = value,
            valueRange = range,
            // Snapped to whole percent, so the readout and the stored value agree to the digit.
            onValueChange = { onChange((it * PERCENT).roundToInt() / PERCENT) },
            label = label,
            width = SliderWidth,
        )
        Readout(
            "${(value * PERCENT).roundToInt()}%",
            modifier = Modifier.width(ReadoutWidth),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * Dims the page and takes every touch on it while the editor is open.
 *
 * Not decoration: with the bars' controls covered (`ChromeContainer`) the page is the only thing left
 * that would act on a press, and a stroke drawn by a hand reaching for a handle is ink the user did
 * not mean to put down. A tap here does nothing — *Done* and Back are the ways out, so a stray touch
 * cannot end the editing either. A chip *dropped* here becomes a bar of its own; that is decided by the
 * chip's own drag, which this layer never sees.
 */
@Composable
internal fun BoxScope.LayoutEditorScrim() {
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(Scrim)
            .pointerInput(Unit) { detectTapGestures { } },
    )
}

/**
 * The chip in the air: a picture of it under the finger, and a sky line where it will land.
 *
 * Drawn by the screen's root, last, above every bar — the chip is carried between bars, and a picture
 * parented to either one would be clipped at its edge halfway across. No line means the page: letting
 * go there makes the chip a bar of its own.
 *
 * Both are placed in the layout phase (`offset { }`), so a frame of the drag moves two boxes and
 * recomposes nothing.
 */
@Composable
internal fun BoxScope.ItemDragGhost(drag: ItemDrag, origin: Offset) {
    val item = drag.item ?: return
    val density = LocalDensity.current
    (drag.target as? DropTarget.Into)?.caret?.let { caret ->
        Box(
            modifier = Modifier
                .offset { IntOffset((caret.left - origin.x).roundToInt(), (caret.top - origin.y).roundToInt()) }
                .size(with(density) { caret.width.toDp() }, with(density) { caret.height.toDp() })
                .background(Ink.Focus, RoundedCornerShape(CaretCorner)),
        )
    }
    Box(
        modifier = Modifier
            .offset {
                val at = drag.pointer - drag.grab - origin
                IntOffset(at.x.roundToInt(), at.y.roundToInt())
            }
            .size(with(density) { drag.size.width.toDp() }, with(density) { drag.size.height.toDp() })
            .background(Ink.PanelRaised, InkDims.ChipCorner)
            .border(GhostOutline, Ink.Focus, InkDims.ChipCorner),
        contentAlignment = Alignment.Center,
    ) { MicroLabel(item.label, color = Ink.TextStrong) }
}

private val Scrim = Color(0x8C000000)
private val LabelWidth = 112.dp
private val CaptionWidth = 320.dp
private val SliderWidth = 180.dp
private val ReadoutWidth = 48.dp
private val CaretCorner = 2.dp
private val GhostOutline = 1.5.dp
private const val PERCENT = 100f
