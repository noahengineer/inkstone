package dev.inkstone.app.ink

import androidx.compose.runtime.Immutable

/**
 * ⭐ *"make the UI more dynamic so you can like change everything and resize the things"* — and then
 * *"you should be able to rearrange buttons, change the size of specific panels … and make a panel
 * display button, slider whatever in a grid or vertically"*, with buttons movable **anywhere on
 * screen**.
 *
 * One immutable value, persisted beside the palette in [EditorPrefs] and edited only in the layout
 * editor. `ChromeLayout()` **is** the stock layout — the M10 design exactly as it shipped — so a user
 * who never opens the editor sees nothing change, and *Reset* is `ChromeLayout()`.
 *
 * ## The model: every chip has an owner
 *
 * A chip ([ChromeItem]) lives in exactly one bar ([BarId]): its [ChromeItem.home] unless [contents]
 * says otherwise. A bar is either one of the stock bars ([ChromeBar]) or a **floating bar** the user
 * made by dropping a chip on the page, which exists exactly as long as it holds something.
 *
 * ⚠️ **One owner, always.** [movedTo] removes a chip from wherever it was before inserting it, and
 * [owner] is the *first* list that names it — so a damaged table that names a chip twice still has one
 * answer, and the codec keeps the first and drops the rest.
 *
 * ## What is stored, and in what space
 *
 * - **A position is a fraction of the free space, not a coordinate** ([ChromePosition]). A dock
 *   dragged to the bottom of a landscape tablet is at y ≈ 730 dp, which in portrait is the *middle*.
 *   A fraction is on the bottom edge in both, and cannot be off screen in either.
 * - **Null is "where the design puts it"** — the stock slot, which `ChromeHost` computes from the
 *   neighbours' measured sizes. A floating bar always has a position: it has no stock slot.
 * - **A stock bar's contents are stored only once they differ from stock** ([normalised]), so a chip
 *   dragged away and back leaves a layout equal to one never touched. A chip a later build adds goes
 *   to its home, beside its stock neighbour ([completeOrder]) — the `ObjectFormat.extras` argument.
 *
 * @param uiScale every piece of chrome, together.
 * @param panelScales one size per summoned panel and contextual bar, on top of [uiScale].
 * @param bars position, size and arrangement per bar; a stock bar with no entry is stock.
 * @param contents bars whose chips differ from stock, and every floating bar. Read through [itemsOf].
 * @param hidden chips the user has hidden. Never a chip that is not [ChromeItem.hideable].
 */
@Immutable
data class ChromeLayout(
    val uiScale: Float = 1f,
    val panelScales: Map<ChromePanel, Float> = emptyMap(),
    val bars: Map<BarId, BarLayout> = emptyMap(),
    val contents: Map<BarId, List<ChromeItem>> = emptyMap(),
    val hidden: Set<ChromeItem> = emptySet(),
) {

    fun bar(id: BarId): BarLayout = bars[id] ?: BarLayout()

    /** What [id] is drawn at: the overall size times its own — the dock's own for its second row. */
    fun scaleOf(id: BarId): Float = uiScale * bar(id.sizedBy()).scale

    fun panelScale(panel: ChromePanel): Float = uiScale * (panelScales[panel] ?: 1f)

    /** The floating bars, oldest first — which is also the order they are drawn in. */
    val floating: List<BarId.Floating>
        get() = contents.keys.filterIsInstance<BarId.Floating>().sortedBy { it.n }

    /** Every bar that can hold chips: the stock ones, then the floating ones. */
    val chipBars: List<BarId> get() = ChromeBar.entries.filter { it.holdsChips }.map(BarId::Stock) + floating

    /** The bar [item] is in. */
    fun owner(item: ChromeItem): BarId =
        contents.entries.firstOrNull { item in it.value }?.key ?: BarId.Stock(item.home)

    /** Every chip in [id], hidden ones included, in the user's order. */
    fun itemsOf(id: BarId): List<ChromeItem> {
        val explicit = contents[id]?.filter { owner(it) == id }?.distinct()
        return when (id) {
            is BarId.Floating -> explicit.orEmpty()
            is BarId.Stock -> completeOrder(id.bar, explicit) { owner(it) == id }
        }
    }

    /** The chips of [id] that are drawn outside the layout editor. */
    fun visibleItemsOf(id: BarId): List<ChromeItem> = itemsOf(id).filter { it !in hidden }

    fun isHidden(item: ChromeItem): Boolean = item in hidden

    /** Hide [item], or show it again. A chip that may not be hidden is left alone. */
    fun toggled(item: ChromeItem): ChromeLayout = when {
        !item.hideable -> this
        item in hidden -> copy(hidden = hidden - item)
        else -> copy(hidden = hidden + item)
    }

    /**
     * [item] moved into [target] at [index] of that bar's [itemsOf] order **with [item] taken out** —
     * which is what a drop measures against, since the chip being dragged is not where it will land.
     */
    fun movedTo(item: ChromeItem, target: BarId, index: Int): ChromeLayout {
        if (target is BarId.Stock && !target.bar.holdsChips) return this
        val lists = chipBars.associateWith { itemsOf(it).toMutableList() }.toMutableMap()
        lists.values.forEach { it.remove(item) }
        val into = lists.getOrPut(target) { mutableListOf() }
        into.add(index.coerceIn(0, into.size), item)
        return normalised(lists)
    }

    /** [item] lifted out onto the page as a floating bar of its own, at [position]. */
    fun floated(item: ChromeItem, position: ChromePosition): ChromeLayout {
        val id = BarId.Floating((floating.maxOfOrNull { it.n } ?: 0) + 1)
        val lists = chipBars.associateWith { itemsOf(it).toMutableList() }.toMutableMap()
        lists.values.forEach { it.remove(item) }
        lists[id] = mutableListOf(item)
        return copy(bars = bars + (id to BarLayout(position = position))).normalised(lists)
    }

    /** [id] put at [position], or back in its stock slot for null. A floating bar always keeps one. */
    fun placed(id: BarId, position: ChromePosition?): ChromeLayout {
        if (id is BarId.Floating && position == null) return this
        return withBar(id, bar(id).copy(position = position))
    }

    fun scaled(id: BarId, scale: Float): ChromeLayout =
        withBar(id.sizedBy(), bar(id.sizedBy()).copy(scale = scale.coerceIn(BAR_SCALE)))

    fun arranged(id: BarId, arrangement: ChromeArrangement): ChromeLayout =
        withBar(id, bar(id).copy(arrangement = arrangement))

    fun withUiScale(scale: Float): ChromeLayout = copy(uiScale = scale.coerceIn(UI_SCALE))

    fun withPanelScale(panel: ChromePanel, scale: Float): ChromeLayout {
        val clamped = scale.coerceIn(PANEL_SCALE)
        return copy(panelScales = if (clamped == 1f) panelScales - panel else panelScales + (panel to clamped))
    }

    /**
     * [lists] as stored: a stock bar only when storing it says something [itemsOf] would not work out
     * on its own, a floating bar only while it holds something — and an emptied floating bar's position
     * and size go with it.
     *
     * ⚠️ A stock bar holding only its own chips, in stock order — all of them, some, or none — is what
     * no entry already means, because a chip moved elsewhere is that other bar's. Storing it anyway
     * gives two spellings of one layout, and then a round trip through the codec is not equal to
     * itself.
     */
    private fun normalised(lists: Map<BarId, List<ChromeItem>>): ChromeLayout {
        val stored = lists.filter { (id, list) ->
            when (id) {
                is BarId.Stock -> list != stockOrder(id.bar).filter { it in list }
                is BarId.Floating -> list.isNotEmpty()
            }
        }
        val live = stored.keys.filterIsInstance<BarId.Floating>().toSet()
        return copy(contents = stored, bars = bars.filterKeys { it !is BarId.Floating || it in live })
    }

    private fun withBar(id: BarId, layout: BarLayout): ChromeLayout {
        val keep = id is BarId.Floating || layout != BarLayout()
        return copy(bars = if (keep) bars + (id to layout) else bars - id)
    }

    companion object {
        /** [uiScale]. Below 0.7 a chip's word is too small to read at arm's length. */
        val UI_SCALE: ClosedFloatingPointRange<Float> = 0.7f..1.6f

        /** One bar's own size, on top of [uiScale]. */
        val BAR_SCALE: ClosedFloatingPointRange<Float> = 0.6f..2f

        /** One panel's own size, on top of [uiScale]. */
        val PANEL_SCALE: ClosedFloatingPointRange<Float> = 0.6f..2f

        /** [bar]'s chips in the design's order, which is their declaration order in [ChromeItem]. */
        fun stockOrder(bar: ChromeBar): List<ChromeItem> = ChromeItem.entries.filter { it.home == bar }

        /**
         * [stored] with every chip of [bar]'s that [ownedHere] and it does not mention put back after
         * the chip that precedes it in the stock order — so a chip a later build adds lands beside its
         * stock neighbour, not at the end, and a chip the user moved elsewhere is not put back.
         */
        fun completeOrder(
            bar: ChromeBar,
            stored: List<ChromeItem>?,
            ownedHere: (ChromeItem) -> Boolean = { it.home == bar },
        ): List<ChromeItem> {
            val stock = stockOrder(bar).filter(ownedHere)
            if (stored == null) return stock
            val result = stored.distinct().toMutableList()
            stock.forEachIndexed { index, item ->
                if (item in result) return@forEachIndexed
                val before = stock.subList(0, index).lastOrNull { it in result }
                result.add(if (before == null) 0 else result.indexOf(before) + 1, item)
            }
            return result
        }
    }
}

/** The bar whose size [this] is drawn at: the dock's second row is the dock's. */
internal fun BarId.sizedBy(): BarId = if (this == BarId.Stock(ChromeBar.DRAW)) BarId.Stock(ChromeBar.DOCK) else this

/** A bar: one of the stock ones, or one the user made by dropping a chip on the page. */
@Immutable
sealed interface BarId {
    /** What a bar is called in a settings key. */
    val key: String

    /** What it is called in the view tree. */
    val label: String

    data class Stock(val bar: ChromeBar) : BarId {
        override val key: String get() = bar.name
        override val label: String get() = bar.label
    }

    data class Floating(val n: Int) : BarId {
        override val key: String get() = "F$n"
        override val label: String get() = "Bar $n"
    }
}

/**
 * One bar's place, size and shape. [position] null is the stock slot; [scale] multiplies
 * [ChromeLayout.uiScale].
 */
@Immutable
data class BarLayout(
    val position: ChromePosition? = null,
    val scale: Float = 1f,
    val arrangement: ChromeArrangement = ChromeArrangement.ROW,
)

/**
 * How a bar lays its chips out. A row is the design's pill; a column turns a bar into a strip down
 * the side of the screen; a grid packs it into a block.
 *
 * @param columns how many chips go across. A row has no limit.
 */
@Suppress("MagicNumber") // The column counts are what the entries *are*; a constant per entry would name them twice.
enum class ChromeArrangement(val columns: Int, val label: String) {
    ROW(Int.MAX_VALUE, "Row"),
    COLUMN(1, "Column"),
    GRID_2(2, "2 across"),
    GRID_3(3, "3 across"),
    GRID_4(4, "4 across"),
    ;

    /** The arrange button's next press. */
    fun next(): ChromeArrangement = entries[(ordinal + 1) % entries.size]
}

/** The summoned panels and contextual bars, each with a size of its own. */
enum class ChromePanel(val label: String) {
    COLOUR("Colour panel"),
    WIDTH("Size panel"),
    MORE("More panel"),
    SELECTION("Selection bar"),
    TEXT("Text bar"),
    IMAGE("Image bar"),
}

/**
 * Where a bar's top-left sits, as fractions of the free space either way — see [ChromeLayout] on why
 * a fraction and not a dp offset. Both are in `0..1`, so a stored position is always on screen.
 */
@Immutable
data class ChromePosition(val fx: Float, val fy: Float) {

    /** The top-left in px for a bar of [width] × [height] in a [viewportWidth] × [viewportHeight] space. */
    fun topLeft(
        width: Float,
        height: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        edge: Float,
    ): Pair<Float, Float> = edge + fx.coerceIn(0f, 1f) * free(viewportWidth, width, edge) to
        edge + fy.coerceIn(0f, 1f) * free(viewportHeight, height, edge)

    companion object {

        /**
         * The position that puts a bar whose top-left is at ([x], [y]) there — or as near as the
         * screen allows, which is what the user sees when they let go.
         */
        fun at(
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            viewportWidth: Float,
            viewportHeight: Float,
            edge: Float,
        ): ChromePosition = ChromePosition(
            fx = fraction(x, free(viewportWidth, width, edge), edge),
            fy = fraction(y, free(viewportHeight, height, edge), edge),
        )

        private fun free(viewport: Float, size: Float, edge: Float): Float =
            (viewport - 2 * edge - size).coerceAtLeast(0f)

        /** A bar wider than the space has nowhere to go but the near edge. */
        private fun fraction(at: Float, free: Float, edge: Float): Float =
            if (free <= 0f) 0f else ((at - edge) / free).coerceIn(0f, 1f)
    }
}

/**
 * The stock bars.
 *
 * @param holdsChips a bar made of [ChromeItem]s, which chips can be dropped into. The record and
 *   calculator chips are bars of one control each, with state of their own (a recorder, a window
 *   anchor), so they move and resize but do not take other chips.
 * @param movable false for the dock's second row, which follows the dock — below it, above it once
 *   the dock is in the lower half of the screen, beside it once the dock is taller than it is wide.
 */
enum class ChromeBar(val label: String, val holdsChips: Boolean = true, val movable: Boolean = true) {
    DOCK("Tools"),
    DRAW("Pen settings", movable = false),
    HISTORY("Undo"),
    PAGES("Pages"),
    ZOOM("Zoom"),
    RECORD("Record", holdsChips = false),
    CALCULATOR("Calculator", holdsChips = false),
}

/**
 * One chip the user can hide or move, in the design's order — **declaration order is the stock
 * order**, so a new chip goes where it should appear.
 *
 * @param home the bar it lives in until it is moved.
 * @param group chips in different groups have a divider between them in a row or a column. With the
 *   stock order this reproduces the dividers the design draws; a chip moved among others grows
 *   dividers either side of it, which is what it now is.
 * @param hideable ⚠️ false for exactly two chips. `Draw` is the pen, and a toolbar with no way back to
 *   the pen is an editor you cannot write in. `⋯` is the way **into** the layout editor, and hiding it
 *   would hide the only way to show it again. Both can still be *moved* anywhere.
 */
enum class ChromeItem(val home: ChromeBar, val group: String, val label: String, val hideable: Boolean = true) {
    TOOL_DRAW(ChromeBar.DOCK, "modes", "Draw", hideable = false),
    TOOL_ERASE(ChromeBar.DOCK, "modes", "Erase"),
    TOOL_LASER(ChromeBar.DOCK, "modes", "Laser"),
    TOOL_SELECT(ChromeBar.DOCK, "select", "Select"),
    TOOL_TEXT(ChromeBar.DOCK, "objects", "Text"),
    TOOL_IMAGE(ChromeBar.DOCK, "objects", "Image"),
    TOOL_ELEMENTS(ChromeBar.DOCK, "objects", "Elements"),
    TOOL_RULER(ChromeBar.DOCK, "ruler", "Ruler"),

    BRUSH_FOUNTAIN(ChromeBar.DRAW, "brushes", "Fountain"),
    BRUSH_BALLPOINT(ChromeBar.DRAW, "brushes", "Ballpoint"),
    BRUSH_PENCIL(ChromeBar.DRAW, "brushes", "Pencil"),
    BRUSH_HIGHLIGHTER(ChromeBar.DRAW, "brushes", "Highlighter"),
    WIDTH_SLIDER(ChromeBar.DRAW, "width", "Width slider"),
    WIDTH_READOUT(ChromeBar.DRAW, "width", "Width"),
    SWATCHES(ChromeBar.DRAW, "colours", "Colours"),
    SNAP(ChromeBar.DRAW, "snap", "Snap"),
    ARROW(ChromeBar.DRAW, "snap", "Arrow"),
    GRID(ChromeBar.DRAW, "snap", "Grid"),

    UNDO(ChromeBar.HISTORY, "history", "Undo"),
    REDO(ChromeBar.HISTORY, "history", "Redo"),

    PAGE_PREVIOUS(ChromeBar.PAGES, "paging", "Previous page"),
    PAGE_COUNTER(ChromeBar.PAGES, "paging", "Page number"),
    PAGE_NEXT(ChromeBar.PAGES, "paging", "Next page"),
    PAGE_ADD(ChromeBar.PAGES, "structure", "Add page"),
    PAGE_DUPLICATE(ChromeBar.PAGES, "structure", "Duplicate page"),
    PAGE_DELETE(ChromeBar.PAGES, "structure", "Delete page"),
    PAGE_TEMPLATE(ChromeBar.PAGES, "paper", "Paper"),
    PAGE_SHARE(ChromeBar.PAGES, "share", "Share"),

    ZOOM_READOUT(ChromeBar.ZOOM, "level", "Zoom level"),
    FIT_WIDTH(ChromeBar.ZOOM, "fit", "Fit width"),
    FIT_PAGE(ChromeBar.ZOOM, "fit", "Fit page"),
    ACTUAL_SIZE(ChromeBar.ZOOM, "fit", "Actual size"),
    MORE(ChromeBar.ZOOM, "more", "More", hideable = false),
}
