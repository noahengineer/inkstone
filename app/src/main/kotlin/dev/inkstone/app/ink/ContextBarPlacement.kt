package dev.inkstone.app.ink

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.hypot

/** Where a floating bar's top-left corner goes, in view pixels. */
internal data class BarPosition(val x: Float, val y: Float)

/** Where a contextual card's top-left corner goes and how wide it may be, in view pixels. */
internal data class CardSpot(val x: Float, val y: Float, val width: Float)

/**
 * ⭐ Where a contextual toolbar floats, as plain arithmetic over view-space rectangles.
 *
 * Hoisted out of `ContextBars.AnchoredBar` for the reason CLAUDE.md keeps giving: a composable cannot
 * be asserted on in a JVM test, and the decision it makes is exactly the kind that looks right in a
 * screenshot of one object and is wrong for the next. It is also the decision that went wrong — the
 * bar was anchored to the selection's box with a 14 dp gap while the rotate grip floats 28 pt above
 * that box, so the bar was drawn over the grip on every selection.
 */
internal object ContextBarPlacement {

    /**
     * ⭐ The whole decision for a contextual card: beside its object when there is a gap there at least
     * [minWidth] wide ([freeWidth], [place]); otherwise the nearest free rectangle **anywhere** on the
     * screen ([refuge]); and only when the screen has none, at the object and over whatever is there.
     *
     * > *"there is just so much overlapping still and that is what i wanted to avoid"*
     *
     * The middle step is what four panels open in the right-hand column needed. The column then reaches
     * from the calculator's chip nearly to the tools, no gap level with the object is wide enough, and
     * the card used to take the screen's width and slide under the panels — while the space under the
     * shortest panel stood empty.
     *
     * @param anchor what the card must not cover, in view px: a selection's chrome, a text box's box.
     * @param naturalWidth the card with each of its lines on one row.
     * @param heightAt the card's height when held to a width. A narrower card wraps and is taller, so a
     *   space is only a refuge if the card *at that space's width* fits in it.
     * @param rails what a card beside its object keeps out from under: the upright bars and the panels.
     * @param chrome every bar and panel — what a card placed anywhere must keep off.
     */
    @Suppress("LongParameterList")
    fun spot(
        anchor: Rect,
        naturalWidth: Float,
        heightAt: (Float) -> Float,
        viewport: Size,
        clearance: Float,
        edgePadding: Float,
        safeTop: Float,
        reach: Float,
        minWidth: Float,
        rails: List<Rect>,
        chrome: List<Rect>,
    ): CardSpot {
        val roomy = freeWidth(
            anchor.center.x,
            anchor.top,
            anchor.bottom,
            reach,
            rails,
            viewport.width,
            edgePadding,
            minWidth,
        )
        if (roomy == null) {
            val area = Rect(edgePadding, safeTop, viewport.width - edgePadding, viewport.height - edgePadding)
            val blocks = chrome + anchor.inflate(clearance)
            refuge(anchor.center, naturalWidth, heightAt, blocks, area, edgePadding, minWidth)
                ?.let { return CardSpot(it.left, it.top, it.width) }
        }
        val width = minOf(naturalWidth, roomy ?: (viewport.width - 2 * edgePadding))
        val at = place(
            anchor.left,
            anchor.top,
            anchor.right,
            anchor.bottom,
            width,
            heightAt(width),
            viewport.width,
            viewport.height,
            clearance,
            edgePadding,
            safeTop,
            rails,
        )
        return CardSpot(at.x, at.y, width)
    }

    /**
     * The card's rectangle in the free space nearest [centre] — clear of every [blocks] by [padding],
     * inside [area], at least [minWidth] wide and as tall as the card is at that width — or null when
     * no such space exists.
     *
     * Every free rectangle is bounded left and right by two block edges (or the area's), so trying each
     * pair of edges and, between them, each vertical run no block covers finds all of them. A handful of
     * bars and panels makes that a few hundred rectangles, which is nothing.
     */
    @Suppress("LongParameterList")
    fun refuge(
        centre: Offset,
        naturalWidth: Float,
        heightAt: (Float) -> Float,
        blocks: List<Rect>,
        area: Rect,
        padding: Float,
        minWidth: Float,
    ): Rect? {
        val walls = blocks.map { it.inflate(padding) }
        val edges = (walls.flatMap { listOf(it.left, it.right) } + area.left + area.right)
            .filter { it >= area.left && it <= area.right }
            .distinct()
            .sorted()
        val spaces = edges.flatMapIndexed { i, left ->
            edges.drop(i + 1).filter { it - left >= minWidth }.flatMap { right ->
                freeRuns(walls.filter { it.left < right && it.right > left }, area.top, area.bottom)
                    .map { Rect(left, it.start, right, it.endInclusive) }
            }
        }
        val widths = trialWidths(minWidth, naturalWidth)
        return spaces.mapNotNull { space ->
            widths.lastOrNull { it <= space.width }?.let { cardIn(space, centre, it, heightAt) }
        }.minByOrNull { hypot(it.center.x - centre.x, it.center.y - centre.y) }
    }

    /**
     * The widths a card is tried at in a [refuge]: its natural width and a few steps down to [minWidth].
     *
     * ⚠️ **A handful, never one per space.** Each width is an intrinsic measurement of the whole card,
     * which took 11 ms on the emulator's debug build; trying every space's own width was up to ~150 of
     * them, a layout pass of over a second, repeated whenever any panel changes size. The first time the
     * Elements library opened as a fourth panel the editor stalled for 215 s (`Skipped 12946 frames`).
     * A space now holds the widest trial width that fits in it, at most one step narrower than the space.
     */
    private fun trialWidths(minWidth: Float, naturalWidth: Float): List<Float> = if (naturalWidth <= minWidth) {
        listOf(naturalWidth)
    } else {
        (0..TRIAL_STEPS).map { minWidth + (naturalWidth - minWidth) * it / TRIAL_STEPS }
    }

    private const val TRIAL_STEPS = 4

    /** The card at [width], as near [centre] as [space] lets it be — or null when it is too tall for it. */
    private fun cardIn(space: Rect, centre: Offset, width: Float, heightAt: (Float) -> Float): Rect? {
        val height = heightAt(width)
        if (height > space.height) return null
        val x = (centre.x - width / 2f).coerceIn(space.left, space.right - width)
        val y = (centre.y - height / 2f).coerceIn(space.top, space.bottom - height)
        return Rect(x, y, x + width, y + height)
    }

    /** The stretches of [top]..[bottom] that none of [blocks] cover, top to bottom. */
    private fun freeRuns(blocks: List<Rect>, top: Float, bottom: Float): List<ClosedFloatingPointRange<Float>> {
        val runs = mutableListOf<ClosedFloatingPointRange<Float>>()
        var y = top
        blocks.sortedBy { it.top }.forEach { block ->
            if (block.top > y) runs += y..minOf(block.top, bottom)
            y = maxOf(y, block.bottom)
        }
        if (y < bottom) runs += y..bottom
        return runs.filter { it.endInclusive > it.start }
    }

    /**
     * Above the anchor if it fits, below if it does not, never above [safeTop], and centred on the
     * anchor horizontally within [edgePadding] of the screen.
     *
     * @param anchorLeft … [anchorBottom] what the bar must not cover, in view pixels. For a selection
     *   that is its **chrome** — the box plus the rotate grip — and not the box alone.
     * @param clearance the gap between the anchor and the bar. For a selection it must be at least
     *   the grips' touch reach: a bar clear of a grip's 6 dp drawing but inside its 22 dp target is a
     *   grip the user can see and cannot press.
     * @param keepOut ⭐ chrome standing at the sides — the tool and page rails, an open Elements library
     *   (docs/UI-RAILS.md). They are drawn over the contextual bars, so a bar slid under one has chips
     *   the user can see the edge of and cannot press — the dock problem [safeTop] solves, turned on its
     *   side. Only what the bar would actually **overlap top to bottom** counts: a bar above a rail
     *   centred on the screen's height has the whole width. Kept only while the bar fits between them;
     *   a bar wider than the gap falls back to the screen's edges, because a whole bar half under a
     *   rail is better than one pushed off the screen.
     */
    @Suppress("LongParameterList")
    fun place(
        anchorLeft: Float,
        anchorTop: Float,
        anchorRight: Float,
        anchorBottom: Float,
        barWidth: Float,
        barHeight: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        clearance: Float,
        edgePadding: Float,
        safeTop: Float,
        keepOut: List<Rect> = emptyList(),
    ): BarPosition {
        val maxY = (viewportHeight - barHeight - edgePadding).coerceAtLeast(safeTop)
        val above = anchorTop - clearance - barHeight
        val y = (if (above >= safeTop) above else anchorBottom + clearance).coerceIn(safeTop, maxY)

        val centre = (anchorLeft + anchorRight) / 2f
        val gap = gapAround(centre, keepOut.filter { it.top < y + barHeight && it.bottom > y }, viewportWidth)
        val railMin = maxOf(edgePadding, gap.start + edgePadding)
        val railMax = minOf(viewportWidth, gap.endInclusive) - edgePadding - barWidth
        val screenMax = (viewportWidth - barWidth - edgePadding).coerceAtLeast(edgePadding)
        val (minX, maxX) = if (railMax >= railMin) railMin to railMax else edgePadding to screenMax
        val x = ((anchorLeft + anchorRight) / 2f - barWidth / 2f).coerceIn(minX, maxX)
        return BarPosition(x, y)
    }

    /**
     * ⭐ The widest a contextual card can be and still sit between the [keepOut]s anywhere within [reach]
     * above or below the anchor — or null when that is less than [atLeast], and the card should take the
     * screen's width and be overlapped rather than become a column of single chips.
     *
     * Measured over the band the card could land in rather than the card's own rows, because its height
     * depends on the width this returns: a band that moved with the card's height could narrow it, which
     * makes it taller, which narrows it again.
     */
    @Suppress("LongParameterList")
    fun freeWidth(
        centre: Float,
        anchorTop: Float,
        anchorBottom: Float,
        reach: Float,
        keepOut: List<Rect>,
        viewportWidth: Float,
        edgePadding: Float,
        atLeast: Float,
    ): Float? {
        val top = minOf(anchorTop, anchorBottom) - reach
        val bottom = maxOf(anchorTop, anchorBottom) + reach
        val gap = gapAround(centre, keepOut.filter { it.top < bottom && it.bottom > top }, viewportWidth)
        val free = gap.endInclusive - gap.start - 2 * edgePadding
        return free.takeIf { it >= atLeast }
    }

    /**
     * The stretch of the screen's width that no [blocks] cover and that holds [centre] — or, when [centre]
     * is itself under one, the widest such stretch.
     *
     * ⚠️ **Not *"left of the middle is a left-hand obstacle"*.** That was the first version, and the panel
     * column broke it on the emulator: with three panels open the column's leftmost panel has its centre
     * left of the screen's middle, so it counted as being on the *left* — and the card, finding no room
     * between it and the tools, fell back to the full width and slid under the panels it was meant to
     * avoid.
     */
    fun gapAround(centre: Float, blocks: List<Rect>, viewportWidth: Float): ClosedFloatingPointRange<Float> {
        val gaps = mutableListOf<ClosedFloatingPointRange<Float>>()
        var x = 0f
        blocks.map { it.left.coerceAtLeast(0f) to it.right.coerceAtMost(viewportWidth) }
            .sortedBy { it.first }
            .forEach { (left, right) ->
                if (left > x) gaps += x..left
                x = maxOf(x, right)
            }
        if (x < viewportWidth) gaps += x..viewportWidth
        return gaps.firstOrNull { centre in it } ?: gaps.maxByOrNull { it.endInclusive - it.start } ?: 0f..0f
    }
}
