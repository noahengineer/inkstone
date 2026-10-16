package dev.inkstone.app.ink

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * *"Bars snap while dragging"* (docs/LAYOUTS.md), as arithmetic. That the editor's drag actually goes
 * through it, and that the guide is drawn, is the emulator walk.
 */
class BarSnapTest {

    private val screen = Size(2560f, 1600f)
    private val edge = 24f
    private val reach = 24f

    @Test
    fun `a bar near the screen's margin snaps onto it`() {
        val snapped = BarSnap.snap(Rect(40f, 500f, 240f, 596f), emptyList(), screen, edge, reach)
        assertEquals(Offset(-16f, 0f), snapped.by)
        assertEquals(edge, snapped.x)
    }

    @Test
    fun `a bar further away than the reach is left where it is`() {
        val snapped = BarSnap.snap(Rect(100f, 500f, 300f, 596f), emptyList(), screen, edge, reach)
        assertEquals(Offset.Zero, snapped.by)
        assertNull(snapped.x)
        assertNull(snapped.y)
    }

    @Test
    fun `a bar lines up with another bar's top edge`() {
        val other = Rect(1000f, 48f, 1400f, 144f)
        val snapped = BarSnap.snap(Rect(600f, 60f, 900f, 156f), listOf(other), screen, edge, reach)
        assertEquals(-12f, snapped.by.y)
        assertEquals(48f, snapped.y)
    }

    @Test
    fun `a bar beside another snaps a margin away from it, not onto it`() {
        val other = Rect(1000f, 48f, 1400f, 144f)
        val snapped = BarSnap.snap(Rect(1430f, 300f, 1700f, 396f), listOf(other), screen, edge, reach)
        assertEquals(other.right + edge, 1430f + snapped.by.x)
    }

    @Test
    fun `a bar near the middle snaps to it`() {
        val snapped = BarSnap.snap(Rect(1090f, 700f, 1490f, 796f), emptyList(), screen, edge, reach)
        assertEquals(1280f, 1090f + snapped.by.x + 200f)
    }

    @Test
    fun `each axis snaps on its own, to the nearest line`() {
        // 10 px from the left margin and 5 px from the bottom one: both, each to its own line.
        val snapped = BarSnap.snap(Rect(34f, 1471f, 234f, 1571f), emptyList(), screen, edge, reach)
        assertEquals(Offset(-10f, 5f), snapped.by)
    }
}
