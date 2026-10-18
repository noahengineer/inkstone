package dev.inkstone.app.library

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.inkstone.android.ink.ThumbnailSourcePages
import dev.inkstone.android.pdf.OriginalPdfCache
import dev.inkstone.android.pdf.PdfPageRasterizer
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.storage.NoteLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SPEC §5.7 + §6.1 — the `:app` half of [ThumbnailSourcePages].
 *
 * ## Why this is not [dev.inkstone.app.note.SourcePages]
 *
 * The editor's cache holds exactly one page, deliberately: it shows one page at a time and a
 * second entry would double the memory for a hit rate of "the page you just left". A library
 * fling asks for a different note every few milliseconds, so sharing that cache would evict
 * the page the user is *writing on* to make room for a picture in a grid — and then evict
 * that again on the next cell. Two callers, two access patterns, no shared slot.
 *
 * ## And why this one has no cache at all
 *
 * Because the thing it produces is already cached, one layer up and in a cheaper form:
 * [Thumbnails] writes the finished WebP to `cache/thumbs/` and only calls the renderer on a
 * miss (SPEC §5.7). Caching the intermediate bitmap would be caching the input to something
 * whose output is cached — megabytes held to save work that is already not being done.
 *
 * Rendered at the thumbnail's own width rather than the editor's 2.5× reading scale: a cover
 * is 480 px wide and a Letter page rasterised for reading is 2400 px, so asking for the
 * larger one would be twenty-five times the pixels, every one of them thrown away by the
 * downscale.
 */
@Singleton
class ThumbnailSourcePagesImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rasterizer: PdfPageRasterizer,
) : ThumbnailSourcePages {

    private val originals = OriginalPdfCache(context.cacheDir)

    override suspend fun rasterize(noteId: DocumentId, page: FoldedPage, widthPx: Int): Bitmap? {
        val index = (page.background as? Background.SourcePdfPage)?.index ?: return null
        val published = File(context.filesDir, NoteLayout.documentPdf(noteId))
        // The original, not the published file, for the reason `OriginalPdfCache` gives: the
        // cover draws the ink on top too, and an erased stroke must not survive on the paper.
        val file = withContext(Dispatchers.IO) {
            runCatching { originals.paperFor(noteId, published) }.getOrDefault(published)
        }
        return rasterizer.rasterize(file, index, widthPx, page.size)
    }
}
