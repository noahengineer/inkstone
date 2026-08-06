package dev.inkstone.app.note

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.inkstone.android.pdf.PdfPageRasterizer
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.storage.NoteLayout
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * SPEC §6.2 — the paper under the ink, for a note imported from a PDF.
 *
 * ## Why the source is `document.pdf`
 *
 * Because the imported PDF *is* the front of it, verbatim and forever (SPEC §5.2).
 * There is no second copy to render from, and that is the point of the incremental
 * update: a 20 MB lecture is stored once. Rendering page *n* of the published file
 * gives exactly the page that was imported, plus whatever ink the last
 * materialization wrote — which is not wanted here, but is also not a problem,
 * because the strokes are drawn live on top and the published copy of them is
 * underneath in the same place.
 *
 * *(That double-draw is worth watching. It is invisible today because both come
 * from the same geometry; if it ever shows as a subtle doubling on a translucent
 * highlighter, the fix is to render from the original prefix rather than the whole
 * file — `PdfReader.originalBytes` already returns it.)*
 *
 * ## The cache
 *
 * One page. Not an LRU: the editor shows one page at a time and a second entry
 * would double the memory for a hit rate of "the page you just left". A rendered
 * Letter page at [RENDER_WIDTH_PX] is about 5 MB, which is enough to be careful
 * with on the 4 GB device SPEC §10 names.
 *
 * The bitmap is **not** recycled on eviction. The dry layer may still be drawing it
 * this frame, and a recycled bitmap in `onDraw` is a crash rather than a blank page.
 * It is dropped and collected, which for one bitmap is the right trade.
 */
@Singleton
class SourcePages @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rasterizer: PdfPageRasterizer,
) {

    private data class Key(val id: DocumentId, val index: Int)

    private var cachedKey: Key? = null
    private var cached: Bitmap? = null

    /**
     * @return the rendered page, or null when this page has no source PDF behind it
     *   — a blank or templated page, or one whose file has gone. Null is a page with
     *   white paper, never a failure to open the note.
     */
    suspend fun render(id: DocumentId, background: Background, size: PageSize): Bitmap? {
        val index = (background as? Background.SourcePdfPage)?.index ?: return null
        val key = Key(id, index)
        cached?.takeIf { cachedKey == key && !it.isRecycled }?.let { return it }

        val file = File(context.filesDir, NoteLayout.documentPdf(id))
        // Width, not height: page space is the CropBox with /Rotate applied (SPEC §4.4),
        // so a landscape page is wider than it is tall and sizing from the width keeps
        // the pixel density comparable either way.
        val widthPx = renderWidthFor(size)
        val bitmap = rasterizer.rasterize(file, index, widthPx, size) ?: return null

        cachedKey = key
        cached = bitmap
        return bitmap
    }

    /** Dropped when the editor leaves, so a closed note does not hold megabytes open. */
    fun clear() {
        cachedKey = null
        cached = null
    }

    /**
     * Enough pixels that the page is sharp at a normal reading zoom without being a
     * texture the GPU has to think about. Capped, because a poster-sized page in a
     * PDF is a legal page size and would otherwise be rendered at its full extent.
     */
    private fun renderWidthFor(size: PageSize): Int =
        (size.widthPt * RENDER_SCALE).roundToInt().coerceIn(MIN_RENDER_WIDTH_PX, RENDER_WIDTH_PX)

    private companion object {
        const val RENDER_SCALE = 2.5f
        const val RENDER_WIDTH_PX = 2400
        const val MIN_RENDER_WIDTH_PX = 320
    }
}
