package dev.inkstone.app.ink

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where a contextual toolbar floats — the half of *"it covers the rotation handle"* that is arithmetic.
 *
 * ⚠️ This covers the placement only. That the anchor handed in is the selection's **chrome** (box plus
 * rotate grip) and not its box is `ContextBars` reading `InkUiState.selectionChrome`, which no JVM test
 * can reach; the emulator walk is what checks it.
 */
class ContextBarPlacementTest {

    // A 2560 × 1600 tablet at density 2: 14 dp gap, 22 dp grip reach, 16 dp padding, 78 dp safe top.
    private val gap = 28f
    private val reach = 44f
    private val pad = 32f
    private val safeTop = 156f

    private fun place(top: Float, bottom: Float, left: Float = 1000f, right: Float = 1400f, clearance: Float = reach) =
        ContextBarPlacement.place(
            anchorLeft = left,
            anchorTop = top,
            anchorRight = right,
            anchorBottom = bottom,
            barWidth = 1400f,
            barHeight = 96f,
            viewportWidth = 2560f,
            viewportHeight = 1600f,
            clearance = clearance,
            edgePadding = pad,
            safeTop = safeTop,
        )

    @Test
    fun `above the anchor, the bar ends a whole grip reach short of it`() {
        // The anchor's top is the rotate grip's centre, so a press anywhere in the grip's reach
        // must land on paper rather than on the bar.
        val gripCentreY = 500f
        val at = place(top = gripCentreY, bottom = 900f)
        assertEquals(gripCentreY - reach, at.y + 96f)
    }

    @Test
    fun `the old 14 dp gap left the bar inside the grip's touch target`() {
        // The regression, stated as geometry: with the gap alone the bar's bottom edge sits closer to
        // the grip's centre than a finger's reach, so the grip is under the bar.
        val gripCentreY = 500f
        val at = place(top = gripCentreY, bottom = 900f, clearance = gap)
        assertTrue(gripCentreY - (at.y + 96f) < reach)
    }

    @Test
    fun `with no room above, the bar goes below and clears the bottom grips too`() {
        val at = place(top = 200f, bottom = 700f)
        assertEquals(700f + reach, at.y)
    }

    @Test
    fun `the bar is centred on the anchor and clamped to the screen`() {
        assertEquals(1200f - 700f, place(top = 500f, bottom = 900f).x)
        assertEquals(pad, place(top = 500f, bottom = 900f, left = 0f, right = 100f).x)
        assertEquals(2560f - 1400f - pad, place(top = 500f, bottom = 900f, left = 2400f, right = 2560f).x)
    }

    @Test
    fun `never above the dock, even for an object taller than the screen`() {
        val at = place(top = -400f, bottom = 2000f)
        assertTrue(at.y >= safeTop)
        assertTrue(at.y + 96f <= 1600f - pad)
    }
}
