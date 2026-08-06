package dev.inkstone.app.library

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.NoteSummary
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageThumbnailRenderer
import dev.inkstone.core.storage.ThumbnailCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SPEC §5.7 — the three tiers a thumbnail can come from, in order of cost.
 *
 * 1. **Memory.** An [ImageBitmap] already decoded. A grid cell scrolling back into view
 *    must not decode a WebP again — that is the jank ROADMAP M3's acceptance criterion is
 *    about.
 * 2. **Disk.** `cache/thumbs/…`. One read of a few kilobytes and a decode. **Never the
 *    bundle inside the PDF** (SPEC §5.7): 500 notes would be 500 PDF opens.
 * 3. **Render.** Fold the page and draw it. Only when there is no fresh cached image —
 *    which, after the first launch, is only for notes that have just changed.
 *
 * ## Freshness
 *
 * A cached image is fresh when [NoteSummary.hasFreshThumbnail] — the note's `localRev`
 * equals the `thumbnailRev` the index recorded. That check is why tier 2 is safe to trust:
 * every save clears `thumbnailRev` in the same statement that bumps `localRev`, so a stale
 * image is a miss rather than a wrong picture.
 *
 * ## Why generation is bounded
 *
 * A fling through a 500-note library touches hundreds of cells in a second. Without
 * [renderPermits], each would open a note, fold a page and tessellate its strokes — several
 * hundred concurrent note opens, on a device SPEC §10 says must not need a flagship. Two at
 * a time keeps the visible cells filling promptly and the rest queued; the user sees
 * placeholders resolve rather than a stalled app.
 */
@Singleton
class Thumbnails @Inject constructor(private val cache: ThumbnailCache, private val renderer: PageThumbnailRenderer) {

    /**
     * Decoded images, bounded by **byte count**, not entry count.
     *
     * A cover at 480 px is roughly 1.2 MB decoded, so an entry-count bound would be a
     * memory bound only by accident — and page thumbnails are a quarter of the size, which
     * would make the accident worse in both directions.
     */
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_BUDGET_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * BYTES_PER_PIXEL
    }

    private val renderPermits = Semaphore(MAX_CONCURRENT_RENDERS)

    /**
     * The library thumbnail for [note], rendering it if there is not a fresh one.
     *
     * @param fold supplies the note's first page — a suspending call because it opens the
     *   note. Only invoked on a miss, which is the whole point of the two tiers above it.
     */
    suspend fun cover(note: NoteSummary, fold: suspend (DocumentId) -> FoldedPage?): ImageBitmap? {
        val key = note.id.value
        memory.get(key)?.let { return it }

        if (note.hasFreshThumbnail) {
            cache.readCover(note.id)?.let { bytes -> return decode(bytes)?.also { memory.put(key, it) } }
        }

        return renderPermits.withPermit {
            // Re-checked under the permit: while this call waited, the note it was queued
            // behind may have been the same note, and rendering it twice is pure waste.
            memory.get(key)?.let { return@withPermit it }
            val page = fold(note.id) ?: return@withPermit null
            val bytes = renderer.render(page, ThumbnailCache.COVER_WIDTH_PX) ?: return@withPermit null
            cache.writeCover(note.id, bytes)
            decode(bytes)?.also { memory.put(key, it) }
        }
    }

    /**
     * A page thumbnail, for the reorder overview (and M7's search jump).
     *
     * Generated on demand rather than with the note's cover: a 200-page notebook would be
     * 200 renders on every save, for a screen the user may never open.
     */
    suspend fun page(id: DocumentId, pageId: PageId, fold: suspend () -> FoldedPage?): ImageBitmap? {
        val key = "${id.value}/${pageId.value}"
        memory.get(key)?.let { return it }
        cache.readPage(id, pageId)?.let { bytes -> return decode(bytes)?.also { memory.put(key, it) } }

        return renderPermits.withPermit {
            memory.get(key)?.let { return@withPermit it }
            val page = fold() ?: return@withPermit null
            val bytes = renderer.render(page, ThumbnailCache.PAGE_WIDTH_PX) ?: return@withPermit null
            cache.writePage(id, pageId, bytes)
            decode(bytes)?.also { memory.put(key, it) }
        }
    }

    /** Render and store a cover from a page already folded — the save path (SPEC §5.1). */
    suspend fun refreshCover(id: DocumentId, page: FoldedPage): Boolean {
        val bytes = renderer.render(page, ThumbnailCache.COVER_WIDTH_PX) ?: return false
        cache.writeCover(id, bytes)
        decode(bytes)?.let { memory.put(id.value, it) }
        return true
    }

    /**
     * Drop everything cached for a note, in memory and on disk.
     *
     * The memory tier is swept by prefix because page thumbnails are keyed under the note.
     * Rebuilding the whole snapshot to find them is affordable — this runs on save, not on
     * the ink path, and the map holds tens of entries.
     */
    suspend fun invalidate(id: DocumentId) {
        val prefix = id.value
        for (key in memory.snapshot().keys) {
            if (key == prefix || key.startsWith("$prefix/")) memory.remove(key)
        }
        cache.invalidate(id)
    }

    private suspend fun decode(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }

    private companion object {
        /**
         * Decoded-image budget. Enough for roughly two screens of a grid plus what is
         * either side of it, which is what a fling actually touches — sized the same way
         * `NoteSession`'s page cache is, and for the same reason.
         */
        const val MEMORY_BUDGET_BYTES = 48 * 1024 * 1024
        const val BYTES_PER_PIXEL = 4

        /** See the class KDoc: a fling must not become hundreds of concurrent note opens. */
        const val MAX_CONCURRENT_RENDERS = 2
    }
}
