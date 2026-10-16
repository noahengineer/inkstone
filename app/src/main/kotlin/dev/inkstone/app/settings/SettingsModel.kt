package dev.inkstone.app.settings

import dev.inkstone.app.ink.ChromeLayout
import dev.inkstone.app.ink.EditorPrefs
import dev.inkstone.core.sync.AccountState
import dev.inkstone.core.sync.SyncAccount

/**
 * The decisions behind the Settings screen, as pure functions over plain data.
 *
 * Hoisted out of the composable for CLAUDE.md's usual reason: `SyncViewModel` and
 * `EditorPreferences` cannot be built in a JVM test, so the parts worth asserting — what a reset
 * keeps, what an account's line says — live here where a test can reach them.
 */
internal object SettingsModel {

    /**
     * Put every bar back where a fresh install has it, and nothing else.
     *
     * ⚠️ Only [EditorPrefs.layout]. The palette, the width ladder and each pen's remembered colour
     * are the user's work too, and a person tidying a toolbar they dragged into a corner did not ask
     * to lose their colours. [resetColoursAndSizes] is the other half, behind its own button.
     */
    fun resetToolbarLayout(prefs: EditorPrefs): EditorPrefs = prefs.copy(layout = ChromeLayout())

    /** The palette, its order, the width ladder, the slider's range and each brush's pen — and not the layout. */
    fun resetColoursAndSizes(prefs: EditorPrefs): EditorPrefs {
        val stock = EditorPrefs()
        return prefs.copy(
            palette = stock.palette,
            widthSlots = stock.widthSlots,
            defaultWidthDp = stock.defaultWidthDp,
            maxWidthDp = stock.maxWidthDp,
            pens = stock.pens,
        )
    }

    /**
     * One short phrase for an account's state, for the summary line.
     *
     * ⚠️ Short on purpose, and **not** a replacement for the Nextcloud screen's sentences: SPEC
     * §7.2's *"Inkstone will not retry on its own"* is spelled out there, and the settings summary
     * is a signpost to it rather than a second copy that could drift.
     */
    fun stateLabel(account: SyncAccount): String = when (account.state) {
        AccountState.ACTIVE -> if (account.lastError != null) "Sync problem" else "Syncing"
        AccountState.NEEDS_REAUTH -> "Signed out by the server — sign in again"
        AccountState.NEEDS_UID -> "Finishing sign-in"
        AccountState.SIGNED_OUT -> "Signed out"
    }

    /** Whether an account's line should be drawn as needing a person. */
    fun needsAction(account: SyncAccount): Boolean = account.state == AccountState.NEEDS_REAUTH ||
        (account.state == AccountState.ACTIVE && account.lastError != null)
}
