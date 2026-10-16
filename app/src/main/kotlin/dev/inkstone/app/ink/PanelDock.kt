package dev.inkstone.app.ink

import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * ⭐ Where each open panel goes in the panel column — and, when one column is full, the next column
 * beside it, further from the edge — so that **no two panels are ever on top of each other**
 * (docs/UI-RAILS.md §3).
 *
 * > *"there is just so much overlapping still and that is what i wanted to avoid"*
 *
 * Every panel used to place itself: the colour panel under its `+`, the Elements library in a corner,
 * the diagnostics in another, the calculator under its chip. Each placement was reasonable alone and
 * together they piled up, because no one of them knew where the others were. Here one function places
 * all of them, top to bottom, away from the edge the column stands against (`ChromeLayout.panelSide`,
 * docs/LAYOUTS.md). Pure, so `PanelDockTest` can hold it.
 */
internal object DockFlow {

    /**
     * The top-left of each of [sizes], in order, inside [area]: in the **first** column it fits under —
     * starting from [area]'s top-right, or its top-left when [fromLeft] — or else in a new column beside
     * the last one, further from that edge.
     *
     * ⚠️ A panel only joins an earlier column if it is no wider than that column, or if that column is
     * the last one. Columns are laid out by their widths, so a wide panel slipped under a narrow one
     * would reach into the column beside it — the overlap this exists to prevent. A panel taller than
     * [area] still gets a column of its own, from the top; it is the panel's job to fit.
     */
    fun place(sizes: List<IntSize>, area: IntRect, gap: Int, fromLeft: Boolean = false): List<IntOffset> {
        val columns = mutableListOf<DockColumn>()
        return sizes.map { size ->
            val fits = columns.withIndex().firstOrNull { (i, column) ->
                column.bottom + gap + size.height <= area.bottom &&
                    (size.width <= column.width || i == columns.lastIndex)
            }?.value
            val column = fits ?: DockColumn(
                edge =
                columns.lastOrNull()?.let { if (fromLeft) it.edge + it.width + gap else it.edge - it.width - gap }
                    ?: if (fromLeft) area.left else area.right,
                bottom = area.top - gap,
            ).also { columns += it }
            val y = column.bottom + gap
            column.bottom = y + size.height
            column.width = maxOf(column.width, size.width)
            IntOffset(if (fromLeft) column.edge else column.edge - size.width, y)
        }
    }

    /**
     * One column while it is being filled: its outer edge — the right for a right-hand column, the left
     * for a left-hand one — its widest panel, and where its last one ends.
     */
    private class DockColumn(val edge: Int, var bottom: Int, var width: Int = 0)
}

/**
 * The panel column, as state: which panels are open, how big each is, which side and in what order
 * ([side], [order] — the layout's, pushed in by `InkScreen`), and from that where each goes. A panel
 * joins with [docked]; its position is read in the placement phase, so a panel growing or another
 * opening moves the rest without recomposing them.
 *
 * The column runs from under the bars at the top of its side to above the bars at the bottom of it,
 * and starts beside any bar standing upright against that edge — the tools, in the stock layout, for
 * a left-hand column — all read from [geometry], so it follows the chrome when the user moves a bar
 * and never sits on one.
 */
@Stable
internal class PanelDock(private val geometry: ChromeGeometry) {
    private val sizes = mutableStateMapOf<DockSlot, IntSize>()
    private val loose = mutableStateMapOf<DockSlot, Rect>()

    /** Between two panels, in px. Written by [docked] from the screen's density. */
    var gap: Int = 0

    /** How close to the column's edge a panel has to be let go to join it, in px. Written by [docked]. */
    var snapReach: Int = 0

    var side by mutableStateOf(PanelSide.RIGHT)
    var order by mutableStateOf(DockSlot.entries.toList())

    fun report(slot: DockSlot, size: IntSize) {
        if (sizes[slot] != size) sizes[slot] = size
    }

    fun forget(slot: DockSlot) {
        sizes.remove(slot)
    }

    /** Where [slot] goes, in the host's px; null until it has been measured. */
    fun offsetOf(slot: DockSlot): IntOffset? {
        val open = order.filter { it in sizes }
        val index = open.indexOf(slot).takeIf { it >= 0 } ?: return null
        return DockFlow.place(open.map { sizes.getValue(it) }, area(), gap, side == PanelSide.LEFT)[index]
    }

    /**
     * Every open panel's rectangle, in the host's px — what a contextual bar keeps out from under —
     * including a panel the user has dragged out of the column ([reportLoose]) — every one but [except],
     * which is how a panel being dragged leaves itself out of what it snaps to.
     */
    fun occupied(except: DockSlot? = null): List<Rect> {
        val open = order.filter { it in sizes }
        val at = DockFlow.place(open.map { sizes.getValue(it) }, area(), gap, side == PanelSide.LEFT)
        return open.mapIndexedNotNull { i, slot ->
            val size = sizes.getValue(slot)
            Rect(Offset(at[i].x.toFloat(), at[i].y.toFloat()), Size(size.width.toFloat(), size.height.toFloat()))
                .takeIf { slot != except }
        } + loose.filterKeys { it != except }.values
    }

    /**
     * Where a panel that is *out* of the column is, in the host's px, or null once it is back or shut —
     * a panel dragged somewhere of the user's choosing still keeps a contextual bar off it.
     */
    fun reportLoose(slot: DockSlot, bounds: Rect?) {
        if (bounds == null) {
            loose.remove(slot)
        } else if (loose[slot] != bounds) {
            loose[slot] = bounds
        }
    }

    /**
     * ⭐ Whether a panel let go at [bounds] has been put back against the column's edge — the snap that
     * docks it, and what a drag out of the column has to undo by more than [snapReach].
     */
    fun wantsDock(bounds: Rect): Boolean {
        val area = area()
        return if (side == PanelSide.LEFT) {
            bounds.left <= area.left + snapReach
        } else {
            bounds.right >= area.right - snapReach
        }
    }

    /** How tall a column is, in px — the most a panel can be and still fit. */
    fun columnHeight(): Int = area().height

    /**
     * The column's space: against the [side] edge — or beside whatever stands upright against it — from
     * under whatever sits at the top of that half to above whatever sits at the bottom of it.
     *
     * Only the edge the column starts from is meaningful; the other is the screen's far side.
     */
    fun area(): IntRect {
        val width = geometry.width
        val height = geometry.height
        val edge = geometry.edge
        val left = side == PanelSide.LEFT
        val near = geometry.pillsWithFoldedRow().filter {
            if (left) {
                it.center.x < width / 2f
            } else {
                it.center.x >
                    width / 2f
            }
        }
        val (upright, flat) = near.partition { it.height > it.width }
        val top = flat.filter { it.center.y < height / 2f }.maxOfOrNull { it.bottom + gap } ?: edge
        val bottom = flat.filter { it.center.y >= height / 2f }.minOfOrNull { it.top - gap } ?: (height - edge)
        return if (left) {
            val start = upright.maxOfOrNull { it.right + gap } ?: edge
            IntRect(start.toInt(), top.toInt(), width.toInt(), bottom.toInt())
        } else {
            val end = upright.minOfOrNull { it.left - gap } ?: (width - edge)
            IntRect(0, top.toInt(), end.toInt(), bottom.toInt())
        }
    }
}

/**
 * Puts this panel in [dock]'s column at [slot]: reports its size, takes its place, and is invisible for
 * the one frame before it has one — the `AnchoredPanel` rule, so it never jumps in from the corner.
 *
 * ⚠️ The panel's parent must fill the screen from the same origin as the chrome (`InkScreen`'s `Box`),
 * because the dock's positions are in that space.
 */
internal fun Modifier.docked(dock: PanelDock, slot: DockSlot): Modifier = composed {
    DisposableEffect(dock, slot) { onDispose { dock.forget(slot) } }
    dock.measuredWith(LocalDensity.current)
    this
        .offset { dock.offsetOf(slot) ?: IntOffset.Zero }
        .onSizeChanged { dock.report(slot, it) }
        .graphicsLayer { alpha = if (dock.offsetOf(slot) == null) 0f else 1f }
}

/** The column's spacing at the screen's density: what [docked] and a floating panel both set. */
internal fun PanelDock.measuredWith(density: androidx.compose.ui.unit.Density) {
    gap = with(density) { DockGap.roundToPx() }
    snapReach = with(density) { DockSnapReach.roundToPx() }
}

/** Between two panels in the column, and between a column and the next. */
private val DockGap = 8.dp

/** How near the column's edge a panel let go of docks. About a fingertip. */
private val DockSnapReach = 48.dp
