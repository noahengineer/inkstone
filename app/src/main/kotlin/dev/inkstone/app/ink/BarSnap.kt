package dev.inkstone.app.ink

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs

/**
 * Where a bar or panel being dragged lines up with something, and the line it lines up on — drawn while
 * the finger is down so the user can see what it caught on.
 *
 * @param x a vertical line at this x, or null; [y] likewise a horizontal one.
 */
@Immutable
internal data class Snapped(val by: Offset, val x: Float? = null, val y: Float? = null)

/**
 * ⭐ *"Bars snap while dragging"* (docs/LAYOUTS.md): a bar or panel within [reach] of lining up with
 * something is moved the rest of the way — its edge onto the screen's margin, its centre onto the
 * screen's centre, its edges onto another bar's edges, or its side beside another bar's with the margin
 * between them. Each axis snaps on its own, to whichever line is nearest.
 *
 * Pure, so `BarSnapTest` can hold it; the caller decides what counts as "something" (every placed bar
 * but the one moving and whatever follows it).
 */
internal object BarSnap {

    fun snap(moving: Rect, others: List<Rect>, viewport: Size, edge: Float, reach: Float): Snapped {
        val x = nearest(xCandidates(moving, others, viewport, edge), reach)
        val y = nearest(yCandidates(moving, others, viewport, edge), reach)
        return Snapped(Offset(x?.first ?: 0f, y?.first ?: 0f), x?.second, y?.second)
    }

    /** Pairs of (how far the moving edge is from a line, where the line is), across. */
    private fun xCandidates(m: Rect, others: List<Rect>, viewport: Size, edge: Float): List<Pair<Float, Float>> =
        buildList {
            add(edge - m.left to edge)
            add(viewport.width - edge - m.right to viewport.width - edge)
            add(viewport.width / 2f - m.center.x to viewport.width / 2f)
            others.forEach { o ->
                add(o.left - m.left to o.left)
                add(o.right - m.right to o.right)
                add(o.center.x - m.center.x to o.center.x)
                // Beside it, one margin apart; the line drawn is the near edge of the other bar.
                add(o.right + edge - m.left to o.right)
                add(o.left - edge - m.right to o.left)
            }
        }

    private fun yCandidates(m: Rect, others: List<Rect>, viewport: Size, edge: Float): List<Pair<Float, Float>> =
        buildList {
            add(edge - m.top to edge)
            add(viewport.height - edge - m.bottom to viewport.height - edge)
            add(viewport.height / 2f - m.center.y to viewport.height / 2f)
            others.forEach { o ->
                add(o.top - m.top to o.top)
                add(o.bottom - m.bottom to o.bottom)
                add(o.center.y - m.center.y to o.center.y)
                add(o.bottom + edge - m.top to o.bottom)
                add(o.top - edge - m.bottom to o.top)
            }
        }

    private fun nearest(candidates: List<Pair<Float, Float>>, reach: Float): Pair<Float, Float>? =
        candidates.filter { abs(it.first) <= reach }.minByOrNull { abs(it.first) }
}
