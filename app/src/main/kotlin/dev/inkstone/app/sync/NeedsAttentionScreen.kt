package dev.inkstone.app.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.inkstone.app.ui.ChipDivider
import dev.inkstone.app.ui.GlassBar
import dev.inkstone.app.ui.GlassPanel
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.Readout
import dev.inkstone.app.ui.TextChip
import dev.inkstone.app.ui.rememberDotGridBrush
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
 * ⚠️ **The M10 restyle did not make it a dialog either**, and the design's vocabulary is why that was
 * never tempting: a [GlassPanel] is the container the whole app reads content out of, so a list of
 * them is the *ordinary* shape here and a modal would have had to be argued for. The screen is
 * reached from the library and is left with the Back chip; nothing raises it.
 *
 * ## Acknowledging is a tombstone
 *
 * "Done" writes a timestamp; it does not delete the row (CLAUDE.md rule 1). Two reasons: the history
 * of what sync had to do to somebody's notes is worth keeping, and a deleted row is one the next
 * reconcile pass would cheerfully raise again as though it were new.
 */
@Composable
fun NeedsAttentionScreen(state: AttentionUiState, actions: AttentionActions, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(Ink.Canvas)) {
        // The same canvas and dot grid the note editor draws on — one tiled draw call, see
        // `rememberDotGridBrush`.
        Box(modifier = Modifier.fillMaxSize().background(rememberDotGridBrush()))

        Column(
            modifier = Modifier.fillMaxSize().padding(InkDims.EdgePadding),
            verticalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
        ) {
            GlassBar(modifier = Modifier.fillMaxWidth()) {
                TextChip(label = "‹ Back", onClick = actions.onBack)
                ChipDivider()
                MicroLabel("Needs attention", color = Ink.TextStrong)
            }

            when {
                // Not shown before the first emission: telling somebody with a conflict that nothing
                // needs attention, for one frame, is worse than showing nothing.
                !state.loaded -> Unit

                state.isEmpty -> GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Nothing needs your attention. Conflicts are resolved automatically and both " +
                            "versions are always kept, so anything that happens will show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink.TextNormal,
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
                ) {
                    items(state.items, key = { it.entry.id }) { item -> AttentionPanel(item, actions) }
                }
            }
        }
    }
}

/**
 * One entry, as a panel.
 *
 * ⚠️ **Only the headline is tinted, and most of them are not tinted at all** — see [tint]. The body
 * stays in the ordinary reading colour on purpose: as the [explain] KDoc says, an app that reports a
 * *resolved* conflict in the register of an error teaches the user to distrust it, and painting nine
 * kinds red would do exactly that with a colour instead of with a word.
 */
@Composable
private fun AttentionPanel(item: AttentionItem, actions: AttentionActions) {
    val entry = item.entry
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Text(
            headlineFor(item),
            style = MaterialTheme.typography.titleSmall,
            color = entry.kindOrNull.tint(),
        )
        Text(explain(item), style = MaterialTheme.typography.bodyMedium, color = Ink.TextNormal)
        entry.remotePath?.let { path ->
            Readout(path, color = Ink.TextMuted, maxLines = 2)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ⚠️ Not offered for a note in the trash, which is the *normal* state for a
            // REMOTE_DELETED entry. `NoteEditor.openOrCreate` falls back to `store.create` when
            // it cannot open the note it was asked for, and a trashed note's directory has moved
            // under `trash/` — so this button would silently mint a new empty note under a new
            // id and leave the user looking at a blank page where their notebook was.
            entry.noteId?.takeIf { !item.noteTrashed }?.let { id ->
                TextChip(label = "Open note", onClick = { actions.onOpenNote(id) })
            }
            entry.conflictedCopyNoteId?.let { id ->
                TextChip(label = "Open the other copy", onClick = { actions.onOpenNote(id) })
            }
            // Never `danger`: acknowledging destroys nothing (see the tombstone note above), and a
            // red chip for the only harmless verb on the screen would be the loudest lie on it.
            TextChip(label = "Done", onClick = { actions.onAcknowledge(entry.id) })
        }
    }
}

/** The note this is about, by name where there is one. An account-level entry says so. */
private fun headlineFor(item: AttentionItem): String = item.noteTitle ?: "This Nextcloud account"

/**
 * ⭐ M10 — what colour an entry's headline is, which is a much narrower question than *"what kind is
 * it"*.
 *
 * [Ink.Danger] means **syncing has stopped and only a person can restart it**: the 401 that
 * CLAUDE.md rule 5 forbids retrying, a full server, and a folder the server refuses to write to.
 * Everything else on this list has already been resolved without losing a byte, and is here to be
 * *read*, not fixed — so it is painted in the ordinary heading colour. That is the same argument
 * [explain] makes about wording, applied to the palette.
 *
 * Exhaustive with no `else`, so a kind added to [AttentionKind] later fails the build here rather
 * than quietly inheriting whichever colour happened to be the default.
 */
private fun AttentionKind?.tint(): Color = when (this) {
    AttentionKind.NEEDS_REAUTH,
    AttentionKind.QUOTA_EXCEEDED,
    AttentionKind.FORBIDDEN,
    -> Ink.Danger

    AttentionKind.CONFLICTED_COPY,
    AttentionKind.TITLE_OVERRIDDEN,
    AttentionKind.REMOTE_DELETED,
    AttentionKind.REMOTE_NOT_RECOVERABLE,
    AttentionKind.REMOTE_TOO_NEW,
    AttentionKind.RECOVERY_LOSS,
    null,
    -> Ink.TextStrong
}

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
