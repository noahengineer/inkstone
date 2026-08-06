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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
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
        Text(
            text = "Pages",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = CELL_MIN_WIDTH.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PAGE_ASPECT)
                .background(Color.White)
                .then(
                    if (page.isCurrent) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        Modifier
                    },
                )
                .clickable { onGoToPage(index) },
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = image
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("…")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onMovePage(index, index - 1) }, enabled = index > 0) { Text("‹") }
            Text("${index + 1}", style = MaterialTheme.typography.labelMedium)
            TextButton(
                onClick = { onMovePage(index, index + 1) },
                enabled = index < page.lastIndex,
            ) { Text("›") }
        }
    }
}

private const val CELL_MIN_WIDTH = 120
private const val PAGE_ASPECT = 612f / 792f
