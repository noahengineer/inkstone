package dev.inkstone.app.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.inkstone.app.ui.ChipDivider
import dev.inkstone.app.ui.ChipKind
import dev.inkstone.app.ui.GlassBar
import dev.inkstone.app.ui.GlassPanel
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.InkType
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.Readout
import dev.inkstone.app.ui.SegmentChip
import dev.inkstone.app.ui.Segmented
import dev.inkstone.app.ui.TextChip
import dev.inkstone.app.ui.rememberDotGridBrush
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.Folder
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.model.LibraryLayout
import dev.inkstone.core.model.LibrarySort
import dev.inkstone.core.model.NoteSummary
import dev.inkstone.core.search.SearchHit
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
 * ## ⭐ ROADMAP M10 — the polish M3 deferred
 *
 * M3 left this screen deliberately plain, on the note that *"M10 owns polish"*. This is M10
 * owning it: the chrome is the redesign's floating pills over the same near-black, dot-gridded
 * canvas the editor draws on, so opening a note is a change of content rather than a change of
 * app. Everything the frame-rate criterion rests on is untouched — the keys are still ids, the
 * thumbnail loads are still per cell and still cancelled by scrolling — because a restyle that
 * reached into those would be trading an acceptance criterion for a colour.
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

    Box(modifier = modifier.fillMaxSize().background(Ink.Canvas)) {
        // ⭐ M10 — the design's *"Deep Charcoal with a subtle dot-grid pattern for scale and
        // alignment"*, the same two lines `InkScreen` opens with. One tiled draw call; see
        // `rememberDotGridBrush` for why a loop of `drawCircle` is not an option.
        Box(modifier = Modifier.fillMaxSize().background(rememberDotGridBrush()))

        Column(modifier = Modifier.fillMaxSize()) {
            LibraryChrome(state = state, actions = actions, dialogs = dialogs)

            when {
                !state.loaded -> Unit

                // The first frame. An empty-state here would be a lie.
                state.isEmpty && state.handwritingHits.isEmpty() ->
                    EmptyLibrary(searching = state.searching, query = state.query)

                state.isEmpty -> Unit

                state.layout == LibraryLayout.GRID -> LibraryGrid(state, actions, dialogs)

                else -> LibraryList(state, actions, dialogs)
            }
        }

        CreateBar(
            onImport = { importPdf.launch(arrayOf(PDF_MIME)) },
            onImportFromNextcloud = actions.onImportFromNextcloud,
            onNewNote = actions.onNewNote,
            modifier = Modifier.align(Alignment.BottomEnd).padding(InkDims.EdgePadding),
        )
    }

    LibraryDialogHost(state = state, actions = actions, dialogs = dialogs)
}

/**
 * ⭐ M10 — the two ways a note starts, as one floating pill.
 *
 * The design has no FABs: *"everything is a floating pill of chips"*, and a bottom-end pill of two
 * words is the same target as two extended FABs without the second shape.
 */
@Composable
private fun CreateBar(
    onImport: () -> Unit,
    onImportFromNextcloud: () -> Unit,
    onNewNote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassBar(modifier = modifier, scrollable = false) {
        // ROADMAP M4 — PDF import, beside "New note" rather than buried in the
        // toolbar. Importing a lecture is half of what this app is for, and a note
        // started from a PDF is not a different kind of note.
        TextChip(label = "Import PDF", onClick = onImport)
        // ⭐ Where the PDFs actually are. A file picker reaches the tablet's own storage and the
        // Nextcloud app's provider if one is installed — but that copies the file, and a copy can
        // never write the user's ink back onto the document they keep on their server. This chip
        // is the one that can, so it sits beside the picker rather than behind a menu.
        // ⚠️ Three chips and no more: `docs/M10-PORTRAIT.md` — a bar that overflows at 800 dp hides
        // its own last control behind a drag nothing suggests.
        TextChip(label = "From Nextcloud", onClick = onImportFromNextcloud)
        ChipDivider()
        TextChip(label = "New note", onClick = onNewNote)
    }
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

/** The six things a folder's menu can start, wired to the dialog state and the actions. */
private fun folderActions(folder: Folder, actions: LibraryActions, dialogs: LibraryDialogState) = FolderActions(
    onOpen = { actions.onOpenFolder(folder.id) },
    onRename = { dialogs.renaming = RenameTarget(folderId = folder.id, name = folder.name) },
    onMove = { dialogs.movingFolder = folder },
    onStyle = { dialogs.stylingFolder = folder },
    onTrash = { actions.onTrashFolder(folder.id) },
    onSyncToFolder = { actions.onSyncFolderToNextcloud(folder.id) },
    // M12 §10. Read from the state the view model already loaded, so opening a menu costs no
    // query — the same reason a note tile reads `syncState` off its row rather than asking.
    autoRecord = actions.autoRecordFolders[folder.id.value],
    onSetAutoRecord = { value -> actions.onSetAutoRecord(folder.id, value) },
)

/**
 * ⭐ M10 — everything that floats above the tiles, as one zone.
 *
 * The design's chrome is *"non-intrusive floating zones"* rather than a docked app bar, so these
 * are five separate pills and panels in a padded column with nothing behind them. Progressive
 * disclosure is the `if`s: the selection bar, the handwriting hits and the model offer are absent
 * — not disabled, not empty — whenever they have nothing to act on.
 */
@Composable
private fun LibraryChrome(state: LibraryUiState, actions: LibraryActions, dialogs: LibraryDialogState) {
    Column(
        modifier = Modifier.padding(InkDims.EdgePadding),
        verticalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
    ) {
        LibraryToolbar(state = state, actions = actions, onNewFolder = { dialogs.creatingFolder = true })
        BreadcrumbBar(state = state, actions = actions)

        if (state.selection.isNotEmpty()) {
            SelectionBar(
                count = state.selection.size,
                actions = actions,
                onMove = { dialogs.movingNotes = true },
            )
        }

        // SPEC §8.4 — what the user wrote, above what the notes are called. The title
        // match is the one they could have found by browsing; this is the one they could
        // not, and burying it under the tiles would make the feature look absent.
        if (state.handwritingHits.isNotEmpty()) {
            HandwritingHits(hits = state.handwritingHits, onOpen = actions.onOpenHit)
        }

        // ROADMAP M7: *"search degrades to titles, message is clear"*. Shown only while
        // searching, so it is an answer to a question the user just asked rather than a
        // banner nagging about a 20 MB download on a screen they opened to find a note.
        if (state.searching && !state.model.downloaded) {
            RecognitionModelOffer(state.model, actions.onDownloadModel)
        }
    }
}

@Composable
private fun LibraryToolbar(state: LibraryUiState, actions: LibraryActions, onNewFolder: () -> Unit) {
    GlassBar {
        SearchField(query = state.query, onQueryChange = actions.onQueryChange)

        ChipDivider()
        // A `Segmented` and not one chip that names the *other* layout: the choice is between two
        // things, and a lone button reading "Grid" is ambiguous about whether that is the state or
        // the consequence. `Segmented` exists for exactly this (see `Glass.kt`).
        Segmented {
            SegmentChip(
                label = "List",
                selected = state.layout == LibraryLayout.LIST,
                onClick = { actions.onLayout(LibraryLayout.LIST) },
            )
            SegmentChip(
                label = "Grid",
                selected = state.layout == LibraryLayout.GRID,
                onClick = { actions.onLayout(LibraryLayout.GRID) },
            )
        }
        SortMenu(state = state, onSort = actions.onSort)

        ChipDivider()
        TextChip(label = "New folder", onClick = onNewFolder)
        TextChip(label = "Trash", onClick = actions.onOpenTrash)
        TextChip(label = "Nextcloud", onClick = actions.onOpenNextcloud)
        MeasureMenu(actions)

        // ⭐ SPEC §7.7's list, and it appears only when there is something in it (M6). A
        // permanently visible "Needs attention" is a permanent suggestion that something is
        // wrong, in an app whose whole conflict story is that nothing is.
        if (state.attentionCount > 0) {
            ChipDivider()
            TextChip(label = "⚑ ${state.attentionCount}", kind = ChipKind.VALUE, onClick = actions.onOpenAttention)
        }
    }
}

/**
 * The one text input on this screen, and the reason the toolbar pill holds an `OutlinedTextField`
 * rather than a chip: a query is *typed*, and the design system has nothing that types.
 *
 * ⚠️ Fixed width, for the same reason `DockRows`' angle field is. A `weight(1f)` cannot live in a
 * horizontally scrolling [GlassBar] — the row is measured with an unbounded width — and the bar
 * scrolls so that the last two chips are not simply gone when the tablet is held in portrait.
 *
 * ⚠️ The hint is a **placeholder and not a label**. A Material label rides up over the border and
 * shrinks as it goes, so at a fixed width the sentence would be clipped in one of its two sizes —
 * and the half that would go is *"and handwriting"*, which is the half that says the ink is searched
 * at all (SPEC §8.4). A placeholder is one size and is on screen exactly when the field is empty,
 * which is when a hint is worth anything.
 *
 * ⚠️ **It is a plain `Text` and not a [MicroLabel], and it ellipsises.** The field's width is in
 * `dp` and the hint's size is in `sp`, so the two diverge the moment the user raises their font
 * scale — and clipping the tail is exactly the failure the paragraph above claims to have designed
 * out. The words are also shorter than they were: *"and handwriting"* now has room to survive a
 * larger font rather than being the first thing to go.
 */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = {
            Text(
                text = "Search notes and handwriting",
                color = Ink.TextMuted,
                fontSize = InkType.MicroSize,
                letterSpacing = InkType.MicroTracking,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = Modifier.width(SearchFieldWidth),
    )
}

/**
 * The path, as chips separated by a muted `/`.
 *
 * ⚠️ The folder chips are [ChipKind.VALUE], so a folder called "pH notes" is not shown as "PH NOTES". A
 * chip is uppercased because its word is a *control*; a breadcrumb segment is the user's own text,
 * and [TextChip]'s KDoc makes the same argument about a measurement. It also puts the path in the
 * readout face, which is what a path is.
 */
@Composable
private fun BreadcrumbBar(state: LibraryUiState, actions: LibraryActions) {
    GlassBar {
        TextChip(
            label = "Library",
            selected = state.breadcrumb.isEmpty(),
            onClick = { actions.onOpenFolder(null) },
        )
        for (folder in state.breadcrumb) {
            MicroLabel("/")
            TextChip(
                label = folder.name,
                kind = ChipKind.VALUE,
                selected = folder.id == state.folderId,
                onClick = { actions.onOpenFolder(folder.id) },
            )
        }

        ChipDivider()
        TextChip(
            label = "★ Favourites",
            selected = state.favouritesOnly,
            onClick = { actions.onFavouritesOnly(!state.favouritesOnly) },
        )
    }
}

@Composable
private fun SortMenu(state: LibraryUiState, onSort: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val arrow = if (state.descending) "↓" else "↑"
    Box {
        TextChip(label = "${state.sort.label} $arrow", selected = open, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (sort in LibrarySort.entries) {
                DropdownMenuItem(
                    text = { MicroLabel(sort.label + if (sort == state.sort) " $arrow" else "") },
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
    GlassBar {
        // Sky and monospaced: how many are selected is a live number, and the one thing in this
        // pill that changes while the user taps.
        Readout(count.toString(), color = Ink.Focus)
        MicroLabel("selected")

        ChipDivider()
        TextChip(label = "Select all", onClick = actions.onSelectAll)
        TextChip(label = "Move to…", onClick = onMove)
        // "Trash", never "Delete" — ROADMAP M3's hard rule, in the wording as well as in
        // the behaviour. Nothing in this screen destroys a note.
        TextChip(label = "Trash", danger = true, onClick = actions.onTrashSelection)
        TextChip(label = "Cancel", onClick = actions.onClearSelection)
    }
}

/**
 * SPEC §8.4's handwriting results: note, page, and the word that matched.
 *
 * Each row carries the page-space box, so tapping it opens the note *at the word* rather than
 * at the note — which is the whole difference between search and a filtered list.
 */
@Composable
private fun HandwritingHits(hits: List<SearchHit>, onOpen: (SearchHit) -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        MicroLabel("In your handwriting")
        for (hit in hits.take(MAX_HANDWRITING_HITS)) {
            HandwritingHitRow(hit = hit, onOpen = { onOpen(hit) })
        }
    }
}

/**
 * One hit, shaped like a chip and laid out like a row.
 *
 * ⚠️ Deliberately not a [TextChip]. That centres its label, keeps it to one line and uppercases it,
 * and a hit is two pieces of the *user's own* text — the word, then the note it is in — which have
 * to stay left-aligned and in the capitalisation they were written in.
 */
@Composable
private fun HandwritingHitRow(hit: SearchHit, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(InkDims.ChipCorner)
            .clickable(onClick = onOpen)
            .padding(horizontal = InkDims.ChipPaddingH, vertical = InkDims.ChipPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
    ) {
        // Sky for the word, because sky is the design's *"this is live"* colour and the word is
        // the thing this row will scroll to.
        Readout("“${hit.word}”", color = Ink.Focus)
        Readout(hit.noteTitle, modifier = Modifier.weight(1f), color = Ink.TextMuted)
    }
}

/** Enough to be useful, few enough that the notes below stay on screen. */
private const val MAX_HANDWRITING_HITS = 8

/**
 * SPEC §8.1 — the language model, offered at the moment it would have helped.
 *
 * ⚠️ **The size and the metrics disclosure are in front of the user, not behind the button.** SPEC
 * §8.1 requires disclosing that ML Kit reports anonymous performance metrics to Google, and the
 * only useful place for that is before the download rather than after.
 *
 * ⭐ ROADMAP M10 — that sentence used to be the button's *label*, which was the same argument by a
 * different route and is no longer available: a [TextChip] is one clipped line, so a 74-character
 * label would have silently truncated exactly the disclosure SPEC §8.1 requires, and the failure
 * mode is that it still looks fine. Nothing is dropped — the size and the metrics sentence sit on
 * their own line *above* the chip, which is earlier in the reading order than a button label, and
 * the chip keeps only the verb.
 */
@Composable
private fun RecognitionModelOffer(model: RecognitionModelState, onDownload: () -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        MicroLabel("Handwriting search")
        Text(
            text = "Searching titles and folders only — handwriting recognition needs a language model.",
            style = MaterialTheme.typography.bodyMedium,
            color = Ink.TextNormal,
        )
        Text(
            text = "English (~20 MB) · ML Kit sends anonymous usage metrics to Google",
            style = MaterialTheme.typography.bodySmall,
            color = Ink.TextMuted,
        )
        model.error?.let {
            Text(
                text = "Download failed: $it",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.Danger,
            )
        }
        TextChip(
            label = if (model.downloading) "Downloading…" else "Download English",
            enabled = !model.downloading,
            onClick = onDownload,
        )
    }
}

@Composable
private fun EmptyLibrary(searching: Boolean, query: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(InkDims.ChipGap),
        ) {
            MicroLabel(if (searching) "Nothing matches" else "Empty")
            // The query is the user's text and the sentence is ours, but both are the *answer*
            // rather than a control, so both are readouts rather than chips.
            Readout(if (searching) "“$query”" else "No notes here yet", color = Ink.TextMuted)
        }
    }
}

@Composable
private fun LibraryGrid(state: LibraryUiState, actions: LibraryActions, dialogs: LibraryDialogState) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = GRID_CELL_MIN_WIDTH.dp),
        contentPadding = ContentInset,
        horizontalArrangement = Arrangement.spacedBy(TileGap),
        verticalArrangement = Arrangement.spacedBy(TileGap),
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
                synced = folder.id in state.syncedFolders,
            )
        }
        gridItems(state.notes, key = { "note-${it.id.value}" }) { note ->
            NoteCard(
                note = note,
                selected = note.id in state.selection,
                selecting = state.selection.isNotEmpty(),
                actions = actions,
                onRename = { dialogs.renaming = RenameTarget(noteId = note.id, name = note.title) },
                needsAttention = note.id in state.notesNeedingAttention,
            )
        }
    }
}

@Composable
private fun LibraryList(state: LibraryUiState, actions: LibraryActions, dialogs: LibraryDialogState) {
    LazyColumn(
        contentPadding = ContentInset,
        verticalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.folders, key = { "folder-${it.id.value}" }) { folder ->
            FolderRow(
                folder = folder,
                actions = folderActions(folder, actions, dialogs),
                cover = rememberFolderCover(folder, actions),
                synced = folder.id in state.syncedFolders,
            )
        }
        items(state.notes, key = { "note-${it.id.value}" }) { note ->
            NoteRow(
                note = note,
                selected = note.id in state.selection,
                selecting = state.selection.isNotEmpty(),
                actions = actions,
                onRename = { dialogs.renaming = RenameTarget(noteId = note.id, name = note.title) },
                needsAttention = note.id in state.notesNeedingAttention,
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
    /** SPEC §8.4 — open the note at the page the word is on and highlight it. */
    val onOpenHit: (SearchHit) -> Unit,
    /** SPEC §8.1 — fetch the recognition model. Only ever from a press (see the offer). */
    val onDownloadModel: () -> Unit,
    val onOpenFolder: (FolderId?) -> Unit,
    val onGoUp: () -> Unit,
    val onNewNote: () -> Unit,
    /** ROADMAP M4 — the PDF the user picked. What it *is* is SPEC §5.6's decision, not this one's. */
    val onImportPdf: (Uri) -> Unit,
    /**
     * Open the server's own file browser to pick a PDF to write on, in place.
     *
     * A different thing from [onImportPdf] rather than a source for it: that one ends in a note
     * this device owns, and this one ends in a note **bound to the file it came from**, so the ink
     * goes back to where the user keeps the document.
     */
    val onImportFromNextcloud: () -> Unit,
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
    /** Relocate a synced note's remote copy — one MOVE, no duplicate left behind. */
    val onChangeSyncFolder: (DocumentId) -> Unit,
    /** M6 — pin a folder to a remote path, so everything under it syncs there (SPEC §9). */
    val onSyncFolderToNextcloud: (FolderId) -> Unit,
    /** M12 §10 — the explicit per-folder setting, by folder id. Absent means *inherit*. */
    val autoRecordFolders: Map<String, Boolean>,
    val onSetAutoRecord: (FolderId, Boolean?) -> Unit,
    /** Stop syncing a bound note. Not a remote delete — the copy on the server stays. */
    val onStopSyncing: (DocumentId) -> Unit,
    val onOpenNextcloud: () -> Unit,
    /**
     * ⭐ SPEC §7.7 — open the "Needs attention" list (M6).
     *
     * A destination reached by tapping a count, never a dialog that arrives on its own (CLAUDE.md
     * rule 6). Everything in that list has already been resolved without losing anything.
     */
    val onOpenAttention: () -> Unit,
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
        TextChip(label = "Measure", selected = open, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { MicroLabel("Fill library (500 notes, 50 folders)") },
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

/**
 * Wide enough for the whole placeholder sentence at the micro size, and for a real query.
 *
 * A number rather than a `weight`: see [SearchField] for why a weighted child cannot live in a
 * horizontally scrolling bar.
 */
private val SearchFieldWidth = 340.dp

/** Between two tiles. The chrome's own gaps come from [InkDims]. */
private val TileGap = 12.dp

/**
 * The margin round the tiles.
 *
 * ⚠️ The bottom is deep on purpose: the "Import PDF / New note" pill floats over this list, and
 * without the inset the last row of a full library would sit permanently underneath it.
 */
private val ContentInset = PaddingValues(
    start = TileGap,
    top = TileGap,
    end = TileGap,
    bottom = 88.dp,
)
