package dev.inkstone.app.ink

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.inkstone.android.ink.EraseMode
import dev.inkstone.android.ink.InkBrushKind
import dev.inkstone.android.ink.InkInputModel
import dev.inkstone.android.ink.PenConfig
import dev.inkstone.core.database.InkstoneDatabase
import dev.inkstone.core.database.settings.SettingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ⛔ *"the favorite colors does not stay like that and the pencil size"* — over the **real**
 * `SettingStore` and schema.
 *
 * `EditorPreferences` is a process-wide singleton, and every editor used to seed from the store as it
 * was at launch. So the second note opened in a process showed the launch-time palette, and the
 * `snapshotFlow` in `InkScreen` — whose first emission is the seed itself — wrote that stale value
 * back over the user's real one 400 ms later. `EditorPrefsCodecTest` was green throughout, because
 * the codec was never wrong; its KDoc named this test as the one that would cover the rest, and it
 * did not exist.
 *
 * ## ⚠️ What stands in for what
 *
 * An "editor" here is an [InkUiState] built the way `rememberInkUiState` builds one, from
 * [EditorPreferences.current], with `InkScreen`'s `snapshotFlow` written out as the explicit
 * [EditorPreferences.changed] call it makes — including the **first** emission, which is the one that
 * did the damage. `LifecycleStartEffect` is written out as the [InkUiState.adopt] it makes. What this
 * cannot show is that the composable makes those calls; that is the emulator walk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorPreferencesTest {

    private class Fixture(scope: TestScope) {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val database = InkstoneDatabase(driver).also { InkstoneDatabase.Schema.create(driver) }
        val store = SettingStore(database, Dispatchers.Unconfined)
        val preferences = EditorPreferences(store, scope.backgroundScope)

        /** Open an editor: seed from the newest value, then report it, as `snapshotFlow` does at once. */
        fun open(): InkUiState {
            val ui = InkUiState(checkNotNull(preferences.current()) { "an editor opened before the store was read" })
            preferences.changed(ui.toPrefs())
            return ui
        }

        suspend fun stored(): EditorPrefs = EditorPrefsCodec.decode(store.all())
    }

    private fun TestScope.settle() {
        advanceTimeBy(DEBOUNCE_PLUS)
        runCurrent()
    }

    @Test
    fun `a second note opened in the same process keeps the first one's colours and pencil size`() = runTest {
        val fixture = Fixture(this)
        fixture.preferences.warm()
        runCurrent()

        val first = fixture.open()
        first.selectBrush(InkBrushKind.PENCIL)
        first.setWidth(7.5f)
        first.setColor(RED)
        first.swapSlots(0, 5)
        fixture.preferences.changed(first.toPrefs())
        settle()
        assertEquals(first.toPrefs(), fixture.stored())

        // Back to the library, open another note — same process, same singleton.
        val second = fixture.open()
        settle()

        assertEquals(InkBrushKind.PENCIL, second.kind)
        assertEquals(7.5f, second.pen.widthDp, "the pencil size reverted on the second note")
        assertEquals(RED, second.pen.colorArgb)
        assertEquals(first.slots.toList(), second.slots.toList(), "the palette order reverted")
        assertEquals(first.toPrefs(), fixture.stored(), "opening the second note wrote the launch-time value back")
    }

    @Test
    fun `an editor coming back to the front takes what the editor above it changed`() = runTest {
        val fixture = Fixture(this)
        fixture.preferences.warm()
        runCurrent()

        val note = fixture.open()
        note.chooseSlot(2)
        val element = fixture.open() // the Element editor: a second NoteActivity on top
        element.swapSlots(0, 11)
        element.setWidth(12f)
        fixture.preferences.changed(element.toPrefs())

        // Back: the note's ON_START.
        note.adopt(checkNotNull(fixture.preferences.current()))
        fixture.preferences.changed(note.toPrefs())
        settle()

        assertEquals(element.toPrefs(), note.toPrefs())
        assertEquals(-1, note.selectedSlot, "a slot index into a reordered palette names a different colour")
        assertEquals(element.toPrefs(), fixture.stored())
    }

    @Test
    fun `coming back to the front with nothing changed keeps the swatch being edited`() = runTest {
        val fixture = Fixture(this)
        fixture.preferences.warm()
        runCurrent()

        val note = fixture.open()
        note.chooseSlot(4)
        note.adopt(checkNotNull(fixture.preferences.current()))

        assertEquals(4, note.selectedSlot)
    }

    @Test
    fun `the constructor and adopt seed every persisted field identically`() {
        // Every field away from its default, so a field the constructor seeds and adopt forgets — or
        // the reverse — fails here rather than on a tablet after a restart.
        val everything = EditorPrefs(
            palette = EditorPrefs.DEFAULT_PALETTE.reversed(),
            widthSlots = listOf(0.5f, 2.5f, 7f),
            defaultWidthDp = 2.5f,
            maxWidthDp = 24f,
            pens = mapOf(
                InkBrushKind.FOUNTAIN to PenConfig(InkBrushKind.FOUNTAIN, 0xFF102030.toInt(), 1.5f),
                InkBrushKind.BALLPOINT to PenConfig(InkBrushKind.BALLPOINT, 0x80FFFFFF.toInt(), 9f),
                InkBrushKind.PENCIL to PenConfig(InkBrushKind.PENCIL, 0xFF000000.toInt(), 4f),
                InkBrushKind.HIGHLIGHTER to PenConfig(InkBrushKind.HIGHLIGHTER, 0x40FF00FF.toInt(), 20f),
            ),
            brush = InkBrushKind.PENCIL,
            eraseMode = EraseMode.WHOLE_STROKE,
            inputModel = InkInputModel.DIRECT,
            fingerDrawingEnabled = true,
            shapeSnapEnabled = false,
            arrowHeads = true,
            gridSnapEnabled = true,
            layout = ChromeLayout()
                .withUiScale(1.2f)
                .placed(BarId.Stock(ChromeBar.ZOOM), ChromePosition(0f, 0f))
                .movedTo(ChromeItem.UNDO, BarId.Stock(ChromeBar.DOCK), 0)
                .floated(ChromeItem.MORE, ChromePosition(1f, 0.5f))
                .arranged(BarId.Stock(ChromeBar.PAGES), ChromeArrangement.GRID_3)
                .withPanelScale(ChromePanel.COLOUR, 1.4f)
                .toggled(ChromeItem.REDO),
        )
        assertEquals(everything, InkUiState(everything).toPrefs())
        assertEquals(everything, InkUiState().apply { adopt(everything) }.toPrefs())
        assertEquals(EditorPrefs(), InkUiState(everything).apply { adopt(EditorPrefs()) }.toPrefs())
    }

    private companion object {
        const val RED = 0xFFB91C1C.toInt()
        const val DEBOUNCE_PLUS = 1_000L
    }
}
