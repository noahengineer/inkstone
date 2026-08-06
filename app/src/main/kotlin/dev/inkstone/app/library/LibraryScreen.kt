package dev.inkstone.app.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.Folder
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.model.LibraryLayout
import dev.inkstone.core.model.LibrarySort
import dev.inkstone.core.model.NoteSummary
import androidx.compose.foundation.lazy.grid.items as gridItems

/**
 * ROADMAP M3 — the library.
 *
 * ## Why a grid of `LazyVerticalGrid` items and not a column of rows
 *
 * The acceptance criterion is *"500 notes across 50 nested folders → library scrolls at
 * 60 fps"*, and the thing that makes that reachable is composing only what is visible.
 * Every item therefore carries a **stable key** — the note or folder id, never its position
 * — so scrolling reuses compositions instead of rebuilding them, and a thumbnail that has
 * loaded stays loaded when a sibling above it changes.
 *
 * Thumbnails are loaded per cell in a `LaunchedEffect` keyed on the note's revision, so a
 * cell scrolled off screen cancels its own load. Generation is bounded inside [Thumbnails];
 * nothing here needs to know that, which is the point.
 *
 * The visual design is deliberately plain. M10 owns polish; this is the plainest thing that
 * makes every M3 acceptance criterion reachable on the tablet.
 */
@Composable
fun LibraryScreen(state: LibraryUiState, actions: LibraryActions, modifier: Modifier = Modifier) {
    val dialogs = remember { LibraryDialogState() }

    // `OpenDocument` rather than `GetContent`: it returns a URI to the file the user
    // actually picked, which is what lets the import read a 200 MB lecture out of
    // Drive or an SD card instead of making the system copy it into a cache first.
    val importPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(actions.onImportPdf)
    }

    // Back goes up a folder before it leaves the app, which is what the breadcrumb implies.
    BackHandler(enabled = state.breadcrumb.isNotEmpty() || state.selection.isNotEmpty()) {
        if (state.selection.isNotEmpty()) actions.onClearSelection() else actions.onGoUp()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LibraryToolbar(state = state, actions = actions, onNewFolder = { dialogs.creatingFolder = true })

            if (state.selection.isNotEmpty()) {
                SelectionBar(
                    count = state.selection.size,
                    actions = actions,
                    onMove = { dialogs.movingNotes = true },
                )
            }

            when {
                !state.loaded -> Unit

                // The first frame. An empty-state here would be a lie.
                state.isEmpty -> EmptyLibrary(searching = state.searching, query = state.query)

                state.layout == LibraryLayout.GRID -> LibraryGrid(state, actions, dialogs)

                else -> LibraryList(state, actions, dialogs)
            }
        }

        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ROADMAP M4 — PDF import, beside "New note" rather than buried in the
            // toolbar. Importing a lecture is half of what this app is for, and a note
            // started from a PDF is not a different kind of note.
            ExtendedFloatingActionButton(
                onClick = { importPdf.launch(arrayOf(PDF_MIME)) },
                text = { Text("Import PDF") },
                icon = { Text("⇩") },
            )
            ExtendedFloatingActionButton(
                onClick = actions.onNewNote,
                text = { Text("New note") },
                icon = { Text("✎") },
            )
        }
    }

    LibraryDialogHost(state = state, actions = actions, dialogs = dialogs)
}

/**
 * Which dialog, if any, is open.
 *
 * One holder rather than five `mutableStateOf`s threaded through every list and card: the
 * grid and the list both raise the same four, and the screen's job is to show them, not to
 * carry them.
 */
@Stable
class LibraryDialogState {
    var renaming by mutableStateOf<RenameTarget?>(null)
    var creatingFolder by mutableStateOf(false)
    var movingNotes by mutableStateOf(false)
    var movingFolder by mutableStateOf<Folder?>(null)
    var stylingFolder by mutableStateOf<Folder?>(null)
}

@Composable
private fun LibraryDialogHost(state: LibraryUiState, actions: LibraryActions, dialogs: LibraryDialogState) {
    RenameAndCreateDialogs(actions, dialogs)
    MoveDialogs(state, actions, dialogs)

    dialogs.stylingFolder?.let { folder ->
        FolderAppearanceDialog(
            folder = folder,
            cover = rememberFolderCover(folder, actions),
            onDismiss = { dialogs.stylingFolder = null },
            onConfirm = { color, icon, cover ->
                actions.onStyleFolder(folder.id, color, icon, cover)
                dialogs.stylingFolder = null
            },
            onDrawCover = {
                actions.onDrawCover(folder.id)
                dialogs.stylingFolder = null
            },
            onPickImage = {
                actions.onPickCoverImage(folder.id)
                dialogs.stylingFolder = null
            },
        )
    }
}

@Composable
private fun RenameAndCreateDialogs(actions: LibraryActions, dialogs: LibraryDialogState) {
    dialogs.renaming?.let { target ->
        TextPromptDialog(
            title = if (target.folderId != null) "Rename folder" else "Rename note",
            initial = target.name,
            onDismiss = { dialogs.renaming = null },
            onConfirm = { name ->
                target.folderId?.let { actions.onRenameFolder(it, name) }
                target.noteId?.let { actions.onRenameNote(it, name) }
                dialogs.renaming = null
            },
        )
    }

    if (dialogs.creatingFolder) {
        TextPromptDialog(
            title = "New folder",
            initial = "",
            onDismiss = { dialogs.creatingFolder = false },
            onConfirm = {
                actions.onNewFolder(it)
                dialogs.creatingFolder = false
            },
        )
    }
}

@Composable
private fun MoveDialogs(state: LibraryUiState, actions: LibraryActions, dialogs: LibraryDialogState) {
    if (dialogs.movingNotes) {
        FolderPickerDialog(
            title = "Move ${state.selection.size} to…",
            folders = state.allFolders,
            onDismiss = { dialogs.movingNotes = false },
            onPick = {
                actions.onMoveSelection(it)
                dialogs.movingNotes = false
            },
        )
    }

    dialogs.movingFolder?.let { folder ->
        FolderPickerDialog(
            title = "Move “${folder.name}” to…",
            // A folder cannot be moved into itself; the repository refuses it too, but not
            // offering it is better than explaining it afterwards.
            folders = state.allFolders.filterNot { it.id == folder.id },
            onDismiss = { dialogs.movingFolder = null },
            onPick = {
                actions.onMoveFolder(folder.id, it)
                dialogs.movingFolder = null
            },
        )
    }
}

/** The five things a folder's menu can start, wired to the dialog state and the actions. */
private fun folderActions(folder: Folder, actions: LibraryActions, dialogs: LibraryDialogState) = FolderActions(
    onOpen = { actions.onOpenFolder(folder.id) },
    onRename = { dialogs.renaming = RenameTarget(folderId = folder.id, name = folder.name) },
    onMove = { dialogs.movingFolder = folder },
    onStyle = { dialogs.stylingFolder = folder },
    onTrash = { actions.onTrashFolder(folder.id) },
)

@Composable
private fun LibraryToolbar(state: LibraryUiState, actions: LibraryActions, onNewFolder: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = actions.onQueryChange,
                singleLine = true,
                label = { Text("Search titles and folders") },
                modifier = Modifier.weight(1f),
            )
            SortMenu(state = state, onSort = actions.onSort)
            TextButton(
                onClick = {
                    actions.onLayout(
                        if (state.layout == LibraryLayout.GRID) LibraryLayout.LIST else LibraryLayout.GRID,
                    )
                },
            ) { Text(if (state.layout == LibraryLayout.GRID) "List" else "Grid") }
            TextButton(onClick = onNewFolder) { Text("New folder") }
            TextButton(onClick = actions.onOpenTrash) { Text("Trash") }
            TextButton(onClick = actions.onOpenNextcloud) { Text("Nextcloud") }
            MeasureMenu(actions)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Breadcrumb(breadcrumb = state.breadcrumb, onOpen = actions.onOpenFolder)
            FilterChip(
                selected = state.favouritesOnly,
                onClick = { actions.onFavouritesOnly(!state.favouritesOnly) },
                label = { Text("★ Favourites") },
            )
        }
    }
}

@Composable
private fun Breadcrumb(breadcrumb: List<Folder>, onOpen: (FolderId?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onOpen(null) }) { Text("Library") }
        for (folder in breadcrumb) {
            Text(" / ", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { onOpen(folder.id) }) {
                Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SortMenu(state: LibraryUiState, onSort: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val arrow = if (state.descending) "↓" else "↑"
    Box {
        AssistChip(onClick = { open = true }, label = { Text("${state.sort.label} $arrow") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (sort in LibrarySort.entries) {
                DropdownMenuItem(
                    text = { Text(sort.label + if (sort == state.sort) " $arrow" else "") },
                    onClick = {
                        onSort(sort)
                        open = false
                    },
                )
            }
        }
    }
}

private val LibrarySort.label: String
    get() = when (this) {
        LibrarySort.NAME -> "Name"
        LibrarySort.MODIFIED -> "Modified"
        LibrarySort.CREATED -> "Created"
    }

@Composable
private fun SelectionBar(count: Int, actions: LibraryActions, onMove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("$count selected", style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = actions.onSelectAll) { Text("Select all") }
        TextButton(onClick = onMove) { Text("Move to…") }
        // "Trash", never "Delete" — ROADMAP M3's hard rule, in the wording as well as in
        // the behaviour. Nothing in this screen destroys a note.
        TextButton(onClick = actions.onTrashSelection) { Text("Trash") }
        TextButton(onClick = actions.onClearSelection) { Text("Cancel") }
    }
}

@Composable
private fun EmptyLibrary(searching: Boolean, query: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (searching) "Nothing matches “$query”" else "No notes here yet",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun LibraryGrid(state: LibraryUiState, actions: LibraryActions, dialogs: LibraryDialogState) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = GRID_CELL_MIN_WIDTH.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Keys are ids, never positions. Without them a note inserted at the top would
        // re-compose — and re-decode the thumbnail of — every cell below it.
        gridItems(state.folders, key = { "folder-${it.id.value}" }) { folder ->
            FolderCard(
                folder = folder,
                noteCount = state.allNotes.count { !it.isTrashed && it.folderId == folder.id },
                actions = folderActions(folder, actions, dialogs),
                cover = rememberFolderCover(folder, actions),
            )
        }
        gridItems(state.notes, key = { "note-${it.id.value}" }) { note ->
            NoteCard(
                note = note,
                selected = note.id in state.selection,
                selecting = state.selection.isNotEmpty(),
                actions = actions,
                onRename = { dialogs.renaming = RenameTarget(noteId = note.id, name = note.title) },
            )
        }
    }
}

@Composable
private fun LibraryList(state: LibraryUiState, actions: LibraryActions, dialogs: LibraryDialogState) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.folders, key = { "folder-${it.id.value}" }) { folder ->
            FolderRow(
                folder = folder,
                actions = folderActions(folder, actions, dialogs),
                cover = rememberFolderCover(folder, actions),
            )
        }
        items(state.notes, key = { "note-${it.id.value}" }) { note ->
            NoteRow(
                note = note,
                selected = note.id in state.selection,
                selecting = state.selection.isNotEmpty(),
                actions = actions,
                onRename = { dialogs.renaming = RenameTarget(noteId = note.id, name = note.title) },
            )
        }
    }
}

/** Which thing a rename dialog is about. Exactly one of the two ids is set. */
data class RenameTarget(val noteId: DocumentId? = null, val folderId: FolderId? = null, val name: String = "")

/**
 * Everything the library screen can do, as one bundle.
 *
 * A parameter object rather than fifteen lambdas: the same shape `InkToolbarActions` and
 * `PageBarActions` use, so a preview or a test can build one without a view model.
 */
data class LibraryActions(
    val onOpenNote: (DocumentId) -> Unit,
    val onOpenFolder: (FolderId?) -> Unit,
    val onGoUp: () -> Unit,
    val onNewNote: () -> Unit,
    /** ROADMAP M4 — the PDF the user picked. What it *is* is SPEC §5.6's decision, not this one's. */
    val onImportPdf: (Uri) -> Unit,
    val onNewFolder: (String) -> Unit,
    val onQueryChange: (String) -> Unit,
    val onSort: (LibrarySort) -> Unit,
    val onLayout: (LibraryLayout) -> Unit,
    val onFavouritesOnly: (Boolean) -> Unit,
    val onToggleSelect: (DocumentId) -> Unit,
    val onSelectAll: () -> Unit,
    val onClearSelection: () -> Unit,
    val onMoveSelection: (FolderId?) -> Unit,
    val onTrashSelection: () -> Unit,
    val onTrashNote: (DocumentId) -> Unit,
    val onTrashFolder: (FolderId) -> Unit,
    val onRenameNote: (DocumentId, String) -> Unit,
    val onRenameFolder: (FolderId, String) -> Unit,
    val onMoveFolder: (FolderId, FolderId?) -> Unit,
    val onStyleFolder: (
        FolderId,
        Int?,
        dev.inkstone.core.model.FolderIcon,
        dev.inkstone.core.model.FolderCover,
    ) -> Unit,
    val onFavourite: (DocumentId, Boolean) -> Unit,
    val onDuplicate: (DocumentId) -> Unit,
    val onOpenTrash: () -> Unit,
    /** SPEC §7.6 — *"Save to Nextcloud…"*. Opens the account/folder picker for this note. */
    val onSaveToNextcloud: (DocumentId) -> Unit,
    /** Stop syncing a bound note. Not a remote delete — the copy on the server stays. */
    val onStopSyncing: (DocumentId) -> Unit,
    val onOpenNextcloud: () -> Unit,
    /** ROADMAP M3's frame-rate gate needs 500 notes. See `LibraryFixture`. */
    val onFillForMeasurement: () -> Unit,
    val thumbnail: suspend (NoteSummary) -> androidx.compose.ui.graphics.ImageBitmap?,
    /** A folder's cover picture, or null when it shows its icon (SPEC §9). */
    val folderCover: suspend (Folder) -> androidx.compose.ui.graphics.ImageBitmap?,
    /** Open the editor on the note that *is* this folder's cover, creating it if needed. */
    val onDrawCover: (FolderId) -> Unit,
    /** Launch the system photo picker for this folder's cover. */
    val onPickCoverImage: (FolderId) -> Unit,
)

/** Wide enough for a legible page thumbnail on a tablet, narrow enough for three columns. */
private const val GRID_CELL_MIN_WIDTH = 180

/**
 * A folder's cover picture, loaded off the composition.
 *
 * Keyed on the cover *and* the folder's `modifiedAt`, so redrawing a cover or importing a new
 * image refreshes the tile without the grid having to be told. The underlying pipeline is the
 * note thumbnail cache, so a hit costs nothing (SPEC §5.7).
 */
@Composable
private fun rememberFolderCover(folder: Folder, actions: LibraryActions): ImageBitmap? {
    var image by remember(folder.id.value) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(folder.id.value, folder.cover, folder.modifiedEpochMillis) {
        image = actions.folderCover(folder)
    }
    return image
}

/**
 * The measurement tool, behind a menu so it is not mistaken for a feature.
 *
 * The library-scale twin of the ink toolbar's Measure panel, and there for the same reason:
 * ROADMAP M3's frame-rate criterion needs 500 notes across 50 nested folders, and generating
 * them is the only honest way to get there.
 *
 * There is deliberately no "remove the fixture" item. That would be a hard delete with a
 * wildcard's blast radius, which is the one thing this milestone rules out. The generated
 * notes are ordinary notes: select them and move them to the trash.
 */
@Composable
private fun MeasureMenu(actions: LibraryActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Measure") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Fill library (500 notes, 50 folders)") },
                onClick = {
                    actions.onFillForMeasurement()
                    open = false
                },
            )
        }
    }
}

/**
 * The only MIME type the picker offers.
 *
 * Narrow on purpose. SPEC §6.2 is about importing PDFs, and a picker that accepted
 * anything would let a user choose a file the import can only reject — which reads
 * as the app being broken rather than as the file being the wrong kind.
 */
private const val PDF_MIME = "application/pdf"
