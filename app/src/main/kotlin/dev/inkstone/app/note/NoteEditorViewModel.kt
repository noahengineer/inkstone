package dev.inkstone.app.note

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.inkstone.app.audio.RecordingController
import dev.inkstone.app.audio.RecordingUiState
import dev.inkstone.app.elements.ElementPreviews
import dev.inkstone.app.elements.ElementShelf
import dev.inkstone.app.handwriting.phraseOf
import dev.inkstone.app.handwriting.stampHandwriting
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
import kotlinx.coroutines.withContext
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
// ⭐ M8c pushed the member count past thirty. Every one of the additions is a one-line pass-through
// to `NoteEditor` — insert a text box, write a register, turn an image, crop it, stamp an Element —
// and the shell exists precisely so those live in a plain class a JVM test can drive. Collapsing
// them into a `perform(Action)` switch would put the whole M8c surface behind one untyped door and
// would hide exactly the thing definition-of-done #6 asks to be able to grep for: a production
// caller per feature. Same judgement `NoteEditor` and `LibraryRepository` already carry.
@Suppress("LongParameterList", "TooManyFunctions")
class NoteEditorViewModel @Inject constructor(
    private val fs: FileSystem,
    clock: Clock,
    private val library: LibraryRepository,
    private val sourcePages: SourcePages,
    private val sharing: NoteSharing,
    /**
     * The single-writer gate (M6). This editor registers with it so that a reconcile pass merges
     * through the session already open here rather than opening a second one.
     */
    private val sessions: NoteSessionHost,
    /** ⭐ M8c — the Element library (SPEC §4.9.4). Read by the panel, written by a capture. */
    private val elements: ElementShelf,
    /** The panel's pictures of each Element, and the ghost a drag carries. */
    private val elementPreviews: ElementPreviews,
    /** For reading the bytes behind a picked image Uri. The resolver stops at `:app` (M4). */
    private val resolver: android.content.ContentResolver,
    /**
     * M12's capture half. A process-wide singleton, deliberately — see [RecordingController]: a
     * lecture has to survive this view model being cleared, and the *stop* has to be able to
     * suspend, which `onCleared` cannot.
     */
    private val recordings: RecordingController,
    /** M10 — the editor's remembered toolbar. A pass-through: this class owns none of it. */
    private val preferences: dev.inkstone.app.ink.EditorPreferences,
    /** M12 — read for the export below; the controller owns writing to it. */
    private val recordingIndex: dev.inkstone.core.database.recording.RecordingIndex,
    publisher: PdfPublisher,
    /** SPEC §8.3's recogniser (M7), handed to the editor so its debounce has something to run. */
    recogniser: NoteRecogniser,
    /** Handwriting synthesis (docs/HANDWRITING-SYNTHESIS.md D8): which strokes the pen drew here. */
    private val penLedger: dev.inkstone.app.handwriting.PenLedgerRecorder,
    /** Handwriting synthesis, phase 3: the Write panel's writer. A pass-through; see [stampHandwriting]. */
    val handwriting: dev.inkstone.app.handwriting.HandwritingWriter,
    /** Draws the pages beside the one being edited, for a note that scrolls like a PDF. See [pagePicture]. */
    private val stripRenderer: PageStripRenderer,
    savedState: SavedStateHandle,
) : ViewModel() {

    /**
     * M10 — non-null once the stored toolbar has been read. ⚠️ A readiness signal: its value is the
     * store **at launch**, so seed from [currentEditorPrefs] instead (see `EditorPreferences`).
     */
    val editorPrefs = preferences.snapshot

    /** The newest toolbar any editor in this process has reported — what to seed from now. */
    fun currentEditorPrefs(): dev.inkstone.app.ink.EditorPrefs? = preferences.current()

    /** Record a change. Debounced by `EditorPreferences`; see why it is not per gesture there. */
    fun editorPrefsChanged(prefs: dev.inkstone.app.ink.EditorPrefs) = preferences.changed(prefs)

    private val noteId = savedState.get<String>(ARG_NOTE_ID)?.let(::DocumentId)

    // -----------------------------------------------------------------------
    // ⭐ The Element editor (SPEC §4.9.7)
    // -----------------------------------------------------------------------

    /**
     * The Element this screen is editing, or null for an ordinary note.
     *
     * The Element editor **is** the note editor — every tool, every gesture, the same undo — pointed
     * at a scratch note in a private corner of storage ([ScopedFileSystem]) and given no library, no
     * publisher and no recogniser. That is the whole of why it cannot reach the library, the PDF or
     * the server: those are the three things that make a note a note, and this one is handed none.
     */
    val editingElement: dev.inkstone.core.model.ElementId? =
        savedState.get<String>(ARG_ELEMENT_ID)?.let { dev.inkstone.core.model.ElementId(it) }

    /**
     * One scratch folder per editing session, kept in the saved state so that a process death mid-edit
     * reopens the same scratch note rather than starting the Element again from its saved version.
     */
    private val editSession: String = savedState.get<String>(ARG_EDIT_SESSION)
        ?: dev.inkstone.core.model.IdFactory(clock).newObjectId().value.also { savedState[ARG_EDIT_SESSION] = it }

    private val editorFs: FileSystem =
        if (editingElement == null) fs else dev.inkstone.app.elements.ScopedFileSystem(fs, "$EDITING_DIR/$editSession")

    /**
     * The Element editor's lattice: the spacing of the note it was opened from, so an Element is
     * drawn on the grid its copies snap to (SPEC §4.9.7).
     */
    val elementGridPt: Float = savedState.get<Float>(ARG_GRID_PT)
        ?.takeIf { it >= dev.inkstone.core.model.SnapGrid.MIN_SPACING_PT }
        ?: dev.inkstone.core.model.SnapGrid.DEFAULT_SPACING_PT

    private val scratchIds = dev.inkstone.core.model.IdFactory(clock)
    private val scratchClock: Clock = clock

    private val _elementName = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** The Element's name, for the editor's banner. */
    val elementName: StateFlow<String?> = _elementName

    private val _elementFrame = kotlinx.coroutines.flow.MutableStateFlow<PageRect?>(null)

    /** The Element's box on the editor's canvas, so the screen can open framed on it. */
    val elementFrame: StateFlow<PageRect?> = _elementFrame

    /** Where the Element's origin was placed on the scratch page — what [ElementRevision.originMoved] is measured from. */
    private var scratchOrigin = dev.inkstone.core.model.PagePoint(0f, 0f)

    /**
     * Move the pivot — the editor's Pivot tool. An op on the bounds object, so it is undone with
     * everything else (`NoteEditor.setElementPivot`).
     */
    suspend fun setElementPivot(at: dev.inkstone.core.model.PagePoint, content: PageRect?): Boolean =
        editor.editShownPage { page, session ->
            dev.inkstone.core.storage.ElementEditing.pivotEditOps(
                page,
                at,
                content,
                scratchIds.newObjectId(),
                session::nextOpId,
                session::nextClock,
            )
        }

    /** The editor's *Fit*: bounds made [box] — the content's, already widened to the grid. */
    suspend fun fitElementFrame(box: PageRect): Boolean = editor.editShownPage { page, session ->
        dev.inkstone.core.storage.ElementEditing.fitEditOps(
            page,
            box,
            scratchIds.newObjectId(),
            session::nextOpId,
            session::nextClock,
        )
    }

    /** Every piece on the editor's canvas — everything but its bounds — for measuring a *Fit*. */
    suspend fun elementPieces(): List<dev.inkstone.core.model.ObjectId> =
        editor.savedFold()?.let { dev.inkstone.core.storage.ElementEditing.pieces(it) }.orEmpty()

    /**
     * The scratch note: the Element's pieces in the middle of a page far larger than any screen, so
     * there is nowhere a stroke can begin off the paper (the pen refuses one that does — SPEC §4.5).
     *
     * Applied with `replay`, so the seed is not an undo entry: the first press of undo in the editor
     * takes back the first thing *the user* did, never the Element itself.
     *
     * ⭐ The seed includes the Element's **bounds** as an object of their own, carrying the pivot
     * (`ElementEditing`), so moving, resizing, snapping and undoing them are the gestures every
     * object already has. The origin sits on the grid, so the bounds start on the lines the backdrop
     * draws and a snapped edit lands where the user aimed.
     */
    private suspend fun seedScratchNote(
        store: dev.inkstone.core.storage.NoteStore,
        device: String,
        id: dev.inkstone.core.model.ElementId,
    ): DocumentId {
        val element = elements.find(id)
        _elementName.value = element?.name
        val centre = EDITOR_PAGE_PT / 2f
        val lattice = elementGridPt
        scratchOrigin = dev.inkstone.core.model.PagePoint(
            kotlin.math.round((centre - (element?.widthPt ?: 0f) / 2f) / lattice) * lattice,
            kotlin.math.round((centre - (element?.heightPt ?: 0f) / 2f) / lattice) * lattice,
        )
        _elementFrame.value = PageRect(
            scratchOrigin.x,
            scratchOrigin.y,
            scratchOrigin.x + maxOf(element?.widthPt ?: 0f, MIN_FRAME_PT),
            scratchOrigin.y + maxOf(element?.heightPt ?: 0f, MIN_FRAME_PT),
        )
        retireOldScratch()
        // A scratch note from before a process death is the edit in progress — reopen it.
        editorFs.list(dev.inkstone.core.storage.NoteLayout.NOTES_DIR).firstOrNull()?.let { return DocumentId(it) }

        val note = store.create(title = element?.name ?: "Element", deviceId = device, background = Background.Blank)
        val session = dev.inkstone.core.storage.NoteSession.open(store, scratchIds, scratchClock, note, device)
        val page = session.document.livePages.first().id
        val ops = mutableListOf<dev.inkstone.core.model.PageOp>(
            dev.inkstone.core.model.PageOp.SetPageMeta(
                session.nextOpId(),
                session.nextClock(),
                size = PageSize(EDITOR_PAGE_PT, EDITOR_PAGE_PT),
            ),
        )
        var assets = emptyMap<String, ByteArray>()
        if (element != null) {
            val frameZ = dev.inkstone.core.model.FractionalKey.Initial
            ops += dev.inkstone.core.storage.ElementEditing.frameOps(
                id = scratchIds.newObjectId(),
                box = PageRect(
                    scratchOrigin.x,
                    scratchOrigin.y,
                    scratchOrigin.x + element.widthPt,
                    scratchOrigin.y + element.heightPt,
                ),
                pivot = element.pivot?.let { pivotOnCanvas(element) },
                z = frameZ,
                nextOpId = session::nextOpId,
                nextClock = session::nextClock,
            )
            val stamp = elements.stamp(
                element = element,
                topLeft = scratchOrigin,
                topZ = frameZ,
                newObjectId = scratchIds::newObjectId,
                nextOpId = session::nextOpId,
                nextClock = session::nextClock,
            )
            ops += stamp.ops
            assets = stamp.assets
        }
        session.replay(page, ops)
        session.materialize(extraCarried = assets)
        return note.document.id
    }

    /**
     * Every earlier editing session's scratch folder, moved to the Elements trash — **moved, never
     * deleted** (CLAUDE.md rule 1: a cancelled edit is still something the user drew).
     *
     * Done here, when a *new* session starts, rather than when the old one ends: a session's editor
     * autosaves into its folder until its view model is cleared, and moving the folder out from under
     * a live session is exactly how `NoteSessionHost`'s trash path once lost a note (the next autosave
     * recreates the directory with a journal and no bundle). By the time another Element is opened,
     * the previous editor is gone.
     */
    private suspend fun retireOldScratch() {
        val finished = fs.list(EDITING_DIR).filter { it != editSession }
        if (finished.isEmpty()) return
        fs.createDirectories(SCRATCH_TRASH_DIR)
        for (name in finished) fs.move("$EDITING_DIR/$name", "$SCRATCH_TRASH_DIR/$name")
    }

    private fun pivotOnCanvas(element: dev.inkstone.core.storage.Element): dev.inkstone.core.model.PagePoint {
        val pivot = element.pivot
            ?: dev.inkstone.core.model.PagePoint(element.widthPt / 2f, element.heightPt / 2f)
        return dev.inkstone.core.model.PagePoint(scratchOrigin.x + pivot.x, scratchOrigin.y + pivot.y)
    }

    /**
     * ⭐ The editor's Done: save the canvas back over the Element (its previous version to trash
     * first) and count the copies that could now be updated.
     *
     * @param measure the surface's measurement of the pieces — handwriting included — which becomes
     *   the Element's origin, exactly as a capture's box does.
     */
    suspend fun saveElementEdit(measure: (List<dev.inkstone.core.model.ObjectId>) -> PageRect?): ElementEditOutcome {
        val id = editingElement
        val original = id?.let { elements.find(it) }
        val fold = original?.let { editor.savedFold() }
        if (id == null || original == null || fold == null) return ElementEditOutcome.Gone
        // Everything but the bounds, which are the editor's guide and not part of the Element.
        val pieces = dev.inkstone.core.storage.ElementEditing.pieces(fold)
        val frame = dev.inkstone.core.storage.ElementEditing.frameOf(fold)
        // The bounds the user set; the content's own box only for a canvas whose bounds were deleted.
        val box = if (pieces.isEmpty()) null else frame?.bounds ?: measure(pieces)
        val revised = if (pieces.isEmpty()) {
            null
        } else {
            elements.revise(
                original,
                fold,
                pieces,
                editor.assets(),
                box,
                frame?.takeIf { it.hasPivot }?.pivot,
            )
        }
        if (revised == null) return ElementEditOutcome.Empty
        val moved = box?.let { dev.inkstone.core.model.PagePoint(it.left - scratchOrigin.x, it.top - scratchOrigin.y) }
            ?: dev.inkstone.core.model.PagePoint(0f, 0f)
        val holding = elements.uses(id)
            .map { note -> note to sessions.countElementCopies(note, id) }
            .filter { (_, copies) -> copies > 0 }
        return ElementEditOutcome.Saved(
            dev.inkstone.app.elements.ElementRevision(revised, moved),
            holding.sumOf { it.second },
            holding.map { it.first },
        )
    }

    /** Replace every copy in [notes] with [revision] — the answer *"Update them all"*. */
    suspend fun updateElementCopies(
        revision: dev.inkstone.app.elements.ElementRevision,
        notes: List<DocumentId>,
    ): Int = notes.sumOf { sessions.updateElementCopies(it, revision) }

    /** A knockout area drawn with the editor's area tool. */
    suspend fun insertCutter(
        kind: dev.inkstone.core.model.ShapeKind,
        box: PageRect,
    ): dev.inkstone.core.model.ObjectId? {
        val id = scratchIds.newObjectId()
        val placed = editor.editShownPage { page, session ->
            dev.inkstone.core.storage.ElementEditing.areaOps(page, id, kind, box, session::nextOpId, session::nextClock)
        }
        return id.takeIf { placed }
    }

    private val libraryUpdates = object : NoteEditor.LibraryUpdates {
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
    }

    private val editor = NoteEditor(
        fs = editorFs,
        clock = clock,
        scope = viewModelScope,
        documentId = noteId,
        // ⚠️ All three null for the Element editor — see [editingElement]. The scratch note must not
        // be indexed, published or read for search.
        library = libraryUpdates.takeIf { editingElement == null },
        publisher = publisher.takeIf { editingElement == null },
        recogniser = recogniser.takeIf { editingElement == null },
        prepare = editingElement?.let { id -> { store, device -> seedScratchNote(store, device, id) } },
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

        // ⭐ SPEC §4.9.7 — an Element was revised elsewhere and this note holds copies of it.
        override suspend fun countElementCopies(elementId: dev.inkstone.core.model.ElementId) =
            editor.countElementCopies(elementId)

        override suspend fun updateElementCopies(revision: dev.inkstone.app.elements.ElementRevision) =
            editor.updateElementCopies(elements, revision)
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
    fun strokeCaptured(objectId: dev.inkstone.core.model.ObjectId, atElapsedRealtime: Long, ruled: Boolean) {
        val current = state.value
        recordings.strokeCaptured(objectId, atElapsedRealtime, current.pageIds.getOrNull(current.pageIndex))
        // Handwriting synthesis — the pen ledger (docs/HANDWRITING-SYNTHESIS.md D8). No note id means
        // the Element editor's scratch canvas, whose ink is an icon being drawn, not handwriting to
        // learn; it is never recognised either.
        noteId?.let { penLedger.strokeCaptured(it, objectId, atElapsedRealtime, ruled) }
    }

    init {
        noteId?.let { sessions.register(it, handle) }
        // §10's per-folder auto-record, and the stop for a recording still running in a *different*
        // note — `NoteActivity` is `launchMode="standard"`, so opening note B from inside note A
        // stacks a second editor and note A's `onCleared` never fires.
        noteId?.let { recordings.noteOpened(it) }
        // SPEC §8.4 — arrive at the word, not at the note. Queued rather than applied: the
        // editor is still opening, and `showPage` queues behind the open.
        //
        // ⚠️ **The box waits for the turn.** `highlight` carries no page: the surface gives a hit
        // to the page it is first centred on (`SearchHitTracker`), so a box sent while the note
        // was still opening was centred on the page the note opened at — the right place on the
        // wrong page — and the turn that followed cleared it. Sent only once the hit's page is
        // the one on screen (or the one a surface attaching later will load), and not at all
        // when the page is gone from the note.
        val pageId = savedState.get<String>(ARG_PAGE_ID)?.let(::PageId)
        val box = savedState.get<FloatArray>(ARG_HIT_BOX)
        if (pageId != null) {
            viewModelScope.launch {
                editor.showPage(pageId).join()
                val shown = editor.state.value
                if (shown.pageIds.getOrNull(shown.pageIndex) != pageId) return@launch
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

    /** SPEC §4.5 — this page grows to hold what is written on it, or it does not. */
    fun setInfiniteCanvas(infinite: Boolean) = editor.setInfiniteCanvas(infinite)

    fun rename(title: String) = editor.rename(title)

    // -----------------------------------------------------------------------
    // ⭐ M8c — text boxes, images and Elements (SPEC §4.9)
    // -----------------------------------------------------------------------

    /** The page on screen, for the overlay's hit-testing. */
    suspend fun currentFold() = editor.currentFold()

    /** Placing a box and typing its first words is one gesture — see [NoteEditor.insertTextBox]. */
    suspend fun insertTextBox(
        box: dev.inkstone.core.model.PageRect,
        text: String,
        style: dev.inkstone.core.model.ObjectStyle,
        format: dev.inkstone.core.model.ObjectFormat,
    ) = editor.insertTextBox(box, text, style, format)

    fun updateText(id: dev.inkstone.core.model.ObjectId, text: String) = editor.updateText(id, text)

    fun updateFormat(
        id: dev.inkstone.core.model.ObjectId,
        change: (dev.inkstone.core.model.ObjectFormat) -> dev.inkstone.core.model.ObjectFormat,
    ) = editor.updateFormat(id, change)

    fun recolour(id: dev.inkstone.core.model.ObjectId, argb: Int) = editor.updateStyle(id) { it.copy(colorArgb = argb) }

    fun turnObject(id: dev.inkstone.core.model.ObjectId, degrees: Float, flipH: Boolean, flipV: Boolean) =
        editor.turnObject(id, degrees, flipH, flipV)

    fun setCrop(id: dev.inkstone.core.model.ObjectId, crop: dev.inkstone.core.model.ImageCrop?) =
        editor.setCrop(id, crop)

    /**
     * ⭐ M8c — a picked or photographed image, downscaled before it ever reaches the bundle.
     *
     * The decode is here rather than in [NoteEditor] because it needs a `ContentResolver` and
     * `BitmapFactory`, which is the same boundary `MainActivity.importCoverImage` draws: a `Uri` is
     * only readable while this process holds the grant the picker made, so everything below is
     * handed bytes.
     *
     * @return the byte sizes, for the report a milestone has to be able to make — source, encoded,
     *   and therefore what the note's `assets/` entry costs. Null when the Uri was not a decodable
     *   image, which a content Uri can always turn out not to be.
     */
    suspend fun insertImage(
        uri: android.net.Uri,
        page: PageSize,
        visible: dev.inkstone.core.model.PageRect,
    ): ImageInsertReport? {
        val source = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { resolver.openInputStream(uri).use { it?.readBytes() } }.getOrNull()
        } ?: return null
        val encoded = withContext(kotlinx.coroutines.Dispatchers.Default) { InsertedImage.reEncode(source) }
            ?: return null
        // The box is computed **after** the decode because it needs the picture's aspect ratio, and
        // the aspect ratio is only known once the bounds pass has run. Placing first and stretching
        // afterwards would need a second `SetTransform` for every insert.
        val box = ImageDownscale.placeOnPage(encoded.widthPx, encoded.heightPx, page, visible)
        val id = editor.insertImage(
            bytes = encoded.webp,
            box = box,
            style = dev.inkstone.app.ink.ObjectToolState.DEFAULT_TEXT_STYLE,
        ) ?: return null
        return ImageInsertReport(id, encoded.sourceBytes, encoded.webp.size, encoded.widthPx, encoded.heightPx)
    }

    /** Put a line in front of the user through the editor's own snackbar — the only channel there is. */
    fun report(message: String) = editor.showMessage(message)

    /** Every saved Element, for the panel. */
    suspend fun elements(): List<dev.inkstone.core.storage.Element> = elements.list()

    suspend fun deleteElement(id: dev.inkstone.core.model.ElementId) = elements.delete(id)

    /** A picture of [element] for the panel, or null. See [ElementPreviews]. */
    suspend fun elementPreview(element: dev.inkstone.core.storage.Element, widthPx: Int): ByteArray? =
        elementPreviews.render(element, widthPx)

    /**
     * Stamp [element] with its top-left at [topLeft].
     *
     * @return the new objects' ids, so the screen can select what it just dropped.
     */
    suspend fun stampElement(
        element: dev.inkstone.core.storage.Element,
        topLeft: dev.inkstone.core.model.PagePoint,
    ): List<dev.inkstone.core.model.ObjectId> {
        val placed = editor.stampElement(elements, element, topLeft)
        // ⭐ So an edit of this Element can find this note again (SPEC §4.9.7).
        if (placed.isNotEmpty()) noteId?.let { elements.recordUse(element.id, it) }
        return placed
    }

    /**
     * ⭐ docs/HANDWRITING-SYNTHESIS.md §6.3 — a written phrase onto the page on screen, one undo entry,
     * replacing [replacing] in the same entry. @return the new strokes, to select.
     */
    suspend fun stampHandwriting(
        strokes: List<dev.inkstone.core.model.StrokeGeometry>,
        tags: List<dev.inkstone.core.model.GeneratedInk>,
        record: dev.inkstone.core.model.PhraseRecord,
        replacing: List<dev.inkstone.core.model.ObjectId> = emptyList(),
    ): List<dev.inkstone.core.model.ObjectId> = editor.stampHandwriting(strokes, tags, record, scratchIds, replacing)

    /** The generated phrase [ids] belong to on the page on screen, or null. */
    suspend fun phraseOf(ids: List<dev.inkstone.core.model.ObjectId>) = editor.phraseOf(ids)

    /** The note on screen, for D15's *this note's writing first*. Null in the Element editor. */
    val openNoteId: DocumentId? get() = if (editingElement == null) noteId else null

    /**
     * ⭐ Save [ids] as a named Element — handwriting included.
     *
     * ⚠️ It used to take an *area* and capture only text boxes, images and shapes, because `:app`
     * cannot measure a stroke (SPEC §5.4) and the lasso gave out a count rather than ids. The surface
     * answers both now (`InkSurfaceView.objectsWithin`, `boundsOf`, `InkSelection.ids`), so the
     * caller hands over exactly what was enclosed and the box it was drawn at.
     *
     * @return the element, or null when none of [ids] is on the page.
     */
    suspend fun saveElement(
        name: String,
        collection: String,
        ids: List<dev.inkstone.core.model.ObjectId>,
        measured: dev.inkstone.core.model.PageRect?,
    ): dev.inkstone.core.storage.Element? {
        val fold = editor.currentFold() ?: return null
        return elements.save(name, collection, fold, ids, editor.assets(), measured)
    }

    /**
     * The decoded pictures the ink surface draws, cached by asset name. See [PageAssetCache] — it
     * reads only the pictures asked for, never the note's whole `assets/` family.
     */
    val assetCache: PageAssetCache = PageAssetCache(viewModelScope, source = { names -> editor.assets(names) })

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

    private val stripCache = PageStripCache<dev.inkstone.android.ink.PageNeighbour>()

    /**
     * ⭐ A picture of [pageId] [widthPx] wide, for the strip beside the page being edited — reused
     * while the page has not changed, redrawn when it has.
     *
     * Folded from the **open session**, like [pageThumbnail], for the same reason: the journal has
     * ops the bundle does not. Null when the page cannot be found or drawn, which the strip shows as
     * no neighbour at all rather than as a broken one.
     *
     * ⚠️ A failure to draw is swallowed except for cancellation. The strip is decoration over a page
     * that is already safe on disk, so an out-of-memory render must cost the picture and nothing
     * else.
     */
    @Suppress("SwallowedException") // Deliberate: see above - a missing picture is a missing neighbour.
    suspend fun pagePicture(pageId: PageId, widthPx: Int): dev.inkstone.android.ink.PageNeighbour? {
        val noteId = editor.openDocumentId ?: return null
        val folded = editor.foldedPage(pageId) ?: return null
        val revision = PageRevision.of(folded, widthPx)
        stripCache.get(pageId, revision)?.let { return it }
        val bitmap = try {
            stripRenderer.render(noteId, folded, widthPx)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
            null
        } catch (failure: OutOfMemoryError) {
            // Three pictures of a long note is the one allocation here that can plausibly fail.
            null
        } ?: return null
        return dev.inkstone.android.ink.PageNeighbour(pageId, bitmap, folded.size).also {
            stripCache.put(pageId, revision, it)
        }
    }

    /**
     * Called from `Activity.onStop`, the last point Android guarantees the process is alive. It
     * makes the last strokes durable every time, and it materializes (SPEC §5.1) **only when
     * [finishing]**, i.e. when the note is being closed.
     *
     * ⛔ **It materialized on every stop until 2026-10, and on a big note that got the note's own
     * screen destroyed.** A stop is also the Element editor opening on top, a switch to another app
     * and the screen timing out. Encoding the physics note's bundle allocates 250–325 MB (see
     * `NoteStore.materialize`). Whenever a collection leaves the heap above three quarters of its
     * limit, ActivityThread's GC watcher asks the system to destroy this app's stopped activities,
     * and a materialize-on-stop ran at the very moment the note's activity had become one. So the
     * note's activity was destroyed (`wm_destroy_activity … low-mem`, Tab S9 FE+, 2026-10-10),
     * this view model was cleared and its editor closed. Coming back from the Element editor or from
     * another app then reloaded the whole note, and once it ran out of memory doing so beside
     * `NoteSessionHost`'s own session over the same note. `docs/NOTE-STAYS-LOADED.md`.
     *
     * Saving alone loses nothing. Everything the save drains is in `journal.ops`, and a kill before
     * the note is closed costs a replay on the next open, which is the trade SPEC §5.1 makes for
     * every autosave. The published file is still at most one editing session stale, because the
     * session has not ended. An upload does not wait for this either: `OutboxDrainer` materializes
     * through the gate before every PUT.
     */
    fun onStopped(finishing: Boolean) {
        // ⛔ Not `viewModelScope`: Back runs `onStop` and then clears this view model, which cancelled
        // the save at its first suspension and threw away the strokes it had just drained. The
        // host's scope outlives the screen — see `NoteSessionHost.saveOnLeaving`. Only a note with
        // no id (M2's open-the-latest fallback, and the Element editor's scratch note) has no host
        // registration, and keeps the old path.
        val id = noteId ?: run {
            viewModelScope.launch { stopWork(finishing) }
            return
        }
        sessions.saveOnLeaving(id) { stopWork(finishing) }
    }

    private suspend fun stopWork(finishing: Boolean) {
        if (finishing) editor.materializeIfChanged() else editor.save()
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
     *
     * ⚠️ **[NoteEditor.openDocumentId], not `state.value.documentId`.** This is called from inside
     * the editor's own open, and the state flow does not carry the id until `publish()` — one
     * statement *after* the `display()` that triggers this. Reading it there answered null, and a
     * null here is "this page has no paper behind it", which is indistinguishable from a blank
     * page and never retried. See that property.
     */
    suspend fun sourcePage(background: Background, size: PageSize) =
        editor.openDocumentId?.let { sourcePages.render(it, background, size) }

    override fun onCleared() {
        // Unregister and close together, under the note's mutex, and only once the close-time save
        // queued by `onStop` has run — see `NoteSessionHost.retire`. Closing first would cancel the
        // editor's scope under that save; unregistering first would let the drainer publish the
        // note while the save rewrites it. Doing both in one step still keeps the old guarantee: a
        // pull cannot reach the registry between them and hand a merge to a closed session.
        val id = noteId
        if (id != null) sessions.retire(id, handle) { editor.close() }
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
        // Ends the autosave loop. Not a substitute for `onStopped` from the Activity —
        // `onCleared` is not guaranteed to run before the process dies, which is why the journal
        // exists at all (SPEC §5.1). With an id it is [NoteSessionHost.retire]'s to do, above.
        //
        // ⚠️ And deliberately not a materialize either. If the activity finished, `onStopped` has
        // already queued one and `retire` runs after it. If it did not, this is the system
        // destroying a stopped screen to get memory back — the worst moment in the process's life
        // to allocate a whole bundle. The journal holds every stroke, and the next close of the
        // note folds it in.
        if (id == null) editor.close()
        super.onCleared()
    }

    /** What the Element editor's Done came to. */
    sealed interface ElementEditOutcome {
        /** Saved; [copies] copies across [notes] could now be updated. */
        class Saved(
            val revision: dev.inkstone.app.elements.ElementRevision,
            val copies: Int,
            val notes: List<DocumentId>,
        ) : ElementEditOutcome

        /** Nothing on the canvas — an Element of nothing is not saved over a real one. */
        data object Empty : ElementEditOutcome

        /** The Element was removed while it was being edited, or the editor never opened. */
        data object Gone : ElementEditOutcome
    }

    /**
     * What one image insert cost, in bytes — the numbers ROADMAP M8c's downscale rule is judged by.
     *
     * Reported rather than logged, because *"insert-time downscale"* is a claim about sizes and a
     * claim about sizes with no measurement behind it is the kind of thing this repo has shipped
     * before. `InsertedImage` produces them; the screen shows them once.
     */
    class ImageInsertReport(
        val id: dev.inkstone.core.model.ObjectId,
        val sourceBytes: Int,
        val storedBytes: Int,
        val widthPx: Int,
        val heightPx: Int,
    )

    companion object {
        /** The note to open, as a [SavedStateHandle] key. */
        const val ARG_NOTE_ID: String = "noteId"

        /** ⭐ The Element to edit instead of a note (SPEC §4.9.7). */
        const val ARG_ELEMENT_ID: String = "elementId"

        /** See [elementGridPt]. */
        const val ARG_GRID_PT: String = "elementGridPt"

        private const val ARG_EDIT_SESSION: String = "elementEditSession"

        /** Where scratch notes live — beside the Element files, never under `notes/`. */
        private const val EDITING_DIR: String = "elements/editing"

        /** Where a finished session's scratch folder goes — see [retireOldScratch]. */
        private const val SCRATCH_TRASH_DIR: String = "elements/trash/editing"

        /** The Element editor's page: far larger than any screen at any zoom a person uses. */
        private const val EDITOR_PAGE_PT: Float = 3000f

        /** The smallest box the editor frames — an Element of a dot still opens at a usable zoom. */
        private const val MIN_FRAME_PT: Float = 120f

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
