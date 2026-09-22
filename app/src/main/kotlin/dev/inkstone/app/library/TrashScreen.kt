package dev.inkstone.app.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.inkstone.app.ui.ChipDivider
import dev.inkstone.app.ui.ChipKind
import dev.inkstone.app.ui.GlassBar
import dev.inkstone.app.ui.GlassPanel
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.TextChip
import dev.inkstone.app.ui.rememberDotGridBrush
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FolderIcon
import dev.inkstone.core.model.FolderId
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
 *
 * ⭐ ROADMAP M10 — restyled onto the design system, and the rule above survives the restyle
 * intact: [Ink.Danger] and `danger = true` appear **nowhere** on this screen, because nothing here
 * is destructive. The one verb is `RESTORE`.
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

    Box(modifier = modifier.fillMaxSize().background(Ink.Canvas)) {
        // ⭐ M10 — the same near-black ground and 24 dp dot grid the note editor stands on, so the
        // trash reads as part of this app rather than as a system list. One tiled draw call — see
        // `rememberDotGridBrush` for why a `drawBehind` loop is not an option anywhere in here.
        Box(modifier = Modifier.fillMaxSize().background(rememberDotGridBrush()))

        Column(modifier = Modifier.fillMaxSize()) {
            TrashHeader(onBack = onBack)

            if (state.isEmpty) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MicroLabel("The trash is empty")
                }
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(InkDims.EdgePadding),
                verticalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.folders, key = { "folder-${it.id.value}" }) { folder ->
                    TrashEntry(
                        title = folder.name,
                        subtitle = "Folder — restores with everything in it",
                        deletedEpochMillis = folder.deletedEpochMillis,
                        nowEpochMillis = nowEpochMillis,
                        onRestore = { onRestoreFolder(folder.id) },
                        icon = folder.icon,
                    )
                }
                items(state.notes, key = { "note-${it.id.value}" }) { note ->
                    TrashEntry(
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
}

/**
 * ⭐ ROADMAP M10 — the design's floating pill, where a row of Material text used to sit.
 *
 * ⚠️ The retention *policy* is here and the per-entry *clock* is on each row, and both are needed:
 * "kept for 30 days" without a countdown is a promise with no evidence, and a countdown without the
 * policy reads as a threat. Dropping either was the temptation the restyle had to refuse.
 */
@Composable
private fun TrashHeader(onBack: () -> Unit) {
    GlassBar(modifier = Modifier.padding(InkDims.EdgePadding)) {
        // An ordinary word chip, as on the sync and attention screens: the label is a *word* with a
        // mark in front of it, so it takes the design's uppercase voice like every other one.
        TextChip(label = "‹ Back", onClick = onBack)
        ChipDivider()
        MicroLabel("Trash", color = Ink.TextStrong)
    }
}

/**
 * One thing in the trash, on the design's panel surface.
 *
 * @param icon a folder's icon, drawn as the glyph it is. It used to arrive inside [title] as
 *   `icon.name.lowercase()` — the icon *spelled out* in front of the folder's name — which is the
 *   one thing a picture is better at than a word.
 */
@Composable
private fun TrashEntry(
    title: String,
    subtitle: String,
    deletedEpochMillis: Long?,
    nowEpochMillis: Long,
    onRestore: () -> Unit,
    icon: FolderIcon? = null,
) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
        ) {
            icon?.let { glyph ->
                FolderGlyph(icon = glyph, tint = Ink.TextMuted, modifier = Modifier.size(EntryGlyph))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(EntryTextGap)) {
                // A note's title is user data, so it is a `Text` and not a chip or a label: both of
                // those uppercase, and a notebook called "pH and buffers" is not "PH AND BUFFERS".
                Text(text = title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                MicroLabel(subtitle)
                // Empty for an entry with no deletion timestamp, and an empty label is a blank line
                // rather than nothing — so it is omitted instead of rendered.
                retentionText(deletedEpochMillis, nowEpochMillis)
                    .takeIf { it.isNotEmpty() }
                    ?.let { MicroLabel(it) }
            }
            // The only verb on this screen. Never `danger = true`: nothing here destroys anything,
            // and a red chip would advertise a code path that deliberately does not exist.
            TextChip(label = "Restore", onClick = onRestore)
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

/** A folder's glyph beside its name, at the size the folder picker uses. */
private val EntryGlyph = 20.dp

/** Tighter than [InkDims.GroupGap] — these three lines are one block, not three. */
private val EntryTextGap = 2.dp
