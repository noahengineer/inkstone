package dev.inkstone.app.note

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.inkstone.app.audio.RecordingController
import dev.inkstone.app.audio.RecordingUiState
import dev.inkstone.app.library.LibraryRepository
import dev.inkstone.app.library.Thumbnails
import dev.inkstone.app.search.NoteRecogniser
import dev.inkstone.app.sync.NoteSessionHost
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageRect
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PageTemplate
import dev.inkstone.core.model.PdfPublisher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A shell around [NoteEditor], supplying it with `viewModelScope` — and, since M3, the note
 * the library asked for.
 *
 * There is deliberately no logic here. It used to be the other way round — the editor *was* the
 * view model — and that is how the binding bug in [NoteEditor.bindSurface] reached the tablet:
 * `viewModelScope` is wired to `Dispatchers.Main` and its autosave loop is only cancelled by
 * `onCleared`, so nothing about the editor could be driven from a unit test. Everything the
 * editor does is now a plain class taking a scope, and this holds the Android lifecycle.
 *
 * ## Which note
 *
 * From [SavedStateHandle], so the note survives process death and a rotation. Passing it
 * through the constructor instead would mean the library had to still be on screen to say
 * which note this is, which after a process kill it is not.
 */
@HiltViewModel
// Eleven collaborators, and the shell holds none of them itself — every one is handed straight to
// `NoteEditor` or to a lifecycle hook below. Grouping them into a parameter object would make the
// graph harder to read for a class whose whole job is to be the wiring, which is the same
// judgement `SyncModule` and `Reconciler` get.
@Suppress("LongParameterList")
class NoteEditorViewModel @Inject constructor(
    fs: FileSystem,
    clock: Clock,
    private val library: LibraryRepository,
    private val sourcePages: SourcePages,
    private val sharing: NoteSharing,
    /**
     * The single-writer gate (M6). This editor registers with it so that a reconcile pass merges
     * through the session already open here rather than opening a second one.
     */
    private val sessions: NoteSessionHost,
    /**
     * M12's capture half. A process-wide singleton, deliberately — see [RecordingController]: a
     * lecture has to survive this view model being cleared, and the *stop* has to be able to
     * suspend, which `onCleared` cannot.
     */
    private val recordings: RecordingController,
    /** M12 — read for the export below; the controller owns writing to it. */
    private val recordingIndex: dev.inkstone.core.database.recording.RecordingIndex,
    publisher: PdfPublisher,
    /** SPEC §8.3's recogniser (M7), handed to the editor so its debounce has something to run. */
    recogniser: NoteRecogniser,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val noteId = savedState.get<String>(ARG_NOTE_ID)?.let(::DocumentId)

    private val editor = NoteEditor(
        fs = fs,
        clock = clock,
        scope = viewModelScope,
        documentId = noteId,
        library = object : NoteEditor.LibraryUpdates {
            override suspend fun onSaved(id: DocumentId, localRev: Long, pageCount: Int) =
                library.noteSaved(id, localRev, pageCount)

            override suspend fun onMaterialized(id: DocumentId, localRev: Long, pageCount: Int, cover: FoldedPage) =
                library.noteMaterialized(id, localRev, pageCount, cover)

            // SPEC §4.7 — compaction must refuse a note with ops the server has not seen.
            // The index owns sync_state, so the editor has to ask.
            override suspend fun syncStateOf(id: DocumentId) = library.syncStateOf(id)

            // SPEC §7.7 layer 2 — the note's own title register was stamped; the index and the
            // remote path are the library's half.
            override suspend fun onRenamed(id: DocumentId, title: String, localRev: Long, pageCount: Int) =
                library.noteRenamed(id, title, localRev, pageCount)

            // M6 — a merge, not a save. Never `noteSaved`: that ends in `SyncEngine.noteChanged`,
            // which uploads unconditionally and makes two devices trade ETags forever.
            override suspend fun onPulled(id: DocumentId, localRev: Long, pageCount: Int, title: String) =
                library.notePulled(id, localRev, pageCount, title)

            // SPEC §4.7's horizon, non-null for the first time in M6 (see the interface).
            override suspend fun oldestAckedCounter(id: DocumentId) = library.oldestAckedCounter(id)
        },
        publisher = publisher,
        recogniser = recogniser,
    )

    /**
     * ⭐ This editor's claim on the note, for SPEC §13.2 row 10.
     *
     * There must be exactly one [dev.inkstone.core.storage.NoteSession] per note: two would each
     * hold their own pending ops and folded pages, and whichever materialized second would write a
     * bundle missing the other's work. Registering here is what tells a reconcile pass to hand the
     * merge to this editor instead of opening its own session — see [NoteSessionHost].
     *
     * Registered in `init` rather than lazily, because the window this closes is exactly the one
     * where the note is open and a pull lands, and a lazy registration would leave it open for the
     * duration of the first file read.
     *
     * ⚠️ That means the handle exists **before** the editor has a session, which is only safe because
     * [NoteEditor.mergeRemoteBundle] waits for the open rather than answering *"no session here"*. It
     * did answer that until M6's review: the host then opened its own session and rewrote the bundle
     * under the offsets this editor had already read.
     */
    private val handle = object : NoteSessionHost.NoteEditorHandle {
        override suspend fun mergeRemoteBundle(bundlePath: String) = editor.mergeRemoteBundle(bundlePath)

        // A rename is a write to the note's own document (SPEC §7.7 layer 2), so it needs the one
        // session exactly as a merge does — the library cannot open its own to stamp a title in.
        override suspend fun rename(title: String) = editor.renameInSession(title)

        // `MaterializeWorker` republishing while this editor holds the note. Directly, it would
        // write a `document.pdf` whose marker records the hash of a bundle this editor has since
        // rewritten, and the next library rebuild reads that as Recovered — see
        // `NoteSessionHost.materialize`.
        override suspend fun materialize() = editor.materializeInSession()

        // SPEC §8.3's batch pass arriving at a note that happens to be open. Through this
        // editor's own session for the same reason a merge is: two sessions over one note each
        // hold their own pending ops, and whichever materializes second drops the other's work.
        override suspend fun recognise() = editor.recogniseInSession()
    }

    /**
     * ⭐ M12 — what the record button draws (docs/M12-AUDIO.md §10).
     *
     * ⚠️ A **pass-through of the controller's own flow**, never a field on [NoteEditorState].
     * `NoteEditor.publish()` reconstructs that object rather than copying it, so a recording field
     * added there would be reset to its default on every autosave tick, every page change, every
     * undo and every merge — an elapsed timer that blanks itself every two seconds while passing
     * any test that calls `publish` once. It also has a different lifetime: the recording outlives
     * this view model, which is the entire point of it.
     */
    val recording: StateFlow<RecordingUiState> = recordings.state

    /** §10 — one press. The permission is asked for by the screen before this is reached. */
    fun toggleRecording() {
        noteId?.let { recordings.toggle(it) }
    }

    /**
     * ⭐ M12 — this note's recordings, out of the app (docs/M12-AUDIO.md §15.5).
     *
     * A recording lives in app-private storage: MTP cannot see it and `adb` needs USB debugging,
     * which a managed device may not offer at all. Until the note is bound to Nextcloud and the
     * outbox has drained, this is the **only** way the one irreplaceable file in the app can be
     * moved, backed up, or even listened to elsewhere.
     *
     * @return null when there is nothing finished to share; the caller says so rather than opening
     *   an empty chooser.
     */
    suspend fun exportRecordings(): List<android.net.Uri>? {
        val id = noteId ?: return null
        val uris = sharing.snapshotRecordings(recordingIndex.forNote(id), state.value.title)
        return uris.ifEmpty { null }
    }

    fun chooserForRecordings(uris: List<android.net.Uri>) = sharing.chooserForRecordings(uris, state.value.title)

    /** Tell the user there was nothing to hand over, through the editor's own snackbar. */
    fun reportNoRecordings() = editor.showMessage(NO_RECORDINGS)

    /** Shown by `NoteActivity`'s snackbar, then cleared. See [RecordingController.dismissMessage]. */
    fun dismissRecordingMessage() = recordings.dismissMessage()

    /**
     * M12 — a stroke was just drawn, with the monotonic time its down event arrived.
     *
     * The page comes from the editor's published state rather than from the ink surface: the
     * surface draws a page but does not own which one it is, and the state is updated by the same
     * `display` that loads it. §15.6 made the page the alignment unit, so this is the field the
     * whole server half reads.
     */
    fun strokeCaptured(objectId: dev.inkstone.core.model.ObjectId, atElapsedRealtime: Long) {
        val current = state.value
        recordings.strokeCaptured(objectId, atElapsedRealtime, current.pageIds.getOrNull(current.pageIndex))
    }

    init {
        noteId?.let { sessions.register(it, handle) }
        // §10's per-folder auto-record, and the stop for a recording still running in a *different*
        // note — `NoteActivity` is `launchMode="standard"`, so opening note B from inside note A
        // stacks a second editor and note A's `onCleared` never fires.
        noteId?.let { recordings.noteOpened(it) }
        // SPEC §8.4 — arrive at the word, not at the note. Queued rather than applied: the
        // editor is still opening, and `showPage` takes the same lock the open holds.
        val pageId = savedState.get<String>(ARG_PAGE_ID)?.let(::PageId)
        val box = savedState.get<FloatArray>(ARG_HIT_BOX)
        if (pageId != null) {
            viewModelScope.launch {
                editor.showPage(pageId)
                pendingHighlight = box?.takeIf { it.size == BOX_VALUES }?.let(::rectOf)
                applyPendingHighlight()
            }
        }
    }

    /**
     * ⚠️ **Held until a surface exists.** The activity starts, this runs, and the
     * `AndroidView` that owns the ink surface has not been created yet — so highlighting
     * immediately writes into nothing and the user lands on the right page with no mark on it.
     * [onSurfaceAttached] replays it.
     */
    private var pendingHighlight: PageRect? = null

    /** `[left, top, right, bottom]`, in the order [NoteActivity.EXTRA_HIT_BOX] documents. */
    private fun rectOf(box: FloatArray): PageRect =
        PageRect(left = box[LEFT], top = box[TOP], right = box[RIGHT], bottom = box[BOTTOM])

    private fun applyPendingHighlight() {
        val box = pendingHighlight ?: return
        if (surface?.let {
                it.highlight(box)
                true
            } == true
        ) {
            pendingHighlight = null
        }
    }

    val state: StateFlow<NoteEditorState> = editor.state

    private var surface: NoteEditor.NoteSurface? = null

    fun onSurfaceAttached(surface: NoteEditor.NoteSurface) {
        this.surface = surface
        editor.onSurfaceAttached(surface)
        applyPendingHighlight()
    }

    fun onSurfaceDetached() {
        surface = null
        editor.onSurfaceDetached()
    }

    fun undo() = editor.undo()

    fun redo() = editor.redo()

    fun showPage(pageId: PageId) = editor.showPage(pageId)

    fun goToPage(index: Int) = editor.goToPage(index)

    fun addPage(template: PageTemplate) = editor.addPage(template)

    fun duplicateCurrentPage() = editor.duplicateCurrentPage()

    fun deleteCurrentPage() = editor.deleteCurrentPage()

    fun movePage(toIndex: Int) = editor.movePage(toIndex)

    fun setTemplate(template: PageTemplate) = editor.setTemplate(template)

    fun rename(title: String) = editor.rename(title)

    fun dismissMessage() = editor.showMessage(null)

    /**
     * A page thumbnail, folded from the **open session** rather than from disk.
     *
     * The session is the newest state there is — the journal has ops the bundle does not —
     * so a page drawn on two seconds ago would otherwise show its previous contents in the
     * overview. Cached and rendered by [Thumbnails]; this only supplies the fold.
     */
    suspend fun pageThumbnail(pageId: PageId) = state.value.documentId?.let { id ->
        library.pageThumbnail(id, pageId) { editor.foldedPage(pageId) }
    }

    /**
     * SPEC §5.1 — fold the journal into the bundle. Called from `Activity.onStop`, which is the
     * last point Android guarantees the process is alive.
     */
    fun materialize() {
        viewModelScope.launch { editor.materializeNow() }
    }

    /**
     * ROADMAP M4 — *"annotate 10 pages, export"*. Publish, then snapshot for another app.
     *
     * The order is the whole point and is not an optimisation: `document.pdf` is allowed to
     * be one editing session stale (SPEC §5.1), so exporting without materializing first
     * would routinely hand out a file missing the ink drawn since the note was opened.
     *
     * @return null when there is nothing to export yet — no note open, or the PDF could not
     *   be written. The caller says so; it is never silent.
     */
    suspend fun exportPdf(): android.net.Uri? {
        val id = state.value.documentId ?: return null
        if (editor.materializeNow() == null) return null
        return sharing.snapshot(id, state.value.title)
    }

    /** Tell the user the export produced nothing, through the editor's own snackbar. */
    fun reportExportFailed() = editor.showMessage(NoteEditor.EXPORT_FAILED)

    /** The chooser for a Uri from [exportPdf]. */
    fun chooserFor(uri: android.net.Uri) = sharing.chooserFor(uri, state.value.title)

    /**
     * SPEC §6.2 — the imported page this note page was drawn on, rendered.
     *
     * Suspends, and is called *after* the ink is already on screen: a note that
     * waited for a 200-page scan to rasterise before showing anything would feel
     * slower than one that draws the strokes and fills the paper in behind them.
     */
    suspend fun sourcePage(background: Background, size: PageSize) =
        state.value.documentId?.let { sourcePages.render(it, background, size) }

    override fun onCleared() {
        // Before `editor.close()`, and synchronously: a pull that reached the registry after the
        // editor's scope was cancelled would hand the merge to a session that can no longer run it,
        // and `mergeRemoteBundle` would never return.
        noteId?.let { sessions.unregister(it, handle) }
        // ⭐ **This is "the user left the note"**, and it is the only callback that means it.
        // `onStop` fires for a screen timeout, a lock and a glance at a message; rotation fires
        // nothing at all, because `NoteActivity` declares `configChanges` for it. Hanging the
        // recorder off `onStop` is docs/M12-AUDIO.md §9's named failure: the screen dims at minute
        // 8 and you lose the other 42 and do not find out until you look.
        //
        // Non-suspending on purpose — `ViewModel.clear()` has already cancelled `viewModelScope`
        // by the time this runs (`NoteSessionHost.unregister` documents the same trap), and
        // finalising an Ogg container is I/O. The controller has its own scope for exactly this.
        noteId?.let { recordings.noteLeft(it) }
        sourcePages.clear()
        // Ends the autosave loop. Not a substitute for `materialize()` from the Activity —
        // `onCleared` is not guaranteed to run before the process dies, which is why the journal
        // exists at all (SPEC §5.1).
        editor.close()
        super.onCleared()
    }

    companion object {
        /** The note to open, as a [SavedStateHandle] key. */
        const val ARG_NOTE_ID: String = "noteId"

        /** SPEC §8.4 — the page a search hit is on. */
        const val ARG_PAGE_ID: String = "pageId"

        const val NO_RECORDINGS: String = "This note has no recording to share yet."

        /** The hit's page-space box, `[left, top, right, bottom]`. */
        const val ARG_HIT_BOX: String = "hitBox"

        /** [NoteActivity.EXTRA_HIT_BOX]'s layout, named so the order cannot be transposed. */
        private const val LEFT = 0
        private const val TOP = 1
        private const val RIGHT = 2
        private const val BOTTOM = 3
        private const val BOX_VALUES = 4
    }
}
