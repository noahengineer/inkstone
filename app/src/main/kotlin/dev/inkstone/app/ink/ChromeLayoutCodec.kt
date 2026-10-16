package dev.inkstone.app.ink

/**
 * [ChromeLayout] as the opaque strings `SettingStore` moves — `EditorPrefsCodec`'s rules, applied to
 * the `ui.*` keys.
 *
 * - **One key per fact**: a key per bar and per bar's chips, so an older build, which knows none of
 *   them, touches none of them.
 * - **Every stock key is always written.** `putAll` leaves a key it is not given alone, so a bar put
 *   back in its stock slot has to be written *as* stock (`",,1.0,"`) or its old position would
 *   come back on the next launch. The same goes for its chips (`""`) and for the hidden set.
 * - ⚠️ **Floating bars are read only if `ui.floats` names them.** Their keys cannot all be rewritten
 *   when one is deleted — the key space is open — so a deleted bar's rows stay in the table. Read
 *   blindly, they would resurrect it, holding a chip the user had since put somewhere else.
 * - **Unparseable is a default, never an error**, and out of range is clamped by the model's own
 *   setters — a settings row must not be able to stop the editor opening.
 * - Locale-independent: `Float.toString` / `toFloatOrNull` only (`EditorPrefsCodec`'s KDoc).
 */
internal object ChromeLayoutCodec {

    fun encode(layout: ChromeLayout): Map<String, String> = buildMap {
        put(VERSION, CURRENT_VERSION)
        put(UI_SCALE, layout.uiScale.toString())
        ChromePanel.entries.forEach { panel -> put(PANEL + panel.name, (layout.panelScales[panel] ?: 1f).toString()) }
        val bars = ChromeBar.entries.map(BarId::Stock) + layout.floating
        bars.forEach { id ->
            val bar = layout.bar(id)
            val fx = bar.position?.fx?.toString().orEmpty()
            val fy = bar.position?.fy?.toString().orEmpty()
            put(BAR + id.key, listOf(fx, fy, bar.scale.toString(), bar.arrangement?.name.orEmpty()).joinToString(SEP))
            put(ITEMS + id.key, layout.contents[id]?.joinToString(SEP) { it.name }.orEmpty())
        }
        put(FLOATS, layout.floating.joinToString(SEP) { it.n.toString() })
        put(HIDDEN, ChromeItem.entries.filter { it in layout.hidden }.joinToString(SEP) { it.name })
        put(PANEL_SIDE, layout.panelSide.name)
        put(PANEL_ORDER, layout.orderedPanels.joinToString(SEP) { it.name })
        DockSlot.entries.forEach { slot ->
            val at = layout.floatingPanels[slot]
            put(PANEL_PLACE + slot.name, at?.let { "${it.fx}$SEP${it.fy}" }.orEmpty())
        }
        put(BRUSH_MENU, layout.brushMenu.toString())
        put(CARD_STYLE, layout.cardStyle.name)
    }

    fun decode(stored: Map<String, String>): ChromeLayout {
        var layout = ChromeLayout().withUiScale(stored[UI_SCALE]?.toFloatOrNull() ?: 1f)
        ChromePanel.entries.forEach { panel ->
            stored.panelScale(panel)?.let { layout = layout.withPanelScale(panel, it) }
        }
        val floats = stored[FLOATS]?.split(SEP)?.mapNotNull { it.trim().toIntOrNull()?.takeIf { n -> n > 0 } }
            ?.distinct()?.sorted()?.map(BarId::Floating).orEmpty()
        val bars = ChromeBar.entries.map(BarId::Stock) + floats

        // Chips first, every bar's at once: one owner per chip, the first bar to name it.
        val seen = HashSet<ChromeItem>()
        val contents = bars.associateWith { id -> stored.items(id).filter { seen.add(it) } }
        layout = layout.copy(contents = contents.filterValues { it.isNotEmpty() })
        // A floating bar that lost every chip to an earlier bar is no bar at all.
        val live = floats.filter { layout.contents[it].orEmpty().isNotEmpty() }

        val legacy = stored[VERSION] == null
        (ChromeBar.entries.map(BarId::Stock) + live).forEach { id ->
            stored[BAR + id.key]?.split(SEP)?.let { layout = layout.withBarRow(id, it, legacy) }
        }
        // Normalise through the model, so what was read is what `movedTo` would have stored.
        layout.chipBars.forEach { id ->
            layout.itemsOf(id).forEachIndexed { index, item -> layout = layout.movedTo(item, id, index) }
        }
        stored[HIDDEN]?.split(SEP)?.mapNotNull(::item)?.forEach { item ->
            if (!layout.isHidden(item)) layout = layout.toggled(item)
        }
        return layout.withPanels(stored)
    }

    /**
     * The panel column and the two style switches (docs/LAYOUTS.md). Every row is optional — a table
     * from before they existed is the stock column on the right with the stock styles.
     */
    private fun ChromeLayout.withPanels(stored: Map<String, String>): ChromeLayout {
        var layout = withPanelSide(PanelSide.entries.firstOrNull { it.name == stored[PANEL_SIDE] } ?: PanelSide.RIGHT)
        stored[PANEL_ORDER]?.split(SEP)?.mapNotNull { name -> DockSlot.entries.firstOrNull { it.name == name.trim() } }
            ?.let { named -> layout = layout.copy(panelOrder = (named + DockSlot.entries).distinct()) }
        DockSlot.entries.forEach { slot ->
            val parts = stored[PANEL_PLACE + slot.name]?.split(SEP)
            val fx = parts?.getOrNull(0)?.trim()?.toFloatOrNull()
            val fy = parts?.getOrNull(1)?.trim()?.toFloatOrNull()
            if (fx != null && fy != null) {
                layout = layout.panelPlaced(slot, ChromePosition(fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f)))
            }
        }
        stored[BRUSH_MENU]?.toBooleanStrictOrNull()?.let { layout = layout.withBrushMenu(it) }
        CardStyle.entries.firstOrNull { it.name == stored[CARD_STYLE] }?.let { layout = layout.withCardStyle(it) }
        return layout
    }

    /**
     * One `ui.bar.*` row — position, size, arrangement — applied to [id].
     *
     * ⚠️ **[legacy]: a row written before a bar had a stock shape of its own.** That build wrote `ROW`
     * for every bar, chosen or not, because a row was the only stock — so read literally, every
     * tablet that ever opened a note would keep the old top-of-the-page dock for ever and never see the
     * rails (docs/UI-RAILS.md). A stock bar still in its stock slot whose row says `ROW` is read as *no
     * choice*. One the user **moved** keeps its `ROW`: that bar was arranged by hand, and changing its
     * shape under them would be the layout rewriting itself.
     */
    private fun ChromeLayout.withBarRow(id: BarId, parts: List<String>, legacy: Boolean): ChromeLayout {
        val fx = parts.getOrNull(0)?.trim()?.toFloatOrNull()
        val fy = parts.getOrNull(1)?.trim()?.toFloatOrNull()
        // A position is both halves or neither: one fraction alone is a damaged row, not a choice.
        val position = when {
            fx != null && fy != null -> ChromePosition(fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f))
            id is BarId.Floating -> ChromePosition(HALF, HALF)
            else -> null
        }
        var layout = placed(id, position)
        parts.getOrNull(2)?.trim()?.toFloatOrNull()?.let { layout = layout.scaled(id, it) }
        val arrangement = ChromeArrangement.entries.firstOrNull {
            it.name == parts.getOrNull(INDEX_ARRANGEMENT)?.trim()
        }
        val unchosen = legacy && id is BarId.Stock && position == null && arrangement == ChromeArrangement.ROW
        return if (arrangement != null && !unchosen) layout.arranged(id, arrangement) else layout
    }

    /**
     * [id]'s chips as written — or, for a stock bar this build has never written, the row-order key
     * the first version of the editor used, so a layout arranged with it is not lost.
     */
    private fun Map<String, String>.items(id: BarId): List<ChromeItem> {
        val raw = this[ITEMS + id.key] ?: (id as? BarId.Stock)?.let { this[LEGACY_ORDER + legacyRow(it.bar)] }
        return raw?.split(SEP)?.mapNotNull(::item).orEmpty()
    }

    private fun Map<String, String>.panelScale(panel: ChromePanel): Float? = this[PANEL + panel.name]?.toFloatOrNull()
        ?: this[LEGACY_PANEL_SCALE]?.toFloatOrNull()?.takeIf { panel in LEGACY_PANELS }

    /** The row names `ui.order.*` used before a chip could leave its bar. */
    private fun legacyRow(bar: ChromeBar): String = if (bar == ChromeBar.DOCK) "TOOLS" else bar.name

    private fun item(name: String): ChromeItem? = ChromeItem.entries.firstOrNull { it.name == name.trim() }

    private const val SEP = ","
    private const val HALF = 0.5f
    private const val INDEX_ARRANGEMENT = 3

    /** Absent in every table written before bars had stock shapes of their own — see [withBarRow]. */
    private const val VERSION = "ui.version"
    private const val CURRENT_VERSION = "2"
    private const val UI_SCALE = "ui.scale"
    private const val PANEL = "ui.panel."
    private const val BAR = "ui.bar."
    private const val ITEMS = "ui.items."
    private const val FLOATS = "ui.floats"
    private const val HIDDEN = "ui.hidden"
    private const val PANEL_SIDE = "ui.panels.side"
    private const val PANEL_ORDER = "ui.panels.order"
    private const val PANEL_PLACE = "ui.panels.place."
    private const val BRUSH_MENU = "ui.style.brushMenu"
    private const val CARD_STYLE = "ui.style.card"
    private const val LEGACY_ORDER = "ui.order."
    private const val LEGACY_PANEL_SCALE = "ui.panelScale"
    private val LEGACY_PANELS = setOf(ChromePanel.COLOUR, ChromePanel.WIDTH, ChromePanel.MORE)
}
