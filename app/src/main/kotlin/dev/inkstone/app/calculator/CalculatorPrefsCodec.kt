package dev.inkstone.app.calculator

/**
 * What the calculator remembers across a restart (docs/CALCULATOR.md D4).
 *
 * @param snapshot the engine's own lossless text (`Calculator.snapshot()`): mode, SETUP, the nine
 *   variables, Ans/PreAns, statistics, matrices, vectors — everything the real one keeps while it
 *   is switched off. Null means "nothing loaded yet", never "empty"; see [CalculatorPrefsCodec].
 * @param open whether the window was up. One calculator for the whole app (D3), so one flag.
 * @param window where the user last dragged it, unclamped. Null until the first drag, which is what
 *   makes the window hang from the chip that opened it (D2) — or, in the editor, sit in the right-hand
 *   panel column (docs/UI-RAILS.md §3).
 * @param face ⭐ the face's width in dp, as the user last sized it from the window's corner. Null is the
 *   default size. *"the calculator should be resizable by like dragin in the corner"*.
 * @param docked true once the user has put the window back in the panel column, which outranks any
 *   remembered [window]. Null means "not chosen in this write" — see the codec on absent keys.
 */
data class CalculatorPrefs(
    val snapshot: String? = null,
    val open: Boolean = false,
    val window: WindowPosition? = null,
    val face: Float? = null,
    val docked: Boolean? = null,
)

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
    const val FACE = "calc.window.face"
    const val DOCKED = "calc.window.docked"

    /** Every key this codec can write. The space is closed; nothing else may start with `calc.`. */
    val KEYS: Set<String> = setOf(SNAPSHOT, WINDOW_X, WINDOW_Y, OPEN, FACE, DOCKED)

    fun encode(prefs: CalculatorPrefs): Map<String, String> = buildMap {
        prefs.snapshot?.let { put(SNAPSHOT, it) }
        put(OPEN, prefs.open.toString())
        prefs.window?.let { window ->
            put(WINDOW_X, window.x.toString())
            put(WINDOW_Y, window.y.toString())
        }
        prefs.face?.let { put(FACE, it.toString()) }
        prefs.docked?.let { put(DOCKED, it.toString()) }
    }

    /**
     * ⚠️ **A docked window has no position, whatever `x` and `y` say.** Putting the window back in the
     * column cannot delete its old coordinates — `putAll` leaves a key it is not given alone — so the
     * docked flag is what outranks them, rather than an empty value that a write made before the seed
     * was read would also produce.
     */
    fun decode(stored: Map<String, String>): CalculatorPrefs {
        val docked = stored[DOCKED]?.trim()?.toBooleanStrictOrNull()
        return CalculatorPrefs(
            snapshot = stored[SNAPSHOT],
            open = stored[OPEN]?.trim()?.toBooleanStrictOrNull() ?: false,
            window = if (docked == true) null else stored.window(),
            face = stored.coordinate(FACE)?.takeIf { it > 0f },
            docked = docked,
        )
    }

    private fun Map<String, String>.window(): WindowPosition? {
        val x = coordinate(WINDOW_X) ?: return null
        val y = coordinate(WINDOW_Y) ?: return null
        return WindowPosition(x, y)
    }

    /** `NaN` and the infinities parse; neither is a place on a screen. */
    private fun Map<String, String>.coordinate(key: String): Float? =
        this[key]?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }
}
