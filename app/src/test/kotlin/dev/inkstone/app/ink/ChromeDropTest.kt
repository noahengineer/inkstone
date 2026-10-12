package dev.inkstone.app.ink

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Where a chip lands when it is let go — the arithmetic `ChromeContainer` asks on every frame of a drag
 * and commits on lift. Rectangles are root px, as `ItemDrag` records them.
 *
 * What this cannot cover is that the rectangles `ItemDrag` holds are where the chips really are; that
 * is the emulator walk.
 */
class ChromeDropTest {

    private val dock = BarId.Stock(ChromeBar.DOCK)
    private val zoom = BarId.Stock(ChromeBar.ZOOM)

    /** Three 100 px chips in a row from x = 0, 10 px apart. */
    private val row = DropBar(
        dock,
        Rect(0f, 0f, 320f, 50f),
        ChromeArrangement.ROW,
        listOf(
            ChromeItem.TOOL_DRAW to Rect(0f, 0f, 100f, 50f),
            ChromeItem.TOOL_ERASE to Rect(110f, 0f, 210f, 50f),
            ChromeItem.TOOL_LASER to Rect(220f, 0f, 320f, 50f),
        ),
    )

    private fun resolve(at: Offset, dragged: ChromeItem, vararg bars: DropBar): DropTarget =
        ChromeDrop.resolve(at, dragged, bars.toList(), reach = 16f, caret = 3f)

    @Test
    fun `between two chips in a row is between them counted without the chip in the air`() {
        val target = assertIs<DropTarget.Into>(resolve(Offset(215f, 25f), ChromeItem.UNDO, row))
        assertEquals(dock, target.bar)
        assertEquals(2, target.index)
        assertEquals(215f, target.caret.center.x, 0.5f)

        // Dragging Draw itself to the same spot: without Draw, Erase is index 0, so after it is 1.
        val own = assertIs<DropTarget.Into>(resolve(Offset(215f, 25f), ChromeItem.TOOL_DRAW, row))
        assertEquals(1, own.index)
    }

    @Test
    fun `the far ends of a row are its first and last places`() {
        assertEquals(0, assertIs<DropTarget.Into>(resolve(Offset(5f, 25f), ChromeItem.UNDO, row)).index)
        assertEquals(3, assertIs<DropTarget.Into>(resolve(Offset(330f, 25f), ChromeItem.UNDO, row)).index)
    }

    @Test
    fun `a column counts down rather than across`() {
        val column = DropBar(
            dock,
            Rect(0f, 0f, 100f, 170f),
            ChromeArrangement.COLUMN,
            listOf(
                ChromeItem.TOOL_DRAW to Rect(0f, 0f, 100f, 50f),
                ChromeItem.TOOL_ERASE to Rect(0f, 60f, 100f, 110f),
                ChromeItem.TOOL_LASER to Rect(0f, 120f, 100f, 170f),
            ),
        )
        val target = assertIs<DropTarget.Into>(resolve(Offset(50f, 115f), ChromeItem.UNDO, column))
        assertEquals(2, target.index)
        assertEquals(115f, target.caret.center.y, 0.5f)
    }

    @Test
    fun `a grid goes before or after the nearest cell`() {
        val grid = DropBar(
            dock,
            Rect(0f, 0f, 210f, 110f),
            ChromeArrangement.GRID_2,
            listOf(
                ChromeItem.TOOL_DRAW to Rect(0f, 0f, 100f, 50f),
                ChromeItem.TOOL_ERASE to Rect(110f, 0f, 210f, 50f),
                ChromeItem.TOOL_LASER to Rect(0f, 60f, 100f, 110f),
                ChromeItem.TOOL_SELECT to Rect(110f, 60f, 210f, 110f),
            ),
        )
        // Just right of Laser's centre, on the second row: after Laser.
        assertEquals(3, assertIs<DropTarget.Into>(resolve(Offset(70f, 85f), ChromeItem.UNDO, grid)).index)
        // Just left of Select's centre: before Select, which is the same place.
        assertEquals(3, assertIs<DropTarget.Into>(resolve(Offset(140f, 85f), ChromeItem.UNDO, grid)).index)
        assertEquals(0, assertIs<DropTarget.Into>(resolve(Offset(20f, 20f), ChromeItem.UNDO, grid)).index)
    }

    @Test
    fun `just past a bar's end still goes in and well past it is the page`() {
        assertIs<DropTarget.Into>(resolve(Offset(330f, 60f), ChromeItem.UNDO, row))
        assertIs<DropTarget.Page>(resolve(Offset(600f, 400f), ChromeItem.UNDO, row))
    }

    @Test
    fun `where bars overlap the smaller one is the one aimed at`() {
        val single = DropBar(
            BarId.Floating(1),
            Rect(200f, 0f, 300f, 50f),
            ChromeArrangement.ROW,
            listOf(ChromeItem.MORE to Rect(205f, 5f, 295f, 45f)),
        )
        val target = assertIs<DropTarget.Into>(resolve(Offset(280f, 25f), ChromeItem.UNDO, row, single))
        assertEquals(BarId.Floating(1), target.bar)
        assertEquals(1, target.index)
    }

    @Test
    fun `an empty bar takes a chip at its start`() {
        val empty = DropBar(zoom, Rect(500f, 500f, 600f, 550f), ChromeArrangement.ROW, emptyList())
        val target = assertIs<DropTarget.Into>(resolve(Offset(550f, 525f), ChromeItem.UNDO, empty))
        assertEquals(zoom, target.bar)
        assertEquals(0, target.index)
    }

    @Test
    fun `a drop resolved here and committed through the layout puts the chip where the caret was`() {
        val target = assertIs<DropTarget.Into>(resolve(Offset(215f, 25f), ChromeItem.UNDO, row))
        val layout = ChromeLayout().movedTo(ChromeItem.UNDO, target.bar, target.index)
        assertEquals(
            listOf(ChromeItem.TOOL_DRAW, ChromeItem.TOOL_ERASE, ChromeItem.UNDO, ChromeItem.TOOL_LASER),
            layout.itemsOf(dock).take(4),
        )
    }
}
