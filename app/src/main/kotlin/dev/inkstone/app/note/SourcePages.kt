package dev.inkstone.app.note

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.inkstone.android.pdf.OriginalPdfCache
import dev.inkstone.android.pdf.PdfPageRasterizer
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.PageSize
import dev.inkstone.core.storage.NoteLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * SPEC §6.2 — the paper under the ink, for a note imported from a PDF.
 *
 * ## ⛔ Why the source is the original, not `document.pdf`
 *
 * The imported PDF is the front of `document.pdf`, verbatim (SPEC §5.2), and the rest
 * of that file is the ink as of the last materialization. This class used to render the
 * whole file and call the result harmless, because the live strokes drew over their
 * published copy in the same place. That held until anything moved: every close baked
 * the note's ink and text into the paper, as a faint outline around text, and a moved or
 * erased object left its old copy on the paper where no tool could reach it. Seen on the
 * tablet, 2026-10-18. [OriginalPdfCache] returns the original prefix as a file of its own.
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

    private val originals = OriginalPdfCache(context.cacheDir)

    /**
     * ⚠️ [size] is part of the key because the bitmap's dimensions are derived from it at raster
     * time. Without it, a page whose extent changed under the same source index answered with the
     * previous bitmap and `PagePainter` stretched it into the new page rect — right paper, wrong
     * aspect, no error. Only reachable since `InkSurfaceView.setPageMeta` made an in-place size
     * change possible at all, which is exactly why it is worth naming here rather than discovering
     * later.
     */
    private data class Key(val id: DocumentId, val index: Int, val size: PageSize)

    private var cachedKey: Key? = null
    private var cached: Bitmap? = null

    /**
     * @return the rendered page, or null when this page has no source PDF behind it
     *   — a blank or templated page, or one whose file has gone. Null is a page with
     *   white paper, never a failure to open the note.
     */
    suspend fun render(id: DocumentId, background: Background, size: PageSize): Bitmap? {
        val index = (background as? Background.SourcePdfPage)?.index ?: return null
        val key = Key(id, index, size)
        cached?.takeIf { cachedKey == key && !it.isRecycled }?.let { return it }

        val published = File(context.filesDir, NoteLayout.documentPdf(id))
        // A failed copy falls back to the published file: old ink under the new is a blemish,
        // white paper under an imported lecture reads as the lecture being lost.
        val file = withContext(Dispatchers.IO) {
            runCatching { originals.paperFor(id, published) }.getOrDefault(published)
        }
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
