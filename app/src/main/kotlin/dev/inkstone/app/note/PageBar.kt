package dev.inkstone.app.note

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.inkstone.core.model.PageTemplate

/**
 * ROADMAP M2 — page management: add, delete, reorder, duplicate, and the template picker.
 *
 * Same rule as M1's toolbar: **don't design the toolbar here** — M10 owns that. This is the
 * plainest thing that makes every M2 acceptance criterion reachable on the tablet without a
 * rebuild, and the `local_rev` readout is there because "did that save?" is the question this
 * milestone exists to answer.
 */
@Composable
fun PageBar(state: NoteEditorState, actions: PageBarActions, modifier: Modifier = Modifier) {
    var templateMenuOpen by remember { mutableStateOf(false) }

    Card(modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = actions.onPrevious, enabled = state.pageIndex > 0) { Text("‹") }
            // The page counter is the overview's handle. M3 gave that sheet thumbnails, and
            // "tap the page number to see the pages" needs no extra button to explain.
            TextButton(onClick = actions.onShowPages) {
                Text(
                    text = "${state.pageIndex + 1} / ${state.pageCount}",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            TextButton(
                onClick = actions.onNext,
                enabled = state.pageIndex < state.pageCount - 1,
            ) { Text("›") }

            TextButton(onClick = actions.onAddPage) { Text("Add") }
            TextButton(onClick = actions.onDuplicatePage) { Text("Duplicate") }
            // Deleting the last page is refused by the session rather than hidden here, so the
            // reason arrives as a message instead of a greyed-out button with no explanation.
            TextButton(onClick = actions.onDeletePage) { Text("Delete") }
            TextButton(
                onClick = actions.onMoveLeft,
                enabled = state.pageIndex > 0,
            ) { Text("Move ‹") }
            TextButton(
                onClick = actions.onMoveRight,
                enabled = state.pageIndex < state.pageCount - 1,
            ) { Text("Move ›") }

            AssistChip(
                onClick = { templateMenuOpen = true },
                label = { Text(state.template.label()) },
            )
            DropdownMenu(expanded = templateMenuOpen, onDismissRequest = { templateMenuOpen = false }) {
                for (template in PageTemplate.entries) {
                    DropdownMenuItem(
                        text = { Text(template.label()) },
                        onClick = {
                            templateMenuOpen = false
                            actions.onTemplate(template)
                        },
                    )
                }
            }

            // The autosave heartbeat. `local_rev` is bumped on every committed change
            // (SPEC §7.6), so a number that stops moving while you write is a bug you can see.
            Text(
                text = "rev ${state.localRev}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun PageTemplate.label(): String = when (this) {
    PageTemplate.BLANK -> "Blank"
    PageTemplate.RULED -> "Ruled"
    PageTemplate.GRID -> "Grid"
    PageTemplate.DOT_GRID -> "Dots"
    PageTemplate.CORNELL -> "Cornell"
}

/** Everything the page bar can ask the editor to do. */
data class PageBarActions(
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onAddPage: () -> Unit,
    val onDuplicatePage: () -> Unit,
    val onDeletePage: () -> Unit,
    val onMoveLeft: () -> Unit,
    val onMoveRight: () -> Unit,
    val onTemplate: (PageTemplate) -> Unit,
    /** ROADMAP M3 — the page overview, with a thumbnail per page. */
    val onShowPages: () -> Unit,
)
