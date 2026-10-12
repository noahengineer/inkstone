package dev.inkstone.app.ink

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Rect
import dev.inkstone.app.note.NoteEditorState
import dev.inkstone.app.note.PageBarActions
import dev.inkstone.app.note.PageChip
import dev.inkstone.app.ui.TextChip

/**
 * ⭐ Any chip, drawn in any bar.
 *
 * Until the user could move a chip *between* bars, each bar drew its own chips with whatever it had
 * to hand — the page bar knew the note, the dock knew the tools. A chip that can be dropped into any
 * bar, or onto the page as a bar of its own, has to be drawable from anywhere, so every chip is drawn
 * here from one bundle of everything any of them needs ([ChromeDeps]), and a bar is only a container
 * (`ChromeContainer`) that lays out whichever chips it holds.
 *
 * Each chip still behaves exactly as it did in its home bar: `Fit width` in the dock fits the width,
 * `Delete` in a floating bar of its own deletes the page. Where a chip is says nothing about what it
 * does — that is what lets it go anywhere.
 */
@Composable
internal fun ChromeItemContent(item: ChromeItem, deps: ChromeDeps) {
    val ui = deps.ui
    when (item.home) {
        ChromeBar.DOCK -> ToolChip(item, deps)
        ChromeBar.DRAW -> DrawChip(item, ui, deps.wiring.onSummon)
        ChromeBar.HISTORY -> HistoryChip(item, ui, deps.actions)
        ChromeBar.ZOOM -> ZoomChip(item, ui, deps.actions, deps.wiring.diagnosticsOpen, deps.wiring.onToggleDiagnostics)
        ChromeBar.PAGES -> PageChip(item, deps.wiring.note, deps.wiring.pageActions, deps.wiring.exporting)
        ChromeBar.RECORD, ChromeBar.CALCULATOR -> Unit
    }
}

/**
 * Row one's chips — the mutually exclusive modes, and the ruler toggle beside them.
 *
 * ⚠️ Switching mode **resets whatever the previous one was holding**: a text field floating over a
 * page whose tool is now the eraser, or crop handles round a picture nothing can crop, is chrome that
 * lies about what a press will do.
 */
@Composable
private fun ToolChip(item: ChromeItem, deps: ChromeDeps) {
    val state = deps.ui
    val tool = item.tool
    if (tool == null) {
        TextChip(
            label = item.label,
            selected = state.rulerEnabled,
            onClick = { deps.objectActions.onRulerEnabled(!state.rulerEnabled) },
        )
    } else {
        TextChip(
            label = item.label,
            selected = state.tool == tool,
            onClick = {
                if (state.tool != tool) deps.objects.reset()
                // ⚠️ Through `selectBrush` for the pen, so the brush the user last held comes back
                // rather than a fixed one — the same restoration `onSelectTappedAway` does.
                if (tool == EditorTool.PEN) state.selectBrush(state.kind) else state.tool = tool
            },
        )
    }
}

/**
 * Everything any chip needs, as one value — so a bar can draw a chip it was never designed to hold.
 *
 * @param geometry where the bars are, for a chip dropped on the page to become a bar there.
 * @param elementEditor true in the Element editor, which has no pages: a page chip the user moved into
 *   another bar is left out there, rather than offering *Add page* on an Element.
 */
@Stable
internal class ChromeDeps(
    val ui: InkUiState,
    val objects: ObjectToolState,
    val actions: InkToolbarActions,
    val objectActions: ObjectActions,
    val wiring: ChromeWiring,
    val geometry: ChromeGeometry,
    val elementEditor: Boolean,
) {
    /** [id]'s chips as this screen draws them: the Element editor leaves out the page chips. */
    fun items(id: BarId): List<ChromeItem> {
        val all = if (ui.editingLayout) ui.layout.itemsOf(id) else ui.layout.visibleItemsOf(id)
        return if (elementEditor) all.filter { it.home != ChromeBar.PAGES } else all
    }
}

/** What the chrome needs from `InkScreen` besides the shared state — one value, for `LongParameterList`. */
@Immutable
internal class ChromeWiring(
    val onSummon: (PanelKind, Rect) -> Unit,
    val note: NoteEditorState,
    val pageActions: PageBarActions,
    val exporting: Boolean,
    val diagnosticsOpen: Boolean,
    val onToggleDiagnostics: () -> Unit,
)
