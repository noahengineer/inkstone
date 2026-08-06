package dev.inkstone.app.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.inkstone.core.model.Folder
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
                        // The depth is the indent. A folder's *path* is what makes two
                        // folders called "Notes" tellable apart.
                        Text("${"    ".repeat(path.size - 1)}${folder.icon.name.lowercase()}  ${folder.name}")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** SPEC §9 — folders have a colour and an icon. */
@Composable
fun FolderAppearanceDialog(folder: Folder, onDismiss: () -> Unit, onConfirm: (Int?, FolderIcon) -> Unit) {
    var color by remember { mutableStateOf(folder.colorArgb) }
    var icon by remember { mutableStateOf(folder.icon) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(folder.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Colour", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // "None" is a real choice and not the same as picking grey: a folder the
                    // user has left plain follows the theme, and one they coloured does not.
                    TextButton(onClick = { color = null }) { Text(if (color == null) "None ✓" else "None") }
                    for (swatch in FOLDER_COLORS) {
                        Box(
                            modifier = Modifier
                                .size(SWATCH_SIZE.dp)
                                .background(Color(swatch), CircleShape)
                                .clickable { color = swatch },
                        )
                    }
                }

                Text("Icon", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in FolderIcon.entries.chunked(ICONS_PER_ROW)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (candidate in row) {
                                FilterChip(
                                    selected = candidate == icon,
                                    onClick = { icon = candidate },
                                    label = { Text(candidate.name.lowercase()) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(color, icon) }) { Text("Save") } },
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
