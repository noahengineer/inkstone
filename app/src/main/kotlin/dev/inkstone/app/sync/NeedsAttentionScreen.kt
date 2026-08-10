package dev.inkstone.app.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.inkstone.core.database.sync.AttentionItem
import dev.inkstone.core.sync.AttentionKind

/**
 * ⭐ **SPEC §7.7's "Needs attention" list. The only conflict UI in the app, and it is a list.**
 *
 * ## Why it is not a dialog, stated where somebody might add one
 *
 * CLAUDE.md rule 6: *"Conflicts resolve automatically and non-destructively, then appear in a
 * 'Needs attention' list. Never a modal. Never blocking sync."* Nextcloud's own Android client uses
 * the modal-from-notification pattern and carries roughly nine open bugs from it — a dialog
 * interrupts whoever happens to be holding the tablet, demands a decision they may not have the
 * information to make, and holds sync up until they make it. Every conflict that reaches this screen
 * has **already** been resolved without losing anything; the list exists so the user can find out
 * about it afterwards.
 *
 * There is deliberately no "resolve", "keep mine" or "keep theirs" here. Layer 3 kept both versions
 * before this screen existed, so the useful verbs are *open the note*, *open the copy* and *I have
 * seen this*.
 *
 * ## Acknowledging is a tombstone
 *
 * "Done" writes a timestamp; it does not delete the row (CLAUDE.md rule 1). Two reasons: the history
 * of what sync had to do to somebody's notes is worth keeping, and a deleted row is one the next
 * reconcile pass would cheerfully raise again as though it were new.
 */
@Composable
fun NeedsAttentionScreen(state: AttentionUiState, actions: AttentionActions, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = actions.onBack) { Text("← Back") }
            Text("Needs attention", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        HorizontalDivider()

        when {
            // Not shown before the first emission: telling somebody with a conflict that nothing
            // needs attention, for one frame, is worse than showing nothing.
            !state.loaded -> Unit

            state.isEmpty -> Text(
                "Nothing needs your attention. Conflicts are resolved automatically and both " +
                    "versions are always kept, so anything that happens will show up here.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.items, key = { it.entry.id }) { item -> AttentionCard(item, actions) }
            }
        }
    }
}

@Composable
private fun AttentionCard(item: AttentionItem, actions: AttentionActions) {
    val entry = item.entry
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(headlineFor(item), style = MaterialTheme.typography.titleSmall)
            Text(explain(item), style = MaterialTheme.typography.bodyMedium)
            entry.remotePath?.let { path ->
                Text(path, style = MaterialTheme.typography.bodySmall)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ⚠️ Not offered for a note in the trash, which is the *normal* state for a
                // REMOTE_DELETED entry. `NoteEditor.openOrCreate` falls back to `store.create` when
                // it cannot open the note it was asked for, and a trashed note's directory has moved
                // under `trash/` — so this button would silently mint a new empty note under a new
                // id and leave the user looking at a blank page where their notebook was.
                entry.noteId?.takeIf { !item.noteTrashed }?.let { id ->
                    TextButton(onClick = { actions.onOpenNote(id) }) { Text("Open note") }
                }
                entry.conflictedCopyNoteId?.let { id ->
                    TextButton(onClick = { actions.onOpenNote(id) }) { Text("Open the other copy") }
                }
                TextButton(onClick = { actions.onAcknowledge(entry.id) }) { Text("Done") }
            }
        }
    }
}

/** The note this is about, by name where there is one. An account-level entry says so. */
private fun headlineFor(item: AttentionItem): String = item.noteTitle ?: "This Nextcloud account"

/**
 * What happened, in a sentence, per [AttentionKind].
 *
 * Written as reassurance first and instruction second, because every one of these describes
 * something that has *already* been handled safely — and an app that reports a resolved conflict in
 * the register of an error teaches the user to distrust it. The two that genuinely need an action
 * say what the action is.
 *
 * ⚠️ An **unknown** kind falls through to a generic line rather than being hidden. A row written by
 * a newer build is still a row the user needs to see; dropping it would hide a conflict, which is the
 * one thing this list exists not to do (see `syncAttention.kind` in `Sync.sq`).
 */
@Suppress("CyclomaticComplexMethod")
// One branch per AttentionKind, which is the only way to check this reads correctly against SPEC
// §7.7. An `else` covering several would make the next kind's wording somebody's afterthought.
private fun explain(item: AttentionItem): String {
    val entry = item.entry
    return when (entry.kindOrNull) {
        AttentionKind.CONFLICTED_COPY ->
            "This note and the copy on the server could not be merged, so both were kept. " +
                (item.copyTitle?.let { "The server's version is here as “$it”. " } ?: "") +
                "Nothing was discarded."

        AttentionKind.TITLE_OVERRIDDEN ->
            "Another device renamed this note more recently, so its name won. " +
                (entry.detail?.let { "The name from this device was “$it”." } ?: "")

        AttentionKind.REMOTE_DELETED ->
            "This note was deleted on the server. The copy on this device is in the trash for " +
                "30 days — open Trash from the library to restore it, with everything that was " +
                "written on it still there."

        AttentionKind.REMOTE_NOT_RECOVERABLE ->
            "The copy on the server is missing the data Inkstone needs to merge it, so it was " +
                "left alone and this device's note was not changed. Both versions still exist."

        AttentionKind.REMOTE_TOO_NEW ->
            "The copy on the server was written by a newer version of Inkstone" +
                (entry.detail?.let { " (bundle schema $it)" } ?: "") +
                ". It has been left exactly as it is. Updating Inkstone will let it open."

        AttentionKind.RECOVERY_LOSS ->
            "Some ink could not be identified when this note was recovered from a PDF. " +
                (entry.detail ?: "The previous version is in the trash.")

        AttentionKind.QUOTA_EXCEEDED ->
            "The server is out of space, so uploads for this account are paused. They resume by " +
                "themselves once there is room."

        AttentionKind.FORBIDDEN ->
            "The server refused to write here. " + (entry.detail ?: "") +
                " Check that the folder still exists and is shared with you."

        AttentionKind.NEEDS_REAUTH ->
            "The server stopped accepting this device's app password, so syncing has stopped for " +
                "this account. Nothing is retried until you sign in again — retrying would get " +
                "this device blocked by your own server."

        // A kind from a newer build. Shown, not hidden.
        null -> entry.detail ?: "Something about syncing this note needs a look."
    }
}
