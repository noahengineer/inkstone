package dev.inkstone.app.ink

/** Where a floating bar's top-left corner goes, in view pixels. */
internal data class BarPosition(val x: Float, val y: Float)

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
     * Above the anchor if it fits, below if it does not, never above [safeTop], and centred on the
     * anchor horizontally within [edgePadding] of the screen.
     *
     * @param anchorLeft … [anchorBottom] what the bar must not cover, in view pixels. For a selection
     *   that is its **chrome** — the box plus the rotate grip — and not the box alone.
     * @param clearance the gap between the anchor and the bar. For a selection it must be at least
     *   the grips' touch reach: a bar clear of a grip's 6 dp drawing but inside its 22 dp target is a
     *   grip the user can see and cannot press.
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
    ): BarPosition {
        val maxX = (viewportWidth - barWidth - edgePadding).coerceAtLeast(edgePadding)
        val maxY = (viewportHeight - barHeight - edgePadding).coerceAtLeast(safeTop)
        val x = ((anchorLeft + anchorRight) / 2f - barWidth / 2f).coerceIn(edgePadding, maxX)
        val above = anchorTop - clearance - barHeight
        val y = (if (above >= safeTop) above else anchorBottom + clearance).coerceIn(safeTop, maxY)
        return BarPosition(x, y)
    }
}
