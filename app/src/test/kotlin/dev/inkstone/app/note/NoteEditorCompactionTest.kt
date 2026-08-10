package dev.inkstone.app.note

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.NoteSyncState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC §4.7 — what the editor tells [dev.inkstone.core.storage.NoteSession.compactIfDue].
 *
 * Both arguments were wrong in production until M6, and both were wrong *silently*:
 *
 * - `close()` defaulted `syncState` to `LOCAL_ONLY` and every call site took the default, so
 *   `Compaction.isAllowed`'s first and most important refusal — never compact a note with ops the
 *   server has not seen — was dead code. The check existed, was unit-tested, and never fired.
 * - `oldestAckedCounter` had no source at all and was always null, leaving thirty days of wall clock
 *   as the whole guard against folding away ops a peer still needs in order to reintegrate its own.
 *
 * Neither can be caught by testing `Compaction` itself, because both are about what the caller
 * passes. This asserts the call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorCompactionTest {

    private class RecordingLibrary(private val state: NoteSyncState, private val acked: Long?) :
        NoteEditor.LibraryUpdates {

        val syncStateAsked = ArrayList<DocumentId>()
        val ackedAsked = ArrayList<DocumentId>()

        override suspend fun onSaved(id: DocumentId, localRev: Long, pageCount: Int) = Unit

        override suspend fun onMaterialized(id: DocumentId, localRev: Long, pageCount: Int, cover: FoldedPage) = Unit

        override suspend fun syncStateOf(id: DocumentId): NoteSyncState {
            syncStateAsked += id
            return state
        }

        override suspend fun oldestAckedCounter(id: DocumentId): Long? {
            ackedAsked += id
            return acked
        }
    }

    private fun TestScope.editor(library: NoteEditor.LibraryUpdates) = NoteEditor(
        fs = InMemoryFileSystem(),
        clock = { 1_800_000_000_000 },
        scope = this,
        drainDispatcher = UnconfinedTestDispatcher(testScheduler),
        autosaveIntervalMs = Long.MAX_VALUE,
        library = library,
    )

    @Test
    fun `materializing asks the library for the notes real sync state`() = runTest {
        val library = RecordingLibrary(NoteSyncState.CONFLICTED, acked = null)
        val editor = editor(library)
        editor.onSurfaceAttached(FakeSurface())
        runCurrent()

        editor.materializeNow()
        runCurrent()

        assertTrue(
            "the note's sync state was never asked for — compaction would run as if LOCAL_ONLY, " +
                "which is exactly the case SPEC §4.7's first refusal exists to prevent",
            library.syncStateAsked.isNotEmpty(),
        )
        editor.close()
    }

    @Test
    fun `materializing asks the library what the peers have acknowledged`() = runTest {
        val library = RecordingLibrary(NoteSyncState.SYNCED, acked = 42L)
        val editor = editor(library)
        editor.onSurfaceAttached(FakeSurface())
        runCurrent()

        editor.materializeNow()
        runCurrent()

        assertEquals(
            "SPEC §4.7's causal-stability horizon was not consulted, so compaction is guarded by " +
                "the 30-day clock alone — the state this was null in from M2 to M6",
            1,
            library.ackedAsked.size,
        )
        editor.close()
    }
}
