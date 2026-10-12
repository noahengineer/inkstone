package dev.inkstone.app.ink

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.inkstone.app.ui.ChipDivider
import dev.inkstone.app.ui.ChipDividerAcross
import dev.inkstone.app.ui.GlassBar
import dev.inkstone.app.ui.GlassBlock
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.Segmented

/**
 * ⭐ A bar: whichever chips the user has put in it, laid out the way they chose — a row (the design's
 * pill), a column, or a grid.
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

    val arrangement = ui.layout.bar(id).arrangement
    if (arrangement == ChromeArrangement.ROW) {
        GlassBar(modifier = report) {
            if (items.isEmpty()) EmptyBar(id) else RowItems(items, deps)
            trailing?.let { more ->
                if (items.isNotEmpty()) ChipDivider()
                more()
            }
        }
    } else {
        GlassBlock(columns = arrangement.columns, modifier = report) {
            if (items.isEmpty()) EmptyBar(id) else BlockItems(items, arrangement, deps)
            trailing?.invoke()
        }
    }
}

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
            key(item) { Segmented { run.forEach { key(it) { ChromeItemContent(it, deps) } } } }
            index += run.size
        } else {
            key(item) { ChromeItemContent(item, deps) }
            index++
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
            if (deps.ui.editingLayout) EditableItem(item, deps) { Cell(item, deps) } else Cell(item, deps)
        }
    }
}

/** One chip on its own — a brush in a track of its own, everything else as itself. */
@Composable
private fun Cell(item: ChromeItem, deps: ChromeDeps) {
    if (item.brush != null) Segmented { ChromeItemContent(item, deps) } else ChromeItemContent(item, deps)
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
 * nothing on a bar *does* anything while it is being arranged — a tap on `Delete` hides the chip and
 * must not delete the page.
 *
 * - **Tap** hides it, or shows it again.
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
        modifier = Modifier
            .onGloballyPositioned { drag.reportSlot(item, token, Rect(it.positionInRoot(), it.size.toSize())) }
            .graphicsLayer {
                alpha = when {
                    drag.item == item -> LIFTED_ALPHA
                    hidden -> HIDDEN_ALPHA
                    else -> 1f
                }
            },
    ) {
        content()
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(ItemOutline, if (hidden) Ink.Hairline else Ink.Focus, InkDims.ChipCorner)
                .onGloballyPositioned { where.coordinates = it }
                .pointerInput(item) {
                    detectTapGestures(
                        onPress = { press.generation = drag.generation },
                        // A hold that became a drag still ends in an up; it is not also a tap.
                        onTap = { if (press.generation == drag.generation) ui.layout = ui.layout.toggled(item) },
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
                .semantics {
                    contentDescription = when {
                        !item.hideable -> item.label
                        hidden -> "Show ${item.label}"
                        else -> "Hide ${item.label}"
                    }
                },
        )
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
private val ItemOutline = 1.dp
private val EmptyWidth = 96.dp

/** How far outside a bar a drop still goes into it — a chip let go just past a pill's rounded end. */
private val DropReach = 16.dp
private val CaretWidth = 3.dp
