package dev.inkstone.app.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import dev.inkstone.core.model.Folder
import dev.inkstone.core.model.FolderCover
import dev.inkstone.core.model.FolderIcon
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.model.FolderTree
import dev.inkstone.core.model.RemoteName

/**
 * Rename, and new-folder. One dialog because they are the same dialog.
 *
 * The name is run through [RemoteName.sanitise] **as it is typed**, and the result is shown
 * when it differs. SPEC §7.8 says a name has to be legal on a WebDAV server and M5 turns
 * folder names into remote paths — so a folder called `Physics / Term 2` is going to become
 * `Physics _ Term 2` whatever happens. Showing that now is the difference between a rule the
 * user understands and a rename they discover two milestones later.
 */
@Composable
fun TextPromptDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val sanitised = RemoteName.sanitise(text)
    val changed = text.isNotBlank() && sanitised != text.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
                if (changed) {
                    Text(
                        text = "Will be saved as “$sanitised” so it syncs cleanly",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Pick a destination folder — for moving notes, and for moving a folder.
 *
 * The whole tree, flattened and indented, rather than a folder browser you navigate. Moving
 * something is a two-second decision and a browser turns it into navigation; with 50 folders
 * the flat list fits on a tablet screen and shows the shape at a glance.
 */
@Composable
fun FolderPickerDialog(title: String, folders: List<Folder>, onDismiss: () -> Unit, onPick: (FolderId?) -> Unit) {
    val byId = folders.associateBy { it.id }
    val flattened = folders
        .map { it to FolderTree.pathOf(it.id, byId) }
        .sortedBy { (_, path) -> path.joinToString("/") { it.name.lowercase() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = PICKER_MAX_HEIGHT.dp)) {
                item {
                    TextButton(onClick = { onPick(null) }) { Text("Library (top level)") }
                }
                items(flattened, key = { (folder, _) -> folder.id.value }) { (folder, path) ->
                    TextButton(onClick = { onPick(folder.id) }) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            // The depth is the indent. A folder's *path* is what makes two
                            // folders called "Notes" tellable apart.
                            modifier = Modifier.padding(start = (INDENT_PER_LEVEL * (path.size - 1)).dp),
                        ) {
                            FolderGlyph(
                                icon = folder.icon,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(PICKER_GLYPH_SIZE.dp),
                            )
                            Text(folder.name)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * SPEC §9 — a folder's colour, icon and cover, **with a preview**.
 *
 * The preview is the point. Picking a colour and an icon from two rows of abstract chips and
 * only seeing the result after saving is guesswork, and it is what made the contrast problem
 * easy to ship in the first place: nothing in the dialog showed that the label was about to
 * become unreadable. The preview is the real [FolderTile] and the real label strip — the same
 * composables the grid draws — so what it shows is what you get, contrast rule included.
 */
@Composable
fun FolderAppearanceDialog(
    folder: Folder,
    cover: ImageBitmap?,
    onDismiss: () -> Unit,
    onConfirm: (Int?, FolderIcon, FolderCover) -> Unit,
    onDrawCover: () -> Unit,
    onPickImage: () -> Unit,
) {
    var color by remember { mutableStateOf(folder.colorArgb) }
    var icon by remember { mutableStateOf(folder.icon) }
    var coverChoice by remember { mutableStateOf(folder.cover) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(folder.name) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                FolderPreview(folder.copy(colorArgb = color, icon = icon, cover = coverChoice), cover)

                Text("Colour", style = MaterialTheme.typography.labelLarge)
                ColourRow(selected = color, onSelect = { color = it })

                Text("Icon", style = MaterialTheme.typography.labelLarge)
                IconGrid(
                    selected = icon.takeIf { coverChoice == FolderCover.Icon },
                    onSelect = {
                        icon = it
                        // Choosing an icon means you want to see the icon.
                        coverChoice = FolderCover.Icon
                    },
                )

                Text("Cover", style = MaterialTheme.typography.labelLarge)
                CoverChoices(
                    folder = folder,
                    selected = coverChoice,
                    onSelect = { coverChoice = it },
                    // These two leave the dialog — one for the editor, one for the system
                    // photo picker — so they save on the way out. Losing the colour you just
                    // picked because you then chose to draw a cover would be its own small
                    // betrayal.
                    onDrawCover = {
                        onConfirm(color, icon, coverChoice)
                        onDrawCover()
                    },
                    onPickImage = {
                        onConfirm(color, icon, coverChoice)
                        onPickImage()
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(color, icon, coverChoice) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Folder colours.
 *
 * Muted on purpose: a folder tint sits behind a page thumbnail, and a saturated one makes
 * the ink in it hard to read — which is the whole reason the thumbnail is there.
 */
private val FOLDER_COLORS = listOf(
    0xFFE8DEF8.toInt(),
    0xFFD7E8DE.toInt(),
    0xFFF8E8D6.toInt(),
    0xFFF8D8D8.toInt(),
    0xFFD6E4F8.toInt(),
    0xFFEDEDED.toInt(),
)

private const val SWATCH_SIZE = 32
private const val ICONS_PER_ROW = 4
private const val PICKER_MAX_HEIGHT = 360
private const val PICKER_GLYPH_SIZE = 20
private const val INDENT_PER_LEVEL = 16

/** The tile and its label, exactly as the grid draws them. */
@Composable
private fun FolderPreview(folder: Folder, cover: ImageBitmap?) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Card {
            Column(modifier = Modifier.width(PREVIEW_WIDTH.dp)) {
                FolderTile(
                    folder = folder,
                    cover = cover,
                    modifier = Modifier.fillMaxWidth().height(PREVIEW_TILE_HEIGHT.dp),
                )
                // The label strip is in the preview on purpose: it is the part that broke,
                // and seeing it here is what shows that it no longer does.
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Text("Preview", style = MaterialTheme.typography.bodySmall)
    }
}

/** An icon choice that shows the icon, rather than its name in lower case. */
@Composable
private fun IconChoice(icon: FolderIcon, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(ICON_CHOICE_SIZE.dp)
            .border(
                width = if (selected) SELECTED_BORDER.dp else UNSELECTED_BORDER.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.small,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        FolderGlyph(
            icon = icon,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ICON_CHOICE_GLYPH.dp),
        )
    }
}

private const val PREVIEW_WIDTH = 120
private const val PREVIEW_TILE_HEIGHT = 150
private const val ICON_CHOICE_SIZE = 52
private const val ICON_CHOICE_GLYPH = 30
private const val SELECTED_BORDER = 2
private const val UNSELECTED_BORDER = 1

/** The palette, plus "None" — which is not the same as picking grey. */
@Composable
private fun ColourRow(selected: Int?, onSelect: (Int?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // A folder the user has left plain follows the theme; one they coloured does not.
        TextButton(onClick = { onSelect(null) }) { Text(if (selected == null) "None ✓" else "None") }
        for (swatch in FOLDER_COLORS) {
            Box(
                modifier = Modifier
                    .size(SWATCH_SIZE.dp)
                    .background(Color(swatch), CircleShape)
                    .border(
                        width = if (selected == swatch) SELECTED_BORDER.dp else UNSELECTED_BORDER.dp,
                        color = if (selected == swatch) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    )
                    .clickable { onSelect(swatch) },
            )
        }
    }
}

/** @param selected null when the folder is showing a cover rather than its icon. */
@Composable
private fun IconGrid(selected: FolderIcon?, onSelect: (FolderIcon) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in FolderIcon.entries.chunked(ICONS_PER_ROW)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (candidate in row) {
                    IconChoice(
                        icon = candidate,
                        selected = candidate == selected,
                        onClick = { onSelect(candidate) },
                    )
                }
            }
        }
    }
}

/** SPEC §9 — what fills the tile. See `FolderCover` for why each option costs almost nothing. */
@Composable
private fun CoverChoices(
    folder: Folder,
    selected: FolderCover,
    onSelect: (FolderCover) -> Unit,
    onDrawCover: () -> Unit,
    onPickImage: () -> Unit,
) {
    FilterChip(
        selected = selected == FolderCover.Icon,
        onClick = { onSelect(FolderCover.Icon) },
        label = { Text("The icon") },
    )
    FilterChip(
        selected = selected == FolderCover.LatestNote,
        onClick = { onSelect(FolderCover.LatestNote) },
        label = { Text("First page of the most recent note") },
    )
    TextButton(onClick = onDrawCover) {
        Text(if (folder.cover is FolderCover.Drawn) "Edit the drawn cover…" else "Draw a cover…")
    }
    TextButton(onClick = onPickImage) { Text("Choose an image…") }
}
