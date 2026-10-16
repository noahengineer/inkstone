package dev.inkstone.app.ink

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Saved layouts (docs/LAYOUTS.md) — *"save multiple layouts so that you can switch between them
 * easily"*, with the Default always there to go back to.
 *
 * What this cannot reach: that the editor's chips call these, and that a switch moves the bars on
 * screen. That is the emulator walk.
 */
class SavedLayoutsTest {

    private val arranged = ChromeLayout().withUiScale(1.2f).withPanelSide(PanelSide.LEFT)
    private val other = ChromeLayout().withBrushMenu(false)

    @Test
    fun `changing the Default starts a new layout and leaves the Default as it was`() {
        val saved = SavedLayouts().synced(arranged)
        assertEquals(1, saved.active)
        assertEquals("Layout 1", saved.activeName)
        assertEquals(arranged, saved.layoutOf(1))
        assertEquals(ChromeLayout(), saved.layoutOf(null))
    }

    @Test
    fun `the Default unchanged is not a layout`() {
        assertEquals(SavedLayouts(), SavedLayouts().synced(ChromeLayout()))
    }

    @Test
    fun `a change to a layout of the user's is saved into it, with no new layout`() {
        val saved = SavedLayouts().synced(arranged).synced(other)
        assertEquals(1, saved.layouts.size)
        assertEquals(other, saved.layoutOf(1))
    }

    @Test
    fun `switching between layouts keeps each one's arrangement`() {
        var saved = SavedLayouts().synced(arranged).switchedTo(null).synced(other)
        assertEquals(2, saved.active)
        saved = saved.switchedTo(1)
        assertEquals(arranged, saved.layoutOf(saved.active))
        saved = saved.switchedTo(2)
        assertEquals(other, saved.layoutOf(saved.active))
    }

    @Test
    fun `deleting the layout on screen puts the Default there`() {
        val saved = SavedLayouts().synced(arranged).deleted(1)
        assertNull(saved.active)
        assertTrue(saved.layouts.isEmpty())
    }

    @Test
    fun `a blank name keeps the old one, and a long one is cut`() {
        val saved = SavedLayouts().synced(arranged)
        assertEquals("Layout 1", saved.renamed(1, "   ").activeName)
        assertEquals("Lecture", saved.renamed(1, " Lecture ").activeName)
        assertEquals(SavedLayouts.MAX_NAME, saved.renamed(1, "x".repeat(99)).activeName.length)
    }

    @Test
    fun `a new layout is numbered after the last, not into a gap`() {
        val saved = SavedLayouts().synced(arranged).added(other).added(arranged).deleted(2).added(other)
        assertEquals(listOf(1, 3, 4), saved.layouts.map { it.id })
    }

    @Test
    fun `a round trip through the settings table keeps every layout, its name and which is on screen`() {
        val saved = SavedLayouts().synced(arranged).added(other, "Lecture").switchedTo(1)
        val read = SavedLayoutsCodec.decode(SavedLayoutsCodec.encode(saved), working = arranged)
        assertEquals(saved, read)
    }

    @Test
    fun `a deleted layout's rows are left in the table and not read back`() {
        // `putAll` cannot remove a row, so the list is what decides what exists — the floats rule.
        val two = SavedLayouts().synced(arranged).added(other, "Gone")
        val after = two.deleted(2)
        val table = SavedLayoutsCodec.encode(two) + SavedLayoutsCodec.encode(after)
        // Deleting the layout on screen puts the Default there (`InkUiState.deleteLayout`).
        assertEquals(after, SavedLayoutsCodec.decode(table, working = ChromeLayout()))
    }

    @Test
    fun `the layout on screen wins over a stale copy of it`() {
        // A build that knew nothing of saved layouts may have rearranged the screen since.
        val table = SavedLayoutsCodec.encode(SavedLayouts().synced(arranged))
        val read = SavedLayoutsCodec.decode(table, working = other)
        assertEquals(other, read.layoutOf(1))
    }

    @Test
    fun `a table from before saved layouts keeps an arranged screen as My layout`() {
        val read = SavedLayoutsCodec.decode(emptyMap(), working = arranged)
        assertEquals("My layout", read.activeName)
        assertEquals(arranged, read.layoutOf(read.active))
        assertEquals(SavedLayouts(), SavedLayoutsCodec.decode(emptyMap(), working = ChromeLayout()))
    }

    @Test
    fun `every key a saved layout writes is under layouts`() {
        val keys = SavedLayoutsCodec.encode(SavedLayouts().synced(arranged)).keys
        assertTrue(keys.all { it.startsWith("layouts.") }, keys.filterNot { it.startsWith("layouts.") }.toString())
    }

    @Test
    fun `the whole editor's settings round trip with saved layouts in them`() {
        val saved = SavedLayouts().synced(arranged).added(other, "Lecture")
        val prefs = EditorPrefs(layout = other, savedLayouts = saved)
        assertEquals(prefs, EditorPrefsCodec.decode(EditorPrefsCodec.encode(prefs)))
    }
}
