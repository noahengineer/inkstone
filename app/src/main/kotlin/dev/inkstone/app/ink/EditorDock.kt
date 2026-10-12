package dev.inkstone.app.ink

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.inkstone.app.ui.ChipDivider
import dev.inkstone.app.ui.GlassBar
import dev.inkstone.app.ui.InkDims

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
 * ## ⭐ The dock is two bars now, and neither knows which chips it holds
 *
 * Row one is the `DOCK` bar and row two's Draw controls are the `DRAW` bar — both drawn by
 * `ChromeContainer` from whatever chips the user has put in them (`ChromeLayout`), so a tool can be
 * moved into the page bar and `Undo` into the dock. `ChromeHost` places row two against row one:
 * below it, above it once the dock is in the lower half of the screen, beside it once the dock is a
 * column. In the stock slot that is the old `Column` exactly — centred, [DockRowGap] below.
 */
@Composable
internal fun DockSecondary(deps: ChromeDeps, suppressed: Boolean) {
    val state = deps.ui
    val secondary = secondaryFor(state)
    val drawBar = BarId.Stock(ChromeBar.DRAW)
    // ⭐ A Draw row with nothing left in it is no row at all — an empty pill under the dock would be
    // chrome with nothing in it. Never in the editor, which is where it gets things put back in.
    val empty = secondary == SecondaryRow.DRAW && deps.items(drawBar).isEmpty()
    val row = if (empty) SecondaryRow.NONE else secondary
    // ⚠️ The ruler contributes to row two **on top of** whatever the tool contributes, rather
    // than replacing it — it is a mode, so "the ruler is out" and "I am drawing" are both true
    // and both need their controls. That is also why the visibility test is an `or`: with the
    // Text tool the tool half is empty and the ruler half is the only reason the pill exists.
    AnimatedVisibility(
        visible = (row != SecondaryRow.NONE || state.rulerEnabled) && !suppressed,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        if (row == SecondaryRow.DRAW) {
            val ruler: (@Composable () -> Unit)? = if (state.rulerEnabled && !state.editingLayout) {
                { RulerControls(state, deps.objectActions) }
            } else {
                null
            }
            ChromeContainer(drawBar, deps, trailing = ruler)
        } else {
            GlassBar {
                SecondaryContent(row, state, deps.objects, deps.actions, deps.objectActions)
                if (state.rulerEnabled) {
                    if (row != SecondaryRow.NONE) ChipDivider()
                    RulerControls(state, deps.objectActions)
                }
            }
        }
    }
}

/**
 * Which secondary row belongs to the current mode.
 *
 * A value rather than a chain of `AnimatedVisibility`s, because the design's row two *swaps* — it
 * does not stack. Stacking is what the old toolbar did, and it is why every row was on screen at
 * once. [SecondaryRow.NONE] is a real answer and collapses the pill.
 *
 * ⭐ In the layout editor it is always the Draw row: that is the one row two made of chips, and it has
 * to be on screen to be arranged — or dropped into — whatever tool was in hand.
 */
private fun secondaryFor(state: InkUiState): SecondaryRow = when {
    state.editingLayout -> SecondaryRow.DRAW

    state.tool == EditorTool.PEN -> SecondaryRow.DRAW

    state.tool == EditorTool.ERASER -> SecondaryRow.ERASE

    state.tool == EditorTool.SELECT -> SecondaryRow.SELECT

    state.tool == EditorTool.IMAGE -> SecondaryRow.IMAGE

    state.tool == EditorTool.ELEMENT -> SecondaryRow.ELEMENT

    // TEXT and LASER have no settings of their own — the design names TEXT as the case row two
    // collapses for, and a laser is a laser. Text *formatting* is a contextual toolbar over the
    // box being typed in, which is where the design puts it and where the box actually is.
    else -> SecondaryRow.NONE
}

internal enum class SecondaryRow { NONE, DRAW, ERASE, SELECT, IMAGE, ELEMENT }

/**
 * The tool a row-one chip selects, or null for the ruler — which is a toggle beside the tools and not
 * one of them (see the class KDoc). The order and the dividers are [ChromeItem]'s.
 */
internal val ChromeItem.tool: EditorTool?
    get() = when (this) {
        ChromeItem.TOOL_DRAW -> EditorTool.PEN
        ChromeItem.TOOL_ERASE -> EditorTool.ERASER
        ChromeItem.TOOL_LASER -> EditorTool.LASER
        ChromeItem.TOOL_SELECT -> EditorTool.SELECT
        ChromeItem.TOOL_TEXT -> EditorTool.TEXT
        ChromeItem.TOOL_IMAGE -> EditorTool.IMAGE
        ChromeItem.TOOL_ELEMENTS -> EditorTool.ELEMENT
        else -> null
    }

/** `gap-2` between the dock's two pills. `internal` so the calculator window can clear row two. */
internal val DockRowGap = 8.dp

/** Where the dock sits, as one value so the contextual bars can avoid it. See `ContextBars`. */
internal val DockTopPadding = InkDims.EdgePadding
