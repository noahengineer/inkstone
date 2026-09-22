package dev.inkstone.app.note

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import dev.inkstone.app.ui.ChipKind
import dev.inkstone.app.ui.Ink
import dev.inkstone.app.ui.InkDims
import dev.inkstone.app.ui.MicroLabel
import dev.inkstone.app.ui.Readout
import dev.inkstone.app.ui.TextChip
import dev.inkstone.core.model.PageId

/**
 * ROADMAP M3 — *"page thumbnails for the reorder UI"*.
 *
 * Every page of the open note, as a picture, with tap-to-go and move-left/right. The moves
 * reuse M2's `movePage`, which writes a fractional key (SPEC §4.3) rather than an index, so
 * two devices reordering the same notebook offline both keep their changes.
 *
 * ## Why not drag-and-drop
 *
 * Because M3 is not the milestone for it. Dragging in a lazy grid means hand-rolling
 * auto-scroll, drop targets and a reorder animation, and the outcome is the same page order
 * this gets with two taps. SPEC §12 keeps the v1 surface small deliberately; the note here is
 * that the *model* already supports arbitrary reordering, so adding the gesture later changes
 * the input and nothing else.
 *
 * ## Thumbnails
 *
 * Folded from the **open session**, not from disk — the journal holds ops the bundle does
 * not, so a page drawn on two seconds ago would otherwise show its previous contents.
 * Loading is one `LaunchedEffect` per cell, keyed on the page id and the note's revision, so
 * an edit invalidates exactly the pages it should and a scrolled-away cell cancels its load.
 *
 * ## ⭐ ROADMAP M10 — still a `ModalBottomSheet`
 *
 * The redesign replaces *chrome*, and a sheet full of page thumbnails is content: it inherits the
 * palette from `InkstoneTheme` and needs nothing bespoke. What changed is inside the cells, and one
 * of those changes is not cosmetic — see [PageCell] on the border.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageOverviewSheet(
    note: NoteEditorState,
    thumbnail: suspend (PageId) -> ImageBitmap?,
    onGoToPage: (Int) -> Unit,
    onMovePage: (from: Int, to: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        MicroLabel(text = "Pages", modifier = Modifier.padding(horizontal = SheetPadding))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = CellMinWidth),
            contentPadding = PaddingValues(SheetPadding),
            horizontalArrangement = Arrangement.spacedBy(CellGap),
            verticalArrangement = Arrangement.spacedBy(CellGap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(note.pageIds, key = { _, id -> id.value }) { index, pageId ->
                PageCell(
                    page = PageCellState(
                        pageId = pageId,
                        index = index,
                        isCurrent = index == note.pageIndex,
                        lastIndex = note.pageIds.lastIndex,
                        localRev = note.localRev,
                    ),
                    thumbnail = thumbnail,
                    onGoToPage = onGoToPage,
                    onMovePage = onMovePage,
                )
            }
        }
    }
}

/** One cell's worth of facts. A parameter object because eight positional arguments is a bug waiting. */
private data class PageCellState(
    val pageId: PageId,
    val index: Int,
    val isCurrent: Boolean,
    val lastIndex: Int,
    val localRev: Long,
)

/**
 * One page.
 *
 * ⚠️ **The current page's ring is [Ink.Focus] and not `colorScheme.primary`** — which happen to be
 * the same colour today, because `InkstoneTheme` maps one onto the other, and that is exactly why it
 * is written out. The mapping exists so *Material's* chrome inherits the palette; a bespoke ring
 * drawn on the app's one white surface is a design decision of its own, and reading it through
 * Material's role would silently re-point it at whatever a future scheme puts in `primary`.
 *
 * The paper stays [Ink.Paper]. A note is a PDF and the page is the document, not the chrome
 * (`InkstoneTheme`), so a thumbnail is the one thing on this screen that must not go dark.
 */
@Composable
private fun PageCell(
    page: PageCellState,
    thumbnail: suspend (PageId) -> ImageBitmap?,
    onGoToPage: (Int) -> Unit,
    onMovePage: (Int, Int) -> Unit,
) {
    val pageId = page.pageId
    val index = page.index
    var image by remember(pageId.value) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(pageId.value, page.localRev) { image = thumbnail(pageId) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PageThumbnail(image = image, isCurrent = page.isCurrent, onClick = { onGoToPage(index) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextChip(
                label = "‹",
                kind = ChipKind.GLYPH,
                enabled = index > 0,
                onClick = { onMovePage(index, index - 1) },
            )
            Readout("${index + 1}", color = if (page.isCurrent) Ink.Focus else Ink.TextNormal)
            TextChip(
                label = "›",
                kind = ChipKind.GLYPH,
                enabled = index < page.lastIndex,
                onClick = { onMovePage(index, index + 1) },
            )
        }
    }
}

/** The paper itself, once its bitmap has arrived. See [PageCell] for why the ring is not `primary`. */
@Composable
private fun PageThumbnail(image: ImageBitmap?, isCurrent: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(PAGE_ASPECT)
            .clip(InkDims.SegmentInnerCorner)
            .background(Ink.Paper)
            .then(
                if (isCurrent) {
                    Modifier.border(CurrentRing, Ink.Focus, InkDims.SegmentInnerCorner)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Muted rather than the readout's usual grey: this one sits on white paper, where the
            // palette's normal text colour is very nearly invisible.
            Readout("…", color = Ink.TextMuted)
        }
    }
}

private val CellMinWidth = 120.dp
private val CellGap = 12.dp
private val SheetPadding = 16.dp

/** Thick enough to survive being drawn inside a 120 dp cell's rounded corner. */
private val CurrentRing = 2.dp

private const val PAGE_ASPECT = 612f / 792f
