package dev.inkstone.app.library

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.inkstone.android.pdf.PdfOpenResult
import dev.inkstone.app.sync.NoteSessionHost
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.Folder
import dev.inkstone.core.model.FolderCover
import dev.inkstone.core.model.FolderIcon
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.model.FolderTree
import dev.inkstone.core.model.LibraryLayout
import dev.inkstone.core.model.LibrarySort
import dev.inkstone.core.model.NoteSummary
import dev.inkstone.core.storage.PdfOpenMode
import dev.inkstone.core.storage.RecoveryLoss
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ROADMAP M3 — what the library screen shows and what its buttons do.
 *
 * ## The one design decision worth stating
 *
 * The **whole** folder list and the **whole** note list are held, and the current folder's
 * contents are derived from them. Not one query per navigation step.
 *
 * That sounds wasteful and is not: SPEC §9 sizes the library at 500 notes across 50 folders,
 * which is a few hundred kilobytes of rows. What it buys is that navigating into a folder,
 * searching, sorting and multi-selecting are all pure functions of state already in memory —
 * so none of them waits on a database round trip, and the 60 fps criterion is about drawing
 * rather than about fetching. It also means the breadcrumb and the "move to…" picker can see
 * the tree, which a per-folder query cannot.
 *
 * Search deliberately ignores the current folder: typing a title should find the note
 * wherever it is, which is the entire reason for having search in an app that also has
 * folders.
 */
// One method per thing the library screen can do, almost all of them a single delegation to
// the repository. The count tracks the size of the UI, not the complexity of this class.
@HiltViewModel
@Suppress("TooManyFunctions")
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val fixture: LibraryFixture,
    /**
     * The single-writer gate over a note's one `NoteSession` (M6).
     *
     * The library needs it for exactly one action: a rename writes the note's document, not just its
     * index row (SPEC §7.7 layer 2), and the note may be open in the editor while this screen is
     * visible — split-screen, or a paused Activity whose `ViewModel` is still alive.
     */
    private val sessions: NoteSessionHost,
    /**
     * Read here and nowhere deeper. A `Uri` is only readable while this process holds
     * the grant the picker made, and neither `:core:storage` nor `:android:pdf` may
     * learn what a `ContentResolver` is.
     */
    private val resolver: ContentResolver,
) : ViewModel() {

    private val controls = MutableStateFlow(LibraryControls())

    /** Set by an action that needs to say something, cleared by the UI. */
    private val message = MutableStateFlow<String?>(null)

    /**
     * The three sync-shaped overlays on the library, combined once.
     *
     * Folded together here rather than passed individually because `combine` is only typed up to
     * five sources, and the untyped `vararg` overload would turn every one of these into an
     * `Any?` cast at the point where getting them the wrong way round is a silent bug — the
     * pinned-folder set and the flagged-note set are both `Set<…>`.
     */
    private val overlay: Flow<SyncOverlay> = combine(
        repository.pinnedFolders,
        repository.notesNeedingAttention,
        repository.openAttentionCount,
    ) { pinned, flagged, count -> SyncOverlay(pinned, flagged, count) }

    val state: StateFlow<LibraryUiState> = combine(
        repository.folders,
        repository.notes,
        controls,
        message,
        overlay,
    ) { folders, notes, controls, message, overlay ->
        uiStateOf(folders, notes, controls, message, overlay)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryUiState())

    val trash: StateFlow<TrashUiState> = combine(
        repository.trashedNotes,
        repository.trashedFolders,
    ) { notes, folders ->
        TrashUiState(notes = notes, folders = folders)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TrashUiState())

    init {
        // Expire old trash, repair the index against the filesystem, bound the thumbnail
        // cache. All three are I/O and none of them blocks the first frame — the library
        // renders from whatever the index already says and corrects itself a moment later.
        viewModelScope.launch { repository.startup() }
    }

    // -----------------------------------------------------------------------
    // Navigation and view options
    // -----------------------------------------------------------------------

    fun openFolder(id: FolderId?) = controls.update { it.copy(folderId = id, selection = emptySet()) }

    /** Up one level. Does nothing at the root, which is what a back press at the root should do. */
    fun goUp(): Boolean {
        val current = state.value.breadcrumb.lastOrNull() ?: return false
        openFolder(current.parentId)
        return true
    }

    fun setLayout(layout: LibraryLayout) = controls.update { it.copy(layout = layout) }

    fun setSort(sort: LibrarySort) = controls.update {
        // Tapping the active sort flips its direction. Name defaults to A–Z and the two date
        // sorts to newest-first, because that is what each one is usually wanted for.
        if (it.sort == sort) {
            it.copy(descending = !it.descending)
        } else {
            it.copy(sort = sort, descending = sort != LibrarySort.NAME)
        }
    }

    fun setQuery(query: String) = controls.update { it.copy(query = query) }

    fun setFavouritesOnly(only: Boolean) = controls.update { it.copy(favouritesOnly = only) }

    // -----------------------------------------------------------------------
    // Selection (ROADMAP M3 — multi-select)
    // -----------------------------------------------------------------------

    fun toggleSelection(id: DocumentId) = controls.update {
        it.copy(selection = if (id in it.selection) it.selection - id else it.selection + id)
    }

    fun clearSelection() = controls.update { it.copy(selection = emptySet()) }

    fun selectAllVisible() = controls.update { current ->
        current.copy(selection = state.value.notes.mapTo(LinkedHashSet()) { it.id })
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    /** @param onCreated invoked with the new note so the caller can open it. */
    fun createNote(onCreated: (DocumentId) -> Unit) = viewModelScope.launch {
        onCreated(repository.createNote(folderId = controls.value.folderId))
    }

    fun createFolder(name: String) = viewModelScope.launch {
        repository.createFolder(controls.value.folderId, name)
    }

    /**
     * ROADMAP M4 — import a PDF the user picked.
     *
     * The file is read here and the bytes handed on, rather than a `Uri` being passed
     * down: `:android:pdf` must not learn what a `ContentResolver` is, and a `Uri` is
     * only readable while this process holds the grant the picker just made.
     *
     * @param onOpened invoked with the note so the caller can open it — but only for a
     *   clean import. A **recovery** is deliberately not opened: SPEC §5.6 requires the
     *   user to be told what was lost first, and dropping them into the editor is how
     *   that message gets dismissed without being read.
     */
    fun importPdf(uri: Uri, onOpened: (DocumentId) -> Unit) = viewModelScope.launch {
        val bytes = withContext(Dispatchers.IO) {
            runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        }
        if (bytes == null) {
            message.value = "That file could not be read."
            return@launch
        }

        when (val result = repository.importPdf(bytes, titleFor(uri))) {
            is PdfOpenResult.Opened -> when (val mode = result.mode) {
                is PdfOpenMode.Recovered -> message.value = recoveryMessage(mode, result)
                else -> onOpened(result.documentId)
            }

            // SPEC §5.8 / §5.6 — a file from a newer schema is not opened and above all
            // is not re-saved, because re-saving it in this build's vocabulary is how the
            // newer version's data gets destroyed.
            is PdfOpenResult.Refused -> message.value = when (result.mode) {
                is PdfOpenMode.TooNew -> "That note was written by a newer version of Inkstone. Update first."
                else -> "There was nothing in that PDF to recover."
            }
        }
    }

    /**
     * SPEC §5.6 point 4 — *"warn explicitly, and say what was lost."*
     *
     * Every loss is named, and the one that is a *decision* rather than a condolence —
     * ink that could not be identified, and so will appear twice if the other tablet
     * still has it — is said last and said plainly.
     */
    private fun recoveryMessage(mode: PdfOpenMode.Recovered, result: PdfOpenResult.Opened): String {
        val lost = mode.losses.mapNotNull { loss ->
            when (loss) {
                RecoveryLoss.PRESSURE_AND_WIDTH -> "pen pressure and stroke width"
                RecoveryLoss.OP_HISTORY -> "edit history"
                RecoveryLoss.UNDO_STACK -> "undo"
                RecoveryLoss.PRISTINE_ORIGINAL -> "the untouched original PDF"
                RecoveryLoss.FLATTENED_PAGE -> "per-stroke detail on dense pages"
                RecoveryLoss.UNIDENTIFIED_OBJECTS -> null
            }
        }
        return buildString {
            append("Recovered from the PDF — the embedded note data was missing or damaged. Lost: ")
            append(lost.joinToString(", "))
            append(".")
            if (result.unidentifiedStrokes > 0 || result.unidentifiedPages > 0) {
                append(
                    " ${result.unidentifiedStrokes} strokes and ${result.unidentifiedPages} pages could not be " +
                        "identified; if another device still has this note they will appear twice after syncing.",
                )
            }
        }
    }

    /** The picked file's display name, minus the extension. A title the user will recognise. */
    private fun titleFor(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".pdf").orEmpty().ifEmpty { "Imported PDF" }

    /**
     * ⭐ Through [sessions], not through the repository — SPEC §7.7 layer 2.
     *
     * A rename writes the note's own `Document.title` and stamps `titleClock` from that note's Lamport
     * generator, so it needs the note's single `NoteSession` (and the live editor's, if one holds it).
     * Calling `repository` here is what M6 shipped, and it renamed an index row only: the register the
     * sync layer resolves titles with was never written, so two devices renaming one note kept two
     * titles for good, silently. The repository still does the index and remote-path half — the host
     * calls it once the document is stamped.
     */
    fun renameNote(id: DocumentId, title: String) = viewModelScope.launch { sessions.renameNote(id, title) }

    fun renameFolder(id: FolderId, name: String) = viewModelScope.launch { repository.renameFolder(id, name) }

    fun setFolderAppearance(id: FolderId, colorArgb: Int?, icon: FolderIcon, cover: FolderCover) =
        viewModelScope.launch { repository.setFolderAppearance(id, colorArgb, icon, cover) }

    /**
     * Start (or resume) drawing a folder's cover.
     *
     * @param onCreated invoked with the note to open — it is an ordinary note in the
     *   ordinary editor, which is the whole trick. See `LibraryRepository.createDrawnCover`.
     */
    fun drawCover(id: FolderId, onCreated: (DocumentId) -> Unit) = viewModelScope.launch {
        repository.createDrawnCover(id)?.let(onCreated) ?: run { message.value = "Could not create a cover" }
    }

    /** @param encoded already decoded, downscaled and re-encoded by the caller. */
    fun setImageCover(id: FolderId, encoded: ByteArray) = viewModelScope.launch {
        repository.setImageCover(id, encoded)
    }

    /** The picture for a folder's tile, or null when it shows its icon. */
    suspend fun folderCover(folder: Folder) = repository.coverFor(folder)

    fun setFavourite(id: DocumentId, favourite: Boolean) = viewModelScope.launch {
        repository.setFavourite(id, favourite)
    }

    fun moveSelection(folderId: FolderId?) = viewModelScope.launch {
        val ids = controls.value.selection
        repository.moveNotes(ids, folderId)
        clearSelection()
        message.value = "Moved ${ids.size} ${notesWord(ids.size)}"
    }

    fun moveFolder(id: FolderId, parentId: FolderId?) = viewModelScope.launch {
        if (!repository.moveFolder(id, parentId)) {
            // The subtree would be detached from the root and unreachable forever. Refused
            // with a reason rather than silently ignored.
            message.value = "A folder cannot be moved inside itself"
        }
    }

    fun duplicate(id: DocumentId) = viewModelScope.launch {
        if (repository.duplicate(id) == null) message.value = "Could not duplicate that note"
    }

    /**
     * ROADMAP M3 — **trash, never delete.** There is no method here that destroys a note,
     * and the wording in the UI says "Trash" for the same reason.
     */
    fun trashSelection() = viewModelScope.launch {
        val ids = controls.value.selection.toList()
        repository.trashNotes(ids)
        clearSelection()
        message.value = "${ids.size} ${notesWord(ids.size)} moved to trash — kept for 30 days"
    }

    fun trashNote(id: DocumentId) = viewModelScope.launch {
        repository.trashNotes(listOf(id))
        message.value = "Moved to trash — kept for 30 days"
    }

    fun trashFolder(id: FolderId) = viewModelScope.launch {
        val carried = state.value.allNotes.count { !it.isTrashed && it.folderId == id }
        repository.trashFolder(id)
        if (controls.value.folderId == id) openFolder(state.value.breadcrumb.lastOrNull()?.parentId)
        message.value = "Folder moved to trash with $carried ${notesWord(carried)} — kept for 30 days"
    }

    fun restoreNote(id: DocumentId) = viewModelScope.launch {
        if (!repository.restoreNote(id)) message.value = "Could not restore — a note with that id is already open"
    }

    fun restoreFolder(id: FolderId) = viewModelScope.launch {
        val failed = repository.restoreFolder(id)
        if (failed.isNotEmpty()) {
            message.value = "Folder restored; ${failed.size} ${notesWord(failed.size)} stayed in the trash"
        }
    }

    /**
     * ROADMAP M3's frame-rate gate needs 500 notes across 50 folders. See [LibraryFixture]:
     * this is the library-scale twin of M1's "Load 5,000 strokes", and it is slow because the
     * notes contain real ink — a library of blank pages would measure nothing.
     */
    fun fillForMeasurement() = viewModelScope.launch {
        message.value = "Generating 500 notes — this takes a couple of minutes"
        val created = fixture.generate { done, total -> message.value = "Generated $done of $total notes…" }
        message.value = "$created notes created. Scroll the library, then read dumpsys gfxinfo."
    }

    fun dismissMessage() {
        message.value = null
    }

    // -----------------------------------------------------------------------
    // Thumbnails
    // -----------------------------------------------------------------------

    /**
     * The library thumbnail for [note] (SPEC §5.7).
     *
     * Suspends; the caller is a `LaunchedEffect` per cell, so a cell that scrolls away
     * cancels its own load. Rendering is bounded inside [Thumbnails], not here.
     */
    suspend fun thumbnail(note: NoteSummary) = repository.thumbnailOf(note)
}

/**
 * The derived view, as a free function.
 *
 * Outside the class on purpose: it reads state and writes none, so keeping it a member would
 * only make it look as though it might.
 */
private fun uiStateOf(
    folders: List<Folder>,
    notes: List<NoteSummary>,
    controls: LibraryControls,
    message: String?,
    overlay: SyncOverlay,
): LibraryUiState {
    val searching = controls.query.isNotBlank()
    val visibleNotes = LibraryFiltering.visibleNotes(
        notes = notes,
        folderId = controls.folderId,
        query = controls.query,
        favouritesOnly = controls.favouritesOnly,
        sort = controls.sort,
        descending = controls.descending,
    )
    val visibleFolders = LibraryFiltering.visibleFolders(folders, controls.folderId, controls.query)

    return LibraryUiState(
        // "Covered by a pin", not "is the pinned folder": a subfolder of a pinned folder syncs
        // just as much, and a cloud on only the folder holding the mapping row would describe
        // the implementation rather than the outcome.
        syncedFolders = coveredByPins(overlay.pinnedFolders, folders),
        notesNeedingAttention = overlay.flaggedNotes,
        attentionCount = overlay.attentionCount,
        folderId = controls.folderId,
        breadcrumb = FolderTree.pathOf(controls.folderId, folders.associateBy { it.id }),
        folders = visibleFolders,
        notes = visibleNotes,
        allFolders = folders.filterNot { it.isTrashed },
        allNotes = notes,
        layout = controls.layout,
        sort = controls.sort,
        descending = controls.descending,
        query = controls.query,
        favouritesOnly = controls.favouritesOnly,
        searching = searching,
        // A selected note that has since been trashed or moved out of view must not stay
        // selected, or "Trash 3 notes" would act on a note the user can no longer see.
        selection = controls.selection intersect visibleNotes.mapTo(HashSet()) { it.id },
        message = message,
        loaded = true,
    )
}

private fun notesWord(count: Int) = if (count == 1) "note" else "notes"

private fun MutableStateFlow<LibraryControls>.update(transform: (LibraryControls) -> LibraryControls) {
    value = transform(value)
}

/** Long enough to survive a rotation without re-subscribing to the database. */
private const val STOP_TIMEOUT_MS = 5_000L

/** The knobs the user turns. Separate from [LibraryUiState], which is derived from these. */
private data class LibraryControls(
    val folderId: FolderId? = null,
    val layout: LibraryLayout = LibraryLayout.GRID,
    val sort: LibrarySort = LibrarySort.MODIFIED,
    val descending: Boolean = true,
    val query: String = "",
    val favouritesOnly: Boolean = false,
    val selection: Set<DocumentId> = emptySet(),
)

/**
 * Every folder that syncs: the pinned ones, plus everything beneath them.
 *
 * Walks each folder's ancestry rather than descending from each pin, because a folder's parent
 * chain is bounded and `FolderTree.pathOf` already terminates on a cycle or a missing parent —
 * which folders arriving from a server can both be (SPEC §9).
 */
private fun coveredByPins(pins: Set<FolderId>, folders: List<Folder>): Set<FolderId> {
    if (pins.isEmpty()) return emptySet()
    val byId = folders.associateBy { it.id }
    return folders
        .filterTo(HashSet()) { folder ->
            FolderTree.pathOf(folder.id, byId).any { it.id in pins }
        }
        .mapTo(HashSet()) { it.id }
}

/**
 * The sync-shaped facts the library overlays on its own rows (M6).
 *
 * @property flaggedNotes notes with an open SPEC §7.7 entry. Ids rather than entries — the tile draws
 *   one flag whatever the reason, and the reasons belong on the "Needs attention" screen.
 */
private data class SyncOverlay(
    val pinnedFolders: Set<FolderId>,
    val flaggedNotes: Set<DocumentId>,
    val attentionCount: Long,
)

/** Everything the library screen draws. */
data class LibraryUiState(
    val folderId: FolderId? = null,
    val breadcrumb: List<Folder> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val notes: List<NoteSummary> = emptyList(),
    val allFolders: List<Folder> = emptyList(),
    val allNotes: List<NoteSummary> = emptyList(),
    /** Folders whose notes sync, pinned or beneath a pin (M6). Drives the cloud badge. */
    val syncedFolders: Set<FolderId> = emptySet(),
    /** Notes with an open SPEC §7.7 entry (M6). Drives the flag on a tile. */
    val notesNeedingAttention: Set<DocumentId> = emptySet(),
    /** How many entries are open. Zero hides the toolbar's button entirely. */
    val attentionCount: Long = 0L,
    val layout: LibraryLayout = LibraryLayout.GRID,
    val sort: LibrarySort = LibrarySort.MODIFIED,
    val descending: Boolean = true,
    val query: String = "",
    val favouritesOnly: Boolean = false,
    val searching: Boolean = false,
    val selection: Set<DocumentId> = emptySet(),
    val message: String? = null,
    /** False until the first database emission, so "no notes yet" is not shown to a full library. */
    val loaded: Boolean = false,
) {
    val isEmpty: Boolean get() = folders.isEmpty() && notes.isEmpty()
}

/** ROADMAP M3 — the trash. Restore only; nothing here deletes (CLAUDE.md rule 1). */
data class TrashUiState(val notes: List<NoteSummary> = emptyList(), val folders: List<Folder> = emptyList()) {
    val isEmpty: Boolean get() = notes.isEmpty() && folders.isEmpty()
}
