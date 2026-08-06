package dev.inkstone.app.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.inkstone.core.model.Clock
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.Folder
import dev.inkstone.core.model.FolderId
import dev.inkstone.core.model.NoteSummary
import dev.inkstone.core.storage.NoteTrash

/**
 * ROADMAP M3 — the trash. **Restore only.**
 *
 * There is deliberately no "delete forever" button, no "empty trash", and no long-press that
 * reveals one. CLAUDE.md rule 1 is not "make deletion recoverable", it is *"no hard
 * deletes"*, and the way that survives contact with a UI is by there being no code path from
 * a tap to a destroyed note. Retention is a 30-day clock in
 * [dev.inkstone.core.storage.NoteTrash.purgeExpired], driven from
 * [LibraryRepository.startup].
 *
 * So the only thing this screen adds beyond a list is honesty about the clock: each entry
 * says how long it has left. A user who wants a note gone immediately is not served by this
 * screen — and that is the trade the rule makes deliberately.
 */
@Composable
fun TrashScreen(
    state: TrashUiState,
    nowEpochMillis: Long,
    onRestoreNote: (DocumentId) -> Unit,
    onRestoreFolder: (FolderId) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack) { Text("‹ Library") }
            Text("Trash", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Kept for 30 days, then removed automatically",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (state.isEmpty) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("The trash is empty", style = MaterialTheme.typography.bodyLarge)
            }
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.folders, key = { "folder-${it.id.value}" }) { folder ->
                TrashEntryCard(
                    title = "${folder.icon.name.lowercase()}  ${folder.name}",
                    subtitle = "Folder — restores with everything that went in with it",
                    deletedEpochMillis = folder.deletedEpochMillis,
                    nowEpochMillis = nowEpochMillis,
                    onRestore = { onRestoreFolder(folder.id) },
                )
            }
            items(state.notes, key = { "note-${it.id.value}" }) { note ->
                TrashEntryCard(
                    title = note.title,
                    subtitle = "${note.pageCount} ${if (note.pageCount == 1) "page" else "pages"}",
                    deletedEpochMillis = note.deletedEpochMillis,
                    nowEpochMillis = nowEpochMillis,
                    onRestore = { onRestoreNote(note.id) },
                )
            }
        }
    }
}

@Composable
private fun TrashEntryCard(
    title: String,
    subtitle: String,
    deletedEpochMillis: Long?,
    nowEpochMillis: Long,
    onRestore: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
                Text(
                    text = retentionText(deletedEpochMillis, nowEpochMillis),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onRestore) { Text("Restore") }
        }
    }
}

/**
 * How long is left, in whole days.
 *
 * Rounded **up**: "1 day left" on something that has 14 hours is a promise the app keeps,
 * and rounding down would show "0 days left" for most of the final day.
 */
private fun retentionText(deletedEpochMillis: Long?, nowEpochMillis: Long): String {
    if (deletedEpochMillis == null) return ""
    val remaining = NoteTrash.RETENTION_MILLIS - (nowEpochMillis - deletedEpochMillis)
    if (remaining <= 0) return "Removed on the next clean-up"
    val days = ((remaining + MILLIS_PER_DAY - 1) / MILLIS_PER_DAY).toInt()
    return if (days == 1) "1 day left" else "$days days left"
}

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
