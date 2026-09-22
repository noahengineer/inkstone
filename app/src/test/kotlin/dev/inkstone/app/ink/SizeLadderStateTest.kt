package dev.inkstone.app.ink

import dev.inkstone.android.ink.InkBrushKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * *"it should not be preset sizes and should not be a set amount you should be ablet to edit every
 * one and you should be able to set the max size"*.
 *
 * What this covers is [InkUiState]'s half — the calls `SizePanel` makes, in the order a user makes
 * them, with the persisted result read back through the real codec. What it cannot cover is that the
 * panel's chips call these; that is the emulator walk.
 */
class SizeLadderStateTest {

    private fun state(vararg slots: Float, max: Float = EditorPrefs.DEFAULT_MAX_WIDTH_DP) =
        InkUiState(EditorPrefs(widthSlots = slots.toList(), maxWidthDp = max))

    private fun InkUiState.reloaded() = EditorPrefsCodec.decode(EditorPrefsCodec.encode(toPrefs()))

    @Test
    fun `tapping a saved size and moving the slider edits that size`() {
        val ui = state(1f, 3f, 8f)
        ui.chooseWidthSlot(1)
        assertEquals(3f, ui.pen.widthDp)
        ui.editWidth(5.5f)
        ui.finishWidthEdit()
        assertEquals(listOf(1f, 5.5f, 8f), ui.widthSlots.toList())
        assertEquals(5.5f, ui.pen.widthDp)
        assertEquals(listOf(1f, 5.5f, 8f), ui.reloaded().widthSlots)
    }

    @Test
    fun `every stock size can be edited, not only the ones a user added`() {
        val ui = InkUiState()
        EditorPrefs.DEFAULT_WIDTH_SLOTS.indices.forEach { index ->
            ui.chooseWidthSlot(index)
            ui.editWidth(ui.widthSlots[index] + 0.5f)
            ui.finishWidthEdit()
        }
        assertEquals(EditorPrefs.DEFAULT_WIDTH_SLOTS.map { it + 0.5f }, ui.widthSlots.toList())
    }

    @Test
    fun `dragging a size through its neighbour does not delete the neighbour`() {
        val ui = state(3f, 4f, 8f)
        ui.chooseWidthSlot(0)
        ui.editWidth(4f) // exactly the neighbour's value, mid-drag
        ui.editWidth(6f)
        ui.finishWidthEdit()
        assertEquals(listOf(4f, 6f, 8f), ui.widthSlots.toList())
    }

    @Test
    fun `tapping another size mid-edit picks the one that was tapped, after the re-sort`() {
        val ui = state(1f, 3f, 8f)
        ui.chooseWidthSlot(0)
        ui.editWidth(5f) // the row reads 5, 3, 8 until the edit ends
        ui.chooseWidthSlot(1) // the 3 on screen in position 1
        assertEquals(3f, ui.pen.widthDp)
        assertEquals(listOf(3f, 5f, 8f), ui.widthSlots.toList())
        assertEquals(0, ui.editingWidthSlot)
    }

    @Test
    fun `tapping the size being edited stops editing, so the slider is free again`() {
        val ui = state(2f, 6f)
        ui.chooseWidthSlot(1)
        ui.chooseWidthSlot(1)
        ui.editWidth(9f)
        assertEquals(listOf(2f, 6f), ui.widthSlots.toList())
        assertEquals(9f, ui.pen.widthDp)
    }

    @Test
    fun `there is no limit on how many sizes can be saved`() {
        val ui = state(1f)
        repeat(25) { i ->
            ui.editWidth(2f + i)
            ui.addWidthSlot()
            ui.chooseWidthSlot(ui.editingWidthSlot) // stop editing the one just added
        }
        assertEquals(26, ui.widthSlots.size)
        assertEquals(26, ui.reloaded().widthSlots.size)
    }

    @Test
    fun `every saved size can be deleted, including the last, and they do not come back`() {
        val ui = state(2f, 4f)
        ui.removeWidthSlot(0)
        ui.chooseWidthSlot(0)
        ui.deleteEditedWidthSlot()
        assertTrue(ui.widthSlots.isEmpty())
        assertEquals(emptyList(), ui.reloaded().widthSlots)
    }

    @Test
    fun `lowering the maximum brings every width above it down to it`() {
        val ui = state(1f, 8f, 16f, 24f)
        ui.selectBrush(InkBrushKind.HIGHLIGHTER) // stock 16 dp
        ui.makeDefaultWidth(20f)
        ui.setMaxWidth(10f)
        assertEquals(10f, ui.maxWidthDp)
        assertEquals(listOf(1f, 8f, 10f), ui.widthSlots.toList())
        assertEquals(10f, ui.pen.widthDp)
        assertEquals(10f, ui.defaultWidthDp)
        val reloaded = ui.reloaded()
        assertEquals(10f, reloaded.maxWidthDp)
        assertTrue(reloaded.pens.values.all { it.widthDp <= 10f })
    }

    @Test
    fun `raising the maximum lets a size go past the old 32 dp and it survives a restart`() {
        val ui = state(4f)
        ui.setMaxWidth(120f)
        ui.chooseWidthSlot(0)
        ui.editWidth(90f)
        ui.finishWidthEdit()
        assertEquals(listOf(90f), ui.widthSlots.toList())
        assertEquals(listOf(90f), ui.reloaded().widthSlots)
        assertEquals(90f, ui.reloaded().pens.getValue(InkBrushKind.FOUNTAIN).widthDp)
    }

    @Test
    fun `the panel cannot set a width past the maximum`() {
        val ui = state(4f, max = 10f)
        ui.editWidth(50f)
        assertEquals(10f, ui.pen.widthDp)
    }
}
