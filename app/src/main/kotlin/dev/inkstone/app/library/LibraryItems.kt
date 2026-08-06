package dev.inkstone.app.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.inkstone.core.model.Folder
import dev.inkstone.core.model.NoteSummary

/**
 * One note in the grid — its thumbnail, title, and the actions on it.
 *
 * ## The thumbnail load
 *
 * A `LaunchedEffect` keyed on `(id, localRev)`. Both halves matter:
 *
 * - **The id** means a cell recycled onto a different note reloads.
 * - **The revision** means a cell showing a note that has just been edited reloads, and
 *   one showing a note that has not does *not* — which is what keeps a scroll from
 *   re-decoding images it already has.
 *
 * A cell scrolled off screen has its effect cancelled, so a fast fling abandons the loads
 * it started rather than queueing them all (SPEC §5.7, and ROADMAP M3's "no jank while
 * scrolling").
 */
@Composable
fun NoteCard(
    note: NoteSummary,
    selected: Boolean,
    selecting: Boolean,
    actions: LibraryActions,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val thumbnail = rememberThumbnail(note, actions)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PAGE_ASPECT)
                .background(Color.White)
                .combinedClickable(
                    // A tap while selecting extends the selection instead of opening — the
                    // usual rule, and the one that keeps multi-select from being a trap.
                    onClick = { if (selecting) actions.onToggleSelect(note.id) else actions.onOpenNote(note.id) },
                    onLongClick = { actions.onToggleSelect(note.id) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // The placeholder SPEC §5.7 asks for. Deliberately not a spinner: most of
                // these resolve within a frame or two from disk, and a spinner per cell in a
                // 500-note grid is visual noise that reads as "everything is loading".
                Text("…", style = MaterialTheme.typography.headlineSmall)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = note.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            NoteRowControls(note, actions, menuOpen, { menuOpen = it }, onRename)
        }
    }
}

/** The same note as a row, for the list layout. */
@Composable
fun NoteRow(
    note: NoteSummary,
    selected: Boolean,
    selecting: Boolean,
    actions: LibraryActions,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (selecting) actions.onToggleSelect(note.id) else actions.onOpenNote(note.id) },
                    onLongClick = { actions.onToggleSelect(note.id) },
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(note.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    "${note.pageCount} ${if (note.pageCount == 1) "page" else "pages"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            NoteRowControls(note, actions, menuOpen, { menuOpen = it }, onRename)
        }
    }
}

/**
 * The star and the overflow, as one group hard against the right edge.
 *
 * They were two `TextButton`s, and a `TextButton` carries a wide minimum width and the
 * generous horizontal padding meant for a *labelled* button. Two side by side left a visible
 * gap between the star and the dots and floated the pair well short of the edge — reported
 * from the tablet as wanting them closer together and further along.
 *
 * So both are bare square tap targets with [CONTROL_GAP] between them and no internal
 * padding to spread them apart. [CONTROL_SIZE] keeps each one a comfortable target.
 */
@Composable
private fun NoteRowControls(
    note: NoteSummary,
    actions: LibraryActions,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onRename: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CONTROL_GAP),
    ) {
        Box(
            modifier = Modifier
                .size(CONTROL_SIZE)
                .clickable { actions.onFavourite(note.id, !note.favourite) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (note.favourite) "★" else "☆",
                style = MaterialTheme.typography.titleMedium,
                color = if (note.favourite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Box(
            modifier = Modifier.size(CONTROL_SIZE).clickable { onMenuOpenChange(true) },
            contentAlignment = Alignment.Center,
        ) {
            Text("⋯", style = MaterialTheme.typography.titleMedium)
            NoteMenu(
                note = note,
                expanded = menuOpen,
                onDismiss = { onMenuOpenChange(false) },
                actions = actions,
                onRename = onRename,
            )
        }
    }
}

@Composable
private fun NoteMenu(
    note: NoteSummary,
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: LibraryActions,
    onRename: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Rename") },
            onClick = {
                onRename()
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Duplicate") },
            onClick = {
                actions.onDuplicate(note.id)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Select") },
            onClick = {
                actions.onToggleSelect(note.id)
                onDismiss()
            },
        )
        // "Move to trash", not "Delete". There is no delete (ROADMAP M3).
        DropdownMenuItem(
            text = { Text("Move to trash") },
            onClick = {
                actions.onTrashNote(note.id)
                onDismiss()
            },
        )
    }
}

/**
 * What can be done to a folder from the library.
 *
 * A parameter object for the same reason [LibraryActions] is one: the card and the row take
 * the identical set, and five positional lambdas of the same type is a bug waiting for a
 * refactor to reorder them.
 */
data class FolderActions(
    val onOpen: () -> Unit,
    val onRename: () -> Unit,
    val onMove: () -> Unit,
    val onStyle: () -> Unit,
    val onTrash: () -> Unit,
)

@Composable
fun FolderCard(
    folder: Folder,
    noteCount: Int,
    actions: FolderActions,
    modifier: Modifier = Modifier,
    cover: ImageBitmap? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }

    // The card stays on the theme's surface and only the *tile* is tinted. Tinting the whole
    // card painted the folder's name onto its own background — see `FolderTile`.
    Card(modifier = modifier.fillMaxWidth()) {
        FolderTile(
            folder = folder,
            cover = cover,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PAGE_ASPECT)
                .combinedClickable(onClick = actions.onOpen),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$noteCount ${if (noteCount == 1) "note" else "notes"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Box {
                TextButton(onClick = { menuOpen = true }) { Text("⋯") }
                FolderMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, actions = actions)
            }
        }
    }
}

@Composable
fun FolderRow(folder: Folder, actions: FolderActions, modifier: Modifier = Modifier, cover: ImageBitmap? = null) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = actions.onOpen).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FolderTile(
                folder = folder,
                cover = cover,
                modifier = Modifier.size(ROW_TILE_SIZE).clip(MaterialTheme.shapes.small),
            )
            Text(folder.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { menuOpen = true }) { Text("⋯") }
                FolderMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, actions = actions)
            }
        }
    }
}

@Composable
private fun FolderMenu(expanded: Boolean, onDismiss: () -> Unit, actions: FolderActions) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Rename") },
            onClick = {
                actions.onRename()
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Move to…") },
            onClick = {
                actions.onMove()
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Colour and icon") },
            onClick = {
                actions.onStyle()
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Move to trash") },
            onClick = {
                actions.onTrash()
                onDismiss()
            },
        )
    }
}

/**
 * Loads a note's thumbnail, keyed so it reloads exactly when it should.
 *
 * See [NoteCard]'s KDoc for why the key is `(id, localRev)` and not the note itself — a
 * `NoteSummary` changes when a favourite is toggled, and re-rendering a page because
 * someone tapped a star is the kind of waste that shows up as a dropped frame.
 */
@Composable
private fun rememberThumbnail(note: NoteSummary, actions: LibraryActions): ImageBitmap? {
    var image by remember(note.id.value) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(note.id.value, note.localRev) {
        image = actions.thumbnail(note)
    }
    return image
}

/** Letter proportions. A cell shaped like the page it shows, so nothing is letterboxed. */
private const val PAGE_ASPECT = 612f / 792f

/**
 * The star and the overflow: square tap targets with nothing between them but [CONTROL_GAP].
 */
private val CONTROL_SIZE = 36.dp
private val CONTROL_GAP = 2.dp

/** The folder's picture in the list layout. */
private val ROW_TILE_SIZE = 44.dp
