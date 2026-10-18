package dev.inkstone.app.settings

import dev.inkstone.android.ink.EraseMode
import dev.inkstone.android.ink.InkBrushKind
import dev.inkstone.android.ink.InkInputModel
import dev.inkstone.android.ink.PenConfig
import dev.inkstone.app.ink.ChromeLayout
import dev.inkstone.app.ink.EditorPrefs
import dev.inkstone.core.sync.AccountState
import dev.inkstone.core.sync.SyncAccount
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Settings screen's two resets, and its account summary.
 *
 * What this establishes: each reset puts back exactly its own half of [EditorPrefs] and keeps the
 * other half and every writing option. What it cannot: that the screen's buttons call these, or that
 * an editor opened afterwards shows the result — that is the emulator walk.
 */
class SettingsModelTest {

    private val customised = EditorPrefs(
        palette = EditorPrefs.DEFAULT_PALETTE.reversed(),
        widthSlots = listOf(0.5f, 9f),
        defaultWidthDp = 9f,
        maxWidthDp = 20f,
        pens = EditorPrefs.DEFAULT_PENS +
            (InkBrushKind.PENCIL to PenConfig(InkBrushKind.PENCIL, 0xFF00FF00.toInt(), 7f)),
        brush = InkBrushKind.PENCIL,
        eraseMode = EraseMode.WHOLE_STROKE,
        inputModel = InkInputModel.DIRECT,
        fingerDrawingEnabled = true,
        shapeSnapEnabled = false,
        layout = ChromeLayout().withUiScale(1.3f),
    )

    @Test
    fun `resetting the toolbar layout keeps colours sizes and writing options`() {
        val reset = SettingsModel.resetToolbarLayout(customised)
        assertEquals(ChromeLayout(), reset.layout)
        assertEquals(customised.copy(layout = ChromeLayout()), reset)
    }

    @Test
    fun `resetting colours and sizes keeps the layout and writing options`() {
        val reset = SettingsModel.resetColoursAndSizes(customised)
        val stock = EditorPrefs()
        assertEquals(stock.palette, reset.palette)
        assertEquals(stock.widthSlots, reset.widthSlots)
        assertEquals(stock.defaultWidthDp, reset.defaultWidthDp)
        assertEquals(stock.maxWidthDp, reset.maxWidthDp)
        assertEquals(stock.pens, reset.pens)

        assertEquals(customised.layout, reset.layout)
        assertEquals(customised.brush, reset.brush)
        assertEquals(customised.eraseMode, reset.eraseMode)
        assertEquals(customised.inputModel, reset.inputModel)
        assertEquals(customised.fingerDrawingEnabled, reset.fingerDrawingEnabled)
        assertEquals(customised.shapeSnapEnabled, reset.shapeSnapEnabled)
    }

    @Test
    fun `an account the server signed out is flagged and a healthy one is not`() {
        val healthy = account(AccountState.ACTIVE)
        assertFalse(SettingsModel.needsAction(healthy))
        assertEquals("Syncing", SettingsModel.stateLabel(healthy))

        assertTrue(SettingsModel.needsAction(account(AccountState.NEEDS_REAUTH)))
        assertTrue(SettingsModel.needsAction(account(AccountState.ACTIVE, lastError = "Server unreachable")))
        // Signed out by the user is a choice, not a fault: shown, but not in red.
        assertFalse(SettingsModel.needsAction(account(AccountState.SIGNED_OUT)))
    }

    private fun account(state: AccountState, lastError: String? = null) = SyncAccount(
        id = "a",
        serverUrl = "https://cloud.example.com",
        loginName = "noah",
        uid = "noah",
        state = state,
        lastError = lastError,
    )
}
