package dev.inkstone.android.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import dev.inkstone.core.model.Background
import dev.inkstone.core.model.DocumentId
import dev.inkstone.core.model.FoldedPage
import dev.inkstone.core.model.LamportClock
import dev.inkstone.core.model.PageOp
import dev.inkstone.core.model.PageThumbnailRenderer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * SPEC §5.7 — renders a folded page to a thumbnail. The `:android:ink` half of
 * [PageThumbnailRenderer].
 *
 * ## Why the library's *ink* comes from here and not from the PDF
 *
 * Because the PDF is the wrong source for it. SPEC §5.7 rules out reading a thumbnail from
 * inside the bundle inside the PDF — 500 notes would be 500 PDF opens and 500 ZIP
 * extractions — and rendering the *published* PDF instead would be no better: it is
 * materialized on close (SPEC §5.1) and may be one session stale, so a note edited and
 * killed would show the wrong picture with no way to tell. The folded page is the truth and
 * it is already in memory when a note is saved.
 *
 * ## The paper, however, does come from the PDF — corrected after M4
 *
 * An imported page's paper exists nowhere else. [Background.SourcePdfPage] is an *index*,
 * and the pixels it indexes are the front of `document.pdf` (SPEC §5.2), which is exactly
 * the part of that file the staleness argument above does not apply to: the original bytes
 * are verbatim and never rewritten, so rendering page *n* of it is the imported page no
 * matter how stale the ink around it is.
 *
 * Missing this is what M4 shipped: the library drew every imported note as blank paper with
 * ink floating on it, which reads as "the preview is broken" and was reported that way from
 * the tablet. [ThumbnailSourcePages] is the seam — `:android:ink` must not depend on
 * `:android:pdf`, so `:app` supplies the rasteriser, the same way [PagePainter] is handed a
 * bitmap rather than fetching one.
 *
 * ## Off the main thread, always
 *
 * CLAUDE.md rule 7. Tessellating a page of strokes into paths and encoding a WebP is
 * hundreds of milliseconds of work; ROADMAP M3's acceptance criterion is *"thumbnails
 * generate off the main thread; no jank while scrolling"*. [dispatcher] defaults to
 * [Dispatchers.Default] and is a parameter so a test can make it deterministic.
 *
 * ## Software canvas
 *
 * A `Bitmap`-backed [Canvas] is not hardware accelerated, so ink's `drawMesh` fast path is
 * unavailable and the renderer falls back to paths — which it decides per draw call, from
 * the canvas. Paths are slower per stroke and produce the same picture, which is what
 * matters here: the same [PagePainter] draws the editor.
 */
public class InkPageThumbnailRenderer(
    private val brushes: InkBrushFactory,
    private val sourcePages: ThumbnailSourcePages = ThumbnailSourcePages { _, _, _ -> null },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * ⭐ M8c — an inserted image's pixels (SPEC §4.9.4). The default answers null for everything,
     * so a caller with no asset cache gets the missing-asset placeholder rather than a picture that
     * silently is not there.
     *
     * ⚠️ **Called on [dispatcher], not on the main thread.** See [PageAssets] on what that requires
     * of an implementation.
     */
    private val assets: PageAssets = PageAssets { null },
) : PageThumbnailRenderer {

    override suspend fun render(noteId: DocumentId, page: FoldedPage, widthPx: Int): ByteArray? =
        withContext(dispatcher) {
            if (widthPx <= 0) return@withContext null
            val size = page.size
            val heightPx = (widthPx * size.heightPt / size.widthPt).roundToInt().coerceAtLeast(1)

            // Before the bitmap is allocated, because this is the one step that can be slow
            // and a failed raster is a thumbnail on white paper, not a failed thumbnail.
            val sourcePage = sourcePages.rasterize(noteId, page, widthPx)

            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                // Page points to thumbnail pixels. Uniform, taken from the width, so the aspect
                // ratio is the page's and a thumbnail is never letterboxed or squashed.
                val pageToThumbnail = Matrix().apply { setScale(widthPx / size.widthPt, widthPx / size.widthPt) }
                canvas.concat(pageToThumbnail)

                val painter = PagePainter()
                painter.assets = assets
                painter.paintBackground(canvas, size, page.background, sourcePage)
                // No culling: the whole page is "on screen" in a thumbnail by definition, and a
                // cull rect would only add a compare per stroke.
                painter.paintObjects(canvas, toStrokeStore(page), pageToThumbnail, visibleBounds = null)
                painter.paintBorder(canvas, size)

                val out = ByteArrayOutputStream(ESTIMATED_BYTES)
                @Suppress("DEPRECATION") // WEBP_LOSSY is API 30+; WEBP itself is what's deprecated.
                if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, out)) return@withContext null
                out.toByteArray()
            } finally {
                // Explicit, not left to the collector: a 500-note library generates these in a
                // tight loop, and a megabyte of bitmap per note is enough to matter on the 4 GB
                // device SPEC §10 says to test on.
                bitmap.recycle()
            }
        }

    /**
     * The folded page as render state.
     *
     * Re-using [StrokeStore] rather than decoding here is the point: it already knows how
     * to turn an `AddObject` into a drawable stroke, how to apply an absolute erased region,
     * and — through [StrokeStore.forEachInRenderOrder] — that highlighters draw over ink.
     * A second decoder would be a second place for those three to be got wrong.
     *
     * The ops are synthetic and never leave this function. The store has no recorder, so
     * nothing here can reach the journal.
     *
     * ⚠️ **The `AddObject`s alone are not the page**, and this was wrong here for a milestone.
     * `SetTextContent` and `SetFormat` fold into **side tables**, not onto the payload (SPEC §4.8.2,
     * §4.9.2), so a store built from `page.objects` holds every shape unfilled, square-cornered and
     * unattached, and — since M8c — every text box in the default font. `InkStrokeOutliner` already
     * pushed the content register for exactly this reason and its comment says why; the thumbnail
     * did not, so the library's picture of a page disagreed with the editor's about precisely the
     * properties M8b added. Both registers are pushed now, after the adds so each lands on an
     * object that exists.
     */
    private fun toStrokeStore(page: FoldedPage): StrokeStore {
        val store = StrokeStore()
        val ops = ArrayList<PageOp>(page.objects.size)
        page.objects.mapTo(ops) { PageOp.AddObject(opId = "", clock = THUMBNAIL_CLOCK, obj = it) }
        for (obj in page.objects) {
            page.textOf(obj.id)?.let {
                ops += PageOp.SetTextContent(opId = "", clock = THUMBNAIL_CLOCK, target = obj.id, text = it)
            }
            page.formatOf(obj.id)?.let {
                ops += PageOp.SetFormat(opId = "", clock = THUMBNAIL_CLOCK, target = obj.id, record = it)
            }
        }
        store.applyOps(ops = ops, brushes = brushes, record = false)
        return store
    }

    private companion object {
        /**
         * WebP quality. 82 is where the artefacts stop being visible on ink at thumbnail
         * size; higher costs bytes for pixels nobody sees at 480 px wide.
         */
        const val QUALITY = 82

        /** Initial buffer for the encoder, so a typical thumbnail does not grow it twice. */
        const val ESTIMATED_BYTES = 32 * 1024

        /** These ops exist for the duration of one render and are never journaled. */
        val THUMBNAIL_CLOCK = LamportClock(0, "thumbnail")
    }
}

/**
 * The paper under a thumbnail's ink, for a page imported from a PDF.
 *
 * Declared here and implemented in `:app` for the reason SPEC §2.2 gives for every other
 * interface of this shape: rendering a PDF page is `:android:pdf`'s job (SPEC §6.1) and
 * `:android:ink` may not depend on it — the dependency direction is one-way and `:app` is
 * the only module that sees both.
 *
 * @return the rendered page at [widthPx], or null when [page] has no source PDF behind it
 *   — a blank or templated page, or one whose file has gone. **Null is white paper, never a
 *   missing thumbnail.** A note whose source went missing still has its ink, and the library
 *   still has to draw it.
 */
public fun interface ThumbnailSourcePages {
    public suspend fun rasterize(noteId: DocumentId, page: FoldedPage, widthPx: Int): Bitmap?
}
