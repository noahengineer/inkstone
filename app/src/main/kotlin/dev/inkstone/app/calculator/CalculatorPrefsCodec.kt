package dev.inkstone.app.calculator

/**
 * What the calculator remembers across a restart (docs/CALCULATOR.md D4).
 *
 * @param snapshot the engine's own lossless text (`Calculator.snapshot()`): mode, SETUP, the nine
 *   variables, Ans/PreAns, statistics, matrices, vectors — everything the real one keeps while it
 *   is switched off. Null means "nothing loaded yet", never "empty"; see [CalculatorPrefsCodec].
 * @param open whether the window was up. One calculator for the whole app (D3), so one flag.
 * @param window where the user last dragged it, unclamped. Null until the first drag, which is what
 *   makes the window hang from the chip that opened it (D2).
 */
data class CalculatorPrefs(val snapshot: String? = null, val open: Boolean = false, val window: WindowPosition? = null)

/**
 * ⭐ [CalculatorPrefs] as the opaque strings `SettingStore` moves — `EditorPrefsCodec`'s shape, under
 * a closed `calc.*` key space (research 08 §4.9; `Setting.sq` on why a closed key space is what makes
 * the generic table right).
 *
 * ## ⚠️ Absent is not empty
 *
 * A null [CalculatorPrefs.snapshot] is **left out** of the encoding, not written as `""`. `putAll`
 * leaves a key it is not given alone, so a write that happens before the stored snapshot has been
 * read — the user opens the window while `warm()` is still in flight — cannot erase every memory
 * the calculator had. Writing `""` there would be a reset nobody asked for, and a silent one.
 * The window position is the same: no position is no key.
 *
 * ## Unparseable is a default, never an error
 *
 * Every reader falls back. A corrupt `calc.window.x` costs the window its remembered place and
 * nothing else; a settings row must not be able to stop a screen opening (the `EditorPrefsCodec`
 * argument). Half a position — an `x` without its `y` — is no position: a window placed at a
 * remembered `x` and a guessed `y` is somewhere the user never put it.
 *
 * ## ⚠️ Locale-independent by construction
 *
 * `Float.toString` / `toFloatOrNull` are, `"%.1f".format` is not — on this calculator's own
 * market's comma-decimal locales it writes `"12,5"` and reads back null. `CalculatorPrefsCodecTest`
 * round-trips under `Locale.GERMANY`.
 */
internal object CalculatorPrefsCodec {

    const val SNAPSHOT = "calc.snapshot"
    const val WINDOW_X = "calc.window.x"
    const val WINDOW_Y = "calc.window.y"
    const val OPEN = "calc.open"

    /** Every key this codec can write. The space is closed; nothing else may start with `calc.`. */
    val KEYS: Set<String> = setOf(SNAPSHOT, WINDOW_X, WINDOW_Y, OPEN)

    fun encode(prefs: CalculatorPrefs): Map<String, String> = buildMap {
        prefs.snapshot?.let { put(SNAPSHOT, it) }
        put(OPEN, prefs.open.toString())
        prefs.window?.let { window ->
            put(WINDOW_X, window.x.toString())
            put(WINDOW_Y, window.y.toString())
        }
    }

    fun decode(stored: Map<String, String>): CalculatorPrefs = CalculatorPrefs(
        snapshot = stored[SNAPSHOT],
        open = stored[OPEN]?.trim()?.toBooleanStrictOrNull() ?: false,
        window = stored.window(),
    )

    private fun Map<String, String>.window(): WindowPosition? {
        val x = coordinate(WINDOW_X) ?: return null
        val y = coordinate(WINDOW_Y) ?: return null
        return WindowPosition(x, y)
    }

    /** `NaN` and the infinities parse; neither is a place on a screen. */
    private fun Map<String, String>.coordinate(key: String): Float? =
        this[key]?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }
}
