package dev.inkstone.app.note

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.inkstone.android.ink.PageAssets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ⭐ M8c — the decoded pictures a page draws, kept by **asset name**.
 *
 * `:android:ink` is handed a lookup rather than a loader ([PageAssets]) because reading an asset
 * means opening a ZIP inside a PDF and decoding a WebP, neither of which may happen anywhere near
 * the ink path (CLAUDE.md rule 7). This is the other side of that seam: `:app` is the module that
 * can see both the bundle and the screen, so it owns the cache and its budget.
 *
 * ## ⭐ Decoded on request, sized to fit — and why it changed
 *
 * The first version decoded the note's **whole** `assets/` family into a 48 MB `LruCache` on every
 * revision. 48 MB is four 2,048-pixel photographs, so on a page of eleven, seven drew the crossed
 * missing-picture box, and every edit re-decoded them — see [PictureCache] for the whole story and
 * the policy that replaced it. In short:
 *
 * - [bitmap] answers what is held and **asks for** what is not; the painter's own requests are the
 *   source of truth for what a page needs, which makes a picture inserted, undone or merged in
 *   appear without anything having to remember to refresh it.
 * - [beginPage] is told a page's pictures as its load starts (`InkSurfaceView.onPagePictures`), so
 *   they decode while the strokes are being built.
 * - Decodes run one batch at a time on [Dispatchers.IO], and [onChanged] is told **once per batch**
 *   — each notification is a full re-record of the page, which on a 4,000-object page is the
 *   expensive thing, so eleven pictures must not cost eleven.
 * - [isPending] lets the painter draw a plain wash, not a fault marker, while a decode is in flight.
 *
 * ## ⚠️ Keyed by name, and nothing may evict on an object being deleted
 *
 * SPEC §4.9.4: *"an `ImageObject`'s asset string survives re-identification, so a duplicated note
 * and its original can name one entry. Nothing may ever delete an asset on the grounds that no
 * object in this note names it."* The same reasoning applies one level up, to the cache: two
 * objects on a page — a picture and its duplicate — name **one** entry, so a cache keyed by object
 * would blank the surviving copy. An asset's bytes never change under its name (the name embeds a
 * UUIDv7), so a held picture is never stale either.
 *
 * ## Threading
 *
 * [bitmap] is called from `onDraw` — the dry layer's recording — on the UI thread, while a decode
 * reports in from an I/O thread. [PictureCache] is synchronized; [onChanged] is always invoked on
 * the main thread.
 *
 * @param scope the editor's scope; decodes stop with it.
 * @param source the bytes for a set of asset names — [NoteEditor.assets]. A name with no bytes is
 *   absent from the answer, and is asked for again only after [retryMissing].
 */
class PageAssetCache(
    private val scope: CoroutineScope,
    private val source: suspend (Set<String>) -> Map<String, ByteArray>,
    budgetBytes: Long = defaultBudgetBytes(),
) : PageAssets {

    private val pictures = PictureCache<Bitmap>(
        budgetBytes = budgetBytes,
        sizeOf = { it.allocationByteCount.toLong() },
        isUsable = { !it.isRecycled },
    )

    private val draining = AtomicBoolean(false)

    /**
     * Told — on the main thread — when a batch of pictures has been decoded, so the surface can
     * re-record with them. Null draws nothing new until the next frame happens to ask.
     */
    @Volatile
    var onChanged: (() -> Unit)? = null

    override fun bitmap(asset: String): Bitmap? {
        val held = pictures.get(asset)
        if (held == null) drain()
        return held
    }

    override fun isPending(asset: String): Boolean = pictures.isPending(asset)

    /** A page load has started and draws [assets]. See [PictureCache.beginPage]. */
    fun beginPage(assets: Set<String>) {
        pictures.beginPage(assets)
        drain()
    }

    /**
     * The note has a new revision, so a picture whose bytes were missing may have them now — an
     * image merged from a peer arrives as an object one poll before its bytes do. Cheap when nothing
     * is missing, which is almost always.
     */
    fun retryMissing() {
        if (pictures.retryAbsent()) drain()
    }

    /**
     * Start decoding whatever is wanted, unless a decode is already running — it will pick the new
     * names up before it stops. The re-check after the flag drops closes the window where a name is
     * wanted between the last [PictureCache.takeWanted] and the flag being cleared.
     */
    private fun drain() {
        if (!pictures.hasWanted() || !draining.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val batch = pictures.takeWanted()
                    if (batch.isEmpty()) break
                    decode(batch)
                }
            } finally {
                draining.set(false)
            }
            if (pictures.hasWanted()) drain()
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun decode(batch: Set<String>) {
        val bytes = try {
            source(batch)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: Exception) {
            // Reported to the cache as absent, below, and retried on the next revision.
            emptyMap()
        }
        val sample = pictures.sampleSizeFor(bytes.values.map(::fullSizeOf))
        var any = false
        for (name in batch) {
            val decoded = bytes[name]?.let { decodeAt(it, sample) }
            pictures.decoded(name, decoded)
            if (decoded != null) any = true
        }
        if (any) withContext(Dispatchers.Main) { onChanged?.invoke() }
    }

    private fun fullSizeOf(bytes: ByteArray): Long {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth.coerceAtLeast(0).toLong() * bounds.outHeight.coerceAtLeast(0) * BYTES_PER_PIXEL
    }

    private fun decodeAt(bytes: ByteArray, sample: Int): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })

    internal companion object {
        /** `ARGB_8888`, which is what `BitmapFactory` decodes a photograph to. */
        private const val BYTES_PER_PIXEL = 4L

        private const val MIB = 1024L * 1024L
        private const val FLOOR_BYTES = 64L * MIB
        private const val CEILING_BYTES = 256L * MIB

        /** Three quarters of the heap limit — see [defaultBudgetBytes]. */
        private const val HEAP_SHARE_NUMERATOR = 3L
        private const val HEAP_SHARE_DENOMINATOR = 4L

        /**
         * Three quarters of the app's Java heap limit, between 64 and 256 MiB.
         *
         * ⚠️ **Not a fixed number, and not a small fraction**, and both mistakes have been made here.
         * A fixed 48 MB was four photographs (see the class KDoc). A small fraction of the heap was
         * rejected in M8c because it *"would silently mean two images on the 4 GB device"* — true of
         * an eighth. The heap limit is not what bitmap pixels count against (they are native memory
         * since Android 8), but it is the per-device figure the OEM sized to the RAM, which makes it
         * an honest proxy for how many pictures this device can afford. A page with more than that
         * is decoded smaller rather than partly ([PictureCache.sampleSizeFor]).
         *
         * ⚠️ Since 2026-10 the app declares `android:largeHeap`, so the limit read here is
         * `dalvik.vm.heapsize` (512m on the Galaxy Tab S9 FE+), not `heapgrowthlimit` (256m), and
         * the ceiling is what binds: 256 MiB, about twenty full-size photographs, where it was
         * 192 MiB. The manifest's comment explains the flag; nothing about it was chosen for this
         * cache.
         */
        fun defaultBudgetBytes(): Long =
            (Runtime.getRuntime().maxMemory() / HEAP_SHARE_DENOMINATOR * HEAP_SHARE_NUMERATOR)
                .coerceIn(FLOOR_BYTES, CEILING_BYTES)
    }
}
