package dev.inkstone.android.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
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
 * ## Why the library's picture comes from here and not from the PDF
 *
 * Because the PDF is the wrong source. SPEC §5.7 rules out reading a thumbnail from inside
 * the bundle inside the PDF — 500 notes would be 500 PDF opens and 500 ZIP extractions —
 * and rendering the *published* PDF instead would be no better: it is materialized on
 * close (SPEC §5.1) and may be one session stale, so a note edited and killed would show
 * the wrong picture with no way to tell. The folded page is the truth and it is already in
 * memory when a note is saved.
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
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PageThumbnailRenderer {

    override suspend fun render(page: FoldedPage, widthPx: Int): ByteArray? = withContext(dispatcher) {
        if (widthPx <= 0) return@withContext null
        val size = page.size
        val heightPx = (widthPx * size.heightPt / size.widthPt).roundToInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            // Page points to thumbnail pixels. Uniform, taken from the width, so the aspect
            // ratio is the page's and a thumbnail is never letterboxed or squashed.
            val pageToThumbnail = Matrix().apply { setScale(widthPx / size.widthPt, widthPx / size.widthPt) }
            canvas.concat(pageToThumbnail)

            val painter = PagePainter()
            painter.paintBackground(canvas, size, page.background)
            // No culling: the whole page is "on screen" in a thumbnail by definition, and a
            // cull rect would only add a compare per stroke.
            painter.paintStrokes(canvas, toStrokeStore(page), pageToThumbnail, visibleBounds = null)
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
     * and — through [StrokeStore.forEachInRenderOrder] — that highlighters draw behind ink.
     * A second decoder would be a second place for those three to be got wrong.
     *
     * The ops are synthetic and never leave this function. The store has no recorder, so
     * nothing here can reach the journal.
     */
    private fun toStrokeStore(page: FoldedPage): StrokeStore {
        val store = StrokeStore()
        store.applyOps(
            ops = page.objects.map { PageOp.AddObject(opId = "", clock = THUMBNAIL_CLOCK, obj = it) },
            brushes = brushes,
            record = false,
        )
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
