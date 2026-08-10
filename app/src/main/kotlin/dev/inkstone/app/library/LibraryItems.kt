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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.inkstone.core.model.Folder
import dev.inkstone.core.model.NoteSummary
import dev.inkstone.core.model.NoteSyncState

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
    /** SPEC §7.7 — this note has an open entry in the "Needs attention" list (M6). */
    needsAttention: Boolean = false,
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
            NoteRowControls(note, actions, menuOpen, { menuOpen = it }, onRename, needsAttention)
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
    /** SPEC §7.7 — this note has an open entry in the "Needs attention" list (M6). */
    needsAttention: Boolean = false,
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
            NoteRowControls(note, actions, menuOpen, { menuOpen = it }, onRename, needsAttention)
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
    needsAttention: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CONTROL_GAP),
    ) {
        // ⭐ SPEC §7.7's flag, beside the sync badge and deliberately not instead of it (M6). The
        // two answer different questions — "is this on Nextcloud" and "did sync have to do something
        // to it" — and a note can be perfectly synced *and* have a conflicted copy sitting next to
        // it. Drawn from a set of ids the library already holds, so it costs no query per tile.
        //
        // Not a tap target, like the sync badge: the flag is information, and the list it refers to
        // is one button away in the toolbar. Tapping a tile opens the note, which is what tapping a
        // note should always do.
        if (needsAttention) AttentionFlag()
        SyncBadge(note.syncState)
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
        // SPEC §7.6 — binding is per note and reversible, so both directions live here.
        // `syncState` is the only thing the library index knows about a note's binding,
        // and LOCAL_ONLY is exactly "not bound"; asking the binding store per row would
        // be a query per tile, which is what SPEC §5.7 exists to avoid.
        if (note.syncState == NoteSyncState.LOCAL_ONLY) {
            DropdownMenuItem(
                text = { Text("Save to Nextcloud…") },
                onClick = {
                    actions.onSaveToNextcloud(note.id)
                    onDismiss()
                },
            )
        } else {
            DropdownMenuItem(
                text = { Text("Stop syncing") },
                onClick = {
                    actions.onStopSyncing(note.id)
                    onDismiss()
                },
            )
            // Relocating used to mean "Stop syncing" then "Save to Nextcloud…" elsewhere, which
            // leaves the original file on the server (unbinding never deletes it — rule 1) and
            // creates a second one. This is one MOVE and no duplicate.
            DropdownMenuItem(
                text = { Text("Change sync folder…") },
                onClick = {
                    actions.onChangeSyncFolder(note.id)
                    onDismiss()
                },
            )
        }
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
 * ⭐ Whether this note is on Nextcloud, at a glance (M6).
 *
 * The library previously said nothing at all about a note's sync state — you had to open the
 * overflow menu and infer it from whether the item read "Save to Nextcloud…" or "Stop
 * syncing". For an app whose whole premise is that notes live in your Nextcloud, "is this one
 * actually up there?" is the first question a user has, and it had no answer.
 *
 * Drawn from [NoteSummary.syncState], which is already on the row — so this costs no query per
 * tile, which is the constraint SPEC §5.7 exists to protect (a 500-note library at 60 fps).
 *
 * Glyphs rather than icons for the same reason the favourite star is one: an icon font
 * dependency for six states is not worth the download, and these read correctly at tile size.
 * Nothing here is a tap target — the state is information, and the actions are in the menu.
 */
@Composable
private fun SyncBadge(state: NoteSyncState) {
    // A note that was never bound is the ordinary case and gets no badge at all. Marking every
    // local note would make the indicator noise rather than information.
    if (state == NoteSyncState.LOCAL_ONLY) return

    val (glyph, description, tint) = when (state) {
        NoteSyncState.SYNCED -> Triple("☁", "Saved to Nextcloud", MaterialTheme.colorScheme.primary)

        NoteSyncState.PENDING_UPLOAD -> Triple("↑", "Waiting to upload", MaterialTheme.colorScheme.onSurfaceVariant)

        NoteSyncState.PENDING_DOWNLOAD -> Triple("↓", "Downloading changes", MaterialTheme.colorScheme.onSurfaceVariant)

        // Not an error, and deliberately not red: SPEC §7.7 resolves conflicts automatically
        // and non-destructively, so nothing has gone wrong and nothing is blocked. It is a
        // "there is a second copy to look at when you feel like it" marker.
        NoteSyncState.CONFLICTED -> Triple(
            "⚑",
            "Both versions kept — needs attention",
            MaterialTheme.colorScheme.tertiary,
        )

        NoteSyncState.ERROR -> Triple("!", "The server refused this note", MaterialTheme.colorScheme.error)

        NoteSyncState.NEEDS_REAUTH -> Triple("!", "Sign in again to resume syncing", MaterialTheme.colorScheme.error)

        // Still on Nextcloud, just not being talked to. The same cloud as SYNCED, dimmed —
        // "it is up there, nothing is happening right now" — rather than a different glyph,
        // which would read as a different kind of thing.
        NoteSyncState.PAUSED -> Triple(
            "☁",
            "Saved to Nextcloud — signed out",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )

        NoteSyncState.LOCAL_ONLY -> return
    }

    Box(
        modifier = Modifier.size(BADGE_SIZE).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/**
 * ⭐ SPEC §7.7 — this note has something in the "Needs attention" list (M6).
 *
 * The **tertiary** colour and not the error colour, deliberately, and this is the whole tone of
 * M6's conflict story in one line: by the time this flag appears, the conflict has already been
 * resolved and both versions have already been kept. Nothing is broken, nothing is blocked and
 * nothing is waiting on the user. Drawing it in red would teach them to read a safe, automatic,
 * non-destructive outcome as a failure — which is exactly the reflex CLAUDE.md rule 6 is trying to
 * avoid by refusing to put a dialog here.
 */
@Composable
private fun AttentionFlag() {
    Box(
        modifier = Modifier
            .size(BADGE_SIZE)
            .semantics { contentDescription = "Needs attention — both versions were kept" },
        contentAlignment = Alignment.Center,
    ) {
        Text("⚑", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
    }
}

/**
 * ⭐ The cloud on a folder that is pinned, or sits under one (M6).
 *
 * Deliberately the same glyph and colour as a synced note's, because it means the same thing to
 * the person looking at it: *the things in here are in my Nextcloud*. A different mark would
 * imply a different kind of state and invite the question of what the difference is.
 *
 * "Covered by a pin" rather than "pinned": a subfolder of a pinned folder syncs just as much as
 * the folder that was pinned, and showing the cloud only on the one that happens to hold the
 * mapping row would be describing the implementation rather than the outcome.
 */
@Composable
private fun FolderSyncBadge() {
    Box(
        modifier = Modifier
            .size(BADGE_SIZE)
            .semantics { contentDescription = "Notes in this folder sync to Nextcloud" },
        contentAlignment = Alignment.Center,
    ) {
        Text("☁", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
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
    /**
     * M6 — pin this folder to a path on the server, so everything in it (and in every folder
     * beneath it) syncs there. SPEC §9's folder mirror.
     */
    val onSyncToFolder: () -> Unit,
)

@Composable
fun FolderCard(
    folder: Folder,
    noteCount: Int,
    actions: FolderActions,
    modifier: Modifier = Modifier,
    cover: ImageBitmap? = null,
    /** True when this folder is covered by a pin, so its notes sync (M6). */
    synced: Boolean = false,
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
            if (synced) FolderSyncBadge()
            Box {
                TextButton(onClick = { menuOpen = true }) { Text("⋯") }
                FolderMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, actions = actions)
            }
        }
    }
}

@Composable
fun FolderRow(
    folder: Folder,
    actions: FolderActions,
    modifier: Modifier = Modifier,
    cover: ImageBitmap? = null,
    synced: Boolean = false,
) {
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
            if (synced) FolderSyncBadge()
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
        // SPEC §9's mirror. Worded as the outcome rather than as "pin" or "bind": what the
        // user is choosing is where the notes in here end up.
        DropdownMenuItem(
            text = { Text("Always sync to…") },
            onClick = {
                actions.onSyncToFolder()
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
 *
 * Tightened again in M6, after the sync badge made a row of three. 36 dp was already the
 * smaller-than-default choice (a `TextButton` is wider still), but three of them plus gaps
 * pushed the title's ellipsis in far enough to be reported as too spread out. 30 dp is still
 * above the 24 dp Material calls the floor for a control with visible bounds, and the glyphs
 * are centred in it so nothing looks cramped.
 *
 * The gap is **zero**: these are one group, and the visual separation comes from each glyph
 * sitting in its own square rather than from space between the squares.
 */
private val CONTROL_SIZE = 30.dp
private val CONTROL_GAP = 0.dp

/**
 * The sync badge is **not** a tap target, so it is not bound by a touch-target minimum.
 *
 * Sizing it like the star and the overflow spent 30 dp on something nobody can press, which is
 * most of why the group read as spread out. It only has to be legible.
 */
private val BADGE_SIZE = 18.dp

/** The folder's picture in the list layout. */
private val ROW_TILE_SIZE = 44.dp
