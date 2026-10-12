package dev.inkstone.app.library

import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.inkstone.core.database.library.LibraryIndex
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.NoteSummary
import dev.inkstone.core.model.PageId
import dev.inkstone.core.model.PageThumbnailRenderer
import dev.inkstone.core.storage.ThumbnailCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
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
 * A cover is fresh when [NoteSummary.hasFreshThumbnail] — the note's `localRev` equals the
 * `thumbnailRev` the index recorded. That check is why tier 2 is safe to trust: every save
 * clears `thumbnailRev` in the same statement that bumps `localRev`, so a stale image is a
 * miss rather than a wrong picture.
 *
 * A **page** thumbnail is fresh when [pageRevisions] says it was rendered at the revision the
 * caller asked for. Same rule, different register, and it exists for the same reason: without
 * it, the only way to stop a page thumbnail going stale was to delete the note's whole page
 * directory on every autosave tick — which it was, every two seconds, for the life of every
 * writing session.
 *
 * ⚠️ **The invariant that makes both tiers safe is that a recorded revision is never *newer*
 * than the pixels.** Recording an older one costs a re-render; recording a newer one shows the
 * user a page they have since written on, and nothing about the app reveals it. Every writer
 * of `localRev` records it in the index *after* the bytes are on disk (`NoteStore.appendOps`,
 * then `LibraryRepository.noteSaved`), so a revision read *before* a fold is always a lower
 * bound on what that fold saw. [storeCover] is where this is spent.
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
class Thumbnails @Inject constructor(
    private val cache: ThumbnailCache,
    private val renderer: PageThumbnailRenderer,
    /**
     * Where SPEC §5.7's `thumbnailRev` is written.
     *
     * ⚠️ **Here rather than in [LibraryRepository] on purpose.** `thumbnailRev` and the WebP on
     * disk are one fact — "this picture is of that revision" — and while they were two writes in
     * two classes only one of the two cover-writing paths made both of them. See [storeCover].
     */
    private val index: LibraryIndex,
) {

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
     * The revision each cached **page** thumbnail was rendered at — the page tier's `thumbnailRev`.
     *
     * Keyed the same way the memory tier is, and it governs *both* the decoded image and the file
     * on disk: a page image is served only when this map agrees with the revision the caller
     * named. That is what replaced deleting the note's page directory on every autosave tick.
     *
     * ⚠️ **In memory, and therefore empty after a restart — deliberately.** A page WebP carries no
     * revision anywhere, so a file found on disk by a *fresh process* cannot be shown to belong to
     * the note's current contents. It usually does; but a process killed between
     * `LibraryIndex.recordSave` and the render that follows it leaves one that does not, and the
     * old code read that file blindly. The cost of distrusting it is one render per page per
     * process, only for a sheet the user actually opens; the cost of trusting it is a wrong
     * picture nobody can spot. The **cover** keeps a durable guard — `thumbnailRev` is a column —
     * which is why the cover's disk tier survives a restart and this one does not.
     *
     * One `Long` per page ever thumbnailed, pruned per note by [invalidate]. It holds revisions,
     * never pictures; the pictures are bounded by [memory] and by the cache directory.
     */
    private val pageRevisions = ConcurrentHashMap<String, Long>()

    /**
     * The library thumbnail for [note], rendering it if there is not a fresh one.
     *
     * @param fold supplies the note's first page — a suspending call because it opens the
     *   note. Only invoked on a miss, which is the whole point of the two tiers above it.
     */
    suspend fun cover(note: NoteSummary, fold: suspend (DocumentId) -> FoldedPage?): ImageBitmap? = orPlaceholder {
        val key = note.id.value
        memory.get(key)?.let { return@orPlaceholder it }

        if (note.hasFreshThumbnail) {
            cache.readCover(note.id)?.let { bytes -> return@orPlaceholder decode(bytes)?.also { memory.put(key, it) } }
        }

        renderPermits.withPermit {
            // Re-checked under the permit: while this call waited, the note it was queued
            // behind may have been the same note, and rendering it twice is pure waste.
            memory.get(key)?.let { return@withPermit it }
            // ⛔ **Off the main thread.** The caller is a tile's `LaunchedEffect`, which runs on the
            // UI dispatcher, and the fold opens the note, CRCs the bundle, parses the whole page
            // JSON and folds every op. For the chemistry revision note that is 7.4 MB of JSON and
            // 5,133 ops, and an emulator walk logged *"Skipped 5409 frames"* — a 90-second freeze
            // of the whole library — while two such covers were drawn. The permit above never
            // protected the UI thread: it bounds how many folds run, not where.
            val page = withContext(Dispatchers.Default) { fold(note.id) } ?: return@withPermit null
            val bytes = renderer.render(note.id, page, ThumbnailCache.COVER_WIDTH_PX) ?: return@withPermit null
            // [note] is an immutable snapshot taken before the permit and before the fold, so
            // `note.localRev` cannot be "the note's revision now" however this is edited later.
            // [storeCover] explains why that distinction is the whole of the correctness argument.
            storeCover(note.id, note.localRev, bytes)
            decode(bytes)?.also { memory.put(key, it) }
        }
    }

    /**
     * ⛔ **A thumbnail that cannot be drawn is a placeholder, never a crash.**
     *
     * Every caller is a composition effect, and an exception escaping a `LaunchedEffect` is fatal
     * to the process — Compose rethrows it. So a note whose bundle is being rewritten, damaged, or
     * deleted between the index read and the fold used to take the whole app down from a tile
     * nobody was looking at: the 2026-09-24 crash, *"Bundle entry … has no local header"*, came out
     * of exactly this path while the user was writing in the editor on top of it. Nothing is
     * recorded on failure, so the next composition simply tries again.
     *
     * Cancellation is rethrown: a tile scrolled away must stop, not report an empty picture.
     */
    @Suppress("TooGenericExceptionCaught") // The point: any failure to draw a tile is a placeholder.
    private inline fun orPlaceholder(block: () -> ImageBitmap?): ImageBitmap? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Log.w(TAG, "thumbnail not drawn", failure)
        null
    }

    /**
     * A page thumbnail, for the reorder overview (and M7's search jump).
     *
     * Generated on demand rather than with the note's cover: a 200-page notebook would be
     * 200 renders on every save, for a screen the user may never open.
     *
     * @param revision the note's `localRev` that [fold] will produce this page from, or **null**
     *   when the caller cannot say. Null reads nothing from either cache and records nothing:
     *   a cached page with no revision beside it is exactly the stale picture [pageRevisions]
     *   exists to prevent, and a render whose revision is unknown must not make one look fresh.
     */
    suspend fun page(id: DocumentId, pageId: PageId, revision: Long?, fold: suspend () -> FoldedPage?): ImageBitmap? =
        orPlaceholder {
            val key = pageKey(id, pageId)
            if (isPageFresh(key, revision)) {
                memory.get(key)?.let { return@orPlaceholder it }
                cache.readPage(id, pageId)?.let { bytes ->
                    return@orPlaceholder decode(bytes)?.also { memory.put(key, it) }
                }
            }

            renderPermits.withPermit {
                if (isPageFresh(key, revision)) memory.get(key)?.let { return@withPermit it }
                // Off the main thread for [cover]'s reason; the fold here is the open editor's session.
                val page = withContext(Dispatchers.Default) { fold() } ?: return@withPermit null
                val bytes = renderer.render(id, page, ThumbnailCache.PAGE_WIDTH_PX) ?: return@withPermit null
                cache.writePage(id, pageId, bytes)
                // Recorded next to the write, and *removed* when the revision is unknown — otherwise a
                // later known-revision read would trust a file this call wrote blind.
                if (revision != null) {
                    pageRevisions[key] = revision
                } else {
                    pageRevisions.remove(key)
                }
                decode(bytes)?.also { memory.put(key, it) }
            }
        }

    private fun isPageFresh(key: String, revision: Long?): Boolean = revision != null && pageRevisions[key] == revision

    private fun pageKey(id: DocumentId, pageId: PageId): String = "${id.value}/${pageId.value}"

    /**
     * A folder's imported cover image.
     *
     * Cached in the same memory tier as thumbnails and **downsampled on decode**. A picked
     * photo is routinely 12 megapixels, which is 48 MB decoded at full size — enough to OOM
     * the 4 GB device SPEC §10 says to test on, for a picture that is drawn 180 dp wide.
     *
     * @param load reads the stored bytes. Only called on a miss.
     */
    suspend fun folderImage(assetPath: String, load: suspend () -> ByteArray?): ImageBitmap? = orPlaceholder {
        val key = "folder-image:$assetPath"
        memory.get(key)?.let { return@orPlaceholder it }
        renderPermits.withPermit {
            memory.get(key)?.let { return@withPermit it }
            val bytes = load() ?: return@withPermit null
            decodeDownsampled(bytes, ThumbnailCache.COVER_WIDTH_PX)?.also { memory.put(key, it) }
        }
    }

    /** Forget a decoded folder image — after the user replaces or clears it. */
    fun invalidateFolderImage(assetPath: String) {
        memory.remove("folder-image:$assetPath")
    }

    /**
     * Render and store a cover from a page already folded — the materialize path (SPEC §5.1).
     *
     * @param localRev the revision [page] was folded from. The caller has it because the fold and
     *   the revision came out of the same `NoteSession` close; see [storeCover] for why it must be
     *   that one and not whatever the index says by the time the render finishes.
     */
    suspend fun refreshCover(id: DocumentId, localRev: Long, page: FoldedPage) {
        val bytes = renderer.render(id, page, ThumbnailCache.COVER_WIDTH_PX) ?: return
        storeCover(id, localRev, bytes)
        decode(bytes)?.let { memory.put(id.value, it) }
    }

    /**
     * [refreshCover], queued rather than awaited — for the editor's materialize.
     *
     * ⛔ **The editor used to wait for this render while holding its lock.** `NoteEditor`'s
     * materialize calls `LibraryRepository.noteMaterialized` under `serial`, and a cover render
     * re-tessellates every stroke on the page: seconds for the chemistry note's 2,097 objects, every
     * ~30 s of writing on a synced note. For all of it the 2-second autosave could not run — it
     * takes the same lock — so new ink sat in memory and in no journal, and undo and page turns
     * queued and then landed together. The render needs nothing the lock protects: [page] is an
     * immutable fold taken under it, and [storeCover] is written for a render that finishes after
     * the note has moved on.
     *
     * **One queue, in order**, not a launch per call: two renders of one note racing would let the
     * older one's WebP land after the newer one's revision was recorded — a fresh-looking picture of
     * a page the user has since written on, which is the one outcome [storeCover]'s invariant exists
     * to rule out.
     */
    fun refreshCoverLater(id: DocumentId, localRev: Long, page: FoldedPage) {
        coverRefreshes.trySend(CoverRefresh(id, localRev, page))
    }

    private class CoverRefresh(val id: DocumentId, val localRev: Long, val page: FoldedPage)

    private val coverRefreshes = Channel<CoverRefresh>(Channel.UNLIMITED).also { queue ->
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            for (request in queue) {
                orPlaceholder {
                    refreshCover(request.id, request.localRev, request.page)
                    null
                }
            }
        }
    }

    /**
     * ⭐ Write a cover **and record the revision it was rendered from**, as one step.
     *
     * ⚠️ **These were two writes in two classes, and [cover] only ever made the first.** Tier 2 is
     * gated on `NoteSummary.hasFreshThumbnail`, which is `thumbnailRev == localRev`, and the only
     * caller of `LibraryIndex.recordThumbnail` was the materialize path — so a WebP written by an
     * on-demand render could never be read back by the on-demand render that wrote it. The disk
     * tier it populated was dead, and every visit to the library re-rendered every cover from
     * scratch: measured at 21 s for one 1000-stroke page on an emulator, two notes at a time.
     * Both cover-writing paths go through here now, and it is the only mention of
     * `cache.writeCover` in the app — so "wrote a cover and forgot to date it" has no call site
     * left to happen at.
     *
     * @param renderedAtRev the revision the **pixels** belong to, read *before* the fold.
     *
     *   ⚠️ Never re-read it afterwards. A render waits for a permit — up to the length of every
     *   render queued ahead of it — and then folds a page, and a save can land in either window.
     *   `recordSave` clears `thumbnailRev` in the same statement that bumps `localRev` (SPEC §5.7),
     *   so writing the *newer* number here would resurrect, as fresh, a picture that save had just
     *   invalidated — and it would be a picture of the page before the stroke that caused the save.
     *   Writing an older number can only ever produce a miss, which costs a render.
     *
     *   The reason an older number is *always* what happens rather than a wrong one is that the
     *   index's `localRev` is never ahead of what a fold from disk sees: every writer of it records
     *   it after the bytes are written. So this is a lower bound on the pixels, by construction.
     */
    private suspend fun storeCover(id: DocumentId, renderedAtRev: Long, bytes: ByteArray) {
        cache.writeCover(id, bytes)
        index.recordThumbnail(id, renderedAtRev)
    }

    /**
     * ⭐ The whole invalidation an **autosave** owes SPEC §5.7 — and it touches no file.
     *
     * ⚠️ **This used to be [invalidate], on every two-second autosave tick.** That deleted the
     * note's cover *and recursively deleted its entire page-thumbnail directory*, so while the user
     * was writing, the page thumbnails of the note they were writing in were destroyed every two
     * seconds and the in-note page overview could never hit a cache at all.
     *
     * It bought nothing. A save clears `thumbnailRev` in the same statement that bumps `localRev`,
     * so the cover file on disk is already unreadable — `hasFreshThumbnail` is false and tier 2 is
     * skipped — and it is overwritten in place by the next render rather than accumulating. Page
     * thumbnails are covered by [pageRevisions] for the same reason. What is left is the one thing
     * with no revision beside it: the **decoded cover in memory**, which [cover] reads before it
     * checks freshness at all. Dropping that one entry is a map removal.
     *
     * Decoded *page* images are deliberately left where they are: they cannot be served, because
     * the revision that would unlock them no longer matches, and they cost nothing but LRU space
     * they will lose on their own.
     */
    fun forgetDecodedCover(id: DocumentId) {
        memory.remove(id.value)
    }

    /**
     * Drop everything cached for a note, in memory and on disk.
     *
     * For the events that genuinely destroy the pictures rather than merely date them — a
     * materialize, a merge, a trash, a purge. **Not** an autosave: that is [forgetDecodedCover],
     * and the difference is a recursive directory delete every two seconds.
     *
     * [pageRevisions] doubles as the registry of which page images this process cached, which is
     * why the memory tier can be swept precisely instead of by rebuilding a whole `snapshot()`.
     */
    suspend fun invalidate(id: DocumentId) {
        forgetDecodedCover(id)
        for (key in pageRevisions.keys.filter { it.startsWith("${id.value}/") }) {
            pageRevisions.remove(key)
            memory.remove(key)
        }
        cache.invalidate(id)
    }

    private suspend fun decode(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }

    /**
     * Decode to roughly [targetWidthPx] wide, never larger.
     *
     * Two passes: `inJustDecodeBounds` reads the header only, then `inSampleSize` makes the
     * decoder skip pixels as it reads. That is the difference between allocating the full
     * image and allocating what is drawn — the decoder never holds the large bitmap at all,
     * so downscaling afterwards would not be the same thing.
     *
     * `inSampleSize` is rounded to a power of two by the decoder anyway, so the result is
     * *at least* the target width rather than exactly it.
     */
    private suspend fun decodeDownsampled(bytes: ByteArray, targetWidthPx: Int): ImageBitmap? =
        withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return@withContext null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidthPx) sample *= 2

            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
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

        const val TAG = "Thumbnails"
    }
}
