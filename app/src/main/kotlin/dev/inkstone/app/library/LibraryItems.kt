package dev.inkstone.app.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.inkstone.app.ui.ChipKind
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.InkType
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.Readout
import dev.inkstone.app.ui.TextChip
import dev.inkstone.core.model.Folder
import dev.inkstone.core.model.NoteSummary
import dev.inkstone.core.model.NoteSyncState

/**
 * ⭐ ROADMAP M10 — the surface every library tile is drawn on.
 *
 * A rounded [InkDims.PanelCorner] rectangle in [Ink.PanelSolid] with a 1 dp [Ink.PanelBorder]
 * hairline; selected, it takes a 2 dp [Ink.Focus] ring instead. Three things about it are
 * deliberate:
 *
 * - **Opaque, not [Ink.Panel].** The floating chrome is translucent because it hovers over the page
 *   and the page is the thing you want to keep seeing. A library tile *is* the content, and there is
 *   nothing behind it worth showing through.
 * - **No elevation.** This replaced a Material `Card`, which draws a shadow layer per cell — 500 of
 *   them in a grid that must fling at 60 fps (SPEC §5.7, ROADMAP M3's "no jank while scrolling").
 *   The design separates a tile from the canvas with a hairline, which costs one stroked round-rect.
 * - **The ring is drawn *over* the content, not under it.** `Modifier.border` draws in chain order
 *   and children draw after every draw modifier, so a border behind an edge-to-edge white thumbnail
 *   is a border you cannot see — and the 2 dp selection ring is the one that must never go missing.
 *
 * Selection used to be a tinted `containerColor`. A ring says the same thing without repainting the
 * tile, which matters here because the tile's own fill is what a folder's colour lives in.
 */
@Composable
private fun TileSurface(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.clip(InkDims.PanelCorner).background(Ink.PanelSolid)) {
        Column(content = content)
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(
                    width = if (selected) SELECTED_RING else HAIRLINE,
                    color = if (selected) Ink.Focus else Ink.PanelBorder,
                    shape = InkDims.PanelCorner,
                ),
        )
    }
}

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

    TileSurface(modifier = modifier.fillMaxWidth(), selected = selected) {
        NoteThumbnail(
            note = note,
            thumbnail = thumbnail,
            selecting = selecting,
            actions = actions,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = LABEL_PADDING_H, vertical = LABEL_PADDING_V),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = note.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Ink.TextStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                CountMeta(note.pageCount, "page", "pages")
            }
            NoteRowControls(note, actions, menuOpen, { menuOpen = it }, onRename, needsAttention)
        }
    }
}

/**
 * ⭐ The page itself, and the one light thing on the screen.
 *
 * [Ink.Paper] rather than a themed surface, on purpose and stated in two places already
 * (`InkstoneTheme`'s KDoc, `PagePainter`): a note is a PDF, the page is the *document* and not the
 * chrome, so the redesign darkens everything around it and nothing about it. A tinted thumbnail
 * would also be a thumbnail that no longer matches the file the user syncs to their desktop.
 *
 * ⚠️ The placeholder is explicitly [Ink.TextMuted]. It used to take the default `onSurface`, which
 * the dark palette makes near-white — invisible on white paper, i.e. a "loading" mark that vanishes
 * exactly when it is the only thing in the cell.
 */
@Composable
private fun NoteThumbnail(
    note: NoteSummary,
    thumbnail: ImageBitmap?,
    selecting: Boolean,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(PAGE_ASPECT)
            .background(Ink.Paper)
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
            Text("…", style = MaterialTheme.typography.headlineSmall, color = Ink.TextMuted)
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

    TileSurface(modifier = modifier.fillMaxWidth(), selected = selected) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (selecting) actions.onToggleSelect(note.id) else actions.onOpenNote(note.id) },
                    onLongClick = { actions.onToggleSelect(note.id) },
                )
                .padding(ROW_PADDING),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ROW_GAP_TIGHT),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = note.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Ink.TextStrong,
                    maxLines = 1,
                )
                CountMeta(note.pageCount, "page", "pages")
            }
            NoteRowControls(note, actions, menuOpen, { menuOpen = it }, onRename, needsAttention)
        }
    }
}

/**
 * ⭐ ROADMAP M10 — a tile's metadata line: the number monospaced, the noun tracked out.
 *
 * The design's split between a [Readout] and a [MicroLabel] is not decoration. A count is a *value*,
 * so it is monospaced: every digit is the same width, so the noun after a two-digit count sits
 * exactly one digit further along instead of at whatever offset a proportional `1` happens to
 * produce. The noun is a caption, so it takes the uppercase, tracked micro face the rest of the
 * chrome uses.
 *
 * (An earlier draft of this KDoc claimed the noun landed in the *same* place for any count. It does
 * not — nothing here fixes the number's width — and the claim was worth correcting rather than
 * making true: padding a count to a fixed column would leave a visible gap in front of every
 * single-digit note, which is worse than a one-character step.)
 *
 * Singular and plural are passed rather than derived, because "1 note"/"2 notes" and
 * "1 page"/"2 pages" are the only two call sites and a pluralisation helper for two words is a
 * helper that will one day be asked about a language that does not work that way.
 */
@Composable
private fun CountMeta(count: Int, singular: String, plural: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(META_GAP),
    ) {
        Readout(text = count.toString(), color = Ink.TextMuted, fontSize = InkType.MicroSize)
        MicroLabel(if (count == 1) singular else plural)
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
 *
 * ⭐ ROADMAP M10 — the overflow is now a [TextChip], which is what the design makes every menu
 * trigger, and it inverts to [Ink.Accent] while its menu is open — the same "this control is
 * currently doing something" signal the page bar's template chip gives.
 *
 * ⚠️ A chip is **not** free of the complaint above: [InkDims.ChipPaddingH] is 14 dp a side and
 * [InkDims.ChipMinHeight] is 34 dp, which is wider than the bare glyph square beside it though
 * narrower than the `TextButton` that caused the report. It is a deliberate trade — the design has
 * one shape for a menu trigger — and if the tablet says "too spread out" again, this padding is
 * where to look first.
 *
 * ⚠️ The star stays a bare glyph square rather than becoming a chip. A selected chip is a near-white
 * inverted block; a grid where every favourited note carries one would read as a row of warnings.
 * The star belongs with the sync badges — information, drawn in colour, not a chip.
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
                color = if (note.favourite) Ink.Focus else Ink.TextMuted,
            )
        }
        Box {
            TextChip(
                label = "⋯",
                kind = ChipKind.GLYPH,
                selected = menuOpen,
                onClick = { onMenuOpenChange(true) },
            )
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
        //
        // ⭐ ROADMAP M10 — the only item painted [Ink.Danger], for the same reason a destructive
        // chip is: this one *does* the thing. Everything above it is reversible in one tap.
        DropdownMenuItem(
            text = { Text("Move to trash", color = Ink.Danger) },
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
 *
 * ## ⭐ ROADMAP M10 — the three colours, and the one the design does not get
 *
 * The palette gives badges three buckets: [Ink.Focus] for *live or in flight*, [Ink.TextMuted] for
 * *not being synced right now*, [Ink.Danger] for *the server refused something*. Every state below
 * keeps its own (glyph, colour) pair — nothing was merged:
 *
 * | State | Glyph | Colour | Reads as |
 * |---|---|---|---|
 * | `SYNCED` | ☁ | [Ink.Focus] | the link to the server is live |
 * | `PENDING_UPLOAD` | ↑ | [Ink.Focus] | in flight, outbound |
 * | `PENDING_DOWNLOAD` | ↓ | [Ink.Focus] | in flight, inbound |
 * | `CONFLICTED` | ⚑ | [Ink.Focus] | both versions kept — look when you like |
 * | `PAUSED` | ☁ | [Ink.TextMuted] | up there, nothing happening |
 * | `ERROR` / `NEEDS_REAUTH` | ! | [Ink.Danger] | the server refused this |
 *
 * ⚠️ **`CONFLICTED` is not [Ink.Danger], and it is not an oversight.** The design's third bucket is
 * named "needs attention", which is exactly this flag's wording — and painting it red would
 * contradict the paragraph on [AttentionFlag] and CLAUDE.md rule 6. By the time the badge appears
 * the conflict is already resolved and both versions are already kept. `Danger` is reserved for the
 * two states where something is genuinely refused and the user has to act.
 *
 * `PENDING_*` moved from a neutral grey to [Ink.Focus], and `PAUSED` from the same grey to
 * [Ink.TextMuted]. They used to share a colour and differ only by glyph; now they differ by both.
 */
@Composable
private fun SyncBadge(state: NoteSyncState) {
    // A note that was never bound is the ordinary case and gets no badge at all. Marking every
    // local note would make the indicator noise rather than information.
    if (state == NoteSyncState.LOCAL_ONLY) return

    val (glyph, description, tint) = when (state) {
        NoteSyncState.SYNCED -> Triple("☁", "Saved to Nextcloud", Ink.Focus)

        NoteSyncState.PENDING_UPLOAD -> Triple("↑", "Waiting to upload", Ink.Focus)

        NoteSyncState.PENDING_DOWNLOAD -> Triple("↓", "Downloading changes", Ink.Focus)

        // Not an error, and deliberately not red: SPEC §7.7 resolves conflicts automatically
        // and non-destructively, so nothing has gone wrong and nothing is blocked. It is a
        // "there is a second copy to look at when you feel like it" marker.
        NoteSyncState.CONFLICTED -> Triple("⚑", "Both versions kept — needs attention", Ink.Focus)

        NoteSyncState.ERROR -> Triple("!", "The server refused this note", Ink.Danger)

        NoteSyncState.NEEDS_REAUTH -> Triple("!", "Sign in again to resume syncing", Ink.Danger)

        // Still on Nextcloud, just not being talked to. The same cloud as SYNCED, dimmed —
        // "it is up there, nothing is happening right now" — rather than a different glyph,
        // which would read as a different kind of thing.
        NoteSyncState.PAUSED -> Triple("☁", "Saved to Nextcloud — signed out", Ink.TextMuted)

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
 * [Ink.Focus] and not [Ink.Danger], deliberately, and this is the whole tone of M6's conflict story
 * in one line: by the time this flag appears, the conflict has already been resolved and both
 * versions have already been kept. Nothing is broken, nothing is blocked and nothing is waiting on
 * the user. Drawing it in red would teach them to read a safe, automatic, non-destructive outcome as
 * a failure — which is exactly the reflex CLAUDE.md rule 6 is trying to avoid by refusing to put a
 * dialog here. (Before M10 this said "the tertiary colour"; the palette maps tertiary onto
 * [Ink.Focus], so the pixels are unchanged and only the name is now honest about which they are.)
 */
@Composable
private fun AttentionFlag() {
    Box(
        modifier = Modifier
            .size(BADGE_SIZE)
            .semantics { contentDescription = "Needs attention — both versions were kept" },
        contentAlignment = Alignment.Center,
    ) {
        Text("⚑", style = MaterialTheme.typography.labelMedium, color = Ink.Focus)
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
        Text("☁", style = MaterialTheme.typography.labelMedium, color = Ink.Focus)
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
    /**
     * ⭐ M12 §10 — *"Auto-record is per folder, not global."*
     *
     * *"Auto-record in Maths"* is what is actually wanted: you record in lectures, not when
     * reviewing at home. It is on the **folder's** menu and nowhere else, because a global switch
     * would mean the microphone turns itself on whenever any note opens — a materially different
     * thing from a button, and one that makes the notification the only signal.
     *
     * @param autoRecord what this folder itself says, ignoring its ancestors. Null means *inherit*,
     *   which is a third state and not the same as off: *"record in Maths, but not in
     *   Maths/Revision"* needs an explicit off that overrides the on above it.
     */
    val autoRecord: Boolean?,
    val onSetAutoRecord: (Boolean?) -> Unit,
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

    // The tile stays on the theme's surface and only the *picture* is tinted. Tinting the whole
    // tile painted the folder's name onto its own background — see `FolderTile`.
    TileSurface(modifier = modifier.fillMaxWidth()) {
        FolderTile(
            folder = folder,
            cover = cover,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PAGE_ASPECT)
                .combinedClickable(onClick = actions.onOpen),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = LABEL_PADDING_H, vertical = LABEL_PADDING_V),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = Ink.TextStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                CountMeta(noteCount, "note", "notes")
            }
            if (synced) FolderSyncBadge()
            Box {
                TextChip(label = "⋯", kind = ChipKind.GLYPH, selected = menuOpen, onClick = { menuOpen = true })
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

    TileSurface(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = actions.onOpen).padding(ROW_PADDING),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
        ) {
            FolderTile(
                folder = folder,
                cover = cover,
                modifier = Modifier.size(ROW_TILE_SIZE).clip(InkDims.ChipCorner),
            )
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleSmall,
                color = Ink.TextStrong,
                modifier = Modifier.weight(1f),
            )
            if (synced) FolderSyncBadge()
            Box {
                TextChip(label = "⋯", kind = ChipKind.GLYPH, selected = menuOpen, onClick = { menuOpen = true })
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
        AutoRecordItem(actions = actions, onDismiss = onDismiss)
        DropdownMenuItem(
            text = { Text("Colour and icon") },
            onClick = {
                actions.onStyle()
                onDismiss()
            },
        )
        // ⭐ ROADMAP M10 — red for the same reason the note menu's is: this one does the thing.
        DropdownMenuItem(
            text = { Text("Move to trash", color = Ink.Danger) },
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
 *
 * ⭐ ROADMAP M10 — these still size the **star** only. The overflow beside it is a [TextChip], which
 * brings its own [InkDims.ChipMinHeight] and 14 dp of chip padding; forcing it into 30 dp would
 * leave 2 dp for the glyph after that padding, so it is left to size itself.
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

/** ⭐ ROADMAP M10 — the tile's hairline, and the ring that replaces it when the tile is selected. */
private val HAIRLINE = 1.dp
private val SELECTED_RING = 2.dp

/** The strip under a grid tile's picture. Tight, because the picture is the tile. */
private val LABEL_PADDING_H = 10.dp
private val LABEL_PADDING_V = 8.dp

/** The list layout's inset, and the space between the things sitting in one row. */
private val ROW_PADDING = 12.dp
private val ROW_GAP = 12.dp
private val ROW_GAP_TIGHT = 8.dp

/** Between a [Readout]'s digits and the [MicroLabel] naming what they count. */
private val META_GAP = 5.dp

/**
 * ⭐ M12 §10 — auto-record for this folder, cycled by tapping: inherit → on → off → inherit.
 *
 * Three states, so not a checkbox. *"Inherit"* is what every folder starts in and is the commonest
 * answer; an explicit **off** is what makes *"record in Maths, but not in Maths/Revision"* sayable,
 * and without it the only way to stop recording in a subfolder is to move it out of the tree.
 */
@Composable
private fun AutoRecordItem(actions: FolderActions, onDismiss: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                when (actions.autoRecord) {
                    true -> "Auto-record: on"
                    false -> "Auto-record: off"
                    null -> "Auto-record: inherit"
                },
            )
        },
        onClick = {
            actions.onSetAutoRecord(
                when (actions.autoRecord) {
                    null -> true
                    true -> false
                    false -> null
                },
            )
            onDismiss()
        },
    )
}
