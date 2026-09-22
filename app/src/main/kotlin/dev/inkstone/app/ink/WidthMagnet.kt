package dev.inkstone.app.ink

import kotlin.math.abs

/**
 * ⭐ ROADMAP M10 — *"if you slide the slider it snaps to those points, which are also indicated by
 * little lines or dots"*.
 *
 * The saved widths are drawn on the slider's track (`SlimSlider`'s `ticks`) and the slider is pulled
 * onto them as it passes. The marks are not decoration: **a snap you cannot see is indistinguishable
 * from a slider that sticks**, and the difference between those two readings is the difference
 * between a feature and a bug report.
 *
 * ## ⚠️ Why the pull is a fraction of the GAP and not a fixed tolerance
 *
 * The first version used a fixed window — a fraction of the slider's 0.5–32 dp range — and it cannot
 * work, because the saved sizes are not evenly spaced. The stock ladder is `1, 2, 3, 4, 8, 16`: one
 * dp apart at the bottom and eight apart at the top. Any single window is then wrong at both ends.
 *
 * - Wide enough to feel at the top (say 2 dp, so 16 has a real pull) and the bottom of the range is
 *   **fully quantised** — 1, 2, 3 and 4 are the only widths a user can reach from the dock, and
 *   2.5 dp, which is the ballpoint's own default, becomes unselectable.
 * - Narrow enough to leave the bottom usable (0.3 dp) and the pull at 16 is a twentieth of the gap
 *   either side, which on a 104 dp track is about one pixel of travel. Nobody can feel it, and the
 *   marks then look like they do nothing.
 *
 * So the window is **[PULL] of the distance to the nearest neighbouring slot**, which is 0.3 dp
 * around 1 and 2.4 dp around 16 — the same *proportional* feel everywhere, and it keeps at least
 * 40% of every interval reachable by construction. That last part is the property the test asserts,
 * because it is the one that stops this quietly becoming a quantiser.
 */
internal object WidthMagnet {

    /**
     * The width the slider should actually take, or null to leave [valueDp] alone.
     *
     * @param slots the saved widths, ascending and deduplicated — which `EditorPrefsCodec`
     *   guarantees. Unsorted input would still work; duplicates would make the neighbour gap zero
     *   and disable the pull, which is a safe way to be wrong.
     */
    fun pull(valueDp: Float, slots: List<Float>): Float? {
        val nearest = slots.minByOrNull { abs(it - valueDp) } ?: return null
        val gap = slots.filter { it != nearest }.minOfOrNull { abs(it - nearest) } ?: LONE_SLOT_GAP
        return nearest.takeIf { abs(nearest - valueDp) <= gap * PULL }
    }

    /**
     * How much of the gap to a slot's nearest neighbour is inside its pull, on each side.
     *
     * At 0.3 a slot claims 30% of the interval either way, so 40% of every interval is still free —
     * enough to place a width deliberately between two saved ones without fighting the control.
     */
    const val PULL: Float = 0.3f

    /**
     * The notional neighbour distance when there is only one saved size.
     *
     * Without it a lone slot has no gap to be a fraction of, and the choice is between "no pull at
     * all" (the mark on the track then does nothing) and "pull everywhere" (the slider has one
     * reachable value). A fixed 2 dp is a normal-feeling magnet and leaves the rest of the range.
     */
    private const val LONE_SLOT_GAP = 2f
}
