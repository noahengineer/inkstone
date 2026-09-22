package dev.inkstone.app.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * ⭐ ROADMAP M10 — a colour being dragged from one swatch to another.
 *
 * ## Why this is a shared object and not a `Modifier.dragAndDropSource`
 *
 * The two ends are in different composables that never see each other: the source is the colour
 * panel's twelve-slot grid, the target is the three quick swatches in a toolbar pill, and the pill
 * is a *scrolling, clipped* `GlassBar` several layers up the tree. Compose's drag-and-drop needs a
 * common ancestor to negotiate through, and the only common ancestor here is `InkScreen`'s root
 * `Box` — which is exactly what this stands in for: every swatch reports where it is in **root**
 * coordinates, the drag carries a colour and a finger position, and the drop is a rectangle test.
 *
 * ⚠️ **Targets are keyed by a string, not by their slot index.** The panel's grid and the quick
 * strip both show slot 0 — they are the same twelve slots, the strip is just the first three of
 * them — so an index alone would have two different rectangles on screen claiming it, and the
 * second to compose would win. The key carries where the swatch is as well as which slot it is.
 */
@Stable
class ColorDragState {

    /** The colour under the finger, or null when nothing is being dragged. */
    var colorArgb by mutableStateOf<Int?>(null)
        private set

    /** Which slot it came from, so a drop can swap rather than overwrite. */
    var fromSlot by mutableStateOf(-1)
        private set

    /** The finger, in root coordinates. */
    var position by mutableStateOf(Offset.Zero)
        private set

    private val targets = mutableStateMapOf<String, Target>()

    private class Target(val slot: Int, val bounds: Rect)

    /** Called from a swatch's `onGloballyPositioned`. [key] must be unique per *swatch*, not per slot. */
    fun reportTarget(key: String, slot: Int, bounds: Rect) {
        targets[key] = Target(slot, bounds)
    }

    /** A swatch leaving the composition stops being a drop target. */
    fun forgetTarget(key: String) {
        targets.remove(key)
    }

    fun begin(slot: Int, argb: Int, at: Offset) {
        fromSlot = slot
        colorArgb = argb
        position = at
    }

    fun moveTo(at: Offset) {
        position = at
    }

    /**
     * The slot under the finger right now, or -1.
     *
     * Read live by every quick swatch so the one about to receive the drop can say so — a drag with
     * no landing indicator is a gesture you find out the result of afterwards.
     */
    fun slotUnder(at: Offset = position): Int = targets.values.firstOrNull { it.bounds.contains(at) }?.slot ?: -1

    /** Ends the drag and answers the slot to drop into, or -1 if the finger was over nothing. */
    fun release(): Int {
        val slot = slotUnder()
        colorArgb = null
        fromSlot = -1
        return slot
    }

    fun cancel() {
        colorArgb = null
        fromSlot = -1
    }
}
