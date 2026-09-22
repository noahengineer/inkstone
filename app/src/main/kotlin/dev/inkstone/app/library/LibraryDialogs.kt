package dev.inkstone.app.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.Readout
import dev.inkstone.app.ui.SwatchStrip
import dev.inkstone.app.ui.TextChip
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
 *
 * ⭐ ROADMAP M10 — the dialog itself stays a Material [AlertDialog], which now inherits the dark
 * palette from `InkstoneTheme`; what changed is the chrome inside it. The buttons are [TextChip]s
 * and the sanitised name is a caption plus a [Readout] rather than one sentence, because the *name*
 * is data: a [MicroLabel] would uppercase it and show the user a string the app is not about to
 * save.
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
            Column(verticalArrangement = Arrangement.spacedBy(InkDims.GroupGap)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
                if (changed) {
                    MicroLabel("Saved as")
                    Readout("“$sanitised”", color = Ink.TextStrong, maxLines = 2)
                }
            }
        },
        confirmButton = {
            TextChip(label = "Save", enabled = text.isNotBlank(), onClick = { onConfirm(text) })
        },
        dismissButton = { TextChip(label = "Cancel", onClick = onDismiss) },
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
            LazyColumn(
                modifier = Modifier.heightIn(max = PICKER_MAX_HEIGHT.dp),
                verticalArrangement = Arrangement.spacedBy(InkDims.ChipGap),
            ) {
                // The one destination that is not a folder, so it is the one entry that is a word
                // rather than a name — and therefore the one that can be a chip.
                item { TextChip(label = "Library (top level)", onClick = { onPick(null) }) }
                items(flattened, key = { (folder, _) -> folder.id.value }) { (folder, path) ->
                    FolderRow(folder = folder, depth = path.size - 1, onClick = { onPick(folder.id) })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextChip(label = "Cancel", onClick = onDismiss) },
    )
}

/**
 * One destination in the picker.
 *
 * ⭐ ROADMAP M10 — a row and **not** a [TextChip], because a folder's name is user data and a chip
 * uppercases its label: `PHYSICS / TERM 2` is not what the folder is called. The depth is still the
 * indent — a folder's *path* is what makes two folders called "Notes" tellable apart.
 */
@Composable
private fun FolderRow(folder: Folder, depth: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
        modifier = Modifier
            .fillMaxWidth()
            .clip(InkDims.ChipCorner)
            .clickable(onClick = onClick)
            // ⚠️ The `TextButton` this replaced carried `minimumInteractiveComponentSize()` — a
            // 48 dp target — for free. A bare `Row` of a 20 dp glyph and one line of text is about
            // 28 dp, and this is a list somebody scrolls with a thumb to file a note.
            .defaultMinSize(minHeight = PICKER_ROW_MIN_HEIGHT.dp)
            .padding(
                start = InkDims.ChipPaddingH + (INDENT_PER_LEVEL * depth).dp,
                end = InkDims.ChipPaddingH,
                top = RowPaddingV,
                bottom = RowPaddingV,
            ),
    ) {
        FolderGlyph(icon = folder.icon, tint = Ink.TextMuted, modifier = Modifier.size(PICKER_GLYPH_SIZE.dp))
        Text(
            text = folder.name,
            style = MaterialTheme.typography.bodyMedium,
            color = Ink.TextStrong,
            maxLines = 1,
            // A folder name is the user's own text and can be any length. Clipped it ends
            // mid-glyph; this is a picker, so the tail is often the part that tells two siblings
            // apart.
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * SPEC §9 — a folder's colour, icon and cover, **with a preview**.
 *
 * The preview is the point. Picking a colour and an icon from two rows of abstract chips and
 * only seeing the result after saving is guesswork, and it is what made the contrast problem
 * easy to ship in the first place: nothing in the dialog showed that the label was about to
 * become unreadable. The preview is the real [FolderTile] and the real label strip — the same
 * composables the grid draws — so what it shows is what you get, contrast rule included.
 *
 * ⚠️ **Draw a cover and Choose an image leave the dialog** — one for the editor, one for the system
 * photo picker — so they save on the way out. Losing the colour you just picked because you then
 * chose to draw a cover would be its own small betrayal.
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
    // One lambda rather than three copies of the same three-argument call, so the two paths that
    // save on the way out (see the KDoc) cannot drift from the one the Save chip takes.
    val save = { onConfirm(color, icon, coverChoice) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(folder.name) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(SECTION_GAP.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                FolderPreview(folder.copy(colorArgb = color, icon = icon, cover = coverChoice), cover)

                MicroLabel("Colour")
                ColourRow(selected = color, onSelect = { color = it })

                MicroLabel("Icon")
                IconGrid(
                    selected = icon.takeIf { coverChoice == FolderCover.Icon },
                    onSelect = {
                        icon = it
                        // Choosing an icon means you want to see the icon.
                        coverChoice = FolderCover.Icon
                    },
                )

                MicroLabel("Cover")
                CoverChoices(
                    folder = folder,
                    selected = coverChoice,
                    onSelect = { coverChoice = it },
                    onDrawCover = {
                        save()
                        onDrawCover()
                    },
                    onPickImage = {
                        save()
                        onPickImage()
                    },
                )
            }
        },
        confirmButton = { TextChip(label = "Save", onClick = save) },
        dismissButton = { TextChip(label = "Cancel", onClick = onDismiss) },
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

private const val ICONS_PER_ROW = 4
private const val PICKER_MAX_HEIGHT = 360
private const val PICKER_GLYPH_SIZE = 20

/** Android's own minimum touch target. `TextButton` gave this for free; a bare `Row` does not. */
private const val PICKER_ROW_MIN_HEIGHT = 48
private const val INDENT_PER_LEVEL = 16
private const val SECTION_GAP = 12

/**
 * A sentinel [SwatchStrip] can never match, for the folder that has no colour at all.
 *
 * Fully transparent black is not in [FOLDER_COLORS] and cannot be, so "None" rings nothing — which
 * is exactly what having picked no colour looks like.
 */
private const val NO_COLOUR = 0

/** The tile and its label, exactly as the grid draws them. */
@Composable
private fun FolderPreview(folder: Folder, cover: ImageBitmap?) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(InkDims.GroupGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ⭐ M10 — a panel rather than a `Card`: the same fill, hairline and radius every other
        // surface in the app uses, so the preview reads as a tile on the design's ground.
        Column(
            modifier = Modifier
                .width(PREVIEW_WIDTH.dp)
                // The **real** tile surface (`LibraryItems.TileSurface`), not `PanelRaised`: a
                // preview drawn on a different ground than the grid is a preview of something else.
                .background(Ink.PanelSolid, InkDims.SegmentCorner)
                .border(HairlineWidth, Ink.PanelBorder, InkDims.SegmentCorner)
                .clip(InkDims.SegmentCorner),
        ) {
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
                // Explicit, because inside an `AlertDialog` the ambient content colour is the
                // dialog's body colour and not the grid's — which would make the one label whose
                // job is to predict the grid the only one that did not match it.
                color = Ink.TextStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = LabelPaddingH, vertical = LabelPaddingV),
            )
        }
        MicroLabel("Preview")
    }
}

/**
 * An icon choice that shows the icon, rather than its name in lower case.
 *
 * ⭐ M10 — chosen is a [Ink.Focus] ring over the lighter [Ink.SegmentActive] fill, the same pairing
 * a [dev.inkstone.app.ui.SegmentChip] uses, because a grid of glyphs told apart only by a border one
 * shade brighter than its neighbour's is a grid with no visible answer.
 */
@Composable
private fun IconChoice(icon: FolderIcon, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(ICON_CHOICE_SIZE.dp)
            .background(if (selected) Ink.SegmentActive else Ink.SegmentTrack, InkDims.SegmentInnerCorner)
            .border(
                width = if (selected) SelectedBorder else HairlineWidth,
                color = if (selected) Ink.Focus else Ink.PanelBorder,
                shape = InkDims.SegmentInnerCorner,
            )
            .clip(InkDims.SegmentInnerCorner)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        FolderGlyph(
            icon = icon,
            tint = if (selected) Ink.TextStrong else Ink.TextNormal,
            modifier = Modifier.size(ICON_CHOICE_GLYPH.dp),
        )
    }
}

private const val PREVIEW_WIDTH = 120
private const val PREVIEW_TILE_HEIGHT = 150
private const val ICON_CHOICE_SIZE = 52
private const val ICON_CHOICE_GLYPH = 30

/**
 * The palette, plus "None" — which is not the same as picking grey.
 *
 * ⭐ M10 — the colours are the design system's [SwatchStrip], so a chosen one is ringed and grows
 * rather than being told apart by a one-pixel border. "None" stays a **word**: a swatch cannot draw
 * the absence of a colour, and an empty circle reads as white.
 */
@Composable
private fun ColourRow(selected: Int?, onSelect: (Int?) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A folder the user has left plain follows the theme; one they coloured does not.
        TextChip(label = "None", selected = selected == null, onClick = { onSelect(null) })
        SwatchStrip(
            colors = FOLDER_COLORS,
            selectedArgb = selected ?: NO_COLOUR,
            onPick = { onSelect(it) },
        )
    }
}

/** @param selected null when the folder is showing a cover rather than its icon. */
@Composable
private fun IconGrid(selected: FolderIcon?, onSelect: (FolderIcon) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(InkDims.ChipGap)) {
        for (row in FolderIcon.entries.chunked(ICONS_PER_ROW)) {
            Row(horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap)) {
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

/**
 * SPEC §9 — what fills the tile. See `FolderCover` for why each option costs almost nothing.
 *
 * ⭐ M10 — the two *states* are chips that light up, the two *actions* are chips that do not, and
 * they are on separate lines for that reason. `Draw a cover…` is never `selected` even when the
 * folder is showing a drawn cover: it opens the editor, and a chip that reads as chosen while its
 * press navigates somewhere is chrome that lies about what a tap will do.
 */
@Composable
private fun CoverChoices(
    folder: Folder,
    selected: FolderCover,
    onSelect: (FolderCover) -> Unit,
    onDrawCover: () -> Unit,
    onPickImage: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(InkDims.ChipGap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(InkDims.ChipGap)) {
            TextChip(
                label = "Icon",
                selected = selected == FolderCover.Icon,
                onClick = { onSelect(FolderCover.Icon) },
            )
            TextChip(
                label = "Latest note",
                selected = selected == FolderCover.LatestNote,
                onClick = { onSelect(FolderCover.LatestNote) },
            )
        }
        TextChip(
            label = if (folder.cover is FolderCover.Drawn) "Edit the drawn cover…" else "Draw a cover…",
            onClick = onDrawCover,
        )
        TextChip(label = "Choose an image…", onClick = onPickImage)
    }
}

/** The design's 1 dp edge, and the 2 dp one a chosen icon wears. See `Glass.kt`. */
private val HairlineWidth = 1.dp
private val SelectedBorder = 2.dp

/** The picker row's own vertical padding — a chip's, so a row and a chip line up. */
private val RowPaddingV = InkDims.ChipPaddingV

private val LabelPaddingH = 8.dp
private val LabelPaddingV = 4.dp
