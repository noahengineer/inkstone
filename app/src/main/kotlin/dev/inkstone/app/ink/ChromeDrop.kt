package dev.inkstone.app.ink

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.hypot

/**
 * ⭐ Where a chip dragged in the layout editor lands — into a bar at a position, or onto the page as
 * a bar of its own — as arithmetic over rectangles, so it can be tested without a screen.
 *
 * ## ⚠️ Nothing moves until the drop
 *
 * The chip being dragged stays where it is, faded, and a picture of it follows the finger
 * (`ItemDragGhost`). The first editor moved a chip *live*, trading places with each neighbour it
 * crossed — which works inside one row and cannot work across bars: moving a chip into another bar
 * gives its node a different parent, Compose disposes the old one, and the drag's own gesture
 * coroutine is cancelled with it on the first bar boundary the finger crosses. So the drag only
 * *describes* where the chip would go ([DropTarget], drawn as a caret), and the layout changes once,
 * on lift.
 */
internal object ChromeDrop {

    /**
     * The target for [pointer] (root px) while [dragged] is in the air.
     *
     * A bar counts when the pointer is within [reach] of it, so a chip dropped just past a bar's
     * rounded end still goes in. Where two bars overlap, the **smaller** wins — a one-chip floating
     * bar laid over the dock is the one the user is aiming at. Anywhere else is the page.
     *
     * The index is into the bar's chips **with [dragged] taken out**, which is what
     * [ChromeLayout.movedTo] expects.
     */
    fun resolve(pointer: Offset, dragged: ChromeItem, bars: List<DropBar>, reach: Float, caret: Float): DropTarget {
        val bar = bars.filter { it.bounds.inflate(reach).contains(pointer) }
            .minByOrNull { it.bounds.width * it.bounds.height }
            ?: return DropTarget.Page
        val slots = bar.slots.filter { it.first != dragged }.map { it.second }
        if (slots.isEmpty()) return DropTarget.Into(bar.id, 0, startCaret(bar, caret))
        return when (bar.arrangement) {
            ChromeArrangement.ROW -> alongX(bar.id, slots, slots.count { it.center.x < pointer.x }, caret)

            ChromeArrangement.COLUMN -> alongY(bar.id, slots, slots.count { it.center.y < pointer.y }, caret)

            else -> {
                val nearest = slots.indices.minBy {
                    hypot(slots[it].center.x - pointer.x, slots[it].center.y - pointer.y)
                }
                val after = pointer.x > slots[nearest].center.x
                val cell = slots[nearest]
                val x = if (after) cell.right else cell.left
                val line = Rect(x - caret / 2, cell.top, x + caret / 2, cell.bottom)
                DropTarget.Into(bar.id, nearest + if (after) 1 else 0, line)
            }
        }
    }

    /** A caret between the chips either side of [index] in a row. */
    private fun alongX(id: BarId, slots: List<Rect>, index: Int, caret: Float): DropTarget {
        val x = when (index) {
            0 -> slots.first().left
            slots.size -> slots.last().right
            else -> (slots[index - 1].right + slots[index].left) / 2f
        }
        val top = slots.minOf { it.top }
        val bottom = slots.maxOf { it.bottom }
        return DropTarget.Into(id, index, Rect(x - caret / 2, top, x + caret / 2, bottom))
    }

    /** The same, down a column. */
    private fun alongY(id: BarId, slots: List<Rect>, index: Int, caret: Float): DropTarget {
        val y = when (index) {
            0 -> slots.first().top
            slots.size -> slots.last().bottom
            else -> (slots[index - 1].bottom + slots[index].top) / 2f
        }
        val left = slots.minOf { it.left }
        val right = slots.maxOf { it.right }
        return DropTarget.Into(id, index, Rect(left, y - caret / 2, right, y + caret / 2))
    }

    /** An empty bar's only place, at its start. */
    private fun startCaret(bar: DropBar, caret: Float): Rect = if (bar.arrangement == ChromeArrangement.ROW) {
        Rect(bar.bounds.left, bar.bounds.top, bar.bounds.left + caret, bar.bounds.bottom)
    } else {
        Rect(bar.bounds.left, bar.bounds.top, bar.bounds.right, bar.bounds.top + caret)
    }
}

/**
 * A bar a dragged chip can land in, as the drop sees it. Every rectangle is in root px; [slots] are in
 * the bar's own order.
 */
internal class DropBar(
    val id: BarId,
    val bounds: Rect,
    val arrangement: ChromeArrangement,
    val slots: List<Pair<ChromeItem, Rect>>,
)

internal sealed interface DropTarget {
    /** Into [bar] at [index]; [caret] is where the editor draws the insertion line. */
    data class Into(val bar: BarId, val index: Int, val caret: Rect) : DropTarget

    /** Onto the page, as a bar of its own. */
    data object Page : DropTarget
}

/**
 * The chip in the air in the layout editor, and where every chip and bar on screen is.
 *
 * [pointer] and [target] are state, read only by the ghost and the caret, so a frame of a drag redraws
 * two small boxes and recomposes nothing else. The rectangles are plain maps, written as things are
 * laid out and read only when the finger moves.
 *
 * ⚠️ **Registrations carry a token.** When a chip moves to another bar its old node is disposed and a
 * new one composed, in an order Compose does not promise; an old node's disposal removing the new
 * node's entry would leave a chip the next drop cannot see. Each entry is removed only by the node
 * that wrote it.
 */
@Stable
class ItemDrag {
    var item by mutableStateOf<ChromeItem?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set

    /** Where letting go would put the chip; null until the finger has moved, so a hold alone is nothing. */
    internal var target by mutableStateOf<DropTarget?>(null)
        private set

    /** Where on the chip the finger took hold, so the picture does not jump to the finger's corner. */
    var grab: Offset = Offset.Zero
        private set

    /** The chip's own size, for its picture and for the bar a page drop makes. */
    var size: Size = Size.Zero
        private set

    /** Bumped at every drag start, so a press can tell whether it turned into one. */
    var generation: Int = 0
        private set

    private val slots = HashMap<ChromeItem, Pair<Any, Rect>>()
    private val containers = HashMap<BarId, Pair<Any, Rect>>()

    fun reportSlot(item: ChromeItem, token: Any, rect: Rect) {
        slots[item] = token to rect
    }

    fun forgetSlot(item: ChromeItem, token: Any) {
        if (slots[item]?.first === token) slots.remove(item)
    }

    fun reportBar(id: BarId, token: Any, rect: Rect) {
        containers[id] = token to rect
    }

    fun forgetBar(id: BarId, token: Any) {
        if (containers[id]?.first === token) containers.remove(id)
    }

    fun begin(dragged: ChromeItem, at: Offset) {
        val home = slots[dragged]?.second ?: Rect(at, Size.Zero)
        generation++
        item = dragged
        grab = at - home.topLeft
        size = home.size
        pointer = at
        target = null
    }

    internal fun moveTo(at: Offset, resolve: (Offset) -> DropTarget) {
        pointer = at
        target = resolve(at)
    }

    fun end() {
        item = null
        target = null
    }

    /** Every bar on screen, as [ChromeDrop.resolve] wants them. */
    internal fun bars(layout: ChromeLayout): List<DropBar> = containers.map { (id, entry) ->
        val inBar = layout.itemsOf(id).mapNotNull { item -> slots[item]?.let { item to it.second } }
        DropBar(id, entry.second, layout.arrangement(id), inBar)
    }
}
