package dev.inkstone.app.ink

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import dev.inkstone.app.ui.ChipDivider
import dev.inkstone.app.ui.GlassBar
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.TextChip

/**
 * ⭐ ROADMAP M10 — **the Dynamic Dock**, the design's primary control hub.
 *
 * Two stacked pills at the top centre of the canvas:
 *
 * 1. **Primary modes**, mutually exclusive, always present. What the pen *is*.
 * 2. **Secondary tools**, which swap wholesale with the mode above and *collapse entirely* when the
 *    mode has nothing to offer — the design's progressive disclosure, and the reason the canvas is
 *    not permanently 120 dp shorter than the screen.
 *
 * ## Why this replaced `InkToolbar`
 *
 * That card was M1's deliberate placeholder — ROADMAP M1: *"minimal floating toolbar. **Don't
 * design the toolbar here** — M10 owns it."* — and by M12 it had accreted eight rows: brushes,
 * object tools, image controls, text format, ruler, undo/zoom, snap, selection. Every one was
 * present at all times, so the note editor opened with a third of the page under chrome and the
 * one row that mattered was wherever the user's eye happened to land.
 *
 * ## ⚠️ The ruler is in row one and is **not** in the exclusive group
 *
 * The design lists it beside the tools; `EditorTool` explains why it cannot be one of them — the pen
 * keeps drawing while the edge is out, and making it a tool would make *"the ruler is out"* and
 * *"I am drawing"* mutually exclusive, which is the opposite of what a ruler is for. So it sits
 * after a divider, as a toggle, and contributes its own controls to row two.
 *
 * ⚠️ **Laser is an eighth chip the design did not list**, and it is here rather than dropped because
 * `EditorTool.LASER` exists and is reachable from nowhere else. A tool that is present in the enum,
 * wired through the surface, and unreachable in the app is CLAUDE.md's definition-of-done #6
 * exactly — the failure this repo has shipped six times.
 *
 * @param secondarySuppressed true while a contextual toolbar is on screen. The design: *"Tapping any
 *   drawn object … summons its respective Contextual Toolbar and hides the Dynamic Dock's secondary
 *   row to maintain focus."*
 */
@Composable
fun EditorDock(
    state: InkUiState,
    objects: ObjectToolState,
    actions: InkToolbarActions,
    objectActions: ObjectActions,
    onSummon: (PanelKind, Rect) -> Unit,
    modifier: Modifier = Modifier,
    secondarySuppressed: Boolean = false,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DockRowGap),
    ) {
        PrimaryRow(state, objects, objectActions)

        val secondary = secondaryFor(state)
        // ⚠️ The ruler contributes to row two **on top of** whatever the tool contributes, rather
        // than replacing it — it is a mode, so "the ruler is out" and "I am drawing" are both true
        // and both need their controls. That is also why the visibility test is an `or`: with the
        // Text tool the tool half is empty and the ruler half is the only reason the pill exists.
        AnimatedVisibility(
            visible = (secondary != SecondaryRow.NONE || state.rulerEnabled) && !secondarySuppressed,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            GlassBar {
                SecondaryContent(
                    row = secondary,
                    state = state,
                    objects = objects,
                    actions = actions,
                    objectActions = objectActions,
                    onSummon = onSummon,
                )
                if (state.rulerEnabled) {
                    if (secondary != SecondaryRow.NONE) ChipDivider()
                    RulerControls(state, objectActions)
                }
            }
        }
    }
}

/**
 * Row 1 — the mutually exclusive modes, grouped by what they do to the page.
 *
 * ⚠️ Switching mode **resets whatever the previous one was holding**, exactly as the old chips did:
 * a text field floating over a page whose tool is now the eraser, or crop handles round a picture
 * nothing can crop, is chrome that lies about what a press will do.
 */
@Composable
private fun PrimaryRow(state: InkUiState, objects: ObjectToolState, objectActions: ObjectActions) {
    GlassBar {
        PRIMARY_TOOLS.forEachIndexed { index, entry ->
            if (index > 0 && entry.startsGroup) ChipDivider()
            TextChip(
                label = entry.label,
                selected = state.tool == entry.tool,
                onClick = {
                    if (state.tool != entry.tool) objects.reset()
                    // ⚠️ Through `selectBrush` for the pen, so the brush the user last held comes
                    // back rather than a fixed one — the same restoration `onSelectTappedAway` does.
                    if (entry.tool == EditorTool.PEN) state.selectBrush(state.kind) else state.tool = entry.tool
                },
            )
        }
        ChipDivider()
        TextChip(
            label = "Ruler",
            selected = state.rulerEnabled,
            onClick = { objectActions.onRulerEnabled(!state.rulerEnabled) },
        )
    }
}

/**
 * Which secondary row belongs to the current mode.
 *
 * A value rather than a chain of `AnimatedVisibility`s, because the design's row two *swaps* — it
 * does not stack. Stacking is what the old toolbar did, and it is why every row was on screen at
 * once. [SecondaryRow.NONE] is a real answer and collapses the pill.
 */
private fun secondaryFor(state: InkUiState): SecondaryRow = when (state.tool) {
    EditorTool.PEN -> SecondaryRow.DRAW

    EditorTool.ERASER -> SecondaryRow.ERASE

    EditorTool.SELECT -> SecondaryRow.SELECT

    EditorTool.IMAGE -> SecondaryRow.IMAGE

    EditorTool.ELEMENT -> SecondaryRow.ELEMENT

    // TEXT and LASER have no settings of their own — the design names TEXT as the case row two
    // collapses for, and a laser is a laser. Text *formatting* is a contextual toolbar over the
    // box being typed in, which is where the design puts it and where the box actually is.
    EditorTool.TEXT, EditorTool.LASER -> SecondaryRow.NONE
}

internal enum class SecondaryRow { NONE, DRAW, ERASE, SELECT, IMAGE, ELEMENT }

/** One primary chip: the tool it selects, its word, and whether a rule precedes it. */
private class PrimaryTool(val tool: EditorTool, val label: String, val startsGroup: Boolean = false)

/**
 * The design's row one, in its order: what the pen does, then what a selection does, then what
 * places an object.
 */
private val PRIMARY_TOOLS = listOf(
    PrimaryTool(EditorTool.PEN, "Draw"),
    PrimaryTool(EditorTool.ERASER, "Erase"),
    PrimaryTool(EditorTool.LASER, "Laser"),
    PrimaryTool(EditorTool.SELECT, "Select", startsGroup = true),
    PrimaryTool(EditorTool.TEXT, "Text", startsGroup = true),
    PrimaryTool(EditorTool.IMAGE, "Image"),
    PrimaryTool(EditorTool.ELEMENT, "Elements"),
)

/** `gap-2` between the dock's two pills. */
private val DockRowGap = 8.dp

/** Where the dock sits, as one value so the contextual bars can avoid it. See `ContextBars`. */
internal val DockTopPadding = InkDims.EdgePadding
