package dev.inkstone.app.ink

import androidx.compose.runtime.Immutable
import dev.inkstone.android.ink.EraseMode
import dev.inkstone.android.ink.InkBrushKind
import dev.inkstone.android.ink.InkInputModel
import dev.inkstone.android.ink.PenConfig

/**
 * ⭐ ROADMAP M10 — everything the editor's toolbar remembers between sessions.
 *
 * *"i noticed the colors you select and more to position do not stay like that they should"* — the
 * palette, **its order**, and the widths beside it now survive closing the note and force-stopping
 * the app. `SettingStore` holds it; [EditorPrefsCodec] turns it into strings; `EditorPreferences`
 * owns the debounce.
 *
 * ## Every parameter has a default, and that is what "factory settings" means
 *
 * `EditorPrefs()` **is** the default configuration. There is deliberately no separate `Defaults`
 * object to drift from the constructor, and no `isDefault` flag — a fresh install reads an empty
 * table, decodes it to exactly this, and is indistinguishable from one that has been reset.
 *
 * (The defaults are not what gets this past detekt, and saying so stops the next person deleting
 * them for the wrong reason: `LongParameterList.constructorThreshold` is **10**, not the 8 that
 * applies to functions.)
 *
 * ## [widthSlots] is a starting point, not a preset
 *
 * [DEFAULT_WIDTH_SLOTS] is what a fresh install shows, and after that every rung is the user's:
 * edited in place, deleted — all of them, if they like — or added to without limit (`SizePanel`).
 * An **empty** list is a real value and survives a restart; only a missing or unreadable row falls
 * back to the stock ladder ([EditorPrefsCodec]).
 *
 * (This section used to say the ladder had to contain every brush default, because tapping the
 * readout cycled through it and a brush above the top rung could never be returned to. The readout
 * opens the size panel now and nothing cycles, so the coupling went with it.)
 *
 * @param palette twelve colours **in the user's order** — the order is the value, see
 *   [EditorPrefsCodec].
 * @param widthSlots saved stroke widths, ascending, that the dock's slider magnetises onto and marks.
 * @param defaultWidthDp what a brush with no remembered width starts at, and what `SET DEFAULT`
 *   writes. ⚠️ Not the same thing as "the last width used": a default is a value you can *return
 *   to*, which is precisely what the last-used one cannot be.
 * @param maxWidthDp the top of both width sliders' range — the user's, between
 *   [WidthLadder.MIN_MAX_DP] and [WidthLadder.HARD_MAX_DP]. Nothing persisted beside it exceeds it.
 * @param pens one remembered [PenConfig] per brush. ⚠️ **Total over [InkBrushKind]** — see
 *   [EditorPrefsCodec.decode].
 */
@Immutable
data class EditorPrefs(
    val palette: List<Int> = DEFAULT_PALETTE,
    val widthSlots: List<Float> = DEFAULT_WIDTH_SLOTS,
    val defaultWidthDp: Float = DEFAULT_WIDTH_DP,
    val maxWidthDp: Float = DEFAULT_MAX_WIDTH_DP,
    val pens: Map<InkBrushKind, PenConfig> = DEFAULT_PENS,
    val brush: InkBrushKind = InkBrushKind.FOUNTAIN,
    val eraseMode: EraseMode = EraseMode.PARTIAL,
    val inputModel: InkInputModel = InkInputModel.SPRING,
    val fingerDrawingEnabled: Boolean = false,
    val shapeSnapEnabled: Boolean = true,
    val arrowHeads: Boolean = false,
    val gridSnapEnabled: Boolean = false,
    /**
     * ⭐ Scroll from one page into the next like a PDF, instead of stopping at the page's edge and
     * turning with the page bar. Off by default: a note that has always stopped at the page's edge
     * keeps doing so until its owner asks otherwise.
     */
    val scrollPagesEnabled: Boolean = false,
    /**
     * ⭐ The Write panel's *neatness* (docs/HANDWRITING-SYNTHESIS.md §6.2): `0` loose, `1` neat; how
     * much a written phrase varies (`Variation.forNeatness`).
     */
    val handwritingNeatness: Float = DEFAULT_NEATNESS,
    /** Where the chrome sits, how big it is, and which chips it shows — see [ChromeLayout]. */
    val layout: ChromeLayout = ChromeLayout(),
    /** The layouts the user has kept, and which one [layout] is — see [SavedLayouts]. */
    val savedLayouts: SavedLayouts = SavedLayouts(),
) {

    companion object {

        /**
         * SPEC §3.4 — *"12 saved slots"*.
         *
         * ⚠️ Moved here from `InkUiState`, not copied. Two lists of twelve colours that are supposed
         * to be the same list is the drift this file exists to prevent.
         */
        val DEFAULT_PALETTE: List<Int> = listOf(
            0xFF1A1A1A.toInt(), 0xFF6B7280.toInt(), 0xFFB91C1C.toInt(), 0xFFEA580C.toInt(),
            0xFFCA8A04.toInt(), 0xFF15803D.toInt(), 0xFF0D9488.toInt(), 0xFF1D4ED8.toInt(),
            0xFF6D28D9.toInt(), 0xFFBE185D.toInt(), 0xFF78350F.toInt(), 0xFFFFFFFF.toInt(),
        )

        /** What a fresh install starts with. Every one of them can be edited or deleted. */
        val DEFAULT_WIDTH_SLOTS: List<Float> = listOf(1f, 2f, 3f, 4f, 8f, 16f)

        const val DEFAULT_WIDTH_DP: Float = 3f

        /** The middle of the slider: §6.1's variation table exactly as it stands. */
        const val DEFAULT_NEATNESS: Float = 0.5f

        /** Where the width sliders stop on a fresh install — the range they had before it was settable. */
        const val DEFAULT_MAX_WIDTH_DP: Float = 32f

        /** The four brushes, chosen so each is immediately recognisable. Was `InkUiState.perBrush`. */
        val DEFAULT_PENS: Map<InkBrushKind, PenConfig> = mapOf(
            InkBrushKind.FOUNTAIN to PenConfig(InkBrushKind.FOUNTAIN, 0xFF1A1A1A.toInt(), 3f),
            InkBrushKind.BALLPOINT to PenConfig(InkBrushKind.BALLPOINT, 0xFF1D4ED8.toInt(), 2.5f),
            InkBrushKind.PENCIL to PenConfig(InkBrushKind.PENCIL, 0xFF44403C.toInt(), 4f),
            InkBrushKind.HIGHLIGHTER to PenConfig(InkBrushKind.HIGHLIGHTER, 0x80FDE047.toInt(), 16f),
        )

        /** How many colours the palette holds. The first [QUICK_SLOTS] are the toolbar's swatches. */
        const val PALETTE_SIZE: Int = 12
    }
}
