package dev.inkstone.app.ink

import kotlin.math.roundToInt

/**
 * The saved stroke widths, and the range they live in, as pure rules.
 *
 * *"i dont like the size thing it should not be preset sizes and should not be a set amount you
 * should be ablet to edit every one and you should be able to set the max size"*. The first size
 * panel could add the current width (up to ten) and long-press to forget one, inside a slider range
 * fixed at 0.5–32 dp — so the six stock sizes could be removed but never *changed*, and nothing could
 * go past 32. Now every saved size is edited in place, there is no count, and the top of the range is
 * a setting.
 *
 * Hoisted out of `InkUiState` and the codec for the reason this repo keeps giving: the panel is a
 * Compose popover over a `SurfaceView`, which a JVM test cannot reach, so the decisions it hands over
 * live where one can — and the codec and the state cannot disagree about what a valid ladder is.
 */
internal object WidthLadder {

    /**
     * The most the maximum can be set to.
     *
     * A ceiling on the *setting*, not a preset: it exists so a mistyped `3000` cannot produce a slider
     * whose whole useful range is its first pixel. 200 dp is most of a tablet's short edge in one
     * stroke, which is past anything a pen is for.
     */
    const val HARD_MAX_DP: Float = 200f

    /** The least the maximum can be set to. Below it the slider has no travel above [MIN_WIDTH_DP]. */
    const val MIN_MAX_DP: Float = 1f

    /** Saved sizes are kept to one decimal, so 3.0 and 3.04 are the same rung. */
    fun rounded(widthDp: Float): Float = (widthDp * ROUNDING).roundToInt() / ROUNDING

    /** A saved size, or a width set from the panel, under [maxDp]. */
    fun clamp(widthDp: Float, maxDp: Float): Float = rounded(widthDp).coerceIn(MIN_WIDTH_DP, maxDp)

    fun clampMax(maxDp: Float): Float = rounded(maxDp).coerceIn(MIN_MAX_DP, HARD_MAX_DP)

    /**
     * Ascending, deduplicated, and nothing above [maxDp].
     *
     * ⚠️ Applied when an edit **ends**, never per frame of one. Dragging a saved 3 dp up past a saved
     * 4 dp passes through exactly 4.0, and merging there would delete the size the user was not
     * touching. See `InkUiState.editingWidthSlot`.
     */
    fun normalised(slots: List<Float>, maxDp: Float): List<Float> = slots.map { clamp(it, maxDp) }.distinct().sorted()

    /**
     * What a user typed, or null if it is not a number.
     *
     * Accepts `3,5`: the field shows `"%.1f".format`, which is a comma on a German tablet, so refusing
     * the comma would refuse the field's own text.
     */
    fun parse(text: String): Float? =
        text.trim().removeSuffix("dp").trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }

    /**
     * `3.0`, or `150` from three digits up.
     *
     * ⚠️ The dock's readout is a fixed seven glyphs wide (`DockRows.WidthReadout`), and a maximum that
     * can reach 200 would otherwise render `200.0 dp` as `200.0 …` — the truncation-that-reads-as-a-
     * value that readout's KDoc already records once.
     */
    fun number(widthDp: Float): String = if (widthDp >= THREE_DIGITS) "%.0f".format(widthDp) else "%.1f".format(widthDp)

    fun label(widthDp: Float): String = "${number(widthDp)} dp"

    private const val ROUNDING = 10f

    /** Where `%.1f` would start printing a third integer digit. */
    private const val THREE_DIGITS = 99.95f
}
