package dev.inkstone.app.ink

import dev.inkstone.core.model.ObjectId
import dev.inkstone.core.model.PageRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * *"You can save an Element and then never add it to a note."*
 *
 * `ElementPanel` is a Compose panel over a `SurfaceView` and cannot be reached from a JVM test, so
 * what is asserted here is the **key its `LaunchedEffect` reloads on** — hoisted into
 * [ObjectToolState] for the reason `TextFrameTest` gives: a test that cannot reach the real path
 * should at least prove the decision it hands over. The gesture end of it was checked by driving
 * the emulator: capture, save with the panel open, and read the row back out of the panel.
 *
 * The bug this covers is an ordering one. `onSaveElement` clears [ObjectToolState.capture] on
 * its first line and writes `elements/index.json` from a coroutine afterwards, so a reload keyed on
 * the capture ran **before** the file existed — and then nothing moved again, because the other
 * half of the key, `elementsOpen`, was already true. The panel went on saying *"Nothing saved yet"*,
 * which left nothing to arm and therefore nothing to stamp.
 */
class ElementPanelRefreshTest {

    /**
     * The **production** key, read off [ObjectToolState.libraryReloadKey] rather than restated
     * here — a test that names its own key passes whatever `ObjectLayers` actually reloads on.
     */
    private fun ObjectToolState.reloadKey() = libraryReloadKey

    @Test
    fun `clearing the captured area does not reload the panel`() {
        val state = ObjectToolState()
        state.elementsOpen = true
        state.capture =
            ElementCapture(listOf(ObjectId("018f0000-0000-7000-8000-000000000001")), PageRect(10f, 10f, 120f, 60f))
        val before = state.reloadKey()

        // The first line of `onSaveElement`, before the save coroutine has written anything.
        state.capture = null

        assertEquals(before, state.reloadKey(), "a reload here reads the index the save has not written yet")
    }

    @Test
    fun `a completed save reloads a panel that is already open`() {
        val state = ObjectToolState()
        state.elementsOpen = true
        state.capture =
            ElementCapture(listOf(ObjectId("018f0000-0000-7000-8000-000000000001")), PageRect(10f, 10f, 120f, 60f))
        val before = state.reloadKey()

        state.capture = null
        // What the save coroutine does once `saveElement` has returned.
        state.elementsOpen = true
        state.libraryChanged()

        assertNotEquals(before, state.reloadKey(), "the panel was open, so `elementsOpen = true` moves nothing")
    }

    @Test
    fun `a completed save reloads a panel that was closed`() {
        val state = ObjectToolState()
        val before = state.reloadKey()

        state.elementsOpen = true
        state.libraryChanged()

        assertNotEquals(before, state.reloadKey())
    }

    @Test
    fun `resetting the page leaves the library revision alone`() {
        // `reset` drops everything that names an object on the page being left. The library is not
        // one of those things — it is a file beside `notes/` — and re-reading it on every page turn
        // would be disk work for a value that cannot have changed.
        val state = ObjectToolState()
        state.elementsOpen = true
        state.libraryChanged()
        val revision = state.libraryRevision

        state.reset()

        assertEquals(revision, state.libraryRevision)
        assertNull(state.pendingElement, "an armed element belongs to the page that is leaving")
    }
}
