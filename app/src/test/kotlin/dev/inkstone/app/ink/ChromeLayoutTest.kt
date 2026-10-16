package dev.inkstone.app.ink

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * *"make the UI more dynamic so you can like change everything and resize the things"*, then
 * *"rearrange buttons … change the size of specific panels … a grid or vertically"* with buttons
 * movable **anywhere on screen** — the arrangement as a value, and as the strings it is stored in.
 *
 * What this covers: that the stock layout is the design (so nobody who never opens the editor sees a
 * change), that a chip has exactly one bar wherever it is moved, that a floating bar lives exactly as
 * long as it holds something, that every edit survives the codec, and that the pen and the way back
 * into the editor stay reachable. What it cannot cover is that the handles and chips in `ChromeHost` /
 * `ChromeContainer` call these; that is the emulator walk (docs/CUSTOM-LAYOUT.md).
 */
class ChromeLayoutTest {

    private val dock = BarId.Stock(ChromeBar.DOCK)
    private val draw = BarId.Stock(ChromeBar.DRAW)
    private val history = BarId.Stock(ChromeBar.HISTORY)
    private val pages = BarId.Stock(ChromeBar.PAGES)
    private val zoom = BarId.Stock(ChromeBar.ZOOM)

    @Test
    fun `the stock layout is the design's order with the design's dividers`() {
        val stock = ChromeLayout()
        assertEquals(
            listOf("Draw", "Erase", "Laser", "|", "Select", "|", "Text", "Image", "Elements", "Write", "|", "Ruler"),
            withDividers(stock.visibleItemsOf(dock)),
        )
        assertEquals(
            listOf(
                "Fountain", "Ballpoint", "Pencil", "Highlighter", "|", "Width slider", "Width", "|", "Colours",
                "|", "Snap", "Arrow", "Grid",
            ),
            withDividers(stock.visibleItemsOf(draw)),
        )
        assertTrue(stock.floating.isEmpty())
        assertTrue(ChromeBar.entries.all { stock.bar(BarId.Stock(it)) == BarLayout() })
    }

    @Test
    fun `the stock shapes are a column of tools with the pen's row beside it and pills elsewhere`() {
        // docs/UI-RAILS.md — the corner "L": "at least move the tools bar to the top left corner".
        val stock = ChromeLayout()
        assertEquals(ChromeArrangement.COLUMN, stock.arrangement(dock))
        assertEquals(ChromeArrangement.ROW, stock.arrangement(draw))
        assertEquals(ChromeArrangement.ROW, stock.arrangement(pages))
        assertEquals(ChromeArrangement.ROW, stock.arrangement(history))
        assertEquals(ChromeArrangement.ROW, stock.arrangement(zoom))
        val floated = stock.floated(ChromeItem.UNDO, ChromePosition(0.5f, 0.5f))
        val own = floated.floating.single()
        assertEquals(ChromeArrangement.ROW, floated.arrangement(own), "a bar of your own starts as a pill")
    }

    @Test
    fun `the pen's panel is a line per group with the four brushes two by two`() {
        val lines = PanelShape.lines(ChromeLayout().visibleItemsOf(draw)).map { line ->
            (if (line.startsGroup) "+" else "") + line.items.joinToString(" ") { it.label }
        }
        assertEquals(
            listOf("+Fountain Ballpoint", "Pencil Highlighter", "+Width slider Width", "+Colours", "+Snap Arrow Grid"),
            lines,
        )
    }

    @Test
    fun `a table written before the rails reads as the rails, unless a bar was moved by hand`() {
        // What every device that had opened a note holds: each stock bar written as stock, `ROW` and all,
        // because a row was the only stock there was — and no `ui.version`.
        val untouched = ChromeBar.entries.associate { "ui.bar.${it.name}" to ",,1.0,ROW" }
        assertEquals(ChromeLayout(), ChromeLayoutCodec.decode(untouched))
        // The same table with the dock dragged somewhere: that row was arranged by hand and stays a row.
        val moved = untouched + ("ui.bar.DOCK" to "0.3,0.9,1.0,ROW")
        val read = ChromeLayoutCodec.decode(moved)
        assertEquals(ChromeArrangement.ROW, read.arrangement(dock))
        assertEquals(ChromePosition(0.3f, 0.9f), read.bar(dock).position)
        assertEquals(ChromeLayout(), read.copy(bars = read.bars - dock), "every other bar reads as stock")
        // A shape chosen in the old editor is kept.
        val gridded = untouched + ("ui.bar.ZOOM" to ",,1.0,GRID_2")
        assertEquals(ChromeArrangement.GRID_2, ChromeLayoutCodec.decode(gridded).arrangement(zoom))
    }

    @Test
    fun `a chip moved into another bar leaves its own and is in exactly one`() {
        val layout = ChromeLayout().movedTo(ChromeItem.UNDO, dock, 0)
        assertEquals(ChromeItem.UNDO, layout.itemsOf(dock).first())
        assertEquals(listOf(ChromeItem.REDO), layout.itemsOf(history))
        assertEquals(dock, layout.owner(ChromeItem.UNDO))
        val everywhere = layout.chipBars.flatMap { layout.itemsOf(it) }
        assertEquals(ChromeItem.entries.size, everywhere.size)
        assertEquals(ChromeItem.entries.toSet(), everywhere.toSet())
    }

    @Test
    fun `a chip moved away and back leaves a layout equal to one never touched`() {
        val away = ChromeLayout().movedTo(ChromeItem.FIT_WIDTH, pages, 2)
        val back = away.movedTo(ChromeItem.FIT_WIDTH, zoom, 1)
        assertEquals(ChromeLayout(), back)
        val reordered = ChromeLayout().movedTo(ChromeItem.TOOL_RULER, dock, 0)
        val last = ChromeLayout.stockOrder(ChromeBar.DOCK).lastIndex
        assertEquals(ChromeLayout(), reordered.movedTo(ChromeItem.TOOL_RULER, dock, last))
    }

    @Test
    fun `a chip dropped on the page is a bar of its own until it is moved out again`() {
        val position = ChromePosition(0.2f, 0.7f)
        val floated = ChromeLayout().floated(ChromeItem.PAGE_DELETE, position)
        val bar = floated.floating.single()
        assertEquals(listOf(ChromeItem.PAGE_DELETE), floated.itemsOf(bar))
        assertEquals(position, floated.bar(bar).position)
        assertFalse(ChromeItem.PAGE_DELETE in floated.itemsOf(pages))

        val joined = floated.movedTo(ChromeItem.FIT_PAGE, bar, 1)
        assertEquals(listOf(ChromeItem.PAGE_DELETE, ChromeItem.FIT_PAGE), joined.itemsOf(bar))

        val emptied = joined.movedTo(ChromeItem.PAGE_DELETE, pages, 5).movedTo(ChromeItem.FIT_PAGE, zoom, 2)
        assertTrue(emptied.floating.isEmpty(), "an emptied floating bar is gone")
        assertTrue(emptied.bars.keys.none { it is BarId.Floating }, "and so are its position and size")
        assertEquals(ChromeLayout(), emptied)
    }

    @Test
    fun `floating a chip out of a floating bar of its own moves the bar rather than leaving a husk`() {
        val once = ChromeLayout().floated(ChromeItem.UNDO, ChromePosition(0f, 0f))
        val twice = once.floated(ChromeItem.UNDO, ChromePosition(1f, 1f))
        assertEquals(1, twice.floating.size)
        assertEquals(ChromePosition(1f, 1f), twice.bar(twice.floating.single()).position)
    }

    @Test
    fun `the pen and the way into the editor cannot be hidden but can be moved`() {
        val hidden = ChromeItem.entries.fold(ChromeLayout()) { acc, item -> acc.toggled(item) }
        assertEquals(listOf(ChromeItem.TOOL_DRAW), hidden.visibleItemsOf(dock))
        assertEquals(listOf(ChromeItem.MORE), hidden.visibleItemsOf(zoom))
        assertTrue(hidden.visibleItemsOf(history).isEmpty(), "every other chip can go, whole bars included")
        val moved = hidden.floated(ChromeItem.MORE, ChromePosition(0.5f, 0.5f))
        assertEquals(listOf(ChromeItem.MORE), moved.visibleItemsOf(moved.floating.single()))
    }

    @Test
    fun `a chip a later build adds appears in its home beside its stock neighbour`() {
        // A stored order written before TOOL_LASER existed, with junk in it.
        val stored = listOf(
            ChromeItem.TOOL_RULER,
            ChromeItem.TOOL_DRAW,
            ChromeItem.TOOL_ERASE,
            ChromeItem.TOOL_DRAW,
        )
        val complete = ChromeLayout.completeOrder(ChromeBar.DOCK, stored)
        assertEquals(ChromeItem.entries.filter { it.home == ChromeBar.DOCK }.toSet(), complete.toSet())
        assertEquals(complete.size, complete.distinct().size)
        assertEquals(complete.indexOf(ChromeItem.TOOL_ERASE) + 1, complete.indexOf(ChromeItem.TOOL_LASER))
        assertEquals(0, complete.indexOf(ChromeItem.TOOL_RULER), "the user's own order is kept")
    }

    @Test
    fun `a bar remembers its own arrangement`() {
        val layout = ChromeLayout().arranged(dock, ChromeArrangement.ROW).arranged(pages, ChromeArrangement.COLUMN)
        assertEquals(ChromeArrangement.ROW, layout.arrangement(dock))
        assertEquals(ChromeArrangement.COLUMN, layout.arrangement(pages))
        assertEquals(ChromeArrangement.ROW, layout.arrangement(zoom))
        // A bar set back to its own stock shape is a bar never touched.
        assertEquals(
            ChromeLayout(),
            layout.arranged(dock, ChromeArrangement.COLUMN).arranged(pages, ChromeArrangement.ROW),
        )
        assertEquals(ChromeLayout(), ChromeLayout().arranged(history, ChromeArrangement.ROW))
    }

    @Test
    fun `sizing one bar sizes that bar and no other`() {
        // "click on a panel (like the undo panel) and be able to change the size of only that"
        val layout = ChromeLayout().withUiScale(1.5f).scaled(history, 0.8f)
        assertEquals(1.2f, layout.scaleOf(history), 0.0001f)
        ChromeBar.entries.map(BarId::Stock).filter { it != history }.forEach { other ->
            assertEquals(1.5f, layout.scaleOf(other), 0.0001f, "$other changed with the Undo bar")
        }
        // The pen settings row used to share the tool bar's size; it has its own now.
        val pen = ChromeLayout().scaled(draw, 1.4f)
        assertEquals(1.4f, pen.scaleOf(draw), 0.0001f)
        assertEquals(1f, pen.scaleOf(dock), 0.0001f)
    }

    @Test
    fun `resetting a bar puts back that bar alone`() {
        val custom = ChromeLayout()
            .scaled(history, 1.8f)
            .placed(history, ChromePosition(0.5f, 0.5f))
            .arranged(history, ChromeArrangement.COLUMN)
            .scaled(zoom, 0.7f)
        val reset = custom.reset(history)
        assertEquals(BarLayout(), reset.bar(history))
        assertEquals(0.7f, reset.bar(zoom).scale)
        val floating = ChromeLayout().floated(ChromeItem.UNDO, ChromePosition(0.2f, 0.3f))
        val bar = floating.floating.single()
        val shrunk = floating.scaled(bar, 0.6f).arranged(bar, ChromeArrangement.GRID_2)
        assertEquals(
            BarLayout(position = ChromePosition(0.2f, 0.3f)),
            shrunk.reset(bar).bar(bar),
            "a floating bar keeps its place",
        )
    }

    @Test
    fun `each panel has a size of its own on top of the overall one`() {
        val layout = ChromeLayout().withUiScale(1.25f).withPanelScale(ChromePanel.COLOUR, 1.6f)
        assertEquals(2f, layout.panelScale(ChromePanel.COLOUR), 0.0001f)
        assertEquals(1.25f, layout.panelScale(ChromePanel.WIDTH), 0.0001f)
        assertEquals(ChromeLayout().withUiScale(1.25f), layout.withPanelScale(ChromePanel.COLOUR, 1f))
    }

    @Test
    fun `a bar on the bottom edge in landscape is on the bottom edge in portrait`() {
        val edge = 12f
        // Dragged to the bottom edge of a 2560 x 1600 screen, then the tablet is turned.
        val position = ChromePosition.at(500f, 1600f - edge - 60f, 600f, 60f, 2560f, 1600f, edge)
        val (x, y) = position.topLeft(600f, 60f, 1600f, 2560f, edge)
        assertEquals(2560f - edge - 60f, y, 0.01f)
        assertTrue(x in edge..(1600f - edge - 600f))
    }

    @Test
    fun `a position always lands on screen and a bar wider than the screen sits at the edge`() {
        assertEquals(ChromePosition(0f, 1f), ChromePosition.at(-400f, 9000f, 100f, 50f, 1000f, 800f, 12f))
        val (x, y) = ChromePosition(1f, 1f).topLeft(1200f, 50f, 1000f, 800f, 12f)
        assertEquals(12f, x)
        assertEquals(800f - 12f - 50f, y)
    }

    @Test
    fun `sizes are held inside their ranges`() {
        val layout = ChromeLayout().withUiScale(9f).withPanelScale(ChromePanel.MORE, 0f).scaled(dock, 100f)
        assertEquals(ChromeLayout.UI_SCALE.endInclusive, layout.uiScale)
        assertEquals(ChromeLayout.PANEL_SCALE.start, layout.panelScales[ChromePanel.MORE])
        assertEquals(ChromeLayout.BAR_SCALE.endInclusive, layout.bar(dock).scale)
    }

    @Test
    fun `every edit survives the codec through the editor's own prefs`() {
        val custom = customised()
        assertEquals(custom, ChromeLayoutCodec.decode(ChromeLayoutCodec.encode(custom)))
        // As the editor holds it: a changed layout is always one of the saved ones (`SavedLayouts.synced`).
        val prefs = EditorPrefs(layout = custom, savedLayouts = SavedLayouts().synced(custom))
        assertEquals(prefs, EditorPrefsCodec.decode(EditorPrefsCodec.encode(prefs)))
    }

    @Test
    fun `reset overwrites every stored key so nothing old comes back on the next launch`() {
        // `SettingStore.putAll` leaves a key it is not given alone; this is what the table holds after
        // a customised layout is followed by Reset.
        val table = ChromeLayoutCodec.encode(customised()) + ChromeLayoutCodec.encode(ChromeLayout())
        assertEquals(ChromeLayout(), ChromeLayoutCodec.decode(table))
    }

    @Test
    fun `a deleted floating bar's rows are left in the table and never read again`() {
        val two = ChromeLayout()
            .floated(ChromeItem.UNDO, ChromePosition(0f, 0f))
            .floated(ChromeItem.REDO, ChromePosition(1f, 1f))
        // Put Undo back: its floating bar is deleted, but `putAll` cannot delete its rows.
        val one = two.movedTo(ChromeItem.UNDO, history, 0)
        val table = ChromeLayoutCodec.encode(two) + ChromeLayoutCodec.encode(one)
        val read = ChromeLayoutCodec.decode(table)
        assertEquals(one, read)
        assertEquals(history, read.owner(ChromeItem.UNDO))
    }

    @Test
    fun `a chip a damaged table names twice has one owner`() {
        val table = mapOf("ui.items.DOCK" to "UNDO,TOOL_DRAW", "ui.items.ZOOM" to "UNDO,MORE")
        val layout = ChromeLayoutCodec.decode(table)
        assertEquals(dock, layout.owner(ChromeItem.UNDO))
        val everywhere = layout.chipBars.flatMap { layout.itemsOf(it) }
        assertEquals(everywhere.size, everywhere.distinct().size)
    }

    @Test
    fun `damaged rows decode to defaults and never throw`() {
        val table = mapOf(
            "ui.scale" to "huge",
            "ui.bar.DOCK" to "0.5",
            "ui.bar.ZOOM" to ",,banana,SIDEWAYS",
            "ui.items.DOCK" to "TOOL_SPRAYCAN,TOOL_RULER,,TOOL_DRAW",
            "ui.floats" to "x,-1,3",
            "ui.hidden" to "TOOL_DRAW,MORE,NOT_A_CHIP",
        )
        val layout = ChromeLayoutCodec.decode(table)
        assertEquals(1f, layout.uiScale)
        assertEquals(BarLayout(), layout.bar(dock), "half a position is no position")
        assertEquals(BarLayout(), layout.bar(zoom))
        assertEquals(ChromeItem.TOOL_RULER, layout.itemsOf(dock).first())
        assertTrue(layout.floating.isEmpty(), "a listed floating bar with no chips is no bar")
        assertFalse(layout.isHidden(ChromeItem.TOOL_DRAW))
        assertFalse(layout.isHidden(ChromeItem.MORE))
    }

    @Test
    fun `the first editor's row orders and panel size are read`() {
        // Exactly the row the emulator's table held after the first editor had been used.
        val order = "TOOL_DRAW,TOOL_RULER,TOOL_ERASE,TOOL_LASER,TOOL_SELECT,TOOL_TEXT,TOOL_IMAGE,TOOL_ELEMENTS"
        val table = mapOf("ui.order.TOOLS" to order, "ui.panelScale" to "1.2")
        val layout = ChromeLayoutCodec.decode(table)
        assertEquals(listOf(ChromeItem.TOOL_DRAW, ChromeItem.TOOL_RULER), layout.itemsOf(dock).take(2))
        assertEquals(1.2f, layout.panelScales[ChromePanel.COLOUR])
        assertEquals(null, layout.panelScales[ChromePanel.SELECTION], "the old slider never sized the contextual bars")
    }

    @Test
    fun `a comma-decimal locale can read back what it wrote`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val custom = customised()
            assertEquals(custom, ChromeLayoutCodec.decode(ChromeLayoutCodec.encode(custom)))
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun customised(): ChromeLayout = ChromeLayout()
        .withUiScale(1.25f)
        .withPanelScale(ChromePanel.COLOUR, 0.8f)
        .withPanelScale(ChromePanel.SELECTION, 1.5f)
        .placed(dock, ChromePosition(0.1f, 0.9f))
        .scaled(dock, 0.75f)
        // ⚠️ `ROW` on purpose: the M10 dock, chosen, is no longer the stock and must survive the codec.
        .arranged(dock, ChromeArrangement.ROW)
        .arranged(draw, ChromeArrangement.GRID_2)
        .arranged(zoom, ChromeArrangement.PANEL)
        .placed(pages, ChromePosition(0.5f, 0f))
        .scaled(zoom, 1.5f)
        .movedTo(ChromeItem.TOOL_RULER, dock, 0)
        .movedTo(ChromeItem.UNDO, dock, 1)
        .movedTo(ChromeItem.BRUSH_PENCIL, draw, 0)
        .floated(ChromeItem.PAGE_DELETE, ChromePosition(0.3f, 0.6f))
        .floated(ChromeItem.FIT_WIDTH, ChromePosition(0.9f, 0.1f))
        .let { it.arranged(it.floating.last(), ChromeArrangement.COLUMN) }
        .toggled(ChromeItem.TOOL_LASER)
        .toggled(ChromeItem.PAGE_SHARE)
        .toggled(ChromeItem.REDO)

    @Test
    fun `the panel column, floating panels and both style switches survive the codec`() {
        // docs/LAYOUTS.md: everything a saved layout is made of has to come back.
        val layout = ChromeLayout()
            .withPanelSide(PanelSide.LEFT)
            .panelMoved(DockSlot.CALCULATOR, -(DockSlot.entries.size - 1))
            .panelPlaced(DockSlot.COLOUR, ChromePosition(0.25f, 0.75f))
            .withBrushMenu(false)
            .withCardStyle(CardStyle.ROW)
            .withPanelScale(ChromePanel.ELEMENTS, 1.4f)
        assertEquals(layout, ChromeLayoutCodec.decode(ChromeLayoutCodec.encode(layout)))
        assertEquals(DockSlot.CALCULATOR, layout.orderedPanels.first())
    }

    @Test
    fun `a table from before the panel settings is the stock column with the stock styles`() {
        val old = ChromeLayoutCodec.encode(ChromeLayout())
            .filterKeys { !it.startsWith("ui.panels") && !it.startsWith("ui.style") }
        assertEquals(ChromeLayout(), ChromeLayoutCodec.decode(old))
    }

    @Test
    fun `the calculator never floats through the layout, because its window has a place of its own`() {
        assertEquals(ChromeLayout(), ChromeLayout().panelPlaced(DockSlot.CALCULATOR, ChromePosition(0.5f, 0.5f)))
    }

    @Test
    fun `moving a panel past either end of the column leaves it at that end`() {
        val first = ChromeLayout().panelMoved(DockSlot.ELEMENTS, -1)
        assertEquals(ChromeLayout(), first)
        val order = ChromeLayout().panelMoved(DockSlot.COLOUR, 9).orderedPanels
        assertEquals(DockSlot.COLOUR, order.last())
        assertEquals(DockSlot.entries.size, order.size)
    }

    @Test
    fun `an order naming only some panels still has every panel, the rest after`() {
        val layout = ChromeLayout(panelOrder = listOf(DockSlot.SIZE))
        assertEquals(listOf(DockSlot.SIZE) + DockSlot.entries.filter { it != DockSlot.SIZE }, layout.orderedPanels)
    }

    /** The bar as a user sees it: labels, with `|` wherever a divider is drawn. */
    private fun withDividers(items: List<ChromeItem>): List<String> = buildList {
        items.forEachIndexed { index, item ->
            if (index > 0 && items[index - 1].group != item.group) add("|")
            add(item.label)
        }
    }
}
