package dev.inkstone.app.note

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dev.inkstone.android.ink.PageAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ⭐ M8c — the decoded pictures a page draws, kept by **asset name**.
 *
 * `:android:ink` is handed a lookup rather than a loader ([PageAssets]) because reading an asset
 * means opening a ZIP inside a PDF and decoding a WebP, neither of which may happen anywhere near
 * the ink path (CLAUDE.md rule 7). This is the other side of that seam: `:app` is the module that
 * can see both the bundle and the screen, so it owns the cache and its budget.
 *
 * ## ⚠️ Keyed by name, and nothing may evict on an object being deleted
 *
 * SPEC §4.9.4: *"an `ImageObject`'s asset string survives re-identification, so a duplicated note
 * and its original can name one entry. Nothing may ever delete an asset on the grounds that no
 * object in this note names it."* The same reasoning applies one level up, to the cache: two
 * objects on a page — a picture and its duplicate — name **one** entry, so a cache keyed by object
 * or swept when an object is deleted would blank the surviving copy. [LruCache] evicts by size and
 * by nothing else, which is exactly the policy this needs.
 *
 * ## Threading
 *
 * [PageAssets.bitmap] is called from `onDraw` on the UI thread **and** from
 * `InkPageThumbnailRenderer` on a background dispatcher, so the lookup has to be safe on both.
 * `LruCache` is internally synchronized; a lazily-populated `HashMap` would not be, and would fail
 * the way a concurrent map fails — rarely, and on somebody else's device.
 *
 * ## Why a missing name is not an error
 *
 * A null answer is ordinary. An image merged from a peer arrives as an object one poll before its
 * bytes do, and a note recovered from a stranger's PDF may name assets that were never in it.
 * `DryImage` draws a visible placeholder for a null, which is what turns SPEC §4.9.4's *"blank
 * rectangle"* into something a person can report.
 */
class PageAssetCache(private val source: suspend () -> Map<String, ByteArray>) : PageAssets {

    /**
     * Budgeted in bytes rather than in entries, because a page can hold one photograph or thirty
     * clipped diagrams and an entry count would be wrong for both.
     */
    private val bitmaps = object : LruCache<String, Bitmap>(BUDGET_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    override fun bitmap(asset: String): Bitmap? = bitmaps.get(asset)?.takeIf { !it.isRecycled }

    /**
     * Read and decode whatever the note now holds.
     *
     * Decoding on [Dispatchers.IO], and **every** name rather than the ones the current page
     * happens to reference: the map comes from one read of the bundle's central directory, and
     * deciding per page which entries to keep would mean re-reading it on every page turn.
     *
     * @return true when something new was decoded, so the caller can invalidate the surface. A
     *   picture that is decoded and never drawn is exactly the M6 inert-feature shape — the cache
     *   would be correct and the page would still be blank.
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        var changed = false
        for ((name, bytes) in source()) {
            if (bitmaps.get(name) != null) continue
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (decoded != null) {
                bitmaps.put(name, decoded)
                changed = true
            }
        }
        changed
    }

    private companion object {
        /**
         * 48 MB of decoded pixels — about a dozen images at M8c's 2,048 px cap.
         *
         * Chosen against `ImageDownscale.MAX_LONG_EDGE_PX` rather than against
         * `Runtime.maxMemory`: the cap is what bounds a single entry, so the budget is a count of
         * entries expressed in bytes, and a fraction of the heap would silently mean "two images"
         * on the 4 GB device SPEC §10 says to test on once the rest of the app has its share.
         */
        const val BUDGET_BYTES = 48 * 1024 * 1024
    }
}
