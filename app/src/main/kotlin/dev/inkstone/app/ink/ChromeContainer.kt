package dev.inkstone.app.ink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.inkstone.app.ui.CardLine
import dev.inkstone.app.ui.CardRule
import dev.inkstone.app.ui.ChipDivider
import dev.inkstone.app.ui.ChipDividerAcross
import dev.inkstone.app.ui.ChipGrid
import dev.inkstone.app.ui.GlassBar
import dev.inkstone.app.ui.GlassBlock
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.Segmented
import dev.inkstone.app.ui.SegmentedGrid
import dev.inkstone.app.ui.SegmentedLine
import dev.inkstone.app.ui.TextChip

/**
 * ⭐ A bar: whichever chips the user has put in it, laid out the way they chose — a row (the design's
 * pill), a column, a panel (a line per group), or a grid.
 *
 * The bar does not know what its chips are. It asks the layout which chips it holds
 * ([ChromeDeps.items]) and draws each with [ChromeItemContent], so the same container is the dock, the
 * page bar, the zoom bar and every bar the user makes by dropping a chip on the page.
 *
 * - **Dividers** fall wherever [ChromeItem.group] changes, in a row and down a column; a grid has none,
 *   because its cells have to line up and a divider would take one.
 * - **Brushes** sit in a recessed track. In a row, a run of them shares one — the design's segmented
 *   control; anywhere else each has its own.
 * - **In the layout editor** every chip is drawn, hidden ones faint, under a cover that takes every
 *   touch ([EditableItem]); an empty bar is drawn as a labelled space so chips can be dropped back in.
 *
 * ⚠️ **Every chip is under a [key].** A chip's own state — the width readout remembers where it is so
 * its panel opens beneath it — would otherwise slide to its neighbour when a chip before it is hidden.
 *
 * @param trailing drawn after the chips and not one of them — the ruler's controls, which belong to
 *   the pen's row while the ruler is out and are not something a user rearranges.
 */
@Composable
internal fun ChromeContainer(id: BarId, deps: ChromeDeps, trailing: (@Composable () -> Unit)? = null) {
    val ui = deps.ui
    val editing = ui.editingLayout
    val items = deps.items(id)
    if (items.isEmpty() && !editing && trailing == null) return

    val token = remember { Any() }
    val drag = ui.itemDrag
    if (editing) DisposableEffect(id) { onDispose { drag.forgetBar(id, token) } }
    val report = if (editing) {
        Modifier.onGloballyPositioned { drag.reportBar(id, token, Rect(it.positionInRoot(), it.size.toSize())) }
    } else {
        Modifier
    }

    val arrangement = ui.layout.arrangement(id)
    CompositionLocalProvider(LocalBarArrangement provides arrangement) {
        when (arrangement) {
            ChromeArrangement.ROW -> GlassBar(modifier = report) {
                if (items.isEmpty()) EmptyBar(id) else RowItems(items, deps)
                trailing?.let { more ->
                    if (items.isNotEmpty()) ChipDivider()
                    more()
                }
            }

            ChromeArrangement.PANEL -> GlassBlock(modifier = report) { PanelBody(id, items, deps, trailing) }

            else -> GlassBlock(modifier = report) { GridBody(id, items, arrangement, deps, trailing) }
        }
    }
}

/** A [ChromeArrangement.PANEL] bar's contents, with [trailing] as a line of its own under them. */
@Composable
private fun PanelBody(id: BarId, items: List<ChromeItem>, deps: ChromeDeps, trailing: (@Composable () -> Unit)?) {
    // ⚠️ `IntrinsicSize.Max`, so the rules and the weighted lines fill the *panel's* width — the widest
    // line's — and not the screen's, which is what `fillMaxWidth` would reach for inside a column that
    // wraps its content.
    Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(InkDims.ChipGap)) {
        if (items.isEmpty()) EmptyBar(id) else PanelItems(items, deps)
    }
    // Outside the measured column: the ruler's angle is a text field, and a panel's width should not be
    // decided by asking one for its intrinsic size.
    trailing?.let { more -> CardLine { more() } }
}

/** A column's or a grid's contents, with [trailing] as a line of its own under them. */
@Composable
private fun GridBody(
    id: BarId,
    items: List<ChromeItem>,
    arrangement: ChromeArrangement,
    deps: ChromeDeps,
    trailing: (@Composable () -> Unit)?,
) {
    if (items.isEmpty()) {
        EmptyBar(id)
    } else {
        ChipGrid(columns = arrangement.columns, fillCells = true) { BlockItems(items, arrangement, deps) }
    }
    trailing?.let { more -> CardLine { more() } }
}

/**
 * The shape of the bar a chip is being drawn in. A column ([ChromeArrangement.vertical]) turns the page
 * arrows to point up and down, so a column of page controls reads as *previous above, next below*; a
 * panel has the room for twice the quick colours a pill does.
 */
internal val LocalBarArrangement = compositionLocalOf { ChromeArrangement.ROW }

/**
 * A [ChromeArrangement.PANEL]: a line per group ([PanelShape]), a hairline between groups, every line
 * as wide as the panel.
 *
 * - **Brushes share one track**, two by two — one choice, so one recessed control (`SegmentedGrid`).
 * - **A line's chips share its width equally**, so `Snap / Arrow / Grid` are three equal keys and a
 *   lit one fills its third. The exception is a chip that [stretches] — the width slider — which takes
 *   what the others leave.
 */
@Composable
private fun PanelItems(items: List<ChromeItem>, deps: ChromeDeps) {
    val editing = deps.ui.editingLayout
    val lines = PanelShape.lines(items)
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (index > 0 && line.startsGroup) CardRule()
        if (!editing && line.items.all { it.brush != null }) {
            val run = listOf(line) + lines.drop(index + 1).takeWhile { next ->
                !next.startsGroup && next.items.all { it.brush != null }
            }
            key(line.items.first()) {
                SegmentedGrid(modifier = Modifier.fillMaxWidth()) {
                    run.forEach { brushes ->
                        SegmentedLine {
                            brushes.items.forEach { brush ->
                                key(brush) {
                                    Box(Modifier.weight(1f), propagateMinConstraints = true) {
                                        ChromeItemContent(brush, deps)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            index += run.size
        } else {
            PanelLineOf(line.items, deps)
            index++
        }
    }
}

/** One line of a panel: equal cells, or a stretching chip and the rest at their own width. */
@Composable
private fun PanelLineOf(items: List<ChromeItem>, deps: ChromeDeps) {
    val editing = deps.ui.editingLayout
    val stretching = items.any { it.stretches }
    CardLine(modifier = Modifier.fillMaxWidth()) {
        items.forEach { item ->
            key(item) {
                val fills = !stretching || item.stretches
                Box(
                    modifier = if (fills) Modifier.weight(1f) else Modifier,
                    contentAlignment = Alignment.Center,
                    propagateMinConstraints = fills && !editing,
                ) {
                    if (editing) EditableItem(item, deps) { Cell(item, deps) } else BlockCell(item, deps)
                }
            }
        }
    }
}

/** A chip that takes the room its line's other chips leave, rather than an equal share of it. */
private val ChromeItem.stretches: Boolean get() = this == ChromeItem.WIDTH_SLIDER

/** A row: dividers between groups, and a run of brushes in one track. */
@Composable
private fun RowItems(items: List<ChromeItem>, deps: ChromeDeps) {
    if (deps.ui.editingLayout) {
        items.forEachIndexed { index, item ->
            if (index > 0 && items[index - 1].group != item.group) ChipDivider()
            key(item) { EditableItem(item, deps) { Cell(item, deps) } }
        }
        return
    }
    var index = 0
    while (index < items.size) {
        val item = items[index]
        if (index > 0 && items[index - 1].group != item.group) ChipDivider()
        if (item.brush != null) {
            val run = items.drop(index).takeWhile { it.brush != null }
            key(item) {
                // ⭐ One chip with a menu, or — the layout's choice (docs/LAYOUTS.md) — a chip each.
                if (run.size > 1 && deps.ui.layout.brushMenu) {
                    BrushMenu(run, deps)
                } else {
                    Segmented { run.forEach { brush -> key(brush) { ChromeItemContent(brush, deps) } } }
                }
            }
            index += run.size
        } else {
            key(item) { ChromeItemContent(item, deps) }
            index++
        }
    }
}

/**
 * ⭐ A run of brushes in a row, as one chip naming the brush in hand — tap it for the others.
 *
 * Four brush names side by side were 400 dp of the pen's row, a third of a landscape tablet, for a
 * choice made once in a while. Every note app the user pointed at keeps the pen *type* one tap behind
 * the pen (Samsung Notes' arrow beside the pen, GoodNotes' pen popover) and spends the toolbar on what
 * changes every minute — colour and thickness (docs/UI-RAILS.md §1). In a column, a grid, a panel or
 * the layout editor every brush is still its own chip, so each can still be moved or hidden.
 */
@Composable
private fun BrushMenu(run: List<ChromeItem>, deps: ChromeDeps) {
    val ui = deps.ui
    var open by remember { mutableStateOf(false) }
    val inHand = run.firstOrNull { it.brush == ui.kind } ?: run.first()
    Box(propagateMinConstraints = true) {
        TextChip(label = "${inHand.label} ▾", selected = open, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            run.forEach { item ->
                DropdownMenuItem(
                    text = { MicroLabel(item.label, color = if (item == inHand) Ink.TextStrong else Ink.TextMuted) },
                    onClick = {
                        open = false
                        item.brush?.let(ui::selectBrush)
                    },
                )
            }
        }
    }
}

/** A column or a grid: one chip per cell, and dividers only down a column. */
@Composable
private fun BlockItems(items: List<ChromeItem>, arrangement: ChromeArrangement, deps: ChromeDeps) {
    val column = arrangement == ChromeArrangement.COLUMN
    items.forEachIndexed { index, item ->
        if (column && index > 0 && items[index - 1].group != item.group) ChipDividerAcross()
        key(item) {
            if (deps.ui.editingLayout) EditableItem(item, deps) { Cell(item, deps) } else BlockCell(item, deps)
        }
    }
}

/** One chip on its own — a brush in a track of its own, everything else as itself. */
@Composable
private fun Cell(item: ChromeItem, deps: ChromeDeps) {
    if (item.brush != null) Segmented { ChromeItemContent(item, deps) } else ChromeItemContent(item, deps)
}

/**
 * [Cell] in a cell of known width — a grid's or a panel's — where a lone brush's lit segment should
 * fill its track rather than hug its word.
 *
 * ⚠️ Never inside a row: a [GlassBar] scrolls, so its width is unbounded, and a weighted child in an
 * unbounded row is measured at nothing.
 */
@Composable
private fun BlockCell(item: ChromeItem, deps: ChromeDeps) {
    if (item.brush != null) {
        Segmented {
            Box(Modifier.weight(1f), propagateMinConstraints = true) { ChromeItemContent(item, deps) }
        }
    } else {
        ChromeItemContent(item, deps)
    }
}

/** A bar with nothing in it, in the layout editor: its name, and room to drop a chip into. */
@Composable
private fun EmptyBar(id: BarId) {
    Box(
        modifier = Modifier.defaultMinSize(minWidth = EmptyWidth, minHeight = InkDims.ChipMinHeight),
        contentAlignment = Alignment.Center,
    ) { MicroLabel(id.label) }
}

/**
 * One chip in the layout editor: the real chip, under a transparent cover that takes every touch, so
 * nothing on a bar *does* anything while it is being arranged — a tap on `Delete` must not delete the
 * page.
 *
 * - **Tap** chooses the bar it is in (`InkUiState.selectedBar`), which is what puts that bar's own
 *   size in the editor's panel. A chip is most of a bar, so a tap on one has to mean the bar — the
 *   first editor used the same tap to *hide* the chip, so reaching for a bar made part of it vanish.
 * - **The −/+ badge** in its corner hides it, or shows it again.
 * - **Hold, then drag** lifts it: a picture follows the finger (`ItemDragGhost`), a sky line shows
 *   where it will land, and letting go puts it there — in this bar, another bar, or on the page as a
 *   bar of its own. A hold first, for the reason `ColorSwatch` uses one: a plain drag is how an
 *   overflowing bar is scrolled, and the editor must not take that away.
 *
 * ⚠️ The chip stays where it is until the drop — see [ChromeDrop] on why nothing moves mid-drag.
 */
@Composable
private fun EditableItem(item: ChromeItem, deps: ChromeDeps, content: @Composable () -> Unit) {
    val ui = deps.ui
    val drag = ui.itemDrag
    val where = remember { RootMapper() }
    val token = remember { Any() }
    val press = remember { PressMark() }
    val hidden = ui.layout.isHidden(item)
    DisposableEffect(item) { onDispose { drag.forgetSlot(item, token) } }
    Box(
        modifier = Modifier.onGloballyPositioned {
            drag.reportSlot(item, token, Rect(it.positionInRoot(), it.size.toSize()))
        },
        // Centred, so a chip in a cell wider than itself — a column's, a panel's — sits in the middle
        // of the outline that marks the cell.
        contentAlignment = Alignment.Center,
    ) {
        // Only the chip fades — the badge stays at full strength, because on a hidden chip it is the
        // way back.
        Box(
            modifier = Modifier.graphicsLayer {
                alpha = when {
                    drag.item == item -> LIFTED_ALPHA
                    hidden -> HIDDEN_ALPHA
                    else -> 1f
                }
            },
        ) { content() }
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(ItemOutline, if (hidden) Ink.Hairline else Ink.Focus, InkDims.ChipCorner)
                .onGloballyPositioned { where.coordinates = it }
                .pointerInput(item) {
                    detectTapGestures(
                        onPress = { press.generation = drag.generation },
                        // A hold that became a drag still ends in an up; it is not also a tap.
                        onTap = { if (press.generation == drag.generation) ui.selectedBar = ui.layout.owner(item) },
                    )
                }
                .pointerInput(item) {
                    val reach = DropReach.toPx()
                    val caret = CaretWidth.toPx()
                    detectDragGesturesAfterLongPress(
                        onDragStart = { at -> where.root(at)?.let { drag.begin(item, it) } },
                        onDrag = { change, _ ->
                            change.consume()
                            where.root(change.position)?.let { at ->
                                drag.moveTo(at) { ChromeDrop.resolve(it, item, drag.bars(ui.layout), reach, caret) }
                            }
                        },
                        onDragEnd = { drop(item, deps) },
                        onDragCancel = drag::end,
                    )
                }
                .semantics { contentDescription = item.label },
        )
        if (item.hideable) HideBadge(item, hidden, ui, Modifier.align(Alignment.TopEnd))
    }
}

/**
 * The chip's corner key: **−** hides it, **+** brings a hidden one back. Drawn by hand — a glyph in
 * this font at this size is a smudge, and a missing one is tofu.
 */
@Composable
private fun HideBadge(item: ChromeItem, hidden: Boolean, ui: InkUiState, modifier: Modifier) {
    Canvas(
        modifier = modifier
            .size(BadgeSize)
            .background(if (hidden) Ink.Focus else Ink.PanelRaised, CircleShape)
            .border(ItemOutline, Ink.Focus, CircleShape)
            .pointerInput(item) { detectTapGestures(onTap = { ui.layout = ui.layout.toggled(item) }) }
            .semantics { contentDescription = if (hidden) "Show ${item.label}" else "Hide ${item.label}" },
    ) {
        val stroke = BadgeStroke.toPx()
        val arm = size.width * BADGE_ARM
        val colour = if (hidden) Ink.OnAccent else Ink.TextStrong
        drawLine(colour, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), stroke, StrokeCap.Round)
        if (hidden) {
            drawLine(
                colour,
                Offset(center.x, center.y - arm),
                Offset(center.x, center.y + arm),
                stroke,
                StrokeCap.Round,
            )
        }
    }
}

/**
 * Let go: into the bar under the finger, or onto the page as a bar of its own. A hold that never
 * moved has no target and changes nothing.
 */
private fun drop(item: ChromeItem, deps: ChromeDeps) {
    val ui = deps.ui
    val drag = ui.itemDrag
    when (val target = drag.target) {
        is DropTarget.Into -> ui.layout = ui.layout.movedTo(item, target.bar, target.index)

        DropTarget.Page -> deps.geometry.pagePosition(drag.pointer - drag.grab, drag.size)
            ?.let { ui.layout = ui.layout.floated(item, it) }

        null -> Unit
    }
    drag.end()
}

/** Which drag a press belonged to — see [EditableItem]'s `onTap`. */
private class PressMark {
    var generation = 0
}

/** Faint enough to read as *off*, legible enough to find and tap back on. */
private const val HIDDEN_ALPHA = 0.32f

/** The chip left behind while its picture is in the air. */
private const val LIFTED_ALPHA = 0.2f
private val BadgeSize = 18.dp
private val BadgeStroke = 2.dp

/** Half the − and + as a share of the badge's width. */
private const val BADGE_ARM = 0.25f
private val ItemOutline = 1.dp
private val EmptyWidth = 96.dp

/** How far outside a bar a drop still goes into it — a chip let go just past a pill's rounded end. */
private val DropReach = 16.dp
private val CaretWidth = 3.dp
