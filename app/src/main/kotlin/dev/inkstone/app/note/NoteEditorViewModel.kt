package dev.inkstone.app.note

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.inkstone.app.library.LibraryRepository
import dev.inkstone.app.library.Thumbnails
import dev.inkstone.app.sync.NoteSessionHost
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.DocumentMaterializer
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.model.PageTemplate
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
class NoteEditorViewModel @Inject constructor(
    fs: FileSystem,
    clock: Clock,
    private val library: LibraryRepository,
    private val thumbnails: Thumbnails,
    private val sourcePages: SourcePages,
    private val sharing: NoteSharing,
    /**
     * The single-writer gate (M6). This editor registers with it so that a reconcile pass merges
     * through the session already open here rather than opening a second one.
     */
    private val sessions: NoteSessionHost,
    materializer: DocumentMaterializer,
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
        materializer = materializer,
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
    }

    init {
        noteId?.let { sessions.register(it, handle) }
    }

    val state: StateFlow<NoteEditorState> = editor.state

    fun onSurfaceAttached(surface: NoteEditor.NoteSurface) = editor.onSurfaceAttached(surface)

    fun onSurfaceDetached() = editor.onSurfaceDetached()

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
        thumbnails.page(id, pageId) { editor.foldedPage(pageId) }
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
        if (!editor.materializeNow()) return null
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
    }
}
