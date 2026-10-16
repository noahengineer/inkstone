package dev.inkstone.app.ink

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The right-hand panel column — *"there is just so much overlapping still and that is what i wanted to
 * avoid"* (docs/UI-RAILS.md §3) — as arithmetic.
 *
 * What this cannot cover is that every panel actually joins the column (`Modifier.docked`) and that the
 * column's top and bottom come from the bars in the corners; that is the emulator walk, with the
 * library, the colour panel, the diagnostics and the calculator all open at once.
 */
class PanelDockTest {

    // The emulator in landscape, in px at density 2: under the record chip, above the zoom bar.
    private val area = IntRect(0, 208, 2536, 1400)
    private val gap = 16

    private fun rects(sizes: List<IntSize>): List<IntRect> =
        DockFlow.place(sizes, area, gap).zip(sizes) { at, size -> IntRect(at, size) }

    @Test
    fun `panels stack down the right edge, one under the other`() {
        val placed = DockFlow.place(listOf(IntSize(480, 300), IntSize(480, 400)), area, gap)
        assertEquals(IntOffset(2536 - 480, 208), placed[0])
        assertEquals(IntOffset(2536 - 480, 208 + 300 + gap), placed[1])
    }

    @Test
    fun `a panel that does not fit starts a column to the left`() {
        val placed = DockFlow.place(listOf(IntSize(480, 700), IntSize(560, 900)), area, gap)
        assertEquals(IntOffset(2536 - 480 - gap - 560, 208), placed[1])
    }

    @Test
    fun `a short panel slips under an earlier column that has room`() {
        // Library (short), colour (tall), size (short): the size panel goes back under the library.
        val placed = DockFlow.place(listOf(IntSize(480, 500), IntSize(560, 1040), IntSize(480, 600)), area, gap)
        assertEquals(placed[0].x, placed[2].x)
        assertEquals(208 + 500 + gap, placed[2].y)
    }

    @Test
    fun `a wide panel never slips under a narrower column, where it would reach into the next one`() {
        val placed = DockFlow.place(listOf(IntSize(480, 300), IntSize(560, 1040), IntSize(640, 300)), area, gap)
        assertTrue(placed[2].x < placed[1].x, "the wide panel went into a column of its own")
    }

    @Test
    fun `a left-hand column mirrors the right-hand one`() {
        // docs/LAYOUTS.md: the column can stand against either edge. Here it starts beside the tools.
        val left = IntRect(272, 208, 2536, 1400)
        val placed = DockFlow.place(listOf(IntSize(480, 700), IntSize(560, 900), IntSize(480, 300)), left, gap, true)
        assertEquals(IntOffset(272, 208), placed[0])
        assertEquals(IntOffset(272 + 480 + gap, 208), placed[1])
        assertEquals(IntOffset(272, 208 + 700 + gap), placed[2], "the short one slips back under the first")
    }

    @Test
    fun `a panel's corner grows it by the mean of how much wider and taller it is pulled`() {
        val size = Size(400f, 600f)
        // Pulled 100 px right and 150 px down: a quarter wider and a quarter taller.
        assertEquals(1.25f, PanelPlacement.resized(1f, size, Offset(100f, 150f), fromLeft = false))
        // On a bottom-left grip a pull to the right is toward the panel's middle: a quarter narrower.
        assertEquals(0.875f, PanelPlacement.resized(1f, size, Offset(100f, 0f), fromLeft = true))
        val crushed = PanelPlacement.resized(1f, size, Offset(-900f, -900f), fromLeft = false)
        assertEquals(ChromeLayout.PANEL_SCALE.start, crushed)
    }

    @Test
    fun `the column keeps the pen's row at full height while it folds away`() {
        // docs/LAYOUTS.md §3: the row folds with an animation when something is selected, and a column
        // measuring from its last sliver rose to the top of the screen on every selection.
        val geometry = ChromeGeometry()
        val row = BarId.Stock(ChromeBar.DRAW)
        val full = androidx.compose.ui.geometry.Rect(272f, 96f, 1600f, 192f)
        geometry.record(row, full)
        listOf(60f, 20f, 2f).forEach { h -> geometry.record(row, full.copy(bottom = full.top + h)) }
        geometry.record(row, full.copy(right = full.left, bottom = full.top))
        assertTrue(full in geometry.pillsWithFoldedRow())
        // A row that has moved is a different row, at whatever height it is.
        val moved = full.translate(0f, 400f).copy(bottom = full.top + 400f + 40f)
        geometry.record(row, moved)
        assertTrue(moved in geometry.pillsWithFoldedRow() && full !in geometry.pillsWithFoldedRow())
    }

    @Test
    fun `nothing placed ever overlaps anything else`() {
        // Every panel the editor can open, at their real sizes in px.
        val sizes =
            listOf(IntSize(480, 520), IntSize(560, 1040), IntSize(600, 760), IntSize(640, 900), IntSize(496, 1064))
        val placed = rects(sizes)
        placed.forEachIndexed { i, a ->
            placed.drop(i + 1).forEach { b -> assertTrue(!a.overlaps(b), "$a overlaps $b") }
            assertTrue(a.top >= area.top && a.right <= area.right, "$a leaves the column's area")
        }
    }
}
