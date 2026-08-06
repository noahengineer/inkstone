package dev.inkstone.core.storage

import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FileSystem
import dev.inkstone.core.model.PageId

/**
 * SPEC §5.7 — thumbnails on disk. **Not in the bundle, and never read from inside one.**
 *
 * > Rendering a 500-note library must not mean opening 500 PDFs and extracting 500 ZIPs —
 * > M3's 60 fps target is unreachable that way.
 *
 * So a thumbnail is a file at a path derived from the note's id, and reading one is a
 * single `read` of a few kilobytes. Nothing here opens a note.
 *
 * ## What this class is not
 *
 * It does not decide *when* a thumbnail is stale and it does not render one. Freshness is
 * `NoteSummary.hasFreshThumbnail` — the note's `localRev` against the `thumbnailRev` the
 * index recorded — because a revision is an answer and a file timestamp is a guess. And
 * rendering needs a `Canvas`, which is `:android:ink`'s (see
 * [dev.inkstone.core.model.PageThumbnailRenderer]).
 *
 * What is here is the file layer, and the one rule that has to hold across all of it:
 * **a stale thumbnail must be impossible to read, not merely unlikely.** [invalidate]
 * therefore deletes rather than overwrites, so a crash between "the note changed" and "the
 * thumbnail was regenerated" leaves a miss — which costs a render — rather than a hit on
 * the wrong picture, which the user would have no way to notice.
 */
public class ThumbnailCache(private val fs: FileSystem) {

    // -----------------------------------------------------------------------
    // Library thumbnails
    // -----------------------------------------------------------------------

    /** @return the encoded image, or null when there is none cached. */
    public suspend fun readCover(id: DocumentId): ByteArray? = readIfPresent(NoteLayout.coverThumbnail(id))

    public suspend fun writeCover(id: DocumentId, bytes: ByteArray) {
        fs.createDirectories(NoteLayout.THUMBNAIL_CACHE_DIR)
        fs.writeBytesAtomically(NoteLayout.coverThumbnail(id), bytes)
    }

    // -----------------------------------------------------------------------
    // Page thumbnails — ROADMAP M3's reorder UI, and M7's search jump
    // -----------------------------------------------------------------------

    public suspend fun readPage(id: DocumentId, pageId: PageId): ByteArray? =
        readIfPresent(NoteLayout.pageThumbnail(id, pageId))

    public suspend fun writePage(id: DocumentId, pageId: PageId, bytes: ByteArray) {
        fs.createDirectories(NoteLayout.pageThumbnailDir(id))
        fs.writeBytesAtomically(NoteLayout.pageThumbnail(id, pageId), bytes)
    }

    // -----------------------------------------------------------------------
    // Invalidation and eviction
    // -----------------------------------------------------------------------

    /**
     * Drop everything cached for one note — the cover and every page.
     *
     * Called on save (SPEC §5.7: *"invalidated on materialize"*) and on trash. Whole-note
     * and not per-page on purpose: the index tracks freshness at note grain, because a page
     * edit bumps the note's `localRev` and there is no cheaper signal that says *which*
     * page changed. Regenerating a handful of page thumbnails on demand is cheaper than
     * carrying per-page revisions through the journal to avoid it.
     */
    public suspend fun invalidate(id: DocumentId) {
        deleteIfPresent(NoteLayout.coverThumbnail(id))
        deleteIfPresent(NoteLayout.pageThumbnailDir(id))
    }

    /**
     * SPEC §5.7 — *"LRU-evicted"*.
     *
     * The ordering comes from the library index (`thumbnailEvictionCandidates`), which
     * knows when each note was last modified. Eviction is unconditionally safe: a thumbnail
     * is a cache of something still on disk, so the worst case is a render.
     *
     * @return how many notes were evicted.
     */
    public suspend fun evict(ids: Collection<DocumentId>): Int {
        var evicted = 0
        for (id in ids) {
            if (!fs.exists(NoteLayout.coverThumbnail(id)) && !fs.exists(NoteLayout.pageThumbnailDir(id))) continue
            invalidate(id)
            evicted++
        }
        return evicted
    }

    /**
     * Delete cached images belonging to notes the index has never heard of.
     *
     * The one direction of drift the freshness rule cannot catch: a note purged from the
     * trash leaves no row to compare a revision against, so its thumbnails would sit there
     * forever. Run after a purge.
     *
     * @param known every note the library still knows about, trashed ones included.
     */
    public suspend fun sweepOrphans(known: Set<DocumentId>): Int {
        val live = known.mapTo(HashSet()) { it.value }
        var swept = 0
        for (name in fs.list(NoteLayout.THUMBNAIL_CACHE_DIR)) {
            val id = name.removeSuffix(NoteLayout.THUMBNAIL_EXTENSION)
            if (id in live) continue
            deleteIfPresent("${NoteLayout.THUMBNAIL_CACHE_DIR}/$name")
            swept++
        }
        return swept
    }

    private suspend fun readIfPresent(path: String): ByteArray? = if (fs.exists(path)) fs.readBytes(path) else null

    private suspend fun deleteIfPresent(path: String) {
        if (fs.exists(path)) fs.delete(path)
    }

    public companion object {
        /**
         * The library thumbnail's width in pixels.
         *
         * Sized for a grid cell on a tablet at 2× density with room to spare, so the image
         * is never upscaled. Bigger would not look better — the cell is the limit — and
         * would multiply 500 renders and 500 files by the square of the difference.
         */
        public const val COVER_WIDTH_PX: Int = 480

        /** Page thumbnails, for the reorder strip. Smaller: they are shown many at a time. */
        public const val PAGE_WIDTH_PX: Int = 240

        /**
         * How many notes keep their thumbnails.
         *
         * Above the 500-note library M3 is sized against, so the common case never evicts
         * at all; the bound exists for the library that keeps growing. At roughly 30 kB a
         * cover this is about 20 MB, which is a fair price for a library that scrolls.
         */
        public const val EVICTION_KEEP_NOTES: Int = 600
    }
}
