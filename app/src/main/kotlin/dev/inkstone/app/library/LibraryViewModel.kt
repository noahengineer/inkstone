package dev.inkstone.app.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.Folder
import dev.inkstone.core.model.FolderCover
import dev.inkstone.core.model.FolderIcon
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.model.FolderTree
import dev.inkstone.core.model.LibraryLayout
import dev.inkstone.core.model.LibrarySort
import dev.inkstone.core.model.NoteSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
class LibraryViewModel @Inject constructor(private val repository: LibraryRepository) : ViewModel() {

    private val controls = MutableStateFlow(LibraryControls())

    /** Set by an action that needs to say something, cleared by the UI. */
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<LibraryUiState> = combine(
        repository.folders,
        repository.notes,
        controls,
        message,
    ) { folders, notes, controls, message ->
        uiStateOf(folders, notes, controls, message)
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

    fun renameNote(id: DocumentId, title: String) = viewModelScope.launch { repository.renameNote(id, title) }

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

/** Everything the library screen draws. */
data class LibraryUiState(
    val folderId: FolderId? = null,
    val breadcrumb: List<Folder> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val notes: List<NoteSummary> = emptyList(),
    val allFolders: List<Folder> = emptyList(),
    val allNotes: List<NoteSummary> = emptyList(),
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
