package dev.inkstone.app.ink

import dev.inkstone.android.ink.EraseMode
import dev.inkstone.android.ink.InkBrushKind
import dev.inkstone.android.ink.InkInputModel
import dev.inkstone.android.ink.PenConfig

/**
 * ⭐ M10 — [EditorPrefs] as the opaque strings `SettingStore` moves.
 *
 * ## One key per fact
 *
 * Eleven-odd keys rather than one blob, and a key **per brush** rather than a compound `ink.pens`.
 * The reason is what an older build does: a key it does not understand is a row it does not
 * *touch*, while a compound value is one it decodes, drops the unknown half of, and writes back
 * truncated. That is the `ObjectFormat.extras` argument (SPEC §5.8) applied to a settings table.
 *
 * ⚠️ **The palette is the deliberate exception, and the cost belongs beside it.** Order *is* the
 * value — *"the colors you select and more to position"* is a reorder — so it has to be one atomic
 * write; twelve keys would let a crash mid-reorder leave two slots holding the same colour. The
 * price is the truncation above: a build that shipped a thirteen-slot palette would have slot 13
 * dropped and then rewritten away by any build compiled against [EditorPrefs.PALETTE_SIZE] = 12.
 * That is accepted, because changing the slot count is a design change and not a data migration.
 *
 * ## ⚠️ Everything here is locale-independent by construction
 *
 * `"%.1f".format(3f)` is `"3,0"` on a German tablet and `"3,0".toFloatOrNull()` is null, so a
 * comma-decimal locale would write a width list it could not read back — once, silently, and then
 * for ever. `Float.toString`/`toFloatOrNull` and a hand-built hex string are locale-independent by
 * contract; the display strings in the toolbar can keep using `format`, because nothing parses
 * those. `EditorPrefsCodecTest` runs a round trip under `Locale.GERMANY`.
 *
 * ## Unparseable is not an error, it is a default
 *
 * Every reader falls back rather than throwing. A settings table is the one place a bad value must
 * not be able to stop the app opening — and the fallback is visible (your palette is the stock
 * one), which is a far better failure than a note editor that will not start.
 *
 * ## ⚠️ …but an empty width list is not unparseable
 *
 * Every saved size can be deleted, so `ink.widths = ""` is a user's deliberate choice, and reading it
 * as "corrupt, use the stock ladder" would bring all six back on the next launch — the exact *"it
 * should not be preset sizes"* complaint, reintroduced by a fallback. Only a **missing** row, or one
 * with text in it and no number, is the stock ladder.
 */
internal object EditorPrefsCodec {

    fun encode(prefs: EditorPrefs): Map<String, String> = buildMap {
        put(PALETTE, prefs.palette.joinToString(SEP) { hex(it) })
        put(WIDTHS, prefs.widthSlots.joinToString(SEP) { it.toString() })
        put(DEFAULT_WIDTH, prefs.defaultWidthDp.toString())
        put(MAX_WIDTH, prefs.maxWidthDp.toString())
        put(BRUSH, prefs.brush.name)
        prefs.pens.forEach { (kind, pen) -> put(PEN + kind.name, "${hex(pen.colorArgb)}$SEP${pen.widthDp}") }
        put(ERASE_MODE, prefs.eraseMode.name)
        put(INPUT_MODEL, prefs.inputModel.name)
        put(FINGER_DRAWING, prefs.fingerDrawingEnabled.toString())
        put(SHAPE_SNAP, prefs.shapeSnapEnabled.toString())
        put(ARROW_HEADS, prefs.arrowHeads.toString())
        put(GRID_SNAP, prefs.gridSnapEnabled.toString())
    }

    /**
     * The maximum is read first because every other width is held under it — a stored width above
     * the stored maximum would be a slider thumb pinned at its end stop reading a number it is not at.
     */
    fun decode(stored: Map<String, String>): EditorPrefs {
        val max = stored.maxWidth()
        return EditorPrefs(
            palette = stored.palette(),
            widthSlots = stored.widths(max),
            defaultWidthDp = (stored.width(DEFAULT_WIDTH) ?: EditorPrefs.DEFAULT_WIDTH_DP).coerceAtMost(max),
            maxWidthDp = max,
            pens = stored.pens(max),
            brush = stored.enum(BRUSH, InkBrushKind.entries, InkBrushKind.FOUNTAIN),
            eraseMode = stored.enum(ERASE_MODE, EraseMode.entries, EraseMode.PARTIAL),
            inputModel = stored.enum(INPUT_MODEL, InkInputModel.entries, InkInputModel.SPRING),
            fingerDrawingEnabled = stored.flag(FINGER_DRAWING, false),
            shapeSnapEnabled = stored.flag(SHAPE_SNAP, true),
            arrowHeads = stored.flag(ARROW_HEADS, false),
            gridSnapEnabled = stored.flag(GRID_SNAP, false),
        )
    }

    /**
     * ⚠️ Exactly [EditorPrefs.PALETTE_SIZE] colours, or the stock palette.
     *
     * A short list is rejected whole rather than padded: the slots are addressed by index by
     * `SwatchStrip`, `ColorPanel` and `swapSlots`, so a ten-colour palette is not a palette with two
     * missing — it is two indices that throw.
     */
    private fun Map<String, String>.palette(): List<Int> {
        val parsed = this[PALETTE]?.split(SEP)?.mapNotNull { it.trim().toLongOrNull(HEX)?.toInt() }
        return parsed?.takeIf { it.size == EditorPrefs.PALETTE_SIZE } ?: EditorPrefs.DEFAULT_PALETTE
    }

    /** Out of range is the default, not a clamp: `0` or `5000` in this row is damage, not a choice. */
    private fun Map<String, String>.maxWidth(): Float =
        this[MAX_WIDTH]?.toFloatOrNull()?.takeIf { it in WidthLadder.MIN_MAX_DP..WidthLadder.HARD_MAX_DP }
            ?: EditorPrefs.DEFAULT_MAX_WIDTH_DP

    /** A width that could ever have been set, or null. Held under the maximum by the caller. */
    private fun Map<String, String>.width(key: String): Float? =
        this[key]?.trim()?.toFloatOrNull()?.takeIf { it in MIN_WIDTH_DP..WidthLadder.HARD_MAX_DP }

    /** Ascending, deduplicated, under [maxDp] — and empty when the user emptied it. See the class KDoc. */
    private fun Map<String, String>.widths(maxDp: Float): List<Float> {
        val raw = this[WIDTHS] ?: return WidthLadder.normalised(EditorPrefs.DEFAULT_WIDTH_SLOTS, maxDp)
        if (raw.isBlank()) return emptyList()
        val parsed = raw.split(SEP)
            .mapNotNull { it.trim().toFloatOrNull() }
            .filter { it in MIN_WIDTH_DP..WidthLadder.HARD_MAX_DP }
        return WidthLadder.normalised(parsed.ifEmpty { EditorPrefs.DEFAULT_WIDTH_SLOTS }, maxDp)
    }

    /**
     * ⚠️ **Total over [InkBrushKind], always.**
     *
     * `InkUiState.pen` is `perBrush.getValue(kind)`, which throws on a missing key — and it is read
     * by the width slider, the quick swatches and the push to the ink surface, i.e. on every
     * composition of the dock. A stored map missing one brush would therefore be a crash on opening
     * a note, from a settings row. Starting at [EditorPrefs.DEFAULT_PENS] and overlaying makes a
     * partial map unrepresentable rather than handled.
     *
     * The stock pens are held under [maxDp] too: a highlighter's stock 16 dp is above a maximum of 10.
     */
    private fun Map<String, String>.pens(maxDp: Float): Map<InkBrushKind, PenConfig> = buildMap {
        putAll(EditorPrefs.DEFAULT_PENS)
        InkBrushKind.entries.forEach { kind ->
            val parts = this@pens[PEN + kind.name]?.split(SEP) ?: return@forEach
            val argb = parts.getOrNull(0)?.trim()?.toLongOrNull(HEX)?.toInt() ?: return@forEach
            val width = parts.getOrNull(1)?.trim()?.toFloatOrNull()
                ?.takeIf { it in MIN_WIDTH_DP..WidthLadder.HARD_MAX_DP }
                ?: return@forEach
            put(kind, PenConfig(kind, argb, width))
        }
        replaceAll { _, pen -> if (pen.widthDp > maxDp) pen.copy(widthDp = maxDp) else pen }
    }

    private fun <T : Enum<T>> Map<String, String>.enum(name: String, all: List<T>, fallback: T): T =
        all.firstOrNull { it.name == this[name] } ?: fallback

    private fun Map<String, String>.flag(name: String, fallback: Boolean): Boolean =
        this[name]?.toBooleanStrictOrNull() ?: fallback

    /**
     * Eight lower-case hex digits, built by hand.
     *
     * `"%08X".format(argb)` reads better and is locale-sensitive in principle; `toUInt` avoids the
     * sign that makes `Int.toString(16)` emit `-` for every colour with alpha above 0x7F — which is
     * every opaque colour in the palette.
     */
    private fun hex(argb: Int): String = argb.toUInt().toString(HEX).padStart(HEX_DIGITS, '0')

    private const val SEP = ","
    private const val HEX = 16
    private const val HEX_DIGITS = 8

    private const val PALETTE = "ink.palette"
    private const val WIDTHS = "ink.widths"
    private const val DEFAULT_WIDTH = "ink.defaultWidth"
    private const val MAX_WIDTH = "ink.maxWidth"
    private const val BRUSH = "ink.brush"
    private const val PEN = "ink.pen."
    private const val ERASE_MODE = "ink.eraseMode"
    private const val INPUT_MODEL = "ink.inputModel"
    private const val FINGER_DRAWING = "ink.fingerDrawing"
    private const val SHAPE_SNAP = "ink.shapeSnap"
    private const val ARROW_HEADS = "ink.arrowHeads"
    private const val GRID_SNAP = "ink.gridSnap"
}
