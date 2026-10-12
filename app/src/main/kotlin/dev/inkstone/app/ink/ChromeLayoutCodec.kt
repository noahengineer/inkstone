package dev.inkstone.app.ink

/**
 * [ChromeLayout] as the opaque strings `SettingStore` moves — `EditorPrefsCodec`'s rules, applied to
 * the `ui.*` keys.
 *
 * - **One key per fact**: a key per bar and per bar's chips, so an older build, which knows none of
 *   them, touches none of them.
 * - **Every stock key is always written.** `putAll` leaves a key it is not given alone, so a bar put
 *   back in its stock slot has to be written *as* stock (`",,1.0,ROW"`) or its old position would
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
        put(UI_SCALE, layout.uiScale.toString())
        ChromePanel.entries.forEach { panel -> put(PANEL + panel.name, (layout.panelScales[panel] ?: 1f).toString()) }
        val bars = ChromeBar.entries.map(BarId::Stock) + layout.floating
        bars.forEach { id ->
            val bar = layout.bar(id)
            val fx = bar.position?.fx?.toString().orEmpty()
            val fy = bar.position?.fy?.toString().orEmpty()
            put(BAR + id.key, listOf(fx, fy, bar.scale.toString(), bar.arrangement.name).joinToString(SEP))
            put(ITEMS + id.key, layout.contents[id]?.joinToString(SEP) { it.name }.orEmpty())
        }
        put(FLOATS, layout.floating.joinToString(SEP) { it.n.toString() })
        put(HIDDEN, ChromeItem.entries.filter { it in layout.hidden }.joinToString(SEP) { it.name })
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

        (ChromeBar.entries.map(BarId::Stock) + live).forEach { id ->
            stored[BAR + id.key]?.split(SEP)?.let { layout = layout.withBarRow(id, it) }
        }
        // Normalise through the model, so what was read is what `movedTo` would have stored.
        layout.chipBars.forEach { id ->
            layout.itemsOf(id).forEachIndexed { index, item -> layout = layout.movedTo(item, id, index) }
        }
        stored[HIDDEN]?.split(SEP)?.mapNotNull(::item)?.forEach { item ->
            if (!layout.isHidden(item)) layout = layout.toggled(item)
        }
        return layout
    }

    /** One `ui.bar.*` row — position, size, arrangement — applied to [id]. */
    private fun ChromeLayout.withBarRow(id: BarId, parts: List<String>): ChromeLayout {
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
        return if (arrangement != null) layout.arranged(id, arrangement) else layout
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
    private const val UI_SCALE = "ui.scale"
    private const val PANEL = "ui.panel."
    private const val BAR = "ui.bar."
    private const val ITEMS = "ui.items."
    private const val FLOATS = "ui.floats"
    private const val HIDDEN = "ui.hidden"
    private const val LEGACY_ORDER = "ui.order."
    private const val LEGACY_PANEL_SCALE = "ui.panelScale"
    private val LEGACY_PANELS = setOf(ChromePanel.COLOUR, ChromePanel.WIDTH, ChromePanel.MORE)
}
