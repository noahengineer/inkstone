package dev.inkstone.app.ink

import androidx.compose.runtime.Immutable

/** One layout the user has kept, under a name of their choosing. */
@Immutable
data class NamedLayout(val id: Int, val name: String, val layout: ChromeLayout)

/**
 * ⭐ *"I also want it that you can save multiple layouts so that you can switch between them easily"*
 * (docs/LAYOUTS.md) — and, asked what *"go back to how it was"* meant, *"like switching to the default
 * and custom ones"*.
 *
 * The **Default** is not in [layouts]: it is `ChromeLayout()`, read-only, and always there to go back
 * to. Every other layout is the user's.
 *
 * ## The layout on screen is always one of these
 *
 * `InkUiState.layout` is the working copy, and [synced] keeps it the same as the layout it is:
 *
 * - **On one of the user's layouts ([active] non-null), a change is a change to that layout.** There is
 *   no *Save*: a layout you arranged and then switched away from is still arranged when you come back,
 *   which is what *"switch between them"* needs.
 * - **On the Default, the first change makes a new layout** — *Layout 1*, *Layout 2* — and switches to
 *   it. So the Default is never edited and is always exactly what it was; and a change is never lost
 *   by switching away, because there is no unsaved state to lose.
 *
 * @param active the id of the layout on screen, or null for the Default.
 */
@Immutable
data class SavedLayouts(val active: Int? = null, val layouts: List<NamedLayout> = emptyList()) {

    /** What the editor calls the layout on screen. */
    val activeName: String get() = layouts.firstOrNull { it.id == active }?.name ?: DEFAULT_NAME

    fun layoutOf(id: Int?): ChromeLayout = layouts.firstOrNull { it.id == id }?.layout ?: ChromeLayout()

    /**
     * These layouts after the one on screen became [layout]: written into the active one, or — on the
     * Default, when it is no longer the Default — kept as a new layout and switched to. See the class
     * KDoc.
     */
    fun synced(layout: ChromeLayout): SavedLayouts = when {
        active != null -> {
            val updated = layouts.map { if (it.id == active && it.layout != layout) it.copy(layout = layout) else it }
            if (updated == layouts) this else copy(layouts = updated)
        }

        layout == ChromeLayout() -> this

        else -> added(layout)
    }

    /** [layout] kept as a new layout, numbered after the last, and switched to. */
    fun added(layout: ChromeLayout, name: String? = null): SavedLayouts {
        val id = (layouts.maxOfOrNull { it.id } ?: 0) + 1
        return copy(
            active = id,
            layouts =
            layouts + NamedLayout(id, name?.takeIf { it.isNotBlank() } ?: "Layout $id", layout),
        )
    }

    /** [id] on screen; null is the Default. An id this does not hold is the Default too. */
    fun switchedTo(id: Int?): SavedLayouts = copy(active = id?.takeIf { wanted -> layouts.any { it.id == wanted } })

    /** [id] renamed. A blank name keeps the old one — a layout with no name cannot be told apart. */
    fun renamed(id: Int, name: String): SavedLayouts {
        val trimmed = name.trim().take(MAX_NAME)
        if (trimmed.isEmpty()) return this
        return copy(layouts = layouts.map { if (it.id == id) it.copy(name = trimmed) else it })
    }

    /** [id] forgotten. If it was on screen, the Default is. */
    fun deleted(id: Int): SavedLayouts =
        copy(active = active.takeIf { it != id }, layouts = layouts.filter { it.id != id })

    companion object {
        const val DEFAULT_NAME = "Default"

        /** Long enough for "Lecture, landscape, left-handed"; short enough to fit a chip. */
        const val MAX_NAME = 32
    }
}

/**
 * [SavedLayouts] as `SettingStore` strings: `layouts.list` names the layouts, `layouts.active` the one
 * on screen, and each layout is its own `ChromeLayoutCodec` table under `layouts.<id>.` — so a saved
 * layout is read by exactly the code that reads the one on screen, and gets every rule it has.
 *
 * ⚠️ **Layouts are read only if `layouts.list` names them**, the `ui.floats` rule: a deleted layout's
 * rows stay in the table (`putAll` cannot remove them), and read blindly they would bring it back.
 */
internal object SavedLayoutsCodec {

    fun encode(saved: SavedLayouts): Map<String, String> = buildMap {
        put(LIST, saved.layouts.joinToString(SEP) { it.id.toString() })
        put(ACTIVE, saved.active?.toString().orEmpty())
        saved.layouts.forEach { named ->
            put(prefix(named.id) + NAME, named.name)
            ChromeLayoutCodec.encode(named.layout).forEach { (key, value) -> put(prefix(named.id) + key, value) }
        }
    }

    /**
     * The saved layouts, made to agree with [working] — the layout on screen, read from the `ui.*` rows —
     * through [SavedLayouts.synced]: when the two disagree, the screen wins, because a build that knew
     * nothing of saved layouts may have rearranged it since.
     *
     * A table with no `layouts.list` at all was written before there were saved layouts. A layout the
     * user had arranged by then is kept as *My layout* and stays on screen; an untouched one is the
     * Default.
     */
    fun decode(stored: Map<String, String>, working: ChromeLayout): SavedLayouts {
        val list = stored[LIST] ?: return if (working == ChromeLayout()) {
            SavedLayouts()
        } else {
            SavedLayouts().added(working, MIGRATED_NAME)
        }
        val ids = list.split(SEP).mapNotNull { it.trim().toIntOrNull()?.takeIf { id -> id > 0 } }.distinct()
        val layouts = ids.map { id ->
            val own = prefix(id)
            val rows = stored.filterKeys { it.startsWith(own) }.mapKeys { it.key.removePrefix(own) }
            NamedLayout(
                id = id,
                name = rows[NAME]?.trim()?.take(SavedLayouts.MAX_NAME)?.takeIf { it.isNotEmpty() } ?: "Layout $id",
                layout = ChromeLayoutCodec.decode(rows - NAME),
            )
        }
        val active = stored[ACTIVE]?.trim()?.toIntOrNull()?.takeIf { it in ids }
        return SavedLayouts(active, layouts).synced(working)
    }

    private fun prefix(id: Int) = "layouts.$id."

    private const val SEP = ","
    private const val LIST = "layouts.list"
    private const val ACTIVE = "layouts.active"
    private const val NAME = "name"
    private const val MIGRATED_NAME = "My layout"
}
